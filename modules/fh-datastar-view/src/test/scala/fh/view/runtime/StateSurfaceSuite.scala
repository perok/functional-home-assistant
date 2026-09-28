package fh.view.runtime

import fh.view.query.QuerySnapshot

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fh.view.model.{Dashboard, LayoutNode, SlotSource, Surface}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import io.circe.Json

import scala.concurrent.duration.*

/** State-activated surfaces on the recording pass (ADR 0007): hidden-branch
  * silence, flips and their prune, nested groups, popup containment. Mostly a
  * negative property, so absence is asserted as carefully as what is sent.
  */
class StateSurfaceSuite extends ServerHarness {

  test("state surfaces: churn in the INACTIVE branch emits ZERO patches") {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0"),
          "sensor.z" -> es("sensor.z", "Z0")
        )
      )
      // Its member surface is never in the active set, so its index is never
      // consulted: structural silence, not a filtered render.
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      _ <- h.step(es("sensor.z", "Z1")).assertEquals(Nil)
      live <- h.step(es("sensor.a", "A1"))
    } yield {
      assertEquals(live.size, 1, clue = live)
      assert(live.head.contains("""id="s_then__c""""), clue = live)
      assert(live.head.contains("A1"), clue = live)
    }
  }

  test(
    "state flip: ONE overwrite of the host, at CURRENT state"
  ) {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      _ <- h.step(es("sensor.a", "A1")).map(p => assertEquals(p.size, 1))
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      // The host takes at most one member, so overwriting it is the delta, and
      // it lands the same whatever the client holds.
      flip <- h.step(es("alarm.h", "disarmed"))
      cache <- h.cacheNow
      moved <- h.mutationsNow
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

  /** '''A fill claims what it put in each node.''' A fill that claimed nothing
    * left the arriving branch unknown, so the next tick re-sent bytes the
    * client had just been handed. Asserted on the wire, however the claim is
    * represented.
    */
  test("a flip's fill claims its nodes, so an unchanged one is not re-sent") {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      flip <- h.step(es("alarm.h", "disarmed"))
      _ = assert(flip.exists(_.contains("B0")), clue = flip)
      // The node becomes a candidate while its bytes stay: the card reads
      // `state` and this moved an attribute.
      quiet <- h.step(
        EntityState("sensor.b", "B0", Map("noise" -> Json.fromInt(1)))
      )
    } yield assertEquals(quiet, Nil, clue = quiet)
  }

  /** '''A flip while a client is away must survive the reconnect''' (ADR 0011).
    * `Patches.resume` looked members up by position in `memberEntities`, empty
    * for a state group, so a `Placed` carrying a `Surface` was dropped: the
    * branch vanished and nothing put one back.
    */

  test("a flip across a disconnect replays as the same single overwrite") {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "A0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      _ <- h.step(es("sensor.a", "A1"))
      logId <- h.logId
      cursor = Some(Server.Cursor(h.headHash, h.styleHash, logId, 2L))
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      opening <- h.opening(cursor)
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

  test(
    "flip prune: a re-revealed child diffs cleanly (no stale-cache suppression)"
  ) {
    for {
      h <- SharedHarness.create(
        ifDash(),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "sensor.a" -> es("sensor.a", "boot"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      _ <- h.step(es("sensor.a", "on")).map(p => assertEquals(p.size, 1))
      // 2. Flip away (prunes s_then__*), 3. churn the hidden branch to "off",
      // the stale-entry trap, 4. flip back, rendered from current state.
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      _ <- h.step(es("sensor.a", "off")).assertEquals(Nil)
      back <- h.step(es("alarm.h", "armed"))
      _ = assertEquals(back.size, 1, clue = back)
      _ = assert(back.head.contains("off"), clue = back)
      // 5. Byte-identical to the step-1 entry: without the prune this would be
      // suppressed while the DOM shows "off".
      reveal <- h.step(es("sensor.a", "on"))
    } yield {
      assertEquals(reveal.size, 1, clue = reveal)
      assert(reveal.head.contains("""id="s_then__c""""), clue = reveal)
      assert(reveal.head.contains("on"), clue = reveal)
    }
  }

  test("a candidate set inside an INACTIVE branch stays silent") {
    val dyn = onSet(
      List("light.x", "light.y", "light.z"),
      List((None, "dot", Map("state" -> SlotSource())))
    )
    for {
      h <- SharedHarness.create(
        ifDash(thenContent = dyn),
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "light.x" -> es("light.x", "on"),
          "light.y" -> es("light.y", "on"),
          "light.z" -> es("light.z", "on"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      tick <- h.step(es("light.x", "on2"))
      // "on2" fails the query: a membership change.
      _ = assert(tick.nonEmpty, clue = tick)
      _ = assert(tick.forall(_.contains("s_then__c")), clue = tick)
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // Now in a hidden branch, query-affecting churn emits nothing.
      _ <- h.step(es("light.y", "off")).assertEquals(Nil)
      _ <- h.step(es("light.y", "on")).assertEquals(Nil)
    } yield ()
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
    for {
      h <- SharedHarness.create(
        d,
        Map(
          "alarm.h" -> es("alarm.h", "armed"),
          "mode.h" -> es("mode.h", "night"),
          "sensor.x" -> es("sensor.x", "X0"),
          "sensor.y" -> es("sensor.y", "Y0"),
          "sensor.b" -> es("sensor.b", "B0")
        )
      )
      // Outer active, the inner flip patches only the inner host.
      innerFlip <- h.step(es("mode.h", "day"))
      _ = assertEquals(innerFlip.size, 1, clue = innerFlip)
      _ = assert(
        innerFlip.head.contains("selector #s_then__c_0_branch"),
        clue = innerFlip
      )
      _ = assert(
        innerFlip.head.contains("""id="s_in_else__c""""),
        clue = innerFlip
      )
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // The active-set recursion never descends into an unselected member.
      _ <- h.step(es("mode.h", "night")).assertEquals(Nil)
      _ <- h.step(es("sensor.y", "Y1")).assertEquals(Nil)
    } yield ()
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
    val after = Map(
      "alarm.h" -> es("alarm.h", "disarmed"),
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B0")
    )
    val change =
      StateChange(
        "alarm.h",
        Some(es("alarm.h", "armed")),
        es("alarm.h", "disarmed")
      )
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(d))
      )
      sessions <- Sessions.create
      // The patch path never calls HA; an unexpected registry call still
      // raises.
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          for {
            renderer <- ref.get.map(_.rendererOf.get)
            session <- Session.create("dashboard")
            _ <- session.open.set(Set("det"))
            _ <- sessions.register("conn", session)
            log <- Ref[IO].of(FragmentLog("test"))
            withPopup <- recordAndPull(
              server,
              sessions,
              store,
              renderer,
              log,
              List(change),
              open = Set("det")
            )
            // Pulled by a client without the popup open.
            without <- (log.get, store.current, RenderCache.create).flatMapN(
              (l, now, rc) =>
                Patches.resume(
                  renderer,
                  rc,
                  l,
                  Map.empty,
                  now.entities,
                  _ => IO.pure(QuerySnapshot.empty),
                  Map.empty,
                  0L
                )
            )
          } yield (without, events(withPopup))
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (without, evts) =>
        assert(
          !events(without).exists(
            _.render.contains("s_det__c_0_branch")
          ),
          clue = events(without).map(_.render)
        )
        val patches = evts.filterNot(isCursor)
        assertEquals(patches.size, 1, clue = patches)
        assertEquals(patches.head.selector, Some("#s_det__c_0_branch"))
        assert(
          patches.head.elements.exists(_.contains("""id="s_d_else__c"""")),
          clue = patches.head.render
        )
      }
  }

}
