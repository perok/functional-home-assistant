package fh.view.testkit

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Perceptual PNG snapshots for the component visual suite. Every asset is
  * fetched at a version pinned in its CDN URL and
  * [[fh.view.smoke.SmokeSuite.settle]] kills animations, so the only
  * cross-environment variance left is font rasterization. Hence a
  * [[Pixelmatch]] port (Playwright's `toHaveScreenshot` algorithm): a pixel
  * differs only past [[Threshold]] and when not anti-aliased in either image,
  * and the snapshot fails past [[MaxDiffRatio]]. Within ±[[MaxDimDelta]]px the
  * shared rectangle is compared; beyond it is a real reflow.
  *
  * Regenerate with `sbt dashboardVisualSnapshotsUpdate`, and normally don't: a
  * local baseline records this machine's rasterization. Let CI fail and decide
  * from its before/after artifact.
  */
object VisualSnapshot {

  private val snapshotDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" / "visual-snapshots"

  /** CI sets `FH_VISUAL_FAILURES_DIR` and uploads it, from a step before any
    * sbt one, since this runs in the sbt server and inherits whichever client
    * started it. The fallback under `target` assumes `os.pwd` is the repo root.
    */
  private val failureDir =
    sys.env
      .get("FH_VISUAL_FAILURES_DIR")
      .map(os.Path(_, os.pwd))
      .getOrElse(
        os.pwd / "modules" / "fh-datastar-view" / "target" / "visual-failures"
      )

  /** Pixelmatch's default. */
  private val Threshold = 0.1

  /** Small, since anti-aliased edges, the bulk of the noise, are already
    * excluded.
    */
  private val MaxDiffRatio = 0.003

  /** A content-derived box can round by a pixel under another rasterizer. */
  private val MaxDimDelta = 2

  /** Its own gate: sharing `FH_UPDATE_SNAPSHOTS` meant the routine wire
    * rebaseline silently rewrote these.
    */
  private def updating: Boolean =
    sys.env.get("FH_UPDATE_VISUAL_SNAPSHOTS").contains("1") ||
      sys.props.get("FH_UPDATE_VISUAL_SNAPSHOTS").contains("1")

  private def decode(bytes: Array[Byte]): BufferedImage =
    ImageIO.read(new ByteArrayInputStream(bytes))

  /** `maxDiffRatio` loosens the budget for a snapshot with a known
    * environment-dependent band, such as the slider's value-fill edge.
    */
  def check(
      name: String,
      actual: Array[Byte],
      maxDiffRatio: Double = MaxDiffRatio
  ): Unit = {
    val file = snapshotDir / s"$name.png"
    if (updating) {
      os.makeDir.all(snapshotDir)
      os.write.over(file, actual)
    } else if (!os.exists(file)) {
      // A new baseline has no before/after pair, so the only portable way to
      // mint one is to let CI take the shot and commit what it uploaded.
      os.makeDir.all(failureDir)
      os.write.over(failureDir / s"$name.actual.png", actual)
      throw new AssertionError(
        s"missing visual snapshot $file — $name.actual.png written to " +
          s"$failureDir. Commit THAT file as the baseline (CI's copy is the " +
          "portable one), or regenerate locally with " +
          "`sbt dashboardVisualSnapshotsUpdate` if this machine is the reference."
      )
    } else {
      val expectedImg = decode(os.read.bytes(file))
      val actualImg = decode(actual)

      def fail(reason: String): Nothing = {
        // The diff is sub-pixel; only the eye can judge it.
        os.makeDir.all(failureDir)
        os.copy.over(file, failureDir / s"$name.expected.png")
        os.write.over(failureDir / s"$name.actual.png", actual)
        throw new AssertionError(
          s"visual snapshot for $name.png changed ($reason). before/after written to " +
            s"$failureDir ($name.expected.png / $name.actual.png) for review. If " +
            "intended, regenerate with `sbt dashboardVisualSnapshotsUpdate`."
        )
      }

      val dw = math.abs(expectedImg.getWidth - actualImg.getWidth)
      val dh = math.abs(expectedImg.getHeight - actualImg.getHeight)
      if (dw > MaxDimDelta || dh > MaxDimDelta) {
        fail(
          s"dimensions ${expectedImg.getWidth}x${expectedImg.getHeight} -> " +
            s"${actualImg.getWidth}x${actualImg.getHeight}"
        )
      }

      val w = math.min(expectedImg.getWidth, actualImg.getWidth)
      val h = math.min(expectedImg.getHeight, actualImg.getHeight)
      val expectedCrop = expectedImg.getSubimage(0, 0, w, h)
      val actualCrop = actualImg.getSubimage(0, 0, w, h)
      val total = w * h
      val diff = Pixelmatch.diffPixels(expectedCrop, actualCrop, Threshold)
      val budget = math.floor(total * maxDiffRatio).toInt
      if (diff > budget) {
        fail(
          f"$diff differing pixels of $total (${diff.toDouble / total * 100}%.3f%%), " +
            f"over the ${maxDiffRatio * 100}%.1f%% budget"
        )
      }
    }
  }
}

/** A port of mapbox/pixelmatch (ISC), restricted to counting visually
  * different, non-anti-aliased pixels over packed-ARGB rasters.
  */
private object Pixelmatch {

  def diffPixels(a: BufferedImage, b: BufferedImage, threshold: Double): Int = {
    val w = a.getWidth
    val h = b.getHeight
    val img1 = a.getRGB(0, 0, w, h, null, 0, w)
    val img2 = b.getRGB(0, 0, w, h, null, 0, w)
    // The maximum YIQ distance (black vs white).
    val maxDelta = 35215.0 * threshold * threshold
    var diff = 0
    var y = 0
    while (y < h) {
      var x = 0
      while (x < w) {
        val pos = y * w + x
        if (
          math.abs(colorDelta(img1, img2, pos, pos, yOnly = false)) > maxDelta
        ) {
          val aa =
            antialiased(img1, img2, x, y, w, h) ||
              antialiased(img2, img1, x, y, w, h)
          if (!aa) diff += 1
        }
        x += 1
      }
      y += 1
    }
    diff
  }

  private inline def alpha(p: Int): Int = (p >>> 24) & 0xff
  private inline def red(p: Int): Int = (p >>> 16) & 0xff
  private inline def green(p: Int): Int = (p >>> 8) & 0xff
  private inline def blue(p: Int): Int = p & 0xff

  private def blend(c: Double, a: Double): Double = 255 + (c - 255) * a
  private def rgb2y(r: Double, g: Double, b: Double): Double =
    r * 0.29889531 + g * 0.58662247 + b * 0.11448223
  private def rgb2i(r: Double, g: Double, b: Double): Double =
    r * 0.59597799 - g * 0.27417610 - b * 0.32180189
  private def rgb2q(r: Double, g: Double, b: Double): Double =
    r * 0.21147017 - g * 0.52261711 + b * 0.31114694

  /** `yOnly` returns just the brightness delta, for the AA detector. */
  private def colorDelta(
      img1: Array[Int],
      img2: Array[Int],
      k: Int,
      m: Int,
      yOnly: Boolean
  ): Double = {
    val p1 = img1(k)
    val p2 = img2(m)
    var a1 = alpha(p1).toDouble
    var a2 = alpha(p2).toDouble
    var r1 = red(p1).toDouble
    var g1 = green(p1).toDouble
    var b1 = blue(p1).toDouble
    var r2 = red(p2).toDouble
    var g2 = green(p2).toDouble
    var b2 = blue(p2).toDouble

    if (a1 == a2 && r1 == r2 && g1 == g2 && b1 == b2) return 0.0

    if (a1 < 255) {
      a1 /= 255
      r1 = blend(r1, a1); g1 = blend(g1, a1); b1 = blend(b1, a1)
    }
    if (a2 < 255) {
      a2 /= 255
      r2 = blend(r2, a2); g2 = blend(g2, a2); b2 = blend(b2, a2)
    }

    val y1 = rgb2y(r1, g1, b1)
    val y2 = rgb2y(r2, g2, b2)
    val y = y1 - y2
    if (yOnly) return y

    val i = rgb2i(r1, g1, b1) - rgb2i(r2, g2, b2)
    val q = rgb2q(r1, g1, b1) - rgb2q(r2, g2, b2)
    val delta = 0.5053 * y * y + 0.299 * i * i + 0.1957 * q * q
    if (y1 > y2) -delta else delta
  }

  /** Mirrors pixelmatch's `antialiased`: a brighter and a darker neighbour, one
    * of them with many identical siblings in both images.
    */
  private def antialiased(
      img: Array[Int],
      img2: Array[Int],
      x1: Int,
      y1: Int,
      w: Int,
      h: Int
  ): Boolean = {
    val x0 = math.max(x1 - 1, 0)
    val y0 = math.max(y1 - 1, 0)
    val x2 = math.min(x1 + 1, w - 1)
    val y2 = math.min(y1 + 1, h - 1)
    val pos = y1 * w + x1
    var zeroes = if (x1 == x0 || x1 == x2 || y1 == y0 || y1 == y2) 1 else 0
    var min = 0.0
    var max = 0.0
    var minX = 0; var minY = 0; var maxX = 0; var maxY = 0

    var x = x0
    while (x <= x2) {
      var y = y0
      while (y <= y2) {
        if (!(x == x1 && y == y1)) {
          val delta = colorDelta(img, img, pos, y * w + x, yOnly = true)
          if (delta == 0.0) {
            zeroes += 1
            if (zeroes > 2) return false
          } else if (delta < min) {
            min = delta; minX = x; minY = y
          } else if (delta > max) {
            max = delta; maxX = x; maxY = y
          }
        }
        y += 1
      }
      x += 1
    }

    if (min == 0.0 || max == 0.0) return false

    (hasManySiblings(img, minX, minY, w, h) && hasManySiblings(
      img2,
      minX,
      minY,
      w,
      h
    )) ||
    (hasManySiblings(img, maxX, maxY, w, h) && hasManySiblings(
      img2,
      maxX,
      maxY,
      w,
      h
    ))
  }

  private def hasManySiblings(
      img: Array[Int],
      x1: Int,
      y1: Int,
      w: Int,
      h: Int
  ): Boolean = {
    val x0 = math.max(x1 - 1, 0)
    val y0 = math.max(y1 - 1, 0)
    val x2 = math.min(x1 + 1, w - 1)
    val y2 = math.min(y1 + 1, h - 1)
    val pos = y1 * w + x1
    var zeroes = if (x1 == x0 || x1 == x2 || y1 == y0 || y1 == y2) 1 else 0

    var x = x0
    while (x <= x2) {
      var y = y0
      while (y <= y2) {
        if (!(x == x1 && y == y1)) {
          if (img(pos) == img(y * w + x)) zeroes += 1
          if (zeroes > 2) return true
        }
        y += 1
      }
      x += 1
    }
    false
  }
}
