package fh.view.functional

import cats.data.NonEmptyList
import cats.effect.IO
import com.comcast.ip4s.{host, port}
import fh.view.build.{
  DashboardBuild,
  DumpPackage,
  LibPackage,
  Pins,
  PklBuild,
  PklDump,
  Site,
  SourceEval,
  SystemPkl
}
import fh.view.model.Dashboard
import fh.view.runtime.TestServer

import fh.view.testkit.{HouseFixture, PklFixture, PklWorkspace}

import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** One test per consumer in ADR 0010's "Use cases" table, each pinning the
  * property that persona's workflow depends on, so a change serving only three
  * of them fails here. The invariant under all four: evaluation always runs
  * against a fully local project; an instance is synced from and pushed to,
  * never imported from.
  */
class UseCaseSuite extends munit.CatsEffectSuite {

  private val dashboards =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

  /** Needed to pin the first dump on a fresh workspace with no pins.json
    * ([[DumpPackage.seedFromText]]).
    */
  private val bundled = LibPackage.build(dashboards / "lib")

  /** `withDump = false` is the laptop before a pull, and a freshly seeded
    * add-on: no pins.json, so `@fh-home` is unresolvable.
    */
  private def stageWorkspace(withDump: Boolean): os.Path = {
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val _ = PklWorkspace.bootstrapInto(ws, bundled, root / "pkl-cache")
    if (withDump) {
      val _ = DumpPackage.seedFromText(
        ws,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )
    }
    ws
  }

