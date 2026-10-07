package fh.view.runtime

import fh.view.testkit.TestAuth

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.parse
import org.http4s.*
import org.http4s.implicits.*

/** What `/edit` offers to edit, plus the bundle it boots from. The bundle is a
  * built, gitignored artifact, and when it is missing or not really bundled the
  * failure is total and silent: the module import throws, so neither the file
  * list nor the on-screen error handler ever exists, and the editor is blank. A
  * text check off the classpath, so it runs in the normal suite.
  *
  * A classic script picking up an `import` from a split chunk is not checked
  * here: `vite.config.ts`'s `fh-assert-self-contained` fails the build on it.
  */
class EditorSuite extends munit.FunSuite {

  /** Filenames carry a content hash, so nothing can spell one out. */
  private def bundle(entry: String): String = FrontendAssets.content(entry)

  test("the editor bundle is present and self-contained") {
    val app = bundle("app")

    // CodeMirror is inside it: the source is ~10KB and the bundle ~650KB.
    assert(app.length > 100000, clue = app.length)

    // A bare package specifier would throw on import in the browser (no import
    // map, no CDN): the blank-editor failure.
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
    // The document calls all four: fhConn mid-body, fhScroll on its last line,
    // fhRegisterSw in the head, fhToast from the banner's signal-patch handler.
    List("fhToast", "fhConn", "fhScroll", "fhRegisterSw").foreach(fn =>
      assert(shell.contains(s"window.$fn="), clue = (fn, shell))
    )
  }

