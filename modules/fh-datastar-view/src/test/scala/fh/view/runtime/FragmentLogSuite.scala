package fh.view.runtime

import fh.view.model.NodeId
import fh.view.testkit.TestIds.given

/** The resume cursor's pure core (ADR 0011). Every failure here is silent (a
  * stale value, a ghost element, a duplicate), so these assert on the value.
  */
class FragmentLogSuite extends munit.FunSuite {

  private val log = FragmentLog("test")

  test("a digest is the truncated SHA-256, whatever the encoding costs") {
    // `Digest.of` hand-rolls the hex encoding to skip a `String.format` per
    // byte, safe only while it agrees exactly: a different encoding would make
    // every node look changed forever. Empty and multi-byte cases cover the two
    // places a hand-rolled version differs: a sign-extended byte (`ffffffab`)
    // and the platform charset in `.getBytes`.
    List(
      "",
      "<div>21.4</div>",
      "<b>ø 😀 ünïcødé</b>",
      "<div class=\"fh-cell\" id=\"c_1_2\">" + ("x" * 5000) + "</div>"
    ).foreach { html =>
      assertEquals(
        // `Digest` is opaque with no `String` bound; at runtime it is the hex
        // string.
        Digest.of(html).toString,
        fh.view.build.LibPackage.sha256(html.getBytes("UTF-8")).take(32),
        clue = html.take(40)
      )
    }
  }

  private def member(e: String): MemberKey = MemberKey.Entity(e)

  /** A literal needs the [[NodeId]] conversion applied explicitly on the key
    * side.
    */
  private def moved(id: NodeId, m: Mutation): (NodeId, Mutation) = id -> m

  /** The conversion does not reach inside a tuple, so the expected side reads
    * as plain strings.
    */
  private def horizonOf(l: FragmentLog): Map[String, Long] =
    l.horizon.map { case (k, v) => (k: String) -> v }

  /** `since` is total: an aged-out container is answered with a `refill`, never
    * a refusal.
    */
  extension (l: FragmentLog) {
    // The ids here are hand-written and positional, so their spelling is a
    // sound source for the relation (see [[TestAncestry]]).
    private def owed(v: Long): Resume = l.since(v, TestAncestry.of(l))
    private def filledAt(container: NodeId, at: Long): FragmentLog =
      l.filled(container, at, TestAncestry.of(l))
  }

  test("a cursor at the current version is owed nothing older") {
    // `>=`: one store version can span several batches, so 3 is re-sent to a
    // cursor at 3, but nothing older is.
    assertEquals(log.touched("a", 3L).owed(3L).nodes, List[NodeId]("a"))
    assertEquals(log.touched("a", 3L).owed(4L).nodes, Nil)
  }

  test("the log names nodes, never their content") {
    // Statement (3): a node id rendered from the current snapshot, which is
    // what lets the log store a version instead of bytes.
    val out = log
      .touched("child", 30L)
      .touched("parent", 25L)
      .owed(1L)
    assertEquals(out.nodes.toSet, Set[NodeId]("child", "parent"))
  }

  test("a departed member replays as a removal, not a group re-render") {
    // One small patch, not a re-rendered group.
    val out = log.removed("c", "c_light_a", 5L).owed(1L)
    assertEquals(
      out.moved,
      List(moved("c_light_a", Mutation.Gone("c", 5L)))
    )
    assertEquals(out.nodes, Nil)
  }

  test("a placed node is reported as a mutation, never as a morph") {
    // Morphing an id the client's DOM lacks silently does nothing.
    val out =
      log.placed("c", member("light.a"), "c_light_a", 5L).owed(1L)
    assertEquals(
      out.moved,
      List(moved("c_light_a", Mutation.Placed("c", member("light.a"), 5L)))
    )
    assertEquals(out.nodes, Nil)
  }

  test("a rejoin is one mutation, not a gone/placed pair") {
    // One sum type: a node cannot be both absent and present, so latest wins.
    val out = log
      .removed("c", "c_light_a", 10L) // left...
      .placed(
        "c",
        member("light.a"),
        "c_light_a",
        20L
      ) // ...came back
      .owed(1L)
    assertEquals(
      out.moved,
      List(moved("c_light_a", Mutation.Placed("c", member("light.a"), 20L)))
    )
  }

