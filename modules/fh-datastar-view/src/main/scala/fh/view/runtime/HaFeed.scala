package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.server.Event
import cats.effect.std.{Queue, QueueSource}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import retry.*

import scala.concurrent.duration.*

/** A self-healing Home Assistant connection feeding a [[StateStore]].
  *
  * The upstream HA WebSocket used to silently freeze the whole dashboard on a
  * single dropped connection: `state_changed` events stopped arriving,
  * `call_service` hung forever on the dead socket, and nothing re-established
  * the link. The connection now keeps itself alive with idle HA ping/pong and
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
  *   - [[api]] is a stable facade built ONE level down, over the low-level WS
  *     ([[HAWSApiLowLevel]]): [[HomeAssistantApi.fromWs]] regenerates the whole
  *     API over a durable low-level facade that routes each call to the CURRENT
  *     live connection. A COMMAND made while disconnected fails fast; a
  *     SUBSCRIPTION is durable — it re-subscribes across reconnects, so a
  *     long-lived external subscriber (the registry watcher) survives a drop
  *     without re-subscribing itself.
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
) {

  /** Complete once the feed FIRST reports healthy — i.e. it has connected and
    * seeded [[store]] from `/api/states` at least once. The feed connects in
    * the background, so until this fires [[store]] is empty and [[api]] fails
    * fast (the facade raises while disconnected). It is the gate before any use
    * that assumes a live connection: startup dump prep on the server, and a
    * test driving state through [[FakeHomeAssistant.emit]].
    *
    * `healthy` starts `false` and flips `true` after the first reseed, so this
    * waits for that first `true` and returns (a later reconnect blip does not
    * un-fire it).
    */
  def awaitHealthy: IO[Unit] =
    healthy.discrete.find(identity).compile.drain
}

object HaFeed {

  private val MinBackoff: FiniteDuration = 1.second
  private val MaxBackoff: FiniteDuration = 30.seconds

  /** A LOW-LEVEL connection resource paired with its `awaitClosed` signal (an
    * `IO[Unit]` that completes when the WebSocket has died) — exactly what
    * `FHApi.lowLevelConnectWithClose` yields. The facade sits below
    * [[HomeAssistantApi]] (over [[HAWSApiLowLevel]]) so subscriptions can be
    * made durable in one place; the connection owns keepalive and liveness
    * detection (idle HA ping/pong).
    */
  type Connect = Resource[IO, (HAWSApiLowLevel[IO], IO[Unit])]

  /** Build the supervised feed. `connect` is re-`.use`d on every reconnect (a
    * fresh WebSocket + auth each time), so pass the full connection resource
    * (`FHApi.lowLevelConnectWithClose`), not an already-established connection.
    */
  def resource(connect: Connect): Resource[IO, HaFeed] =
    for {
      currentRef <- SignallingRef[IO]
        .of(Option.empty[HAWSApiLowLevel[IO]])
        .toResource
      healthyRef <- SignallingRef[IO].of(false).toResource
      store <- StateStore.empty.toResource
      // The stable API: a durable facade over the current low-level connection,
      // rebuilt into the full API by `fromWs`. Consumers hold this one value
      // across every reconnect.
      api = HomeAssistantApi.fromWs(durableFacade(currentRef))
      _ <- superviseLoop(connect, currentRef, healthyRef, store, api).background
    } yield HaFeed(api, store, healthyRef)

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
      currentRef: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      healthyRef: SignallingRef[IO, Boolean],
      store: StateStore,
      api: HomeAssistantApi[IO]
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
    retryingOnErrors(
      runConnection(connect, currentRef, healthyRef, store, api)
    )(
      policy = policy,
      errorHandler = logReconnect
    ).foreverM
  }

  /** One connection's lifetime: subscribe to `state_changed` on THIS connection
    * (before seeding, so no change is missed in the snapshot gap), publish the
    * connection as current — which is what makes [[api]] and every durable
    * subscription route here and re-arm — re-seed the store (republishing
    * whatever changed during the outage), publish liveness, then run the ingest
    * pump raced against the connection's own `awaitClosed`. The `guarantee`
    * marks the feed down and clears the current connection on EVERY end (clean
    * or abnormal), so commands fail fast and the disconnect banner trips during
    * the reconnect gap.
    *
    * The store's own `state_changed` stays PER-CONNECTION (re-created here each
    * reconnect, with the reseed for catch-up) — that keeps its strict
    * subscribe-before-seed guarantee. The durable facade is what carries
    * LONG-LIVED external subscribers (the registry watcher) across reconnects.
    */
  private def runConnection(
      connect: Connect,
      currentRef: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      healthyRef: SignallingRef[IO, Boolean],
      store: StateStore,
      api: HomeAssistantApi[IO]
  ): IO[Unit] =
    connect
      .use { case (ll, awaitClosed) =>
        // A throwaway high-level view of THIS connection, just to subscribe the
        // store's state_changed stream before we seed.
        HomeAssistantApi
          .fromWs(ll)
          .event(Some("state_changed"))
          .use { queue =>
            currentRef.set(Some(ll)) *>
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

  /** A stable low-level WS that dispatches to whatever connection is live now.
    *
    *   - A COMMAND (`sendCommand`) issued while disconnected fails fast with a
    *     clear error instead of hanging on a dead socket.
    *   - A SUBSCRIPTION (`subscribeStream`) is DURABLE: it returns a stable
    *     queue and re-subscribes on every connection generation (a `switchMap`
    *     over the current-connection signal cancels the old subscription and
    *     opens a new one), forwarding events into that queue. So a long-lived
    *     subscriber survives a reconnect without re-subscribing itself. Events
    *     during the disconnect gap are lost; a caller that needs catch-up (the
    *     store) re-seeds on reconnect.
    *
    * `HomeAssistantApi.fromWs` regenerates the full API over this — so the
    * whole high-level surface inherits fail-fast commands and durable
    * subscriptions from one place, replacing the old hand-written 18-method
    * facade.
    */
  private def durableFacade(
      currentRef: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]]
  ): HAWSApiLowLevel[IO] =
    new HAWSApiLowLevel[IO] {
      private def disconnected[A]: IO[A] =
        IO.raiseError(
          new RuntimeException("Home Assistant feed is disconnected")
        )

      def sendCommand[Response](
          command: CommandPhase & CommandResponse.AsResult[Response]
      ): IO[Response] =
        currentRef.get.flatMap(
          _.fold(disconnected[Response])(_.sendCommand(command))
        )

      def subscribeStream[Result](
          msg: CommandPhase & CommandResponse.AsStream[Result]
      ): Resource[IO, QueueSource[IO, Result]] =
        Resource.eval(Queue.unbounded[IO, Result]).flatMap { out =>
          val arm =
            currentRef.discrete
              .switchMap {
                case None       => Stream.empty
                case Some(conn) =>
                  Stream
                    .resource(conn.subscribeStream(msg))
                    .flatMap(q => Stream.repeatEval(q.take))
              }
              .evalMap(out.offer)
              .compile
              .drain
          Resource.make(arm.start)(_.cancel).as(out)
        }

      // The durable facade never itself "closes" — it outlives every
      // connection, reconnecting under the hood. The per-connection close is
      // observed by the supervisor via each `connect`'s own `awaitClosed`.
      def awaitClosed: IO[Unit] = IO.never
    }
}
