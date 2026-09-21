package fh.view.history

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fh.view.runtime.JsIsolate

import java.time.Instant

/** Renders one chart on the engine the add-on actually ships, and reports what
  * it cost.
  *
  * Strictly the ISOLATE, with no fallback, which is the whole point:
  * `ChartSuite` says what a chart SAYS on whichever engine the machine has, and
  * this says what the IMAGE's engine costs. Interpreted numbers printed under
  * these headings would be a measurement that lies rather than one that is
  * missing — and the staged library being the wrong architecture, or linked
  * against the wrong glibc, builds cleanly and dies at the first chart. CI runs
  * it inside the built image for the same reason it runs `JsIsolateCheck`
  * there.
  *
  * It also prints the timings a Pi run needs — ADR 0032's table is x86_64, and
  * its open question is what those numbers look like on the target hardware.
  * Otherwise they are only measurable from a scratch harness that no longer
  * exists.
  */
object ChartCheck extends IOApp.Simple {

  private val points = 2000

  def run: IO[Unit] = {
    val series = Series(
      (0 until points)
        .map(i =>
          Series.Point(
            Instant.ofEpochSecond(1789755910L + i * 60L),
            20 + 5 * Math.sin(i / 50.0)
          )
        )
        .toVector,
      0
    )
    val reduced =
      Series(Downsample.lttb(series.points, Downsample.DefaultTarget), 0)

    for {
      t0 <- IO.monotonic
      _ <- JsIsolate.engine.flatMap(ChartRenderer.fromEngine).use { renderer =>
        for {
          t1 <- IO.monotonic
          _ <- IO.println(s"engine + echarts  ${(t1 - t0).toMillis} ms")
          first <- timed(renderer.render(reduced, ChartStyle()))
          _ <- IO.println(
            s"first render      ${first._2} ms, ${first._1.length} bytes"
          )
          warm <- (1 to 5).toList
            .traverse(_ => timed(renderer.render(reduced, ChartStyle())))
          _ <- IO.println(
            s"warm renders      ${warm.map(_._2).mkString(", ")} ms"
          )
          svg = first._1
          _ <- IO
            .raiseError(
              new IllegalStateException(s"not an SVG: ${svg.take(120)}")
            )
            .unlessA(svg.startsWith("<svg") && svg.contains("<path"))
        } yield ()
      }
    } yield ()
  }

  private def timed(io: IO[String]): IO[(String, Long)] =
    for {
      a <- IO.monotonic
      out <- io
      b <- IO.monotonic
    } yield (out, (b - a).toMillis)
}
