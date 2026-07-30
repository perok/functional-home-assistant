package fh.view.runtime

import fh.view.model.NodeId
import fh.view.testkit.TestIds.given

/** The resume cursor's pure core (docs/adr/0011-the-live-connection.md). Every
  * failure mode here is SILENT — the server believes the browser is current and
  * suppresses the patch, so the user sees a stale value, a ghost element, or a
  * duplicate indefinitely rather than an error. That is why these are asserted
  * on the value rather than through a booted server.
  */
class FragmentLogSuite extends munit.FunSuite {

  private val log = FragmentLog("test")

  /** A stamp whose wall clock equals its version, so these fixtures read as
    * "recent" and never evict. The age-based eviction tests set the two apart
    * deliberately.
    */
  private def at(v: Long): Stamp = Stamp(v, v)

  private def member(e: String): MemberKey = MemberKey.Entity(e)

  /** One `moved` entry, typed — the tuple's key is a [[NodeId]] and a literal
    * needs the conversion applied on that side explicitly.
    */
  private def moved(id: NodeId, m: Mutation): (NodeId, Mutation) = id -> m

  /** These fixtures never evict, so a cursor is always resumable; `since`
    * returning None is exercised by the eviction tests below.
    */
  extension (l: FragmentLog)
    private def owed(v: Long): Resume =
      l.since(v).getOrElse(fail(s"cursor $v unexpectedly not resumable"))

  test("a cursor at the current version is owed nothing older") {
    // `>=`, so version 3 is re-sent to a cursor AT 3 (one store version can span
    // several patch batches) — but nothing older is.
    assertEquals(log.set("a", "<a/>", 3L).owed(3L).nodes, List[NodeId]("a"))
    assertEquals(log.set("a", "<a/>", 3L).owed(4L).nodes, Nil)
  }

  test("the log names nodes, never their content") {
    // Statement (3): what comes back is a node id the caller renders from the
    // CURRENT snapshot — at least as fresh as anything the log could have kept,
    // and what lets the log store a digest instead of bytes.
    val out = log
      .set("child", "<c v=30/>", 30L)
      .set("parent", "<p v=25/>", 25L)
      .owed(1L)
    assertEquals(out.nodes.toSet, Set[NodeId]("child", "parent"))
  }

  test("the stored digest answers only 'is this still what it holds'") {
    val l = log.set("a", "<a v=1/>", 3L)
    assert(l.holds("a", "<a v=1/>"))
    assert(!l.holds("a", "<a v=2/>"))
    // An ABSENT entry holds nothing — unknown means send it, which is what makes
    // dropping an entry always safe rather than destroying what a resume needed.
    assert(!l.holds("b", "<b/>"))
    assert(!l.invalidate("a").holds("a", "<a v=1/>"))
  }

  test("a departed member replays as a removal, not a group re-render") {
    // The win over re-rendering the group: one small patch, not 20 cards.
    val out = log.removed("c_light_a", at(5L)).owed(1L)
    assertEquals(out.moved, List(moved("c_light_a", Mutation.Gone(at(5L)))))
    // ...and the stale entry does not also come back as a morph.
    assertEquals(out.nodes, Nil)
  }

  test("a placed node is reported as a mutation, never as a morph") {
    // Morphing an id the client's DOM lacks silently does nothing, so the node
    // must not leak into the morph list.
    val out =
      log.placed("c", member("light.a"), "c_light_a", "<a/>", at(5L)).owed(1L)
    assertEquals(
      out.moved,
      List(moved("c_light_a", Mutation.Placed("c", member("light.a"), at(5L))))
    )
    assertEquals(out.nodes, Nil)
  }

  test("a rejoin is one mutation, not a gone/placed pair") {
    // The reason these are ONE sum type: a node cannot be both absent and
    // present, so latest wins and the old special case disappears.
    val out = log
      .removed("c_light_a", at(10L)) // left...
      .placed(
        "c",
        member("light.a"),
        "c_light_a",
        "<a/>",
        at(20L)
      ) // ...came back
      .owed(1L)
    assertEquals(
      out.moved,
      List(moved("c_light_a", Mutation.Placed("c", member("light.a"), at(20L))))
    )
  }

