package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.domain.EntitiesEvent
import fh.view.FHError
import cats.effect.{Deferred, IO, Resource}
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
  * subscribes to HA's compressed entity feed — whose opening frame is the full
  * entity set, so a reconnect republishes exactly the entities that changed
  * during the outage and every connected browser catches up — and races the
  * ingest pump against `awaitClosed`. When the connection dies it tears down
  * and reconnects with capped exponential backoff.
  *
  *   - [[api]] is a stable facade built ONE level down, over the low-level WS
  *     ([[HAWSApiLowLevel]]): [[HomeAssistantApi.fromWs]] regenerates the whole
  *     API over a facade that ROUTES each call to the CURRENT live connection,
  *     so consumers hold one value across reconnects. Anything issued while
  *     disconnected fails fast, and a subscription stream ends when its
  *     connection dies — a consumer that must span reconnects re-subscribes off
  *     [[healthy]].
  *   - [[store]] is the single [[StateStore]], refilled across reconnects.
  *   - [[healthy]] reports whether a connection is currently live (`true` from
  *     the moment the socket is up and subscribed, before the full set has been
  *     applied); the Server pushes it to the browser as the `haDown` signal, so
  *     the disconnect banner reflects an upstream freeze, not just a
  *     browser-side drop.
  *
  * A HaFeed VALUE MEANS THE STORE IS POPULATED: [[resource]] does not hand one
  * out until the feed's opening full state has been applied (or gives up
  * loudly, [[SeedTimeout]]). So a consumer never holds a feed it must first
  * remember to wait on — the wait has no expression in the API because it
  * cannot be skipped. That is only about the FIRST fill; a later reconnect
  * re-seeds in the background and is reported by [[healthy]], which is a
  * different question ("is the link up right now") from a one-shot "has it ever
  * filled".
  */
final case class HaFeed(
    api: HomeAssistantApi[IO],
    store: StateStore,
    healthy: Signal[IO, Boolean]
)

object HaFeed {

  private val MinBackoff: FiniteDuration = 1.second
  private val MaxBackoff: FiniteDuration = 30.seconds

