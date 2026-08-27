package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import io.circe.Json

import scala.concurrent.duration.*

/** Candidate sets on the recording pass (ADR 0003): a member ticking, arriving,
  * leaving, switching case, and the churn heuristic that decides between a
  * per-member delta and a whole-mount fill.
  */
class SetMembershipSuite extends ServerHarness {

  // ---------------------------------------------------------------------------
  // Per-entity candidate-set patches (Tier 1 in-place + Tier 2 add/remove)
  // ---------------------------------------------------------------------------

  /** Two cases over ONE membership: an entity that stays a member while the
    * case it dispatches to changes (`attr:mode`), which is the only way a
    * member's node definition moves without its membership moving.
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

  /** A case binding a SECOND entity — one the group's query does not match.
    * Authorable all along; it just never ticked.
    */

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

  /** A case switch whose ARRIVING card binds nothing live — the shape that has
    * no reverse-index edge to be found by.
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

  /** Drive the shared per-slug diff for one change against `after` (the current
    * snapshot) with an optional pre-seeded cache; return the emitted SSE
    * patches (rendered to strings) and the resulting cache.
    */

  // Seeded through `set`, so the digest derivation is never duplicated here.
  /** A viewer already CURRENT on these nodes, and a log that has recorded them.
    *
    * One map for both halves because they were one thing before: a seeded log
    * entry meant "the group is established AND this node is not worth sending".
    * The value is the id's live rendering rather than the literal, since what
    * suppression compares is a digest of what the client actually holds.
    */

  /** The ELEMENT patches of a shared batch. Every non-empty batch also carries
    * the resume cursor as a `patch-signals` event
    * (docs/adr/0011-the-live-connection.md); these contracts are about what the
    * DOM receives, and one dedicated test below covers the cursor itself.
    */

