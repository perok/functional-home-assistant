package fh.view.build

/** The bundled lib is read off the RUNNING jar's classpath resources
  * ([[BundledLib]]) rather than a `/opt/fh/lib` path — so the jar is the single
  * source of the lib bytes. This pins the load-bearing invariant: streaming the
  * lib from the classpath produces the EXACT same content version and bytes as
  * building it from the repo directory, so dev `dashboardServe` (classpath =
  * target/classes) and the assembled jar seed one and the same
  * `@fh-dashboard@<v>`.
  */
class BundledLibSuite extends munit.FunSuite {

  private val resourcesLib =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards" / "lib"

  test("classpath-streamed lib matches the dir build byte-for-byte") {
    val streamed = BundledLib.artifacts()
    val fromDir = LibPackage.build(resourcesLib)

    assertEquals(streamed.version, fromDir.version)
    assertEquals(streamed.sha256, fromDir.sha256)
    assert(
      java.util.Arrays.equals(streamed.zip, fromDir.zip),
      clue = "streamed zip bytes differ from the dir build"
    )
    // And a real module is actually present (the stream isn't empty).
    assert(
      BundledLib.entries().exists(_._1 == "components.pkl"),
      clue = BundledLib.entries().map(_._1)
    )
  }
}
