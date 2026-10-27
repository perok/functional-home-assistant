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
       |    (c.history.chooser) {
       |      children { c.history.chart(dump.entities.${sensor.dumpKey}).fromChooser() }
       |    }
       |    (c.tabs) { tabs { ["A"] { c.title("a") } ["B"] { c.title("b") } } }
       |  }
       |}
       |""".stripMargin

  /** What the entry declares, which the mirror drops the param at. */
  private val declared = Map("window" -> "24h", "tab" -> "0")

  private val mirrored =
    """data-fh-url:(window|tab)="\['v\.([A-Za-z0-9_]+)\.\1'""".r

  test("the page spells every variable as the server does") {
    TestServer
      .fromWorkspace("var-names", entry, List(sensor))
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        val keys = mirrored
          .findAllMatchIn(html)
          .map(m => VarKey(NodeId.derived(m.group(2)), m.group(1)))
          .toList
        assertEquals(keys.map(_.name).sorted, List("tab", "window"), html)
        keys.foreach { key =>
          // The mirror, the shell's seeds and clear, and every button's route
          // and ask.
          assert(
            html.contains(
              s"""data-fh-url:${key.name}="['${key.urlParam}', $$${key.committedSignal}, '${declared(
                  key.name
                )}']""""
            ),
            clue = key
          )
          assert(html.contains(s"${key.committedSignal}: '"), clue = key)
          assert(html.contains(s"${key.pendingSignal}: ''"), clue = key)
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

  /** A node whose template knows nothing of variables can declare one: the
    * shell seeds and clears every ask, and the declarer's cell mirrors it.
    */
  test("any node can declare: the shell holds the ask, the cell the mirror") {
    val plain =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |
         |card = (c.column) {
         |  vars {
         |    c.variable("shade").defaultingTo("pale")
         |    c.variable("size").defaultingTo("s")
         |  }
         |  children { c.title("x") }
         |}
         |""".stripMargin
    TestServer
      .fromWorkspace("var-plain", plain, Nil)
      .use(_.page())
      .timeout(60.seconds)
      .map { html =>
        val keys = List("shade" -> "pale", "size" -> "s").map { (name, at) =>
          val id = s"""data-fh-url:$name="\\['v\\.([A-Za-z0-9_]+)\\.$name'""".r
            .findFirstMatchIn(html)
            .map(_.group(1))
            .getOrElse(fail(s"no mirror of $name", clues(html)))
          VarKey(NodeId.derived(id), name) -> at
        }
        keys.foreach { (key, at) =>
          assert(
            html.contains(
              s"""data-fh-url:${key.name}="['${key.urlParam}', $$${key.committedSignal}, '$at']""""
            ),
            clue = key
          )
          assert(
            html.contains(
              s"${key.committedSignal}: '$at', ${key.pendingSignal}: ''"
            ),
            clue = key
          )
          assert(html.contains(key.pendingClear), clue = key)
        }
        // Both on the one cell: the mirrors are keyed, so neither drops.
        assertEquals(keys.map(_._1.declarer).distinct.size, 1)
      }
  }
}
