package fh.view.runtime

import cats.effect.IO
import fh.view.build.{AddonBootstrap, PklDump, Site, SourceEval}
import fh.view.testkit.{FixtureEntity, PklWorkspace}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Uri

import scala.concurrent.duration.*

/** The workspace's `components.pkl`: the library's components with the
  * workspace's `defaults`, imported as `c` by the site and by a dashboard in
  * its own file (ADR 0015).
  */
class WorkspaceComponentsSuite extends CatsEffectSuite {

  private def n(i: Int) = Json.fromInt(i)

  // In colour-temperature mode, so a slider that opens on the light's mode
  // shows its colour-temperature head.
  private val lamp = FixtureEntity(
    "light.lamp",
    "on",
    Map(
      "friendly_name" -> Json.fromString("Lamp"),
      "supported_color_modes" -> Json.arr(
        Json.fromString("color_temp"),
        Json.fromString("hs")
      ),
      "color_mode" -> Json.fromString("color_temp"),
      "min_color_temp_kelvin" -> n(2000),
      "max_color_temp_kelvin" -> n(6500),
      "brightness" -> n(180),
      "color_temp_kelvin" -> n(3000)
    )
  )

  private def components(opensOn: String) =
    s"""amends "@fh-dashboard/components.pkl"
       |
       |defaults { light { opensOn = List($opensOn) } }
       |""".stripMargin

  private val site =
    s"""amends "@fh-dashboard/site.pkl"
       |
       |import "components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |default = "kitchen"
       |dashboards {
       |  ["home"] { card = (c.column) { children { c.entitySlider(dump.entities.${lamp.dumpKey}) } } }
       |  ["kitchen"] = import("kitchen.pkl")
       |}
       |""".stripMargin

  private val kitchen =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) { children { c.entitySlider(dump.entities.${lamp.dumpKey}) } }
       |""".stripMargin

  private def workspace(opensOn: String): os.Path = {
    val tmp = os.temp.dir(prefix = "fh-components")
    val dump = Json.obj(
      "areas" -> Json.obj(),
      "floors" -> Json.obj(),
      "entities" -> Json.fromFields(List(lamp.toDumpEntry))
    )
    val _ = PklWorkspace.bootstrap(tmp, PklDump.render(dump))
    os.write.over(tmp / AddonBootstrap.ComponentsFile, components(opensOn))
    os.write.over(tmp / Site.EntryFile, site)
    os.write.over(tmp / "kitchen.pkl", kitchen)
    tmp
  }

  // The slider's opening start, which only an `opensOn` declares.
  private val opensOnStart = "attr[?'color_mode']"

  test("the site and a dashboard file both get the workspace's defaults") {
    def build(ws: os.Path) =
      SourceEval.eval(ws, Site.EntryFile).fold(e => fail(e), identity)
    def opensOnMode(built: SourceEval.Result): List[Boolean] =
      List("home", "kitchen").map { slug =>
        built.value.hcursor
          .downField(Site.DashboardsKey)
          .downField(slug)
          .focus
          .fold(fail(slug))(_.noSpaces)
          .contains(opensOnStart)
      }
    assertEquals(opensOnMode(build(workspace(""))), List(false, false))
    val ws = workspace("\"colourTemp\", \"colour\"")
    val built = build(ws)
    assertEquals(opensOnMode(built), List(true, true))
    assertEquals(
      built.imports.filter(_.startsWith(ws)),
      Set(
        ws / Site.EntryFile,
        ws / "kitchen.pkl",
        ws / AddonBootstrap.ComponentsFile
      )
    )
  }

  test("editing components.pkl repaints a dashboard in its own file") {
    val ws = workspace("\"colourTemp\", \"colour\"")
    TestServer.ofWorkspace(ws, List(lamp)).use { case (ts, _) =>
      // The pill names the control shown.
      awaitPage(ts, "kitchen")(_.contains(colourTemp)) *>
        ts.edit(ws / AddonBootstrap.ComponentsFile, components("")) *>
        awaitPage(ts, "kitchen")(h =>
          h.contains("""aria-label="Brightness"""") && !h.contains(colourTemp)
        )
    }
  }

  private val colourTemp = """aria-label="Colour temperature""""

  private def awaitPage(ts: TestServer, slug: String)(
      shows: String => Boolean
  ): IO[Unit] =
    fs2.Stream
      .repeatEval(
        ts.get(Uri.unsafeFromString(s"/d/$slug"))
          .flatMap(_.as[String]) <* IO.sleep(20.millis)
      )
      .find(shows)
      .compile
      .drain
      .timeout(30.seconds)
}
