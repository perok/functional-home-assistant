package fh.view.runtime

import fh.view.model.NodeId
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** `c.select` under a node whose template knows nothing of variables: every
  * style builds, passes validation, and routes its choices to the declarer the
  * build resolved.
  */
class SelectSuite extends CatsEffectSuite {

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |
       |local shade = c.variable("shade")
       |
       |card = (c.column) {
       |  vars = shade.declare("pale")
       |  children {
       |    c.select(shade, List("pale", "deep"))
       |    c.select(shade, List("pale", "deep")).style("dropdown")
       |    c.select(shade, List("pale", "deep")).style("cycle")
       |  }
       |}
       |""".stripMargin

  test("every style chooses for the declarer above it") {
    TestServer
      .fromWorkspace("select", entry, Nil)
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        val declarer =
          """data-fh-url:shade="\['v\.([A-Za-z0-9_]+)\.shade'""".r
            .findFirstMatchIn(html)
            .map(_.group(1))
            .getOrElse(fail("the declarer carries no mirror", clues(html)))
        val key = VarKey(NodeId.derived(declarer), "shade")
        // Buttons and dropdown offer both values; the cycle's two buttons
        // each choose the other.
        assertEquals(
          s"/$declarer/shade/deep\\?group=${key.group}".r
            .findAllMatchIn(html)
            .size,
          3,
          clue = html
        )
        // The theme expands `fh-tabs`, so only the select's own class is fixed.
        assert(html.contains("""fh-select">"""), html)
        assert(html.contains("""<details class="fh-select-menu""""), html)
        assert(html.contains("""class="fh-select-cycle""""), html)
        assert(html.contains(s"$$${key.pendingSignal} || $$${key.committedSignal}"), html)
      }
  }
}
