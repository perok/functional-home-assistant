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
import fh.view.model.{Ix, NodeId, TreeIndex}
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
    ids.foldLeft(empty)(_.touched(_, at))

  private def resume(
      log: FragmentLog,
      v: Long,
      holds: Map[Ix, Held],
      now: Map[String, EntityState] = states
  ): List[String] =
    // A fresh cache per call, so no test depends on what another rendered.
    RenderCache.create
      .flatMap(
        Patches
          .resume(
            renderer,
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
      logged(9L, "c", cid("light.b")).touched(cid("light.c"), 2L),
      1L,
      showing("light.a", "light.d")
    )
    assert(out(0).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(1).contains(s"""id="${cid("light.b")}""""), clue = out)
  }

  test("morphs precede placements") {
    val out = resume(
      logged(5L, "c", cid("light.b")).touched(cid("light.a"), 6L),
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
      logged(20L, "c", cid("light.b")).touched(cid("light.b"), 30L),
      1L,
      showing("light.a", "light.c", "light.d")
    )
    assertEquals(out.size, 1, clue = out)
    assert(out(0).contains("mode before"), clue = out)
  }

  test("a log key the renderer cannot resolve emits nothing") {
    // A key naming no node can never be sent, so it is dropped, not a crash;
    // `NodeId`/`DomId` keep a host id from getting here.
    val out = resume(empty.touched("no_such_node", 5L), 1L, showing(all*))
    assertEquals(out, Nil)
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

  private def held(bytes: String): Held = Held(
    Some(ByteValues(Map("v" -> bytes)))
  )

  /** The dangerous direction is claiming values the client lacks. A fill
    * overwrites a host's subtree with no per-node trace, so an old member claim
    * would outlive its bytes and suppress that value coming round again.
    */
  test("a fill forgets its host, then claims what it placed") {
    // `c` over eleven children, the second holding one: `c_1` is a prefix of
    // `c_10` and of `c_1_0`, and only one of them is under it.
    def dot = LayoutNode.Component("dot")
    val tree = TreeIndex.of(
      LayoutNode.Component(
        "col",
        slots = Slots.kids(
          List.tabulate(11)(i =>
            if i == 1 then LayoutNode.Component("col", slots = Slots.kids(dot))
            else dot
          )*
        )
      ),
      Map.empty
    )
    def ix(id: NodeId): Ix = tree.ixsOf(id).head
    val holds: Map[Ix, Held] = List(
      "c" -> "<c/>",
      "c_1" -> "<one/>",
      "c_10" -> "<ten/>",
      "c_1_0" -> "<nested/>",
      "c_2" -> "<other/>"
    ).map { case (id, html) => ix(id) -> held(html) }.toMap
    val after = Patches.applied(
      tree,
      holds,
      Addressed(
        Patch.Morph("<ignored/>"),
        establishes = Map(ix("c_1") -> held("<fresh/>")),
        invalidates = Set[NodeId]("c_1")
      )
    )
    assertEquals(after.get(ix("c_1_0")), None, clue = after)
    // The same patch's placement survives the prune it triggered.
    assertEquals(after.get(ix("c_1")), Some(held("<fresh/>")), clue = after)
    // `c_1` must not swallow `c_10`.
    assertEquals(after.get(ix("c_10")), holds.get(ix("c_10")), clue = after)
    assertEquals(after.get(ix("c")), holds.get(ix("c")), clue = after)
    assertEquals(after.get(ix("c_2")), holds.get(ix("c_2")), clue = after)
  }

  test("a cursor past everything is owed nothing") {
    val log = logged(4L, "c").touched(cid("light.c"), 5L).touched("other", 6L)
    assertEquals(resume(log, 7L, showing(all*)), Nil)
  }
}