  test("leaving after arriving collapses the same way, to Gone") {
    val out = log
      .placed("c", member("light.a"), "c_light_a", "<a/>", at(10L))
      .removed("c_light_a", at(20L))
      .owed(1L)
    assertEquals(out.moved, List(moved("c_light_a", Mutation.Gone(at(20L)))))
  }

  test("a member key carries HOW to resolve it, not just its name") {
    // The distinction a bare String refused to carry: a dynamic group's member is
    // an entity, a state group's is a branch surface, and each resolves
    // differently. As a sum type the caller needs no per-kind rule.
    assertEquals(
      MemberKey.Entity("light.a"),
      MemberKey.Entity("light.a"): MemberKey
    )
    assertNotEquals(
      MemberKey.Entity("x"): MemberKey,
      MemberKey.Surface("x"): MemberKey
    )
  }

  test("a subtree re-stamp supersedes its stale mutations") {
    // Heavy churn repaints the group wholesale, so its fresh HTML is
    // authoritative: a leftover Gone would delete a rejoined member, and a
    // leftover Placed would insert one the HTML already contains.
    val out = log
      .removed("c_light_a", at(10L))
      .placed("c", member("light.b"), "c_light_b", "<b/>", at(11L))
      .invalidateWhere(k => k == "c" || k.startsWith("c_"))
      .set("c", "<c>both present</c>", 20L)
      .owed(1L)
    assertEquals(out.moved, Nil)
    assertEquals(out.nodes, List[NodeId]("c"))
  }

  test("an ancestor fragment at or after the mutation already carries it") {
    // An ancestor morph rendered at v=30 contains the member placed at v=25, so
    // inserting it again would duplicate the element. Ancestry is a path-id
    // prefix, and the group `c_0` is an ancestor of its child `c_0_light_a`.
    val withAncestor = log
      .placed("c_0", member("light.a"), "c_0_light_a", "<a/>", at(25L))
      .set("c_0", "<group/>", 30L)
    assert(withAncestor.coveredByAncestor("c_0_light_a", 25L))
    assertEquals(withAncestor.owed(1L).moved, Nil)
    // An ancestor rendered BEFORE the placement does not carry it.
    val stale = log
      .placed("c_0", member("light.a"), "c_0_light_a", "<a/>", at(25L))
      .set("c_0", "<group/>", 20L)
    assert(!stale.coveredByAncestor("c_0_light_a", 25L))
    assertEquals(stale.owed(1L).moved.size, 1)
  }

  test("a node never covers itself") {
    // Self-coverage would make every fragment suppress its own emission — the
    // whole resume would silently send nothing.
    val l = log.set("c_0", "<x/>", 5L)
    assert(!l.coveredByAncestor("c_0", 5L))
    assertEquals(l.owed(1L).nodes, List[NodeId]("c_0"))
  }

  test("prefix ancestry does not confuse sibling ids") {
    // `c_1` must not read as an ancestor of `c_10`; the trailing `_` is what
    // prevents it.
    val l = log.set("c_1", "<x/>", 9L)
    assert(!l.coveredByAncestor("c_10", 5L))
    assert(l.coveredByAncestor("c_1_0", 5L))
  }

  test("a descendant fragment an ancestor already carries is not re-sent") {
    // Correctness never depended on this, but sending a subtree twice defeats the
    // point of resuming.
    val out = log
      .set("c_0_1", "<child v=20/>", 20L)
      .set("c_0", "<parent v=25/>", 25L)
      .owed(1L)
    assertEquals(out.nodes, List[NodeId]("c_0"))
  }

  test("a descendant NEWER than its ancestor is still sent") {
    // The parent's HTML predates the child's change, so the child's morph is the
    // only thing carrying it. Both go — and with content rendered NOW rather than
    // read back, there is no stale-parent-wins hazard left to order around.
    val out = log
      .set("c_0", "<parent v=20/>", 20L)
      .set("c_0_1", "<child v=25/>", 25L)
      .owed(1L)
    assertEquals(out.nodes.toSet, Set[NodeId]("c_0", "c_0_1"))
  }

