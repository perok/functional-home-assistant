package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, LayoutNode, Op, Predicate, SlotSource}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import io.circe.Json

/** The candidate-set node (`docs/adr/0003-candidate-sets.md`), one slice
  * through the real runtime: presence decided by a member's clauses,
  * `Placed`/`Gone` as the patch pair, and the AUTHORED candidate order rather
  * than an entity-id sort.
  *
  * The contrast with `SetMembershipSuite` is the point — the same patch
  * machinery, but membership is a static list the runtime only filters, so
  * nothing here scans the state map to find out who the members are.
  *
  * Every delta test spends its first frame ESTABLISHING the mount. A viewer
  * that has only just connected holds no membership history, so the first
  * change refills the container wholesale (`Patches.resume`'s `refill`) and
  * says nothing about deltas; the frame after it is the one under test.
  */
class SetNodeSuite extends ServerHarness {

  private val tile = Map(
    "tile" -> CardDef("<b>{{state}}</b>", slots = List("state"))
  )

  private val whileOn: Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"))

  private def tileNode(id: String): LayoutNode.Component =
    // A clause carries the COMPLETE node — its candidate's `entity_id`
    // included, because the build knew the candidate. Nothing is injected at
    // render time, which is the whole difference from a set clause.
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

  /** Five lights, shown while on, in an order that is NOT their entity-id order
    * — so a test that would pass under the old entity-id sort fails here. Five
    * so one member moving stays a minority of the group and takes the
    * per-member path rather than a churn repaint.
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
    SharedHarness.create(setDash, allOn).flatMap { h =>
      h.opening(None).map { html =>
        assertEquals(
          order(html),
          List("c_light_c", "c_light_a", "c_light_b", "c_light_d", "c_light_e")
        )
      }
    }
  }

  test("a candidate whose clause stops holding is REMOVED, not hidden") {
    SharedHarness.create(setDash, allOn).flatMap { h =>
      for {
        _ <- h.opening(None)
        _ <- h.step(off("light.e"))
        patches <- h.step(off("light.a"))
      } yield {
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode remove"), clue = p)
        assert(p.contains("selector #c_light_a"), clue = p)
        // P7: absent from the DOM, not present-and-hidden.
        assert(!p.contains("data: elements"), clue = p)
      }
    }
  }

  test("a candidate coming back is PLACED at its authored position") {
    SharedHarness.create(setDash, allOn).flatMap { h =>
      for {
        _ <- h.opening(None)
        _ <- h.step(off("light.a"))
        patches <- h.step(on("light.a"))
      } yield {
        // remove-then-insert, the idempotent pair an arrival always is.
        assertEquals(patches.size, 2, clue = patches)
        assert(patches.head.contains("mode remove"), clue = patches.head)
        val p = patches.last
        assert(p.contains("mode before"), clue = p)
        // Authored order is c,a,b,d,e — so `a` anchors before `b`, which is
        // where the AUTHOR put it, not where the alphabet would.
        assert(p.contains("selector #c_light_b"), clue = p)
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_a">"""),
          clue = p
        )
      }
    }
  }

  test("an entity outside the candidate set moves nothing") {
    SharedHarness
      .create(setDash, allOn + ("light.z" -> on("light.z")))
      .flatMap { h =>
        for {
          _ <- h.opening(None)
          patches <- h.step(off("light.z"))
        } yield assertEquals(patches, Nil, clue = patches)
      }
  }

  test("an UNGUARDED clause is present even for an entity HA never reported") {
    // Presence decided at build time is not the runtime's to revisit (P3) — and
    // a candidate with no state is exactly where a presence-by-query group
    // would have silently dropped it.
    SharedHarness
      .create(setOf(List("light.ghost"), _ => None), Map.empty)
      .flatMap { h =>
        h.opening(None).map { html =>
          assert(html.contains("""id="c_light_ghost""""), clue = html)
        }
      }
  }

  test("a reorder moves the FEWEST members that can produce it") {
    // Sets are the only thing that can reorder, so the minimisation lives here.
    // It matters because a set ordered on a live value reorders whenever two
    // members cross: moving everything on each crossing is the patch storm P7
    // exists to prevent.
    def moves(before: String, after: String) =
      Patches.reordered(before.split(" ").toList, after.split(" ").toList)

    assertEquals(moves("a b c", "a b c"), Nil)
    // One element to the front costs one move, not three.
    assertEquals(moves("a b c", "c a b"), List("c"))
    assertEquals(moves("a b c d", "a d b c"), List("d"))
    // A full reversal genuinely costs n-1 — only one element can stay put, and
    // WHICH one is arbitrary among equally minimal answers.
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
    // 2 must sort below 10 — the trap a string compare falls into, and the one
    // an author ordering by brightness hits immediately.
    val dash = sorted(
      List("light.a", "light.b", "light.c"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc")
    )
    val states =
      Map("light.a" -> bri("light.a", 2), "light.b" -> bri("light.b", 10))
        + ("light.c" -> bri("light.c", 200))
    SharedHarness.create(dash, states).flatMap { h =>
      h.opening(None).map { html =>
        assertEquals(order(html), List("c_light_c", "c_light_b", "c_light_a"))
      }
    }
  }

  test("ordering by whether a predicate HOLDS puts the true ones first") {
    val dash = sorted(
      List("light.a", "light.b", "light.c"),
      LayoutNode.SortTerm(
        LayoutNode.SortKey.Holds(
          Predicate.Cmp("attr:mode", Op.Eq, Json.fromString("night"))
        ),
        "asc"
      )
    )
    def mode(id: String, m: String) =
      st(id, "on", "mode" -> Json.fromString(m))
    val states = Map(
      "light.a" -> mode("light.a", "day"),
      "light.b" -> mode("light.b", "night"),
      "light.c" -> mode("light.c", "day")
    )
    SharedHarness.create(dash, states).flatMap { h =>
      h.opening(None).map { html =>
        // b first; a and c keep their authored order behind it.
        assertEquals(order(html), List("c_light_b", "c_light_a", "c_light_c"))
      }
    }
  }

  test("ties keep the AUTHORED order, so a tick does not reshuffle them") {
    // The mandatory stable tiebreak: without it a set ordered on a live value
    // churns Gone/Placed pairs every time anything changes.
    val dash = sorted(
      List("light.c", "light.a", "light.b"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc")
    )
    val states =
      List("light.a", "light.b", "light.c").map(id => id -> bri(id, 50)).toMap
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        html <- h.opening(None)
        patches <- h.step(bri("light.a", 50))
      } yield {
        assertEquals(order(html), List("c_light_c", "c_light_a", "c_light_b"))
        assertEquals(patches, Nil, clue = patches)
      }
    }
  }

  test("an ordering key moving WITHOUT crossing anyone emits nothing") {
    // A live-ordered set rebuilds its member list rather than patching one
    // place in it — but rebuilding is not repainting. What reaches the client
    // is still a diff of the member lists, so a brightness that moves without
    // overtaking a neighbour costs zero patches, exactly as it does for a set
    // with no ordering at all.
    val dash = sorted(
      List("light.a", "light.b", "light.c"),
      LayoutNode.SortTerm(LayoutNode.SortKey.Prop("attr:brightness"), "desc")
    )
    val states = Map(
      "light.a" -> bri("light.a", 90),
      "light.b" -> bri("light.b", 50),
      "light.c" -> bri("light.c", 10)
    )
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        _ <- h.opening(None)
        _ <- h.step(off("light.c"))
        // b climbs, but stays under a and over c: same order, no patches.
        patches <- h.step(bri("light.b", 80))
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
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        _ <- h.opening(None)
        _ <- h.step(bri("light.d", 5))
        // d overtakes c and b, landing behind a.
        patches <- h.step(bri("light.d", 35))
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
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        html <- h.opening(None)
        // c overtakes b, so the CUT moves to a member that did not change.
        patches <- h.step(bri("light.c", 25))
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
    // `light.a_b` sanitises to `c_light_a_b`, which reads as a member of a
    // container called `c_light_a` just as well as it reads as `light.a_b` in
    // `c`. A candidate set's members are all knowable at construction, so the
    // container is a lookup and the ambiguity cannot arise; the id-prefix
    // search is kept only for query groups, whose members cannot be enumerated.
    val dash = setOf(List("light.a_b", "light.a"), _ => Some(whileOn))
    val states =
      Map("light.a_b" -> on("light.a_b"), "light.a" -> on("light.a"))
    SharedHarness.create(dash, states).flatMap { h =>
      h.opening(None).map { html =>
        assert(html.contains("""id="c_light_a_b""""), clue = html)
        assert(html.contains("""id="c_light_a""""), clue = html)
      }
    }
  }

  test("a member renders a SUBTREE, woken by the entities its children bind") {
    // Composite (a): the candidate is still an entity, the rendering is not a
    // leaf. The children have no ids — the member is the one patch target for
    // everything it holds — so a child's entity has to reach the reverse index
    // through the MEMBER, or the tile silently stops updating.
    val cards = tile ++ Map(
      "col" -> CardDef(
        """<div>{{#children}}{{{html}}}{{/children}}</div>"""
      )
    )
    val subtree = LayoutNode.Component(
      "col",
      children = List(
        tileNode("light.a"),
        // A child binding a DIFFERENT entity than the candidate.
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
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        html <- h.opening(None)
        patches <- h.step(st("sensor.temp", "22"))
      } yield {
        // Both children are inside the member's bytes.
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
    // Composite (b). The outer candidates are the rooms; each tile holds a set
    // over that room's own lights. What makes it worth nesting rather than
    // composing bytes: a bulb patches ITS OWN element, and the tile — whose
    // content is a registry fact and so a literal — is never re-rendered.
    val cards = tile ++ Map(
      "col" -> CardDef("""<div>{{#children}}{{{html}}}{{/children}}</div>""")
    )
    def room(id: String, lights: List[String]) =
      id -> LayoutNode.SetMember(
        List(
          LayoutNode.SetClause(
            None,
            LayoutNode.Component(
              "col",
              children = List(
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
    SharedHarness.create(dash, states.toMap).flatMap { h =>
      for {
        html <- h.opening(None)
        // Establish the inner mount, as every delta test here must.
        _ <- h.step(off("light.d"))
        patches <- h.step(off("light.b"))
      } yield {
        // Each inner member has its own element, under its own room's set.
        assert(html.contains("""id="c_area_stue_0_0_light_a""""), clue = html)
        assert(html.contains("""id="c_area_stue_0_0_light_b""""), clue = html)
        assert(html.contains("""id="c_area_bad_0_0_light_c""""), clue = html)
        // A bulb going out removes ITS element. The tile is untouched — no
        // patch names the room, and nothing re-sends the other room at all.
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode remove"), clue = p)
        assert(p.contains("selector #c_area_stue_0_0_light_b"), clue = p)
        assert(!p.contains("c_area_bad"), clue = p)
      }
    }
  }

  test("every nested group the markup shows is one the graph registered") {
    // The failure this catches is the worst-behaved one in the whole set path:
    // the ids are right, the HTML is right, the graph syncs — and NO PATCH is
    // ever emitted, because the container the recorder maintains is not the
    // element the browser has. It happened once already (`affectedSets`
    // reading the static index, which cannot hold a nested set).
    //
    // The id scheme is one function now (`Renderer.innerSetId`, read by both
    // `memberSources` and `memberChild`), so the two ends cannot drift by
    // spelling. This pins the property that survives that refactor: whatever
    // the renderer PAINTS as a group is something the renderer KNOWS as a
    // container. Two levels deep, so a scheme that happens to work at one level
    // does not pass.
    val cards = tile ++ Map(
      "col" -> CardDef("""<div>{{#children}}{{{html}}}{{/children}}</div>""")
    )
    def wrap(children: List[LayoutNode]) =
      LayoutNode.Component("col", children = children)
    def leafSet(ids: List[String]) = LayoutNode.SetNode(
      candidates = ids,
      members = ids.map { l =>
        l -> LayoutNode.SetMember(List(LayoutNode.SetClause(None, tileNode(l))))
      }.toMap
    )
    // outer set -> member -> col -> middle set -> member -> col -> leaf set
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

    // Every `fh-group` element in the markup is a container the graph knows.
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
        r.setContainer(id).isDefined,
        clue = s"painted group '$id' is not a registered container; html: $html"
      )
    )
    // ...and the deepest one really is two levels down, so this is not passing
    // on the root alone.
    assert(painted.exists(_.count(_ == '_') >= 6), clue = painted)
  }

  test("a COUNT over other entities decides presence, and wakes the member") {
    // "Show this while more than one light in the room is on." The counted
    // lights are not candidates of this set — only the count names them — so
    // the reverse index has to learn about them through
    // `Predicate.referencedEntities`, exactly as it does for a cross-entity
    // guard.
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
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        // One on: the banner is absent even though it is a candidate, and its
        // OWN state ("off") is irrelevant — a count reads only what it names.
        html <- h.opening(None)
        patches <- h.step(on("light.y"))
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
    // The per-member cross-entity case: a light shows while its own room's
    // sensor is on. The sensor is not a candidate, so nothing about the set's
    // membership names it — only the guard does, through
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
    SharedHarness.create(dash, states).flatMap { h =>
      for {
        html <- h.opening(None)
        _ <- h.step(off("light.e"))
        patches <- h.step(on(hall))
      } yield {
        // Gated off at first paint, on the sensor's state rather than its own.
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
