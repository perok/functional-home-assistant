package fh.view.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fh.view.history.{ChartRenderer, ChartStyle, Downsample, Series}
import org.graalvm.polyglot.Engine

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Using

/** The add-on image's JavaScript, end to end: boots the isolate with no
  * fallback, runs a line of JavaScript, draws a chart, and prints what each
  * cost.
  *
  * CI runs it inside the built image, the only place that proves the staged
  * library is the right architecture and links against the base image's glibc —
  * every way of getting that wrong builds cleanly and dies at the first chart.
  * The timings are the Pi numbers ADR 0032 lacks.
  *
  * Memory comes from `/proc/self/smaps_rollup` because the isolate's heap is
  * native memory in a `dlopen`ed library, invisible to the heap MXBeans and to
  * `NativeMemoryTracking`.
  */
object JsIsolateCheck extends IOApp.Simple {

  private val rollup = os.root / "proc" / "self" / "smaps_rollup"

  def run: IO[Unit] =
    for {
      _ <- memory("before")
      t0 <- IO.monotonic
      _ <- JsIsolate.engine.use { engine =>
        for {
          _ <- IO.println(s"engine        ${engine.getVersion}")
          _ <- sameVersion(engine)
          _ <- JsIsolate.context(engine).use { context =>
            IO.blocking(
              context.eval("js", "[1, 2, 3].map(x => x * 2).join()").asString()
            ).flatMap(out => IO.println(s"javascript    $out"))
          }
          _ <- ChartRenderer.fromEngine(engine).use { renderer =>
            for {
              t1 <- IO.monotonic
              _ <- IO.println(s"+ echarts     ${(t1 - t0).toMillis} ms")
              first <- timed(renderer.render(chartSeries, ChartStyle()))
              _ <- IO.println(
                s"first chart   ${first._2} ms, ${first._1.length} bytes"
              )
              warm <- (1 to 5).toList
                .traverse(_ =>
                  timed(renderer.render(chartSeries, ChartStyle()))
                )
              _ <- IO.println(
                s"warm charts   ${warm.map(_._2).mkString(", ")} ms"
              )
              _ <- IO
                .raiseError(
                  new IllegalStateException(
                    s"not an SVG: ${first._1.take(120)}"
                  )
                )
                .unlessA(
                  first._1.startsWith("<svg") && first._1.contains("<path")
                )
            } yield ()
          }
          _ <- memory("after")
        } yield ()
      }
    } yield ()

  private val chartSeries: Series = {
    val raw = (0 until 2000).map(i =>
      Series.Point(
        Instant.ofEpochSecond(1789755910L + i * 60L),
        20 + 5 * Math.sin(i / 50.0)
      )
    )
    Series(Downsample.lttb(raw.toVector, Downsample.DefaultTarget), 0)
  }

  private def timed(io: IO[String]): IO[(String, Long)] =
    IO.monotonic.flatMap(a =>
      io.flatMap(out => IO.monotonic.map(b => (out, (b - a).toMillis)))
    )

  /** Truffle does not report a library/jar version mismatch — 25.2.4 against
    * 25.3.4.1 runs clean (measured) — but `getVersion` is the LIBRARY's, so it
    * is detectable here. Guards a stale `target/addon` beside a fresh jar.
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

  /** `Rss` is what the supervisor reports; `Anonymous` is the part that cannot
    * be reclaimed.
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
          .flatMap(
            _.traverse_(l => IO.println(s"${when.padTo(14, ' ')}${l.trim}"))
          )
    }
}
