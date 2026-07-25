package fh.view.runtime

import api.homeassistant.ws.domain.EntitiesEvent
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.concurrent.Topic
import io.circe.Json

import java.time.Instant

/** A single entity's current value as the runtime cares about it.
  *
  * Carries its own `entityId` so the entity's identity (id and `domain`)
  * travels with its value — derived once at ingest from the fetched data, not
  * recomputed from the id on every render.
  */
case class EntityState(
    entityId: String,
    state: String,
    attributes: Map[String, Json],
    // HA's `last_updated` for this state, parsed once. Drives recency: a full
    // state is applied only if it isn't older than the stored one, so a
    // reconnect's full set can't clobber a fresher delta. `None` when the frame
    // carried no timestamp; then updates fall back to value dedup.
    lastUpdated: Option[Instant] = None
) {

  /** The entity's domain, i.e. the entity-id prefix (`light.kitchen` ->
    * `light`) — the same value HA exposes as `state.domain`. A `val` so it is
    * computed once per state rather than re-derived per transform/predicate.
    */
  val domain: String = entityId.takeWhile(_ != '.')

  /** HA's non-value states: the entity has no real reading. A value-display
    * slot marked `bypassUnavailable` shows this verbatim instead of running its
    * transform — which would otherwise error (`$number("unavailable")`) or be
    * meaningless.
    */
  def unavailable: Boolean = EntityState.unavailableStates(state)

  /** The attributes as plain Java values for JSONata's `$attr.*` navigation,
    * converted **once per state version** and reused across every
    * slot/transform on this entity (a card with three `$attr` slots converts
    * the map once, not three times). A fresh `EntityState` is built on every
    * change, so this cache invalidates naturally. Numbers stay numeric (so
    * `$attr.brightness` arithmetic works), nested objects/arrays recurse, null
    * fields drop out.
    */
  lazy val javaAttributes: java.util.Map[String, Any] =
    EntityState.toJavaObject(attributes)
}

object EntityState {
  val unavailableStates: Set[String] = Set("unavailable", "unknown")

  /** HA's compressed feed timestamps are epoch SECONDS as a float; millisecond
    * resolution is more than recency needs.
    */
  def fromEpoch(seconds: Double): Instant =
    Instant.ofEpochMilli(math.round(seconds * 1000d))
  def fromEpoch(seconds: Option[Double]): Option[Instant] =
    seconds.map(fromEpoch)

  /** Stale iff both sides carry a timestamp and the incoming one is not newer —
    * a reseed snapshot racing a fresher live event. Missing timestamps fall
    * through (handled by value dedup at the call site).
    */
  def stale(next: EntityState, prev: EntityState): Boolean =
    (next.lastUpdated, prev.lastUpdated) match {
      case (Some(n), Some(o)) => !n.isAfter(o)
      case _                  => false
    }

  /** Same rendered content (ignoring timestamps), so a timestamp-only bump does
    * not publish a redundant change.
    */
  def sameContent(a: EntityState, b: EntityState): Boolean =
    a.state == b.state && a.attributes == b.attributes

