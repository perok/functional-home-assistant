package fh.view.runtime

import fh.view.model.{Dashboard, NodeId}
import fh.view.testkit.HouseFixture
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** '''A variable's names are spelled in several places on one page and must
  * agree''': the URL mirror, the shell's seeds and clear, each button's ask and
  * each tab's highlight, all from [[VarKey]], and the popup's close, which Pkl
  * spells by hand (`components/base/popup.pkl`). Disagreement is silent — the
  * server commits a signal no highlight reads — so the declarers come off a
  * real page and every name is checked against the server's.
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
          // The mirror, the shell's seeds and clear, and every button's ask
          // before its press.
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
            s"""\\$$${key.pendingSignal} = '[^']*'; @post\\('sse/call/""".r
              .findFirstIn(html)
              .isDefined,
            clue = key
          )
        }
        // The open tab's highlight reads the tab bar's own signals.
        val tab = keys.find(_.name == "tab").get
        assert(
          html.contains(
            s"String(($$${tab.pendingSignal} || $$${tab.committedSignal})) === '0'"
          ),
          html
        )
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

  /** The close guard is the hand-spelled name, so every variable signal a page
    * with a popup open reads is held to the ones the server writes.
    */
  test("an open popup reads only the signals the server writes") {
    val popups = VarKey(Dashboard.PageRootId, "popups")
    val withPopup =
      s"""amends "@fh-dashboard/entry.pkl"
         |
         |import "@fh-dashboard/components.pkl" as c
         |
         |card = (c.column) {
         |  children {
         |    c.button("Help", c.view.popup("help", new c.Column {
         |      children { c.button("Close", c.view.closePopup()) }
         |    }))
         |  }
         |}
         |""".stripMargin
    val reads = s"""\\$$(${VarKey.SignalPrefix}[A-Za-z0-9_]+)""".r
    TestServer
      .fromWorkspace("var-popups", withPopup, Nil)
      .use(_.page("?v.popups=help"))
      .timeout(60.seconds)
      .map { html =>
        assert(html.contains("""<dialog open class="fh-popup">"""), html)
        assert(
          html.contains(s"$$${popups.committedSignal} && history.back()"),
          html
        )
        val written = Set(
          popups.committedSignal,
          popups.pendingSignal,
          popups.initialValueSignal
        )
        assertEquals(
          reads.findAllMatchIn(html).map(_.group(1)).toSet -- written,
          Set.empty[String],
          html
        )
      }
  }
}
