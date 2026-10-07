package fh.view.testkit

import api.homeassistant.ServiceTarget
import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.client.CommandPhase.*
import api.homeassistant.ws.domain.{EntitiesEvent, HistoryPoint, StatisticPoint}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cats.effect.std.Queue
import cats.effect.kernel.Ref
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json

import java.time.Instant
import scala.concurrent.duration.*

/** `entityId` is `""` for an area or floor call, which `group` carries. */
case class ServiceCall(
    domain: String,
    service: String,
    entityId: String,
    serviceData: Json,
    group: Option[ServiceTarget] = None
)

/** `callDelay` holds the response open, the window a busy-guard test clicks
  * inside; `failCalls` makes every `call_service` raise, which the server
  * answers as a refusal (the toast test's trigger). Both default off.
  * `recorder` answers history per entity in place of the synthetic line, for a
  * test about what was asked for or how a failure lands.
  */
final case class FakeConfig(
    callDelay: FiniteDuration = Duration.Zero,
    failCalls: Boolean = false,
    recorder: Option[(Instant, Instant, String) => IO[List[HistoryPoint]]] =
      None
)

/** Stubs the low-level WS API, the one seam
  * [[api.homeassistant.HomeAssistantApi.fromWs]] builds on, so consumers get a
  * genuine `HomeAssistantApi[IO]`. It answers the commands the runtime issues:
  *
  * `subscribe_entities` opens with the fixtures as one full frame, then the
  * deltas [[emit]] pushes; `subscribe_events` hands back a per-type queue
  * ([[pushRawEvent]]); the registry lists answer
  * [[fh.view.build.RegistryDump.fetch]], which joins them against that same
  * opening frame, so a Tier-A dashboard is built through the real
  * `prepareDumps` against the fixtures the feed serves; `call_service` is
  * recorded; the recorder's history is a synthetic line for numeric fixtures.
  *
  * Anything else raises `NotImplementedError`, so an unexpected command fails
  * loudly.
  */
