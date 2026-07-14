package fh.view.testkit

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Perceptual PNG snapshots for the component visual suite ([[fh.view.smoke]])
  * — the screenshot analogue of `PklBuildSuite`'s checked-in wire-format
  * snapshots. A component's rendered look is deterministic here BY
  * CONSTRUCTION: fixed viewport, [[fh.view.smoke.SmokeSuite.settle]] kills
  * animations and waits on webfonts, and every asset (fonts included) is
  * fetched at a version pinned in its CDN URL (`theme-beer.pkl`'s
  * `beerVersion`, `Server.DatastarCdn`'s tag) — so unlike a typical
  * visual-regression setup there is no live CDN version drift to chase.
  *
  * The comparison is NOT byte-identity: the only cross-environment variance
  * left after asset-pinning is the OS-level font rasterization stack (FreeType
  * hinting / anti-aliasing), which shifts glyph-edge pixels by a sub-pixel
  * between machines (a locally-regenerated baseline vs CI's `ubuntu-latest`).
  * So we compare with a [[Pixelmatch]] port (the same algorithm Playwright's
  * own `toHaveScreenshot` uses): a pixel counts as "different" only if its YIQ
  * color distance exceeds [[Threshold]] AND it is not an anti-aliased edge in
  * either image; the snapshot fails only if more than [[MaxDiffRatio]] of the
  * image's pixels differ. A dimension change beyond a ±[[MaxDimDelta]]px
  * rounding tolerance always fails (a real reflow); within that tolerance the
  * shared rectangle is compared, since a component's screenshot is its own
  * content-derived box and a different font-rasterization stack can round that
  * box by a pixel on an axis. That makes the baseline portable across
  * environments while still catching real visual regressions.
  *
  * To regenerate after an intentional visual change: `sbt
  * dashboardSnapshotsUpdate` (the scoped-`sys.props` alias — NOT a plain
  * `FH_UPDATE_SNAPSHOTS=1` shell export, which sbt 2.0's persistent server
  * keeps forever, leaving the gate silently stuck in regenerate mode; see
  * `PklBuildSuite`).
  */
object VisualSnapshot {

  private val snapshotDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" / "visual-snapshots"

  /** Where a mismatch drops its `before`/`after` PNGs, side by side under a
    * stable (gitignored) path so CI can collect them as an artifact — a local
    * vs `ubuntu-latest` rasterization diff is only inspectable by eye, so the
    * failing pair has to leave the machine. `.github/workflows/cicd.yml`
    * uploads this dir on failure.
    */
  private val failureDir =
    os.pwd / "modules" / "fh-datastar-view" / "target" / "visual-failures"

  /** Per-pixel YIQ color-distance tolerance (0..1); pixelmatch's default. */
  private val Threshold = 0.1

  /** Fraction of pixels allowed to differ before the snapshot fails. Kept small
    * because anti-aliased edge pixels — the bulk of cross-environment noise —
    * are already excluded by the AA detection, so a genuine change lights up
    * far more than this.
    */
  private val MaxDiffRatio = 0.002

  /** Per-axis pixel tolerance on the screenshot's own dimensions. A component's
    * bounding box is content-derived, so a different OS font-rasterization stack
    * can round it by a pixel; a delta this small is the same sub-pixel noise the
    * perceptual diff already forgives, so we compare over the shared rectangle
    * instead of hard-failing. A larger delta is a genuine reflow and fails.
    */
  private val MaxDimDelta = 2

  private def updating: Boolean =
    sys.env.get("FH_UPDATE_SNAPSHOTS").contains("1") ||
      sys.props.get("FH_UPDATE_SNAPSHOTS").contains("1")

  private def decode(bytes: Array[Byte]): BufferedImage =
    ImageIO.read(new ByteArrayInputStream(bytes))

  /** Compare `actual` PNG bytes against the checked-in `name.png`. With the
    * update gate on, (re)writes the resource file; otherwise runs the
    * perceptual diff, dropping the before/after pair into [[failureDir]] on
    * mismatch for review.
    */
  def check(name: String, actual: Array[Byte]): Unit = {
    val file = snapshotDir / s"$name.png"
    if (updating) {
      os.makeDir.all(snapshotDir)
      os.write.over(file, actual)
    } else if (!os.exists(file)) {
      throw new AssertionError(
        s"missing visual snapshot $file — regenerate with " +
          "`sbt dashboardSnapshotsUpdate`"
      )
    } else {
      val expectedImg = decode(os.read.bytes(file))
      val actualImg = decode(actual)

      def fail(reason: String): Nothing = {
        // Drop the baseline and the actual side by side under the stable
        // failure dir, so CI can archive the pair and a human can flick
        // between them (the diff is sub-pixel; only the eye can judge it).
        os.makeDir.all(failureDir)
        os.copy.over(file, failureDir / s"$name.expected.png")
        os.write.over(failureDir / s"$name.actual.png", actual)
        throw new AssertionError(
          s"visual snapshot for $name.png changed ($reason). before/after written to " +
            s"$failureDir ($name.expected.png / $name.actual.png) for review. If " +
            "intended, regenerate with `sbt dashboardSnapshotsUpdate`."
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

      // Within tolerance: compare over the rectangle both images share, so a
      // ±MaxDimDelta rounding rim doesn't fail an otherwise-identical component.
      val w = math.min(expectedImg.getWidth, actualImg.getWidth)
      val h = math.min(expectedImg.getHeight, actualImg.getHeight)
      val expectedCrop = expectedImg.getSubimage(0, 0, w, h)
      val actualCrop = actualImg.getSubimage(0, 0, w, h)
      val total = w * h
      val diff = Pixelmatch.diffPixels(expectedCrop, actualCrop, Threshold)
      val budget = math.floor(total * MaxDiffRatio).toInt
      if (diff > budget) {
        fail(
          f"$diff differing pixels of $total (${diff.toDouble / total * 100}%.3f%%), " +
            f"over the ${MaxDiffRatio * 100}%.1f%% budget"
        )
      }
    }
  }
}

/** A faithful port of mapbox/pixelmatch (ISC-licensed), the pixel comparison
  * behind Playwright's screenshot assertions, restricted to what
  * [[VisualSnapshot]] needs: a count of visually-different, non-anti-aliased
  * pixels. Operates on packed-ARGB int rasters (one int per pixel).
  */
private object Pixelmatch {

  def diffPixels(a: BufferedImage, b: BufferedImage, threshold: Double): Int = {
    val w = a.getWidth
    val h = b.getHeight
    val img1 = a.getRGB(0, 0, w, h, null, 0, w)
    val img2 = b.getRGB(0, 0, w, h, null, 0, w)
    // 35215 is the maximum possible YIQ color distance (black vs white).
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
          // A pixel that differs but reads as anti-aliasing in EITHER image is
          // rasterization noise, not a real change — skip it.
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

  /** Signed YIQ color distance between pixel `k` of `img1` and pixel `m` of
    * `img2` (0 when identical). `yOnly` returns just the brightness delta, used
    * by the AA detector.
    */
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

  /** True if pixel (x1,y1) of `img` looks anti-aliased: it has both a brighter
    * and a darker neighbor, and one of those extremes has many identical
    * siblings in both images (a solid edge on one side). Mirrors pixelmatch's
    * `antialiased`.
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

  /** True if pixel (x1,y1) has 3+ identical neighbors (incl. edges). */
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
