package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.domain.EntitiesEvent
import fh.view.FHError
import cats.effect.{Deferred, IO, Resource}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}

import scala.concurrent.duration.*

/** A self-healing Home Assistant connection feeding a [[StateStore]].
  *
  * A dropped upstream WebSocket freezes the whole dashboard — no state arrives
  * and `call_service` hangs on the dead socket — and nothing in the socket's
  * own API says it died. So the connection keeps itself alive with idle HA
  * ping/pong and reports its own death via `awaitClosed`
  * ([[api.homeassistant.ws.HAWSApiLowLevel]]).
  *
  * This supervises the whole connection resource. On every (re)connect it
  * subscribes to HA's compressed entity feed — whose opening frame is the full
  * entity set, so a reconnect republishes exactly the entities that changed
  * during the outage and every connected browser catches up — and races the
  * ingest pump against `awaitClosed`. When the connection dies it tears down
  * and reconnects, rate-limited to one attempt per [[ReconnectDelay]].
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

  /** The shortest time between two connection attempts — a RATE limit, not a
    * backoff.
    *
    * Deliberately flat. Exponential backoff assumes retries are expensive or
    * the peer is shared; this is one WebSocket to one local instance, where a
    * failed attempt is a refused TCP connect. It also gets the main case
    * backwards: a Home Assistant restart takes half a minute or so, by which
    * point an escalating delay has grown to its cap — so the dashboard would
    * stay dark for up to that long AFTER the instance is ready again. A flat
    * second notices within a second.
    */
  private val ReconnectDelay: FiniteDuration = 1.second

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

  /** Reconnect forever: [[runConnection]] is one connection's whole lifetime,
    * so supervising it is just "run it, wait, run it again".
    *
    * The wait is UNCONDITIONAL, which is the whole reason this cannot spin —
    * however a connection ended, however fast. That is what a rate limit gives
    * over a retry policy: a policy has to be told which endings count, so it
    * spins on the ending nobody thought to enumerate (a peer that accepts,
    * auths and closes politely, over and over).
    *
    * `meteredStartImmediately` also hands us, for free, the two properties a
    * backoff needs bookkeeping for. The first attempt is immediate, so boot is
    * not delayed. And `fixedRate` DAMPENS missed ticks: a connection that
    * outlived the period is followed by an immediate tick, so a healthy link
    * that drops reconnects at once, while a flapping one is held to one attempt
    * per period. No lifetime to measure, nothing to reset.
    *
    * Cancellation is deliberately not caught by the `attempt`: that is the app
    * shutting down, and it must stop the loop rather than look like another
    * reconnect.
    */
  private def superviseLoop(
      connect: Connect,
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
      store: StateStore
  ): IO[Unit] =
    Stream
      .repeatEval(runConnection(connect, connection, seeded, store).attempt)
      .meteredStartImmediately(ReconnectDelay)
      // Why the last attempt ended, deduped: an instance that is down ends
      // every attempt the same way, so this says it once instead of once a
      // second. Dedupe is only safe because this is SUPPLEMENTARY detail —
      // the transition itself is reported by [[logConnectivity]], which
      // cannot lose one.
      .map(describe)
      .changes
      .evalMap(reason => IO.println(s"[ha-feed] attempt ended: $reason"))
      .concurrently(logConnectivity(connection))
      .compile
      .drain

  /** Report the connection coming and going.
    *
    * Keyed on CONNECTIVITY, not on why an attempt ended, because a boolean
    * alternates: `changes` can never swallow a real transition. Keyed on the
    * reason it can, and did — two ends with the same cause an hour apart, with
    * a healthy connection between them, are CONSECUTIVE elements of that stream
    * (nothing between two ends emits anything), so the second was dropped and a
    * genuine disconnect went unlogged.
    */
  private def logConnectivity(
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]]
  ): Stream[IO, Nothing] =
    connection.discrete
      .map(_.isDefined)
      .changes
      .zipWithPrevious
      .collect {
        // A leading `false` is the starting state, not a connection lost; a
        // leading `true` means we raced the first connect, which is still
        // worth reporting.
        case (prev, true) if !prev.contains(true) =>
          "connected; subscribed to entity feed"
        case (Some(true), false) => "connection lost; retrying"
      }
      .evalMap(msg => IO.println(s"[ha-feed] $msg"))
      .drain

  private def describe(outcome: Either[Throwable, Unit]): String =
    outcome.fold(
      err =>
        s"${err.getClass.getName} ${Option(err.getMessage).getOrElse(err.toString)}",
      _ => "closed cleanly"
    )

  /** One connection's lifetime: subscribe to the entity feed on THIS
    * connection, publish the connection as current — which routes [[api]] and
    * flips `healthy` — then run the ingest pump raced against the connection's
    * own `awaitClosed`. The `guarantee` clears the connection on EVERY end
    * (clean or abnormal), so commands fail fast and the banner trips during the
    * reconnect gap.
    *
    * There is no separate seeding step: `subscribe_entities` opens with the
    * full entity set, so a reconnect's catch-up IS the new subscription's first
    * frame and nothing needs ordering against a snapshot fetch. That also makes
    * the outage LOSSLESS without any buffering: a delta that never arrived, or
    * one in flight when the socket died, is superseded by the next full set
    * rather than replayed.
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
          // Publishing the connection is also what REPORTS it — one place owns
          // the log line, driven off the signal ([[logConnectivity]]).
          .use(frames =>
            connection.set(Some(ll)) *> pump(frames, store, seeded)
          )
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
    * It deliberately does NOT make subscriptions durable. Re-arming them here
    * would duplicate the reconnect logic the supervisor already owns, and the
    * one consumer that must span reconnects — the state feed — does not need it
    * anyway: it rides the connection being established ([[runConnection]]). Any
    * other consumer re-subscribes off [[HaFeed.healthy]], which is three lines
    * where it is needed and no machinery where it is not.
    */
  private def routingFacade(
      currentRef: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]]
  ): HAWSApiLowLevel[IO] = {
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
}
