package fh.view.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*

/** Boots the isolate, runs a line of JavaScript and prints what it cost.
  *
  * Run TWICE, and the first time is not a test: the image build runs it with
  * `polyglot.engine.userResourceCache` aimed at a staging directory, and
  * unpacking is simply what booting a cold engine does. That the bootstrap and
  * the check are one command is the point — the build cannot stage a library it
  * could not run. The second run is inside the finished image.
  *
  * It prints memory because nothing else can see this: the isolate's heap is
  * native memory inside a `dlopen`ed library, invisible to the heap MXBeans and
  * to `NativeMemoryTracking` alike. `/proc/self/smaps_rollup` sees it.
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

  /** `Rss` is what the supervisor reports; `Anonymous` is the unreclaimable
    * part of it, which is what decides whether this fits on a 4 GB machine.
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
