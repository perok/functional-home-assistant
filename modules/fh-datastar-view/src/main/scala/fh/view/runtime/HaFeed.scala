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
  * A dropped upstream WebSocket freezes the whole dashboard — no state arrives,
  * `call_service` hangs on the dead socket — and nothing in the socket's own
  * API says it died, hence idle ping/pong and `awaitClosed`
  * ([[api.homeassistant.ws.HAWSApiLowLevel]]).
  *
  * **A HaFeed value means the store is populated**: [[resource]] does not hand
  * one out until the opening full state has been applied (or gives up loudly,
  * [[SeedTimeout]]), so the wait has no expression in the API because it cannot
  * be skipped. That is the FIRST fill only; [[healthy]] answers the different,
  * ongoing question.
  *
  *   - [[api]] routes each call to whatever connection is live now, so
  *     consumers hold one value across reconnects. Issued while disconnected it
  *     fails fast, and a subscription stream ENDS when its connection dies —
  *     spanning reconnects means re-subscribing off [[healthy]].
  *   - [[healthy]] is `true` from the moment the socket is up and subscribed,
  *     before the full set has been applied. The Server pushes it as the
  *     `haDown` signal, so the banner distinguishes an upstream freeze from a
  *     browser-side drop.
  */
final case class HaFeed(
    api: HomeAssistantApi[IO],
    store: StateStore,
    healthy: Signal[IO, Boolean]
)

object HaFeed {

  /** A RATE limit, not a backoff, and deliberately flat. Exponential backoff
    * assumes retries are expensive or the peer is shared; this is one WebSocket
    * to one local instance, where a failed attempt is a refused TCP connect. It
    * also gets the main case backwards: an HA restart takes half a minute, by
    * which point an escalating delay has reached its cap, leaving the dashboard
    * dark that long AFTER the instance is ready again.
    */
  private val ReconnectDelay: FiniteDuration = 1.second

  /** How long [[resource]] waits for the feed's opening state before failing
    * the boot. Generous, since an add-on may start just before Home Assistant
    * core is ready, but bounded so a misconfiguration fails loudly instead of
    * hanging on the infinite reconnect loop.
    */
  private val SeedTimeout: FiniteDuration = 60.seconds

  /** The `IO[Unit]` completes when the WebSocket has died —
    * `FHApi.lowLevelConnectWithClose` yields exactly this.
    */
  type Connect = Resource[IO, (HAWSApiLowLevel[IO], IO[Unit])]

