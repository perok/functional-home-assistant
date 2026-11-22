package fh.view.runtime

import fh.view.model.Dashboard
import fh.view.testkit.{FixtureEntity, PklFixture}
import fh.view.testkit.TestIds.given
import fh.view.runtime.RendererTestOps.ixOf
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
        assert(shown.head.contains("""id="c_0_branch_0_0""""), clue = shown)
        assert(shown.head.contains("A1"), clue = shown)
      }
    }
  }

  test(
    "a case nobody shows costs nothing: its entity's churn renders and logs nothing"
  ) {
    def silent(dash: Dashboard, states: Map[String, EntityState]) =
      live(dash, states) { ts =>
        for {
          v <- ts.viewer()
          before <- ts.log.map(logged)
          quiet <- step(v, es("sensor.b", "B1"))
          after <- ts.log.map(logged)
          // The control: the shown case does answer its own entity.
          shown <- step(v, es("sensor.a", "A1"))
        } yield {
          // Never rendered: the page carries the shown case alone.
          assert(v.document.html.contains("A0"), clue = v.document.html)
          assert(!v.document.html.contains("B0"), clue = v.document.html)
          assertEquals(quiet, Nil, clue = quiet)
          assertEquals(after, before, clue = after)
          assertEquals(shown.size, 1, clue = shown)
        }
      }
    val states = Map(
      "alarm.h" -> es("alarm.h", "armed"),
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B0")
    )
    // An If branch, and a tab panel the viewer has not opened.
    silent(ifDash(), states) *> silent(tabsDash, states)
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
      } yield {
        assertEquals(flip.size, 1, clue = flip)
        val p = flip.head
        assert(p.contains("mode inner"), clue = p)
        // The If's root.
        assert(p.contains("selector #c_0\n"), clue = p)
        assert(p.contains("""id="c_0_branch_1""""), clue = p)
        // Current state: B1, which no client ever saw.
        assert(p.contains("B1"), clue = p)
        assert(!p.contains("A1"), clue = p)
        assert(!p.contains("mode remove"), clue = p)
        // The pick's host is logged as moved, and so is the arriving case's
        // root: a pull plans the host against what the viewer holds.
        val r = Renderer.create(ifDash())
        assert(cache.contains(r.ixOf("c_0")), clue = cache)
        assert(cache.contains(r.ixOf("c_0_branch_1")), clue = cache)
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

  test("a pick flipped A, B, A within one pull sends case A whole") {
    // The page holds A's subtree and the log says only that A arrived, so
    // nothing but a fill of the host can be sure it shows A as it is now.
    val boxed = """(c.column) { children { t.state("sensor.a") } }"""
    live(
      ifDash(thenContent = boxed),
      Map(
        "alarm.h" -> es("alarm.h", "armed"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v <- ts.viewer()
        _ <- ts.record(FixtureEntity("alarm.h", "disarmed"))
        // Hidden, so nothing logs it: only a fill carries it.
        _ <- ts.record(FixtureEntity("sensor.a", "A1"))
        _ <- ts.record(FixtureEntity("alarm.h", "armed"))
        patches <- v.pull.map(elementPatches)
      } yield {
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode inner"), clue = p)
        assert(p.contains("selector #c_0\n"), clue = p)
        assert(p.contains("""id="c_0_branch_0""""), clue = p)
        assert(p.contains("A1"), clue = p)
      }
    }
  }

  test("a match over a state's text switches its case for every viewer") {
    val dash = PklFixture.snippet(
      """card = (c.column) {
        |  children {
        |    groupMod.group("div", "", nodeMod.match(
        |      (simpleMod.state()) { entityId = "input_select.m" },
        |      new Mapping { ["heat"] = t.state("sensor.a"); ["cool"] = t.state("sensor.b") }
        |    ))
        |  }
        |}
        |""".stripMargin
    )
    live(
      dash,
      Map(
        "input_select.m" -> es("input_select.m", "heat"),
        "sensor.a" -> es("sensor.a", "A0"),
        "sensor.b" -> es("sensor.b", "B0")
      )
    ) { ts =>
      for {
        v1 <- ts.viewer()
        v2 <- ts.viewer()
        _ = List(v1, v2).foreach { v =>
          assert(v.document.html.contains("A0"), clue = v.document.html)
          assert(!v.document.html.contains("B0"), clue = v.document.html)
        }
        p1 <- step(v1, es("input_select.m", "cool"))
        // One change to the store; the second viewer pulls the same version.
        p2 <- v2.pull.map(elementPatches)
      } yield List(p1, p2).foreach { p =>
        assertEquals(p.size, 1, clue = p)
        assert(p.head.contains("B0"), clue = p)
        assert(!p.head.contains("A0"), clue = p)
      }
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
        assert(opening.contains("selector #c_0\n"), clue = opening)
        assert(opening.contains("""id="c_0_branch_1""""), clue = opening)
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
        // 2. Flip away (prunes s_c_0_t0__*), 3. churn the hidden branch to "off",
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
        assert(reveal.head.contains("""id="c_0_branch_0_0""""), clue = reveal)
        assert(reveal.head.contains("on"), clue = reveal)
      }
    }
  }

  test("a candidate set inside an INACTIVE branch stays silent") {
    val lights = List("light.x", "light.y", "light.z")
    val dyn =
      lights
        .map(l => s"dump.entities.${l.replace('.', '_')}")
        .mkString("q.from(List(", ", ", "))") +
        """.where(q.eq(q.stateProp, "on")).render((e) -> t.dot(e.entity_id)).build()"""
    live(
      ifDash(thenContent = dyn, extras = lights.map(FixtureEntity(_, "on"))),
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
        _ = assert(tick.forall(_.contains("c_0_branch_0")), clue = tick)
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
    // The inner If is the outer branch's row's one node, "c_0_branch_0_0"; its
    // cases nest a level deeper.
    val inner =
      """c.iff(q.entity(dump.entities.mode_h).stateIs("night"))""" +
        """.then(t.state("sensor.x")).`else`(t.state("sensor.y"))"""
    val d = ifDash(
      thenContent = inner,
      extras = List(FixtureEntity("mode.h", "night"))
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
          innerFlip.head.contains("selector #c_0_branch_0_0\n"),
          clue = innerFlip
        )
        _ = assert(
          innerFlip.head.contains("""id="c_0_branch_0_0_branch_1""""),
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
    val d = PklFixture.snippet(
      s"""card = new c.Column {}
         |surfaces { ["det"] { body { ${ifSnippet()} } } }
         |""".stripMargin,
      alarm
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
          !closed.exists(_.render.contains("selector #s_det__c_0\n")),
          clue = closed.map(_.render)
        )
        val patches = elementPatches(opened)
        assertEquals(patches.size, 1, clue = patches)
        assert(patches.head.contains("selector #s_det__c_0\n"), patches)
        assert(
          patches.head.contains("""id="s_det__c_0_branch_1""""),
          clue = patches
        )
      }
    }
  }

}
