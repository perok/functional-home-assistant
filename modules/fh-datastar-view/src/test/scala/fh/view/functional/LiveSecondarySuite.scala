package fh.view.functional

import cats.effect.IO
import fh.view.runtime.TestServer
import fh.view.testkit.HouseFixture

import scala.concurrent.duration.*

/** A live `secondary` is a text signal on every card that places one (ADR
  * 0017), from a real Pkl entry: bound on its run in the document, and moved by
  * a frame rather than by re-sending the line.
  */
class LiveSecondarySuite extends munit.CatsEffectSuite {

  private val kitchen = HouseFixture.kitchenLight

  private val cards = List(
    "c.entityCard(k)",
    "c.entityButton(k)",
    "c.entitySlider(k)"
  )

  private def entry(card: String): String =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-home/dump.pkl" as dump
       |
       |local k = dump.entities.${kitchen.dumpKey}
       |
       |card = (c.column) {
       |  children {
       |    $card.secondary(c.expr(#"state + ' now'"#))
       |  }
       |}
       |""".stripMargin

  private def withServer[A](card: String)(f: TestServer => IO[A]): IO[A] =
    TestServer
      .fromWorkspace("live-secondary", entry(card), List(kitchen))
      .use(f)
      .timeout(60.seconds)

  /** The signal the second line's run is bound to, read off the markup. */
  private def secondarySignal(html: String): String =
    """<span class="fh-sub fh-text"><span class="fh-text-run" data-text="\$([^"]+)">""".r
      .findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(fail("no bound secondary run", clues(html)))

  cards.foreach { card =>
    test(s"$card: a live secondary is bound, inline, and moved by a frame") {
      withServer(card) { ts =>
        for {
          html <- ts.page()
          sig = secondarySignal(html)
          sent <- ts.sentAfter(ts.change(kitchen.entityId, "off"))
        } yield {
          assert(html.contains(">on now</span>"), clue = html)
          assert(
            sent.contains(sig.split('.').last + "\":\"off now\""),
            clue = sent
          )
          assert(!sent.contains("fh-sub"), clue = sent)
        }
      }
    }
  }
}
