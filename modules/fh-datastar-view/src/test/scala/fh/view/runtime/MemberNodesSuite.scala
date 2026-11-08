package fh.view.runtime

import fh.view.runtime.RendererTestOps.*

import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Region,
  SlotSource,
  TemplateSlot,
  Surface
}
import fh.view.fetch.FetchSnapshot
import fh.view.testkit.DashboardBuilders.{declared, st}
import fh.view.testkit.TestIds.given
import io.circe.Json
import org.http4s.Status

/** A set member's children are nodes of their own (#556): ids the static walk
  * would give them, patched as leaves, baking a viewer's selection.
  */
class MemberNodesSuite extends ServerHarness {

  private val cards = Map(
    "tile" -> CardDef("<b>{{state}}</b>"),
    "col" -> CardDef(
      """<div>{{{children}}}</div>""",
      regions = Map("children" -> Region())
    ),
    "host" -> CardDef(
      """<div id="{{hostId}}">{{{panel}}}</div>""",
      regions = Map("panel" -> Region(Region.Baked))
    ),
    "text" -> CardDef("<p>{{text}}</p>")
  )

  private def tile(id: String) = LayoutNode.Component(
    "tile",
    Map(
      "entity_id" -> TemplateSlot(SlotSource(literal = Some(id))),
      "state" -> TemplateSlot(SlotSource())
    )
  )

  private def col(children: LayoutNode*) =
    LayoutNode.Component("col", regions = LayoutNode.kids(children*))

  private val whileOn: Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"))

  private val lights =
    List("light.c", "light.a", "light.b", "light.d", "light.e")

  // Each light a column of itself and a shared sensor: a member holding nodes.
  private val columns = Dashboard(
    cards = cards,
    card = LayoutNode.SetNode(
      candidates = lights,
      members = lights.map { id =>
        id -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(Some(whileOn), col(tile(id), tile("sensor.t")))
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
      r.renderNodeById(id, allOn, fragments = FetchSnapshot.empty).isDefined
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
      card = LayoutNode.SetNode(
        candidates = lights,
        members = lights.map { id =>
          id -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(whileOn),
                col(tile(id), tile("sensor.t"))
              ),
              LayoutNode.SetClause(None, tile(id))
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

  // Each member hosts a baked panel chosen by `tab`, declared above the set.
  private val panels = {
    val ids = List("light.a", "light.b")
    def panel(member: String, i: Int, text: String) = Surface(
      LayoutNode
        .Component(
          "text",
          Map("text" -> TemplateSlot(SlotSource(literal = Some(text))))
        ),
      bakeInto = Some(member),
      bakeAs = Some("panel"),
      bakeIndex = Some(i),
      activation = Activation.Var("tab"),
      // As the build tags a surface baked inside a clause.
      clause = Some(0)
    )
    Dashboard(
      cards = cards,
      card = LayoutNode.Component(
        "col",
        vars = declared("tab" -> "0"),
        regions = LayoutNode.kids(
          LayoutNode.SetNode(
            candidates = ids,
            members = ids.map { id =>
              id -> LayoutNode.SetMember(
                List(
                  LayoutNode.SetClause(
                    None,
                    LayoutNode.Component(
                      "host",
                      Map(
                        "entity_id" -> TemplateSlot(
                          SlotSource(literal = Some(id))
                        )
                      )
                    )
                  )
                )
              )
            }.toMap
          )
        )
      ),
      surfaces = Map(
        "a0" -> panel("c_0_light_a", 0, "A first"),
        "a1" -> panel("c_0_light_a", 1, "A second"),
        "b0" -> panel("c_0_light_b", 0, "B first"),
        "b1" -> panel("c_0_light_b", 1, "B second")
      )
    )
  }

  private val twoOn =
    Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))

  test("a baked region inside a member shows this viewer's member") {
    assertEquals(panels.validate(), Nil)
    val r = Renderer.create(panels)
    val html = r.renderBody(twoOn, picking("c_0_light_a_k0" -> 1))
    assert(html.contains("A second") && !html.contains("A first"), clue = html)
    assert(html.contains("B first") && !html.contains("B second"), clue = html)
    assert(html.contains("""id="c_0_light_a_panel""""), clue = html)
  }

  test("a write swaps each member's panel, not the member") {
    live(panels, twoOn) { ts =>
      for {
        v <- ts.viewer()
        wrote <- ts.postResult(
          s"sse/var/${ts.slug}/c/tab/1?group=var_c__tab",
          body = s"""{"${Server.ConnSignal}":"${v.document.conn}"}"""
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
    val flipping = panels.copy(surfaces =
      Map(
        "a0" -> panels
          .surfaces("a0")
          .copy(activation =
            Activation.State(
              Predicate.Cmp(
                "state",
                Op.Eq,
                Json.fromString("on"),
                entity = Some("light.a")
              )
            )
          )
      )
    )
    assertEquals(flipping.validate(), Nil)
  }
}
