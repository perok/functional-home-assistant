package fh.view.runtime

/** The editor's `vendor.js` is a BUILT artifact (esbuild, from `editor-src/`)
  * and is gitignored — CI builds it, a checkout may carry an old one. When it
  * drifts from what `app.js` imports, the failure is TOTAL and SILENT: the ES
  * module import throws, so `app.js` never runs, so the file list is never
  * rendered AND the on-screen error handler (registered inside that module) is
  * never installed. The editor is simply blank, with nothing but a browser
  * console entry to say why. That happened; hence this test.
  *
  * A text check on purpose — no node, no browser — so it runs in the normal
  * suite. Both files are read off the CLASSPATH, which is what the server
  * actually serves.
  */
class EditorAssetsSuite extends munit.FunSuite {

  private def resource(name: String): String = {
    val in = Option(getClass.getResourceAsStream(s"/editor/$name"))
      .getOrElse(fail(s"editor/$name is not on the classpath"))
    try new String(in.readAllBytes(), "UTF-8")
    finally in.close()
  }

  test("vendor.js exports every symbol app.js imports") {
    val app = resource("app.js")
    val vendor = resource("vendor.js")

    // The single `import { … } from "./vendor.js"` block at the top of app.js.
    val imported = "(?s)import\\s*\\{(.*?)\\}\\s*from\\s*\"\\./vendor\\.js\"".r
      .findFirstMatchIn(app)
      .map(_.group(1))
      .getOrElse(fail("app.js has no import block from ./vendor.js"))
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

    assert(imported.sizeIs > 5, clue = imported) // the regex really matched

    // esbuild's esm output ends in one `export { … }` list.
    val exported = "(?s)export\\s*\\{([^}]*)\\}\\s*;?\\s*$".r
      .findFirstMatchIn(vendor)
      .map(_.group(1))
      .getOrElse(fail("vendor.js has no trailing export block"))
      .split(",")
      .map(_.trim.split("\\s+as\\s+").last.trim)
      .filter(_.nonEmpty)
      .toSet

    val missing = imported.filterNot(exported.contains)
    assertEquals(
      missing,
      Nil,
      clue =
        s"vendor.js is stale — rebuild it: (cd modules/fh-datastar-view/editor-src && npm install && npm run build). Missing: ${missing
            .mkString(", ")}"
    )
  }
}