  test("leaving after arriving collapses the same way, to Gone") {
    val out = log
      .placed("c", member("light.a"), "c_light_a", 10L)
      .removed("c", "c_light_a", 20L)
      .owed(1L)
    assertEquals(
      out.moved,
      List(moved("c_light_a", Mutation.Gone("c", 20L)))
    )
  }

  test("a member key carries HOW to resolve it, not just its name") {
    // A candidate set's member is an entity, a state group's a branch surface,
    // and each resolves differently.
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
    // The wholesale repaint's HTML is authoritative: a leftover Gone would
    // delete a rejoined member, a leftover Placed insert a duplicate.
    val out = log
      .removed("c", "c_light_a", 10L)
      .placed("c", member("light.b"), "c_light_b", 11L)
      .invalidateWhere(k => k == "c" || k.startsWith("c_"))
      .touched("c", 20L)
      .owed(1L)
    assertEquals(out.moved, Nil)
    assertEquals(out.nodes, List[NodeId]("c"))
  }

  test("a mutation re-supplying an ancestor covers everything under it") {
    // A placed branch root carries its subtree, whose ids the client's DOM does
    // not hold yet, so morphs for them would be silent no-ops.
    val flipped = log
      .placed(
        "c_0",
        MemberKey.Surface("else"),
        "s_else__c",
        30L
      )
      .touched("s_else__c_0", 30L)
      .touched("s_else__c_1", 30L)
    assert(
      flipped.coveredByMutation(
        "s_else__c_0",
        Set[NodeId]("s_else__c"),
        TestAncestry.of(flipped)
      )
    )
    val out = flipped.owed(20L)
    assertEquals(out.nodes, Nil, clue = out)
    assertEquals(out.moved.map(_._1), List[NodeId]("s_else__c"))
  }

  test(
    "a FRAGMENT ancestor covers nothing — no fragment contains another node"
  ) {
    // A patch is a node's own element, never a region's contents, so an
    // ancestor's entry cannot carry a descendant: both are sent on their own
    // ids.
    val out = log
      .touched("c_0_1", 20L)
      .touched("c_0", 25L)
      .owed(1L)
    assertEquals(out.nodes.toSet, Set[NodeId]("c_0", "c_0_1"))
  }

  test("a node never covers itself") {
    // Self-coverage would make every mutation suppress its own emission.
    assert(
      !log.coveredByMutation(
        "c_0",
        Set[NodeId]("c_0"),
        TestAncestry.of(Set[NodeId]("c", "c_0"))
      )
    )
    val l = log.removed("c", "c_0", 5L)
    assertEquals(l.owed(1L).moved.map(_._1), List[NodeId]("c_0"))
  }

  test("ancestry does not confuse sibling ids") {
    // `c_1` must not cover `c_10`: a claim about the tree, since `c_10` is a
    // sibling whatever the spelling.
    val tree = TestAncestry.of(Set[NodeId]("c", "c_1", "c_10", "c_1_0"))
    assert(!log.coveredByMutation("c_10", Set[NodeId]("c_1"), tree))
    assert(log.coveredByMutation("c_1_0", Set[NodeId]("c_1"), tree))
  }

  test("a removal under a re-supplied ancestor is not replayed") {
    // The placement's fresh render omits the departed node, so replaying the
    // removal would delete an element that render restored.
    val out = log
      .removed("c_0", "c_0_light_a", 20L)
      .placed("c", MemberKey.Surface("b"), "c_0", 25L)
      .owed(1L)
    assertEquals(out.moved.map(_._1), List[NodeId]("c_0"))
  }

  test("a removal with no mutated ancestor is sent") {
    val out = log
      .touched("c_0", 25L)
      .removed("c_0", "c_0_light_a", 30L)
      .owed(1L)
    assertEquals(out.moved.map(_._1), List[NodeId]("c_0_light_a"))
  }

