package fh.view.runtime

import fh.view.model.Ix

/** The resume cursor's pure core (ADR 0011). Every failure here is silent (a
  * stale value, a ghost element, a duplicate), so these assert on the value.
  */
class FragmentLogSuite extends munit.FunSuite {

  private val log = FragmentLog("test")

  private val a = Ix(0)
  private val child = Ix(1)
  private val parent = Ix(2)
  private val c0 = Ix(3)
  private val c1 = Ix(4)

  test("a cursor at the current version is owed nothing older") {
    // `>=`: one store version can span several batches, so 3 is re-sent to a
    // cursor at 3, but nothing older is.
    assertEquals(log.touched(a, 3L).since(3L), Set(a))
    assertEquals(log.touched(a, 3L).since(4L), Set.empty[Ix])
  }

  test("the log names nodes, never their content") {
    // A place rendered from the current snapshot, which is what lets the log
    // store a version instead of bytes.
    val out = log
      .touched(child, 30L)
      .touched(parent, 25L)
      .since(1L)
    assertEquals(out, Set(child, parent))
  }

  test("a stretch nobody watched drops the history it made unreachable") {
    // Follows from the gate: after a skip `reaches` refuses every cursor at or
    // below it, and later sessions start above it.
    val busy = log
      .touched(c0, 3L)
      .touched(c1, 6L)
    val idle = busy.skipped(9L)
    assertEquals(idle.fragments, Map.empty[Ix, Long])
    // A cursor naming this log is refused on its version, not mistaken for
    // another log's.
    assertEquals(idle.id, busy.id)
    // A client complete through 9 needs (9, now], and nothing in that range was
    // lost: what the ordering argument rests on.
    assert(idle.reaches(9L))
    assert(!idle.reaches(8L), clue = "but anything below it must repaint")
  }

  /** A recorder's first frame is the state it subscribed at, recorded nowhere,
    * so a page served before it holds a version the log cannot lead on from.
    */
  test("a fresh log reaches no cursor until seeded, then from its seed on") {
    val mine = Renderer.Stamp("head", "style", Map.empty)
    val fresh = FragmentLog.fresh("f", Some(mine))
    assert(!fresh.reaches(0L) && !fresh.reaches(1000L), clue = fresh)
    val seeded = fresh.seeded(7L)
    assert(seeded.reaches(7L))
    assert(!seeded.reaches(6L), clue = "missed 7 itself")
    // A skip keeps who numbers it, or the recorder refuses its own log after.
    assert(seeded.skipped(9L).numberedBy(mine))
    assert(!seeded.numberedBy(Renderer.Stamp("head", "style", Map.empty)))
    assertEquals(seeded.idUnder(mine), "f")
    assertEquals(
      seeded.idUnder(Renderer.Stamp("head", "style", Map.empty)),
      FragmentLog.NoLog
    )
  }

  test("re-touching a node does not grow the map") {
    // Latest-wins per node keeps a churning entity from filling the map, and a
    // later version is never moved back.
    val churned = (1 to 1000).foldLeft(log)((l, i) => l.touched(c0, i.toLong))
    assertEquals(churned.fragments.size, 1)
    assertEquals(churned.touched(c0, 3L).since(1000L), Set(c0))
  }
}
