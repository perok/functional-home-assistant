package fh.view.testkit

/** Byte-identity PNG snapshots for the component visual suite
  * ([[fh.view.smoke]]) — the same checked-in-resource-file contract
  * `PklBuildSuite`'s wire-format snapshots use, extended to screenshots. A
  * component's rendered look is deterministic here BY CONSTRUCTION: fixed
  * viewport, [[fh.view.smoke.SmokeSuite.settle]] kills animations and waits on
  * webfonts, and every asset (fonts included) is the fully offline,
  * pinned-version [[VendoredAssets]] set — so unlike a typical
  * visual-regression setup, there is no live CDN version drift to chase.
  *
  * To regenerate after an intentional visual change: `sbt 'eval
  * sys.props.put("FH_UPDATE_SNAPSHOTS", "1")' 'fh-datastar-view/testFull' 'eval
  * sys.props.remove("FH_UPDATE_SNAPSHOTS")'` (NOT a plain
  * `FH_UPDATE_SNAPSHOTS=1` shell export — sbt 2.0's persistent server keeps its
  * start-time env forever, leaving the gate silently stuck in regenerate mode;
  * see `PklBuildSuite`).
  */
object VisualSnapshot {

  private val snapshotDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" / "visual-snapshots"

  private def updating: Boolean =
    sys.env.get("FH_UPDATE_SNAPSHOTS").contains("1") ||
      sys.props.get("FH_UPDATE_SNAPSHOTS").contains("1")

  /** Compare `actual` PNG bytes against the checked-in `name.png`. With the
    * update gate on, (re)writes the resource file; otherwise asserts byte
    * identity, writing `actual` to a temp file for review on mismatch.
    */
  def check(name: String, actual: Array[Byte]): Unit = {
    val file = snapshotDir / s"$name.png"
    if (updating) {
      os.makeDir.all(snapshotDir)
      os.write.over(file, actual)
    } else if (!os.exists(file)) {
      throw new AssertionError(
        s"missing visual snapshot $file — regenerate with " +
          "FH_UPDATE_SNAPSHOTS=1 sbt 'fh-datastar-view/testFull'"
      )
    } else {
      val expected = os.read.bytes(file)
      if (!expected.sameElements(actual)) {
        val actualFile = os.temp.dir() / s"$name.actual.png"
        os.write(actualFile, actual)
        throw new AssertionError(
          s"visual snapshot for $name.png changed (${expected.length}B -> " +
            s"${actual.length}B). Actual written to $actualFile for review. " +
            "If intended, regenerate with FH_UPDATE_SNAPSHOTS=1 sbt " +
            "'fh-datastar-view/testFull'."
        )
      }
    }
  }
}
