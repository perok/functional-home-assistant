package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import org.http4s.*
import org.http4s.implicits.*

/** The editor surface: what `/edit` offers to edit, plus the built asset bundle
  * it boots from.
  *
  * That bundle is covered here because `vendor.js` is a BUILT artifact
  * (esbuild, from `editor-src/`) and is gitignored — CI builds it, a checkout
  * may carry an old one. When it drifts from what `app.js` imports, the failure
  * is TOTAL and SILENT: the ES module import throws, so `app.js` never runs, so
  * the file list is never rendered AND the on-screen error handler (registered
  * inside that module) is never installed. The editor is simply blank, with
  * nothing but a browser console entry to say why. That happened; hence this
  * test.
  *
  * A text check on purpose — no node, no browser — so it runs in the normal
  * suite. Both files are read off the CLASSPATH, which is what the server
  * actually serves.
  */
class EditorSuite extends munit.FunSuite {

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

  /** A workspace shaped like a real one: two entries, the manifest, its
    * generated lockfile, a machine-specific `.fh/`, and a `lib/` source.
    */
  private def workspace(f: os.Path => Unit): Unit = {
    val ws = os.temp.dir() / "ws"
    os.makeDir.all(ws / "lib")
    os.makeDir.all(ws / ".fh")
    os.write(ws / "pkl-demo.pkl", "// demo")
    os.write(ws / "pkl-tabs.pkl", "// tabs")
    os.write(ws / "PklProject", "amends \"...\"")
    os.write(ws / "PklProject.deps.json", "{}")
    os.write(ws / ".fh" / "machine.json", "{}")
    os.write(ws / "lib" / "components.pkl", "// lib")
    f(ws)
  }

  private def routes(ws: os.Path) =
    new EditorRoutes(ws, None, "pkl-demo").routes(null)

  private def get(ws: os.Path, path: String): (Status, String) = {
    val resp = routes(ws).orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .unsafeRunSync()
    (
      resp.status,
      resp.body.through(fs2.text.utf8.decode).compile.string.unsafeRunSync()
    )
  }

  test("the file list carries the entries, the lib sources, and PklProject") {
    workspace { ws =>
      val (status, body) = get(ws, "/edit/files")
      assertEquals(status, Status.Ok)
      val names = parse(body).toOption
        .flatMap(_.asArray)
        .toList
        .flatten
        .flatMap(_.hcursor.get[String]("name").toOption)
      assertEquals(
        names,
        List("pkl-demo.pkl", "pkl-tabs.pkl", "lib/components.pkl", "PklProject")
      )
      // Only a dashboard entry carries a slug — that is what the editor previews,
      // and what dims everything else in the list.
      val slugged = parse(body).toOption
        .flatMap(_.asArray)
        .toList
        .flatten
        .flatMap(e =>
          e.hcursor
            .get[String]("slug")
            .toOption
            .map(_ => e.hcursor.get[String]("name").toOption.get)
        )
      assertEquals(slugged, List("pkl-demo.pkl", "pkl-tabs.pkl"))
      // The generated lockfile and the machine-specific files stay hidden.
      assert(!names.contains("PklProject.deps.json"), clue = names)
      assert(!names.exists(_.startsWith(".fh")), clue = names)
    }
  }

  test("PklProject is readable and writable; its lockfile is neither") {
    workspace { ws =>
      assertEquals(get(ws, "/edit/file/PklProject")._1, Status.Ok)

      val written = routes(ws).orNotFound
        .run(
          Request[IO](Method.PUT, uri"/edit/file/PklProject")
            .withEntity("amends \"edited\"")
        )
        .unsafeRunSync()
      assertEquals(written.status, Status.NoContent)
      assertEquals(os.read(ws / "PklProject"), "amends \"edited\"")

      // The lockfile is generated — a write would be silently undone by the next
      // resolve, so it is not offered at all.
      val lockfile = routes(ws).orNotFound
        .run(
          Request[IO](Method.PUT, uri"/edit/file/PklProject.deps.json")
            .withEntity("{}")
        )
        .unsafeRunSync()
      assertEquals(lockfile.status, Status.Forbidden)
    }
  }
}
