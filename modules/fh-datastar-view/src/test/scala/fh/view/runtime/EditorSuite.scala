package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import org.http4s.*
import org.http4s.implicits.*

/** The editor surface: what `/edit` offers to edit, plus the bundle it boots
  * from.
  *
  * That bundle is covered here because it is a BUILT artifact (vite, from
  * `src/js/`) and is gitignored — CI builds it, a checkout may carry an old one
  * or none. When it is missing or was not really bundled, the failure is TOTAL
  * and SILENT: the ES module import throws, so `app.js` never runs, so the file
  * list is never rendered AND the on-screen error handler (registered inside
  * that module) is never installed. The editor is simply blank, with nothing
  * but a browser console entry to say why. That happened when `app.js` and its
  * vendor bundle could drift apart; they are one file now, and this is the
  * guard that replaces that one.
  *
  * A text check on purpose — no node, no browser — so it runs in the normal
  * suite. Everything is read off the CLASSPATH, which is what the server
  * actually serves.
  *
  * The OTHER way a bundle breaks — a classic script (the shell, the overlay)
  * picking up an `import` because rollup split a shared module out — is not
  * checked here. `vite.config.ts`'s `fh-assert-self-contained` plugin fails the
  * build on it, off rollup's own chunk metadata, so it cannot reach a test.
  */
class EditorSuite extends munit.FunSuite {

  /** A bundle by ENTRY NAME, the way the app addresses it — the filenames carry
    * a content hash, so nothing here can spell one out either.
    */
  private def bundle(entry: String): String = FrontendAssets.content(entry)

  test("the editor bundle is present and self-contained") {
    val app = bundle("app")

    // Really a bundle, not the bare source: CodeMirror is inside it. The
    // source is ~10KB and the bundle ~650KB, so the floor is far from either.
    assert(app.length > 100000, clue = app.length)

    // ...and nothing was left EXTERNAL. A bundle that still names a bare
    // package specifier would throw on import in the browser (no import map,
    // no CDN) — the blank-editor failure this suite exists for.
    val unbundled = "from\\s*[\"']([^./\"'][^\"']*)[\"']".r
      .findAllMatchIn(app)
      .map(_.group(1))
      .toList
    assertEquals(
      unbundled,
      Nil,
      clue = s"app.js imports unbundled modules: ${unbundled.mkString(", ")}"
    )
  }

  test("the page shell bundle defines the helpers the document calls") {
    val shell = bundle("shell")
    // The document calls all four by name — fhConn from a script mid-body,
    // fhScroll from the last line of it, fhUrl from Datastar's first effect,
    // fhRegisterSw from a script in the head.
    List("fhUrl", "fhConn", "fhScroll", "fhRegisterSw").foreach(fn =>
      assert(shell.contains(s"window.$fn="), clue = (fn, shell))
    )
  }

  test("the editor page names the hashed bundle, and nothing else does") {
    workspace { ws =>
      val (status, html) = get(ws, "/edit")
      assertEquals(status, Status.Ok)
      // The placeholder is gone and the real, hashed, RELATIVE url is in its
      // place — relative so it resolves against <base href> behind ingress.
      val app = FrontendAssets.url("app")
      assert(html.contains(s"""src="$app""""), clue = html)
      assert(!html.contains("__APP_JS__"), clue = html)
      assert(app.startsWith("web/") && app.endsWith(".js"), clue = app)
      // A hash, not a bare name: that is what makes the immutable caching on
      // the serving route honest.
      assertNotEquals(app, "web/app.js", clue = app)
      // ...and the editor route no longer serves JavaScript at all.
      assertEquals(get(ws, "/edit/app.js")._1, Status.NotFound)
    }
  }

  test("only files the manifest names are served") {
    assert(FrontendAssets.serves(FrontendAssets.url("app").stripPrefix("web/")))
    // The guard is an allowlist of built filenames, so a made-up name — or a
    // traversal attempt — is simply not a route that exists.
    assert(!FrontendAssets.serves("app.js"))
    assert(!FrontendAssets.serves("../application.conf"))
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
