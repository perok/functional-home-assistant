package fh.view.runtime

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import fh.view.testkit.Conds

import fh.view.fetch.FetchSnapshot

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Op,
  Value,
  ValueExpr,
  TemplateSlot
}
import fh.view.model.{Ix, NodeId}
import fh.view.testkit.TestIds.{setId, given}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json

/** [[Patches.resume]]: where a resuming client's members get their position
  * back. The ordering argument (ADR 0011) is the whole of the correctness, so
  * these test which anchor each insert names, against the list a viewer holds
  * ([[Patches.planHost]]).
  */
class ResumePatchesSuite extends munit.FunSuite {

  // One set at the root, so members are `c_<sanitized entity>`.
  private val renderer = Renderer.create(
    Dashboard(
      cards = Sets.cards ++ Map("dot" -> TestCard("<span>{{state}}</span>")),
      card = Sets.node(
        candidates = List("light.a", "light.b", "light.c", "light.d"),
        members = List("light.a", "light.b", "light.c", "light.d").map { id =>
          id -> Sets.Member(
            List(
              Sets.Clause(
                Some(Conds.cmp("state", Op.Eq, Json.fromString("on"))),
                LayoutNode.Component(
                  "dot",
                  Slots.values(
                    Map(
                      "entity_id" -> TemplateSlot(ValueExpr.literal(id)),
                      "state" -> TemplateSlot(ValueExpr(Value.Entity(None)))
                    )
                  )
                )
              )
            )
          )
        }.toMap
      )
    )
  )

  private def on(id: String) = EntityState(id, "on", Map.empty)

  private val states =
    List("light.a", "light.b", "light.c", "light.d")
      .map(id => id -> on(id))
      .toMap

  private def cid(entity: String) =
    renderer.members.memberIdOf(setId("c"), entity)

  private val host: Ix = renderer.hostIx(setId("c"), Shared.of(states))

  private def key(entity: String): Ix = renderer.index.ixsOf(cid(entity)).head

  /** The set's page as a client holds it: these members, in this order. */
  private def showing(entities: String*): Map[Ix, Held] =
    Map(host -> Held(shows = Some(IArray.from(entities.map(key)))))

  private val all = List("light.a", "light.b", "light.c", "light.d")

  /** What the recorder writes: the host, and each arriving member's root. */
  private def logged(at: Long, ids: NodeId*): FragmentLog =
    ids.foldLeft(empty)((l, id) => l.touched(ixOf(id), at))

  private def ixOf(id: NodeId): Ix = renderer.index.ixsOf(id).head

  private def resume(
      log: FragmentLog,
      v: Long,
      holds: Map[Ix, Held],
      now: Map[String, EntityState] = states,
      by: Renderer = renderer
  ): List[String] =
    // A fresh cache per call, so no test depends on what another rendered.
    RenderCache.create
      .flatMap(
        Patches
          .resume(
            by,
            _,
            log,
            holds,
            Shared.of(now),
            noDecisionFetches,
            _ => IO.pure(FetchSnapshot.empty),
            Map.empty,
            v
          )
      )
      .unsafeRunSync()
      .map(_.patch.toSse.render)

  private val empty = FragmentLog("test")