  /** Convert a circe attribute map to a Java map for JSONata. Kept here (with
    * the cached [[EntityState.javaAttributes]]) rather than in [[Transform]],
    * so the conversion happens once per state, not once per transform
    * evaluation.
    */
  private[runtime] def toJavaObject(
      attrs: Map[String, Json]
  ): java.util.Map[String, Any] = {
    val m = new java.util.LinkedHashMap[String, Any](attrs.size)
    attrs.foreach { case (k, v) => m.put(k, toJava(v)) }
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
  * seen), and its `current` value. Carrying both lets a consumer decide whether
  * a change affects a data-dependent view (a dynamic group) by testing the
  * group's query against the before AND after state — so an add, a remove, or
  * an in-place update all register, while an unrelated entity is skipped,
  * without any per-consumer membership tracking.
  */
case class StateChange(
    entityId: String,
    previous: Option[EntityState],
    current: EntityState
)

/** One unit of incoming state, naming HOW it combines with what is stored —
  * because HA's compressed feed sends both whole states and partial deltas, and
  * the difference is not recoverable from an `EntityState` alone.
  */
private[runtime] enum Ingest(val entityId: String) {

  /** A complete state, replacing whatever is stored (the feed's `a` frames). */
  case Replace(state: EntityState) extends Ingest(state.entityId)

  /** A partial change to an entity we already hold: changed fields and changed
    * attributes only, so attributes MERGE (the feed's `c` frames).
    */
  case Merge(id: String, delta: EntitiesEvent.Delta) extends Ingest(id)

  /** The entity no longer exists (the feed's `r` frames). */
  case Remove(id: String) extends Ingest(id)
}

/** The runtime single source of truth for all entity state.
  *
  * Filled and kept current by a background fiber consuming HA's compressed
  * `subscribe_entities` feed ([[applyEntities]]) — full states first, deltas
  * after. Every applied change is published to `changes` so SSE connections can
  * re-render dependent components.
  */
class StateStore private (
    ref: Ref[IO, Map[String, EntityState]],
    topic: Topic[IO, StateChange]
) {

  def snapshot: IO[Map[String, EntityState]] = ref.get

  /** Stream of state changes (entity + its previous/current value). */
  def changes: Stream[IO, StateChange] = topic.subscribe(64)

  /** Apply a batch of `subscribe_entities` frames — a burst arrives as one
    * chunk and lands in one [[update]], so the ref is touched once per batch
    * rather than once per frame.
    */
  private[runtime] def applyEntities(frames: Chunk[EntitiesEvent]): IO[Unit] =
    update(frames.asSeq.flatMap(StateStore.ingests))

  private[runtime] def update(next: EntityState): IO[Unit] =
    update(List(Ingest.Replace(next)))

  /** Apply a batch of ingests in ONE ref update, publishing a [[StateChange]]
    * per entity whose content actually changed. The previous value rides along
    * so a dynamic group can tell whether the change crossed its membership
    * boundary.
    *
    * A newer-but-identical state is stored (to advance the timestamp) but not
    * published — only real content changes reach the SSE stream. That dedup is
    * what makes a RECONNECT cheap: the new subscription re-sends every entity
    * as a [[Ingest.Replace]], and only the ones that actually moved during the
    * outage produce a change, so every connected browser catches up over its
    * live SSE stream with no per-client tracking.
    */
  private[runtime] def update(ingests: Iterable[Ingest]): IO[Unit] =
    ref
      .modify { current =>
        val (updated, changes) =
          ingests.foldLeft((current, List.empty[StateChange])) {
            case ((m, changes), ingest) =>
              // Store `next` and publish it unless it is redundant.
              def put(
                  next: EntityState,
                  previous: Option[EntityState]
              ): (Map[String, EntityState], List[StateChange]) = {
                val m2 = m.updated(next.entityId, next)
                if (previous.exists(EntityState.sameContent(_, next)))
                  (m2, changes)
                else (m2, StateChange(next.entityId, previous, next) :: changes)
              }

              val previous = m.get(ingest.entityId)
              ingest match {
                // No StateChange: no rendered view survives its entity, and a
                // deletion also fires the registry event that reloads the
                // dashboard wholesale.
                case Ingest.Remove(id) => (m - id, changes)

                // A full state can be OLDER than what we hold (a reconnect's
                // full set racing a delta that already arrived), so it yields to
                // a fresher stored value.
                case Ingest.Replace(next) =>
                  if (previous.exists(EntityState.stale(next, _))) (m, changes)
                  else put(next, previous)

                // A delta only makes sense against a state we hold, and is
                // always newer than it — no recency check.
                case Ingest.Merge(_, delta) =>
                  previous.fold((m, changes))(prev =>
                    put(StateStore.merge(prev, delta), previous)
                  )
              }
          }
        (updated, changes.reverse)
      }
      .flatMap(_.traverse_(topic.publish1))

  /** Current number of `changes` subscribers, as a signal stream — a test seam
    * to await subscriptions deterministically (topic publishes reach only
    * already-subscribed consumers).
    */
  private[runtime] def changeSubscribers: Stream[IO, Int] = topic.subscribers
}

object StateStore {

  // JSON null is treated as absent so slot defaults apply (e.g. brightness is
  // null when a light is off).
  def jsonToString(json: Json): String =
    if (json.isNull) "" else json.asString.getOrElse(json.noSpaces)

  /** How one feed frame combines with the store: full states replace, deltas
    * merge, removals drop. Pure, so the whole translation is testable without a
    * socket.
    */
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

  /** Fold a delta into the state we hold: only the fields it carries move, its
    * attributes merge over the stored ones, and `-` attributes drop out.
    */
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

  /** The store's ONLY constructor: an empty, passive sink with no feed of its
    * own. It never subscribes for itself — [[HaFeed]] is its single driver,
    * draining the one live `subscribe_entities` subscription into it via
    * [[applyEntities]]. Keeping the subscription out of the store is what
    * guarantees exactly one state feed from Home Assistant no matter how many
    * consumers read the fan-out ([[changes]]); a store that subscribed for
    * itself would be a second stream waiting to happen.
    */
  def empty: IO[StateStore] = inMemory(Map.empty)

  /** A store seeded with `initial` and driven by explicit [[StateStore.update]]
    * calls — no feed. The test/seed seam behind [[empty]].
    */
  private[runtime] def inMemory(
      initial: Map[String, EntityState]
  ): IO[StateStore] =
    for {
      ref <- Ref[IO].of(initial)
      topic <- Topic[IO, StateChange]
    } yield new StateStore(ref, topic)

}
