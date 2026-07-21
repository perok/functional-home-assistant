package fh.view.testkit

import api.DocumentJson
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.client.CommandPhase.*
import api.homeassistant.ws.protocol.server.{Event, ResultContext}
import cats.effect.{IO, Resource}
import cats.effect.std.{Queue, QueueSource}
import cats.effect.kernel.Ref
import io.circe.Json
import io.circe.syntax.*
import perok.ha.GetStatesData
import smithy4s.{Document, Schema}

/** One recorded `call_service` invocation — what the dashboard sent back to HA
  * when a control was actuated.
  */
case class ServiceCall(
    domain: String,
    service: String,
    entityId: String,
    serviceData: Json
)

/** A stubbed Home Assistant that stands in for a live instance in end-to-end
  * tests.
  *
  * It stubs the SMALL low-level WebSocket API ([[HAWSApiLowLevel]] — the ONE
  * seam the whole `HomeAssistantApi` is built on via
  * [[api.homeassistant.HomeAssistantApi.fromWs]]), not the 18-method high-level
  * trait: the real `fromWs` wraps this, so consumers still get a genuine
  * `HomeAssistantApi[IO]` and the fake only has to answer the few WS commands
  * the runtime actually issues:
  *
  *   - `sendCommand(get_states)` returns the current fixture as an
  *     `/api/states` snapshot (the seed [[fh.view.runtime.StateStore]] reads on
  *     startup),
  *   - `subscribeStream(subscribe_events state_changed)` hands back a live
  *     queue that [[emit]] pushes `state_changed` events onto (the change feed
  *     the store's background fiber drains),
  *   - `subscribeStream(render_template)` answers the boot dump fetch
  *     (`DataDump.fetch`) with the raw dump derived from the same fixtures, so
  *     a Tier-A dashboard can be built through the REAL `prepareDumps` path,
  *     and
  *   - `sendCommand(call_service)` records the call for later assertion.
  *
  * Anything else raises `NotImplementedError`: not on the runtime hot path, so
  * an unexpected command is a loud test failure rather than a silent stub. This
  * lets a test drive the WHOLE loop — `HaFeed` -> `StateStore` -> `Server` ->
  * HTTP/SSE -> `call_service` — against static, in-repo state with a scripted
  * timeline, and no live HA.
  */
