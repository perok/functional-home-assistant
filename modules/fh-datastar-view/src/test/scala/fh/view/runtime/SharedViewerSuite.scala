package fh.view.runtime

import fh.view.FHError
import fh.view.model.NodeId
import fh.view.testkit.FixtureEntity

import java.util.concurrent.atomic.AtomicInteger

/** What a decision that reads a variable is, once per values: the memo under
  * [[Shared.branchAt]] and its carry ([[ByValues]]), then the recorder's two
  * ends on a real server: viewers on different tabs, and a write racing a
  * frame.
  */
class SharedViewerSuite extends ServerHarness {

  private val gid = NodeId.derived("g")

  private def mode(value: String): VarEnv = Map(gid -> Map("mode" -> value))

  private val a = mode("a")
  private val b = mode("b")

  private val states0 = Map("light.a" -> es("light.a", "on"))
  private val states1 = Map("light.a" -> es("light.a", "off"))
  private val states2 = Map("light.a" -> es("light.a", "unavailable"))

  /** A decision reading `mode`: case 0 at `a`, 1 elsewhere; `derived` counts
    * the derivations.
    */
  private def decide(shared: Shared, env: VarEnv, derived: AtomicInteger) =
    shared
      .viewing(env)
      .branchAt(gid, List("mode"), Set.empty) { in =>
        val _ = derived.incrementAndGet()
        Some(if (in.variable("mode") == "a") 0 else 1)
      }

  private def ask(shared: Shared, env: VarEnv, derived: AtomicInteger): Unit = {
    val _ = decide(shared, env, derived)
  }

  private def decided(marked: Boolean = true) = new Decided(
    Shared.Marked(_ => marked, _ => marked)
  )

  private def frame(states: Map[String, EntityState], version: Long) =
    Frame(Nil, StoreState(states, version))

  private val dirtyHere =
    Shared.Dirty(Set.empty, Set.empty, Set(gid), Set.empty)
  private val clean = Shared.Dirty(Set.empty, Set.empty, Set.empty, Set.empty)

  test("viewers on equal values share one decision, other values their own") {
    val derived = AtomicInteger(0)
    val shared = decided().at(StoreState(states0, 1L))
    assertEquals(decide(shared, a, derived), Some(0))
    assertEquals(
      decide(shared, Map(gid -> Map("mode" -> "a")), derived),
      Some(0)
    )
    assertEquals(derived.get, 1)
    assertEquals(decide(shared, b, derived), Some(1))
    assertEquals(derived.get, 2)
    assertEquals(decide(shared, a, derived), Some(0))
    assertEquals(decide(shared, b, derived), Some(1))
    assertEquals(derived.get, 2)
  }

  test("a decision asked with no viewer raises, naming the missing viewer") {
    val shared = decided().at(StoreState(states0, 1L))
    val e = intercept[FHError](
      shared.branchAt(gid, List("mode"), Set.empty)(_ => Some(0))
    )
    assert(e.getMessage.contains("no viewer"), clue = e.getMessage)
    intercept[FHError](shared.viewer)
  }

  test(
    "a carried decision is kept at every values; a dirty one is dropped at all"
  ) {
    val derived = AtomicInteger(0)
    val d = decided()
    val prev = d.at(StoreState(states0, 1L))
    ask(prev, a, derived)
    ask(prev, b, derived)
    assertEquals(derived.get, 2)

    val kept = d.advance(
      prev,
      frame(states1, 2L),
      clean,
      Shared.Live.Unchanged
    )
    ask(kept, a, derived)
    ask(kept, b, derived)
    assertEquals(derived.get, 2, "nothing dirtied it, so both are carried")

    val dirtied = d.advance(
      kept,
      frame(states2, 3L),
      dirtyHere,
      Shared.Live.Unchanged
    )
    ask(dirtied, a, derived)
    ask(dirtied, b, derived)
    assertEquals(
      derived.get,
      4,
      "a dirty decision is made again at each values"
    )
  }

  test("a decision no marked entity can dirty is never carried") {
    val derived = AtomicInteger(0)
    val d = decided(marked = false)
    val prev = d.at(StoreState(states0, 1L))
    ask(prev, a, derived)
    val next = d.advance(prev, frame(states1, 2L), clean, Shared.Live.Unchanged)
    ask(next, a, derived)
    assertEquals(derived.get, 2)
  }

