package fh.view.testkit

import munit.Assertions.{assertEquals, fail}
import munit.Location

/** The wire snapshots and the render goldens: text files reviewed as a diff,
  * rebaselined with `sbt dashboardSnapshotsUpdate`.
  *
  * The gate is read in the persistent sbt server's JVM. Anything that leaves
  * FH_UPDATE_SNAPSHOTS set there (a shell export, a `; put ; test ; remove`
  * chain whose `remove` is skipped when the test fails) keeps it regenerating,
  * and every later run passes while rewriting files.
  */
object TextSnapshot {

  val resources: os.Path =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources"

  /** With `FH_UPDATE_SNAPSHOTS=1` it rewrites `file` and passes; otherwise it
    * asserts byte identity and writes the actual output to a temp file.
    */
  def check(file: os.Path, actual: String)(using Location): Unit = {
    val updating =
      sys.env.get("FH_UPDATE_SNAPSHOTS").contains("1") ||
        sys.props.get("FH_UPDATE_SNAPSHOTS").contains("1")
    if (updating) {
      os.makeDir.all(file / os.up)
      os.write.over(file, actual)
    } else {
      val expected =
        if (os.exists(file)) os.read(file)
        else
          fail(
            s"missing snapshot $file — regenerate with " +
              "sbt dashboardSnapshotsUpdate"
          )
      val actualFile =
        if (expected == actual) None
        else {
          val f = os.temp.dir() / s"${file.baseName}.actual.${file.ext}"
          os.write(f, actual)
          Some(f)
        }
      assertEquals(
        actual,
        expected,
        clue = s"snapshot ${file.last} changed. If intended, regenerate with " +
          "'sbt dashboardSnapshotsUpdate'" +
          actualFile.fold("")(f => s" (actual output written to $f).")
      )
    }
  }
}
