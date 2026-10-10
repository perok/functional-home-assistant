package fh.view.runtime

import cats.effect.IO
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Op,
  Predicate,
  Region,
  SlotSource,
  Surface
}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.FixtureEntity
import fh.view.testkit.TestIds.given
import io.circe.Json

/** Candidate sets on the recording pass (ADR 0003): a member switching case,
  * the churn rule choosing between a per-member delta and a whole-host fill,
  * and what reaches a viewer by what it has open. Arrival, departure and a tick
  * in place are `SetNodeSuite`'s and `DashboardBehaviourSuite`'s.
  */
class SetMembershipSuite extends ServerHarness {

  private def viewing(dash: Dashboard, states: Map[String, EntityState])(
      f: TestServer.Viewer => IO[Unit]
  ): IO[Unit] =
    live(dash, states)(_.viewer().flatMap(f))

  private def step(v: TestServer.Viewer, next: EntityState) =
    v.change(next).map(elementPatches)

  private def mode(id: String, m: String) =
    st(id, "on", "mode" -> Json.fromString(m))

  /** The only way a member's node moves without its membership moving: the case
    * it dispatches to changes (`attr:mode`).
    */

  private def caseDash = Dashboard(
    cards = Map(
      "bright" -> CardDef("<b>{{state}}</b>", slots = List("state")),
      "dim" -> CardDef("<i>{{state}}</i>", slots = List("state"))
    ),
    card = onSet(
      List("light.a", "light.b"),
      List(
        (
          Some(Predicate.Cmp("attr:mode", Op.Eq, Json.fromString("bright"))),
          "bright",
          Map("state" -> SlotSource())
        ),
        (None, "dim", Map("state" -> SlotSource()))
      )
    )
  )

  /** A case binding an entity the group's query does not match. */

  private def crossDash = Dashboard(
    cards = Map(
      "dot" -> CardDef(
        "<span>{{state}}/{{extra}}</span>",
        slots = List("state", "extra")
      )
    ),
    card = onSet(
      List("light.a", "light.b"),
      List(
        (
          None,
          "dot",
          Map(
            "state" -> SlotSource(),
            "extra" -> SlotSource(Some("sensor.outside"))
          )
        )
      )
    )
  )

  /** The arriving card binds nothing live, so there is no reverse-index edge to
    * find it by.
    */

  private def literalCaseDash = Dashboard(
    cards = Map(
      "live" -> CardDef("<b>{{state}}</b>", slots = List("state")),
      "plain" -> CardDef("<i>{{label}}</i>", slots = List("label"))
    ),
    card = onSet(
      List("light.a", "light.b"),
      List(
        (
          Some(Predicate.Cmp("attr:mode", Op.Eq, Json.fromString("bright"))),
          "live",
          Map("state" -> SlotSource())
        ),
        (None, "plain", Map("label" -> SlotSource(literal = Some("off-duty"))))
      )
    )
  )

