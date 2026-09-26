package fh.view.history

import com.ggalmazor.downsampling.{Downsampling, Point as LtPoint}

import scala.jdk.CollectionConverters.*

/** LTTB, applied before the series is cached and handed to the chart, so a
  * render never carries thousands of points into the JavaScript isolate.
  */
object Downsample {

  /** Below a 600px chart's pixel resolution, and a few tens of KB of SVG. */
  val DefaultTarget: Int = 300

  def lttb(points: Vector[Series.Point], target: Int): Vector[Series.Point] =
    // The library throws when the input cannot fill the buckets, and returns
    // `buckets + 2` points (first and last are always kept).
    if (target < 3 || points.length <= target) points
    else
      Downsampling
        .lttb(points.map(At(_)).asJava, target - 2)
        .asScala
        .iterator
        .map(_.point)
        .toVector

  private final case class At(point: Series.Point) extends LtPoint {
    // Seconds rather than millis keeps the triangle areas small; they are
    // only compared with each other.
    def x(): Double = point.at.getEpochSecond.toDouble + point.at.getNano / 1e9
    def y(): Double = point.value
  }
}