final class FakeHomeAssistant private (
    stateRef: Ref[IO, Map[String, FixtureEntity]],
    // Created on first subscribe and shared by every re-subscribe, so an event
    // pushed during a reconnect gap is buffered, not lost.
    queues: Ref[IO, Map[String, Queue[IO, Json]]],
    calls: Ref[IO, Vector[ServiceCall]],
    // One for the fake's lifetime, so a delta pushed during a reconnect gap is
    // buffered.
    deltas: Queue[IO, EntitiesEvent],
    // Stamped as each emit's `last_updated`, so a change always passes
    // StateStore's recency guard.
    clock: Ref[IO, Long],
    eventSubscribes: SignallingRef[IO, Int],
    // The feed re-subscribes when the entity set the dashboards read changes,
    // and a filtered feed delivers the same fixtures as an unfiltered one, so
    // this is the only way a test sees the narrowing.
    entitySubscribes: SignallingRef[IO, Vector[Option[List[String]]]],
    // See [[dropConnection]].
    generation: SignallingRef[IO, Int],
    config: FakeConfig
) extends HAWSApiLowLevel[IO] {

  /** Every subscription opened on the current generation ends, as the real
    * transport's `None` sentinel makes it. Otherwise a consumer that must
    * re-subscribe looks identical to one that need not.
    */
  def dropConnection: IO[Unit] = generation.update(_ + 1)

  private def forThisConnection[A](s: Stream[IO, A]): IO[Stream[IO, A]] =
    generation.get.map(mine => s.interruptWhen(generation.map(_ != mine)))

  /** The supervisor reconnecting happens strictly before the subscription
    * re-arms, and an event pushed between them can legitimately be lost.
    */
  def awaitEventSubscribes(n: Int): IO[Unit] =
    eventSubscribes.discrete.find(_ >= n).head.compile.drain

  private def queueFor(eventType: String): IO[Queue[IO, Json]] =
    queues.get.map(_.get(eventType)).flatMap {
      case Some(q) => IO.pure(q)
      case None    =>
        Queue
          .unbounded[IO, Json]
          .flatMap(q => queues.update(_.updated(eventType, q)).as(q))
    }

  def sendCommand[Response](
      command: CommandPhase & CommandResponse.WithSingleResponse[Response]
  ): IO[Response] =
    command match {
      case cs: `call_service` =>
        calls
          .update(
            _ :+ (cs.target match {
              case ServiceTarget.Entity(id) =>
                ServiceCall(cs.domain, cs.service, id, cs.service_data)
              case group =>
                ServiceCall(
                  cs.domain,
                  cs.service,
                  "",
                  cs.service_data,
                  Some(group)
                )
            })
          )
          .flatMap(_ => delayedOrFailedResponse)
          .as(Json.obj())

      // Fixtures declare entities, never registry rows, so these are empty: a
      // faithful answer, since the dump's join runs from the state snapshot. A
      // test that needs areas or devices fills the list in.
      case _: `config/entity_registry/list` =>
        IO.pure(Nil)
      case _: `config/device_registry/list` =>
        IO.pure(Nil)
      case _: `config/area_registry/list` =>
        IO.pure(Nil)
      case _: `config/floor_registry/list` =>
        IO.pure(Nil)
      // Suites that care about users seed them through the dump they build.
      case _: `config/auth/list` =>
        IO.pure(Nil)

      case h: `history/history_during_period` =>
        config.recorder.fold(syntheticHistory(h))(recorder =>
          h.entity_ids
            .traverse(id => recorder(h.start_time, h.end_time, id).map(id -> _))
            .map(_.toMap)
        )
      case _: `recorder/statistics_during_period` =>
        IO.pure(Map.empty[String, List[StatisticPoint]])

      case _ => na
    }

  /** Every numeric fixture at its current state, with a ripple so a chart has a
    * line, sampled across the asked span. No statistics, so `History` charts
    * the raw history.
    */
  private def syntheticHistory(
      h: `history/history_during_period`
  ): IO[Map[String, List[HistoryPoint]]] =
    stateRef.get.map { states =>
      h.entity_ids.flatMap { id =>
        states.get(id).flatMap(_.state.toDoubleOption).map { v =>
          val step =
            (h.end_time.toEpochMilli - h.start_time.toEpochMilli) / 30
          id -> List.tabulate(31) { i =>
            HistoryPoint(
              (v + math.sin(i / 3.0)).toString,
              h.start_time.plusMillis(step * i)
            )
          }
        }
      }.toMap
    }

  def subscribeStream[Result](
      msg: CommandPhase & CommandResponse.AsStream[Result]
  ): Resource[IO, Stream[IO, Result]] =
    msg match {
      case s: `subscribe_entities` =>
        // The opening frame comes from the same fixtures as the dump, which
        // keeps built-against and served state identical. The filter is
        // applied, not just recorded, or a wrong entity set would fail only
        // against a real instance.
        val only = s.entity_ids.map(_.toSet)
        Resource.eval(
          entitySubscribes.update(_ :+ s.entity_ids) *>
            forThisConnection(
              // The opening frame is sent even when empty: it tells the feed it
              // is seeded. Only deltas are dropped when filtered out, as HA
              // does.
              Stream.eval(fullSet.map(narrow(_, only))) ++
                Stream
                  .fromQueueUnterminated(deltas)
                  .map(narrow(_, only))
                  .filter(e =>
                    e.added.nonEmpty || e.changed.nonEmpty || e.removed.nonEmpty
                  )
            )
        )
      case subscribe_events(Some(eventType)) =>
        Resource.eval(
          queueFor(eventType)
            .flatTap(_ => eventSubscribes.update(_ + 1))
            .flatMap(q => forThisConnection(Stream.fromQueueUnterminated(q)))
        )
      case _ => naR
    }

  // The never-closing `Connect` in `TestServer` supplies `awaitClosed`.
  def awaitClosed: IO[Unit] = IO.never

  /** `None` is unfiltered, which is also what HA does with an empty
    * `entity_ids`, and why production refuses to send one.
    */
  private def narrow(
      e: EntitiesEvent,
      only: Option[Set[String]]
  ): EntitiesEvent =
    only.filter(_.nonEmpty).fold(e) { ids =>
      EntitiesEvent(
        added = e.added.filter { case (id, _) => ids(id) },
        changed = e.changed.filter { case (id, _) => ids(id) },
        removed = e.removed.filter(ids)
      )
    }

  def entitySubscriptions: IO[Vector[Option[List[String]]]] =
    entitySubscribes.get

  /** The readiness seam for a re-subscribe, which is otherwise racy to observe.
    */
  def awaitEntitySubscribes(n: Int): IO[Unit] =
    entitySubscribes.discrete.filter(_.sizeIs >= n).head.compile.drain

  /** Stamped with the current tick, so a reconnect's frame is never older than
    * what the store holds.
    */
  private def fullSet: IO[EntitiesEvent] =
    (stateRef.get, clock.get).mapN { (current, tick) =>
      EntitiesEvent(added =
        current.values.map(_.toFeedEntry(FixtureEntity.epochAt(tick))).toMap
      )
    }

  /** Update the fixture and push the matching delta, as a real
    * `subscribe_entities` frame would.
    */
  def emit(
      entityId: String,
      state: String,
      attributes: Map[String, Json] = Map.empty
  ): IO[Unit] =
    emitFrame(List(FixtureEntity(entityId, state, attributes)))

  /** Several entities in one frame, which the feed applies as one batch. */
  def emitFrame(nexts: List[FixtureEntity]): IO[Unit] =
    clock.updateAndGet(_ + 1).flatMap { tick =>
      stateRef
        .modify { current =>
          val changed = nexts.map { next =>
            val prev = current.getOrElse(
              next.entityId,
              FixtureEntity(next.entityId, "unknown", Map.empty)
            )
            next.entityId -> next.deltaFrom(prev, FixtureEntity.epochAt(tick))
          }
          (current ++ nexts.map(n => n.entityId -> n), changed.toMap)
        }
        .flatMap(changed => deltas.offer(EntitiesEvent(changed = changed)))
    }

  /** The registry-watch analogue of [[emit]]. */
  def pushRawEvent(eventType: String, payload: Json): IO[Unit] =
    queueFor(eventType).flatMap(_.offer(payload))

  def recordedCalls: IO[Vector[ServiceCall]] = calls.get

  def resetCalls: IO[Unit] = calls.set(Vector.empty)

  private def delayedOrFailedResponse: IO[Unit] =
    if (config.failCalls)
      IO.raiseError(
        new RuntimeException("call_service rejected by the fake")
      )
    else if (config.callDelay > Duration.Zero) IO.sleep(config.callDelay)
    else IO.unit

  private def na: IO[Nothing] =
    IO.raiseError(
      new NotImplementedError("FakeHomeAssistant: unexpected WS command")
    )
  private def naR: Resource[IO, Nothing] = Resource.eval(na)
}

object FakeHomeAssistant {

  /** Unbounded event queue: tests emit a handful of changes. */
  def create(
      seed: List[FixtureEntity],
      config: FakeConfig = FakeConfig()
  ): IO[FakeHomeAssistant] =
    for {
      stateRef <- Ref[IO].of(seed.map(e => e.entityId -> e).toMap)
      queues <- Ref[IO].of(Map.empty[String, Queue[IO, Json]])
      calls <- Ref[IO].of(Vector.empty[ServiceCall])
      deltas <- Queue.unbounded[IO, EntitiesEvent]
      clock <- Ref[IO].of(0L)
      eventSubscribes <- SignallingRef[IO].of(0)
      entitySubscribes <- SignallingRef[IO].of(
        Vector.empty[Option[List[String]]]
      )
      generation <- SignallingRef[IO].of(0)
    } yield new FakeHomeAssistant(
      stateRef,
      queues,
      calls,
      deltas,
      clock,
      eventSubscribes,
      entitySubscribes,
      generation,
      config
    )
}
