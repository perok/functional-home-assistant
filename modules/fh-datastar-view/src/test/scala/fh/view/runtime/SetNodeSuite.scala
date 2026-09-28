package fh.view.runtime

import fh.view.runtime.RendererTestOps.*

import cats.effect.IO
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  Region,
  SlotSource
}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import io.circe.Json

/** The candidate-set node (ADR 0003): presence decided by a member's clauses,
  * `Placed`/`Gone` as the patch pair, and the authored order. Membership is a
  * static list the runtime only filters.
  *
  * Every delta test spends its first frame establishing the host: a
  * just-connected viewer has no membership history, so the first change refills
  * wholesale and the frame after it is under test.
  */
class SetNodeSuite extends ServerHarness {

  // Opens documents; see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  /** The document's first paint, and a viewer holding it. */
  private def viewing(dash: Dashboard, states: Map[String, EntityState])(
      f: (String, TestServer.Viewer) => IO[Unit]
  ): IO[Unit] =
    live(dash, states)(_.viewer().flatMap(v => f(v.document.html, v)))

  private def step(v: TestServer.Viewer, next: EntityState) =
    v.change(next).map(elementPatches)

  private val tile = Map(
    "tile" -> CardDef("<b>{{state}}</b>", slots = List("state"))
  )

  private val whileOn: Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"))

  private def tileNode(id: String): LayoutNode.Component =
    // A clause carries the complete node, `entity_id` included; nothing is
    // injected at render time.
    LayoutNode.Component(
      "tile",
      Map(
        "entity_id" -> SlotSource(literal = Some(id)),
        "state" -> SlotSource()
      )
    )

  private def setOf(
      candidates: List[String],
      guard: String => Option[Predicate],
      orderBy: List[LayoutNode.SortTerm] = Nil,
      limit: Option[Int] = None
  ): Dashboard =
    Dashboard(
      cards = tile,
      card = LayoutNode.SetNode(
        candidates = candidates,
        members = candidates.map { id =>
          id -> LayoutNode.SetMember(
            List(LayoutNode.SetClause(guard(id), tileNode(id)))
          )
        }.toMap,
        orderBy = orderBy,
        limit = limit
      )
    )

  /** Shown while on, in an order that is not entity-id order. Five, so one
    * member moving is a minority and takes the per-member path.
    */
  private val lights =
    List("light.c", "light.a", "light.b", "light.d", "light.e")

  private def setDash = setOf(lights, _ => Some(whileOn))

  private def allOn = lights.map(id => id -> on(id)).toMap

  private def order(html: String): List[String] =
    """id="(c_light_[a-z])"""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .toList

  test("a set renders its candidates in AUTHORED order, not entity-id order") {
    viewing(setDash, allOn) { (html, _) =>
      IO {
        assertEquals(
          order(html),
          List("c_light_c", "c_light_a", "c_light_b", "c_light_d", "c_light_e")
        )
      }
    }
  }

  test("a candidate whose clause stops holding is REMOVED, not hidden") {
    viewing(setDash, allOn) { (_, v) =>
      for {
        _ <- step(v, off("light.e"))
        patches <- step(v, off("light.a"))
      } yield {
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode remove"), clue = p)
        assert(p.contains("selector #c_light_a"), clue = p)
        // Absent from the DOM, not present-and-hidden.
        assert(!p.contains("data: elements"), clue = p)
      }
    }
  }

