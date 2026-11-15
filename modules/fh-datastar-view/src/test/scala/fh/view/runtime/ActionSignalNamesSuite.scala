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
       |    c.button("Toggle", c.tap.call("light/toggle", dump.entities.${light.dumpKey}))
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
        ts.page()
          .flatMap { html =>
            // From the markup, so a Pkl rename moves these and the server must
            // follow.
            val nodeId =
              firstMatch(
                """data-fh-node="([A-Za-z0-9_]+)"""".r,
                html,
                "node id"
              )
            // A tab's press writes its ask, then posts; posted with no `conn`,
            // it is refused.
            val tab =
              """\$(_var_[A-Za-z0-9_]+__pending) = '[^']*'; @post\('(sse/call/[^']+)'""".r
                .findFirstMatchIn(html)
                .getOrElse(
                  fail("the page carries no tab press — the markup moved")
                )
            val (pending, tabPress) = (tab.group(1), tab.group(2))

            val req = Request[IO](
              Method.POST,
              Uri.unsafeFromString(
                s"/sse/call/x/0123456789abcdef?${Server.NodeParam}=$nodeId"
              )
            )
            val names =
              Server.actionSignals(req, "refused", None).asObject.get.keys.toSet

            // `$<name>`: an expression reading it is what must match, and a
            // substring of a longer signal would pass a bare check.
            val bound = names - Server.ToastSignal
            assertEquals(bound, Set(s"_${nodeId}__error"))
            bound.foreach(n =>
              assert(html.contains(s"$$$n"), s"the page never reads $$$n")
            )
            ts.postResult(tabPress, body = "{}")
              .map { (_, refusal) =>
                assert(
                  refusal.contains(s"\"$pending\":\"\""),
                  clue = s"a refused choice must clear $pending: $refusal"
                )
              }
              .as(names -> html)
          }
          .map { (names, html) =>
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
