package fh.view.history

import cats.effect.{IO, IOApp}
import cats.syntax.all.*

import java.time.Instant

/** Renders one chart on the engine the add-on actually ships.
  *
  * `ChartSuite` covers what a chart SAYS, on an in-heap engine, because the
  * polyglot isolate exists only inside this image. What it cannot cover is that
  * ECharts runs on the isolate at all — a different VM, a different JS
  * implementation, and the one that serves users. CI runs this inside the built
  * image for the same reason it runs `JsIsolateCheck` there: every way of
  * getting it wrong builds cleanly and dies at the first chart.
  *
  * It also prints the timings a Pi run needs (plan-history-view §6), which are
  * otherwise only measurable from a scratch harness that no longer exists.
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
    val reduced = Series(Downsample.lttb(series.points, Downsample.DefaultTarget), 0)

    for {
      t0 <- IO.monotonic
      _ <- ChartRenderer.resource.use { renderer =>
        for {
          t1 <- IO.monotonic
          _ <- IO.println(s"engine + echarts  ${(t1 - t0).toMillis} ms")
          first <- timed(renderer.render(reduced, ChartStyle()))
          _ <- IO.println(s"first render      ${first._2} ms, ${first._1.length} bytes")
          warm <- (1 to 5).toList.traverse(_ => timed(renderer.render(reduced, ChartStyle())))
          _ <- IO.println(
            s"warm renders      ${warm.map(_._2).mkString(", ")} ms"
          )
          svg = first._1
          _ <- IO
            .raiseError(new IllegalStateException(s"not an SVG: ${svg.take(120)}"))
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
