package fh.view.history

import io.circe.Json
import io.circe.syntax.*

/** Everything about a chart that is not its data. */
final case class ChartStyle(
    width: Int = 600,
    height: Int = 180,
    line: String = "var(--fh-accent)",
    fill: Option[String] = None,
    /** Drawn beside the y axis. The entity's own `unit_of_measurement`; nothing
      * here derives it, because the card beside the chart reads the same field
      * and the two must agree.
      */
    unit: Option[String] = None
)

/** The ECharts option object, built here rather than in JavaScript.
  *
  * Pure, so it is where the tests are: everything about what a chart SAYS is
  * decided in Scala, and the guest does nothing but turn an option object into
  * SVG. A bug in the axis config is then an assertion on a `Json`, not a regex
  * over 20 KB of markup.
  *
  * Two settings are not cosmetic and must not be "cleaned up":
  *
  *   - `animation: false`. zrender starts an animation loop at init, and SSR
  *     renders one frame — with animation on, that frame is the START of every
  *     transition, so a chart renders empty or half-drawn.
  *   - `xAxis.type: "time"` with `[millis, value]` pairs rather than a category
  *     axis. A category axis would space points evenly whatever their
  *     timestamps, which is exactly wrong for recorder data: raw rows are
  *     written when a value MOVES, so the gaps carry meaning.
  */
object ChartOption {

  def apply(series: Series, style: ChartStyle): Json = {
    val data = Json.arr(
      series.points.map(p =>
        Json.arr(Json.fromLong(p.at.toEpochMilli), doubleOrNull(p.value))
      )*
    )

    Json.obj(
      "animation" -> Json.False,
      // No title, no legend, no toolbox: a chart inside a more-info popup is
      // already labelled by the card around it, and every pixel spent on chrome
      // is one not spent on the line.
      "grid" -> Json.obj(
        "left" -> Json.fromInt(if (style.unit.isDefined) 48 else 36),
        "right" -> Json.fromInt(12),
        "top" -> Json.fromInt(12),
        "bottom" -> Json.fromInt(24)
      ),
      "xAxis" -> Json.obj(
        "type" -> "time".asJson,
        "axisLine" -> Json.obj("show" -> Json.False),
        "axisTick" -> Json.obj("show" -> Json.False),
        "splitLine" -> Json.obj("show" -> Json.False)
      ),
      "yAxis" -> Json.obj(
        "type" -> "value".asJson,
        // `scale` so the line uses the height it has. A y axis anchored at zero
        // renders an indoor temperature as a flat line at the top of the box,
        // which is true and useless.
        "scale" -> Json.True,
        "name" -> style.unit.fold(Json.Null)(Json.fromString),
        "nameLocation" -> "end".asJson,
        "splitLine" -> Json.obj("show" -> Json.True)
      ),
      "series" -> Json.arr(
        Json.obj(
          "type" -> "line".asJson,
          // The points are already thinned to about the chart's pixel width,
          // so a symbol per point would be a solid band.
          "showSymbol" -> Json.False,
          "lineStyle" -> Json.obj(
            "width" -> Json.fromInt(2),
            "color" -> style.line.asJson
          ),
          "itemStyle" -> Json.obj("color" -> style.line.asJson),
          "areaStyle" -> style.fill.fold(Json.Null)(c =>
            Json.obj("color" -> c.asJson)
          ),
          "data" -> data
        )
      )
    )
  }

  /** A non-finite value has no place on an axis, and `Json.fromDouble` returns
    * `None` for one rather than emitting invalid JSON. Sent as null, which
    * ECharts renders as a break in the line.
    */
  private def doubleOrNull(d: Double): Json =
    Json.fromDouble(d).getOrElse(Json.Null)
}
