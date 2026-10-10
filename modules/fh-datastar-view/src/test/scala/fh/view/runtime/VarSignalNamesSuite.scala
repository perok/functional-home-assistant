package fh.view.runtime

import fh.view.model.NodeId
import fh.view.testkit.HouseFixture
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** '''A variable's names are spelled twice and must agree''': by
  * `core/variable.pkl`'s `Spelling` in a component's markup, and by [[VarKey]]
  * where the server commits, seeds, adopts and reads a link. Disagreement is
  * silent — the server commits a signal no highlight reads — so the declarers
  * come off a real page and every name is checked against the server's.
  */
class VarSignalNamesSuite extends CatsEffectSuite {

  private val sensor = HouseFixture.outsideTemp

  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    (c.windowChooser) {
       |      children { c.historyChart(dump.entities.${sensor.dumpKey}).chosen() }
       |    }
       |    (c.tabs) { tabs { ["A"] { c.title("a") } ["B"] { c.title("b") } } }
       |  }
       |}
       |""".stripMargin

  private val mirrored =
    """data-fh-url="\['v\.([A-Za-z0-9_]+)\.(window|tab)'""".r

  test("the page spells every variable as the server does") {
    TestServer
      .fromWorkspace("var-names", entry, List(sensor))
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        val keys = mirrored
          .findAllMatchIn(html)
          .map(m => VarKey(NodeId.derived(m.group(1)), m.group(2)))
          .toList
        assertEquals(keys.map(_.name).sorted, List("tab", "window"), html)
        keys.foreach { key =>
          // The mirror, the shell's seed, the bar's pending seed and clear,
          // and every button's route and ask.
          assert(
            html.contains(
              s"""data-fh-url="['${key.urlParam}', $$${key.committedSignal}]""""
            ),
            clue = key
          )
          assert(html.contains(s"${key.committedSignal}: '"), clue = key)
          assert(html.contains(s"{ ${key.pendingSignal}: '' }"), clue = key)
          assert(
            html
              .contains(s"$$${key.committedSignal} == $$${key.pendingSignal}"),
            key
          )
          assert(
            html.contains(s"/${key.declarer}/${key.name}/") &&
              html.contains(s"?group=${key.group}'"),
            clue = key
          )
          assert(html.contains(s"$$${key.pendingSignal} = '"), clue = key)
        }
      }
  }
}
