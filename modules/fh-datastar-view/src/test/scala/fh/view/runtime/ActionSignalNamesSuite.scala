package fh.view.runtime

import cats.effect.IO
import fh.view.testkit.HouseFixture
import munit.CatsEffectSuite
import org.http4s.*

import scala.concurrent.duration.*

/** **The names the server writes are the names the page reads.**
  *
  * A refusal's signal names are declared TWICE — `"_{{id}}__error"` in
  * `core/tap.pkl`, `s"_${id}__error"` in [[Server.actionSignals]] — in two
  * languages, with a Mustache pass and a CEL evaluation between them. Nothing
  * else makes them agree, and disagreement is SILENT in the worst way: the
  * server patches a signal nobody binds, so the POST still answers 200, the
  * suite still passes, and the control simply never lights up.
  *
  * So this renders a real Pkl page, takes the ids out of the MARKUP, and asks
  * the server to build its frame from those same ids — the two sides meeting on
  * a value neither of them chose. `WireShapeSuite` does the same job for the
  * wire model, and exists because that pair drifted.
  */
class ActionSignalNamesSuite extends CatsEffectSuite {

  private val light = HouseFixture.kitchenLight

  /** One guarded tap (which is what carries a node id) and one tab group (which
    * is what carries a pending selection) — the two halves of a refusal frame.
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

  private def firstMatch(re: scala.util.matching.Regex, html: String, what: String): String =
    re.findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail(s"the page carries no $what — the markup moved"))

  test("a refusal frame names the signals the rendered page actually binds") {
    TestServer
      .fromWorkspace("fixture-names", entry, List(light))
      .use { ts =>
        ts.page().map { html =>
          // Taken from the MARKUP, so a rename on the Pkl side moves these and
          // the server has to follow.
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
          val names = Server.actionSignals(req, "refused").asObject.get.keys.toSet

          // Every name the server would patch is one this page reads. `$<name>`
          // rather than the bare name: an expression READING it is the thing
          // that has to match, and a substring of some longer signal would pass
          // a bare check.
          val bound = names - Server.ToastSignal
          assertEquals(
            bound,
            Set(s"_${nodeId}__error", s"_${groupId}__pending"),
            clue = "the server stopped naming one of the two"
          )
          bound.foreach(n =>
            assert(html.contains(s"$$$n"), s"the page never reads $$$n")
          )

          // The toast is the third name, and its reader is the page's own
          // handler calling a shell global — so both ends are pinned here too.
          // It has been possible to ship a page whose toast handler called a
          // function no build emitted.
          assert(names.contains(Server.ToastSignal), clue = names)
          assert(html.contains(s"$$${Server.ToastSignal} = ''"), clue = html)
          assert(html.contains("window.fhToast="), clue = "the shell must define fhToast")
        }
      }
      .timeout(60.seconds)
  }
}
