package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.domain.EntitiesEvent
import fh.view.telemetry.{Logging, Meters}
import fh.view.FHError
import cats.effect.{Deferred, IO, Resource}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

/** A self-healing HA connection feeding a [[StateStore]]. A dead socket says
  * nothing on its own, hence ping/pong and `awaitClosed`.
  *
  * '''A `HaFeed` value means the store is populated''': [[resource]] waits for
  * the first full state. [[healthy]] is the ongoing question, true once the
  * socket is subscribed; the server pushes it as `haDown`.
  *
  * [[api]] routes to whichever connection is live and fails fast while
  * disconnected; a subscription ends with its connection, so spanning
  * reconnects means re-subscribing off [[healthy]].
  */
final case class HaFeed(
    api: HomeAssistantApi[IO],
    store: StateStore,
    healthy: Signal[IO, Boolean]
)

object HaFeed {

  /** A flat rate limit, not a backoff: an HA restart takes half a minute, by
    * which point a backoff has reached its cap and keeps the dashboard dark
    * after HA is back.
    */
  private val ReconnectDelay: FiniteDuration = 1.second

  /** Generous (an add-on may start before HA core), but bounded so a
    * misconfiguration fails boot instead of hanging.
    */
  private val SeedTimeout: FiniteDuration = 60.seconds

  /** The `IO[Unit]` completes when the socket dies. */
  type Connect = Resource[IO, (HAWSApiLowLevel[IO], IO[Unit])]