final class FakeHomeAssistant private (
    stateRef: Ref[IO, Map[String, FixtureEntity]],
    // One live queue per subscribed event type, created on first subscribe. Each
    // carries the raw event objects that type's `subscribe_events` yields (an
    // `Event` encoded to JSON for `state_changed`; arbitrary JSON for others —
    // exactly what the real low-level yields after extracting the `event`
    // field). A per-type Queue (not a fresh one per subscribe) is what makes a
    // subscription DURABLE across reconnects: a re-subscribe reads the SAME
    // queue, so an event pushed during the gap is buffered, not lost.
    queues: Ref[IO, Map[String, Queue[IO, Json]]],
    calls: Ref[IO, Vector[ServiceCall]]
) extends HAWSApiLowLevel[IO] {

  /** The persistent queue for one event type, created on first use. */
  private def queueFor(eventType: String): IO[Queue[IO, Json]] =
    queues.get.map(_.get(eventType)).flatMap {
      case Some(q) => IO.pure(q)
      case None    =>
        Queue
          .unbounded[IO, Json]
          .flatMap(q => queues.update(_.updated(eventType, q)).as(q))
    }

  // --- The WS commands the runtime uses, with real behaviour -----------------

  def sendCommand[Response](
      command: CommandPhase & CommandResponse.AsResult[Response]
  ): IO[Response] =
    command match {
      case _: `get_states` =>
        statesJson.asInstanceOf[IO[Response]]
      case cs: `call_service` =>
        calls
          .update(
            _ :+ ServiceCall(
              cs.domain,
              cs.service,
              cs.target.entity_id,
              cs.service_data
            )
          )
          .as(Json.obj())
          .asInstanceOf[IO[Response]]
      case _ => na
    }

  def subscribeStream[Result](
      msg: CommandPhase & CommandResponse.AsStream[Result]
  ): Resource[IO, QueueSource[IO, Result]] =
    msg match {
      case subscribe_events(Some(eventType)) =>
        // Both the store's state_changed feed and arbitrary rawEvents (the
        // registry watch) resolve to their persistent per-type queue.
        Resource.eval(
          queueFor(eventType).map(_.asInstanceOf[QueueSource[IO, Result]])
        )
      case _: render_template =>
        // `render_template` is HA's dump-fetch subscription; the runtime's
        // `templateFunc` takes exactly ONE result and releases (`DataDump.fetch`
        // at boot). Hand back a queue pre-loaded with the raw `{areas, floors,
        // entities}` dump derived from the SAME seeded fixtures `get_states`
        // serves — so a Tier-A dashboard authored against the dump and the live
        // state it renders come from one source and cannot drift. Encoded as a
        // JSON STRING, exactly as real HA renders a `| tojson` template (the
        // form `DataDump.parseIfString` must handle), so this path is faithful.
        Resource.eval(
          rawDump
            .flatMap(dump =>
              Queue
                .unbounded[IO, Json]
                .flatTap(_.offer(Json.fromString(dump.noSpaces)))
            )
            .map(_.asInstanceOf[QueueSource[IO, Result]])
        )
      case _ => naR
    }

  // The fake never "closes" — the never-closing `Connect` in `TestServer`
  // supplies the supervisor's `awaitClosed`; this is only here to satisfy the
  // trait.
  def awaitClosed: IO[Unit] = IO.never

  /** The fixture as one `get_states` JSON payload: the fixtures rendered to
    * `GetStatesData` and back through the same smithy schema the runtime
    * decodes with, so it round-trips exactly.
    */
  private def statesJson: IO[Json] =
    stateRef.get.map { current =>
      val list = current.values.toList.map(_.toGetStatesData)
      val doc =
        Document.Encoder
          .fromSchema(Schema.list(GetStatesData.schema))
          .encode(list)
      DocumentJson.decoder
        .decode(doc)
        .getOrElse(throw new IllegalStateException("fixture -> JSON failed"))
    }

  /** The fixture as one RAW `render_template` dump: the pre-transform
    * `{areas, floors, entities}` shape `DataDump.fetch` receives (entities as a
    * list of rows; no areas/floors, as the fixtures carry no `area_id`). Each
    * row is [[FixtureEntity.toDumpEntry]]'s value — the same row
    * `DataDump.transform` keys by `entity_id` — so `transform(rawDump)` is the
    * `@fh-home` dump a Tier-A entry is authored against.
    */
  private def rawDump: IO[Json] =
    stateRef.get.map { current =>
      Json.obj(
        "areas" -> Json.arr(),
        "floors" -> Json.arr(),
        "entities" -> Json.fromValues(
          current.values.toList.map(_.toDumpEntry._2)
        )
      )
    }

  // --- Test-driving surface (not part of the trait) --------------------------

  /** Apply one change over time: update the fixture and push the matching
    * `state_changed` event onto the feed, exactly as a real WS frame would. The
    * store's background fiber picks it up and re-renders dependents.
    */
  def emit(
      entityId: String,
      state: String,
      attributes: Map[String, Json] = Map.empty
  ): IO[Unit] =
    stateRef
      .modify { current =>
        val prev = current.getOrElse(
          entityId,
          FixtureEntity(entityId, "unknown", Map.empty)
        )
        val next = FixtureEntity(entityId, state, attributes)
        (current.updated(entityId, next), (prev, next))
      }
      .flatMap { case (prev, next) =>
        val event = Event(
          data = Event.EventData(
            entity_id = entityId,
            new_state = next.eventDataState,
            old_state = prev.eventDataState
          ),
          event_type = "state_changed",
          time_fired = "1970-01-01T00:00:00+00:00",
          origin = "LOCAL",
          context = ResultContext("test", None, None)
        )
        queueFor("state_changed").flatMap(_.offer(event.asJson))
      }

  /** Push a raw event of an arbitrary type onto its subscription queue — the
    * registry-watch analogue of [[emit]], used to drive a durable `rawEvents`
    * subscription (e.g. across a reconnect).
    */
  def pushRawEvent(eventType: String, payload: Json): IO[Unit] =
    queueFor(eventType).flatMap(_.offer(payload))

  /** Every `call_service` recorded so far, in order. */
  def recordedCalls: IO[Vector[ServiceCall]] = calls.get

  /** Forget every recorded call (per-test isolation). */
  def resetCalls: IO[Unit] = calls.set(Vector.empty)

  private def na: IO[Nothing] =
    IO.raiseError(
      new NotImplementedError("FakeHomeAssistant: unexpected WS command")
    )
  private def naR: Resource[IO, Nothing] = Resource.eval(na)
}

object FakeHomeAssistant {

  /** Build a fake seeded with the given entities. Unbounded event queue: tests
    * emit a handful of changes, never enough to matter.
    */
  def create(seed: List[FixtureEntity]): IO[FakeHomeAssistant] =
    for {
      stateRef <- Ref[IO].of(seed.map(e => e.entityId -> e).toMap)
      queues <- Ref[IO].of(Map.empty[String, Queue[IO, Json]])
      calls <- Ref[IO].of(Vector.empty[ServiceCall])
    } yield new FakeHomeAssistant(stateRef, queues, calls)
}
