package fh.view.runtime

/** The resume cursor's pure core (docs/plan-sse-resume.md). Every failure mode
  * here is SILENT — the server believes the browser is current and suppresses
  * the patch, so the user sees a stale value, a ghost element, or a duplicate
  * indefinitely rather than an error. That is why these are asserted on the
  * value rather than through a booted server.
  */
class FragmentLogSuite extends munit.FunSuite {

  private val log = FragmentLog("test")

  test("a cursor at the current version is owed nothing older") {
    // `>=`, so version 3 is re-sent to a cursor AT 3 (one store version can span
    // several patch batches) — but nothing older is.
    assertEquals(
      log.set("a", "<a/>", 3L).since(3L).fragments.map(_.html),
      List("<a/>")
    )
    assertEquals(log.set("a", "<a/>", 3L).since(4L).fragments, Nil)
  }

  test("fragments come back in version order, oldest first") {
    // A container's cached HTML embeds its children's, so a stale parent applied
    // after a fresh child would revert that child.
    val out = log
      .set("child", "<c v=30/>", 30L)
      .set("parent", "<p v=25/>", 25L)
      .since(1L)
    assertEquals(out.fragments.map(_.version), List(25L, 30L))
  }

  test("a departed member replays as a removal, not a group re-render") {
    // The win over re-rendering the group: one small patch, not 20 cards.
    val out = log.removed("c_light_a", 5L).since(1L)
    assertEquals(out.mutations, List("c_light_a" -> Mutation.Gone(5L)))
    // ...and the stale fragment does not also come back as a morph.
    assertEquals(out.fragments, Nil)
  }

  test("a placed node is reported as a mutation, never as a morph") {
    // Morphing an id the client's DOM lacks silently does nothing, so the
    // fragment must not leak into the morph list.
    val out = log.placed("c", "light.a", "c_light_a", "<a/>", 5L).since(1L)
    assertEquals(
      out.mutations,
      List("c_light_a" -> Mutation.Placed("c", "light.a", 5L))
    )
    assertEquals(out.fragments, Nil)
  }

  test("a rejoin is one mutation, not a gone/placed pair") {
    // The reason these are ONE sum type: a node cannot be both absent and
    // present, so latest wins and the old special case disappears.
    val out = log
      .removed("c_light_a", 10L) // left...
      .placed("c", "light.a", "c_light_a", "<a/>", 20L) // ...and came back
      .since(1L)
    assertEquals(
      out.mutations,
      List("c_light_a" -> Mutation.Placed("c", "light.a", 20L))
    )
  }

  test("leaving after arriving collapses the same way, to Gone") {
    val out = log
      .placed("c", "light.a", "c_light_a", "<a/>", 10L)
      .removed("c_light_a", 20L)
      .since(1L)
    assertEquals(out.mutations, List("c_light_a" -> Mutation.Gone(20L)))
  }

  test("a subtree re-stamp supersedes its stale mutations") {
    // Heavy churn repaints the group wholesale, so its fresh HTML is
    // authoritative: a leftover Gone would delete a rejoined member, and a
    // leftover Placed would insert one the HTML already contains.
    val out = log
      .removed("c_light_a", 10L)
      .placed("c", "light.b", "c_light_b", "<b/>", 11L)
      .invalidateWhere(k => k == "c" || k.startsWith("c_"))
      .set("c", "<c>both present</c>", 20L)
      .since(1L)
    assertEquals(out.mutations, Nil)
    assertEquals(out.fragments.map(_.html), List("<c>both present</c>"))
  }

  test("an ancestor fragment at or after the mutation already carries it") {
    // The hazard the group re-render used to sidestep: an ancestor morph
    // rendered at v=30 contains the member that arrived at v=25, so inserting
    // it again would duplicate the element. Ancestry is a path-id prefix.
    val withAncestor = log
      .placed("c_0", "light.a", "c_0_light_a", "<a/>", 25L)
      .set("c_0", "<group/>", 30L)
    assert(withAncestor.coveredByAncestor("c_0", 25L))
    // An ancestor rendered BEFORE the arrival does not carry it.
    val stale = log
      .placed("c_0", "light.a", "c_0_light_a", "<a/>", 25L)
      .set("c_0", "<group/>", 20L)
    assert(!stale.coveredByAncestor("c_0", 25L))
  }

  test("prefix ancestry does not confuse sibling ids") {
    // `c_1` must not read as an ancestor of `c_10`; the trailing `_` is what
    // prevents it.
    val l = log.set("c_1", "<x/>", 9L)
    assert(!l.coveredByAncestor("c_10", 5L))
    assert(l.coveredByAncestor("c_1_0", 5L))
  }

  test("a cleared log owes nothing but keeps its identity") {
    val before = log
      .set("a", "<a/>", 3L)
      .removed("b", 4L)
      .placed("c", "light.a", "c_light_a", "<a/>", 5L)
    val after = before.cleared
    assertEquals(after.id, before.id) // cursors already issued stay comparable
    assertEquals(after.since(1L), Resume(Nil, Nil), clue = after)
  }
}
