package fh.view.runtime

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import fh.view.testkit.Conds

import fh.view.runtime.RendererTestOps.*

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Value,
  ValueExpr,
  TemplateSlot,
  Slot
}
import fh.view.fetch.FetchSnapshot
import fh.view.testkit.DashboardBuilders
import fh.view.testkit.DashboardBuilders.{declared, st}
import fh.view.testkit.TestIds.given
import io.circe.Json
import org.http4s.Status

/** A set member's children are nodes of their own (#556): ids the static walk
  * would give them, patched as leaves, showing a viewer's choice.
  */
class MemberNodesSuite extends ServerHarness {

  private val cards = Sets.cards ++ Map(
    DashboardBuilders.ChoiceCard -> DashboardBuilders.choiceCard,
    "tile" -> TestCard("<b>{{state}}</b>"),
    "col" -> TestCard(
      """<div>{{{children}}}</div>""",
      regions = Set("children")
    ),
    "host" -> TestCard(
      """<div id="{{hostId}}">{{{panel}}}</div>""",
      regions = Set("panel")
    ),
    "text" -> TestCard("<p>{{text}}</p>")
  )

  private def tile(id: String) = LayoutNode.Component(
    "tile",
    Slots.values(
      Map(
        "entity_id" -> TemplateSlot(ValueExpr.literal(id)),
        "state" -> TemplateSlot(ValueExpr(Value.Entity(Some(id))))
      )
    )
  )

  private def col(children: LayoutNode*) =
    LayoutNode.Component("col", slots = Slots.kids(children*))

  private val whileOn: ValueExpr =
    Conds.cmp("state", Op.Eq, Json.fromString("on"))

  private val lights =
    List("light.c", "light.a", "light.b", "light.d", "light.e")

  // Each light a column of itself and a shared sensor: a member holding nodes.
  private val columns = Dashboard(
    cards = cards,
    card = Sets.node(
      candidates = lights,
      members = lights.map { id =>
        id -> Sets.Member(
          List(
            Sets.Clause(Some(whileOn), col(tile(id), tile("sensor.t")))
          )
        )
      }.toMap
    )
  )

  private val allOn =
    lights.map(id => id -> on(id)).toMap + ("sensor.t" -> st("sensor.t", "21"))

  private def viewing(dash: Dashboard, states: Map[String, EntityState])(
      f: TestServer.Viewer => cats.effect.IO[Unit]
  ) = live(dash, states)(_.viewer().flatMap(f))

  private def step(v: TestServer.Viewer, next: EntityState) =
    v.change(next).map(elementPatches)

  test("every element a member paints is a node the renderer can find") {
    val r = Renderer.create(columns)
    val html = r.renderBody(allOn)
    val painted =
      """id="([^"]+)"""".r.findAllMatchIn(html).map(_.group(1)).toList
    val leaves = painted.filter(id =>
      r.renderNodeById(
        id,
        Shared.of(allOn),
        Viewing(Selections.none, FetchSnapshot.empty)
      ).isDefined
    )
    // Two leaves per member, each renderable on its own.
    assertEquals(leaves.size, 2 * lights.size, clue = painted)
    painted.foreach { id =>
      val known = leaves.contains(id) ||
        r.members.setContainer(NodeId.derived(id)).isDefined ||
        r.ancestry.ancestorsOf(NodeId.derived(id)).nonEmpty
      assert(known, clue = s"'$id' is painted but nothing knows it: $html")
    }
  }

  test("a tick patches the child that binds it, never the member") {
    viewing(columns, allOn) { v =>
      for {
        _ <- step(v, off("light.e"))
        patches <- step(v, st("sensor.t", "22"))
      } yield {
        val sent = patches.mkString("\n")
        // Each present member's sensor tile, batched into one event.
        assertEquals(
          """id="(c_light_[a-z][^"]*)"""".r
            .findAllMatchIn(sent)
            .map(_.group(1))
            .toList
            .sorted,
          List("c_light_a_1", "c_light_b_1", "c_light_c_1", "c_light_d_1"),
          clue = sent
        )
        assert(!sent.contains("<b>on</b>"), clue = sent)
      }
    }
  }