  test("a removal an ancestor's fresh HTML already omits is not re-sent") {
    val out = log
      .removed("c_0_light_a", at(20L))
      .set("c_0", "<parent v=25/>", 25L)
      .owed(1L)
    assertEquals(out.moved, Nil)
  }

  test("a removal NEWER than its ancestor is still sent") {
    val out = log
      .set("c_0", "<parent v=25/>", 25L)
      .removed("c_0_light_a", at(30L))
      .owed(1L)
    assertEquals(out.moved.map(_._1), List[NodeId]("c_0_light_a"))
  }

  test("a content tick after a placement rides the mutation") {
    // The cross-map collapse: one insert, not a morph and an insert. The insert's
    // content is rendered fresh, so it is the v=30 value either way.
    val l = log
      .placed("c", member("light.a"), "c_light_a", "<v20/>", at(20L))
      .set("c_light_a", "<v30/>", 30L)
    val fromBefore = l.owed(1L)
    assertEquals(fromBefore.nodes, Nil)
    assertEquals(fromBefore.moved.map(_._1), List[NodeId]("c_light_a"))
    assert(l.holds("c_light_a", "<v30/>"))
    // A client that WAS present for the insert needs only the content morph.
    val fromAfter = l.owed(25L)
    assertEquals(fromAfter.moved, Nil)
    assertEquals(fromAfter.nodes, List[NodeId]("c_light_a"))
  }

  test("a cleared log owes nothing but keeps its identity") {
    val before = log
      .set("a", "<a/>", 3L)
      .removed("b", at(4L))
      .placed("c", member("light.a"), "c_light_a", "<a/>", at(5L))
    val after = before.cleared
    assertEquals(after.id, before.id) // cursors already issued stay comparable
    assertEquals(after.owed(1L), Resume(Nil, Nil), clue = after)
  }

  test("mutations older than the retention window are evicted") {
    // The one part of the log that is not self-limiting: a `Gone` for a member
    // that never returns has nothing to remove it, so a long-lived server would
    // otherwise accumulate one per entity that ever matched a group.
    val hour = FragmentLog.Retention.toMillis
    val stale = log.removed("c_old", Stamp(5L, 1_000L))
    // A later change, two hours on, ages the first one out.
    val fresh = stale.removed("c_new", Stamp(9L, 1_000L + 2 * hour))
    assertEquals(fresh.mutations.keySet, Set("c_new"))
    // Complete only from just after the newest thing forgotten.
    assertEquals(fresh.horizon, 6L)
  }

  test("a cursor below the horizon is refused, not served a lossy delta") {
    val hour = FragmentLog.Retention.toMillis
    val evicted = log
      .removed("c_old", Stamp(5L, 1_000L))
      .removed("c_new", Stamp(9L, 1_000L + 2 * hour))
    // Cursor 5 needed to hear about the removal that was just forgotten, so the
    // honest answer is a repaint rather than a delta missing it.
    assertEquals(evicted.since(5L), None)
    assert(evicted.since(6L).isDefined)
  }

  test("a mutation inside the window survives") {
    val half = FragmentLog.Retention.toMillis / 2
    val kept = log
      .removed("c_old", Stamp(5L, 1_000L))
      .removed("c_new", Stamp(9L, 1_000L + half))
    assertEquals(kept.mutations.size, 2)
    assertEquals(
      kept.horizon,
      0L
    ) // nothing forgotten, so every cursor is valid
  }

  test("re-touching a node does not grow the map, so eviction stays rare") {
    // Latest-wins per node id is what keeps a churning entity from filling the
    // window on its own.
    val churned = (1 to 1000).foldLeft(log) { (l, i) =>
      if (i % 2 == 0) l.removed("c_light_a", at(i.toLong))
      else l.placed("c", member("light.a"), "c_light_a", "<a/>", at(i.toLong))
    }
    assertEquals(churned.mutations.size, 1)
    assertEquals(churned.horizon, 0L)
  }
}
