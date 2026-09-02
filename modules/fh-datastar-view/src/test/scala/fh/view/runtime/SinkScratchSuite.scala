package fh.view.runtime

/** The borrowed scratch buffer.
  *
  * A page open rendered every own-rendering node's PATCH form into a throwaway
  * buffer purely to fingerprint it — a `char[1024]` plus its `toString` per
  * node, for bytes nobody keeps. One buffer per thread serves the whole walk
  * instead. Two things have to hold for that to be safe, and neither is visible
  * at a call site.
  */
class SinkScratchSuite extends munit.FunSuite {

  test("a borrowed buffer starts empty") {
    // Without the reset, each node's bytes would carry the previous node's in
    // front of them — and the digest taken from them would be wrong in a way
    // that suppresses a later real change rather than showing up as garbage.
    val first = Sink.scratched { b => b.append("first"); b.result }
    val second = Sink.scratched { b => b.append("second"); b.result }
    assertEquals(first, "first")
    assertEquals(second, "second")
  }

  test("a nested borrow gets its own buffer, and neither run is spliced") {
    // NO current path nests — instrumenting the borrow across the whole suite
    // counts zero — so this is not describing today's walk. It is what keeps
    // "never render into a scratch while holding one" from being an unwritten
    // precondition that the next member-walk change silently breaks: the
    // failure would be two nodes' bytes concatenated, which is still
    // well-formed HTML.
    val outer = Sink.scratched { a =>
      a.append("outer-head|")
      val inner = Sink.scratched { b => b.append("inner"); b.result }
      a.append(inner).append("|outer-tail")
      a.result
    }
    assertEquals(outer, "outer-head|inner|outer-tail")
  }

  test("the borrow survives a throw, and the next one is clean") {
    // `busy` is cleared in a finally: a render that throws mid-node must not
    // leave every later borrow on this thread taking the allocating path.
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
    // The next borrow reuses the SAME scratch object, so if the giant capacity
    // survived, this would still be holding 128 kB of char[].
    val capacityAfter = Sink.scratched { b => b.capacity }
    assert(
      capacityAfter <= 64 * 1024,
      clue = s"scratch kept ${capacityAfter} chars after an outsized run"
    )
  }
}
