package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.domain.*
import api.homeassistant.ws.protocol.client.TriggerData
import api.homeassistant.ws.protocol.server.Event
import cats.effect.std.QueueSource
import cats.effect.{IO, Resource}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import ha.runtime.definitions.*
import io.circe.{Decoder, Json}
import perok.ha.{GetStatesData, ServiceDomain}
import retry.*

import scala.concurrent.duration.*

/** A self-healing Home Assistant connection feeding a [[StateStore]].
  *
  * The upstream HA WebSocket used to silently freeze the whole dashboard on a
  * single dropped connection: `state_changed` events stopped arriving,
  * `call_service` hung forever on the dead socket, and nothing re-established
  * the link — while the browser SSE stream stayed open, so from the user's side
  * "the session just stops working after a while" with no recovery short of a
  * restart. The connection now keeps itself alive with idle HA ping/pong and
  * reports its own death via `awaitClosed`
  * ([[api.homeassistant.ws.HAWSApiLowLevel]]).
  *
  * This supervises the whole connection resource. On every (re)connect it
  * subscribes to `state_changed`, re-seeds the store from `/api/states` — which
  * republishes exactly the entities that changed during the outage, so every
  * connected browser catches up — and races the ingest pump against
  * `awaitClosed`. When the connection dies it tears down and reconnects with
  * capped exponential backoff.
  *
  *   - [[api]] is a stable facade that routes every call to the CURRENT live
  *     connection, so callers (the Server's `call_service` handler) never see
  *     the swap. A call made while disconnected fails fast rather than hanging.
  *   - [[store]] is the single [[StateStore]], seeded across reconnects.
  *   - [[healthy]] reports whether the upstream feed is currently live; the
  *     Server pushes it to the browser as the `haDown` signal, so the client
  *     disconnect banner reflects an upstream freeze, not just a browser-side
  *     drop.
  */
final case class HaFeed(
    api: HomeAssistantApi[IO],
    store: StateStore,
    healthy: Signal[IO, Boolean]
)

object HaFeed {

  private val MinBackoff: FiniteDuration = 1.second
  private val MaxBackoff: FiniteDuration = 30.seconds

  /** A connection resource paired with its `awaitClosed` signal (an `IO[Unit]`
    * that completes when the WebSocket has died) — exactly what
    * `FHApi.fromEnvWithClose` yields. The connection owns keepalive and
    * liveness detection (idle HA ping/pong); this supervisor just reacts to the
    * close.
    */
  type Connect = Resource[IO, (HomeAssistantApi[IO], IO[Unit])]

  /** Build the supervised feed. `connect` is re-`.use`d on every reconnect (a
    * fresh WebSocket + auth each time), so pass the full connection resource
    * (`FHApi.fromEnvWithClose`), not an already-established connection.
    */
  def resource(connect: Connect): Resource[IO, HaFeed] =
    for {
      currentRef <- SignallingRef[IO]
        .of(Option.empty[HomeAssistantApi[IO]])
        .toResource
      healthyRef <- SignallingRef[IO].of(false).toResource
      store <- StateStore.empty.toResource
      _ <- superviseLoop(connect, currentRef, healthyRef, store).background
    } yield HaFeed(facade(currentRef), store, healthyRef)

  /** Reconnect forever with capped exponential backoff, expressed as a
    * cats-retry policy rather than a hand-rolled doubling `Ref`.
    *
    * `runConnection` is one connection's whole lifetime: it either RETURNS (the
    * socket closed cleanly) or RAISES (an abnormal drop — `awaitClosed` reports
    * the cause). `retryingOnErrors` retries only on a raise, escalating the
    * delay across CONSECUTIVE failed runs (a flapping link), and stops on a
    * clean return; `.foreverM` then re-enters with a FRESH policy — so a
    * long-lived connection that later closes cleanly restarts the backoff from
    * the minimum, while rapid connect/fail churn escalates up to `MaxBackoff`.
    * There is no retry limit, so it reconnects indefinitely.
    */
  private def superviseLoop(
      connect: Connect,
      currentRef: SignallingRef[IO, Option[HomeAssistantApi[IO]]],
      healthyRef: SignallingRef[IO, Boolean],
      store: StateStore
  ): IO[Unit] = {
    val policy = RetryPolicies.capDelay(
      MaxBackoff,
      RetryPolicies.exponentialBackoff[IO](MinBackoff)
    )
    val logReconnect = ResultHandler.retryOnAllErrors[IO, Unit] {
      (err: Throwable, details: RetryDetails) =>
        val reason = Option(err.getMessage).getOrElse(err.toString)
        IO.println(
          s"[ha-feed] disconnected ($reason); reconnecting (attempt ${details.retriesSoFar + 1})"
        )
    }
    retryingOnErrors(runConnection(connect, currentRef, healthyRef, store))(
      policy = policy,
      errorHandler = logReconnect
    ).foreverM
  }

