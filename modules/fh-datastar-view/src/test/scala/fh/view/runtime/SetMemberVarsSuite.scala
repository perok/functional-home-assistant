package fh.view.runtime

import fh.view.testkit.FixtureEntity
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** A node variable declared inside a candidate set's clause: each member's own
  * (#556).
  */
class SetMemberVarsSuite extends CatsEffectSuite {

  private def light(id: String, state: String): FixtureEntity =
    FixtureEntity(
      id,
      state,
      Map(
        "supported_color_modes" -> Json.arr(
          Json.fromString("color_temp"),
          Json.fromString("hs")
        ),
        "color_mode" -> Json.fromString("color_temp"),
        "min_color_temp_kelvin" -> Json.fromInt(2000),
        "max_color_temp_kelvin" -> Json.fromInt(6500),
        "brightness" -> Json.fromInt(128)
      )
    )

  private val a = light("light.a", "on")
  private val b = light("light.b", "on")

  private val entry =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |card = (c.column) {
      |  children {
      |    c.entitySlider(dump.lights.b)
      |    q.from(List(dump.lights.a, dump.lights.b))
      |      .where(q.eq(q.stateProp, "on"))
      |      .render((e) -> c.entitySlider(e))
      |      .build()
      |  }
      |}
      |""".stripMargin

  // The default dashboard's lights: a slider while on, a card while off, so
  // turning one off moves it to another clause without leaving the set.
  private val byClause =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |card = (c.column) {
      |  children {
      |    q.from(List(dump.lights.a, dump.lights.b))
      |      .caseOf(q.eq(q.stateProp, "on"), c.entitySlider)
      |      .`else`(c.entityCard)
      |      .build()
      |  }
      |}
      |""".stripMargin

  // Both clauses are sliders, so both own a group of heads at the same node.
  private val twoSliders =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |card = (c.column) {
      |  children {
      |    q.from(List(dump.lights.a))
      |      .caseOf(q.eq(q.stateProp, "on"), (l) -> c.entitySlider(l).label("Lit"))
      |      .`else`((l) -> c.entitySlider(l).label("Dark"))
      |      .build()
      |  }
      |}
      |""".stripMargin

  private val ifInClause =
    """amends "@fh-dashboard/entry.pkl"
      |
      |import "@fh-dashboard/components.pkl" as c
      |import "@fh-dashboard/query.pkl" as q
      |import "@fh-home/dump.pkl" as dump
      |
      |local view = c.variable("view")
      |
      |card = (c.column) {
      |  children {
      |    q.from(List(dump.lights.a, dump.lights.b))
      |      .render((e) -> (c.column) {
      |        vars = view.declare("plain")
      |        children {
      |          c.iff(view.valueIs("detail")).then(c.title("Detail")).`else`(c.title("Plain"))
      |        }
      |      })
      |      .build()
      |  }
      |}
      |""".stripMargin

  private def keyOf(member: String, name: String = "control") =
    VarKey(fh.view.model.NodeId.derived(member), name)

  private def write(ts: TestServer, conn: String, key: VarKey, value: String) =
    ts.postResult(
      s"sse/var/${ts.slug}/${key.declarer}/${key.name}/$value?group=${key.group}",
      body = s"""{"${Server.ConnSignal}":"$conn"}"""
    )

  test(
    "each member's control is its own, and kept while the member is away"
  ) {
    TestServer
      .fromWorkspace("set-member-vars", entry, List(a, b))
      .use { ts =>
        for {
          viewer <- ts.viewer()
          chose <- write(
            ts,
            viewer.document.conn,
            keyOf("c_1_light_a"),
            "colour"
          )
          moved <- viewer.session.takeBacklog
          chosen <- viewer.session.state.map(_.vars)
          _ <- viewer.step(light("light.a", "off"))
          away <- viewer.session.state.map(_.vars)
          back <- viewer.step(a)
        } yield {
          val page = viewer.document.html
          // Both start on the mode their light is in.
          assertEquals(
            "color_temp_kelvin".r.findAllMatchIn(page).size >= 2,
            true,
            clue = page
          )
          assertEquals(chose._1, org.http4s.Status.NoContent, clue = chose)
          // Only light.a's choice moved, and only its head was sent: the
          // slider above the set and light.b declare `control` too.
          assertEquals(chosen, Map(keyOf("c_1_light_a") -> "colour"))
          val sent = moved.flatMap(_.data).mkString("\n")
          assert(sent.contains("c_1_light_a_head"), sent)
          assert(!sent.contains("c_1_light_b"), sent)
          assert(!sent.contains("\"c_0"), sent)
          assertEquals(away, chosen)
          // Back in the set, light.a shows the control chosen before it left.
          val arrived = back.flatMap(_.data).mkString("\n")
          assert(arrived.contains("c_1_light_a"), arrived)
          assert(arrived.contains("hs_color"), arrived)
        }
      }
      .timeout(60.seconds)
  }

  test("a link names one member's choice, and each member mirrors its own") {
    TestServer
      .fromWorkspace("set-member-vars", entry, List(a, b))
      .use { ts =>
        ts.page(s"?${keyOf("c_1_light_a").urlParam}=colour").map { page =>
          val heads = """max="360"""".r.findAllMatchIn(page).map(_.start)
          val (memberA, memberB) =
            (
              page.indexOf("""id="c_1_light_a""""),
              page.indexOf("""id="c_1_light_b"""")
            )
          assert(0 <= memberA && memberA < memberB, clue = page)
          assertEquals(
            heads.toList.map(i => memberA < i && i < memberB),
            List(true),
            clue = page
          )
          for (d <- List("c_0", "c_1_light_a", "c_1_light_b"))
            assert(
              page.contains(s"""data-fh-url:control="['v.$d.control'"""),
              page
            )
        }
      }
      .timeout(60.seconds)
  }

  test("a choice survives the member moving to another clause and back") {
    TestServer
      .fromWorkspace("set-member-vars", byClause, List(a, b))
      .use { ts =>
        for {
          viewer <- ts.viewer()
          chose <- write(
            ts,
            viewer.document.conn,
            keyOf("c_0_light_a"),
            "colour"
          )
          _ <- viewer.session.takeBacklog
          asCard <- viewer.step(light("light.a", "off"))
          kept <- viewer.session.state.map(_.vars)
          asSlider <- viewer.step(a)
        } yield {
          assertEquals(chose._1, org.http4s.Status.NoContent, clue = chose)
          // Off, light.a is a card: still in the set, with no `control`.
          val card = asCard.flatMap(_.data).mkString("\n")
          assert(card.contains("c_0_light_a"), card)
          assert(!card.contains("hs_color"), card)
          assertEquals(kept, Map(keyOf("c_0_light_a") -> "colour"))
          val back = asSlider.flatMap(_.data).mkString("\n")
          assert(back.contains("c_0_light_a"), back)
          assert(back.contains("hs_color"), back)
        }
      }
      .timeout(60.seconds)
  }

  test("an If over a member's own variable swaps that member's branch alone") {
    TestServer
      .fromWorkspace("set-member-vars", ifInClause, List(a, b))
      .use { ts =>
        for {
          viewer <- ts.viewer()
          wrote <- write(
            ts,
            viewer.document.conn,
            keyOf("c_0_light_a", "view"),
            "detail"
          )
          frames <- viewer.session.takeBacklog
        } yield {
          val page = viewer.document.html
          assertEquals("Plain".r.findAllMatchIn(page).size, 2, clue = page)
          assert(!page.contains("Detail"), page)
          assertEquals(wrote._1, org.http4s.Status.NoContent, clue = wrote)
          val sent = frames.flatMap(_.data).mkString("\n")
          assertEquals("Detail".r.findAllMatchIn(sent).size, 1, clue = sent)
          assert(sent.contains("c_0_light_a"), sent)
          assert(!sent.contains("c_0_light_b"), sent)
        }
      }
      .timeout(60.seconds)
  }

  test("two slider clauses each show their own heads, and a write swaps one") {
    TestServer
      .fromWorkspace(
        "set-member-vars",
        twoSliders,
        List(light("light.a", "off"))
      )
      .use { ts =>
        for {
          viewer <- ts.viewer()
          wrote <- write(
            ts,
            viewer.document.conn,
            keyOf("c_0_light_a"),
            "colour"
          )
          swapped <- viewer.session.takeBacklog
          lit <- viewer.step(a)
        } yield {
          val page = viewer.document.html
          assert(page.contains("Dark") && !page.contains("Lit"), page)
          assertEquals(wrote._1, org.http4s.Status.NoContent, clue = wrote)
          // The dark clause's colour head, never the lit clause's.
          val sent = swapped.flatMap(_.data).mkString("\n")
          assert(sent.contains("hs_color"), sent)
          assert(sent.contains("Dark") && !sent.contains("Lit"), sent)
          // Switched on, the lit clause shows the choice made while dark.
          val back = lit.flatMap(_.data).mkString("\n")
          assert(back.contains("Lit") && !back.contains("Dark"), back)
          assert(back.contains("hs_color"), back)
        }
      }
      .timeout(60.seconds)
  }
}
