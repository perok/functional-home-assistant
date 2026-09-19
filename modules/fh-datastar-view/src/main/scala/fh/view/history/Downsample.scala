package fh.view.history

/** Largest-Triangle-Three-Buckets.
  *
  * Downsampling happens HERE, before the chart, and not through ECharts'
  * `sampling: 'lttb'`, for two reasons that have nothing to do with which
  * implementation is better: the reduced series is what gets cached and shared
  * between every viewer in a bucket, and it is what bounds the cost of crossing
  * into the JavaScript isolate. Leaving it to the chart would carry thousands
  * of points through the process and across that boundary every render.
  *
  * LTTB rather than "every Nth point" because a chart's job is the shape, and
  * uniform sampling drops spikes exactly when they are the only interesting
  * thing on the line. It keeps the first and last points, so the axis still
  * ends where the data does.
  */
object Downsample {

  /** ~300 points: enough that a 600px-wide chart is sampling below its own
    * pixel resolution, small enough that the SVG stays a few tens of KB.
    */
  val DefaultTarget: Int = 300

  def lttb(points: Vector[Series.Point], target: Int): Vector[Series.Point] =
    // Below three there are no interior buckets to pick from, and at or above
    // the input size there is nothing to drop.
    if (target < 3 || points.length <= target) points
    else {
      val out = Vector.newBuilder[Series.Point]
      out.sizeHint(target)
      out += points.head

      val every = (points.length - 2).toDouble / (target - 2)
      var anchor = 0

      var i = 0
      while (i < target - 2) {
        // The NEXT bucket's centre of mass, which is the third corner of the
        // triangle. Bounded at the last interior index so the final bucket
        // does not reach past the point `out` already ends with.
        val nextFrom = Math.floor((i + 1) * every).toInt + 1
        val nextTo =
          Math.min(Math.floor((i + 2) * every).toInt + 1, points.length - 1)
        val nextCount = Math.max(nextTo - nextFrom, 1)
        var avgX = 0.0
        var avgY = 0.0
        var j = nextFrom
        while (j < nextFrom + nextCount && j < points.length) {
          avgX += seconds(points(j))
          avgY += points(j).value
          j += 1
        }
        avgX /= nextCount
        avgY /= nextCount

        // This bucket: keep whichever point makes the biggest triangle with
        // the previously kept point and that centre of mass.
        val from = Math.floor(i * every).toInt + 1
        val to = Math.min(Math.floor((i + 1) * every).toInt + 1, points.length - 1)
        val aX = seconds(points(anchor))
        val aY = points(anchor).value
        var best = from
        var bestArea = -1.0
        var k = from
        while (k < to) {
          val area = Math.abs(
            (aX - avgX) * (points(k).value - aY) - (aX - seconds(points(k))) * (avgY - aY)
          )
          if (area > bestArea) { bestArea = area; best = k }
          k += 1
        }
        out += points(best)
        anchor = best
        i += 1
      }

      out += points.last
      out.result()
    }

  /** The x axis in seconds. Epoch MILLIS would overflow nothing but makes the
    * triangle areas three orders of magnitude larger for no gain; the areas are
    * only ever compared with each other.
    */
  private def seconds(p: Series.Point): Double =
    p.at.getEpochSecond.toDouble + p.at.getNano / 1e9
}
