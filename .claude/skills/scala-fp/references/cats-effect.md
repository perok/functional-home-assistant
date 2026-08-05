# Cats Effect 3 Reference

## Setup

```scala
// build.sbt
libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect"     % "3.5.7",
  "org.typelevel" %% "cats-effect-std" % "3.5.7",  // Queue, Semaphore, etc.
)
```

## IOApp — the entry point

```scala
import cats.effect.*

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    IO.println("Hello, Cats Effect!")

// Or with full control over exit code:
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    myProgram.as(ExitCode.Success)
```

## Building IO values

```scala
import cats.effect.IO
import scala.concurrent.duration.*

IO.pure(42)                      // Lift pure value (must be truly pure!)
IO(sideEffect())                 // Suspend synchronous side effect
IO.delay(sideEffect())           // Alias for IO(...)
IO.unit                          // IO[Unit] = IO.pure(())
IO.never                         // IO that never completes
IO.sleep(1.second)               // Suspend for duration
IO.realTime                      // Current time as FiniteDuration
IO.realTimeInstant               // java.time.Instant
IO.println("msg")                // Print to stdout
IO.raiseError(new Exception("")) // Fail with exception
IO.canceled                      // A cancelled IO
IO.cede                          // Yield to other fibers (fairness)

// Blocking I/O — runs on a separate blocking thread pool:
IO.blocking { scala.io.Source.fromFile("file.txt").mkString }
```

## Composing effects

```scala
for {
  a <- IO(compute())
  b <- IO(computeWith(a))
  _ <- IO.println(s"result: $b")
} yield b

// Map / flatMap:
IO(1).map(_ + 1)                      // IO[Int]
IO(1).flatMap(n => IO(n + 1))         // IO[Int]
IO(1) *> IO(2)                        // sequence, discard left (IO[Int])
IO(1) <* IO(println("side"))          // sequence, discard right (IO[Int])
IO.unit >> IO("next")                 // alias for *>

// Combining:
IO(1).both(IO("a"))                   // IO[(Int, String)] — parallel!
(IO(1), IO("a")).tupled               // IO[(Int, String)] — sequential
```

## Error handling

```scala
IO.raiseError[Int](new Exception("oh no"))
  .handleError(_ => 0)               // IO[Int] — recover with value

  .handleErrorWith(e => IO(log(e)) *> IO.pure(0))  // recover with IO

  .attempt                           // IO[Either[Throwable, Int]]

  .recover { case _: TimeoutException => 0 }

  .onError(e => IO.println(s"Failed: $e"))  // run effect on error, rethrow

// Ensure cleanup:
IO(resource).guarantee(IO(cleanup()))   // always run cleanup
IO(resource).guaranteeCase {
  case Outcome.Succeeded(_) => IO.println("success")
  case Outcome.Errored(e)   => IO.println(s"error: $e")
  case Outcome.Canceled()   => IO.println("canceled")
}
```

## Resource management

```scala
import cats.effect.Resource

val managed: Resource[IO, Connection] =
  Resource.make(
    IO(openConnection())   // acquire
  )(conn =>
    IO(conn.close())       // release — always called, even on error/cancel
  )

managed.use { conn =>
  conn.query("SELECT 1")
}

// Combining resources:
val bothResources: Resource[IO, (Connection, Cache)] =
  (connectionResource, cacheResource).tupled

// From AutoCloseable:
Resource.fromAutoCloseable(IO(new FileInputStream("file.txt")))

// As bracket (lower-level):
IO(open()).bracket(use)(release)
```

## Fibers — lightweight threads

```scala
import cats.effect.{IO, FiberIO}

// Start a fiber (don't block, run concurrently):
val program: IO[Unit] = for {
  fiber <- IO.sleep(2.seconds).flatMap(_ => IO.println("done")).start
  _     <- IO.println("fiber started")
  _     <- fiber.join        // semantically block until complete
} yield ()

// Cancel a fiber:
for {
  fiber  <- longRunningTask.start
  _      <- IO.sleep(1.second)
  _      <- fiber.cancel     // sends cancellation signal
} yield ()

// Outcome — result of a joined fiber:
import cats.effect.Outcome.*
fiber.join.flatMap {
  case Succeeded(ioa) => ioa                        // IO[A]
  case Errored(e)     => IO.raiseError(e)
  case Canceled()     => IO.raiseError(new Exception("canceled"))
}
```

## Cancellation & safety

```scala
// Uncancelable region — cannot be interrupted mid-way:
IO.uncancelable { poll =>
  for {
    _   <- IO(acquireCriticalResource())
    res <- poll(interruptibleWork)   // poll re-enables cancellation here
    _   <- IO(releaseCriticalResource())
  } yield res
}

// onCancel finalizer — always runs if fiber is canceled:
longTask.onCancel(IO.println("I was cancelled, cleaning up..."))
```

## Concurrency primitives

### Ref — atomic mutable reference
```scala
import cats.effect.Ref

val program = for {
  counter <- Ref[IO].of(0)
  _       <- counter.update(_ + 1)
  _       <- counter.update(_ + 1)
  value   <- counter.get
  _       <- IO.println(s"Counter: $value")  // 2
} yield ()

// Atomic modify — read + update atomically:
counter.modify(n => (n + 1, n))   // returns old value
counter.getAndUpdate(_ + 1)       // returns old value
counter.updateAndGet(_ + 1)       // returns new value
```

