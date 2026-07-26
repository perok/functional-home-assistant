package fh.view.runtime

/** The resume cursor's pure core (docs/plan-sse-resume.md). Every failure mode
  * here is SILENT — the server believes the browser is current and suppresses the
  * patch, so the user sees a stale or ghost element indefinitely rather than an
  * error. That is why these are asserted on the value rather than through a
  * booted server.
  */
class FragmentLogSuite extends munit.FunSuite {

  private val log = FragmentLog("test")

  test("a cursor at the current version is owed nothing") {
    val out = log.set("a", "<a/>", 3L).since(3L)
    // `>=`, so version 3 is re-sent to a cursor AT 3 (one store version can span
    // several patch batches) — but nothing older is.
    assertEquals(out.fragments.map(_.html), List("<a/>"))
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
    // The win over re-rendering: one small patch instead of a whole-group morph.
    val out = log.removed("c_light_a", 5L).since(1L)
    assertEquals(out.removals, List("c_light_a"))
    assertEquals(out.groups, Nil)
    // ...and the stale fragment does not also come back as a morph.
    assertEquals(out.fragments, Nil)
  }

  test("an arriving member re-renders the group instead of replaying") {
    // An `insert` was positioned against a DOM neighbour that may be gone by now.
    val out = log.structuralAt("c", 5L).since(1L)
    assertEquals(out.groups, List("c"))
  }

  test("a group re-render supersedes its own children's fragments") {
    val out = log
      .set("c_light_a", "<stale/>", 4L)
      .structuralAt("c", 5L)
      .since(1L)
    assertEquals(out.groups, List("c"))
    // Redundant: the fresh group render already contains this child.
    assertEquals(out.fragments, Nil)
  }

  test("a member that left and rejoined is NOT removed on resume") {
    // The load-bearing case for dropping a re-rendered group's tombstones: the
    // fresh render contains the rejoined member, so replaying the departure
    // would delete a live element.
    val out = log
      .removed("c_light_a", 10L)   // left...
      .structuralAt("c", 20L)      // ...and came back
      .since(1L)
    assertEquals(out.groups, List("c"))
    assertEquals(out.removals, Nil)
  }

  test("a wholesale group repaint also supersedes a stale tombstone") {
    // Same hazard reached by the other path: heavy churn repaints the group
    // rather than marking it structural, so the prune must clear tombstones too
    // or a rejoined member is deleted on resume.
    val out = log
      .removed("c_light_a", 10L)
      .invalidateWhere(k => k == "c" || k.startsWith("c_"))
      .set("c", "<c>light.a is back</c>", 20L)
      .since(1L)
    assertEquals(out.removals, Nil)
    assertEquals(out.fragments.map(_.html), List("<c>light.a is back</c>"))
  }

  test("a cleared log owes nothing but keeps its identity") {
    val before = log.set("a", "<a/>", 3L).removed("b", 4L).structuralAt("c", 5L)
    val after = before.cleared
    assertEquals(after.id, before.id) // cursors already issued stay comparable
    assertEquals(after.since(1L), Resume(Nil, Nil, Nil))
  }
}