  test(
    "values no live viewer holds are pruned on carry, and kept while unchanged"
  ) {
    val derived = AtomicInteger(0)
    val d = decided()
    val prev = d.at(StoreState(states0, 1L))
    ask(prev, a, derived)
    ask(prev, b, derived)
    assertEquals(derived.get, 2)

    val unchanged =
      d.advance(prev, frame(states1, 2L), clean, Shared.Live.Unchanged)
    ask(unchanged, a, derived)
    ask(unchanged, b, derived)
    assertEquals(derived.get, 2, "an unchanged live set prunes nothing")

    val pruned = d.advance(
      unchanged,
      frame(states2, 3L),
      clean,
      Shared.Live.Now(List(a))
    )
    ask(pruned, a, derived)
    assertEquals(derived.get, 2, "the live values stay carried")
    ask(pruned, b, derived)
    assertEquals(derived.get, 3, "the values nobody holds were dropped")
  }

  private val abStates = Map(
    "sensor.shared" -> es("sensor.shared", "S0"),
    "sensor.a" -> es("sensor.a", "A0"),
    "sensor.b" -> es("sensor.b", "B0")
  )

  test(
    "two viewers on two tabs: a change in a tab's panel reaches only its viewer"
  ) {
    live(twoTabsDash, abStates) { ts =>
      for {
        onTab0 <- ts.viewer()
        onTab1 <- ts.viewer("?v.c_1.tab=1")
        _ <- ts.record(FixtureEntity("sensor.a", "A1"))
        a0 <- onTab0.pull
        a1 <- onTab1.pull
        _ <- ts.record(FixtureEntity("sensor.b", "B1"))
        b0 <- onTab0.pull
        b1 <- onTab1.pull
      } yield {
        assert(a0.exists(_.render.contains("A1")), clue = a0)
        assertEquals(a1, Nil)
        assertEquals(b0, Nil)
        assert(b1.exists(_.render.contains("B1")), clue = b1)
      }
    }
  }

  test("a viewer that revealed new values is watched under both") {
    live(twoTabsDash, abStates) { ts =>
      val tab = VarKey(NodeId.derived("c_1"), "tab")
      for {
        v <- ts.viewer()
        before <- v.session.watching.map(_.map(_.values))
        _ <- v.session.reveal(Set.empty, Map(tab -> "1"))
        during <- v.session.watching.map(_.map(_.values))
        all <- ts.sessions.watching(ts.slug).map(_.map(_.values))
      } yield {
        assertEquals(before, List(Map.empty[VarKey, String]))
        assertEquals(
          during.toSet,
          Set(Map.empty[VarKey, String], Map(tab -> "1"))
        )
        assertEquals(all.toSet, during.toSet)
      }
    }
  }

  test(
    "a frame recorded while a write is revealed logs the panel it is moving to"
  ) {
    live(twoTabsDash, abStates) { ts =>
      val tab = VarKey(NodeId.derived("c_1"), "tab")
      for {
        v <- ts.viewer()
        renderer <- ts.server
          .liveSlug(ts.slug)
          .flatMap(_.renderer.get)
          .map(_.rendererOf.get)
        panelB = renderer.picks
          .containersSpelled("c_1_panel_0_1")
          .flatMap(renderer.containerComponentsFor(_, "sensor.b"))
          .toList
          .flatMap(renderer.index.ixsOf)
        _ <- ts.record(FixtureEntity("sensor.b", "B1"))
        beforeWrite <- ts.log
        // The write's step has revealed tab 1 and not yet published it.
        _ <- v.session.reveal(Set.empty, Map(tab -> "1"))
        _ <- ts.record(FixtureEntity("sensor.b", "B2"))
        afterReveal <- ts.log
      } yield {
        assert(panelB.nonEmpty, clue = "sensor.b is on no node")
        assert(
          panelB.forall(ix => !beforeWrite.fragments.contains(ix)),
          clue =
            ("a viewer on tab 0 needs no tab 1 node", beforeWrite.fragments)
        )
        assert(
          panelB.forall(ix => afterReveal.fragments.contains(ix)),
          clue = ("the revealed values are recorded for", afterReveal.fragments)
        )
      }
    }
  }
}
