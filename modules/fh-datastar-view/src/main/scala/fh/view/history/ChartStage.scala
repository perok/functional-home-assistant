package fh.view.history

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import io.circe.Json

/** How a series becomes bytes — a function rather than [[ChartRenderer]], so a
  * cache test needs no JavaScript engine.
  */
type ChartDraw = (Series, ChartStyle) => IO[String]

/** The built-in drawing stage: a provider's answer in, SVG out. It thins the
  * answer first, since how many points a drawing needs is the drawing's; the
  * provider answers every point, which a computation over the same question
  * wants. Uncached — `FetchResolver` caches every stage's output alike.
  */
object ChartStage {

  def draw(renderer: IO[ChartDraw], style: ChartStyle, data: Json): IO[String] =
    data
      .as[Series]
      .leftMap(e =>
        FHError.internal(
          s"the chart stage cannot read its provider's answer: ${e.getMessage}"
        )
      )
      .liftTo[IO]
      .map(s =>
        s.copy(points = Downsample.lttb(s.points, Downsample.DefaultTarget))
      )
      .flatMap(s => renderer.flatMap(_(s, style)))
}
