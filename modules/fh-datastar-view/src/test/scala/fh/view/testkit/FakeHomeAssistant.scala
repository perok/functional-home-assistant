package fh.view.testkit

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.client.TriggerData
import api.homeassistant.ws.protocol.server.{Event, ResultContext}
import cats.effect.{IO, Resource}
import cats.effect.std.{Queue, QueueSource}
import cats.effect.kernel.Ref
import ha.runtime.definitions.{DeviceId, EntityId}
import io.circe.Json

/** One recorded `call_service` invocation — what the dashboard sent back to HA
  * when a control was actuated.
  */
final case class ServiceCall(
    domain: String,
    service: String,
    entityId: String,
    serviceData: Json
)

/** A stubbed Home Assistant that stands in for a live instance in end-to-end
  * tests.
  *
  * It implements the three `HomeAssistantApi` methods the runtime actually
  * touches with real behaviour:
  *
  *   - [[getStates]] returns the current fixture as an `/api/states` snapshot
  *     (the seed [[fh.view.runtime.StateStore]] reads on startup),
  *   - [[event]] hands back a live queue that [[emit]] pushes `state_changed`
  *     events onto (the change feed the store's background fiber drains), and
  *   - [[callService]] records the call for later assertion.
  *
  * The remaining methods raise `NotImplementedError`: they are not on the
  * runtime hot path, so an unexpected call is a loud test failure rather than a
  * silent stub. This lets a test drive the WHOLE loop — `StateStore.create` ->
  * `Server` -> HTTP/SSE -> `callService` — against static, in-repo state with a
  * scripted timeline, and no live HA.
  */
final class FakeHomeAssistant private (
    stateRef: Ref[IO, Map[String, FixtureEntity]],
    events: Queue[IO, Event],
    calls: Ref[IO, Vector[ServiceCall]]
) extends HomeAssistantApi[IO] {

  // --- The three methods the runtime uses, with real behaviour ---------------

  def getStates =
    stateRef.get.map(_.values.toList.map(_.toGetStatesData))

  def event(event: Option[String]): Resource[IO, QueueSource[IO, Event]] =
    Resource.pure(events)

  def callService(
      domain: String,
      service: String,
      entityId: String,
      serviceData: Json
  ): IO[Json] =
    calls
      .update(_ :+ ServiceCall(domain, service, entityId, serviceData))
      .as(Json.obj())

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
        events.offer(
          Event(
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
        )
      }

  /** Every `call_service` recorded so far, in order. */
  def recordedCalls: IO[Vector[ServiceCall]] = calls.get

  /** Forget every recorded call (per-test isolation). */
  def resetCalls: IO[Unit] = calls.set(Vector.empty)

  // --- The rest: not on the runtime path, so a call is a test failure --------

  private def na: IO[Nothing] =
    IO.raiseError(new NotImplementedError("FakeHomeAssistant: unexpected call"))
  private def naR: Resource[IO, Nothing] = Resource.eval(na)

  def configDeviceRegistryList = na
  def configEntityRegistryList = na
  def configEntityRegistryGet(entityId: EntityId) = na
  def manifestList() = na
  def configEntriesGet(type_filter: List[String], domain: Option[String]) = na
  def deviceAutomationTriggerList(deviceId: DeviceId) = na
  def deviceAutomationActionList(deviceId: DeviceId) = na
  def deviceAutomationActionCapabilities(action: Json) = na
  def getConfigWS = na
  def getServicesWS = na
  def rawEvents(eventType: String) = naR
  def trigger(data: TriggerData*) = naR
  def getServices = na
  def templateFunc[Body: io.circe.Decoder](template: String) = na
}

object FakeHomeAssistant {

  /** Build a fake seeded with the given entities. Unbounded event queue: tests
    * emit a handful of changes, never enough to matter.
    */
  def create(seed: List[FixtureEntity]): IO[FakeHomeAssistant] =
    for {
      stateRef <- Ref[IO].of(seed.map(e => e.entityId -> e).toMap)
      events <- Queue.unbounded[IO, Event]
      calls <- Ref[IO].of(Vector.empty[ServiceCall])
    } yield new FakeHomeAssistant(stateRef, events, calls)
}