  /** Names a real entity, so an absent dump is a build error rather than an
    * emptier dashboard.
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

  test("end user on /edit: the seeded workspace builds server-side") {
    // pkl-lsp behind /edit resolves the same manifests and cache
    // (`moduleCacheDir` is declared in the manifest), so nothing is fetched.
    val root = os.temp.dir()
    val ws = root / "fh-dashboards"
    val _ = PklWorkspace.bootstrapInto(ws, bundled, root / "pkl-cache")
    val _ =
      DumpPackage.seedFromText(
        ws,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )
    os.write(ws / "mine.pkl", entryNeedingDump)

    val result = SourceEval.eval(ws, "mine.pkl")
    assert(result.isRight, clue = result)
  }

  test("end user on /edit: a saved file says whether the site reads it") {
    // A workspace whose analysis really runs, unlike EditorSuite's stub.
    // `mine.pkl` is named by nothing, and the answer flips once the entrypoint
    // imports it, without a reload: it comes from the sources, not the live
    // site.
    val ws = stageWorkspace(withDump = true)
    os.write(ws / "mine.pkl", entryNeedingDump)
    // One evaluation first writes the lockfile the analyser needs; without it
    // the analysis answers with the conservative superset.
    val _ = SourceEval.eval(ws, Site.EntryFile)

    def used(): Boolean =
      PklBuild
        .fileImports(ws, Site.EntryFile)
        .contains(ws / "mine.pkl")

    assert(!used(), clue = "an unreferenced module counted as read")

    os.write.over(
      ws / Site.EntryFile,
      """amends "@fh-dashboard/site.pkl"
        |dashboards { ["mine"] = import("mine.pkl") }
        |""".stripMargin
    )
    assert(used(), clue = "a module the entrypoint imports counted as unread")
  }

  test(
    "end user on a local editor: the instance serves its dump as text (ETag 304)"
  ) {
    // The human/debug download, extracted from the pinned `@fh-home` package.
    // Laptop consumption is the package pull below; this route's one ETag
    // consumer asks "did the home change?" cheaply.
    val instance = stageWorkspace(withDump = true)

    val uri = uri"/system/pkl/dump.pkl"
    // Live data under a fixed URL: `no-cache` on 200 and 304 alike, and a 304
    // only when the tag matches the current bytes. A stale tag winning would
    // give an author completions for devices they no longer own.
    val noCache = `Cache-Control`(CacheDirective.`no-cache`())
    def asking(tag: Option[EntityTag]) =
      Request[IO](Method.GET, uri)
        .putHeaders(`If-None-Match`(tag.map(NonEmptyList.one)))
    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        List(HouseFixture.kitchenLight),
        workspace = Some(instance)
      )
      .use { ts =>
        val app = ts.gatedApp
        for {
          pulled <- app.run(Request[IO](Method.GET, uri))
          body <- pulled.body.through(fs2.text.utf8.decode).compile.string
          tag = pulled.headers.get[ETag].map(_.tag)
          second <- app.run(asking(tag))
          stale <- app.run(asking(Some(EntityTag("stale-etag"))))
          unknown <- app.run(Request[IO](Method.GET, uri"/system/pkl/nope.pkl"))
        } yield {
          assertEquals(pulled.status, Status.Ok)
          assert(
            body.contains("""import "@fh-dashboard/hass.pkl""""),
            clue = body.take(200)
          )
          assert(tag.isDefined, clue = pulled.headers)
          assertEquals(pulled.headers.get[`Cache-Control`], Some(noCache))
          assertEquals(second.status, Status.NotModified)
          // A bare 304 would let a cache fall back to its own heuristics.
          assertEquals(second.headers.get[`Cache-Control`], Some(noCache))
          assertEquals(second.headers.get[ETag].map(_.tag), tag)
          assertEquals(stale.status, Status.Ok)
          assertEquals(unknown.status, Status.NotFound)
        }
      }
  }

  test(
    "end user on a local editor, no checkout: both packages arrive from the instance"
  ) {
    // A laptop with neither lib nor checkout: two `package://fh.invalid/…` pins
    // and one rewrite toward `/system/pkl/packages/`. pkl's real resolver over
    // a real socket, so a drift in metadata shape, zip layout or route breaks
    // here.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val instanceCache = root / "pkl-cache"
    val _ = PklWorkspace.bootstrapInto(instance, bundled, instanceCache)
    val _ =
      DumpPackage.seedFromText(
        instance,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )
    val v = LibPackage.version(dashboards / "lib")
    val artifacts = LibPackage.build(dashboards / "lib")
    val homeV =
      Pins.homeVersion(instance).getOrElse(fail("instance not pinned"))
    val homeSha = LibPackage.sha256(
      os.read.bytes(
        DumpPackage.cacheEntryDir(instanceCache, homeV) /
          s"fh-home@$homeV.json"
      )
    )

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

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        workspace = Some(instance)
      )
      .flatMap { ts =>
        EmberServerBuilder
          .default[IO]
          .withHost(host"127.0.0.1")
          .withPort(port"0")
          .withHttpApp(ts.gatedApp)
          .withShutdownTimeout(0.seconds)
          .build
          .map(bound => (ts, bound.baseUri))
      }
      .use { case (ts, base) =>
        val app = ts.gatedApp
        val get = (file: String) =>
          app.run(
            Request[IO](
              Method.GET,
              Uri.unsafeFromString(s"/system/pkl/packages/$file")
            )
          )
        for {
          // The metadata's sha256 is what the zip hashes to: the pin the
          // laptop's lockfile gets.
          meta <- get(s"fh-dashboard@$v")
          metaBody <- meta.body.through(fs2.text.utf8.decode).compile.string
          zip <- get(s"fh-dashboard@$v.zip")
          zipBytes <- zip.body.compile.to(Array)
          missing <- get("fh-dashboard@9.9.9-nosuch")

          properties <- IO.blocking(
            resolveAndEvalOverHttp(laptop, laptopCache, base)
          )

          // A malformed artifact name must never index into the filesystem.
          badArtifact <- SystemPkl
            .fromDisk(instance)
            .packageArtifact("..")
            .attempt
        } yield {
          assert(badArtifact.isLeft, clue = badArtifact)
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
    // What `fh init`/`pull` read before rewriting the laptop's pins.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = PklWorkspace.bootstrapInto(instance, bundled, root / "pkl-cache")
    val _ =
      DumpPackage.seedFromText(
        instance,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        workspace = Some(instance)
      )
      .use { ts =>
        val app = ts.gatedApp
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
    // The server half of `fh init`/`pull`, at the interface the script consumes
    // (its logic has its own suite, scripts/fh.test.scala). When the home
    // changes, the index moves to a new content version whose artifacts are
    // served too: what `pull` re-pins to.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = PklWorkspace.bootstrapInto(instance, bundled, root / "pkl-cache")
    val _ =
      DumpPackage.seedFromText(
        instance,
        PklDump.render(HouseFixture.transformedDump),
        Some(bundled)
      )

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        workspace = Some(instance)
      )
      .use { ts =>
        val app = ts.gatedApp
        val get = (path: String) =>
          app.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

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

  /** A laptop's pkl tooling minus the manifest's rewrite sugar: an HttpClient
    * rewriting `https://fh.invalid/` to the instance, driving pkl's real
    * resolver and evaluator.
    */
  private def resolveAndEvalOverHttp(
      laptop: os.Path,
      laptopCache: os.Path,
      base: Uri
  ): java.util.Map[String, AnyRef] = {
    import org.pkl.core.http.HttpClient
    import org.pkl.core.packages.PackageResolver
    import org.pkl.core.project.{Project, ProjectDependenciesResolver}
    import org.pkl.core.{EvaluatorBuilder, ModuleSource}

    val http = HttpClient
      .builder()
      .addRewrite(
        java.net.URI.create("https://fh.invalid/"),
        java.net.URI.create(s"${base.renderString}/system/pkl/packages/")
      )
      .build()
    // A real laptop gets this from the base.pkl `fh init` fetched; the port is
    // only known now.
    os.write.append(
      laptop / "PklProject",
      s"""|evaluatorSettings {
          |  // Declaring the field REPLACES pkl's defaults, so they are relisted
          |  // here (this manifest amends pkl:Project directly, not base.pkl).
          |  allowedResources {
          |    "prop:"
          |    "env:"
          |    "file:"
          |    "modulepath:"
          |    "package:"
          |    "projectpackage:"
          |    "https:"
          |    "^${base.renderString.stripSuffix("/").replace(".", "[.]")}/"
          |  }
          |}
          |""".stripMargin
    )
    fh.view.build.PklBuild.serialized {
      val laptopProject = Project.loadFromPath((laptop / "PklProject").toNIO)
      val resolver = new ProjectDependenciesResolver(
        laptopProject,
        PackageResolver.getInstance(
          // Derived as production does; a laptop resolving from the instance
          // uses plain http.
          fh.view.build.PklBuild.securityManagerFor(laptopProject),
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
        .applyFromProject(laptopProject)
        .build()
      try
        evaluator
          .evaluate(ModuleSource.path((laptop / "mine.pkl").toNIO))
          .getProperties
      finally evaluator.close()
    }
  }

  test(
    "repo developer: lib + dump are cache packages, excluded from the watch set"
  ) {
    // Cache packages resolve to `package://…` URIs and are filtered out of the
    // watch set (ADR 0010), so only the entry and loose imports are watched.
    // Iterating on `lib/` is a restart or `fh push`.
    val dir = stageWorkspace(withDump = true)
    os.write.over(dir / "site.pkl", entryNeedingDump)

    val imports = SourceEval
      .eval(dir, "site.pkl")
      .fold(err => fail(s"eval failed: $err"), _.imports)

    assertEquals(imports, Set(dir / "site.pkl"), clue = imports)
  }

  /** A card class in a module of the author's own. */
  private val privateComponent =
    // A card author imports the kit; `components.pkl` holds no card contract.
    """module mycards
      |
      |import "@fh-dashboard/core/node.pkl" as nodes
      |
      |class Gauge extends nodes.Node {
      |  card = "gauge"
      |  cardDef = new nodes.CardDef {
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
    // Their components live only on their laptop, so they evaluate locally and
    // push the result.
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
         |card = mine.gauge("from my own component")
         |""".stripMargin
    )

    // 1. Used, the class joins `cards`: no library edit, nothing registered.
    val built = SourceEval
      .eval(dir, "mine.pkl")
      .fold(err => fail(s"eval failed: $err"), identity)
    val dashboard = DashboardBuild
      .hoistInlineSurfaces(built.value)
      .as[Dashboard]
      .fold(err => fail(s"decode failed: $err"), identity)

    assert(dashboard.cards.contains("gauge"), clue = dashboard.cards.keys)

    // 2. The instance has never seen `mycards.pkl`: the evaluated JSON is
    // pushed under a new slug. That works only while every card carries its
    // own template; a render-time dependency on template sources would break
    // here.
    TestServer
      .resource(PklFixture.buildDashboard("home", entryNeedingDump), Nil)
      .use { ts =>
        val app = ts.gatedApp
        val push = (slug: String, body: String) =>
          app.run(
            Request[IO](
              Method.POST,
              Uri.unsafeFromString(s"/system/push/$slug")
            ).withEntity(body)
          )
        for {
          // Push is what mints the slug.
          before <- app.run(Request[IO](Method.GET, uri"/d/preview"))
          pushed <- push("preview", built.value.noSpaces)
          after <- app.run(Request[IO](Method.GET, uri"/d/preview"))
          html <- after.body.through(fs2.text.utf8.decode).compile.string

          // Push runs the eval path's validation, and the developer has no
          // server log, so the rejection comes back on the wire.
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

          // A validation rejection naming the card, not a decode 400: this body
          // decodes cleanly.
          assertEquals(bogus.status, Status.BadRequest)
          assert(bogusBody.contains("nosuchcard"), clue = bogusBody)

          assertEquals(notJson.status, Status.BadRequest)
        }
      }
  }

