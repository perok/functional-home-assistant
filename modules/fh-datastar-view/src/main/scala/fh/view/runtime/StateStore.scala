package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.server.Event
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
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
}

object EntityState {
  val unavailableStates: Set[String] = Set("unavailable", "unknown")
}

/** The runtime single source of truth for all entity state.
  *
  * Seeded once from a full HA snapshot, then kept current by a background fiber
  * consuming the `state_changed` WebSocket stream. Every applied change is
  * published to `changes` so SSE connections can re-render dependent
  * components.
  */
class StateStore private (
    ref: Ref[IO, Map[String, EntityState]],
    topic: Topic[IO, String]
) {

  def snapshot: IO[Map[String, EntityState]] = ref.get

  /** Stream of entity ids whose state just changed. */
  def changes: Stream[IO, String] = topic.subscribe(64)

  private def applyEvent(event: Event): IO[Unit] = {
    val entityId = event.data.entity_id
    val ns = event.data.new_state
    // The WS event carries the FULL attribute set, so we replace wholesale —
    // every attribute stays live, matching the seed snapshot.
    val next =
      EntityState(entityId, StateStore.jsonToString(ns.state), ns.attributes)

    // Re-render/diff happens downstream; here we only publish when the entity's
    // state actually changed, so identical events don't churn the SSE stream.
    ref
      .modify { current =>
        if (current.get(entityId).contains(next)) (current, false)
        else (current.updated(entityId, next), true)
      }
      .flatMap(changed => IO.whenA(changed)(topic.publish1(entityId).void))
  }
}

object StateStore {

  // JSON null is treated as absent so slot defaults apply (e.g. brightness is
  // null when a light is off).
  def jsonToString(json: Json): String =
    if (json.isNull) "" else json.asString.getOrElse(json.noSpaces)

  /** Build the store: take a full snapshot, then run a background fiber that
    * keeps it current from the live `state_changed` stream.
    */
  def create(api: HomeAssistantApi[IO]): Resource[IO, StateStore] =
    for {
      initial <- seed(api).toResource
      ref <- Ref[IO].of(initial).toResource
      topic <- Topic[IO, String].toResource
      store = new StateStore(ref, topic)
      _ <- api
        .event(Some("state_changed"))
        .flatMap { queue =>
          Stream
            .repeatEval(queue.take)
            .evalMap(store.applyEvent)
            .compile
            .drain
            .background
        }
    } yield store

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
