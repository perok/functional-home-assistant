package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.server.Event
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json

/** A single entity's current value as the runtime cares about it.
  *
  * Carries its own `entityId` so the entity's identity (id and `domain`)
  * travels with its value — derived once at ingest from the fetched data, not
  * recomputed from the id on every render.
  */
case class EntityState(
    entityId: String,
    state: String,
    attributes: Map[String, Json]
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

/** The runtime single source of truth for all entity state.
  *
  * Seeded once from a full HA snapshot, then kept current by a background fiber
  * consuming the `state_changed` WebSocket stream. Every applied change is
  * published to `changes` so SSE connections can re-render dependent
  * components.
  */
class StateStore private (
    ref: Ref[IO, Map[String, EntityState]],
    topic: Topic[IO, StateChange]
) {

  def snapshot: IO[Map[String, EntityState]] = ref.get

  /** Stream of state changes (entity + its previous/current value). */
  def changes: Stream[IO, StateChange] = topic.subscribe(64)

  private[runtime] def applyEvent(event: Event): IO[Unit] = {
    val entityId = event.data.entity_id
    val ns = event.data.new_state
    // The WS event carries the FULL attribute set, so we replace wholesale —
    // every attribute stays live, matching the seed snapshot.
    update(
      EntityState(entityId, StateStore.jsonToString(ns.state), ns.attributes)
    )
  }

  /** Apply one entity's next state: store it and publish the change. The WS
    * ingest tail, and the test seam ([[StateStore.inMemory]]).
    *
    * Re-render/diff happens downstream; here we only publish when the entity's
    * state actually changed, so identical events don't churn the SSE stream.
    * The previous value rides along so a dynamic group can tell whether the
    * change crossed its membership boundary.
    */
  private[runtime] def update(next: EntityState): IO[Unit] =
    ref
      .modify { current =>
        val previous = current.get(next.entityId)
        if (previous.contains(next)) (current, None)
        else
          (
            current.updated(next.entityId, next),
            Some(StateChange(next.entityId, previous, next))
          )
      }
      .flatMap(_.fold(IO.unit)(change => topic.publish1(change).void))

  /** Re-fetch the full snapshot from HA and fold it into the store.
    *
    * The reconnect-recovery path: after the [[HaFeed]] supervisor
    * re-establishes a dropped connection, the store may have missed any number
    * of changes. This replays a fresh `/api/states` snapshot through
    * [[update]], which dedups unchanged entities (no churn) and publishes a
    * [[StateChange]] only for entities that actually changed or newly appeared
    * while we were away — so every connected browser catches up over its live
    * SSE stream without any per-client timestamp tracking. Entities that
    * VANISHED from HA during the outage are left in place (removal is rare and
    * not what a reconnect must heal).
    */
  def reseed(api: HomeAssistantApi[IO]): IO[Unit] =
    StateStore.seed(api).flatMap(_.values.toList.traverse_(update))

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

  /** The store's ONLY constructor: an empty, passive sink with no feed of its
    * own. It never subscribes `state_changed` itself — [[HaFeed]] is its single
    * driver, seeding it on connect and re-seeding ([[reseed]]) on every
    * reconnect, and draining the one live `state_changed` subscription into it
    * via [[applyEvent]]. Keeping the subscription out of the store is what
    * guarantees exactly one `state_changed` stream from Home Assistant no
    * matter how many consumers read the fan-out ([[changes]]); a store that
    * subscribed for itself would be a second stream waiting to happen.
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

  /** Full initial snapshot via the native `/api/states` endpoint (robust JSON;
    * the Jinja `tojson` path can 400 on non-serializable attribute values).
    */
  private def seed(api: HomeAssistantApi[IO]): IO[Map[String, EntityState]] =
    api.getStates.map { states =>
      states.map { s =>
        val typed = List(
          "friendly_name" -> s.attributes.friendly_name,
          "device_class" -> s.attributes.device_class
        ).collect { case (k, Some(v)) => k -> Json.fromString(v) }.toMap
        val unknown =
          s.attributes.unknown
            .getOrElse(Map.empty)
            .view
            .mapValues(docToJson)
            .toMap
        s.entity_id.value -> EntityState(
          s.entity_id.value,
          jsonToString(docToJson(s.state)),
          unknown ++ typed
        )
      }.toMap
    }

  private def docToJson(d: smithy4s.Document): Json =
    api.DocumentJson.decoder.decode(d).toOption.getOrElse(Json.Null)
}
