package fh.view.runtime

import fh.view.query.QuerySnapshot

import fh.view.model.{CardDef, Dashboard, LayoutNode, Op, Predicate, SlotSource}
import fh.view.model.NodeId
import fh.view.testkit.TestIds.{setId, given}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json

/** [[Patches.resume]]: where a resuming client's members get their position
  * back. The ordering argument (ADR 0011) is the whole of the correctness, so
  * these test which anchor each insert names. A [[Mutation.Placed]] emits
  * remove+insert, which makes an arrival and a re-order one operation.
  */
class ResumePatchesSuite extends munit.FunSuite {

  // One set at the root, so members are `c_<sanitized entity>`.
  private val renderer = Renderer.create(
    Dashboard(
      cards =
        Map("dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))),
      card = LayoutNode.SetNode(
        candidates = List("light.a", "light.b", "light.c", "light.d"),
        members = List("light.a", "light.b", "light.c", "light.d").map { id =>
          id -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
                LayoutNode.Component(
                  "dot",
                  Map(
                    "entity_id" -> SlotSource(literal = Some(id)),
                    "state" -> SlotSource()
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

  /** A fresh cache per call, so no test depends on what another rendered. */
  private def resume(log: FragmentLog, v: Long): List[String] =
    RenderCache.create
      .flatMap(
        Patches
          .resume(
            renderer,
            _,
            log,
            Map.empty,
            states,
            _ => IO.pure(QuerySnapshot.empty),
            Map.empty,
            v
          )
      )
      .unsafeRunSync()
      .map(_.patch.toSse.render)

  private val empty = FragmentLog("test")

  test("a placement removes then inserts, anchored on the next member") {
    // The paired remove makes this idempotent in any client DOM.
    val log = empty.placed(
      "c",
      MemberKey.Entity("light.b"),
      cid("light.b"),
      5L
    )
    val out = resume(log, 1L)
    assertEquals(out.size, 2, clue = out)
    assert(out(0).contains("mode remove"), clue = out(0))
    assert(out(0).contains("selector #" + cid("light.b")), clue = out(0))
    assert(out(1).contains("selector #" + cid("light.c")), clue = out(1))
    assert(out(1).contains("mode before"), clue = out(1))
  }

  test("the last member appends into the group root instead") {
    // Nothing sorts after light.d, so no anchor.
    val log = empty.placed(
      "c",
      MemberKey.Entity("light.d"),
      cid("light.d"),
      5L
    )
    val out = resume(log, 1L)
    assert(out(1).contains("mode append"), clue = out(1))
    assert(out(1).contains("selector #c"), clue = out(1))
  }

  test("placements go high-to-low so every anchor exists") {
    // Ascending would anchor b on c before c exists; descending places c on d,
    // then b on the just-placed c.
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), 5L)
      .placed("c", MemberKey.Entity("light.c"), cid("light.c"), 6L)
    val out = resume(log, 1L)
    assertEquals(out.size, 4, clue = out)
    assert(out(1).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(1).contains("selector #" + cid("light.d")), clue = out)
    assert(out(3).contains(s"""id="${cid("light.b")}""""), clue = out)
    assert(out(3).contains("selector #" + cid("light.c")), clue = out)
  }

  test("placement order depends on position, not on version") {
    // Position, not recency, decides.
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), 9L)
      .placed("c", MemberKey.Entity("light.c"), cid("light.c"), 2L)
    val out = resume(log, 1L)
    assert(out(1).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(3).contains(s"""id="${cid("light.b")}""""), clue = out)
  }

  test("morphs precede mutations") {
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), 5L)
      .touched(cid("light.a"), 6L)
    val out = resume(log, 1L)
    assertEquals(out.size, 3, clue = out)
    // Rendered now: the seeded `<stale/>` never reaches the wire (statement
    // (3)).
    assert(out(0).contains(s"""id="${cid("light.a")}""""), clue = out)
    assert(!out(0).contains("stale"), clue = out)
    assert(out(1).contains("mode remove"), clue = out)
    assert(out(2).contains("mode before"), clue = out)
  }

  test("a log key the renderer cannot resolve emits nothing") {
    // A key naming no node can never be sent, so it is dropped, not a crash;
    // `NodeId`/`DomId` keep a host id from getting here.
    val out = resume(empty.touched("no_such_node", 5L), 1L)
    assertEquals(out, Nil)
  }

  test("a placed node that is no longer a member is not inserted") {
    // Unreachable in practice, since the latest mutation would be Gone: this
    // pins the defence.
    val log = empty.placed(
      "c",
      MemberKey.Entity("light.zz"),
      "c_light_zz",
      5L
    )
    assertEquals(resume(log, 1L), Nil)
  }

  test("no container-level fragment can hide a placement any more") {
    // A group root has no rendering of its own, so nothing writes this entry;
    // one planted by hand renders to nothing and drops out, and the placement
    // still goes.
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), 5L)
      .touched("c", 6L)
    val out = resume(log, 1L)
    assertEquals(out.size, 2, clue = out)
    assert(!out.exists(_.contains("all four")), clue = out)
    assert(out(1).contains("mode before"), clue = out)
  }

  /** The dangerous direction is claiming a digest the client lacks. A fill
    * overwrites a host's subtree with no per-node trace, so an old member claim
    * would outlive its bytes and suppress that value coming round again.
    */
  test("a fill forgets its host, then claims what it placed") {
    val holds: Map[NodeId, Held] = List(
      "c" -> "<c/>",
      "c_1" -> "<one/>",
      "c_10" -> "<ten/>",
      "c_1_0" -> "<nested/>",
      "d_1" -> "<other/>"
    ).map { case (id, html) => (id: NodeId) -> Held.of(html) }.toMap
    val after = Patches.applied(
      TestAncestry.of(holds.keySet),
      holds,
      Addressed(
        Patch.Morph("<ignored/>"),
        establishes = Map(("c_1": NodeId) -> Held.of("<fresh/>")),
        invalidates = Set[NodeId]("c_1")
      )
    )
    assertEquals(after.get("c_1_0"), None, clue = after)
    // The same patch's placement survives the prune it triggered.
    assertEquals(after.get("c_1"), Some(Held.of("<fresh/>")), clue = after)
    // `c_1` must not swallow `c_10`.
    assertEquals(after.get("c_10"), holds.get("c_10"), clue = after)
    assertEquals(after.get("c"), holds.get("c"), clue = after)
    assertEquals(after.get("d_1"), holds.get("d_1"), clue = after)
  }

  test("a cursor past everything is owed nothing") {
    val log = empty
      .removed("c", cid("light.b"), 4L)
      .placed("c", MemberKey.Entity("light.c"), cid("light.c"), 5L)
      .touched("other", 6L)
    assertEquals(resume(log, 7L), Nil)
  }
}
