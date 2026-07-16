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

  /** Stage a dashboards workspace the way every persona has one: the library,
    * both package manifests, the consumer project. `withDump = false` is the
    * laptop before a pull — the `@fh-home` package exists but is empty, which
    * is also the state a freshly-seeded add-on starts in.
    */
  private def stageWorkspace(withDump: Boolean): os.Path = {
    val dir = os.temp.dir()
    os.copy(dashboards / "lib", dir / "lib")
    os.makeDir.all(dir / "home")
    os.copy.into(dashboards / "home" / "PklProject", dir / "home")
    os.copy.into(dashboards / "PklProject", dir)
    if (withDump)
      os.write(
        dir / "home" / "dump.pkl",
        PklDump.render(HouseFixture.transformedDump)
      )
    dir
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
    val _ = fh.view.build.AddonBootstrap.run(
      ws,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache"
    )
    os.write(
      ws / "home" / "dump.pkl",
      PklDump.render(HouseFixture.transformedDump)
    )
    os.write(ws / "mine.pkl", entryNeedingDump)

    val result = SourceEval.eval(ws, "mine.pkl")
    assert(result.isRight, clue = result)
  }

  // ---------------------------------------------------------------- persona 2

  test(
    "end user on a local editor: pulling the dump makes their project resolve"
  ) {
    // The instance: a home with a live dump, serving it over /system/pkl.
    val instance = stageWorkspace(withDump = true)

    // The laptop: their copy of the workspace — lib, manifests, their entry —
    // with NO dump, because a dump is per-home live data that is never checked
    // in. This is the whole reason the endpoint exists.
    val laptop = stageWorkspace(withDump = false)
    os.write(laptop / "mine.pkl", entryNeedingDump)

    // Before pulling it CANNOT build. This is what stops the post-pull assertion
    // from passing vacuously: stage a dump anyway and the test proves nothing
    // about what the endpoint served.
    val before = SourceEval.eval(laptop, "mine.pkl")
    assert(before.isLeft, clue = s"expected an unresolvable dump, got: $before")

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
          // The pull: fetch the instance's dump and write it into the @fh-home
          // package. That is the CLI's entire job — a file copy, not an import.
          pulled <- app.run(Request[IO](Method.GET, uri))
          body <- pulled.body.through(fs2.text.utf8.decode).compile.string
          _ <- IO.blocking(os.write.over(laptop / "home" / "dump.pkl", body))

          // Now the same entry builds, against the live home, having imported
          // nothing over the wire.
          after = SourceEval.eval(laptop, "mine.pkl")

          // A pull is a conditional GET, so an unchanged home costs a 304 rather
          // than the whole dump. This is the endpoint's only ETag consumer.
          tag = pulled.headers.get[ETag].map(_.tag)
          second <- app.run(
            Request[IO](Method.GET, uri)
              .putHeaders(`If-None-Match`(tag.map(NonEmptyList.one)))
          )
        } yield {
          assertEquals(pulled.status, Status.Ok)
          assert(after.isRight, clue = after)

          // The served text reaches its schema BY ALIAS, which resolves only
          // because it landed inside a project. That is precisely why the
          // endpoint is a download API and not a module source: fetched as a
          // module this same text cannot resolve @fh-dashboard at all.
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
    "end user on a local editor, no checkout: the instance's lib arrives as a package"
  ) {
    // The complement of the pull test above: there the laptop had the lib (a
    // git copy) and lacked the dump; here it has NEITHER lib nor checkout —
    // only a `package://fh.invalid/fh-dashboard@<v>` pin and one rewrite
    // toward the instance's `/system/pkl/packages/` route. This is the whole
    // laptop story for a user who never cloned the repo, and it is
    // deliberately end-to-end: pkl's REAL package resolver over a REAL socket,
    // so any drift in the metadata shape, the zip layout, or the route breaks
    // here and not on someone's laptop.
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      instance,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache"
    )
    val v = LibPackage.version(dashboards / "lib")
    val artifacts = LibPackage.build(dashboards / "lib")

    // The laptop workspace: the pin, the @fh-home package with an
    // already-pulled dump (the pull itself is the previous test), an entry.
    // Templated via .replace — `\(version)` must reach Pkl verbatim.
    val laptop = root / "laptop"
    os.makeDir.all(laptop / "home")
    os.write(
      laptop / "PklProject",
      """amends "pkl:Project"
        |dependencies {
        |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
        |  ["fh-home"] = import("./home/PklProject")
        |}
        |""".stripMargin.replace("@V@", v)
    )
    os.write(
      laptop / "home" / "PklProject",
      """amends "pkl:Project"
        |package {
        |  name = "fh-home"
        |  baseUri = "package://fh.invalid/fh-home"
        |  version = "1.0.0"
        |  packageZipUrl = "https://fh.invalid/fh-home@\(version).zip"
        |}
        |dependencies {
        |  ["fh-dashboard"] { uri = "package://fh.invalid/fh-dashboard@@V@" }
        |}
        |""".stripMargin.replace("@V@", v)
    )
    os.write(
      laptop / "home" / "dump.pkl",
      PklDump.render(HouseFixture.transformedDump)
    )
    os.write(laptop / "mine.pkl", entryNeedingDump)
    val laptopCache = root / "laptop-cache"

    // A malformed artifact name must never index into the filesystem.
    assertEquals(SystemPkl.fromDisk(instance).packageArtifact(".."), None)

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

  test("the fh script and its discovery index are served by the instance") {
    // The script's distribution channel IS the instance; the `?format=sh`
    // index is what lets it run on curl + pkl alone (no JSON parser).
    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      instance,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache"
    )
    os.write(
      instance / "home" / "dump.pkl",
      PklDump.render(HouseFixture.transformedDump)
    )
    val _ = DumpPackage.seedFromWorkspace(instance)

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        Nil,
        SystemPkl.fromDisk(instance)
      )
      .use { ts =>
        val app = ts.server.routes.orNotFound
        for {
          script <- app.run(Request[IO](Method.GET, uri"/system/fh"))
          scriptBody <- script.body.through(fs2.text.utf8.decode).compile.string
          sh <- app.run(
            Request[IO](Method.GET, uri"/system/pkl/packages?format=sh")
          )
          shBody <- sh.body.through(fs2.text.utf8.decode).compile.string
          json <- app.run(Request[IO](Method.GET, uri"/system/pkl/packages"))
          jsonBody <- json.body.through(fs2.text.utf8.decode).compile.string
        } yield {
          assertEquals(script.status, Status.Ok)
          assert(scriptBody.contains("fh init"), clue = scriptBody.take(200))

          assertEquals(sh.status, Status.Ok)
          // Shell-sourceable and naming the same versions as the JSON form.
          val vars = shBody.linesIterator
            .map(_.split("=", 2))
            .collect { case Array(k, v) =>
              k -> v.stripPrefix("'").stripSuffix("'")
            }
            .toMap
          val doc = io.circe.parser
            .parse(jsonBody)
            .fold(err => fail(s"index not JSON: $err"), identity)
          assertEquals(
            vars.get("FH_HOME_VERSION"),
            doc.hcursor.downField("fh-home").get[String]("version").toOption
          )
          assertEquals(
            vars.get("FH_DASHBOARD_SHA256"),
            doc.hcursor
              .downField("fh-dashboard")
              .get[String]("sha256")
              .toOption
          )
        }
      }
  }

  test(
    "end user with the fh script: init, evaluate with stock pkl, push, pull"
  ) {
    // THE laptop product test: the real shell script driving the real pkl CLI
    // against the real routes over a real socket — our code appears only on
    // the instance side. Skipped when a pkl binary cannot be obtained.
    val pkl = obtainPklCli()
    assume(pkl.isDefined, "pkl CLI unavailable (offline?) — skipping")
    val pklDir = pkl.get / os.up

    val root = os.temp.dir()
    val instance = root / "fh-dashboards"
    val _ = AddonBootstrap.run(
      instance,
      bundledLib = dashboards / "lib",
      seedDir = os.pwd / "home-addon" / "dashboards-seed",
      cacheDir = root / "pkl-cache"
    )
    os.write(
      instance / "home" / "dump.pkl",
      PklDump.render(HouseFixture.transformedDump)
    )
    val _ = DumpPackage.seedFromWorkspace(instance)

    val laptop = root / "laptop"
    os.makeDir.all(laptop)
    val script = laptop / "fh"
    os.copy(
      os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "scripts" / "fh",
      script
    )

    def run(args: String*): os.CommandResult =
      os.proc("sh" :: script.toString :: args.toList)
        .call(
          cwd = laptop,
          env = Map("PATH" -> s"$pklDir:${sys.env.getOrElse("PATH", "")}"),
          check = false,
          mergeErrIntoOut = true
        )

    TestServer
      .resource(
        PklFixture.buildDashboard("home", entryNeedingDump),
        List(HouseFixture.kitchenLight),
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
          .map(bound => (ts, bound.baseUri.renderString))
      }
      .use { case (ts, base) =>
        for {
          // init: manifests written, lockfile resolved, packages cached.
          initRes <- IO.blocking(run("init", base))
          _ = assertEquals(initRes.exitCode, 0, clue = initRes.out.text())
          _ = assert(os.exists(laptop / ".fh" / "base.pkl"))
          _ = assert(os.exists(laptop / "PklProject.deps.json"))

          // The whole authoring loop is stock pkl from here: evaluation
          // resolves both packages (lib + content-versioned dump) offline
          // from the just-seeded .fh/cache.
          _ <- IO.blocking(os.write(laptop / "mine.pkl", entryNeedingDump))
          eval1 <- IO.blocking(
            os.proc(
              (pkl.get.toString :: "eval" :: "--project-dir" :: "." ::
                "-f" :: "json" :: "mine.pkl" :: Nil)
            ).call(cwd = laptop, check = false, mergeErrIntoOut = true)
          )
          _ = assertEquals(eval1.exitCode, 0, clue = eval1.out.text())
          _ = assert(eval1.out.text().contains("\"card\""))

          // push: pkl-rendered JSON crosses the wire and installs live — the
          // renderer-parity claim (our backend uses pkl's own ValueRenderers)
          // is load-bearing exactly here.
          pushRes <- IO.blocking(run("push", "mine.pkl", "preview"))
          _ = assertEquals(pushRes.exitCode, 0, clue = pushRes.out.text())
          pushed <- ts.server.routes.orNotFound
            .run(Request[IO](Method.GET, uri"/d/preview"))
          _ = assertEquals(pushed.status, Status.Ok)

          // The home changes; the instance seeds a NEW snapshot; pull re-pins.
          oldPin = os.read(laptop / ".fh" / "base.pkl")
          _ <- IO.blocking {
            os.write.append(
              instance / "home" / "dump.pkl",
              "\n// a new device appeared\n"
            )
            DumpPackage.seedFromWorkspace(instance)
          }
          pullRes <- IO.blocking(run("pull"))
          _ = assertEquals(pullRes.exitCode, 0, clue = pullRes.out.text())
          newPin = os.read(laptop / ".fh" / "base.pkl")
          _ = assertNotEquals(newPin, oldPin)
          _ = assert(
            pullRes.out.text().contains("->"),
            clue = pullRes.out.text()
          )

          // And the workspace still evaluates against the new snapshot.
          eval2 <- IO.blocking(
            os.proc(
              (pkl.get.toString :: "eval" :: "--project-dir" :: "." ::
                "-f" :: "json" :: "mine.pkl" :: Nil)
            ).call(cwd = laptop, check = false, mergeErrIntoOut = true)
          )
          _ = assertEquals(eval2.exitCode, 0, clue = eval2.out.text())
        } yield ()
      }
  }

  /** The stock pkl CLI: from PATH, from the repo-local cache, or downloaded
    * once from the pkl release matching the pinned pkl-core version. `None`
    * (test skipped) when unobtainable — e.g. offline, or an unmapped platform.
    */
  private def obtainPklCli(): Option[os.Path] = {
    val version = "0.31.1"
    val fromPath = sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .iterator
      .map(dir => os.Path(dir, os.pwd) / "pkl")
      .find(p => os.exists(p) && os.isFile(p))
    fromPath.orElse {
      // Named exactly `pkl`: the script locates it with `command -v pkl`.
      val cached = os.pwd / ".pkl-cli" / version / "pkl"
      if (os.exists(cached)) Some(cached)
      else
        platformAsset.flatMap { asset =>
          val url =
            s"https://github.com/apple/pkl/releases/download/$version/$asset"
          scala.util.Try {
            os.makeDir.all(cached / os.up)
            val tmp = cached / os.up / s"${cached.last}.part"
            os.proc("curl", "-fsSL", "-o", tmp.toString, url)
              .call(timeout = 120000)
            os.perms.set(tmp, "rwxr-xr-x")
            os.move.over(tmp, cached)
            cached
          }.toOption
        }
    }
  }

  private def platformAsset: Option[String] = {
    val osName = sys.props.getOrElse("os.name", "").toLowerCase
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase
    (osName, arch) match {
      case (n, "amd64" | "x86_64") if n.contains("linux") =>
        Some("pkl-linux-amd64")
      case (n, "aarch64") if n.contains("linux") => Some("pkl-linux-aarch64")
      case (n, "aarch64") if n.contains("mac")   => Some("pkl-macos-aarch64")
      case (n, "amd64" | "x86_64") if n.contains("mac") =>
        Some("pkl-macos-amd64")
      case _ => None
    }
  }

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

  test("repo developer: a library edit reaches every entry that imports it") {
    // Live reload is driven by the import set, so the property that matters is
    // that an entry's imports name the REAL lib files behind @fh-dashboard. If
    // they stayed opaque projectpackage: URIs, editing a card class or the
    // schema would silently stop reloading.
    val dir = stageWorkspace(withDump = true)
    os.write(dir / "dashboard.pkl", entryNeedingDump)

    val imports = SourceEval
      .eval(dir, "dashboard.pkl")
      .fold(err => fail(s"eval failed: $err"), _.imports)

    assert(imports.contains(dir / "lib" / "components.pkl"), clue = imports)
    assert(imports.contains(dir / "lib" / "hass.pkl"), clue = imports)
    assert(imports.contains(dir / "home" / "dump.pkl"), clue = imports)
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