  test("component developer: a card class written in the dashboard itself") {
    // Reflection could not see a class in an amending module, which must be
    // `local`; the registry reads nodes, so it can.
    val dir = stageWorkspace(withDump = true)
    os.write(
      dir / "inline.pkl",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/core/node.pkl" as nodes
         |import "@fh-dashboard/theme.pkl" as th
         |
         |theme = ${PklFixture.dummyTheme}
         |
         |local class Badge extends nodes.Node {
         |  card = "badge"
         |  cardDef = new nodes.CardDef { template = "<b class=\\"badge\\">here</b>" }
         |}
         |
         |card = new Badge {}
         |""".stripMargin
    )
    val built = SourceEval
      .eval(dir, "inline.pkl")
      .fold(err => fail(s"eval failed: $err"), identity)
    val dashboard = DashboardBuild
      .hoistInlineSurfaces(built.value)
      .as[Dashboard]
      .fold(err => fail(s"decode failed: $err"), identity)
    assertEquals(dashboard.validate(), Nil)
    assertEquals(
      dashboard.cards.get("badge").map(_.template),
      Some("""<b class="badge">here</b>""")
    )
  }

  test(
    "component developer: two card classes claiming one name fail the build"
  ) {
    // Reflection made that a "Duplicate definition"; read off nodes, the two
    // must agree on the card's markup or the build names the card.
    val dir = stageWorkspace(withDump = true)
    os.write(dir / "mycards.pkl", privateComponent)
    os.write(
      dir / "clash.pkl",
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-dashboard/core/node.pkl" as nodes
         |import "mycards.pkl" as mine
         |import "@fh-dashboard/theme.pkl" as th
         |
         |theme = ${PklFixture.dummyTheme}
         |
         |local class Other extends nodes.Node {
         |  card = "gauge"
         |  cardDef = new nodes.CardDef { template = "<i>not the same</i>" }
         |}
         |
         |card = (c.column) { children { mine.gauge("a") new Other {} } }
         |""".stripMargin
    )
    val err = SourceEval
      .eval(dir, "clash.pkl")
      .left
      .toOption
      .getOrElse(
        fail("two cardDefs under one name built")
      )
    assert(
      err.contains("card 'gauge' has 2 different cardDefs"),
      clue = err
    )
  }