  /** How long [[resource]] waits for the feed's opening state before failing
    * the boot. Generous, since an add-on may start just before Home Assistant
    * core is ready, but bounded so a misconfiguration fails loudly instead of
    * hanging on the infinite reconnect loop.
    */
  private val SeedTimeout: FiniteDuration = 60.seconds

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
    *
    * ACQUISITION BLOCKS until the store has been filled once, so what it yields
    * is a feed that is ready to read.
    */
  def resource(connect: Connect): Resource[IO, HaFeed] =
    for {
      // The live connection, or None while disconnected. Drives command routing,
      // subscription re-arm, AND the `healthy` banner (`.isDefined`).
      connection <- SignallingRef[IO]
        .of(Option.empty[HAWSApiLowLevel[IO]])
        .toResource
      // One-shot: completed once a feed batch has landed; gates acquisition.
      seeded <- IO.deferred[Unit].toResource
      store <- StateStore.empty.toResource
      // The stable API: a durable facade over the current low-level connection,
      // rebuilt into the full API by `fromWs`. Consumers hold this one value
      // across every reconnect.
      api = HomeAssistantApi.fromWs(routingFacade(connection))
      _ <- superviseLoop(connect, connection, seeded, store).background
      // Credentials are validated by the caller before we get here, so failing
      // this wait means HA is configured but not answering: a clear boot error
      // rather than a silent hang inside the reconnect loop.
      _ <- seeded.get
        .timeoutTo(
          SeedTimeout,
          IO.raiseError(
            FHError.internal(
              s"Home Assistant sent no state within $SeedTimeout " +
                "(the instance is configured but did not answer — is it running?)"
            )
          )
        )
        .toResource
    } yield HaFeed(api, store, connection.map(_.isDefined))

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
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
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
          s"[ha-feed] disconnected (${err.getClass.getName} $reason); reconnecting (attempt ${details.retriesSoFar + 1})"
        )
    }
    retryingOnErrors(
      runConnection(connect, connection, seeded, store)
    )(
      policy = policy,
      errorHandler = logReconnect
    ).foreverM
  }

  /** One connection's lifetime: subscribe to the entity feed on THIS
    * connection, publish the connection as current — which routes [[api]] and
    * every durable subscription here and re-arms them, and flips `healthy` —
    * then run the ingest pump raced against the connection's own `awaitClosed`.
    * The `guarantee` clears the connection on EVERY end (clean or abnormal), so
    * commands fail fast and the banner trips during the reconnect gap.
    *
    * There is no separate seeding step: `subscribe_entities` opens with the
    * full entity set, so a reconnect's catch-up IS the new subscription's first
    * frame and nothing needs ordering against a snapshot fetch.
    */
  private def runConnection(
      connect: Connect,
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
      store: StateStore
  ): IO[Unit] =
    connect
      .use { case (ll, awaitClosed) =>
        // A throwaway high-level view of THIS connection: the store's feed must
        // ride the connection being established, not the durable facade.
        val live = HomeAssistantApi
          .fromWs(ll)
          .entities
          .use { frames =>
            connection.set(Some(ll)) *>
              IO.println("[ha-feed] connected; subscribed to entity feed") *>
              pump(frames, store, seeded)
          }
        // The race covers the WHOLE lifetime, not just the pump: subscribing
        // waits on the wire, so a socket dying there has to end this run too or
        // the supervisor never gets to reconnect.
        live.race(awaitClosed).void
      }
      .guarantee(connection.set(None))

  /** Drain the entity feed into the store, one store update per arriving CHUNK.
    * A chunk IS a coalesced frame — the transport groups a frame's payloads by
    * subscription and hands them over whole — so a burst (an automation moving
    * a dozen entities at once) costs one `ref.modify`, not one per entity.
    * Blocks as long as the connection lives; `runConnection` races it against
    * `awaitClosed`, which is what ends the connection scope on death.
    *
    * The first applied batch latches `seeded`: the feed opens with the full
    * entity set, so "a batch has landed" IS "the store is populated".
    */
  private def pump(
      frames: Stream[IO, EntitiesEvent],
      store: StateStore,
      seeded: Deferred[IO, Unit]
  ): IO[Unit] =
    frames.chunks
      .evalMap(store.applyEntities)
      .evalTap(_ => seeded.complete(()).void)
      .compile
      .drain

  /** A stable low-level WS that resolves to whatever connection is live now.
    *
    * Nothing more: it is pure routing, so that consumers can hold ONE value
    * across reconnects instead of chasing the current connection themselves.
    * Both methods fail fast while disconnected, and within a connection the
    * transport itself refuses to strand a caller — a dead socket closes every
    * open route, so a command raises and a subscription stream ENDS
    * ([[HAWSApiLowLevel]]).
    *
    * It deliberately does NOT make subscriptions durable. It used to: a queue,
    * an arm fiber and a `switchMap` re-subscribed on every connection
    * generation. That existed to keep the dashboard's state feed alive across a
    * reconnect — but the state feed never used it (it rides the connection
    * being established, see [[runConnection]]), so the mechanism served one
    * low-volume consumer while duplicating the reconnect logic the supervisor
    * already owns. A consumer that wants to span reconnects re-subscribes off
    * [[HaFeed.healthy]], which is three lines where it is needed and no
    * machinery where it is not.
    */
  private def routingFacade(
      currentRef: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]]
  ): HAWSApiLowLevel[IO] =
    new HAWSApiLowLevel[IO] {
      private def disconnected[A]: IO[A] =
        IO.raiseError(
          new RuntimeException("Home Assistant feed is disconnected")
        )

      def sendCommand[Response](
          command: CommandPhase & CommandResponse.WithSingleResponse[Response]
      ): IO[Response] =
        currentRef.get.flatMap(
          _.fold(disconnected[Response])(_.sendCommand(command))
        )

      def subscribeStream[Result](
          msg: CommandPhase & CommandResponse.AsStream[Result]
      ): Resource[IO, Stream[IO, Result]] =
        Resource.eval(currentRef.get).flatMap {
          case Some(conn) => conn.subscribeStream(msg)
          case None       => Resource.eval(disconnected[Stream[IO, Result]])
        }

      // The facade itself never closes — it outlives every connection. A
      // per-connection close is observed by the supervisor via that
      // connection's own `awaitClosed`, and by consumers as their subscription
      // stream ending.
      def awaitClosed: IO[Unit] = IO.never
    }
}
