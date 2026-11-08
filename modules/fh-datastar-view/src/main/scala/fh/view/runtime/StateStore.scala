package fh.view.runtime

import api.homeassistant.ws.domain.EntitiesEvent
import cats.effect.IO
import cats.effect.kernel.Ref
import dev.cel.common.values.MutableMapValue
import fs2.{Chunk, Stream}
import fs2.concurrent.{SignallingRef, Topic}
import io.circe.Json

import java.time.Instant

case class EntityState(
    entityId: String,
    state: String,
    attributes: Map[String, Json],
    // So a reconnect's full set cannot clobber a fresher delta. `None` falls
    // back to value dedup.
    lastUpdated: Option[Instant] = None,
    // The store version at which this entity's content last moved — not the
    // store's version, which untouched entities do not carry. The render key's
    // stand-in for the value ([[Renderer.renderInputs]], ADR 0012).
    contentVersion: Long = 0L
) {

  val domain: String = entityId.takeWhile(_ != '.')

  val unavailable: Boolean = EntityState.unavailableStates(state)

  /** What CEL reads as `value.attr`, converted once per state. Rebuilt per
    * change on purpose: 3.4% of a signals tick (`RenderBench.resumeSignals`),
    * not worth carrying across one.
    *
    * Held as CEL's own map type, which cel-java 0.14.0 passes through, because
    * it converts a plain `java.util.Map` on every read of `attr` by walking
    * every attribute: a quarter of `RenderBench.page`, and more on a light with
    * a long `effect_list`. `MutableMapValue` is `@Internal`; should an upgrade
    * break it, `toJavaObject(attributes)` alone is the fallback, at that cost.
    * Upstream `main` stops the walk for `attr.x` but not for a bare `attr`
    * (`'k' in attr`). `TransformsSuite` pins that both read the same.
    */
  def javaAttributes: java.util.Map[Object, Object] =
    val built = javaAttrs
    if (built != null) built
    else
      val m = MutableMapValue.create(EntityState.toJavaObject(attributes))
      javaAttrs = m
      m

  // Not a lazy val so [[attribute]] can tell whether the map exists: a
  // condition reading one attribute of a fresh state would otherwise convert
  // every attribute (`RenderBench.conditionAttr`, +174%). A race builds it twice.
  @volatile private var javaAttrs: java.util.Map[Object, Object] | Null = null

  /** One attribute as [[javaAttributes]] holds it, `null` when absent. */
  def attribute(name: String): Any =
    val built = javaAttrs
    if (built != null) built.get(name)
    else attributes.get(name).fold(null: Any)(EntityState.toJava)

  /** CEL's `fh.Entity`, held here so an evaluation builds no struct. */
  lazy val celValue: Object = new Cel.EntityValue(this)
}

object EntityState {
  val unavailableStates: Set[String] = Set("unavailable", "unknown")

  /** A subject where there is none; nothing reads it. */
  val none: EntityState = EntityState("", "", Map.empty)

  // HA's compressed feed sends epoch seconds as a float.
  def fromEpoch(seconds: Double): Instant =
    Instant.ofEpochMilli(math.round(seconds * 1000d))
  def fromEpoch(seconds: Option[Double]): Option[Instant] =
    seconds.map(fromEpoch)

  /** Not newer counts as stale, which is what drops a reconnect's resent set.
    */
  def stale(next: EntityState, prev: EntityState): Boolean =
    (next.lastUpdated, prev.lastUpdated) match {
      case (Some(n), Some(o)) => !n.isAfter(o)
      case _                  => false
    }

  def sameContent(a: EntityState, b: EntityState): Boolean =
    a.state == b.state && a.attributes == b.attributes

  private[runtime] def toJavaObject(
      attrs: Map[String, Json]
  ): java.util.Map[String, Any] = {
    val m = new java.util.LinkedHashMap[String, Any](attrs.size)
    // Null dropped, so `'k' in attr` is false; a kept null makes CEL's index
    // throw.
    attrs.foreach {
      case (k, v) if !v.isNull => { m.put(k, toJava(v)); () }
      case _                   => ()
    }
    m
  }