### Deferred — one-shot async signal
```scala
import cats.effect.Deferred

val program = for {
  signal <- Deferred[IO, String]
  fiber  <- (IO.sleep(1.second) *> signal.complete("ready")).start
  _      <- IO.println("waiting...")
  result <- signal.get      // semantically blocks until completed
  _      <- IO.println(s"Got: $result")
} yield ()
```

### Semaphore — limit concurrency
```scala
import cats.effect.std.Semaphore

val program = Semaphore[IO](3).flatMap { sem =>  // max 3 concurrent
  List.fill(10)(sem.permit.use(_ => doWork)).parSequence
}
```

### Queue — async message passing
```scala
import cats.effect.std.Queue

val program = for {
  queue   <- Queue.bounded[IO, Int](100)
  _       <- queue.offer(1)
  _       <- queue.offer(2)
  item    <- queue.take         // semantically blocks if empty
  _       <- IO.println(item)
} yield ()
```

## Supervisor — manage background fibers

```scala
import cats.effect.std.Supervisor

Supervisor[IO](await = false).use { supervisor =>
  for {
    _ <- supervisor.supervise(backgroundTask1)
    _ <- supervisor.supervise(backgroundTask2)
    _ <- IO.sleep(10.seconds)  // fibers run in background
  } yield ()
  // supervisor cancels all fibers on Resource release
}
```

## Runtime & thread model

Cats Effect 3 uses a **work-stealing scheduler** on the JVM:
- **Compute pool**: CPU-bound fibers (default: N threads where N = CPU cores)
- **Blocking pool**: for `IO.blocking { }` — unbounded, elastic
- **Scheduler thread**: for sleep/timeout

```scala
// Check which thread a fiber is on:
IO(Thread.currentThread().getName).flatMap(IO.println)

// Shift to a specific ExecutionContext:
IO.evalOn(IO(compute()), myExecutionContext)
```

### Where `IO.blocking` goes: at the origin, wrapped tightly

Two rules, and they pull in opposite directions, which is why both need saying.

**1. The origin declares it, never the call site.** The function that performs
the blocking work is the one that returns `IO` with `IO.blocking` inside. A
caller wrapping someone else's blocking function is a bug waiting to be
duplicated: the next caller will not know to wrap it, and nothing in the type
says they must.

```scala
// WRONG — the route knows a secret about listFiles that the type does not tell it
private def listFiles: String = os.list(dir).map(render).mkString
case GET -> Root / "files" => IO.blocking(listFiles).flatMap(Ok(_))

// RIGHT — listFiles owns its blocking; the route just calls it
private def listFiles: IO[String] = IO.blocking(scan()).map(render)
case GET -> Root / "files" => listFiles.flatMap(Ok(_))
```

Watch for the eager variant, which is worse than an unwrapped call because it
*looks* suspended: `IO.pure(os.read.bytes(p))` runs the read when the `IO` is
CONSTRUCTED, on whatever thread built it. `IO.pure` takes a value — if that
value costs a syscall, it is already too late. Same for `Option.when(cond)(read)`
handed to `.liftTo[IO]`.

**2. Wrap only what blocks.** `IO.blocking` is a pool shift, so everything
inside runs off the compute pool — including pure work that had no reason to
leave it. Scan in the region, transform outside.

```scala
// Loose: JSON encoding, sorting and rendering all run on the blocking pool
IO.blocking(os.list(dir).map(toJson).sorted.mkString)

// Tight: only the syscall is in the region
IO.blocking(os.list(dir).toList).map(_.map(toJson).sorted.mkString)
```

**The exception that keeps rule 1 honest**: a *synchronous* helper layer whose
callers already hold a region (a bootstrap step, a file-rewrite routine) should
stay synchronous — one region per step beats a dozen, and it cannot return `IO`
anyway if it is called from inside `IO.blocking`. Say so in its doc, name the
effectful entry points that are allowed to reach it, and make each of those own
its region. What you must not have is a function that blocks, does not say so,
and is reachable from a route.

**Why it matters beyond tidiness**: work parked on the compute pool delays every
other fiber on it, and cats-effect will tell you so —

> Your app's responsiveness to a new asynchronous event was in excess of 100
> milliseconds. Your CPU is probably starving.

That warning names blocking I/O explicitly, but a long *CPU-bound* step is just
as capable of causing it, and `IO.blocking` is the wrong tool there: the
blocking pool is unbounded, so handing it CPU-bound work (a template compiler, a
Pkl/Truffle evaluation) oversubscribes the cores rather than protecting them.
Bound that work to its own sized pool with `IO.evalOn`, or break it up with
`IO.cede`.

### A by-name parameter does not make an effect go away

Taking `render: => String` instead of `render: IO[String]` looks like a way to
say "this must be pure and cheap". It says nothing of the kind. The thunk can
`Thread.sleep`, take a lock, await a `java.util.concurrent` latch or spin for a
minute — the compiler cannot tell, and neither can the runtime, which is the
worse half: work parked inside a by-name thunk holds a compute worker with no
`IO.blocking` to hint at it and no `IO.cede` available to break it up.

```scala
// looks constrained, is not: this compiles and parks a compute worker
cache(key)( { latch.await(); html } )

// honest, and now the tools apply
cache(key)(IO.blocking(readFromDisk()))     // right pool
cache(key)(IO.cede *> IO(bigPureWalk()))    // fair
```

So make the effect typed and bound it where it needs bounding — `Semaphore` for
concurrency, `IO.evalOn` for the pool, `IO.cede` for fairness. An obligation the
type cannot express ("this render must be bounded") belongs in the doc as an
obligation, not disguised as a signature that appears to enforce it.
