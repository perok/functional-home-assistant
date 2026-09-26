package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.HouseFixture
import munit.CatsEffectSuite
import org.http4s.*

import scala.concurrent.duration.*

/** '''The names the server writes are the names the page reads.''' A refusal's
  * signal names are declared twice, `"_{{id}}__error"` in `core/tap.pkl` and
  * `s"_${id}__error"` in [[Server.actionSignals]], and disagreement is silent:
  * the server patches a signal nobody binds and the control never lights up. So
  * the ids come out of a real page's markup and the server builds its frame
  * from them.
  */
class ActionSignalNamesSuite extends CatsEffectSuite {

  private val light = HouseFixture.kitchenLight

  /** A guarded tap carries a node id and a tab group a pending selection: the
    * two halves of a refusal frame.
    */
  private val entry =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |card = (c.column) {
       |  children {
       |    c.button("Toggle", c.tap.service("light/toggle"))
       |    (c.tabs) {
       |      tabs {
       |        ["One"] { c.entityCard(dump.entities.${light.dumpKey}) }
       |        ["Two"] { c.entityCard(dump.entities.${light.dumpKey}) }
       |      }
       |    }
       |  }
       |}
       |""".stripMargin

  private def firstMatch(
      re: scala.util.matching.Regex,
      html: String,
      what: String
  ): String =
    re.findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail(s"the page carries no $what — the markup moved"))

  test("a refusal frame names the signals the rendered page actually binds") {
    TestServer
      .fromWorkspace("fixture-names", entry, List(light))
      .use { ts =>
        ts.page().map { html =>
          // From the markup, so a Pkl rename moves these and the server must
          // follow.
          val nodeId =
            firstMatch("""data-fh-node="([A-Za-z0-9_]+)"""".r, html, "node id")
          val groupId =
            firstMatch("""\{ ui_([A-Za-z0-9_]+):""".r, html, "tab group id")

          val req = Request[IO](
            Method.POST,
            Uri.unsafeFromString(
              s"/sse/action/x/light/toggle/${light.entityId}" +
                s"?${Server.NodeParam}=$nodeId&${Server.GroupParam}=$groupId"
            )
          )
          val names =
            Server.actionSignals(req, "refused").asObject.get.keys.toSet

          // `$<name>`: an expression reading it is what must match, and a
          // substring of a longer signal would pass a bare check.
          val bound = names - Server.ToastSignal
          assertEquals(
            bound,
            Set(s"_${nodeId}__error", s"_${groupId}__pending"),
            clue = "the server stopped naming one of the two"
          )
          bound.foreach(n =>
            assert(html.contains(s"$$$n"), s"the page never reads $$$n")
          )

          // The toast's reader is the page's handler calling a shell global,
          // and a page has shipped calling a function no build emitted.
          assert(names.contains(Server.ToastSignal), clue = names)
          assert(html.contains(s"$$${Server.ToastSignal} = ''"), clue = html)
          assert(
            html.contains("window.fhToast="),
            clue = "the shell must define fhToast"
          )
        }
      }
      .timeout(60.seconds)
  }
}