  /** `connect` is re-used per reconnect. Acquisition blocks until seeded. */
  def resource(
      connect: Connect,
      wanted: Signal[IO, Option[Set[String]]] = Signal.constant(None),
      tracer: Tracer[IO] = Tracer.noop,
      loggerFactory: LoggerFactory[IO] = Logging.console,
      meters: Meters = Meters.noop
  ): Resource[IO, HaFeed] =
    for {
      // `.isDefined` IS the `healthy` banner — one toggle, not a second flag.
      connection <- SignallingRef[IO]
        .of(Option.empty[HAWSApiLowLevel[IO]])
        .toResource
      seeded <- IO.deferred[Unit].toResource
      store <- StateStore.empty.toResource
      api = HomeAssistantApi.fromWs(routingFacade(connection))
      _ <- superviseLoop(
        connect,
        connection,
        seeded,
        store,
        wanted,
        tracer,
        loggerFactory.getLoggerFromName("fh.view.runtime.HaFeed"),
        meters
      ).background
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

  /** The wait is unconditional, so it cannot spin however a connection ended; a
    * retry policy spins on the ending nobody enumerated (a peer that auths and
    * closes politely). `meteredStartImmediately` reconnects at once after a
    * healthy drop and holds a flapping link to one attempt per period.
    * Cancellation is outside the `attempt`: shutdown stops the loop.
    */
  private def superviseLoop(
      connect: Connect,
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
      store: StateStore,
      wanted: Signal[IO, Option[Set[String]]],
      tracer: Tracer[IO],
      log: SelfAwareStructuredLogger[IO],
      meters: Meters
  ): IO[Unit] =
    Stream
      .repeatEval(
        runConnection(
          connect,
          connection,
          seeded,
          store,
          wanted,
          tracer,
          meters
        ).attempt
      )
      .meteredStartImmediately(ReconnectDelay)
      // Deduped, so a down instance is reported once, not once a second. Safe
      // only because [[logConnectivity]] reports the transitions.
      .map(describe)
      .changes
      .evalMap(reason => log.info(s"attempt ended: $reason"))
      .concurrently(logConnectivity(connection, log))
      .compile
      .drain

  /** On connectivity, which alternates, so `changes` cannot swallow a
    * transition. Keyed on the reason, two same-cause drops an hour apart are
    * consecutive and the second is lost.
    */
  private def logConnectivity(
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      log: SelfAwareStructuredLogger[IO]
  ): Stream[IO, Nothing] =
    connection.discrete
      .map(_.isDefined)
      .changes
      .zipWithPrevious
      .collect {
        // A leading `false` is the starting state.
        case (prev, true) if !prev.contains(true) =>
          "connected; subscribed to entity feed"
        case (Some(true), false) => "connection lost; retrying"
      }
      .evalMap(msg => log.info(msg))
      .drain

  private def describe(outcome: Either[Throwable, Unit]): String =
    outcome.fold(
      err =>
        s"${err.getClass.getName} ${Option(err.getMessage).getOrElse(err.toString)}",
      _ => "closed cleanly"
    )

  /** No separate seeding: `subscribe_entities` opens with the full set, so a
    * reconnect's catch-up is its first frame, and anything lost in the outage
    * is superseded rather than replayed.
    */
  private def runConnection(
      connect: Connect,
      connection: SignallingRef[IO, Option[HAWSApiLowLevel[IO]]],
      seeded: Deferred[IO, Unit],
      store: StateStore,
      wanted: Signal[IO, Option[Set[String]]],
      tracer: Tracer[IO],
      meters: Meters
  ): IO[Unit] =
    connect
      .use { case (ll, awaitClosed) =>
        // On `ll`, not the facade, which still points at the old connection.
        val live = subscriptions(
          HomeAssistantApi.fromWs(ll),
          wanted,
          store,
          seeded,
          connection.set(Some(ll)),
          tracer,
          meters
        )
        // The whole lifetime: a socket dying while subscribing must end the
        // run too.
        live.race(awaitClosed).void
      }
      .guarantee(connection.set(None))

  /** A chunk is one coalesced HA frame, so a burst costs one `ref.modify`. The
    * feed opens with the full set, so the first batch means seeded.
    */
  private def pump(
      frames: Stream[IO, EntitiesEvent],
      store: StateStore,
      seeded: Deferred[IO, Unit],
      tracer: Tracer[IO],
      meters: Meters
  ): Stream[IO, Unit] =
    frames.chunks
      .evalMap(batch =>
        // Per batch, not per entity: one arrival, not a crowd.
        tracer
          .span(
            "ha.entities.apply",
            Attribute("fh.entities", batch.size.toLong)
          )
          .surround(store.applyEntities(batch)) *>
          meters.haEntities.add(batch.size.toLong)
      )
      .evalTap(_ => seeded.complete(()).void)

  /** One subscription, reopened when the watched set changes. The gap loses
    * nothing, as with a reconnect. An empty set opens none: an empty
    * `entity_ids` means the whole house to HA.
    */
  private def subscriptions(
      ha: HomeAssistantApi[IO],
      wanted: Signal[IO, Option[Set[String]]],
      store: StateStore,
      seeded: Deferred[IO, Unit],
      established: IO[Unit],
      tracer: Tracer[IO],
      meters: Meters
  ): IO[Unit] =
    Stream
      .eval(IO.deferred[Unit])
      .flatMap { ended =>
        wanted.discrete.changes
          .switchMap {
            // Not `Stream.empty`: an end means the feed died (below), and an
            // instance with no dashboards would reconnect in a loop.
            case Some(ids) if ids.isEmpty => Stream.never[IO]
            case only                     =>
              Stream
                .resource(ha.entities(only))
                .evalTap(_ => established)
                .flatMap(pump(_, store, seeded, tracer, meters)) ++
                // Ending on its own means the connection is gone, so end the
                // run; a rotation interrupts instead and never reaches this.
                Stream.exec(ended.complete(()).void)
          }
          .interruptWhen(ended.get.attempt)
      }
      .compile
      .drain

  /** Pure routing; it strands no caller. Subscriptions are deliberately not
    * durable: that would duplicate the supervisor's reconnect logic.
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

      // The facade outlives every connection.
      def awaitClosed: IO[Unit] = IO.never
    }
  }
}