  test("the editor page names the hashed bundle, and nothing else does") {
    workspace { ws =>
      val (status, html) = get(ws, "/edit")
      assertEquals(status, Status.Ok)
      // Relative, so it resolves against <base href> behind ingress.
      val app = FrontendAssets.url("app")
      assert(html.contains(s"""src="$app""""), clue = html)
      assert(!html.contains("__APP_JS__"), clue = html)
      assert(app.startsWith("web/") && app.endsWith(".js"), clue = app)
      // A hash is what makes the route's immutable caching honest.
      assertNotEquals(app, "web/app.js", clue = app)
      assertEquals(get(ws, "/edit/app.js")._1, Status.NotFound)
    }
  }

  test("the editor states its own chrome colour") {
    // A page in the PWA's scope with no theme-color falls back to the
    // manifest's, which tracks the dashboard's theme. The editor's palette is
    // fixed, so it names its own, unqualified. Installed, Chrome takes the
    // manifest's anyway (see PwaAssets).
    workspace { ws =>
      val (_, html) = get(ws, "/edit")
      assert(
        html.contains("""<meta name="theme-color" content="#1e1e1e">"""),
        clue = html
      )
    }
  }

  test("no pkl-lsp jar disables the socket, not the editor") {
    // `wsb` is null here, safe only because the None branch answers first: this
    // pins that ordering.
    workspace { ws =>
      val r = routes(ws).orNotFound
      val (fileStatus, _) = get(ws, "/edit/files")
      assertEquals(fileStatus, Status.Ok)
      assertEquals(
        r.run(Request[IO](Method.GET, uri"/lsp/pkl")).unsafeRunSync().status,
        Status.ServiceUnavailable
      )
    }
  }

  test("only files the manifest names are served") {
    assert(FrontendAssets.serves(FrontendAssets.url("app").stripPrefix("web/")))
    // An allowlist of built filenames, so a made-up name or traversal is no
    // route.
    assert(!FrontendAssets.serves("app.js"))
    assert(!FrontendAssets.serves("../application.conf"))
  }

  /** The entrypoint, a module, the manifest, its lockfile, a machine-specific
    * `.fh/` and a `lib/` source.
    */
  private def workspace(f: os.Path => Unit): Unit = {
    val ws = os.temp.dir() / "ws"
    os.makeDir.all(ws / "lib")
    os.makeDir.all(ws / ".fh")
    os.write(ws / "site.pkl", "// the entrypoint")
    os.write(ws / "pkl-tabs.pkl", "// tabs")
    os.write(ws / "PklProject", "amends \"...\"")
    os.write(ws / "PklProject.deps.json", "{}")
    os.write(ws / ".fh" / "machine.json", "{}")
    os.write(ws / "lib" / "components.pkl", "// lib")
    f(ws)
  }

  private def routes(ws: os.Path) =
    new EditorRoutes(
      ws,
      TestAuth.openGate,
      None,
      IO.pure("home"),
      IO.pure(List("home", "kitchen"))
    ).routes(null)

  private def get(ws: os.Path, path: String): (Status, String) = {
    val resp = routes(ws).orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .unsafeRunSync()
    (
      resp.status,
      resp.body.through(fs2.text.utf8.decode).compile.string.unsafeRunSync()
    )
  }

  test("the file list carries the sources, each with its kind") {
    workspace { ws =>
      val (status, body) = get(ws, "/edit/files")
      assertEquals(status, Status.Ok)
      val entries = parse(body).toOption
        .flatMap(_.asArray)
        .toList
        .flatten
        .flatMap(e =>
          (
            e.hcursor.get[String]("name").toOption,
            e.hcursor.get[String]("kind").toOption
          ).tupled
        )
      // Exactly one entrypoint; everything else is an ordinary source, dimmed
      // in the list (ADR 0021).
      assertEquals(
        entries,
        List(
          "pkl-tabs.pkl" -> "module",
          "site.pkl" -> "entry",
          "lib/components.pkl" -> "lib",
          "PklProject" -> "manifest"
        )
      )
      val names = entries.map(_._1)
      assert(!names.contains("PklProject.deps.json"), clue = names)
      assert(!names.exists(_.startsWith(".fh")), clue = names)
    }
  }

  test("the dashboard list is the LIVE slugs, not the files") {
    workspace { ws =>
      val (status, body) = get(ws, "/edit/dashboards")
      assertEquals(status, Status.Ok)
      assertEquals(
        parse(body).flatMap(_.as[List[String]]).toOption,
        Some(List("home", "kitchen"))
      )
    }
  }

  test("a write says whether the site actually reads the file") {
    // Saving a file no dashboard reads is allowed, but silence would read as
    // "it is live". The answer is static analysis of the entrypoint, right as
    // soon as the file is on disk. An analysis that cannot run answers the
    // conservative superset (`PklBuild.fileImports`), so it is never wrong the
    // confident way.
    workspace { ws =>
      def put(name: String, body: String) = routes(ws).orNotFound
        .run(
          Request[IO](Method.PUT, Uri.unsafeFromString(s"/edit/file/$name"))
            .withEntity(body)
        )
        .flatMap(resp =>
          resp.body
            .through(fs2.text.utf8.decode)
            .compile
            .string
            .map(resp.status -> _)
        )
        .unsafeRunSync()

      val (entryStatus, entryBody) = put("site.pkl", "// the entrypoint")
      assertEquals(entryStatus, Status.Ok)
      assertEquals(
        parse(entryBody).toOption
          .flatMap(_.hcursor.get[Boolean]("used").toOption),
        Some(true)
      )

      val (modStatus, modBody) = put("pkl-tabs.pkl", "// nothing names me")
      assertEquals(modStatus, Status.Ok)
      assertEquals(
        parse(modBody).toOption
          .flatMap(_.hcursor.get[Boolean]("used").toOption),
        Some(false)
      )
      // A note, not a gate: the bytes landed either way.
      assertEquals(os.read(ws / "pkl-tabs.pkl"), "// nothing names me")
    }
  }

  test(
    "an identical write is reported unchanged, and does not touch the file"
  ) {
    // Touching the file fires the watcher, which re-evaluates the site (seconds
    // on a Pi) and reloads every connected browser.
    workspace { ws =>
      def put(name: String, body: String) = routes(ws).orNotFound
        .run(
          Request[IO](Method.PUT, Uri.unsafeFromString(s"/edit/file/$name"))
            .withEntity(body)
        )
        .flatMap(resp => resp.body.through(fs2.text.utf8.decode).compile.string)
        .unsafeRunSync()

      def changed(body: String) = parse(body).toOption
        .flatMap(_.hcursor.get[Boolean]("changed").toOption)

      val target = ws / "pkl-tabs.pkl"
      assertEquals(changed(put("pkl-tabs.pkl", "// first")), Some(true))
      val afterFirst = os.mtime(target)

      // The mtime standing still is the half the watcher reads.
      assertEquals(changed(put("pkl-tabs.pkl", "// first")), Some(false))
      assertEquals(os.mtime(target), afterFirst)
      assertEquals(os.read(target), "// first")

      assertEquals(changed(put("pkl-tabs.pkl", "// second")), Some(true))
      assertEquals(os.read(target), "// second")
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
      // `used` is false: the entrypoint does not import the manifest.
      assertEquals(written.status, Status.Ok)
      assertEquals(os.read(ws / "PklProject"), "amends \"edited\"")

      // Generated, so a write would be undone by the next resolve.
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
