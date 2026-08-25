package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}

import scala.concurrent.duration.*

/** State-activated surfaces on the recording pass (ADR 0007): hidden-branch
  * silence, flips with their cache prune, nested groups, popup containment.
  *
  * The property most of these are about is a NEGATIVE one — an inactive branch
  * emits nothing — so they assert on what is absent as carefully as on what is
  * sent.
  */
class StateSurfaceSuite extends ServerHarness {

  // ---------------------------------------------------------------------------
  // State-activated surfaces on the SHARED pass: hidden-branch silence, flips
  // with cache prune, nested groups, popup containment (the feature contract)
  // ---------------------------------------------------------------------------

  /** An If/else dashboard: `ifhost` at "c_0" (col -> ifhost); `then` shows
    * sensor.a while alarm.h == armed, the always-true `else` shows sensor.b.
    */

  /** Drives one VIEWER over an EVOLVING store: each [[step]] applies one entity
    * update (deriving the StateChange exactly like the WS ingest does), records
    * the frame for the slug, and returns what this viewer's pull emits for it.
    *
    * `holds` and `position` accumulate across steps, which is what multi-step
    * contracts (flip then re-reveal) need and what the shared log used to do on
    * everyone's behalf.
    */

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
      // then (sensor.a) is active; the ELSE branch's entity churns silently —
      // its member surface is never in the active set, so its index is never
      // consulted (structural silence, not a filtered render).
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      // An entity no branch binds and no condition reads: nothing at all (the
      // O(1) shortcut path — no member condition match flipped for it).
      _ <- h.step(es("sensor.z", "Z1")).assertEquals(Nil)
      // The ACTIVE branch's entity, by contrast, patches its surface-scoped node.
      live <- h.step(es("sensor.a", "A1"))
    } yield {
      assertEquals(live.size, 1, clue = live)
      assert(live.head.contains("""id="s_then__c""""), clue = live)
      assert(live.head.contains("A1"), clue = live)
    }
  }

  test(
    "state flip: ONE overwrite of the host's mount, at CURRENT state"
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
      // Establish the active branch in the shared cache...
      _ <- h.step(es("sensor.a", "A1")).map(p => assertEquals(p.size, 1))
      // ...and churn the hidden branch (never rendered, never patched).
      _ <- h.step(es("sensor.b", "B1")).assertEquals(Nil)
      // The flip: ONE patch. The mount takes at most one member, so overwriting
      // it IS the delta — no siblings to preserve, no position to fix — and it
      // lands the same whatever the client currently holds there. Not a morph of
      // the host, whose HTML would have embedded the branch.
      flip <- h.step(es("alarm.h", "disarmed"))
      cache <- h.cacheNow
      moved <- h.mutationsNow
    } yield {
      assertEquals(flip.size, 1, clue = flip)
      val p = flip.head
      assert(p.contains("mode inner"), clue = p)
      // The MOUNT — `Surface.hostId`, the id the If's mount template carries.
      assert(p.contains("selector #c_0_branch"), clue = p)
      assert(p.contains("""id="s_else__c""""), clue = p)
      // Rendered against CURRENT state: B1, which no client ever saw.
      assert(p.contains("B1"), clue = p)
      assert(!p.contains("A1"), clue = p)
      assert(!p.contains("mode remove"), clue = p)
      // The prune keeps its original job (hidden-branch churn leaves entries
      // stale), and the new branch's ROOT is recorded as the mount's occupant —
      // structure, not content, which is why it is a Mutation and not a
      // fragment. No host-level entry of either kind.
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

  /** '''A flip that happens while a client is away must survive the
    * reconnect''' (docs/adr/0011-the-live-connection.md) — the exact hole
    * recording the flip structurally was meant to close.
    *
    * Found in the running app before this test existed: `Patches.resume`
    * grouped placements by container and looked each member up by POSITION in
    * `memberEntities`, which is empty for a state group — so a `Placed`
    * carrying a `Surface` member matched nothing and was dropped. The client
    * got the `Gone`, its branch vanished, and nothing ever put one back.
    * Silent, and permanent until an unrelated change moved something.
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
      // Establish the then-branch, then note where a client's cursor sits.
      _ <- h.step(es("sensor.a", "A1"))
      logId <- h.logId
      cursor = Some(Server.Cursor(h.headHash, h.styleHash, logId, 2L))
      // It flips while that client is away.
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      opening <- h.opening(cursor)
    } yield {
      // The new branch ARRIVES — without it the host is left empty, which is
      // exactly what the running app showed before this was fixed.
      assert(opening.contains("mode inner"), clue = opening)
      assert(opening.contains("selector #c_0_branch"), clue = opening)
      assert(opening.contains("""id="s_else__c""""), clue = opening)
      // Rendered from the CURRENT snapshot, and not via a body repaint.
      assert(opening.contains("B0"), clue = opening)
      assert(!opening.contains(BodyRepaint), clue = opening)
      // The overwrite subsumes the removal: a client that already applied the
      // flip and one that missed it both land on the same DOM, so there is no
      // paired remove to reason about.
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
      // 1. Cache the then-branch child at "on".
      _ <- h.step(es("sensor.a", "on")).map(p => assertEquals(p.size, 1))
      // 2. Flip away (prunes s_then__*), 3. churn the hidden branch to "off"
      // (silent — the stale-entry trap this test springs), 4. flip back (the
      // arriving branch is rendered from current state, so it shows "off").
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      _ <- h.step(es("sensor.a", "off")).assertEquals(Nil)
      back <- h.step(es("alarm.h", "armed"))
      _ = assertEquals(back.size, 1, clue = back)
      _ = assert(back.head.contains("off"), clue = back)
      // 5. The re-revealed child returns to "on" — HTML byte-identical to the
      // step-1 cache entry. Without the flip prune this would be suppressed as
      // "unchanged" while the DOM (showing "off") has moved on.
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
      // Active branch: the group's members get the usual per-entity treatment,
      // scoped under the member surface's id namespace.
      tick <- h.step(es("light.x", "on2"))
      // "on2" fails the query -> a membership change (remove) for the group.
      _ = assert(tick.nonEmpty, clue = tick)
      _ = assert(tick.forall(_.contains("s_then__c")), clue = tick)
      // Flip to else: one overwrite of the mount...
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // ...and now the group is in a hidden branch: query-affecting churn that
      // would previously re-render it emits NOTHING.
      _ <- h.step(es("light.y", "off")).assertEquals(Nil)
      _ <- h.step(es("light.y", "on")).assertEquals(Nil)
    } yield ()
  }

  test("nested state groups: inner flips patch only inside the ACTIVE branch") {
    // Outer If ("c_0"): then-branch content is col(ifhost) — the INNER host
    // lives at the member's content path s_then__c_0; its members nest one
    // level deeper. Inner condition: mode.h == night.
    val innerHost =
      LayoutNode.Component(
        "col",
        children = List(LayoutNode.Component("ifhost"))
      )
    val d = Dashboard(
      cards = ifCards,
      card = LayoutNode
        .Component("col", children = List(LayoutNode.Component("ifhost"))),
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
      // Outer active: the inner flip patches ONLY the inner host's mount
      // (recursion into the active member's index found it), with its else branch.
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
      // Flip the OUTER group away...
      _ <- h.step(es("alarm.h", "disarmed")).map(p => assertEquals(p.size, 1))
      // ...then the inner group's condition flips inside the hidden branch:
      // unreachable DOM, zero patches (the active-set recursion never descends
      // into an unselected member).
      _ <- h.step(es("mode.h", "night")).assertEquals(Nil)
      // Liveness inside the hidden branch's active member is silent too.
      _ <- h.step(es("sensor.y", "Y1")).assertEquals(Nil)
    } yield ()
  }

  test(
    "a state group inside an open popup is rendered SHARED, tagged with it"
  ) {
    // The If roots inside popup "det" (owner s_det__c_0). Its flip is a pure
    // function of entity state — identical for every client that can see it —
    // so it is rendered ONCE for the slug and addressed to "det", not
    // re-rendered per connection. The popup being in SOMEONE's open set is what
    // makes it worth rendering at all.
    val d = Dashboard(
      cards = ifCards,
      card = LayoutNode.Component("col"),
      surfaces = Map(
        "det" -> Surface(
          LayoutNode
            .Component("col", children = List(LayoutNode.Component("ifhost")))
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
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
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
            // The same frame, pulled by a client that does NOT have the popup
            // open — the other half of what the surface tag used to assert.
            without <- (log.get, store.current, RenderCache.create).flatMapN(
              (l, now, rc) =>
                Patches.resume(renderer, rc, l, Map.empty, now.entities, 0L)
            )
          } yield (without, events(withPopup))
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (without, evts) =>
        assert(
          !events(without).exists(
            _.renderString.contains("s_det__c_0_branch")
          ),
          clue = events(without).map(_.renderString)
        )
        val patches = evts.filterNot(isCursor)
        assertEquals(patches.size, 1, clue = patches)
        assertEquals(patches.head.selector, Some("#s_det__c_0_branch"))
        assert(
          patches.head.elements.exists(_.contains("""id="s_d_else__c"""")),
          clue = patches.head.renderString
        )
      }
  }

}
