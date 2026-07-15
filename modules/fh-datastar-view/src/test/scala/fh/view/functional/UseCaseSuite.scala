package fh.view.functional

import cats.data.NonEmptyList
import cats.effect.IO
import fh.view.build.{DashboardBuild, PklDump, SourceEval, SystemPkl}
import fh.view.model.Dashboard
import fh.view.runtime.TestServer
import fh.view.testkit.{HouseFixture, PklFixture}
import org.http4s.*
import org.http4s.headers.{`If-None-Match`, ETag}
import org.http4s.implicits.*

/** One test per consumer in ADR 0010's "Use cases" table.
  *
  * Not unit tests of a class: each pins the load-bearing PROPERTY one persona's
  * workflow depends on. The four stories are meant to be ONE design, so a change
  * that quietly serves three of them should fail here rather than in someone's
  * home.
  *
  * The invariant under all four: **evaluation always runs against a fully-local
  * project**; an instance is synced FROM and pushed TO, never imported from.
  */
class UseCaseSuite extends munit.CatsEffectSuite {

  private val dashboards =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

  /** Stage a dashboards workspace the way every persona has one: the library,
    * both package manifests, the consumer project. `withDump = false` is the
    * laptop before a pull — the `@fh-home` package exists but is empty, which is
    * also the state a freshly-seeded add-on starts in.
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
    // What a fresh add-on does: seed the dir, write the dump from HA, evaluate.
    // pkl-lsp behind /edit resolves these same local files (it runs as a
    // server-side subprocess over on-disk paths), so nothing here is fetched.
    val dir = stageWorkspace(withDump = true)
    os.write(dir / "dashboard.pkl", entryNeedingDump)

    val result = SourceEval.eval(dir, "dashboard.pkl")
    assert(result.isRight, clue = result)
  }

  // ---------------------------------------------------------------- persona 2

  test("end user on a local editor: pulling the dump makes their project resolve") {
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

  test("component developer: their own card class reaches the registry and renders") {
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
      .fold(err => fail(s"decode failed: $err"), _.copy(slug = "pushed"))

    assert(dashboard.cards.contains("gauge"), clue = dashboard.cards.keys)

    // 2. Self-containment: the card carries its own template, so the server can
    //    render it while holding no source for it — which is what lets a push
    //    skip the Pkl layer and ship only JSON. If rendering ever grew a
    //    dependency on resolving templates from disk, this is what would catch
    //    it: `mycards.pkl` exists in no lib and in no file the server reads.
    TestServer.resource(dashboard, Nil).use { ts =>
      ts.page.map { html =>
        assert(html.contains("from my own component"), clue = html)
        assert(html.contains("""class="mine""""), clue = html)
      }
    }
  }
}
