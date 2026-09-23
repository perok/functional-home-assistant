package fh.view.history

import io.circe.{Decoder, Json}
import io.circe.syntax.*

/** Everything about a chart that is not its data. */
final case class ChartStyle(
    width: Int = 600,
    height: Int = 180,
    line: String = "var(--fh-accent)",
    fill: Option[String] = None,
    /** The entity's own `unit_of_measurement`, so it agrees with the card. */
    unit: Option[String] = None
)

object ChartStyle {

  /** A chart stage's params, all strings on the wire because the stage's
    * `params` is a generic `Mapping<String, String>`.
    */
  given Decoder[ChartStyle] =
    Decoder[Map[String, String]].emap(parse)

  def parse(params: Map[String, String]): Either[String, ChartStyle] = {
    def int(name: String, fallback: Int): Either[String, Int] =
      params.get(name) match {
        case None    => Right(fallback)
        case Some(v) =>
          v.toIntOption.toRight(s"chart stage has non-numeric $name '$v'")
      }
    val d = ChartStyle()
    for {
      w <- int("width", d.width)
      h <- int("height", d.height)
    } yield ChartStyle(
      width = w,
      height = h,
      line = params.getOrElse("line", d.line),
      fill = params.get("fill"),
      unit = params.get("unit")
    )
  }
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

    Json.obj(
      "animation" -> Json.False,
      // No title or legend: the card around the chart already labels it.
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
        // Not anchored at zero, or 21.4–21.6 °C is a flat line.
        "scale" -> Json.True,
        "name" -> style.unit.fold(Json.Null)(Json.fromString),
        "nameLocation" -> "end".asJson,
        "splitLine" -> Json.obj("show" -> Json.True)
      ),
      "series" -> Json.arr(
        Json.obj(
          "type" -> "line".asJson,
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
