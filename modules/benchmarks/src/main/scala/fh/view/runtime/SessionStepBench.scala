package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fh.view.model.NodeId
import fh.view.telemetry.Logging
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** What a session step costs beside the render it carries: the owner's round
  * trip, the publish, and the outlet a stream takes from. Against `lock*`, the
  * shape it replaced — a `Mutex` around the `Ref` reads and writes a pull made
  * and a queue offer. `*Fanout` is one tick reaching ten clients.
  *
  * Measured 2026-10-10, owner against lock, each with ~7 µs of `unsafeRunSync`:
  * a step 14.4 µs / 5.9 kB against 9.8 µs / 3.8 kB (the hop to the owner
  * fiber); a ten-client tick 46.6 µs / 80 kB against 40.9 µs / 59 kB, so
  * 0.6 µs and 2 kB a client, beside the ~70 µs and 117 kB each further client
  * costs to render (`RenderBench.resumeSignalsFanout`); a create and reap 25 µs
  * / 25 kB against 8 µs / 3 kB, once a page load.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 5 -i 5 -prof gc .*SessionStepBench.*'
  * }}}
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class SessionStepBench {
  import SessionStepBench.*

  private var close: IO[Unit] = IO.unit
  private var owned: List[(Session, Outlet)] = Nil
  private var locked: List[Locked] = Nil
  private var supervisor: Supervisor[IO] = null
  private val logger = Logging.console.getLoggerFromName("bench")

  @Setup(Level.Trial)
  def setup(): Unit = {
    val (sup, release) = Supervisor[IO].allocated.unsafeRunSync()
    supervisor = sup
    close = release
    owned = List
      .fill(Clients)(
        for {
          s <- Session.create("bench", supervisor, logger)
          o <- Outlet.create
          _ <- s.attach(o)
        } yield s -> o
      )
      .sequence
      .unsafeRunSync()
    locked = List.fill(Clients)(Locked.create).sequence.unsafeRunSync()
  }

  @TearDown(Level.Trial)
  def tearDown(): Unit = close.unsafeRunSync()

  @Benchmark
  def ownerStep(bh: Blackhole): Unit =
    bh.consume(ownerTick(owned.head).unsafeRunSync())

  @Benchmark
  def lockStep(bh: Blackhole): Unit =
    bh.consume(locked.head.tick.unsafeRunSync())

  @Benchmark
  def ownerStepFanout(bh: Blackhole): Unit =
    bh.consume(owned.parTraverse(ownerTick).unsafeRunSync())

  @Benchmark
  def lockStepFanout(bh: Blackhole): Unit =
    bh.consume(locked.parTraverse(_.tick).unsafeRunSync())

  /** Once per page load: the owner started on the supervisor, then reaped. */
  @Benchmark
  def createAndReap(bh: Blackhole): Unit =
    bh.consume(
      (for {
        s <- Session.create("bench", supervisor, logger)
        _ <- s.relinquish(Tenure.Fresh)
        refused <- s.run(st => IO.pure(Session.Step(st, Nil, ()))).attempt
      } yield refused).unsafeRunSync()
    )

  @Benchmark
  def lockCreate(bh: Blackhole): Unit =
    bh.consume(Locked.create.unsafeRunSync())
}

object SessionStepBench {

  val Clients = 10

  private val frame: SseFrame = Server.keepAliveComment

  /** A pull that owed one frame, and the stream taking it. */
  def ownerTick(so: (Session, Outlet)): IO[Vector[SseFrame]] = {
    val (session, outlet) = so
    session.run(s =>
      IO.pure(
        Session.Step(s.copy(position = s.position + 1), List(frame), ())
      )
    ) *> outlet.takeAll
  }

  /** The replaced shape: five reads and three writes under one lock, then the
    * frame queued for the stream.
    */
  final case class Locked(
      lock: Mutex[IO],
      open: Ref[IO, Set[String]],
      vars: Ref[IO, Map[VarKey, String]],
      holds: Ref[IO, Map[NodeId, Held]],
      position: Ref[IO, Long],
      told: Ref[IO, Long],
      control: Queue[IO, SseFrame]
  ) {
    def tick: IO[Option[SseFrame]] =
      lock.lock.surround(
        (open.get, vars.get, holds.get, position.get, told.get).flatMapN {
          (_, _, h, p, _) =>
            holds.set(h) *> told.set(p + 1) *> position.set(p + 1) *>
              control.offer(frame)
        }
      ) *> control.tryTake
  }

  object Locked {
    def create: IO[Locked] =
      (
        Mutex[IO],
        Ref[IO].of(Set.empty[String]),
        Ref[IO].of(Map.empty[VarKey, String]),
        Ref[IO].of(Map.empty[NodeId, Held]),
        Ref[IO].of(0L),
        Ref[IO].of(-1L),
        Queue.unbounded[IO, SseFrame]
      ).mapN(Locked.apply)
  }
}
