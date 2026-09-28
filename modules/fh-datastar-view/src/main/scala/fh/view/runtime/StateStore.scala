package fh.view.runtime

import api.homeassistant.ws.domain.EntitiesEvent
import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.{Chunk, Stream}
import fs2.concurrent.Topic
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

  def unavailable: Boolean = EntityState.unavailableStates(state)

  /** What CEL binds as `attr`, converted once per state. Rebuilt per change on
    * purpose: 3.4% of a signals tick (`RenderBench.resumeSignals`), not worth
    * carrying across one.
    */
  lazy val javaAttributes: java.util.Map[String, Any] =
    EntityState.toJavaObject(attributes)
}

object EntityState {
  val unavailableStates: Set[String] = Set("unavailable", "unknown")

  /** For `Conditions.matchesIn` where there is no subject; nothing reads it. */
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

/** One applied state change: the entity, its `previous` value (None if newly
  * seen), and its `current` value. `previous` is what lets a frame rebuild the
  * snapshot before it ([[Patches.beforeSnapshot]]), so membership is compared
  * before vs. after without the store keeping old snapshots.
  */
case class StateChange(
    entityId: String,
    previous: Option[EntityState],
    current: EntityState
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

/** All entity state, driven by [[HaFeed]]; each applied frame is published to
  * `changes` for the per-slug recorders.
  */
class StateStore private (
    ref: Ref[IO, StoreState],
    topic: Topic[IO, List[StateChange]]
) {

  def snapshot: IO[Map[String, EntityState]] = ref.get.map(_.entities)

  private[runtime] def current: IO[StoreState] = ref.get

  private[runtime] def version: IO[Long] = ref.get.map(_.version)

  /** One element per frame, because the version is per frame: per entity, the
    * diff pass saw N views of one instant and rebuilt "befores" that never
    * existed.
    *
    * '''Unbounded, for correctness''': `publish1` blocks on a full subscriber,
    * so one slow recorder would stall the feed for every dashboard. Sessions
    * never subscribe here, so a stalled browser cannot.
    */
  def changes: Stream[IO, List[StateChange]] = topic.subscribeUnbounded

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
    ref
      .modify { state =>
        val batch = state.version + 1
        val (updated, changes, removed) =
          ingests.foldLeft((state.entities, List.empty[StateChange], false)) {
            case ((m, changes, removed), ingest) =>
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
                      changes,
                      removed
                    )
                  case None =>
                    val stamped = value.copy(contentVersion = batch)
                    (
                      m.updated(value.entityId, stamped),
                      StateChange(value.entityId, previous, stamped) :: changes,
                      removed
                    )
                }

              val previous = m.get(ingest.entityId)
              ingest match {
                // No StateChange: a vanished entity changes what dashboards
                // were built from, which the registry watcher re-evaluates.
                // The version still moves: an `r` frame may have no registry
                // event behind it.
                case Ingest.Remove(id) =>
                  (m - id, changes, removed || m.contains(id))

                case Ingest.Replace(value) =>
                  if (previous.exists(EntityState.stale(value, _)))
                    (m, changes, removed)
                  else put(value, previous)

                // Always newer than what we hold.
                case Ingest.Merge(_, delta) =>
                  previous.fold((m, changes, removed))(prev =>
                    put(StateStore.merge(prev, delta), previous)
                  )
              }
          }
        val touched = changes.nonEmpty || removed
        (
          StoreState(
            entities = updated,
            version = if (touched) batch else state.version
          ),
          changes.reverse
        )
      }
      .flatMap(cs => IO.whenA(cs.nonEmpty)(topic.publish1(cs).void))

  // Test seam: a publish reaches only existing subscribers.
  private[runtime] def changeSubscribers: Stream[IO, Int] = topic.subscribers
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
      topic <- Topic[IO, List[StateChange]]
    } yield new StateStore(ref, topic)

}
