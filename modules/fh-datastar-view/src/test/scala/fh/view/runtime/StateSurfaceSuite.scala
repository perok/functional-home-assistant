package fh.view.runtime

import fh.view.model.{Dashboard, LayoutNode, SlotSource, Surface}
import fh.view.testkit.FixtureEntity
import fh.view.testkit.TestIds.given
import io.circe.Json

/** State-activated surfaces on the recording pass (ADR 0007): hidden-branch
  * silence, flips and their prune, nested groups, popup containment. Mostly a
  * negative property, so absence is asserted as carefully as what is sent.
  */
class StateSurfaceSuite extends ServerHarness {

  private def step(v: TestServer.Viewer, next: EntityState) =
    v.change(next).map(elementPatches)

  test("state surfaces: churn in the INACTIVE branch emits ZERO patches") {
    live(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        // Its member surface is never in the active set, so its index is never
        // consulted: structural silence, not a filtered render.
        _ <- step(v, es("sensor.b", "B1")).assertEquals(Nil)
        shown <- step(v, es("sensor.a", "A1"))
      } yield {
        assertEquals(shown.size, 1, clue = shown)
        assert(shown.head.contains("""id="s_then__c""""), clue = shown)
        assert(shown.head.contains("A1"), clue = shown)
      }
    }
  }

  test(
    "state flip: ONE overwrite of the host, at CURRENT state"
  ) {
    live(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        _ <- step(v, es("sensor.a", "A1")).map(p => assertEquals(p.size, 1))
        _ <- step(v, es("sensor.b", "B1")).assertEquals(Nil)
        // The host takes at most one member, so overwriting it is the delta, and
        // it lands the same whatever the client holds.
        flip <- step(v, es("alarm.h", "disarmed"))
        cache <- ts.log.map(logged)
        moved <- ts.log.map(_.mutations)
      } yield {
        assertEquals(flip.size, 1, clue = flip)
        val p = flip.head
        assert(p.contains("mode inner"), clue = p)
        // `Surface.hostId`, the id the If's template puts on it.
        assert(p.contains("selector #c_0_branch"), clue = p)
        assert(p.contains("""id="s_else__c""""), clue = p)
        // Current state: B1, which no client ever saw.
        assert(p.contains("B1"), clue = p)
        assert(!p.contains("A1"), clue = p)
        assert(!p.contains("mode remove"), clue = p)
        // The prune still clears hidden-branch staleness, and the new branch's
        // root is recorded as the host's occupant: a Mutation, since it is
        // structure.
        assert(!cache.keys.exists(_.startsWith("s_then__")), clue = cache)
        assert(
          moved.get("s_else__c").exists {
            case _: Mutation.Placed => true
            case _                  => false
          },
          clue = moved
        )
        assert(!cache.contains("c_0"), clue = cache)
        assert(!moved.contains("c_0"), clue = moved)
      }
    }
  }

  /** '''A fill claims what it put in each node.''' A fill that claimed nothing
    * left the arriving branch unknown, so the next tick re-sent bytes the
    * client had just been handed. Asserted on the wire, however the claim is
    * represented.
    */
  test("a flip's fill claims its nodes, so an unchanged one is not re-sent") {
    live(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        flip <- step(v, es("alarm.h", "disarmed"))
        _ = assert(flip.exists(_.contains("B0")), clue = flip)
        // The node becomes a candidate while its bytes stay: the card reads
        // `state` and this moved an attribute.
        quiet <- step(
          v,
          EntityState("sensor.b", "B0", Map("noise" -> Json.fromInt(1)))
        )
      } yield assertEquals(quiet, Nil, clue = quiet)
    }
  }

  /** '''A flip while a client is away must survive the reconnect''' (ADR 0011).
    * `Patches.resume` looked members up by position in `memberEntities`, empty
    * for a state group, so a `Placed` carrying a `Surface` was dropped: the
    * branch vanished and nothing put one back.
    */

  test("a flip across a disconnect replays as the same single overwrite") {
    live(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        _ <- step(v, es("sensor.a", "A1"))
        _ <- step(v, es("alarm.h", "disarmed")).map(p =>
          assertEquals(p.size, 1)
        )
        opening <- v.cursor.flatMap(c => ts.reconnect(Some(c)))
      } yield {
        // Without it the host is left empty.
        assert(opening.contains("mode inner"), clue = opening)
        assert(opening.contains("selector #c_0_branch"), clue = opening)
        assert(opening.contains("""id="s_else__c""""), clue = opening)
        assert(opening.contains("B0"), clue = opening)
        assert(!opening.contains(BodyRepaint), clue = opening)
        // A client that applied the flip and one that missed it land on the same
        // DOM.
        assert(!opening.contains("mode remove"), clue = opening)
      }
    }
  }

  test(
    "flip prune: a re-revealed child diffs cleanly (no stale-cache suppression)"
  ) {
    live(
      ifDash(),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "boot"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        _ <- step(v, es("sensor.a", "on")).map(p => assertEquals(p.size, 1))
        // 2. Flip away (prunes s_then__*), 3. churn the hidden branch to "off",
        // the stale-entry trap, 4. flip back, rendered from current state.
        _ <- step(v, es("alarm.h", "disarmed")).map(p =>
          assertEquals(p.size, 1)
        )
        _ <- step(v, es("sensor.a", "off")).assertEquals(Nil)
        back <- step(v, es("alarm.h", "armed"))
        _ = assertEquals(back.size, 1, clue = back)
        _ = assert(back.head.contains("off"), clue = back)
        // 5. Byte-identical to the step-1 entry: without the prune this would be
        // suppressed while the DOM shows "off".
        reveal <- step(v, es("sensor.a", "on"))
      } yield {
        assertEquals(reveal.size, 1, clue = reveal)
        assert(reveal.head.contains("""id="s_then__c""""), clue = reveal)
        assert(reveal.head.contains("on"), clue = reveal)
      }
    }
  }

  test("a candidate set inside an INACTIVE branch stays silent") {
    val dyn = onSet(
      List("light.x", "light.y", "light.z"),
      List((None, "dot", Map("state" -> SlotSource())))
    )
    live(
      ifDash(thenContent = dyn),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "light.x" -> es("light.x", "on"),
        "light.y" -> es("light.y", "on"),
        "light.z" -> es("light.z", "on"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        tick <- step(v, es("light.x", "on2"))
        // "on2" fails the query: a membership change.
        _ = assert(tick.nonEmpty, clue = tick)
        _ = assert(tick.forall(_.contains("s_then__c")), clue = tick)
        _ <- step(v, es("alarm.h", "disarmed")).map(p =>
          assertEquals(p.size, 1)
        )
        // Now in a hidden branch, query-affecting churn emits nothing.
        _ <- step(v, es("light.y", "off")).assertEquals(Nil)
        _ <- step(v, es("light.y", "on")).assertEquals(Nil)
      } yield ()
    }
  }

  test("nested state groups: inner flips patch only inside the ACTIVE branch") {
    // The inner host lives at the member's content path s_then__c_0; its
    // members nest a level deeper.
    val innerHost =
      LayoutNode.Component(
        "col",
        regions = LayoutNode.kids(LayoutNode.Component("ifhost"))
      )
    val d = Dashboard(
      cards = ifCards,
      card = LayoutNode
        .Component(
          "col",
          regions = LayoutNode.kids(LayoutNode.Component("ifhost"))
        ),
      surfaces = Map(
        "then" -> stateMember(innerHost, "c_0", 0, armedCond),
        "else" -> stateMember(branchCard("sensor.b"), "c_0", 1, always),
        "in_then" -> stateMember(
          branchCard("sensor.x"),
          "s_then__c_0",
          0,
          entityIs("mode.h", "night")
        ),
        "in_else" -> stateMember(
          branchCard("sensor.y"),
          "s_then__c_0",
          1,
          always
        )
      )
    )
    live(
      d,
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "mode.h" -> es("mode.h", "night"),
        "sensor.x" -> es("sensor.x", "X0"),
        "sensor.y" -> es("sensor.y", "Y0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        // Outer active, the inner flip patches only the inner host.
        innerFlip <- step(v, es("mode.h", "day"))
        _ = assertEquals(innerFlip.size, 1, clue = innerFlip)
        _ = assert(
          innerFlip.head.contains("selector #s_then__c_0_branch"),
          clue = innerFlip
        )
        _ = assert(
          innerFlip.head.contains("""id="s_in_else__c""""),
          clue = innerFlip
        )
        _ <- step(v, es("alarm.h", "disarmed")).map(p =>
          assertEquals(p.size, 1)
        )
        // The active-set recursion never descends into an unselected member.
        _ <- step(v, es("mode.h", "night")).assertEquals(Nil)
        _ <- step(v, es("sensor.y", "Y1")).assertEquals(Nil)
      } yield ()
    }
  }

  test(
    "a state group inside an open popup is rendered SHARED, tagged with it"
  ) {
    // A flip is a function of entity state, the same for every client that can
    // see it, so it is rendered once for the slug and addressed to "det".
    // Someone having the popup open is what makes it worth rendering.
    val d = Dashboard(
      cards = ifCards,
      card = LayoutNode.Component("col"),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode
            .Component(
              "col",
              regions = LayoutNode.kids(LayoutNode.Component("ifhost"))
            )
        ),
        "d_then" -> stateMember(
          branchCard("sensor.a"),
          "s_det__c_0",
          0,
          armedCond
        ),
        "d_else" -> stateMember(branchCard("sensor.b"), "s_det__c_0", 1, always)
      )
    )
    live(
      d,
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        withPopup <- ts.viewer(s"?ui.${Dashboard.PopupHostId}=det")
        without <- ts.viewer()
        _ <- ts.record(FixtureEntity("alarm.h", "disarmed"))
        opened <- withPopup.pull
        closed <- without.pull
      } yield {
        assert(
          !closed.exists(_.render.contains("s_det__c_0_branch")),
          clue = closed.map(_.render)
        )
        val patches = elementPatches(opened)
        assertEquals(patches.size, 1, clue = patches)
        assert(patches.head.contains("selector #s_det__c_0_branch"), patches)
        assert(patches.head.contains("""id="s_d_else__c""""), clue = patches)
      }
    }
  }

}
