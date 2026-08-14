package fh.view.runtime

import fh.view.model.{CardDef, Dashboard, LayoutNode, NodeId, SlotSource}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given

/** Signal slots (ADR 0017): a value that changes without re-rendering its card.
  *
  * The contract is a NEGATIVE one, which is why every test here asserts what is
  * NOT on the wire as well as what is. The failure mode of getting it wrong is
  * invisible in a browser: the card still updates, because the morph the frame
  * was supposed to replace is still being sent — so the feature silently does
  * nothing while every test that only checks the value looks green.
  */
class SignalSlotSuite extends ServerHarness {

  // A card whose reading is signal-backed and whose label is not: the two paths
  // side by side, in one node, so a test can move each independently.
  private val cards = Map(
    "gauge" -> CardDef(
      "<b>{{label}}</b><i {{{value__bind}}}>{{value}}</i>",
      slots = List("label", "value")
    )
  )

  private def gauge(entity: String): LayoutNode.Component =
    LayoutNode.Component(
      "gauge",
      Map(
        "entity_id" -> SlotSource(literal = Some(entity)),
        "label" -> SlotSource(transform = "$attr.friendly_name"),
        "value" -> SlotSource(signal = true)
      )
    )

  private val dash = Dashboard(cards, gauge("sensor.a"))
  private val leaf: NodeId = "c"

  private def at(state: String, name: String = "Hall") =
    Map("sensor.a" -> st("sensor.a", state, "friendly_name" -> name.asJson))

  extension (s: String) private def asJson = io.circe.Json.fromString(s)

  private def renderer = Renderer.create(dash)

  // ---------------------------------------------------------------------------
  // The two forms
  // ---------------------------------------------------------------------------

  test("the document form carries the value inline AND seeds its signal") {
    val html = renderer.renderPage(at("21.4"))
    // Inline, for a browser that will never run a line of JavaScript: the
    // document is the only thing it ever gets.
    assert(html.contains(">21.4<"), clue = html)
    // ...and the seed, so a client that DOES run Datastar is correct before any
    // frame arrives rather than blanking until the first tick.
    assert(html.contains("data-signals=\"{_c__value: '21.4'}\""), clue = html)
    assert(html.contains("data-text=\"$_c__value\""), clue = html)
  }

  test("the patch form carries neither the value nor the seed") {
    val patch = renderer.renderNodeById(leaf, at("21.4")).get
    assertEquals(patch.contains("21.4"), false, clue = patch)
    assertEquals(patch.contains("data-signals"), false, clue = patch)
    // The BINDING stays: it is what the frame feeds, and a morph that dropped
    // it would leave the element inert for good.
    assert(patch.contains("data-text=\"$_c__value\""), clue = patch)
  }

  test("a node with no signal slot renders one form, by reference") {
    val plain = Dashboard(
      Map("plain" -> CardDef("<b>{{value}}</b>", slots = List("value"))),
      LayoutNode.Component(
        "plain",
        Map(
          "entity_id" -> SlotSource(literal = Some("sensor.a")),
          "value" -> SlotSource()
        )
      )
    )
    // Not merely equal — the SAME reference, which is the guard on the cost: a
    // subtree that opted into nothing must not pay for a second template
    // execute. Asserted on ONE walk, because that is the only place the two
    // forms can share a string; two separate calls build two strings whatever
    // the slots say.
    val free = Renderer.create(plain).renderBodyTraced(at("21.4"))
    assert(free.html eq free.patch, clue = "a signal-free node rendered twice")
    // ...and the same walk over the signal card really does produce two.
    val signalled = renderer.renderBodyTraced(at("21.4"))
    assert(signalled.html ne signalled.patch)
    assert(signalled.html.contains("21.4"), clue = signalled.html)
    assertEquals(signalled.patch.contains("21.4"), false, signalled.patch)
  }

  // ---------------------------------------------------------------------------
  // What reaches the wire
  // ---------------------------------------------------------------------------

  /** `holds` as the document left it — the state every live assertion below
    * starts from, because that is what a real client has.
    */
  private def documentHolds(
      r: Renderer,
      states: Map[String, EntityState]
  ): Map[NodeId, Held] =
    r.renderPageTraced(states).own.map { case (id, p) =>
      id -> Held(Some(Digest.of(p.html)), p.signals)
    }

  private def resumeFrom(
      r: Renderer,
      was: Map[String, EntityState],
      now: Map[String, EntityState]
  ): List[Addressed] = {
    val log = FragmentLog("test").touched(leaf, 1L)
    resumeNow(r, log, documentHolds(r, was), now, 1L, Set.empty, Map.empty)
  }

  test("a signal-only change sends a frame and NO element patch") {
    val r = renderer
    val out = resumeFrom(r, at("21.4"), at("21.5"))
    assertEquals(
      out.map(_.patch),
      List(Patch.Signals(Map(Renderer.signalName(leaf, "value") -> "21.5"))),
      clue = events(out).map(_.renderString)
    )
    // The whole point, stated as the absence it is: no card was re-sent.
    assertEquals(elementPatches(events(out)), Nil)
  }

