package fh.view.build

import cats.effect.IO
import fh.view.testkit.{HouseFixture, PklWorkspace}
import org.typelevel.log4cats.testing.StructuredTestingLogger

/** Issue #406: where a slow boot or reload spends its time must be readable in
  * the log of a Pi that has no trace collector.
  */
class BuildTimingSuite extends munit.CatsEffectSuite {

  test("the site eval and every dashboard, broken ones too, log their time") {
    val tmp = os.temp.dir()
    val _ =
      PklWorkspace.bootstrap(tmp, PklDump.render(HouseFixture.transformedDump))
    // A transform that does not parse fails in decode, after the eval.
    os.write.over(
      tmp / "probe.pkl",
      s"""import "@fh-dashboard/core/node.pkl" as nodes
         |import "@fh-dashboard/core/slot.pkl" as slotMod
         |
         |class Probe extends nodes.Node {
         |  card = "probe"
         |  cardDef = new nodes.CardDef { template = "<div>{{v}}</div>"; slots { "v" } }
         |  slots {
         |    ["v"] = new slotMod.Slot {
         |      entityId = "${HouseFixture.outsideTemp.entityId}"
         |      transform = "'unclosed"
         |    }
         |  }
         |}
         |""".stripMargin
    )
    os.write.over(
      tmp / Site.EntryFile,
      """amends "@fh-dashboard/site.pkl"
        |import "@fh-dashboard/components.pkl" as c
        |import "probe.pkl"
        |
        |dashboards {
        |  ["good"] { card = (c.grid) { children { new c.SectionTitle { text = "hi" } } } }
        |  ["broken"] { card = new probe.Probe {} }
        |}
        |""".stripMargin
    )
    val log = StructuredTestingLogger.impl[IO]()
    for {
      decoded <- DashboardBuild.evalSite(tmp, log).map(_._1)
      lines <- log.logged.map(_.map(_.message))
    } yield {
      assert(decoded.dashboards.exists(_._2.isLeft), clue = decoded)
      assert(
        lines.exists(
          _.matches("""site\.pkl evaluated in \d+ ms \(\d+ workspace files\)""")
        ),
        clue = lines
      )
      List("good", "broken").foreach(slug =>
        assert(
          lines.exists(_.matches(s"""dashboard '$slug' decoded in \\d+ ms""")),
          clue = lines
        )
      )
    }
  }
}
