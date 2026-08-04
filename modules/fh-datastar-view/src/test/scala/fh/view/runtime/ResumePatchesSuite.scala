package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource
}
import fh.view.model.NodeId
import fh.view.runtime.Digest.AsHtml.given
import fh.view.testkit.TestIds.given
import io.circe.Json

/** [[Patches.resume]] — where a resuming client's group members get their
  * POSITION back. The ordering argument (docs/adr/0011-the-live-connection.md)
  * is the whole of the correctness here, so these tests are about which anchor
  * each insert names.
  *
  * A [[Mutation.Placed]] emits remove+insert for itself, so every placement is
  * two patches: that self-containment is what makes an arrival and a re-order
  * the same operation.
  */
class ResumePatchesSuite extends munit.FunSuite {

  // The whole dashboard is one dynamic group at the root, so its id is "c" and a
  // member's id is `c_<sanitized entity>` — matching Renderer.dynamicChildId.
  private val renderer = Renderer.create(
    Dashboard(
      cards =
        Map("dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))),
      card = LayoutNode.Dynamic(
        query = Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
        cases = List(
          DynamicCase(
            Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__")),
            "dot",
            slots = Map("state" -> SlotSource())
          )
        )
      )
    )
  )

  private def on(id: String) = EntityState(id, "on", Map.empty)

  /** Members sort ascending by entity id, so these are a, b, c, d in order. */
  private val states =
    List("light.a", "light.b", "light.c", "light.d")
      .map(id => id -> on(id))
      .toMap

  private def cid(entity: String) = renderer.dynamicChildId("c", entity)

  private def resume(log: FragmentLog, v: Long): List[String] =
    Patches
      .resume(renderer, log, log.digestsFor(_ => 0), states, v)
      .map(_.patch.toSse.renderString)

  private val empty = FragmentLog("test")

  /** Wall clock equal to the version, so nothing in these fixtures ages out —
    * retention is [[FragmentLogSuite]]'s subject, not this one's.
    */
  private def at(v: Long): Stamp = Stamp(v, v)

  test("a placement removes then inserts, anchored on the next member") {
    // light.b is placed; a is before it and c is after, so it goes before c. The
    // paired remove is what makes this idempotent in any client DOM.
    val log = empty.placed(
      "c",
      MemberKey.Entity("light.b"),
      cid("light.b"),
      "<b/>",
      at(5L)
    )
    val out = resume(log, 1L)
    assertEquals(out.size, 2, clue = out)
    assert(out(0).contains("mode remove"), clue = out(0))
    assert(out(0).contains("selector #" + cid("light.b")), clue = out(0))
    assert(out(1).contains("selector #" + cid("light.c")), clue = out(1))
    assert(out(1).contains("mode before"), clue = out(1))
  }

  test("the last member appends into the group root instead") {
    // No current member sorts after light.d, so there is no anchor to name.
    val log = empty.placed(
      "c",
      MemberKey.Entity("light.d"),
      cid("light.d"),
      "<d/>",
      at(5L)
    )
    val out = resume(log, 1L)
    assert(out(1).contains("mode append"), clue = out(1))
    assert(out(1).contains("selector #c"), clue = out(1))
  }

  test("placements go high-to-low so every anchor exists") {
    // b and c are both placed. Ascending would anchor b on c before c exists;
    // descending places c (anchored on the present d) and then b (anchored on
    // the just-placed c).
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), "<b/>", at(5L))
      .placed("c", MemberKey.Entity("light.c"), cid("light.c"), "<c/>", at(6L))
    val out = resume(log, 1L)
    assertEquals(out.size, 4, clue = out)
    assert(out(1).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(1).contains("selector #" + cid("light.d")), clue = out)
    assert(out(3).contains(s"""id="${cid("light.b")}""""), clue = out)
    assert(out(3).contains("selector #" + cid("light.c")), clue = out)
  }

  test("placement order depends on position, not on version") {
    // Same as above with the versions swapped: position, not recency, decides.
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), "<b/>", at(9L))
      .placed("c", MemberKey.Entity("light.c"), cid("light.c"), "<c/>", at(2L))
    val out = resume(log, 1L)
    assert(out(1).contains(s"""id="${cid("light.c")}""""), clue = out)
    assert(out(3).contains(s"""id="${cid("light.b")}""""), clue = out)
  }

  test("morphs precede mutations") {
    // Content goes first and the structural fixups land on top of it.
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), "<b/>", at(5L))
      .set(cid("light.a"), "<stale/>", 6L)
    val out = resume(log, 1L)
    assertEquals(out.size, 3, clue = out)
    // Rendered NOW, not read back from the log — `<stale/>` was what the log was
    // seeded with, and it never appears on the wire (statement (3)).
    assert(out(0).contains(s"""id="${cid("light.a")}""""), clue = out)
    assert(!out(0).contains("stale"), clue = out)
    assert(out(1).contains("mode remove"), clue = out)
    assert(out(2).contains("mode before"), clue = out)
  }

  test("a log key the renderer cannot resolve emits nothing") {
    // The ledger renders content FROM its keys, so a key naming no node is a
    // fragment that can never be sent. It must be dropped, not crash the resume
    // — and `NodeId`/`DomId` are what keep a `-self` or mount id from getting in
    // here in the first place.
    val out = resume(empty.set("no_such_node", "<x/>", 5L), 1L)
    assertEquals(out, Nil)
  }

  test("a placed node that is no longer a member is not inserted") {
    // It arrived and left again while the client was away. Unreachable in
    // practice (the latest mutation would be Gone), so this pins the defence.
    val log = empty.placed(
      "c",
      MemberKey.Entity("light.zz"),
      "c_light_zz",
      "<z/>",
      at(5L)
    )
    assertEquals(resume(log, 1L), Nil)
  }

  test("no container-level fragment can hide a placement any more") {
    // A group used to log its whole HTML under `gid`, and that ancestor entry
    // suppressed a member's insert. Nothing writes such an entry now — a group
    // root composes its members and so has no rendering of its OWN — and a
    // stale one planted by hand is not merely harmless but unresolvable: it
    // renders to nothing and drops out, while the placement still goes.
    val log = empty
      .placed("c", MemberKey.Entity("light.b"), cid("light.b"), "<b/>", at(5L))
      .set("c", "<group>all four</group>", 6L)
    val out = resume(log, 1L)
    assertEquals(out.size, 2, clue = out)
    assert(!out.exists(_.contains("all four")), clue = out)
    assert(out(1).contains("mode before"), clue = out)
  }

  /** What a patch does to the SESSION's record of this client's DOM. The
    * dangerous direction is claiming a digest the client does not have, and a
    * fill is where that happens without help: it overwrites a mount's whole
    * subtree with no per-node trace, so a member's old claim would outlive the
    * bytes it described and suppress that value coming round again.
    */
  test("a fill forgets its mount, then claims what it placed") {
    val holds: Map[NodeId, Digest] = List(
      "c" -> "<c/>",
      "c_1" -> "<one/>",
      "c_10" -> "<ten/>",
      "c_1_0" -> "<nested/>",
      "d_1" -> "<other/>"
    ).map { case (id, html) => (id: NodeId) -> Digest.of(html) }.toMap
    val after = Patches.applied(
      holds,
      Addressed(
        Patch.Morph("<ignored/>"),
        establishes = Map(("c_1": NodeId) -> Digest.of("<fresh/>")),
        invalidates = Set[NodeId]("c_1")
      )
    )
    // The root of the fill and everything under it are unknown again...
    assertEquals(after.get("c_1_0"), None, clue = after)
    // ...but the same patch's own placement survives the prune it triggered.
    assertEquals(after.get("c_1"), Some(Digest.of("<fresh/>")), clue = after)
    // A prefix is not a sibling: `c_1` must not swallow `c_10`.
    assertEquals(after.get("c_10"), holds.get("c_10"), clue = after)
    assertEquals(after.get("c"), holds.get("c"), clue = after)
    assertEquals(after.get("d_1"), holds.get("d_1"), clue = after)
  }

  test("a cursor past everything is owed nothing") {
    val log = empty
      .removed("c", cid("light.b"), at(4L))
      .placed("c", MemberKey.Entity("light.c"), cid("light.c"), "<c/>", at(5L))
      .set("other", "<o/>", 6L)
    assertEquals(resume(log, 7L), Nil)
  }
}