  test("a member holding nodes arrives with its children's ids") {
    viewing(columns, allOn) { v =>
      for {
        _ <- step(v, off("light.e"))
        _ <- step(v, off("light.a"))
        patches <- step(v, on("light.a"))
      } yield {
        assertEquals(patches.size, 2, clue = patches)
        val p = patches.last
        assert(p.contains("mode before"), clue = p)
        List("c_light_a", "c_light_a_0", "c_light_a_1").foreach(id =>
          assert(p.contains(s"""id="$id""""), clue = p)
        )
      }
    }
  }

  test("a clause switch to a member holding nodes places it again") {
    // Off a tile, on a column: the column has no one element to morph.
    val switching = Dashboard(
      cards = cards,
      card = Sets.node(
        candidates = lights,
        members = lights.map { id =>
          id -> Sets.Member(
            List(
              Sets.Clause(
                Some(whileOn),
                col(tile(id), tile("sensor.t"))
              ),
              Sets.Clause(None, tile(id))
            )
          )
        }.toMap
      )
    )
    viewing(switching, allOn) { v =>
      for {
        _ <- step(v, off("light.e"))
        _ <- step(v, off("light.a"))
        patches <- step(v, on("light.a"))
      } yield {
        assert(patches.exists(_.contains("mode remove")), clue = patches)
        val inserted = patches.find(_.contains("mode before"))
        assert(
          inserted.exists(_.contains("""id="c_light_a_1"""")),
          clue = patches
        )
      }
    }
  }

  private def text(t: String) = LayoutNode.Component(
    "text",
    Slots.values(Map("text" -> TemplateSlot(ValueExpr.literal(t))))
  )

  // Each member hosts a panel chosen by `tab`, declared above the set.
  private def panels(panel: String => Slot) = {
    val ids = List("light.a", "light.b")
    Dashboard(
      cards = cards,
      card = LayoutNode.Component(
        "col",
        vars = declared("tab" -> "0"),
        slots = Slots.kids(
          Sets.node(
            candidates = ids,
            members = ids.map { id =>
              id -> Sets.Member(
                List(
                  Sets.Clause(
                    None,
                    LayoutNode.Component(
                      "host",
                      Slots.values(
                        Map(
                          "entity_id" -> TemplateSlot(
                            ValueExpr.literal(id)
                          )
                        )
                      ) + ("panel" -> panel(id))
                    )
                  )
                )
              )
            }.toMap
          ) :: DashboardBuilders.choices("tab", "1")*
        )
      )
    )
  }

  private val tabbed = panels { id =>
    val n = if (id == "light.a") "A" else "B"
    Slots.tabs("tab", text(s"$n first"), text(s"$n second"))
  }

  private val twoOn =
    Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))

  test("a choice inside a member shows this viewer's member") {
    assertEquals(tabbed.validate(), Nil)
    val r = Renderer.create(tabbed)
    val html =
      r.renderBody(twoOn, picking("c_0_light_a_k0" -> 1, "c_0_light_b_k0" -> 0))
    assert(html.contains("A second") && !html.contains("A first"), clue = html)
    assert(html.contains("B first") && !html.contains("B second"), clue = html)
    assert(html.contains("""id="c_0_light_a_panel""""), clue = html)
  }

  test("a write swaps each member's panel, not the member") {
    live(tabbed, twoOn) { ts =>
      for {
        v <- ts.viewer()
        wrote <- ts.choose(
          v.document.conn,
          VarKey(NodeId.derived("c"), "tab"),
          Some("1")
        )
        frames <- v.session.takeBacklog
      } yield {
        assertEquals(wrote._1, Status.NoContent, clue = wrote)
        val sent = frames.flatMap(_.data).mkString("\n")
        assert(
          sent.contains("A second") && sent.contains("B second"),
          clue = sent
        )
        assert(sent.contains("c_0_light_a_panel"), clue = sent)
        // The member itself is never re-sent.
        assert(!sent.contains("""id="c_0_light_a""""), clue = sent)
      }
    }
  }

  test("a branch chosen by live state inside a clause builds") {
    val flipping = panels(_ =>
      Slots.iff(
        Conds.cmp("state", Op.Eq, Json.fromString("on"), Some("light.a")),
        text("A first")
      )
    )
    assertEquals(flipping.validate(), Nil)
  }
}