  test("an arrival is placed, anchored on the next member") {
    val out = resume(
      logged(5L, "c", cid("light.b")),
      1L,
      showing("light.a", "light.c", "light.d")
    )
    assertEquals(out.size, 1, clue = out)
    assert(out(0).contains(s"""id="${cid("light.b")}""""), clue = out)
    assert(out(0).contains("selector #" + cid("light.c")), clue = out)
    assert(out(0).contains("mode before"), clue = out)
  }

  test("a reorder leaves and places the member outside the longest run") {
    // The leave first, so the place is idempotent in any client DOM.
    val out = resume(
      logged(5L, "c"),
      1L,
      showing("light.b", "light.a", "light.c", "light.d")
    )
    assertEquals(out.size, 2, clue = out)
    assert(out(0).contains("mode remove"), clue = out(0))
    assert(out(0).contains("selector #" + cid("light.b")), clue = out(0))
    assert(out(1).contains("selector #" + cid("light.c")), clue = out(1))
    assert(out(1).contains("mode before"), clue = out(1))
  }

  test("the last member appends into the group root instead") {
    // Nothing sorts after light.d, so no anchor.
    val out = resume(
      logged(5L, "c", cid("light.d")),
      1L,
      showing("light.a", "light.b", "light.c")
    )
    assertEquals(out.size, 1, clue = out)
    assert(out(0).contains("mode append"), clue = out)
    assert(out(0).contains("selector #c"), clue = out)
  }

  test("placements go back to front so every anchor exists") {
    // Front to back would anchor b on c before c exists; this places c on d,
    // then b on the just-placed c.
    val out = resume(
      logged(5L, "c", cid("light.b"), cid("light.c")),
      1L,
      showing("light.a", "light.d")
    )
    assertEquals(out.size, 2, clue = out)
    assert(out(0).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(0).contains("selector #" + cid("light.d")), clue = out)
    assert(out(1).contains(s"""id="${cid("light.b")}""""), clue = out)
    assert(out(1).contains("selector #" + cid("light.c")), clue = out)
  }

  test("placement order depends on position, not on version") {
    val out = resume(
      logged(9L, "c", cid("light.b")).touched(key("light.c"), 2L),
      1L,
      showing("light.a", "light.d")
    )
    assert(out(0).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(1).contains(s"""id="${cid("light.b")}""""), clue = out)
  }

  test("morphs precede placements") {
    val out = resume(
      logged(5L, "c", cid("light.b")).touched(key("light.a"), 6L),
      1L,
      showing("light.a", "light.c", "light.d")
    )
    assertEquals(out.size, 2, clue = out)
    // Rendered now, from the current snapshot.
    assert(out(0).contains(s"""id="${cid("light.a")}""""), clue = out)
    assert(out(1).contains("mode before"), clue = out)
  }

  test("a tick on an arriving member rides its placement") {
    // One insert, not a morph and an insert; its content is rendered fresh.
    val out = resume(
      logged(20L, "c", cid("light.b")).touched(key("light.b"), 30L),
      1L,
      showing("light.a", "light.c", "light.d")
    )
    assertEquals(out.size, 1, clue = out)
    assert(out(0).contains("mode before"), clue = out)
  }

  test("a logged place the renderer does not show emits nothing") {
    // A place nothing shows can never be sent, so it is dropped, not a crash.
    // A place naming no node at all is not representable as an `Ix`.
    val gone =
      states.updated("light.b", EntityState("light.b", "off", Map.empty))
    val out =
      resume(empty.touched(key("light.b"), 5L), 1L, showing(all*), gone)
    assertEquals(out, Nil)
  }

  /** One member of two clauses, which mint the same ids: `light.a` on is the
    * first, off the second.
    */
  private object TwoClauses {
    val r: Renderer = Renderer.create(
      Dashboard(
        cards = Sets.cards ++ Map("dot" -> TestCard("<span>{{state}}</span>")),
        card = Sets.node(
          candidates = List("light.a"),
          members = Map(
            "light.a" -> Sets.Member(
              List(
                Sets.Clause(
                  Some(Conds.cmp("state", Op.Eq, Json.fromString("on"))),
                  dot
                ),
                Sets.Clause(None, dot)
              )
            )
          )
        )
      )
    )
    private def dot = LayoutNode.Component(
      "dot",
      Slots.values(
        Map(
          "entity_id" -> TemplateSlot(ValueExpr.literal("light.a")),
          "state" -> TemplateSlot(ValueExpr(Value.Entity(None)))
        )
      )
    )
    val member: NodeId = r.members.memberIdOf(setId("c"), "light.a")
    val offStates: Map[String, EntityState] =
      Map("light.a" -> EntityState("light.a", "off", Map.empty))
    val shown: Ix = r.nodeAt(member, Shared.of(offStates)).map(_.ix).get
    val other: Ix = r.index.ixsOf(member).filterNot(_ == shown).head
  }

  test("a change in one clause is logged apart from the other's") {
    import TwoClauses.*
    val prev = Shared.of(offStates)
    val next = Shared.of(
      Map("light.a" -> EntityState("light.a", "off", Map("x" -> Json.True))),
      1L
    )
    val log = Patches.record(
      r,
      empty,
      Patches.DiffRequest(List(member), Nil, Nil, prev, next)
    )
    assertEquals(log.fragments, Map(shown -> 1L))
  }

  test("a change in one clause sends nothing to a viewer shown the other") {
    import TwoClauses.*
    def sent(at: Ix) =
      resume(empty.touched(at, 5L), 1L, Map.empty, offStates, r)
    assertEquals(sent(other), Nil)
    // The control: the same change in the clause shown is sent.
    assertEquals(sent(shown).size, 1, clue = sent(shown))
  }

  test("a departed member is a removal, not a group repaint") {
    val gone =
      states.updated("light.b", EntityState("light.b", "off", Map.empty))
    val out = resume(logged(5L, "c"), 1L, showing(all*), gone)
    assertEquals(out.size, 1, clue = out)
    assert(out(0).contains("mode remove"), clue = out)
    assert(out(0).contains("selector #" + cid("light.b")), clue = out)
  }

  test("a key logged as arrived that has left again is not inserted") {
    val gone =
      states.updated("light.b", EntityState("light.b", "off", Map.empty))
    val out = resume(
      logged(5L, "c", cid("light.b")),
      1L,
      showing("light.a", "light.c", "light.d"),
      gone
    )
    assertEquals(out, Nil)
  }

  test("a host that arrives whole is filled, and its members are not morphed") {
    // A fill carries what is under its host, and is never suppressed by
    // covering itself.
    val out = resume(logged(5L, "c", cid("light.a")), 1L, Map.empty)
    assertEquals(out.size, 1, clue = out)
    assert(out(0).contains("mode inner"), clue = out)
    assert(out(0).contains("selector #c"), clue = out)
  }

  test("a cursor past everything is owed nothing") {
    val log =
      logged(4L, "c").touched(key("light.c"), 5L).touched(key("light.d"), 6L)
    assertEquals(resume(log, 7L, showing(all*)), Nil)
  }
}
