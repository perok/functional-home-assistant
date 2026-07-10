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

import scala.concurrent.duration.*

/** A self-healing Home Assistant connection feeding a [[StateStore]].
  *
  * The upstream HA WebSocket ([[api.homeassistant.ws.HAWSApiLowLevel]]) has no
  * built-in reconnect and no keepalive (both are `TODO`s in that file), so a
  * single dropped connection used to silently freeze the whole dashboard:
  * `state_changed` events stop arriving, `call_service` requests hang forever
  * on the dead socket, and nothing ever re-establishes the link. The browser
  * SSE stream stays open the entire time, so from the user's side "the session
  * just stops working after a while" with no recovery short of a server
  * restart.
  *
  * This supervises the entire `HomeAssistantApi` resource instead. On every
  * (re)connect it subscribes to `state_changed`, re-seeds the store from
  * `/api/states` — which republishes exactly the entities that changed during
  * the outage, so every connected browser catches up — and runs a periodic
  * `get_config` probe whose timeout doubles as liveness detection. When the
  * probe fails (or the connection resource ends), it tears the connection down
  * and reconnects with capped exponential backoff.
  *
  *   - [[api]] is a stable facade that routes every call to the CURRENT live
  *     connection, so callers (the Server's `call_service` handler) never see
  *     the swap. A call made while disconnected fails fast rather than hanging.
  *   - [[store]] is the single [[StateStore]], seeded across reconnects.
  *   - [[healthy]] reports whether the upstream feed is currently live; the
  *     Server's SSE heartbeat gates its beat on it, so the client disconnect
  *     banner also reflects an upstream freeze, not just a browser-side drop.
  */
final case class HaFeed(
    api: HomeAssistantApi[IO],
    store: StateStore,
    healthy: Signal[IO, Boolean]
)

object HaFeed {

  /** How often to probe the live connection with a `get_config` round-trip. The
    * probe keeps intermediaries (proxies/NAT) from idling the socket out AND
    * detects a dead connection — a hung probe is what triggers a reconnect.
    */
  private val ProbeInterval: FiniteDuration = 15.seconds

  /** How long a probe may hang before the connection is considered dead. On a
    * live socket `get_config` returns in milliseconds; on a dead one the
    * request blocks forever (the response never arrives), so the timeout is the
    * signal.
    */
  private val ProbeTimeout: FiniteDuration = 10.seconds

  private val MinBackoff: FiniteDuration = 1.second
  private val MaxBackoff: FiniteDuration = 30.seconds

  /** Build the supervised feed. `connect` is re-`.use`d on every reconnect (a
    * fresh WebSocket + auth each time), so pass the full connection resource
    * (`FHApi.fromEnv`), not an already-established connection.
    */
  def resource(
      connect: Resource[IO, HomeAssistantApi[IO]]
  ): Resource[IO, HaFeed] =
    for {
      currentRef <- SignallingRef[IO]
        .of(Option.empty[HomeAssistantApi[IO]])
        .toResource
      healthyRef <- SignallingRef[IO].of(false).toResource
      store <- StateStore.empty.toResource
      _ <- superviseLoop(connect, currentRef, healthyRef, store).background
    } yield HaFeed(facade(currentRef), store, healthyRef)

  /** Reconnect forever: run one connection to completion, mark the feed down,
    * wait out the (escalating) backoff, and try again. A connection that seeds
    * successfully resets the backoff, so a flapping link escalates while a
    * long-lived one that later drops restarts from the minimum.
    */
  private def superviseLoop(
      connect: Resource[IO, HomeAssistantApi[IO]],
      currentRef: SignallingRef[IO, Option[HomeAssistantApi[IO]]],
      healthyRef: SignallingRef[IO, Boolean],
      store: StateStore
  ): IO[Unit] =
    SignallingRef[IO].of(MinBackoff).flatMap { backoffRef =>
      runConnection(
        connect,
        currentRef,
        healthyRef,
        store,
        backoffRef
      ).attempt.flatMap { outcome =>
        val reason = outcome match {
          case Right(_)  => "connection closed"
          case Left(err) => Option(err.getMessage).getOrElse(err.toString)
        }
        healthyRef.set(false) *>
          currentRef.set(None) *>
          backoffRef.get.flatMap { delay =>
            val next = delay * 2
            IO.println(
              s"[ha-feed] disconnected ($reason); reconnecting in ${delay.toSeconds}s"
            ) *>
              IO.sleep(delay) *>
              backoffRef.set(if (next < MaxBackoff) next else MaxBackoff)
          }
      }.foreverM
    }

  /** One connection's lifetime: subscribe to `state_changed`, re-seed the
    * store, publish liveness, then run the ingest pump alongside the heartbeat
    * probe. `race` means whichever ends first (a hung probe, or the
    * pump/connection failing) ends the block, releasing the connection resource
    * — which cancels the low-level's background fibers and closes the socket —
    * so the supervisor reconnects cleanly.
    */
  private def runConnection(
      connect: Resource[IO, HomeAssistantApi[IO]],
      currentRef: SignallingRef[IO, Option[HomeAssistantApi[IO]]],
      healthyRef: SignallingRef[IO, Boolean],
      store: StateStore,
      backoffRef: SignallingRef[IO, FiniteDuration]
  ): IO[Unit] =
    connect.use { api =>
      // Subscribe BEFORE seeding so no change is missed in the gap between the
      // snapshot and the live stream; events buffer in the queue and the pump
      // drains them after the seed.
      api.event(Some("state_changed")).use { queue =>
        currentRef.set(Some(api)) *>
          store.reseed(api) *>
          backoffRef.set(MinBackoff) *>
          healthyRef.set(true) *>
          IO.println(
            "[ha-feed] connected; state re-seeded from Home Assistant"
          ) *>
          pump(queue, store).race(heartbeat(api)).void
      }
    }

  /** Drain the live `state_changed` queue into the store. Blocks forever on a
    * healthy connection (there is no in-band "closed" signal from the low-level
    * queue), so death detection is the heartbeat's job, not this one's.
    */
  private def pump(queue: QueueSource[IO, Event], store: StateStore): IO[Unit] =
    Stream.repeatEval(queue.take).evalMap(store.applyEvent).compile.drain

  /** Periodic liveness probe: a `get_config` round-trip with a timeout. A
    * successful round-trip proves the socket is alive (and generates keepalive
    * traffic); a timeout or error propagates out, ending [[runConnection]] and
    * triggering a reconnect.
    */
  private def heartbeat(api: HomeAssistantApi[IO]): IO[Unit] =
    (IO.sleep(ProbeInterval) *> api.getConfigWS
      .timeout(ProbeTimeout)
      .void).foreverM

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
