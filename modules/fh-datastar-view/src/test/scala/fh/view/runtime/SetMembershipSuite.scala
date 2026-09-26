package fh.view.runtime

import fh.view.query.QuerySnapshot

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
  Region,
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
  * leaving and switching case, and the churn rule choosing between a per-member
  * delta and a whole-host fill.
  */
class SetMembershipSuite extends ServerHarness {

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

  /** One change through the shared diff against `after`; returns the element
    * patches and what the viewer holds after applying them. The resume cursor
    * also rides every non-empty batch (ADR 0011); one test below covers it.
    */

  private def runShared(
      dash: Dashboard,
      after: Map[String, EntityState],
      change: StateChange,
      // What the viewer's DOM already holds, by node id -> HTML.
      seedCache: Map[String, String] = Map.empty,
      ui: Map[String, String] = Map.empty
  ): IO[(List[String], Map[NodeId, Held])] =
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(dash))
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
            // What the viewer holds after applying what it was just sent.
          } yield (
            elementPatches(events(patches)),
            patches.foldLeft(seed._2)(Patches.applied(renderer.ancestry, _, _))
          )
        }
    } yield out)
      .timeout(30.seconds)

  test("in-place member tick patches ONE child, not the whole group") {
    val after = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
    val change = StateChange("light.b", Some(on("light.b")), on("light.b"))
    runShared(dynDash, after, change).map { case (patches, _) =>
      assertEquals(patches.size, 1, clue = patches)
      val p = patches.head
      // Outer-morphs the child id, not the group.
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
    // A member's node is state-derived, so crossing a case boundary must
    // replace it. Getting this wrong is silent: the card renders from the wrong
    // branch for as long as the entity stays a member.
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
    // A member is in the reverse index like any node, so an entity its case
    // binds names it even when the group's query does not match that entity.
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
    // The arriving card contributes no entity edge, so the member's id is the
    // handle: `syncMembers` reports what it replaced and `record` touches that.
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

  /** "Established" means the log has member entries; no container logs a
    * fragment of its own.
    */

  private val establishedGroup = Map(
    "c_light_a" -> "<a>",
    "c_light_c" -> "<c>",
    "c_light_d" -> "<d>"
  )

  test("member add: per-entity insert BEFORE the DOM successor") {
    // Churn 1 of 3 shown: per-entity.
    val after = Map(
      "light.a" -> on("light.a"),
      "light.b" -> on("light.b"),
      "light.c" -> on("light.c"),
      "light.d" -> on("light.d")
    )
    val change = StateChange("light.b", Some(off("light.b")), on("light.b"))
    // An arrival is remove-then-insert, idempotent whatever the client's DOM
    // holds, so an arrival and a re-order are one operation (see
    // `Patches.resume`).
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
    // Churn 1 of 4 shown: per-entity remove.
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
      // The event name still says "…elements".
      assert(!p.contains("data: elements"), clue = p)
      assert(!cache.contains("c_light_b"), clue = cache)
    }
  }

  test("removing 1 of 2 members is a DELTA, not a fill") {
    // A `remove` carries no HTML, where a fill re-renders the survivor and
    // raises the host's horizon, costing every client below that cursor its
    // delta path. A fill happens only where it costs nothing (all arrived or
    // all left) or where there is no baseline.
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
      assert(!p.contains("data: elements"), clue = p)
      assert(!cache.contains("c_light_b"), clue = cache)
      assert(!cache.contains("c"), clue = cache)
    }
  }

  test("the LAST member leaving fills, because the fill carries nothing") {
    // Everything left, so there is no survivor for a fill to re-send, and an
    // empty `inner` leaves the host unambiguously empty.
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
    // With an empty log the group is not established, so it fills to set a
    // base.
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
      // Established by its members' entries, and by no entry of its own.
      assert(cache.contains("c_light_a"), clue = cache)
      assert(!cache.contains("c"), clue = cache)
    }
  }

  private def surfaceDynDash = Dashboard(
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
        onSet(
          List("light.a", "light.b"),
          List((None, "dot", Map("state" -> SlotSource())))
        )
      )
    )
  )

  /** '''A client is never sent a surface it is not viewing.''' Each viewer
    * renders against its own open set. The second direction stops this passing
    * if nobody were sent anything.
    */

  test(
    "a tab nobody is viewing is not pushed to them; the viewer still gets it"
  ) {
    val after = Map(
      "sensor.a" -> es("sensor.a", "A0"),
      "sensor.b" -> es("sensor.b", "B1")
    )
    val change =
      StateChange("sensor.b", Some(es("sensor.b", "B0")), es("sensor.b", "B1"))
    (for {
      store <- StateStore.inMemory(after)
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(tabsRenderer))
      sessions <- Sessions.create
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
            viewingT0 <- Session.create("dashboard")
            _ <- viewingT0.open.set(Set("c_t0"))
            _ <- sessions.register("a", viewingT0)
            viewingT1 <- Session.create("dashboard")
            _ <- viewingT1.open.set(Set("c_t1"))
            _ <- sessions.register("b", viewingT1)
            renderer <- ref.get.map(_.rendererOf.get)
            log <- Ref[IO].of(FragmentLog("test"))
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
                  _ => IO.pure(QuerySnapshot.empty),
                  Map.empty,
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
        val bytes = events(forB).map(_.render)
        assert(
          bytes.exists(_.contains("""id="s_c_t1__c"""")),
          clue = bytes
        )
        assert(bytes.exists(_.contains("B1")), clue = bytes)
        assert(
          !events(forA).map(_.render).exists(_.contains("s_c_t1__c")),
          clue = events(forA).map(_.render)
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
      // The patch path never calls HA; an unexpected registry call still
      // raises.
      fake <- FakeHomeAssistant.create(Nil)
      patches <- Server
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
        assertEquals(patches.size, 1, clue = bytes.map(_.render))
        // One child morph, not the whole surface group.
        assertEquals(
          bytes.head.elements,
          Some(
            """<div class="fh-cell" id="s_det__c_light_b"><span>on</span></div>"""
          )
        )
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
    val lit = Map("light.a" -> on("light.a"), "light.b" -> on("light.b"))
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
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
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
            // Establish the inner host, so the frame takes the per-member
            // delta: the path that must get `root` right per member.
            seed = seeded(
              renderer,
              lit,
              List(
                "s_det__c_area_stue_0_0_light_a",
                "s_det__c_area_stue_0_0_light_b"
              )
            )
            log <- Ref[IO].of(seed._1)
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
            // Same DOM and cursor: the open set is the only difference.
            forElsewhere <- (log.get, store.current, RenderCache.create)
              .flatMapN((l, now, rc) =>
                Patches.resume(
                  renderer,
                  rc,
                  l,
                  seed._2,
                  now.entities,
                  _ => IO.pure(QuerySnapshot.empty),
                  Map.empty,
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
        val seen = events(forWatching).map(_.render)
        val unseen = events(forElsewhere).map(_.render)
        // Without this half the assertion below passes if nobody got anything.
        assert(
          seen.exists(_.contains("s_det__c_area_stue_0_0_light_b")),
          clue = seen
        )
        assert(
          !unseen.exists(_.contains("s_det__c_area_stue")),
          clue = unseen
        )
      }
  }

}
