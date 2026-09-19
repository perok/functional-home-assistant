package fh.view.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*

/** `java -cp /opt/fh-dashboard.jar fh.view.runtime.JsIsolateCheck` — boots the
  * isolate, runs a line of JavaScript and prints what it cost.
  *
  * It exists because nothing else proves the shipped image works. A container
  * that BUILDS says nothing about whether the staged `.so` links against the
  * base image's glibc and zlib, or whether it is the right architecture; the CI
  * `image` job runs this inside the built image for exactly that reason, under
  * emulation on aarch64.
  *
  * The memory lines are the second reason. The isolate's heap is native memory
  * inside a `dlopen`ed library, so no JVM instrument can see it — not the heap
  * MXBeans, not `NativeMemoryTracking`, which accounts for the JVM's own native
  * allocation and not a foreign library's. `/proc/self/smaps_rollup` can, and
  * on a Pi this is the cheapest way to ask what the isolate actually costs on
  * that machine.
  */
object JsIsolateCheck extends IOApp.Simple {

  private val rollup = os.root / "proc" / "self" / "smaps_rollup"

  def run: IO[Unit] =
    for {
      _ <- memory("before")
      _ <- JsIsolate.engine.use { engine =>
        JsIsolate.context(engine).use { context =>
          for {
            _ <- IO.println(s"engine     ${engine.getVersion}")
            out <- IO.blocking(
              context.eval("js", "[1, 2, 3].map(x => x * 2).join()").asString()
            )
            _ <- IO.println(s"javascript $out")
            _ <- memory("after")
          } yield ()
        }
      }
    } yield ()

  /** `Rss` is what the supervisor reports; `Anonymous` is the part of it that
    * no amount of memory pressure can reclaim, which is the number that decides
    * whether this fits on a 4 GB machine.
    */
  private def memory(when: String): IO[Unit] =
    IO.blocking(os.exists(rollup)).flatMap {
      case false => IO.unit
      case true  =>
        IO.blocking(os.read.lines(rollup))
          .map(
            _.toList
              .filter(l => l.startsWith("Rss:") || l.startsWith("Anonymous:"))
          )
          .flatMap(_.traverse_(l => IO.println(s"$when      ${l.trim}")))
    }
}