  test("a candidate coming back is PLACED at its authored position") {
    viewing(setDash, allOn) { (_, v) =>
      for {
        _ <- step(v, off("light.a"))
        patches <- step(v, on("light.a"))
      } yield {
        // Remove-then-insert, the idempotent pair an arrival always is.
        assertEquals(patches.size, 2, clue = patches)
        assert(patches.head.contains("mode remove"), clue = patches.head)
        val p = patches.last
        assert(p.contains("mode before"), clue = p)
        // Authored order is c,a,b,d,e, so `a` anchors before `b`.
        assert(p.contains("selector #c_light_b"), clue = p)
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_a">"""),
          clue = p
        )
      }
    }
  }

  test("a reorder moves the FEWEST members that can produce it") {
    // A set ordered on a live value reorders whenever two members cross, so
    // moving everything on each crossing would be a patch storm.
    def moves(before: String, after: String) =
      Patches.reordered(before.split(" ").toList, after.split(" ").toList)

    assertEquals(moves("a b c", "a b c"), Nil)
    assertEquals(moves("a b c", "c a b"), List("c"))
    assertEquals(moves("a b c d", "a d b c"), List("d"))
    // A full reversal costs n-1; which element stays is arbitrary.
    assertEquals(moves("a b c", "c b a").size, 2)
  }

  private def bri(id: String, v: Int) =
    st(id, "on", "brightness" -> Json.fromInt(v))

  private def sorted(
      candidates: List[String],
      by: LayoutNode.SortTerm,
      limit: Option[Int] = None
  ) = setOf(candidates, _ => Some(whileOn), List(by), limit)

  test("a live ordering key sorts the PRESENT members, numerically") {
    // 2 must sort below 10, the trap a string compare falls into.
    val dash = sorted(
      List("light.a", "light.b", "light.c"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc")
    )
    val states =
      Map("light.a" -> bri("light.a", 2), "light.b" -> bri("light.b", 10))
        + ("light.c" -> bri("light.c", 200))
    viewing(dash, states) { (html, _) =>
      IO {
        assertEquals(order(html), List("c_light_c", "c_light_b", "c_light_a"))
      }
    }
  }

  test("an ordering key moving WITHOUT crossing anyone emits nothing") {
    // Rebuilding the member list is not repainting: the client gets a diff, so
    // a value moving without overtaking a neighbour costs zero patches.
    val dash = sorted(
      List("light.a", "light.b", "light.c"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc")
    )
    val states = Map(
      "light.a" -> bri("light.a", 90),
      "light.b" -> bri("light.b", 50),
      "light.c" -> bri("light.c", 10)
    )
    viewing(dash, states) { (_, v) =>
      for {
        _ <- step(v, off("light.c"))
        patches <- step(v, bri("light.b", 80))
      } yield assertEquals(patches, Nil, clue = patches)
    }
  }

  test("a reorder is Gone/Placed, and only for the member that moved") {
    val dash = sorted(
      List("light.a", "light.b", "light.c", "light.d"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc")
    )
    val states = Map(
      "light.a" -> bri("light.a", 40),
      "light.b" -> bri("light.b", 30),
      "light.c" -> bri("light.c", 20),
      "light.d" -> bri("light.d", 10)
    )
    viewing(dash, states) { (_, v) =>
      for {
        _ <- step(v, bri("light.d", 5))
        patches <- step(v, bri("light.d", 35))
      } yield {
        assertEquals(patches.size, 2, clue = patches)
        assert(patches.head.contains("mode remove"), clue = patches.head)
        val p = patches.last
        assert(p.contains("mode before"), clue = p)
        assert(p.contains("selector #c_light_b"), clue = p)
      }
    }
  }

  test("`limit` cuts the losers out of the DOM, not into a hidden state") {
    val dash = sorted(
      List("light.a", "light.b", "light.c"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc"),
      limit = Some(2)
    )
    val states = Map(
      "light.a" -> bri("light.a", 30),
      "light.b" -> bri("light.b", 20),
      "light.c" -> bri("light.c", 10)
    )
    viewing(dash, states) { (html, v) =>
      for {
        // The cut moves to a member that did not change.
        patches <- step(v, bri("light.c", 25))
      } yield {
        assertEquals(order(html), List("c_light_a", "c_light_b"))
        assert(!html.contains("c_light_c"), clue = html)
        assert(patches.nonEmpty, clue = patches)
        assert(
          patches.exists(_.contains("c_light_c")),
          clue = patches
        )
      }
    }
  }

  test("a member is found by OWNERSHIP, not by parsing its id") {
    // `c_light_a_b` reads as a member of `c_light_a` as well as `light.a_b` in
    // `c`. A set's members are all known at construction, so the container is a
    // lookup and the ambiguity cannot arise.
    val dash = setOf(List("light.a_b", "light.a"), _ => Some(whileOn))
    val states =
      Map("light.a_b" -> on("light.a_b"), "light.a" -> on("light.a"))
    viewing(dash, states) { (html, _) =>
      IO {
        assert(html.contains("""id="c_light_a_b""""), clue = html)
        assert(html.contains("""id="c_light_a""""), clue = html)
      }
    }
  }

  test("a member renders a SUBTREE, woken by the entities its children bind") {
    // The children have no ids, so a child's entity has to reach the reverse
    // index through the member, or the tile silently stops updating.
    val cards = tile ++ Map(
      "col" -> CardDef(
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("children" -> Region())
      )
    )
    val subtree = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        tileNode("light.a"),
        LayoutNode.Component(
          "tile",
          Map(
            "entity_id" -> SlotSource(literal = Some("sensor.temp")),
            "state" -> SlotSource()
          )
        )
      )
    )
    val dash = Dashboard(
      cards = cards,
      card = LayoutNode.SetNode(
        candidates = List("light.a"),
        members = Map(
          "light.a" -> LayoutNode.SetMember(
            List(LayoutNode.SetClause(None, subtree))
          )
        )
      )
    )
    val states =
      Map("light.a" -> on("light.a"), "sensor.temp" -> st("sensor.temp", "21"))
    viewing(dash, states) { (html, v) =>
      for {
        patches <- step(v, st("sensor.temp", "22"))
      } yield {
        assert(html.contains("<b>on</b>"), clue = html)
        assert(html.contains("<b>21</b>"), clue = html)
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("""id="c_light_a""""), clue = p)
        assert(p.contains("<b>22</b>"), clue = p)
      }
    }
  }

  test("a tile per room: the inner set is addressable, the tile is not") {
    // Nested rather than composed so a bulb patches its own element, and the
    // tile, a registry fact and so a literal, is never re-rendered.
    val cards = tile ++ Map(
      "col" -> CardDef(
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("children" -> Region())
      )
    )
    def room(id: String, lights: List[String]) =
      id -> LayoutNode.SetMember(
        List(
          LayoutNode.SetClause(
            None,
            LayoutNode.Component(
              "col",
              regions = LayoutNode.kids(
                LayoutNode.SetNode(
                  candidates = lights,
                  members = lights.map { l =>
                    l -> LayoutNode.SetMember(
                      List(LayoutNode.SetClause(Some(whileOn), tileNode(l)))
                    )
                  }.toMap
                )
              )
            )
          )
        )
      )
    val dash = Dashboard(
      cards = cards,
      card = LayoutNode.SetNode(
        candidates = List("area.stue", "area.bad"),
        members = Map(
          room("area.stue", List("light.a", "light.b", "light.d")),
          room("area.bad", List("light.c"))
        )
      )
    )
    val states =
      List("light.a", "light.b", "light.c", "light.d").map(id => id -> on(id))
    viewing(dash, states.toMap) { (html, v) =>
      for {
        _ <- step(v, off("light.d"))
        patches <- step(v, off("light.b"))
      } yield {
        assert(html.contains("""id="c_area_stue_0_0_light_a""""), clue = html)
        assert(html.contains("""id="c_area_stue_0_0_light_b""""), clue = html)
        assert(html.contains("""id="c_area_bad_0_0_light_c""""), clue = html)
        // The tile is untouched: no patch names the room.
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode remove"), clue = p)
        assert(p.contains("selector #c_area_stue_0_0_light_b"), clue = p)
        assert(!p.contains("c_area_bad"), clue = p)
      }
    }
  }

  test("every nested group the markup shows is one the graph registered") {
    // The worst-behaved failure in the set path: ids, HTML and graph are right,
    // and no patch is ever emitted, because the recorder's container is not the
    // browser's element (it happened: `affectedSets` read the static index,
    // which cannot hold a nested set). Whatever the renderer paints as a group
    // must be a container it knows, two levels deep so one level cannot pass by
    // luck.
    val cards = tile ++ Map(
      "col" -> CardDef(
        """<div>{{#children}}{{{html}}}{{/children}}</div>""",
        regions = Map("children" -> Region())
      )
    )
    def wrap(children: List[LayoutNode]) =
      LayoutNode.Component("col", regions = LayoutNode.kids(children*))
    def leafSet(ids: List[String]) = LayoutNode.SetNode(
      candidates = ids,
      members = ids.map { l =>
        l -> LayoutNode.SetMember(List(LayoutNode.SetClause(None, tileNode(l))))
      }.toMap
    )
    val middle = LayoutNode.SetNode(
      candidates = List("area.stue"),
      members = Map(
        "area.stue" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(
              None,
              wrap(List(leafSet(List("light.a", "light.b"))))
            )
          )
        )
      )
    )
    val dash = Dashboard(
      cards = cards,
      card = LayoutNode.SetNode(
        candidates = List("floor.up"),
        members = Map(
          "floor.up" -> LayoutNode.SetMember(
            List(LayoutNode.SetClause(None, wrap(List(middle))))
          )
        )
      )
    )
    val r = Renderer.create(dash)
    val states = List("light.a", "light.b").map(id => id -> on(id)).toMap
    val html = r.renderBody(states)

    val painted = """id="([^"]+)"""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .filter(id => html.contains(s"""fh-group" id="$id""""))
      .toList
    assert(
      painted.length >= 3,
      clue = s"expected 3 nested groups, got $painted"
    )
    painted.foreach(id =>
      assert(
        r.members.setContainer(id).isDefined,
        clue = s"painted group '$id' is not a registered container; html: $html"
      )
    )
    // The deepest is really two levels down, so the root alone cannot pass.
    assert(painted.exists(_.count(_ == '_') >= 6), clue = painted)
  }

  test("a COUNT over other entities decides presence, and wakes the member") {
    // The counted lights are not candidates, so the reverse index learns them
    // through `Predicate.referencedEntities`, as for a cross-entity guard.
    val counted = List("light.x", "light.y", "light.z")
    val moreThanOneOn = Predicate.Count(
      candidates = counted,
      when = counted.map(_ -> whileOn).toMap,
      op = Op.Gt,
      value = Json.fromInt(1)
    )
    val dash = setOf(List("light.banner"), _ => Some(moreThanOneOn))
    val states = Map("light.banner" -> off("light.banner")) ++
      Map("light.x" -> on("light.x")) ++
      counted.tail.map(id => id -> off(id)).toMap
    // A count reads only what it names, so the banner's own state is
    // irrelevant.
    viewing(dash, states) { (html, v) =>
      for {
        patches <- step(v, on("light.y"))
      } yield {
        assert(!html.contains("c_light_banner"), clue = html)
        assert(patches.nonEmpty, clue = patches)
        assert(
          patches.exists(_.contains("""id="c_light_banner"""")),
          clue = patches
        )
      }
    }
  }

  test("a guard naming ANOTHER entity is woken by that entity") {
    // The sensor is not a candidate; only the guard names it, through
    // `Predicate.referencedEntities`.
    val hall = "binary_sensor.hall"
    val gated =
      List("light.b", "light.a", "light.c", "light.d", "light.e")
    val dash = setOf(
      gated,
      {
        case "light.a" =>
          Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"), Some(hall)))
        case _ => None
      }
    )
    val states = gated.map(id => id -> on(id)).toMap + (hall -> off(hall))
    viewing(dash, states) { (html, v) =>
      for {
        _ <- step(v, off("light.e"))
        patches <- step(v, on(hall))
      } yield {
        assert(!html.contains("""id="c_light_a""""), clue = html)
        assertEquals(patches.size, 2, clue = patches)
        val p = patches.last
        assert(p.contains("mode before"), clue = p)
        assert(p.contains("selector #c_light_c"), clue = p)
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_a">"""),
          clue = p
        )
      }
    }
  }
}