  test("a change to a NON-signal slot still morphs the card") {
    val r = renderer
    val out = resumeFrom(r, at("21.4"), at("21.4", name = "Landing"))
    val morphs = out.map(_.patch).collect { case m: Patch.Morph => m }
    assertEquals(morphs.size, 1, clue = events(out).map(_.renderString))
    assert(morphs.head.html.contains("Landing"), clue = morphs.head.html)
    // ...and no frame rides along, because the reading did not move.
    assertEquals(out.map(_.patch).collect { case s: Patch.Signals => s }, Nil)
  }

  test("a frame is not re-sent for a value the client already holds") {
    val r = renderer
    // Same state on both sides: the node is a candidate (the log names it), it
    // renders, and everything about it is what this viewer holds.
    assertEquals(resumeFrom(r, at("21.4"), at("21.4")), Nil)
  }

  test("the document's holds suppress the first tick's morph") {
    // The invariant `Traced.patch` buys. Seeded from the DOCUMENT form while
    // the pull renders the PATCH form, a mismatch here would send one pointless
    // morph per signal node per page load — correct, but muddying what `holds`
    // means for the rest of the session's life.
    val r = renderer
    val out = resumeFrom(r, at("21.4"), at("21.5"))
    assertEquals(elementPatches(events(out)), Nil)
  }

  // ---------------------------------------------------------------------------
  // The shapes that broke the first design
  // ---------------------------------------------------------------------------

  test("two signal slots on one node share ONE data-signals attribute") {
    // A per-SLOT seed puts two `data-signals` on one element and the browser
    // silently keeps one, so the second slot never updates. Node-level is what
    // makes this work at all.
    val two = Dashboard(
      Map(
        "pair" -> CardDef(
          "<i {{{value__bind}}}>{{value}}</i><u {{{other__bind}}}>{{other}}</u>",
          slots = List("value", "other")
        )
      ),
      LayoutNode.Component(
        "pair",
        Map(
          "entity_id" -> SlotSource(literal = Some("sensor.a")),
          "value" -> SlotSource(signal = true),
          "other" -> SlotSource(
            transform = "$attr.friendly_name",
            signal = true
          )
        )
      )
    )
    val html = Renderer.create(two).renderPage(at("21.4"))
    assertEquals(
      "data-signals".r.findAllIn(html).size,
      1,
      clue = html
    )
    assert(
      html.contains("data-signals=\"{_c__other: 'Hall', _c__value: '21.4'}\""),
      clue = html
    )
  }

  test("a member's children name their signals under the MEMBER") {
    // A member's children have no ids — the member is their patch target — so
    // their signals belong to its namespace and ride on its wrapper. Naming
    // them under a child would seed a signal no element binds.
    val set = LayoutNode.SetNode(
      candidates = List("light.a"),
      members = Map(
        "light.a" -> LayoutNode.SetMember(
          List(
            LayoutNode.SetClause(node =
              LayoutNode.Component(
                "gauge",
                Map(
                  "entity_id" -> SlotSource(literal = Some("light.a")),
                  "label" -> SlotSource(transform = "$attr.friendly_name"),
                  "value" -> SlotSource(signal = true)
                )
              )
            )
          )
        )
      )
    )
    val r = Renderer.create(Dashboard(cards, set))
    val states = Map("light.a" -> st("light.a", "on"))
    val html = r.renderPage(states)
    assert(html.contains("data-text=\"$_c_light_a__value\""), clue = html)
    assertEquals(
      r.signalsFor("c_light_a", states),
      Map(Renderer.signalName("c_light_a", "value") -> "on")
    )
  }

  // ---------------------------------------------------------------------------
  // Validation — both failures are otherwise silent
  // ---------------------------------------------------------------------------

  test("a card that never places the binding is rejected") {
    val unbound = Dashboard(
      Map("gauge" -> CardDef("<i>{{value}}</i>", slots = List("value"))),
      LayoutNode.Component(
        "gauge",
        Map(
          "entity_id" -> SlotSource(literal = Some("sensor.a")),
          "value" -> SlotSource(signal = true)
        )
      )
    )
    assertEquals(
      unbound.validate(),
      List(
        "c: card 'gauge' has slot 'value' marked as a signal slot, but no " +
          "part of its template places {{{value__bind}}} — the value would " +
          "stop updating"
      )
    )
  }

  test("a constant literal cannot be a signal slot") {
    val constant = Dashboard(
      cards,
      LayoutNode.Component(
        "gauge",
        Map(
          "label" -> SlotSource(literal = Some("Hall")),
          "value" -> SlotSource(literal = Some("21.4"), signal = true)
        )
      )
    )
    assertEquals(
      constant.validate(),
      List(
        "c: slot 'value' is a constant literal and cannot be a signal slot " +
          "— a value that never moves has nothing to patch"
      )
    )
  }
}
