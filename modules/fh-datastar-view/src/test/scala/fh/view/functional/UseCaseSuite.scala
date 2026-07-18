package fh.view.functional

import cats.data.NonEmptyList
import cats.effect.IO
import com.comcast.ip4s.{host, port}
import fh.view.build.{
  AddonBootstrap,
  DashboardBuild,
  DumpPackage,
  LibPackage,
  PklDump,
  SourceEval,
  SystemPkl
}
import fh.view.model.Dashboard
import fh.view.runtime.TestServer

import fh.view.testkit.{HouseFixture, PklFixture}

import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{`If-None-Match`, ETag}
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** One test per consumer in ADR 0010's "Use cases" table.
  *
  * Not unit tests of a class: each pins the load-bearing PROPERTY one persona's
  * workflow depends on. The four stories are meant to be ONE design, so a
  * change that quietly serves three of them should fail here rather than in
  * someone's home.
  *
  * The invariant under all four: **evaluation always runs against a fully-local
  * project**; an instance is synced FROM and pushed TO, never imported from.
  */
class UseCaseSuite extends munit.CatsEffectSuite {

  private val dashboards =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

  /** Stage a package-form dashboards workspace the way every persona has one
    * (the ONE resolution mode, ADR 0010): the real `AddonBootstrap` seeds the
    * lib package into a cache, writes the static base.pkl + consumer project +
    * a placeholder `@fh-home` pin. `withDump = true` seeds the dump package and
    * moves the pin; `withDump = false` is the laptop before a pull — `@fh-home`
    * is the unresolvable placeholder, which is also how a freshly-seeded add-on
    * starts.
    */
  private def stageWorkspace(withDump: Boolean): os.Path = {
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      ws,
      bundledLib = dashboards / "lib",
      seedDir = os.temp.dir(), // empty: never seed the demo entries into a test
      cacheDir = root / "pkl-cache",
      loopbackUrl = "http://127.0.0.1:8080"
    )
    if (withDump) {
      val _ = DumpPackage.seedFromText(
        ws,
        PklDump.render(HouseFixture.transformedDump)
      )
    }
    ws
  }

  /** An entry that can only build if the live dump resolved: it names a real
    * entity of this home, so an absent dump is a build error rather than a
    * quietly emptier dashboard.
    */
  private val entryNeedingDump =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |import "@fh-dashboard/theme.pkl" as th
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |card = (c.column) {
       |  children {
       |    c.entityCard(dump.entities.${HouseFixture.kitchenLight.dumpKey})
       |  }
       |}
       |""".stripMargin

  // ---------------------------------------------------------------- persona 1

  test("end user on /edit: the seeded workspace builds server-side") {
    // What a fresh add-on does: bootstrap the workspace (package-form
    // manifests, lib pre-cached — AddonBootstrapSuite pins that machinery),
    // write the dump from HA, evaluate. pkl-lsp behind /edit resolves the same
    // manifests + cache (`moduleCacheDir` is declared IN the manifest), so
    // nothing here is fetched.
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      ws,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache",
      loopbackUrl = "http://127.0.0.1:8080"
    )
    val _ =
      DumpPackage.seedFromText(ws, PklDump.render(HouseFixture.transformedDump))
    os.write(ws / "mine.pkl", entryNeedingDump)

    val result = SourceEval.eval(ws, "mine.pkl")
    assert(result.isRight, clue = result)
  }

  // ---------------------------------------------------------------- persona 2

  test(
    "end user on a local editor: the instance serves its dump as text (ETag 304)"
  ) {
    // The human/debug download of the live dump, extracted from the currently
    // pinned `@fh-home` package (there is no loose `home/dump.pkl`). The actual
    // laptop CONSUMPTION is the package pull below; this route is for reading the
    // dump text and for tooling asking "did the home change?" cheaply — an
    // unchanged home is a 304, the endpoint's only ETag consumer.
    val instance = stageWorkspace(withDump = true)

    val uri = uri"/system/pkl/dump.pkl"
    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        List(HouseFixture.kitchenLight),
        SystemPkl.fromDisk(instance)
      )
      .use { ts =>
        val app = ts.server.routes.orNotFound
        for {
          pulled <- app.run(Request[IO](Method.GET, uri))
          body <- pulled.body.through(fs2.text.utf8.decode).compile.string
          tag = pulled.headers.get[ETag].map(_.tag)
          second <- app.run(
            Request[IO](Method.GET, uri)
              .putHeaders(`If-None-Match`(tag.map(NonEmptyList.one)))
          )
        } yield {
          assertEquals(pulled.status, Status.Ok)
          // The served text is the typed dump — it reaches its schema by alias.
          assert(
            body.contains("""import "@fh-dashboard/hass.pkl""""),
            clue = body.take(200)
          )
          assert(tag.isDefined, clue = pulled.headers)
          assertEquals(second.status, Status.NotModified)
        }
      }
  }

  test(
    "end user on a local editor, no checkout: both packages arrive from the instance"
  ) {
    // The laptop with NEITHER lib nor checkout — only two
    // `package://fh.invalid/…` pins (`@fh-dashboard` AND `@fh-home`) and one
    // rewrite toward the instance's `/system/pkl/packages/` route. This is the
    // whole laptop story for a user who never cloned the repo, and it is
    // deliberately end-to-end: pkl's REAL package resolver over a REAL socket,
    // so any drift in the metadata shape, the zip layout, or the route breaks
    // here and not on someone's laptop.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val instanceCache = root / "pkl-cache"
    val _ = AddonBootstrap.run(
      instance,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = instanceCache,
      loopbackUrl = "http://127.0.0.1:8080"
    )
    val _ =
      DumpPackage.seedFromText(
        instance,
        PklDump.render(HouseFixture.transformedDump)
      )
    val v = LibPackage.version(dashboards / "lib")
    val artifacts = LibPackage.build(dashboards / "lib")
    val homeV =
      DumpPackage.pinnedVersion(instance).getOrElse(fail("instance not pinned"))
    val homeSha = LibPackage.sha256(
      os.read.bytes(
        DumpPackage.cacheEntryDir(instanceCache, homeV) /
          s"fh-home@$homeV.json"
      )
    )

    // The laptop workspace: just the two package pins (uri + the @fh-home
    // checksum a pull writes) — no lib, no home/ dir, no dump file.
    val laptop = root / "laptop"
    os.makeDir.all(laptop)
    os.write(
      laptop / "PklProject",
      s"""amends "pkl:Project"
         |dependencies {
         |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@$v" }
         |  ["fh-home"] {
         |    uri = "package://fh.invalid/fh-home@$homeV"
         |    checksums { sha256 = "$homeSha" }
         |  }
         |}
         |""".stripMargin
    )
    os.write(laptop / "mine.pkl", entryNeedingDump)
    val laptopCache = root / "laptop-cache"

    // A malformed artifact name must never index into the filesystem.
    assert(SystemPkl.fromDisk(instance).packageArtifact("..").isLeft)

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        SystemPkl.fromDisk(instance)
      )
      .flatMap { ts =>
        EmberServerBuilder
          .default[IO]
          .withHost(host"127.0.0.1")
          .withPort(port"0")
          .withHttpApp(ts.server.routes.orNotFound)
          .withShutdownTimeout(0.seconds)
          .build
          .map(bound => (ts, bound.baseUri))
      }
      .use { case (ts, base) =>
        val app = ts.server.routes.orNotFound
        val get = (file: String) =>
          app.run(
            Request[IO](
              Method.GET,
              Uri.unsafeFromString(s"/system/pkl/packages/$file")
            )
          )
        for {
          // The wire contract pkl's resolver consumes: metadata JSON naming
          // the sha256 that the zip actually hashes to — the pin that will
          // land in the laptop's lockfile.
          meta <- get(s"fh-dashboard@$v")
          metaBody <- meta.body.through(fs2.text.utf8.decode).compile.string
          zip <- get(s"fh-dashboard@$v.zip")
          zipBytes <- zip.body.compile.to(Array)
          missing <- get("fh-dashboard@9.9.9-nosuch")

          // The laptop's whole flow, over the real socket: resolve (writes
          // the lockfile, fetches metadata + zip into the laptop's own cache)
          // then evaluate the entry against what arrived.
          properties <- IO.blocking(
            resolveAndEvalOverHttp(laptop, laptopCache, base)
          )
        } yield {
          assertEquals(meta.status, Status.Ok)
          assertEquals(
            meta.headers.get[headers.`Content-Type`].map(_.mediaType),
            Some(MediaType.application.json)
          )
          assert(metaBody.contains(artifacts.sha256), clue = metaBody)
          assertEquals(zip.status, Status.Ok)
          assertEquals(LibPackage.sha256(zipBytes), artifacts.sha256)
          assertEquals(missing.status, Status.NotFound)

          assert(properties.containsKey("card"), clue = properties.keySet)
          // The artifacts really crossed the wire: the laptop's own cache now
          // holds the same package entry the instance evaluates from.
          assert(
            os.exists(
              LibPackage.cacheEntryDir(laptopCache, v) /
                s"fh-dashboard@$v.zip"
            ),
            clue = os.walk(laptopCache).mkString("\n")
          )
        }
      }
  }

  test("the package-discovery index is served by the instance") {
    // What `fh init`/`pull` read before rewriting the laptop's pins: current
    // versions + metadata sha256 of both packages, as JSON.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      instance,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache",
      loopbackUrl = "http://127.0.0.1:8080"
    )
    val _ =
      DumpPackage.seedFromText(
        instance,
        PklDump.render(HouseFixture.transformedDump)
      )

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        SystemPkl.fromDisk(instance)
      )
      .use { ts =>
        val app = ts.server.routes.orNotFound
        for {
          json <- app.run(Request[IO](Method.GET, uri"/system/pkl/packages"))
          jsonBody <- json.body.through(fs2.text.utf8.decode).compile.string
        } yield {
          assertEquals(json.status, Status.Ok)
          val doc = io.circe.parser
            .parse(jsonBody)
            .fold(err => fail(s"index not JSON: $err"), identity)
          for (
            pkg <- List("fh-dashboard", "fh-home");
            key <- List("version", "sha256")
          )
            assert(
              doc.hcursor.downField(pkg).get[String](key).isRight,
              clue = s"$pkg.$key missing in $jsonBody"
            )
        }
      }
  }

  test(
    "fh script interface: the @fh-home package the pins point at is served"
  ) {
    // The server-side half of `fh init`/`pull`, driven at the interface the
    // script consumes and no further (the script's own logic has its own
    // suite, scripts/fh.test.scala; no subprocesses here): the discovery
    // index names the current @fh-home version, that version's metadata +
    // zip artifacts are fetchable, and when the home changes and the
    // instance re-seeds, the index moves to a NEW content version whose
    // artifacts are served too — which is exactly what `pull` re-pins to.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      instance,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache",
      loopbackUrl = "http://127.0.0.1:8080"
    )
    val _ =
      DumpPackage.seedFromText(
        instance,
        PklDump.render(HouseFixture.transformedDump)
      )

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        SystemPkl.fromDisk(instance)
      )
      .use { ts =>
        val app = ts.server.routes.orNotFound
        val get = (path: String) =>
          app.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

        // What fetchIndex in the script reads before rewriting the pins.
        val homeVersion = for {
          idx <- get("/system/pkl/packages")
          body <- idx.body.through(fs2.text.utf8.decode).compile.string
        } yield io.circe.parser
          .parse(body)
          .flatMap(_.hcursor.downField("fh-home").get[String]("version"))
          .fold(err => fail(s"unusable index: $err in $body"), identity)

        for {
          v1 <- homeVersion
          meta <- get(s"/system/pkl/packages/fh-home@$v1")
          zip <- get(s"/system/pkl/packages/fh-home@$v1.zip")

          // The home changes; the instance seeds a NEW snapshot. This is the
          // state `fh pull` finds and re-pins from.
          _ <- IO.blocking {
            DumpPackage.seedFromText(
              instance,
              PklDump.render(HouseFixture.transformedDump) +
                "\n// a new device appeared\n"
            )
          }
          v2 <- homeVersion
          meta2 <- get(s"/system/pkl/packages/fh-home@$v2")
        } yield {
          assertEquals(meta.status, Status.Ok)
          assertEquals(zip.status, Status.Ok)
          assertNotEquals(v2, v1)
          assertEquals(meta2.status, Status.Ok)
        }
      }
  }

  // The script's own behavior (workspace-missing errors, `fh update`'s
  // sha-compare + dated backup) is covered by the script's OWN suite,
  // scripts/fh.test.scala, run in-process by scala-cli's test command:
  //   cd scripts && SCALA_TEST_MODE=true scala-cli test .
  // Here we keep only the server side of the interface the script drives.

  /** What a laptop's pkl tooling does, minus the manifest-declared rewrite
    * sugar: an HttpClient rewriting `https://fh.invalid/` to the instance's
    * `/system/pkl/packages/`, driving pkl's real project resolver + evaluator.
    */
  private def resolveAndEvalOverHttp(
      laptop: os.Path,
      laptopCache: os.Path,
      base: Uri
  ): java.util.Map[String, AnyRef] = {
    import org.pkl.core.http.HttpClient
    import org.pkl.core.packages.PackageResolver
    import org.pkl.core.project.{Project, ProjectDependenciesResolver}
    import org.pkl.core.{EvaluatorBuilder, ModuleSource, SecurityManagers}

    val http = HttpClient
      .builder()
      .addRewrite(
        java.net.URI.create("https://fh.invalid/"),
        java.net.URI.create(s"${base.renderString}/system/pkl/packages/")
      )
      .build()
    val resolver = new ProjectDependenciesResolver(
      Project.loadFromPath((laptop / "PklProject").toNIO),
      PackageResolver.getInstance(
        SecurityManagers.defaultManager,
        http,
        laptopCache.toNIO
      ),
      new java.io.PrintWriter(new java.io.StringWriter)
    )
    val out =
      new java.io.FileOutputStream(
        (laptop / "PklProject.deps.json").toNIO.toFile
      )
    try resolver.resolve().writeTo(out)
    finally out.close()

    val evaluator = EvaluatorBuilder
      .preconfigured()
      .setHttpClient(http)
      .setModuleCacheDir(laptopCache.toNIO)
      .applyFromProject(Project.loadFromPath((laptop / "PklProject").toNIO))
      .build()
    try
      evaluator
        .evaluate(ModuleSource.path((laptop / "mine.pkl").toNIO))
        .getProperties
    finally evaluator.close()
  }

  // ---------------------------------------------------------------- persona 3

  test(
    "repo developer: lib + dump are cache packages, excluded from the watch set"
  ) {
    // The single package-form resolution mode (ADR 0010): `@fh-dashboard` and
    // `@fh-home` are cache packages, so their modules resolve to `package://…`
    // URIs — not `file:` paths — and are filtered out of the import/watch set.
    // The library is immutable per version: editing `lib/` does not hot-reload
    // (a restart re-seeds the cache), so only the entry itself (and any loose
    // imports) is watched. Iterating on `lib/` is a restart or `fh push`.
    val dir = stageWorkspace(withDump = true)
    os.write(dir / "dashboard.pkl", entryNeedingDump)

    val imports = SourceEval
      .eval(dir, "dashboard.pkl")
      .fold(err => fail(s"eval failed: $err"), _.imports)

    assertEquals(imports, Set(dir / "dashboard.pkl"), clue = imports)
  }

  // ---------------------------------------------------------------- persona 4

  /** A component module of their own: a card class in a plain (non-amending)
    * module, which is the only place one can live — see `entry.pkl`'s
    * `componentModules`.
    */
  private val privateComponent =
    """module mycards
      |
      |import "@fh-dashboard/components.pkl" as c
      |
      |class Gauge extends c.Node {
      |  card = "gauge"
      |  cardDef = new c.CardDef {
      |    template = "<article class=\"mine\">{{label}}</article>"
      |    slots { "label" }
      |  }
      |  label: String
      |  slots { ["label"] = label }
      |}
      |
      |function gauge(l: String): Gauge = new Gauge { label = l }
      |""".stripMargin

  test(
    "component developer: their own card class reaches the registry and renders"
  ) {
    // Their components live only on their laptop, so the instance can never
    // evaluate this entry — they evaluate locally and push the RESULT. Two
    // properties make that workflow real, and this test pins both.
    val dir = stageWorkspace(withDump = true)
    os.write(dir / "mycards.pkl", privateComponent)
    os.write(
      dir / "mine.pkl",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "mycards.pkl" as mine
         |import "@fh-dashboard/theme.pkl" as th
         |
         |theme = ${PklFixture.dummyTheme}
         |
         |componentModules { mine }
         |
         |card = mine.gauge("from my own component")
         |""".stripMargin
    )

    // 1. Registration: their class joins `cards` purely by being named in
    //    `componentModules`, with no edit to the library.
    val built = SourceEval
      .eval(dir, "mine.pkl")
      .fold(err => fail(s"eval failed: $err"), identity)
    val dashboard = DashboardBuild
      .hoistInlineSurfaces(built.value)
      .as[Dashboard]
      .fold(err => fail(s"decode failed: $err"), identity)

    assert(dashboard.cards.contains("gauge"), clue = dashboard.cards.keys)

    // 2. Push: the instance runs its OWN dashboard and has never seen
    //    `mycards.pkl`. The developer POSTs the evaluated JSON — exactly what
    //    `built.value` is — under a brand-new slug, skipping the Pkl layer
    //    entirely. It works only because the wire model is self-contained:
    //    every card carries its own template, so the server needs no source.
    //    If rendering ever grew a dependency on resolving templates from disk,
    //    this is what would catch it.
    TestServer
      .resource(PklFixture.buildDashboard("home", entryNeedingDump), Nil)
      .use { ts =>
        val app = ts.server.routes.orNotFound
        val push = (slug: String, body: String) =>
          app.run(
            Request[IO](
              Method.POST,
              Uri.unsafeFromString(s"/system/push/$slug")
            ).withEntity(body)
          )
        for {
          // The slug does not exist yet — push is what mints it.
          before <- app.run(Request[IO](Method.GET, uri"/d/preview"))
          pushed <- push("preview", built.value.noSpaces)
          after <- app.run(Request[IO](Method.GET, uri"/d/preview"))
          html <- after.body.through(fs2.text.utf8.decode).compile.string

          // A dashboard naming a card nobody defines must be REJECTED, not
          // installed to render blank: push runs the same validation as the
          // eval path, and the developer has no server log to read, so the
          // error has to come back on the wire.
          bogus <- push(
            "bogus",
            """{"cards":{},"card":{"kind":"component","card":"nosuchcard",
              |"children":[],"slots":{}}}""".stripMargin
          )
          bogusBody <- bogus.body.through(fs2.text.utf8.decode).compile.string
          notJson <- push("bad", "not json at all")
        } yield {
          assertEquals(before.status, Status.NotFound)
          assertEquals(pushed.status, Status.Ok)
          assertEquals(after.status, Status.Ok)
          assert(html.contains("from my own component"), clue = html)
          assert(html.contains("""class="mine""""), clue = html)

          // Specifically a VALIDATION rejection, naming the offending card —
          // not merely a 400 from failing to decode, which this body does
          // cleanly. Without this the assertion above cannot tell the two apart.
          assertEquals(bogus.status, Status.BadRequest)
          assert(bogusBody.contains("nosuchcard"), clue = bogusBody)

          assertEquals(notJson.status, Status.BadRequest)
        }
      }
  }
}
