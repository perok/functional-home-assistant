package fh.view.runtime

import cats.effect.IO
import fh.view.model.Dashboard
import fh.view.testkit.{FixtureEntity, HouseFixture}

import scala.concurrent.duration.*

/** A popup outlives its opener: the opener leaving the page, a set member or a
  * tab panel, is a patch of its own host and leaves the popup open.
  */
class PopupOutlivesOpenerSuite extends munit.CatsEffectSuite {

  override def munitIOTimeout: Duration = 90.seconds

  private val kitchen = HouseFixture.kitchenLight
  private val living = HouseFixture.livingRoomLight.copy(state = "on")
  private val entities = List(kitchen, living)

  private val header =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |""".stripMargin

  private val setEntry =
    s"""$header
       |card = (c.column) {
       |  children {
       |    q.from(List(dump.entities.${kitchen.dumpKey}, dump.entities.${living.dumpKey}))
       |      .where(q.eq(q.stateProp, "on"))
       |      .render((e) -> c.button("More", c.view.moreInfo(e)))
       |      .build()
       |  }
       |}
       |""".stripMargin

  private val tabsEntry =
    s"""$header
       |card = (c.column) {
       |  children {
       |    (c.tabs) {
       |      tabs {
       |        ["A"] { c.button("Info", c.view.moreInfo(dump.entities.${kitchen.dumpKey})) }
       |        ["B"] { c.title("bee") }
       |      }
       |    }
       |  }
       |}
       |""".stripMargin

  private def served[A](entry: String)(f: TestServer => IO[A]): IO[A] =
    TestServer.fromWorkspace("outlives", entry, entities).use(f)

  /** Its more-info popup's id (`components/popup.pkl`'s `idFor`). */
  private def moreInfoOf(entity: FixtureEntity): String =
    s"moreinfo_${entity.entityId.replace('.', '_')}"

  private val popups = VarKey(Dashboard.PageRootId, "popups")

  private val closes = "#popups > *"

  test("a set member's more-info stays open when the member leaves") {
    served(setEntry)(ts =>
      for {
        v <- ts.viewer(s"?v.popups=${moreInfoOf(kitchen)}")
        left <- v.step(kitchen.copy(state = "off"))
        state <- v.session.state
      } yield {
        val sent = left.map(_.render).mkString("\n")
        assert(left.nonEmpty, clue = "the member's leave reached nobody")
        assert(!sent.contains(closes), clue = sent)
        assertEquals(
          state.vars.get(popups),
          Some(moreInfoOf(kitchen)),
          clue = state.vars
        )
      }
    )
  }

  test("a tab panel's more-info stays open when the tab switches away") {
    served(tabsEntry)(ts =>
      for {
        v <- ts.viewer(s"?v.popups=${moreInfoOf(kitchen)}")
        r <- ts.server
          .liveSlug(ts.slug)
          .flatMap(_.renderer.get)
          .map(_.rendererOf.getOrElse(sys.error("no renderer")))
        tab = r.vars.defaults.keys
          .find(_.name == "tab")
          .getOrElse(sys.error(r.vars.defaults.toString))
        switched <- ts.choose(v.document.conn, tab, Some("1"))
        sent <- v.session.takeBacklog.map(_.map(_.render).mkString("\n"))
        state <- v.session.state
      } yield {
        assert(sent.contains("bee"), clue = (switched, sent))
        assert(!sent.contains(closes), clue = sent)
        assertEquals(
          state.vars.get(popups),
          Some(moreInfoOf(kitchen)),
          clue = state.vars
        )
      }
    )
  }
}