  test("a content tick after a placement rides the mutation") {
    // One insert, not a morph and an insert; its content is rendered fresh.
    val l = log
      .placed("c", member("light.a"), "c_light_a", 20L)
      .touched("c_light_a", 30L)
    val fromBefore = l.owed(1L)
    assertEquals(fromBefore.nodes, Nil)
    assertEquals(fromBefore.moved.map(_._1), List[NodeId]("c_light_a"))
    val fromAfter = l.owed(25L)
    assertEquals(fromAfter.moved, Nil)
    assertEquals(fromAfter.nodes, List[NodeId]("c_light_a"))
  }

  test("mutations below the floor are pruned") {
    // A `Gone` for a member that never returns has nothing else to remove it.
    // The floor does, exactly: the lowest position any live session holds.
    val churned = log
      .removed("c", "c_old", 5L)
      .removed("c", "c_new", 9L)
    val pruned = churned.pruned(7L)
    assertEquals(pruned.mutations.keySet, Set("c_new"))
    // A client cursor is not bounded by the floor, so one below this still gets
    // a refill rather than silence.
    assertEquals(horizonOf(pruned), Map("c" -> 6L))
  }

  test("one container being pruned says nothing about any other") {
    // Per container, so one churning group's expired history costs only that
    // group, not every client a whole-body repaint.
    val evicted = log
      .removed("c_0", "c_0_old", 5L)
      .removed("c_1", "c_1_new", 9L)
      .pruned(7L)
    assertEquals(horizonOf(evicted), Map("c_0" -> 6L))
    assertEquals(evicted.owed(5L).refill, List[NodeId]("c_0"))
    assertEquals(evicted.owed(6L).refill, Nil)
    assertEquals(evicted.owed(5L).moved.map(_._1), List[NodeId]("c_1_new"))
  }

  test("a refilled container's members are not ALSO sent") {
    // The same prefix test a `Placed` goes through.
    val evicted = log
      .removed("c_0", "c_0_old", 5L)
      .removed("c_1", "c_1_x", 9L)
      .pruned(7L)
      .touched("c_0_light_a", 9L)
    val out = evicted.owed(5L)
    assertEquals(out.refill, List[NodeId]("c_0"))
    assertEquals(out.nodes, Nil, clue = out)
  }

  test("a mutation at the floor survives") {
    // `< floor`: a session at P resumes from P + 1, but a client cursor at P
    // asks for `>= P`.
    val kept = log
      .removed("c", "c_old", 5L)
      .removed("c", "c_new", 9L)
      .pruned(5L)
    assertEquals(kept.mutations.size, 2)
    assertEquals(horizonOf(kept), Map.empty[String, Long])
  }

  test("a stretch nobody watched drops the history it made unreachable") {
    // Follows from the gate: after a skip `reaches` refuses every cursor at or
    // below it, and later sessions start above it.
    val busy = log
      .touched("c_0", 3L)
      .removed("c", "c_old", 5L)
      .filledAt("c_1", 6L)
    val idle = busy.skipped(9L)
    assertEquals(idle.mutations, Map.empty[NodeId, Mutation])
    assertEquals(idle.fragments, Map.empty[NodeId, Long])
    assertEquals(horizonOf(idle), Map.empty[String, Long])
    // A cursor naming this log is refused on its version, not mistaken for
    // another log's.
    assertEquals(idle.id, busy.id)
    // A client complete through 9 needs (9, now], and nothing in that range was
    // lost: what the ordering argument rests on.
    assert(idle.reaches(9L))
    assert(!idle.reaches(8L), clue = "but anything below it must repaint")
  }

  test("re-touching a node does not grow the map, so pruning stays rare") {
    // Latest-wins per node keeps a churning entity from filling the map.
    val churned = (1 to 1000).foldLeft(log) { (l, i) =>
      if (i % 2 == 0) l.removed("c", "c_light_a", i.toLong)
      else l.placed("c", member("light.a"), "c_light_a", i.toLong)
    }
    assertEquals(churned.mutations.size, 1)
    assertEquals(horizonOf(churned), Map.empty[String, Long])
  }
}
