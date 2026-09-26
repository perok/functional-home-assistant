package fh.view.runtime

/** The per-thread scratch buffer a page open fingerprints each node's patch
  * form into. Its safety conditions are invisible at a call site.
  */
class SinkScratchSuite extends munit.FunSuite {

  test("a borrowed buffer starts empty") {
    // Without the reset a node's digest would include the previous node's
    // bytes, suppressing a later real change rather than showing as garbage.
    val first = Sink.scratched { b => b.append("first"); b.result }
    val second = Sink.scratched { b => b.append("second"); b.result }
    assertEquals(first, "first")
    assertEquals(second, "second")
  }

  test("a nested borrow gets its own buffer, and neither run is spliced") {
    // No current path nests; this keeps nesting from becoming an unwritten
    // precondition, whose failure would be two nodes' bytes concatenated into
    // still well-formed HTML.
    val outer = Sink.scratched { a =>
      a.append("outer-head|")
      val inner = Sink.scratched { b => b.append("inner"); b.result }
      val _ = a.append(inner).append("|outer-tail")
      a.result
    }
    assertEquals(outer, "outer-head|inner|outer-tail")
  }

  test("the borrow survives a throw, and the next one is clean") {
    // A throw mid-node must not leave later borrows on the allocating path.
    val boom =
      try Sink.scratched[String] { b => b.append("half"); sys.error("boom") }
      catch { case e: RuntimeException => e.getMessage }
    assertEquals(boom, "boom")

    val after = Sink.scratched { b => b.append("clean"); b.result }
    assertEquals(after, "clean")
  }

  test("an outsized run does not pin its buffer on the thread") {
    val big = "x" * (128 * 1024)
    val _ = Sink.scratched { b => b.append(big); b.result }
    // The next borrow reuses the same object, which would still hold 128 kB.
    val capacityAfter = Sink.scratched { b => b.capacity }
    assert(
      capacityAfter <= 64 * 1024,
      clue = s"scratch kept ${capacityAfter} chars after an outsized run"
    )
  }
}