  private def toJava(j: Json): Any =
    j.fold(
      null,
      b => b,
      n => n.toLong.map(l => l: Any).getOrElse(n.toDouble),
      s => s,
      arr => {
        val l = new java.util.ArrayList[Any](arr.size)
        arr.foreach(x => l.add(toJava(x)))
        l
      },
      obj => {
        val m = new java.util.LinkedHashMap[String, Any]()
        obj.toIterable.foreach { case (k, v) => m.put(k, toJava(v)) }
        m
      }
    )
}

/** One applied state change. A removal is one too, so whatever read the entity
  * moves before the registry watcher's rebuild lands.
  */
enum StateChange {
  def entityId: String

  case Moved(
      entityId: String,
      previous: Option[EntityState],
      current: EntityState
  )
  case Removed(entityId: String, previous: EntityState)
}

/** One version and the changes that made it. */
private[runtime] final case class Frame(
    changes: List[StateChange],
    state: StoreState
)

/** How incoming state combines with what is stored: HA's compressed feed sends
  * whole states (`a`), deltas (`c`) and removals (`r`).
  */
private[runtime] enum Ingest(val entityId: String) {
  case Replace(state: EntityState) extends Ingest(state.entityId)
  case Merge(id: String, delta: EntitiesEvent.Delta) extends Ingest(id)
  case Remove(id: String) extends Ingest(id)
}

/** One value, so state and version cannot be read torn. `version` bumps once
  * per batch that changed anything — one coalesced HA frame (ADR 0011).
  */
private[runtime] case class StoreState(
    entities: Map[String, EntityState],
    version: Long
)

/** All entity state, driven by [[HaFeed]]; each version is published as a
  * [[Frame]] for the per-slug recorders.
  */