  test("component developer: pushing the whole SITE installs every key") {
    // `fh push site.pkl` (ADR 0021): the keys are the slugs and the URL's is
    // ignored. All-or-nothing, since a half-installed site is no state anybody
    // asked for.
    val dir = stageWorkspace(withDump = true)
    os.write.over(
      dir / Site.EntryFile,
      s"""amends "@fh-dashboard/site.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |import "@fh-dashboard/theme.pkl" as th
         |
         |dashboards {
         |  ["one"] { theme = ${PklFixture.dummyTheme}; card = c.title("first") }
         |  ["two"] { theme = ${PklFixture.dummyTheme}; card = c.title("second") }
         |}
         |""".stripMargin
    )
    val site = SourceEval
      .eval(dir, Site.EntryFile)
      .fold(err => fail(s"site eval failed: $err"), _.value.noSpaces)

    val broken = io.circe.parser
      .parse(site)
      .toOption
      .get
      .hcursor
      .downField("dashboards")
      .downField("two")
      .downField("card")
      .downField("card")
      .withFocus(_ => io.circe.Json.fromString("nosuchcard"))
      .top
      .get
      .noSpaces

    TestServer
      .resource(PklFixture.buildDashboard("home", entryNeedingDump), Nil)
      .use { ts =>
        val app = ts.gatedApp
        val push = (slug: String, body: String) =>
          app.run(
            Request[IO](
              Method.POST,
              Uri.unsafeFromString(s"/system/push/$slug")
            ).withEntity(body)
          )
        for {
          rejected <- push("ignored", broken)
          rejectedBody <- rejected.body
            .through(fs2.text.utf8.decode)
            .compile
            .string
          afterReject <- app.run(Request[IO](Method.GET, uri"/d/one"))
          pushed <- push("ignored", site)
          one <- app.run(Request[IO](Method.GET, uri"/d/one"))
          two <- app.run(Request[IO](Method.GET, uri"/d/two"))
          urlSlug <- app.run(Request[IO](Method.GET, uri"/d/ignored"))
        } yield {
          assertEquals(rejected.status, Status.BadRequest)
          assert(rejectedBody.contains("nosuchcard"), clue = rejectedBody)
          assertEquals(afterReject.status, Status.NotFound)

          assertEquals(pushed.status, Status.Ok)
          assertEquals(one.status, Status.Ok)
          assertEquals(two.status, Status.Ok)
          assertEquals(urlSlug.status, Status.NotFound)
        }
      }
  }
}
