package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.server.Event
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json

/** A single entity's current value as the runtime cares about it. */
case class EntityState(state: String, attributes: Map[String, Json]) {
  def slotValue(attribute: Option[String]): String =
    attribute match {
      case None => state
      case Some(a) =>
        attributes.get(a).map(StateStore.jsonToString).getOrElse("")
    }
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
    // The typed WS event carries only a few attributes; merge them over the
    // last known full attribute set so values like brightness from the seed
    // snapshot are not lost on a state change.
    val attrs = event.data.new_state.attributes
    val updates = List(
      "state_class" -> attrs.state_class,
      "unit_of_measurement" -> attrs.unit_of_measurement,
      "device_class" -> attrs.device_class,
      "friendly_name" -> attrs.friendly_name
    ).collect { case (k, Some(v)) => k -> Json.fromString(v) }.toMap

    ref.update { current =>
      val prevAttrs =
        current.get(entityId).map(_.attributes).getOrElse(Map.empty)
      current.updated(
        entityId,
        EntityState(StateStore.jsonToString(ns.state), prevAttrs ++ updates)
      )
    } *> topic.publish1(entityId).void
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
          jsonToString(docToJson(s.state)),
          unknown ++ typed
        )
      }.toMap
    }

  private def docToJson(d: smithy4s.Document): Json =
    api.DocumentJson.decoder.decode(d).toOption.getOrElse(Json.Null)
}