  /** `connect` is re-`.use`d on every reconnect (a fresh WebSocket + auth each
    * time), so pass the full connection resource, not an established
    * connection. ACQUISITION BLOCKS until the store has been filled once.
    */
  def resource(
      connect: Connect,
      wanted: Signal[IO, Option[Set[String]]] = Signal.constant(None)
  ): Resource[IO, HaFeed] =
    for {
      // `.isDefined` IS the `healthy` banner — one toggle, not a second flag.
      connection <- SignallingRef[IO]
        .of(Option.empty[HAWSApiLowLevel[IO]])
        .toResource
      seeded <- IO.deferred[Unit].toResource
      store <- StateStore.empty.toResource
      api = HomeAssistantApi.fromWs(routingFacade(connection))
      _ <- superviseLoop(connect, connection, seeded, store, wanted).background
      // Credentials are validated by the caller, so failing this wait means HA
      // is configured but not answering — a boot error rather than a silent
      // hang inside the reconnect loop.
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

  /** The wait is UNCONDITIONAL, which is the whole reason this cannot spin,
    * however a connection ended and however fast. A retry policy has to be told
    * which endings count, so it spins on the one nobody thought to enumerate (a
    * peer that accepts, auths and closes politely, over and over).
    *
    * `meteredStartImmediately` gives the two properties a backoff needs
    * bookkeeping for: the first attempt is immediate, and `fixedRate` DAMPENS
    * missed ticks, so a healthy link that drops reconnects at once while a
    * flapping one is held to one attempt per period. No lifetime to measure.
    *
    * Cancellation is deliberately outside the `attempt`: that is the app
    * shutting down, and it must stop the loop rather than look like another
    * reconnect.
    */
  private def superviseLoop(
      connect: Connect,
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
      store: StateStore,
      wanted: Signal[IO, Option[Set[String]]]
  ): IO[Unit] =
    Stream
      .repeatEval(
        runConnection(connect, connection, seeded, store, wanted).attempt
      )
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

  /** Keyed on CONNECTIVITY, not on why an attempt ended, because a boolean
    * alternates and `changes` can never swallow a transition. Keyed on the
    * reason it silently can: two ends with the same cause an hour apart, with a
    * healthy connection between them, are CONSECUTIVE in that stream — nothing
    * between two ends emits anything — so the second is deduped away and a real
    * disconnect goes unlogged.
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

  /** There is no separate seeding step: `subscribe_entities` opens with the
    * full entity set, so a reconnect's catch-up IS the new subscription's first
    * frame, with nothing to order against a snapshot fetch. That also makes the
    * outage LOSSLESS without buffering — a delta that never arrived, or one in
    * flight when the socket died, is superseded by the next full set rather
    * than replayed.
    */
  private def runConnection(
      connect: Connect,
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
      store: StateStore,
      wanted: Signal[IO, Option[Set[String]]]
  ): IO[Unit] =
    connect
      .use { case (ll, awaitClosed) =>
        // The store's feed must ride the connection being established, not the
        // routing facade, which still points at the previous one.
        val live = subscriptions(
          HomeAssistantApi.fromWs(ll),
          wanted,
          store,
          seeded,
          connection.set(Some(ll))
        )
        // The race covers the WHOLE lifetime, not just the pump: subscribing
        // waits on the wire, so a socket dying there has to end this run too or
        // the supervisor never gets to reconnect.
        live.race(awaitClosed).void
      }
      .guarantee(connection.set(None))

  /** A chunk IS a coalesced frame — the transport groups a frame's payloads by
    * subscription and hands them over whole — so a burst (an automation moving
    * a dozen entities at once) costs one `ref.modify`, not one per entity.
    *
    * Latching `seeded` on the first batch is sound because the feed opens with
    * the full entity set, so "a batch has landed" IS "the store is populated".
    */
  private def pump(
      frames: Stream[IO, EntitiesEvent],
      store: StateStore,
      seeded: Deferred[IO, Unit]
  ): Stream[IO, Unit] =
    frames.chunks
      .evalMap(store.applyEntities)
      .evalTap(_ => seeded.complete(()).void)

  /** One subscription at a time, re-opened when the set of entities anyone
    * reads changes ([[Dashboard.watchedEntities]], unioned over the registered
    * slugs).
    *
    * `switchMap` ends the old subscription before opening the new one, and the
    * window between them loses nothing for the same reason a RECONNECT does
    * not: the new subscription opens with the full state of its set, so
    * anything that moved while it was closed arrives in its first frame. That
    * is the argument [[runConnection]] already makes for the outage case.
    *
    * (`Hotswap` would overlap instead, and the duplicate frames would be
    * absorbed by `EntityState.stale`. It is not needed here, and this is one
    * mechanism rather than a second beside the pump.)
    *
    * An EMPTY wanted set opens no subscription at all, because an empty
    * `entity_ids` is how HA spells the whole house
    * ([[HomeAssistantApi.entities]]). Nothing is registered, so nothing is owed
    * any state.
    */
  private def subscriptions(
      ha: HomeAssistantApi[IO],
      wanted: Signal[IO, Option[Set[String]]],
      store: StateStore,
      seeded: Deferred[IO, Unit],
      established: IO[Unit]
  ): IO[Unit] =
    Stream
      .eval(IO.deferred[Unit])
      .flatMap { ended =>
        wanted.discrete.changes
          .switchMap {
            // Wanted nothing: hold the connection with no subscription on it.
            // `Stream.empty` would END here, and an end means the feed died
            // (below), so an instance with no dashboards would reconnect in a
            // loop.
            case Some(ids) if ids.isEmpty => Stream.never[IO]
            case only                     =>
              Stream
                .resource(ha.entities(only))
                .evalTap(_ => established)
                .flatMap(pump(_, store, seeded)) ++
                // A subscription that ends ON ITS OWN means the connection is
                // gone — the transport closes every route when it dies — and
                // this run must end so the supervisor reconnects. Under
                // `switchMap` alone it would instead sit waiting for a `wanted`
                // that will never arrive, and the feed would stay dark. A
                // ROTATION does not reach this: switching INTERRUPTS the inner
                // stream rather than letting it complete.
                Stream.exec(ended.complete(()).void)
          }
          .interruptWhen(ended.get.attempt)
      }
      .compile
      .drain

  /** Pure routing, so consumers hold ONE value across reconnects. Nothing here
    * strands a caller: both methods fail fast while disconnected, and within a
    * connection a dead socket closes every open route, so a command raises and
    * a subscription stream ENDS ([[HAWSApiLowLevel]]).
    *
    * It deliberately does NOT make subscriptions durable. Re-arming them here
    * would duplicate the reconnect logic the supervisor owns, and the one
    * consumer that must span reconnects — the state feed — does not need it: it
    * rides the connection being established ([[runConnection]]).
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

      // The facade outlives every connection. A per-connection close reaches
      // the supervisor via that connection's own `awaitClosed`, and consumers
      // as their subscription stream ending.
      def awaitClosed: IO[Unit] = IO.never
    }
  }
}