class StateStore private (
    ref: Ref[IO, StoreState],
    topic: Topic[IO, Frame],
    seeded: SignallingRef[IO, Int]
) {

  def snapshot: IO[Map[String, EntityState]] = ref.get.map(_.entities)

  private[runtime] def current: IO[StoreState] = ref.get

  private[runtime] def version: IO[Long] = ref.get.map(_.version)

  /** The current version, then every one after it. Subscribed BEFORE the
    * current one is read: read apart, a version published between the two would
    * be skipped, and a fold over these would carry decisions it dirtied.
    *
    * '''Unbounded, for correctness''': `publish1` blocks on a full subscriber,
    * so one slow recorder would stall the feed for every dashboard. Sessions
    * never subscribe here, so a stalled browser cannot.
    */
  private[runtime] def frames: Stream[IO, Frame] =
    Stream.resource(topic.subscribeAwaitUnbounded).flatMap { later =>
      Stream.eval(ref.get).flatMap { now =>
        Stream.bracket(seeded.update(_ + 1))(_ => seeded.update(_ - 1)) >>
          Stream.emit(Frame(Nil, now)) ++
          later.filter(_.state.version > now.version)
      }
    }

  private[runtime] def applyEntities(frames: Chunk[EntitiesEvent]): IO[Unit] =
    update(frames.asSeq.flatMap(StateStore.ingests))

  private[runtime] def update(next: EntityState): IO[Unit] =
    update(List(Ingest.Replace(next)))

  /** One ref update per batch. An identical content is stored but not
    * published, and a batch with no changes keeps its version, so a reconnect's
    * resent full set costs no cursor, no publish and no recorder pass. Most of
    * that set never reaches `put`: HA resends the same `last_updated`, which
    * [[EntityState.stale]] drops.
    */
  private[runtime] def update(ingests: Iterable[Ingest]): IO[Unit] =
    // Uncancelable: the feed is interrupted on every narrowing and reconnect,
    // and a change stored but not published is lost for good, as the reseed
    // finds it already held. `publish1` cannot block, `frames` being unbounded.
    ref
      .modify { state =>
        val batch = state.version + 1
        val (updated, changes) =
          ingests.foldLeft((state.entities, List.empty[StateChange])) {
            case ((m, changes), ingest) =>
              def put(
                  value: EntityState,
                  previous: Option[EntityState]
              ) =
                // Stored and published with the same stamp, or a render would
                // be keyed to a version the snapshot never had.
                previous.filter(EntityState.sameContent(_, value)) match {
                  case Some(same) =>
                    (
                      m.updated(
                        value.entityId,
                        value.copy(contentVersion = same.contentVersion)
                      ),
                      changes
                    )
                  case None =>
                    val stamped = value.copy(contentVersion = batch)
                    (
                      m.updated(value.entityId, stamped),
                      StateChange.Moved(value.entityId, previous, stamped) ::
                        changes
                    )
                }

              val previous = m.get(ingest.entityId)
              ingest match {
                case Ingest.Remove(id) =>
                  previous.fold((m, changes))(p =>
                    (m - id, StateChange.Removed(id, p) :: changes)
                  )

                case Ingest.Replace(value) =>
                  if (previous.exists(EntityState.stale(value, _)))
                    (m, changes)
                  else put(value, previous)

                // Always newer than what we hold.
                case Ingest.Merge(_, delta) =>
                  previous.fold((m, changes))(prev =>
                    put(StateStore.merge(prev, delta), previous)
                  )
              }
          }
        // Still stored: a newer `lastUpdated` is what keeps a stale resend out.
        if (changes.isEmpty) (state.copy(entities = updated), None)
        else {
          val next = StoreState(updated, batch)
          (next, Some(Frame(changes.reverse, next)))
        }
      }
      .flatMap(_.fold(IO.unit)(topic.publish1(_).void))
      .uncancelable

  // Test seam: an update reaches a subscriber as a change only once it holds
  // its seed; counting at the topic, an update could land in the seed instead.
  private[runtime] def changeSubscribers: Stream[IO, Int] = seeded.discrete
}

object StateStore {

  // Null is absent, so slot defaults apply (brightness while a light is off).
  def jsonToString(json: Json): String =
    if (json.isNull) "" else json.asString.getOrElse(json.noSpaces)

  private[runtime] def ingests(event: EntitiesEvent): List[Ingest] =
    event.added.toList.map { (id, full) =>
      Ingest.Replace(
        EntityState(
          id,
          full.state,
          full.attributes,
          EntityState.fromEpoch(full.lastUpdated.orElse(full.lastChanged))
        )
      )
    } ++
      event.changed.toList.map(Ingest.Merge(_, _)) ++
      event.removed.map(Ingest.Remove(_))

  private[runtime] def merge(
      prev: EntityState,
      delta: EntitiesEvent.Delta
  ): EntityState = {
    val dropped = delta.minus.fold(List.empty[String])(_.attributes)
    val patched = delta.plus.fold(Map.empty[String, Json])(_.attributes)
    prev.copy(
      state = delta.plus.flatMap(_.state).getOrElse(prev.state),
      attributes = (prev.attributes -- dropped) ++ patched,
      lastUpdated = delta.plus
        .flatMap(p =>
          EntityState.fromEpoch(p.lastUpdated.orElse(p.lastChanged))
        )
        .orElse(prev.lastUpdated)
    )
  }

  /** A passive sink: [[HaFeed]] is its one driver, which keeps it to exactly
    * one state subscription to HA.
    */
  def empty: IO[StateStore] = inMemory(Map.empty)

  private[runtime] def inMemory(
      initial: Map[String, EntityState]
  ): IO[StateStore] =
    for {
      ref <- Ref[IO].of(StoreState(initial, 0L))
      topic <- Topic[IO, Frame]
      seeded <- SignallingRef[IO].of(0)
    } yield new StateStore(ref, topic, seeded)

}
