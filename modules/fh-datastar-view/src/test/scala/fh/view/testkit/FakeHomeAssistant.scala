package fh.view.testkit

import api.homeassistant.ws.HAWSApiLowLevel
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.client.CommandPhase.*
import api.homeassistant.ws.domain.EntitiesEvent
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cats.effect.std.Queue
import cats.effect.kernel.Ref
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json

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
  *   - `subscribeStream(subscribe_entities)` opens with the fixtures as one
  *     full state frame and then yields the deltas [[emit]] pushes — the feed
  *     [[fh.view.runtime.StateStore]] lives on,
  *   - `subscribeStream(subscribe_events …)` hands back a live per-type queue
  *     ([[pushRawEvent]]) for the registry watch,
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
    // One live queue per subscribed event type, created on first subscribe,
    // carrying the raw event objects that type yields — exactly what the real
    // low-level yields after extracting the `event` field. A per-type Queue (not
    // a fresh one per subscribe) is what makes a subscription DURABLE across
    // reconnects: a re-subscribe reads the SAME queue, so an event pushed during
    // the gap is buffered, not lost.
    queues: Ref[IO, Map[String, Queue[IO, Json]]],
    calls: Ref[IO, Vector[ServiceCall]],
    // The live delta feed. ONE queue for the lifetime of the fake, which is what
    // makes the entity subscription durable across a reconnect: a re-subscribe
    // reads the SAME queue, so a delta pushed during the gap is buffered.
    deltas: Queue[IO, EntitiesEvent],
    // Monotonic tick, stamped as each emit's `last_updated` so a change always
    // reads as newer than the opening full set and prior emits (StateStore's
    // recency guard).
    clock: Ref[IO, Long],
    // How many `subscribe_events` subscriptions have been opened, counting
    // re-subscribes. See [[awaitEventSubscribes]].
    eventSubscribes: SignallingRef[IO, Int],
    // Which CONNECTION generation subscriptions belong to. See [[dropConnection]].
    generation: SignallingRef[IO, Int]
) extends HAWSApiLowLevel[IO] {

  /** Model a dropped connection: every subscription opened on the current
    * generation ENDS, as the real transport's `None` sentinel makes it.
    *
    * The fake stands in for the low level, so without this its streams outlive
    * every connection and a consumer that must re-subscribe looks identical to
    * one that need not — which is exactly the distinction worth testing now
    * that subscriptions are not durable.
    */
  def dropConnection: IO[Unit] = generation.update(_ + 1)

  /** Bind a stream to the generation that opened it. */
  private def forThisConnection[A](s: Stream[IO, A]): IO[Stream[IO, A]] =
    generation.get.map(mine => s.interruptWhen(generation.map(_ != mine)))

  /** Block until `subscribe_events` has been subscribed `n` times.
    *
    * A reconnect test needs this because "the supervisor re-connected" happens
    * strictly BEFORE "the durable subscription re-armed" — pushing an event in
    * between races the seam, where an event taken from the dying connection but
    * not yet handed to the durable queue is legitimately lost.
    */
  def awaitEventSubscribes(n: Int): IO[Unit] =
    eventSubscribes.discrete.find(_ >= n).head.compile.drain

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
      command: CommandPhase & CommandResponse.WithSingleResponse[Response]
  ): IO[Response] =
    command match {
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

      // The four registries [[fh.view.build.RegistryDump]] joins against. A
      // fixture declares entities and their attributes, never registry rows, so
      // these are EMPTY — which is a faithful answer, not a stub: the dump's
      // join runs from the state snapshot, so every fixture entity still lands
      // in the dump, just with no area/floor/device/category. A test that needs
      // those fills the corresponding list in.
      case _: `config/entity_registry/list` =>
        IO.pure(List.empty).asInstanceOf[IO[Response]]
      case _: `config/device_registry/list` =>
        IO.pure(List.empty).asInstanceOf[IO[Response]]
      case _: `config/area_registry/list` =>
        IO.pure(List.empty).asInstanceOf[IO[Response]]
      case _: `config/floor_registry/list` =>
        IO.pure(List.empty).asInstanceOf[IO[Response]]

      case _ => na
    }

  def subscribeStream[Result](
      msg: CommandPhase & CommandResponse.AsStream[Result]
  ): Resource[IO, Stream[IO, Result]] =
    msg match {
      case _: `subscribe_entities` =>
        // Real HA opens the feed with the FULL entity set and then sends deltas,
        // so the stream is "current fixtures as one `a` frame" followed by the
        // live delta queue `emit` pushes to. Deriving the opening frame from the
        // same fixtures the dump comes from is what keeps built-against and
        // served state identical.
        Resource.eval(
          forThisConnection(
            Stream.eval(fullSet) ++ Stream.fromQueueUnterminated(deltas)
          ).map(_.asInstanceOf[Stream[IO, Result]])
        )
      case subscribe_events(Some(eventType)) =>
        // Both the store's state_changed feed and arbitrary rawEvents (the
        // registry watch) resolve to their persistent per-type queue.
        Resource.eval(
          queueFor(eventType)
            .flatTap(_ => eventSubscribes.update(_ + 1))
            .flatMap(q => forThisConnection(Stream.fromQueueUnterminated(q)))
            .map(_.asInstanceOf[Stream[IO, Result]])
        )
      case _: render_template =>
        // HA's render_template pushes `event` frames `{result, listeners}`;
        // `templateFunc` takes the first and reads `.result`. A `| tojson`
        // template renders `result` to a JSON STRING (which `DataDump.parseIfString`
        // reparses), so wrap the dump the same way real HA does. Derived from the
        // SAME fixtures the entity feed serves, so dump and live state can't
        // drift.
        Resource.eval(
          rawDump.map(dump =>
            Stream
              .emit(Json.obj("result" -> Json.fromString(dump.noSpaces)))
              .covary[IO]
              .asInstanceOf[Stream[IO, Result]]
          )
        )
      case _ => naR
    }

  // The fake never "closes" — the never-closing `Connect` in `TestServer`
  // supplies the supervisor's `awaitClosed`; this is only here to satisfy the
  // trait.
  def awaitClosed: IO[Unit] = IO.never

  /** The current fixtures as a `subscribe_entities` opening frame: every entity
    * with its complete state, stamped with the current tick so a reconnect's
    * frame is never older than what the store already holds.
    */
  private def fullSet: IO[EntitiesEvent] =
    (stateRef.get, clock.get).mapN { (current, tick) =>
      EntitiesEvent(added =
        current.values.map(_.toFeedEntry(FixtureEntity.epochAt(tick))).toMap
      )
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

  /** Apply one change over time: update the fixture and push the matching feed
    * DELTA, exactly as a real `subscribe_entities` frame would. The store's
    * background fiber picks it up and re-renders dependents.
    */
  def emit(
      entityId: String,
      state: String,
      attributes: Map[String, Json] = Map.empty
  ): IO[Unit] =
    clock.updateAndGet(_ + 1).flatMap { tick =>
      stateRef
        .modify { current =>
          val prev = current.getOrElse(
            entityId,
            FixtureEntity(entityId, "unknown", Map.empty)
          )
          val next = FixtureEntity(entityId, state, attributes)
          (current.updated(entityId, next), (prev, next))
        }
        .flatMap { (prev, next) =>
          deltas.offer(
            EntitiesEvent(
              changed = Map(
                entityId -> next.deltaFrom(prev, FixtureEntity.epochAt(tick))
              )
            )
          )
        }
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
      deltas <- Queue.unbounded[IO, EntitiesEvent]
      clock <- Ref[IO].of(0L)
      eventSubscribes <- SignallingRef[IO].of(0)
      generation <- SignallingRef[IO].of(0)
    } yield new FakeHomeAssistant(
      stateRef,
      queues,
      calls,
      deltas,
      clock,
      eventSubscribes,
      generation
    )
}