  /** One connection's lifetime: subscribe to `state_changed`, re-seed the store
    * (republishing whatever changed during the outage), publish liveness, then
    * run the ingest pump raced against the connection's own `awaitClosed`. The
    * connection reports its death (idle ping unanswered / receive loop ended),
    * so when `awaitClosed` fires the block ends, the resource is released —
    * which cancels the low-level fibers and closes the socket — and the
    * supervisor reconnects. The `guarantee` marks the feed down and clears the
    * current connection on EVERY end (clean or abnormal), so the facade fails
    * fast and the disconnect banner trips during the reconnect gap.
    */
  private def runConnection(
      connect: Connect,
      currentRef: SignallingRef[IO, Option[HomeAssistantApi[IO]]],
      healthyRef: SignallingRef[IO, Boolean],
      store: StateStore
  ): IO[Unit] =
    connect
      .use { case (api, awaitClosed) =>
        // Subscribe BEFORE seeding so no change is missed in the gap between the
        // snapshot and the live stream; events buffer in the queue and the pump
        // drains them after the seed.
        api.event(Some("state_changed")).use { queue =>
          currentRef.set(Some(api)) *>
            store.reseed(api) *>
            healthyRef.set(true) *>
            IO.println(
              "[ha-feed] connected; state re-seeded from Home Assistant"
            ) *>
            pump(queue, store).race(awaitClosed).void
        }
      }
      .guarantee(healthyRef.set(false) *> currentRef.set(None))

  /** Drain the live `state_changed` queue into the store. Blocks as long as the
    * connection lives; `runConnection` races it against `awaitClosed`, which is
    * what ends the connection scope on death.
    */
  private def pump(queue: QueueSource[IO, Event], store: StateStore): IO[Unit] =
    Stream.repeatEval(queue.take).evalMap(store.applyEvent).compile.drain

  /** A stable `HomeAssistantApi` that dispatches each call to whatever
    * connection is live now. Calls issued while disconnected fail fast with a
    * clear error instead of hanging on a dead socket.
    */
  private def facade(
      currentRef: SignallingRef[IO, Option[HomeAssistantApi[IO]]]
  ): HomeAssistantApi[IO] =
    new HomeAssistantApi[IO] {
      private val disconnected: IO[Nothing] =
        IO.raiseError(
          new RuntimeException("Home Assistant feed is disconnected")
        )

      private def use[A](f: HomeAssistantApi[IO] => IO[A]): IO[A] =
        currentRef.get.flatMap(_.fold(disconnected)(f))

      private def useR[A](
          f: HomeAssistantApi[IO] => Resource[IO, A]
      ): Resource[IO, A] =
        Resource
          .eval(currentRef.get)
          .flatMap(_.fold(Resource.eval(disconnected))(f))

      def configDeviceRegistryList: IO[Map[DeviceId, Device]] =
        use(_.configDeviceRegistryList)
      def configEntityRegistryList: IO[Map[EntityId, Entity]] =
        use(_.configEntityRegistryList)
      def configEntityRegistryGet(entityId: EntityId): IO[Json] =
        use(_.configEntityRegistryGet(entityId))
      def manifestList(): IO[List[Manifest]] = use(_.manifestList())
      def configEntriesGet(
          type_filter: List[String],
          domain: Option[String]
      ): IO[List[ConfigEntry]] = use(_.configEntriesGet(type_filter, domain))
      def deviceAutomationTriggerList(
          deviceId: DeviceId
      ): IO[List[DeviceTrigger]] = use(_.deviceAutomationTriggerList(deviceId))
      def deviceAutomationActionList(deviceId: DeviceId): IO[List[Json]] =
        use(_.deviceAutomationActionList(deviceId))
      def deviceAutomationActionCapabilities(action: Json): IO[Json] =
        use(_.deviceAutomationActionCapabilities(action))
      def getConfigWS: IO[Json] = use(_.getConfigWS)
      def getServicesWS: IO[Json] = use(_.getServicesWS)
      def event(event: Option[String]): Resource[IO, QueueSource[IO, Event]] =
        useR(_.event(event))
      def rawEvents(eventType: String): Resource[IO, QueueSource[IO, Json]] =
        useR(_.rawEvents(eventType))
      def trigger(data: TriggerData*): Resource[IO, QueueSource[IO, Json]] =
        useR(_.trigger(data*))
      def callService(
          domain: String,
          service: String,
          entityId: String,
          serviceData: Json
      ): IO[Json] = use(_.callService(domain, service, entityId, serviceData))
      def getStates: IO[List[GetStatesData]] = use(_.getStates)
      def getServices: IO[List[ServiceDomain]] = use(_.getServices)
      def templateFunc[Body: Decoder](template: String): IO[Body] =
        use(_.templateFunc[Body](template))
    }
}