  private def runShared(
      dash: Dashboard,
      after: Map[String, EntityState],
      change: StateChange,
      // What the viewer's DOM already holds, by node id -> HTML. It used to seed
      // the shared log; the suppression it drives is now this client's own.
      seedCache: Map[String, String] = Map.empty,
      ui: Map[String, String] = Map.empty
  ): IO[(List[String], Map[NodeId, Held])] =
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(dash))
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
            seed = seeded(renderer, after, seedCache.keys)
            log <- Ref[IO].of(seed._1)
            patches <- recordAndPull(
              server,
              sessions,
              store,
              renderer,
              log,
              List(change),
              ui = ui,
              holds = seed._2
            )
            // What the viewer holds AFTER applying what it was just sent —
            // where a seeded log entry used to be the baseline, this is.
          } yield (
            elementPatches(events(patches)),
            patches.foldLeft(seed._2)(Patches.applied)
          )
        }
    } yield out)
      .timeout(30.seconds)

  test("in-place member tick patches ONE child, not the whole group") {
    val after = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    // light.b ticks (a fresh EntityState, same "on" state) -> InPlace member.
    val change = StateChange("light.b", Some(on("light.b")), on("light.b"))
    runShared(dynDash, after, change).map { case (patches, _) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      // outer-morphs the child id (default mode, no mode line), not the group.
      assert(
        p.contains("""elements <div class="fh-cell" id="c_light_b">"""),
        clue = p
      )
      assert(!p.contains("id=\"c\""), clue = p)
      assert(!p.contains("mode "), clue = p)
    }
  }

  test(
    "a member that switches CASE is re-materialised, not left on the old one"
  ) {
    // The trap materialisation creates: a member's node is state-derived, so a
    // frame that moves the matched entity across a case boundary must REPLACE
    // the node, not merely mark it changed. Getting this wrong is silent — the
    // card renders happily, from the wrong branch, for as long as the entity
    // stays a member.
    val after =
      Map("light.a" -> st("light.a", "on", "mode" -> Json.fromString("dim")))
    val change = StateChange(
      "light.a",
      Some(st("light.a", "on", "mode" -> Json.fromString("bright"))),
      after("light.a")
    )
    runShared(caseDash, after, change).map { case (patches, _) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      assert(p.contains("<i>on</i>"), clue = p)
      assert(!p.contains("<b>"), clue = p)
    }
  }

  test("a member ticks on a SECOND entity it binds, not only on its own") {
    // A CORRECTION, and the one place this phase moves the wire. The only
    // selector for a member used to be its group's query, so a case slot naming
    // an entity the query does not match — authorable, and accounted for in the
    // old cache key — silently never re-rendered. A materialised member is in
    // the reverse index like any node, so the entity it binds names it.
    val after = Map(
      "light.a" -> on("light.a"),
      "sensor.outside" -> st("sensor.outside", "13.1")
    )
    val change = StateChange(
      "sensor.outside",
      Some(st("sensor.outside", "12.0")),
      after("sensor.outside")
    )
    runShared(crossDash, after, change).map { case (patches, _) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      assert(p.contains("""id="c_light_a""""), clue = p)
      assert(p.contains("on/13.1"), clue = p)
    }
  }

  test("a case switch to a card binding NOTHING is still recorded") {
    // The hole the reverse index cannot cover: the arriving card has no live
    // slot, so it contributes no entity edge and nothing would name it. The
    // member's ID is the sound handle — it exists whatever the card does — so
    // `syncMembers` reports what it replaced and `record` touches that.
    val after =
      Map("light.a" -> st("light.a", "on", "mode" -> Json.fromString("dim")))
    val change = StateChange(
      "light.a",
      Some(st("light.a", "on", "mode" -> Json.fromString("bright"))),
      after("light.a")
    )
    runShared(literalCaseDash, after, change).map { case (patches, _) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      assert(p.contains("<i>off-duty</i>"), clue = p)
      assert(!p.contains("<b>"), clue = p)
    }
  }

  /** A candidate set the log already knows: MEMBER entries, which is what
    * "established" means now that no container logs a fragment of its own.
    */

  private val establishedGroup = Map(
    "c_light_a" -> "<a>",
    "c_light_c" -> "<c>",
    "c_light_d" -> "<d>"
  )

  test("member add: per-entity insert BEFORE the DOM successor") {
    // a,c,d already on; b turns on -> Added, churn 1 of shown 3 -> per-entity.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(off("light.b")), on("light.b"))
    // A group is ESTABLISHED by having member entries — there is no group-level
    // fragment any more (it would be a fragment containing other nodes).
    // An arrival is remove-then-insert: the pair is idempotent whatever the
    // client's DOM holds, which is what lets an arrival and a re-order be the
    // same operation (see `Patches.resume`).
    runShared(dynDash, after, change, seedCache = establishedGroup).map {
      case (patches, cache) =>
        assertEquals(patches.size, 2, clue = patches)
        assert(patches.head.contains("mode remove"), clue = patches.head)
        val p = patches.last
        assert(p.contains("mode before"), clue = p)
        assert(
          p.contains("selector #c_light_c"),
          clue = p
        ) // first member after b
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_b">"""),
          clue = p
        )
        // the new child is logged; no node logs a fragment containing another.
        assert(cache.contains("c_light_b"), clue = cache)
        assert(!cache.contains("c"), clue = cache)
    }
  }

  test("member add of the last-sorting entity APPENDS into the group") {
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.z" -> on("light.z")
    )
    val change = StateChange("light.z", Some(off("light.z")), on("light.z"))
    runShared(dynDash, after, change, seedCache = establishedGroup).map {
      case (patches, _) =>
        assertEquals(patches.size, 2, clue = patches)
        val p = patches.last
        assert(p.contains("mode append"), clue = p)
        assert(p.contains("selector #c"), clue = p)
        assert(
          p.contains("""elements <div class="fh-cell" id="c_light_z">"""),
          clue = p
        )
    }
  }

  test("member remove: per-entity remove patch (no elements), child pruned") {
    // 4 on; b turns off -> Removed, churn 1 of shown 4 -> per-entity remove.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> off("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(
      dynDash,
      after,
      change,
      seedCache = Map("c" -> "<stale>", "c_light_b" -> "<old>")
    ).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      assert(p.contains("mode remove"), clue = p)
      assert(p.contains("selector #c_light_b"), clue = p)
      // remove carries no HTML payload (the event name still says "…elements").
      assert(!p.contains("data: elements"), clue = p)
      assert(!cache.contains("c_light_b"), clue = cache)
    }
  }

  test("removing 1 of 2 members is a DELTA, not a fill") {
    // This used to fill: churn was compared against a fraction of the group
    // (half), and 1 of 2 is not a minority. It was the wrong call at its own
    // motivating boundary — a `remove` carries NO HTML, where the fill it chose
    // instead re-rendered the surviving member for nothing, and raised the
    // mount's horizon so every client below that cursor lost its delta path
    // too. A fill now happens only where it costs nothing (everything arrived,
    // or everything left) or where there is no baseline to patch against.
    val after = Map("light.a" -> on("light.a"), "light.b" -> off("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(
      dynDash,
      after,
      change,
      seedCache = Map("c_light_a" -> "<a>", "c_light_b" -> "<b>")
    ).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      assert(p.contains("mode remove"), clue = p)
      assert(p.contains("selector #c_light_b"), clue = p)
      // The whole point: no HTML at all, where a fill would have re-sent the
      // survivor's markup.
      assert(!p.contains("data: elements"), clue = p)
      assert(!cache.contains("c_light_b"), clue = cache)
      assert(!cache.contains("c"), clue = cache)
    }
  }

  test("the LAST member leaving fills, because the fill carries nothing") {
    // The other side of the same rule. Everything left, so there is no
    // unchanged member for a fill to re-send: one empty `inner` beats one
    // `remove`, and it leaves the mount unambiguously empty.
    val after = Map("light.a" -> off("light.a"))
    val change = StateChange("light.a", Some(on("light.a")), off("light.a"))
    runShared(dynDash, after, change, seedCache = Map("c_light_a" -> "<a>"))
      .map { case (patches, cache) =>
        assertEquals(patches.size, 1, clue = patches)
        val p = patches.head
        assert(p.contains("mode inner"), clue = p)
        assert(p.contains("selector #c"), clue = p)
        assert(!p.contains("""id="c_light_a""""), clue = p)
        assert(!cache.contains("c_light_a"), clue = cache)
      }
  }

  test("membership change on a not-yet-logged group falls back to a fill") {
    // Same 1-of-4 remove that would be per-entity — but with an EMPTY log the
    // group isn't established, so we fill to establish a known base.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> off("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    runShared(dynDash, after, change).map { case (patches, cache) =>
      assertEquals(patches.size, 1, clue = patches)
      assert(patches.head.contains("mode inner"), clue = patches)
      assert(patches.head.contains("selector #c"), clue = patches)
      // Established by its MEMBERS' entries, so the next churn takes the delta
      // path — and by no entry of its own.
      assert(cache.contains("c_light_a"), clue = cache)
      assert(!cache.contains("c"), clue = cache)
    }
  }

  // A candidate set inside an open SURFACE (id "det"); its group id is
  // surface-namespaced `s_det__c`, children `s_det__c_<slug>`.
  private def surfaceDynDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component("col"),
    surfaces = Map(
      "det" -> Surface(
        onSet(
          List("light.a", "light.b"),
          List((None, "dot", Map("state" -> SlotSource())))
        )
      )
    )
  )

  /** '''A client is never sent a surface it is not viewing.'''
    *
    * Both directions are asserted deliberately. Without the second half this
    * would pass just as well if the server sent NOBODY anything.
    *
    * It is now a property of the PULL rather than of a tag: each viewer renders
    * against its own open set, so a tab nobody is viewing is not withheld from
    * them — it is never produced for them at all.
    */

  test(
    "a tab nobody is viewing is not pushed to them; the viewer still gets it"
  ) {
    val after = Map(
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B1")
    )
    // The change is inside tab 1's panel, which only B has open.
    val change =
      StateChange("sensor.b", Some(es("sensor.b", "B0")), es("sensor.b", "B1"))
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(tabsRenderer))
      sessions <- Sessions.create
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
            viewingT0 <- Session.create("dashboard")
            _ <- viewingT0.open.set(Set("c_t0"))
            _ <- sessions.register("a", viewingT0)
            viewingT1 <- Session.create("dashboard")
            _ <- viewingT1.open.set(Set("c_t1"))
            _ <- sessions.register("b", viewingT1)
            renderer <- ref.get.map(_.rendererOf.get)
            log <- Ref[IO].of(FragmentLog("test"))
            // Recorded ONCE for the slug; each viewer then pulls its own.
            forB <- recordAndPull(
              server,
              sessions,
              store,
              renderer,
              log,
              List(change),
              open = Set("c_t1"),
              ui = renderer.surfaces.uiStateFrom(Set("c_t1"))
            )
            forA <- (log.get, store.current, RenderCache.create).flatMapN(
              (l, now, rc) =>
                Patches.resume(
                  renderer,
                  rc,
                  l,
                  Map.empty,
                  now.entities,
                  0L,
                  Set("c_t0"),
                  renderer.surfaces.uiStateFrom(Set("c_t0"))
                )
            )
          } yield (forA, forB)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (forA, forB) =>
        val bytes = events(forB).map(_.renderString)
        assert(
          bytes.exists(_.contains("""id="s_c_t1__c"""")),
          clue = bytes
        )
        assert(bytes.exists(_.contains("B1")), clue = bytes)
        // A is looking at tab 0 and must not receive it; B, who IS looking at
        // it, must — the second half is what stops this passing vacuously.
        assert(
          !events(forA).map(_.renderString).exists(_.contains("s_c_t1__c")),
          clue = events(forA).map(_.renderString)
        )
      }
  }

  test("a set inside an open surface gets the same per-member treatment") {
    val after = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), on("light.b"))
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(surfaceDynDash))
      )
      sessions <- Sessions.create
      // Stub HA: the SSE/patch path never calls it (an unexpected registry call
      // still raises); the store is driven in-memory, so the empty seed is inert.
      fake <- FakeHomeAssistant.create(Nil)
      patches <- Server
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
            session <- Session.create("dashboard")
            _ <- session.open.set(Set("det"))
            _ <- sessions.register("conn", session)
            renderer <- ref.get.map(_.rendererOf.get)
            log <- Ref[IO].of(FragmentLog("test"))
            ps <- recordAndPull(
              server,
              sessions,
              store,
              renderer,
              log,
              List(change),
              open = Set("det")
            )
          } yield ps
        }
    } yield patches)
      .timeout(30.seconds)
      .map { patches =>
        val bytes = events(patches)
        assertEquals(patches.size, 1, clue = bytes.map(_.renderString))
        // one child morph, surface-namespaced id — not the whole surface group.
        assertEquals(
          bytes.head.elements,
          Some(
            """<div class="fh-cell" id="s_det__c_light_b"><span>on</span></div>"""
          )
        )
      }
  }

  /** A set NESTED inside a member, inside a surface — a tile per room, on a
    * tab.
    *
    * A member carries the layout tree it lives in, and that is what decides
    * which clients its patch may reach. Only the OUTER set is in the static
    * index; an inner one hangs off a member, so a `root` read from the index
    * answered `""` — the main page — and every inner member's patch went to
    * every connected client, tab open or not.
    *
    * Asserted at the level the bug actually shows: two viewers, one frame. The
    * unit test on `Member.root` in `MemberGraphSuite` pins the fix; this pins
    * the PROPERTY, which is what would have caught it in the first place.
    */
  private def nestedSurfaceDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
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
                    children = LayoutNode.kids(
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
    val lit = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    // A bulb goes out: a membership departure inside the INNER set.
    val change = StateChange("light.b", Some(on("light.b")), off("light.b"))
    val after = lit.updated("light.b", off("light.b"))
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(nestedSurfaceDash))
      )
      sessions <- Sessions.create
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
            watching <- Session.create("dashboard")
            _ <- watching.open.set(Set("det"))
            _ <- sessions.register("watching", watching)
            elsewhere <- Session.create("dashboard")
            _ <- elsewhere.open.set(Set("other"))
            _ <- sessions.register("elsewhere", elsewhere)
            renderer <- ref.get.map(_.rendererOf.get)
            // Establish the inner mount, so the frame produces a per-member
            // delta rather than a wholesale fill — the delta is the path that
            // has to get `root` right per member.
            seed = seeded(
              renderer,
              lit,
              List(
                "s_det__c_area_stue_0_0_light_a",
                "s_det__c_area_stue_0_0_light_b"
              )
            )
            log <- Ref[IO].of(seed._1)
            // Recorded once for the slug; then each viewer pulls its own.
            forWatching <- recordAndPull(
              server,
              sessions,
              store,
              renderer,
              log,
              List(change),
              open = Set("det"),
              holds = seed._2
            )
            // Same DOM, same cursor — the OPEN SET is the only difference, so
            // anything the second viewer receives is receiving it for that
            // reason alone.
            forElsewhere <- (log.get, store.current, RenderCache.create)
              .flatMapN((l, now, rc) =>
                Patches.resume(
                  renderer,
                  rc,
                  l,
                  seed._2,
                  now.entities,
                  0L,
                  Set("other"),
                  Map.empty
                )
              )
          } yield (forWatching, forElsewhere)
        }
    } yield out)
      .timeout(30.seconds)
      .map { case (forWatching, forElsewhere) =>
        val seen = events(forWatching).map(_.renderString)
        val unseen = events(forElsewhere).map(_.renderString)
        // The viewer WITH the tab open gets the departure. Without this half
        // the assertion below would pass just as well if nobody got anything.
        assert(
          seen.exists(_.contains("s_det__c_area_stue_0_0_light_b")),
          clue = seen
        )
        // The viewer on another tab gets nothing that names the nested set.
        assert(
          !unseen.exists(_.contains("s_det__c_area_stue")),
          clue = unseen
        )
      }
  }

}
