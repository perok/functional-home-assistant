package fh.view.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import org.graalvm.polyglot.Engine

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

/** Boots the isolate, runs a line of JavaScript and prints what it cost.
  *
  * CI runs it inside the built image, which is the only thing that proves the
  * staged library is the right architecture and links against the base image's
  * glibc and zlib — every way of getting that wrong builds cleanly and dies at
  * the first chart. On a cold cache it also pays the one-time unpack, so it
  * measures that too.
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
            _ <- sameVersion(engine)
            out <- IO.blocking(
              context.eval("js", "[1, 2, 3].map(x => x * 2).join()").asString()
            )
            _ <- IO.println(s"javascript $out")
            _ <- memory("after")
          } yield ()
        }
      }
    } yield ()

  /** The isolate library and the polyglot jars are two halves of one engine,
    * and Truffle does NOT report a mismatch: a 25.2.4 library against 25.3.4.1
    * jars runs clean, with correct output (measured). What it does do is report
    * the LIBRARY's version from `getVersion`, while the jars carry their own on
    * the classpath — so the drift is detectable even though it is not reported,
    * and this is where we detect it.
    *
    * `build.sbt` resolves both from one string, so this should be unfireable.
    * It guards the gap where it isn't: a stale `target/addon` staged against a
    * freshly assembled jar.
    */
  private def sameVersion(engine: Engine): IO[Unit] =
    IO.blocking(
      Option(
        getClass.getResourceAsStream(
          "/META-INF/graalvm/org.graalvm.polyglot/version"
        )
      )
    ).flatMap {
      case None     => IO.unit
      case Some(in) =>
        IO.blocking(
          Using.resource(in)(s => String(s.readAllBytes(), UTF_8).trim)
        ).flatMap { jars =>
          IO.raiseError(
            new IllegalStateException(
              s"GraalJS drift: polyglot jars are $jars, the isolate library is ${engine.getVersion}"
            )
          ).unlessA(jars == engine.getVersion)
        }
    }

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