  test(
    "a member that switches CASE is re-materialised, not left on the old one"
  ) {
    // A member's node is state-derived, so crossing a case boundary must
    // replace it. Getting this wrong is silent: the card renders from the wrong
    // branch for as long as the entity stays a member.
    viewing(caseDash, Map("light.a" -> mode("light.a", "bright"))) { v =>
      step(v, mode("light.a", "dim")).map { patches =>
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("<i>on</i>"), clue = p)
        assert(!p.contains("<b>"), clue = p)
      }
    }
  }

  test("a member ticks on a SECOND entity it binds, not only on its own") {
    // A member is in the reverse index like any node, so an entity its case
    // binds names it even when the group's query does not match that entity.
    viewing(
      crossDash,
      Map(
        "light.a" -> on("light.a"),
        "sensor.outside" -> st("sensor.outside", "12.0")
      )
    ) { v =>
      step(v, st("sensor.outside", "13.1")).map { patches =>
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("""id="c_light_a""""), clue = p)
        assert(p.contains("on/13.1"), clue = p)
      }
    }
  }

  test("a case switch to a card binding NOTHING is still recorded") {
    // The arriving card contributes no entity edge, so the member's id is the
    // handle: `syncMembers` reports what it replaced and `record` touches that.
    viewing(literalCaseDash, Map("light.a" -> mode("light.a", "bright"))) { v =>
      step(v, mode("light.a", "dim")).map { patches =>
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("<i>off-duty</i>"), clue = p)
        assert(!p.contains("<b>"), clue = p)
      }
    }
  }

  private val lights =
    List("light.a", "light.b", "light.c", "light.d", "light.z")

  private def lit(ids: String*) =
    lights.map(id => id -> (if (ids.contains(id)) on(id) else off(id))).toMap

  test("membership change on a not-yet-logged group falls back to a fill") {
    // With an empty log the group is not established, so it fills to set a
    // base.
    viewing(dynDash, lit("light.a", "light.b", "light.c", "light.d")) { v =>
      for {
        patches <- step(v, off("light.b"))
        held <- v.session.state.map(_.holds)
      } yield {
        assertEquals(patches.size, 1, clue = patches)
        assert(patches.head.contains("mode inner"), clue = patches)
        assert(patches.head.contains("selector #c"), clue = patches)
        // Established by its members' entries, and by no entry of its own.
        assert(held.contains("c_light_a"), clue = held)
        assert(!held.contains("c"), clue = held)
      }
    }
  }

  test("removing 1 of 2 members is a DELTA, not a fill") {
    // A `remove` carries no HTML, where a fill re-renders the survivor and
    // raises the host's horizon, costing every client below that cursor its
    // delta path. A fill happens only where it costs nothing (all arrived or
    // all left) or where there is no baseline.
    viewing(dynDash, lit("light.a", "light.b")) { v =>
      for {
        // Establishes the host, and puts it back at two members.
        _ <- step(v, on("light.c"))
        _ <- step(v, off("light.c"))
        patches <- step(v, off("light.b"))
        held <- v.session.state.map(_.holds)
      } yield {
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode remove"), clue = p)
        assert(p.contains("selector #c_light_b"), clue = p)
        assert(!p.contains("data: elements"), clue = p)
        assert(!held.contains("c"), clue = held)
      }
    }
  }

  test("the LAST member leaving fills, because the fill carries nothing") {
    // Everything left, so there is no survivor for a fill to re-send, and an
    // empty `inner` leaves the host unambiguously empty.
    viewing(dynDash, lit("light.a")) { v =>
      for {
        // Establishes the host, and puts it back at one member.
        _ <- step(v, on("light.b"))
        _ <- step(v, off("light.b"))
        patches <- step(v, off("light.a"))
        held <- v.session.state.map(_.holds)
      } yield {
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode inner"), clue = p)
        assert(p.contains("selector #c"), clue = p)
        assert(!p.contains("""id="c_light_a""""), clue = p)
        assert(!held.contains("c_light_a"), clue = held)
      }
    }
  }

  /** '''A client is never sent a surface it is not viewing.''' Each viewer
    * renders against its own open set. The second direction stops this passing
    * if nobody were sent anything.
    */
  test(
    "a tab nobody is viewing is not pushed to them; the viewer still gets it"
  ) {
    live(
      tabsDash,
      Map(
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        onT0 <- ts.viewer()
        onT1 <- ts.viewer("?v.c.tab=1")
        _ <- ts.record(FixtureEntity("sensor.b", "B1"))
        forA <- onT0.pull.map(_.map(_.render))
        forB <- onT1.pull.map(_.map(_.render))
      } yield {
        assert(forB.exists(_.contains("""id="s_c_t1__c"""")), clue = forB)
        assert(forB.exists(_.contains("B1")), clue = forB)
        assert(!forA.exists(_.contains("s_c_t1__c")), clue = forA)
      }
    }
  }

  /** The member card reads an attribute, so the member ticks without leaving
    * the set.
    */
  private def surfaceDynDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "dot" -> CardDef(
        "<span>{{state}}/{{level}}</span>",
        slots = List("state", "level")
      )
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      "det" -> Surface(
        onSet(
          List("light.a", "light.b"),
          List(
            (
              None,
              "dot",
              Map(
                "state" -> SlotSource(),
                "level" -> SlotSource(transform =
                  "'level' in attr ? attr['level'] : ''"
                )
              )
            )
          )
        )
      )
    )
  )

  test("a set inside an open surface gets the same per-member treatment") {
    live(
      surfaceDynDash,
      Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    ) { ts =>
      for {
        v <- ts.viewer(s"?ui.${Dashboard.PopupHostId}=det")
        batch <- v.change(
          st("light.b", "on", "level" -> Json.fromString("2"))
        )
      } yield {
        val patches = elementPatches(batch)
        assertEquals(patches.size, 1, clue = patches)
        // One child morph, not the whole surface group.
        assertEquals(
          batch.head.elements,
          Some(
            """<div class="fh-cell" id="s_det__c_light_b"><span>on/2</span></div>"""
          )
        )
      }
    }
  }

  /** A set nested inside a member, inside a surface: a tile per room, on a tab.
    * Only the outer set is in the static index, so a `root` read from it
    * answered `""`, the main page, and every inner member's patch reached every
    * client. `MemberGraphSuite` pins `Member.root`; this pins the property.
    */
  private def nestedSurfaceDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      "det" -> Surface(
        LayoutNode.SetNode(
          candidates = List("area.stue"),
          members = Map(
            "area.stue" -> LayoutNode.SetMember(
              List(
                LayoutNode.SetClause(
                  None,
                  LayoutNode.Component(
                    "col",
                    regions = LayoutNode.kids(
                      onSet(
                        List("light.a", "light.b"),
                        List((None, "dot", Map("state" -> SlotSource())))
                      )
                    )
                  )
                )
              )
            )
          )
        )
      ),
      "other" -> Surface(LayoutNode.Component("col"))
    )
  )

  test("a member of a set nested in a surface never reaches a closed tab") {
    live(
      nestedSurfaceDash,
      Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    ) { ts =>
      for {
        watching <- ts.viewer(s"?ui.${Dashboard.PopupHostId}=det")
        elsewhere <- ts.viewer(s"?ui.${Dashboard.PopupHostId}=other")
        // Establish the inner host, so the last frame takes the per-member
        // delta: the path that must get `root` right per member.
        _ <- step(watching, off("light.b"))
        _ <- step(watching, on("light.b"))
        seen <- watching.change(off("light.b")).map(_.map(_.render))
        // One pull over every frame: the open set is the only difference.
        unseen <- elsewhere.pull.map(_.map(_.render))
      } yield {
        // Without this half the assertion below passes if nobody got anything.
        assert(
          seen.exists(_.contains("s_det__c_area_stue_0_0_light_b")),
          clue = seen
        )
        assert(!unseen.exists(_.contains("s_det__c_area_stue")), clue = unseen)
      }
    }
  }

}
