package fh.view.history

import io.circe.{Decoder, Json}
import io.circe.derivation.{Configuration, ConfiguredDecoder}
import io.circe.syntax.*

/** Everything about a chart that is not its data.
  *
  * Every colour is a CSS custom property, never a literal: the SVG is drawn
  * once and shared by every viewer, so a light/dark switch or a theme's palette
  * has to reach it through the page's CSS rather than through the bytes. A
  * theme sets `--fh-chart-*`; left unset, each falls back to the base tokens.
  */
final case class ChartStyle(
    width: Int = 400,
    height: Int = 160,
    line: String = "var(--fh-chart-line, var(--fh-accent))",
    fill: Option[String] = Some("var(--fh-chart-fill, transparent)"),
    /** The entity's own `unit_of_measurement`, so it agrees with the card. */
    unit: Option[String] = None
)

object ChartStyle {

  private given Configuration = Configuration.default.withDefaults

  /** `components/history.pkl`'s `ChartParams`; an absent field is the default
    * here. Pkl already refuses a size that is not positive, which this repeats
    * for a hand-written wire.
    */
  given Decoder[ChartStyle] =
    ConfiguredDecoder
      .derived[ChartStyle]
      .ensure(s => s.width > 0 && s.height > 0, "chart size must be positive")
}

/** The ECharts option object, built in Scala so what a chart says is tested on
  * `Json` rather than on SVG.
  *
  * Not cosmetic: `animation: false` (SSR renders one frame, which would be the
  * start of every transition), and a `time` x axis (recorder rows are written
  * when a value moves, so the gaps mean something).
  */
object ChartOption {

  def apply(series: Series, style: ChartStyle): Json = {
    val data = Json.arr(
      series.points.map(p =>
        Json.arr(Json.fromLong(p.at.toEpochMilli), doubleOrNull(p.value))
      )*
    )

    // `inherit` so the page's font draws the labels. zrender writes it into a
    // `font:` shorthand on the time axis, where it is invalid and dropped —
    // which also inherits, and the card's CSS pins the size.
    val label = Json.obj(
      "color" -> "var(--fh-chart-label, var(--fh-text-dim))".asJson,
      "fontFamily" -> "inherit".asJson
    )
    Json.obj(
      "animation" -> Json.False,
      "textStyle" -> Json.obj("fontFamily" -> "inherit".asJson),
      // No title or legend: the card around the chart already labels it. The
      // top margin is the unit's: it is drawn above the axis, and at 12 it
      // fell outside the picture.
      "grid" -> Json.obj(
        "left" -> Json.fromInt(if (style.unit.isDefined) 44 else 36),
        "right" -> Json.fromInt(12),
        "top" -> Json.fromInt(if (style.unit.isDefined) 26 else 12),
        "bottom" -> Json.fromInt(24)
      ),
      "xAxis" -> Json.obj(
        "type" -> "time".asJson,
        "axisLine" -> Json.obj("show" -> Json.False),
        "axisTick" -> Json.obj("show" -> Json.False),
        "splitLine" -> Json.obj("show" -> Json.False),
        "axisLabel" -> label.deepMerge(Json.obj("hideOverlap" -> Json.True))
      ),
      "yAxis" -> Json.obj(
        "type" -> "value".asJson,
        // Not anchored at zero, or 21.4–21.6 °C is a flat line.
        "scale" -> Json.True,
        "splitNumber" -> Json.fromInt(3),
        "name" -> style.unit.fold(Json.Null)(Json.fromString),
        "nameLocation" -> "end".asJson,
        "nameTextStyle" -> label.deepMerge(Json.obj("align" -> "right".asJson)),
        "axisLabel" -> label,
        "splitLine" -> Json.obj(
          "show" -> Json.True,
          "lineStyle" -> Json.obj(
            "color" -> "var(--fh-chart-grid, var(--fh-border))".asJson
          )
        )
      ),
      "series" -> Json.arr(
        Json.obj(
          "type" -> "line".asJson,
          // HA state holds until it changes: a slope between two readings
          // claims values nobody measured.
          "step" -> "end".asJson,
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

  // Null for a non-finite value, which ECharts draws as a break in the line.
  private def doubleOrNull(d: Double): Json =
    Json.fromDouble(d).getOrElse(Json.Null)
}
