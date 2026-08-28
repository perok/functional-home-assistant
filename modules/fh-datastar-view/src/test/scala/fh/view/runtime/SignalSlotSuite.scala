package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  SetId,
  SignalBind,
  SlotSource
}
import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.FakeHomeAssistant
import fs2.concurrent.SignallingRef
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth

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
        "value" -> SlotSource(signal = Some(SignalBind.Text))
      )
    )

  private val dash = Dashboard(cards, gauge("sensor.a"))
  private val leaf: NodeId = "c"

  private def at(state: String, name: String = "Hall") =
    Map("sensor.a" -> st("sensor.a", state, "friendly_name" -> name.asJson))

  extension (s: String) private def asJson = io.circe.Json.fromString(s)

  private def renderer = Renderer.create(dash)

  /** A frame's expected payload. Slot values are always strings on the wire;
    * the `Json` in [[Patch.Signals]] is there so the CURSOR — a nested object —
    * can ride in the same patch kind and merge with one.
    */
  private def frame(kv: (fh.view.model.SignalId, String)*): Patch.Signals =
    Patch.Signals(kv.map { case (k, v) =>
      k -> io.circe.Json.fromString(v)
    }.toMap)

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
      List(frame(Renderer.signalName(leaf, "value") -> "21.5")),
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
          "value" -> SlotSource(signal = Some(SignalBind.Text)),
          "other" -> SlotSource(
            transform = "$attr.friendly_name",
            signal = Some(SignalBind.Text)
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
                  "value" -> SlotSource(signal = Some(SignalBind.Text))
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
  // The slider: four moving slots, three binding kinds, one frame
  // ---------------------------------------------------------------------------

  /** The shipped slider's shape, reduced to what matters here: everything that
    * moves when brightness does. A reading (text), the input's position
    * (two-way bind), the track fill (a custom property) and its colour (an
    * ordinary one). Getting any ONE of them wrong re-renders the card and the
    * other three buy nothing — which is why this asserts on the whole set.
    */
  private val sliderish = Dashboard(
    Map(
      "slider" -> CardDef(
        """<div class="slider" style="--_end: {{fill}}" {{{fill__bind}}}>""" +
          """<span class="state" {{{state__bind}}}>{{state}}</span>""" +
          """<input type="range" value="{{value}}" {{{value__bind}}}""" +
          """ data-on:change="@post('x/' + ${{value__signal}})" />""" +
          """<span style="background:{{tint}}" {{{tint__bind}}}></span></div>""",
        slots = List("state", "value", "fill", "tint")
      )
    ),
    LayoutNode.Component(
      "slider",
      Map(
        "entity_id" -> SlotSource(literal = Some("light.a")),
        "state" -> SlotSource(
          transform = "$attr.brightness",
          signal = Some(SignalBind.Text)
        ),
        "value" -> SlotSource(
          transform = "$attr.brightness",
          signal = Some(SignalBind.Bind)
        ),
        "fill" -> SlotSource(
          transform = "$string($attr.brightness) & \"%\"",
          signal = Some(SignalBind.Style("--_end"))
        ),
        "tint" -> SlotSource(
          transform = "$attr.rgb_color",
          signal = Some(SignalBind.Attr("title"))
        )
      )
    )
  )

  private def lit(brightness: Int) =
    Map(
      "light.a" -> st(
        "light.a",
        "on",
        "brightness" -> io.circe.Json.fromInt(brightness),
        "rgb_color" -> "warm".asJson
      )
    )

  test("each binding kind renders its own Datastar attribute") {
    val html = Renderer.create(sliderish).renderPage(lit(40))
    // Text, two-way, one custom property, one attribute — and the two-way one
    // takes the signal's NAME rather than a `$`-read, because it writes back.
    assert(html.contains("""data-text="$_c__state""""), clue = html)
    assert(html.contains("""data-bind="_c__value""""), clue = html)
    assert(html.contains("""data-style:--_end="$_c__fill""""), clue = html)
    assert(html.contains("""data-attr:title="$_c__tint""""), clue = html)
    // The value still lands inline in every position, for the reader that will
    // never run any of the above.
    assert(html.contains("--_end: 40%"), clue = html)
    assert(html.contains("""value="40""""), clue = html)
    // ...and the action URL composes the signal by name, which is the one thing
    // a canned binding cannot do for it.
    assert(html.contains("""@post('x/' + $_c__value)"""), clue = html)
  }

  test("a brightness tick moves four values and sends no element patch") {
    val r = Renderer.create(sliderish)
    val log = FragmentLog("test").touched(leaf, 1L)
    val out = resumeNow(
      r,
      log,
      r.renderPageTraced(lit(40)).own.map { case (id, p) =>
        id -> Held(Some(Digest.of(p.html)), p.signals)
      },
      lit(41),
      1L,
      Set.empty,
      Map.empty
    )
    assertEquals(
      out.map(_.patch),
      List(
        frame(
          Renderer.signalName(leaf, "state") -> "41",
          Renderer.signalName(leaf, "value") -> "41",
          Renderer.signalName(leaf, "fill") -> "41%"
        )
      ),
      clue = events(out).map(_.renderString)
    )
    // `tint` did not move, so it is not in the frame even though its node was.
    assertEquals(elementPatches(events(out)), Nil)
  }

  // ---------------------------------------------------------------------------
  // One frame per batch, and what a departure carries
  // ---------------------------------------------------------------------------

  /** Two signal-backed leaves under a BARE container — no rendering of its own,
    * so the leaves are the patch units and anything merging has to reach both.
    */
  private val twoNodes = Dashboard(
    cards + ("col" -> CardDef(
      "<div>{{#children}}{{{html}}}{{/children}}</div>",
      regions = Map("children" -> Region())
    )),
    LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(gauge("sensor.a"), gauge("sensor.b"))
    )
  )

  private def both(a: String, b: String) =
    Map(
      "sensor.a" -> st("sensor.a", a, "friendly_name" -> "A".asJson),
      "sensor.b" -> st("sensor.b", b, "friendly_name" -> "B".asJson)
    )

  test("a frame MERGES every node the batch touched") {
    // One frame per batch, not one per node: `signalFrame` collects across all
    // candidates before emitting. Two entities moving in one HA frame is the
    // normal case, not an edge one.
    val r = Renderer.create(twoNodes)
    val log = FragmentLog("test")
      .touched(NodeId.derived("c_0"), 1L)
      .touched(NodeId.derived("c_1"), 1L)
    val held = r.renderPageTraced(both("1", "2")).own.map { case (id, p) =>
      id -> Held(Some(Digest.of(p.html)), p.signals)
    }
    val out =
      resumeNow(r, log, held, both("9", "8"), 1L, Set.empty, Map.empty)
    assertEquals(
      out.map(_.patch),
      List(
        frame(
          Renderer.signalName("c_0", "value") -> "9",
          Renderer.signalName("c_1", "value") -> "8"
        )
      ),
      clue = events(out).map(_.renderString)
    )
  }

  test("coalesced versions collapse to one frame carrying the LATEST value") {
    // The doorbell is a SignallingRef, so versions landing while a session
    // renders collapse into one pull — a slow client gets one wake, not a
    // backlog. Two things make that safe, and neither is a "keep the latest"
    // rule anyone had to write:
    //
    //   - the pull selects from `position + 1`, NOT from the version it woke
    //     for, so a skipped doorbell value drops no CANDIDATES;
    //   - the log holds versions, never values, so a frame is rendered from the
    //     CURRENT snapshot. There is no intermediate value stored anywhere that
    //     could be served by mistake.
    val r = renderer
    // The entity moved at 1 and again at 2; this session saw neither.
    val log = FragmentLog("test").touched(leaf, 1L).touched(leaf, 2L)
    val out = resumeNow(
      r,
      log,
      documentHolds(r, at("21.4")),
      at("21.6"), // where it ended up, two moves later
      1L,
      Set.empty,
      Map.empty
    )
    assertEquals(
      out.map(_.patch),
      List(frame(Renderer.signalName(leaf, "value") -> "21.6")),
      clue = events(out).map(_.renderString)
    )
  }

  test("three frames over two nodes, then ONE pull: one event, both nodes") {
    // The coalescing case driven through the REAL `Server.pull` rather than
    // `Patches.resume`: three versions reach the changelog before this session
    // pulls at all — TWO of them the same node, the third a different one — and
    // the one pull has to cover all of it in a single frame.
    //
    // The two axes together, which is the point: merging ACROSS versions (a
    // node that moved twice contributes its newest value, once) and merging
    // ACROSS nodes (both leaves in one frame, not one frame each).
    //
    // Deterministic on purpose. The fully end-to-end version — three
    // `store.update`s racing one session's pull fiber — cannot assert "it
    // pulled once", because whether that fiber is scheduled in between is
    // exactly the nondeterminism `LiveWorld.settle` exists to remove; the
    // assertion would be testing the scheduler. What is decidable is what
    // `pull` does GIVEN a log several versions ahead of the session.
    (for {
      // The store holds where the entities ENDED UP; the log says how often
      // they moved. That is the real shape of a coalesced wake — no
      // intermediate value was written down anywhere (§6), which is why none
      // can be served by mistake.
      store <- StateStore.inMemory(both("21.6", "48"))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(twoNodes))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          for {
            renderer <- ref.get.map(_.rendererOf.get)
            live <- server.liveSlug("dashboard")
            session <- Session.create("dashboard")
            _ <- session.holds.set(
              documentHolds(renderer, both("21.4", "44"))
            )
            _ <- sessions.register("c1", session)
            // sensor.a moved at 1 and again at 2; sensor.b at 3.
            _ <- live.log.update(
              _.touched(NodeId.derived("c_0"), 1L)
                .touched(NodeId.derived("c_0"), 2L)
                .touched(NodeId.derived("c_1"), 3L)
            )
            // ONE pull, woken for the NEWEST version — the doorbell having
            // coalesced 1 and 2 into 3.
            first <- server.pull(live, session, 3L)
            position <- session.position.get
            // ...and the gate: nothing is owed for a version already served.
            again <- server.pull(live, session, 3L)
          } yield (first, position, again)
        }
    } yield out).map { case (first, position, again) =>
      // ONE event, and the whole of it: both nodes' newest values and the
      // cursor, merged, because a value tick puts no element patch between the
      // frame and the cursor. `c_0` appears ONCE despite having moved twice.
      assertEquals(
        first.map(_.data),
        List(
          Some(
            """signals {"_c_0__value":"21.6","_c_1__value":"48",""" +
              """"_cursor":{"storeVersion":3}}"""
          )
        ),
        clue = first.map(_.renderString)
      )
      assertEquals(position, 3L)
      assertEquals(again, Nil)
    }
  }

  test("the cursor merges into a signals-only batch, and not past a morph") {
    // A value tick is ONE event on the wire, not a frame followed by a cursor
    // frame. `encode` merges adjacent signal patches the way it merges adjacent
    // morphs, and the cursor rides as a patch so it lands in that merge.
    val values = frame(Renderer.signalName(leaf, "value") -> "21.5")
    val cursor = Server.versionPatch(27L)
    assertEquals(
      Patches.encode(List(Addressed(values), Addressed(cursor))).map(_.data),
      List(
        Some(
          """signals {"_c__value":"21.5","_cursor":{"storeVersion":27}}"""
        )
      )
    )
    // ...but an element patch between them keeps them apart, which the cursor's
    // meaning REQUIRES: echoing it is a claim to have applied what came before
    // it, so it must not overtake a morph (ADR 0011).
    val withMorph = Patches.encode(
      List(
        Addressed(values),
        Addressed(Patch.Morph("<div id=\"c\"></div>")),
        Addressed(cursor)
      )
    )
    assertEquals(withMorph.size, 3, clue = withMorph.map(_.renderString))
    assertEquals(withMorph.last.data, Some(cursor.toSse.data.get))
  }

  test("a member leaving sends the remove and NO signal for it") {
    // A departing node is not a candidate — `signalFrame` reads the ids the
    // batch renders, and a `Gone` is not among them — so nothing is sent for a
    // value that has left the DOM.
    //
    // The client's signal store KEEPS that value, and that is why this is safe
    // rather than merely cheap: signals outlive the elements bound to them, so
    // if the member returns with the same value its re-inserted element (which
    // is patch-form, and carries no seed) reads a store that is still correct.
    // A different value is a difference this session's record can see, so the
    // frame carries it. What leaks is one entry per departed member on both
    // sides, bounded by the dashboard — a set's candidates are static (ADR
    // 0003), so there is no unbounded set of names to accumulate.
    def member(id: String) = LayoutNode.Component(
      "gauge",
      Map(
        "entity_id" -> SlotSource(literal = Some(id)),
        "label" -> SlotSource(transform = "$attr.friendly_name"),
        "value" -> SlotSource(signal = Some(SignalBind.Text))
      )
    )
    // TWO candidates, because one leaving an otherwise-empty set is the
    // wholesale-refill path instead (`now.isEmpty`) and says nothing about a
    // per-member departure.
    val set = LayoutNode.SetNode(
      candidates = List("light.a", "light.b"),
      members = List("light.a", "light.b").map { id =>
        id -> LayoutNode.SetMember(
          List(LayoutNode.SetClause(when = Some(isOn), node = member(id)))
        )
      }.toMap
    )
    val r = Renderer.create(Dashboard(cards, set))
    val on =
      Map("light.a" -> st("light.a", "on"), "light.b" -> st("light.b", "on"))
    val gone = on.updated("light.a", st("light.a", "off"))
    // The graph has to have SEEN the members before it can report one leaving,
    // and the LOG has to know them or the group is not "established" and a
    // departure fills the host wholesale instead of emitting a delta.
    val _ = r.members.syncMembers(Nil, on, on)
    val held = r.renderPageTraced(on).own.map { case (id, p) =>
      id -> Held(Some(Digest.of(p.html)), p.signals)
    }
    val seededLog = List("c_light_a", "c_light_b")
      .foldLeft(FragmentLog("test"))((l, id) =>
        l.touched(NodeId.derived(id), 0L)
      )
    val delta = r.members.syncMembers(
      List(
        StateChange("light.a", Some(st("light.a", "on")), st("light.a", "off"))
      ),
      on,
      gone
    )
    val log = Patches.record(
      r,
      seededLog,
      Patches.DiffRequest(
        staticIds = Nil,
        sets =
          List((SetId.of(NodeId.derived("c"), LayoutNode.SetNode()), None)),
        flips = Nil,
        changes = Nil,
        states = gone,
        before = on,
        membership = delta,
        at = 1L
      )
    )
    val out = resumeNow(r, log, held, gone, 1L, Set.empty, Map.empty)
    assertEquals(
      out.map(_.patch),
      List(Patch.Remove(r.elementId("c_light_a"))),
      clue = events(out).map(_.renderString)
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
          "value" -> SlotSource(signal = Some(SignalBind.Text))
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
          "value" -> SlotSource(
            literal = Some("21.4"),
            signal = Some(SignalBind.Text)
          )
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

  // ---------------------------------------------------------------------------
  // Signals on STRUCTURE
  // ---------------------------------------------------------------------------

  /** A card that holds a region AND carries a signal slot of its own.
    *
    * Structure is never a patch target, so a live BYTES slot on one is a build
    * error — but a signal is not bytes, and the error's own advice was to reach
    * for exactly this. It was rejected anyway, and the runtime half agreed:
    * `signalsFor` gated on `hasOwnRendering`, so had validate let it through,
    * the seed would have been written once and then never updated.
    *
    * Both halves are asserted below, because either one alone is the silent
    * failure this suite exists to catch.
    */
  private val structural = Dashboard(
    cards + ("frame" -> CardDef(
      "<section {{{tint__bind}}}>{{#body}}{{{html}}}{{/body}}</section>",
      slots = List("tint"),
      regions = Map("body" -> Region())
    )),
    LayoutNode.Component(
      "frame",
      Map(
        "entity_id" -> SlotSource(literal = Some("sensor.a")),
        "tint" -> SlotSource(
          transform = "$attr.tint",
          signal = Some(SignalBind.Style("background"))
        )
      ),
      regions = Map("body" -> List(gauge("sensor.a")))
    )
  )

  private def tinted(tint: String, value: String = "21.4") =
    Map(
      "sensor.a" -> st(
        "sensor.a",
        value,
        "friendly_name" -> "Hall".asJson,
        "tint" -> tint.asJson
      )
    )

  test("a signal slot on a structural card is accepted") {
    assertEquals(structural.validate(), Nil)
  }

  test("a live BYTES slot on a structural card is still rejected") {
    // The rule did not go, it narrowed — and this is the half it still owns:
    // `tint` as bytes could only reach the DOM by patching the section, which
    // would carry the region's whole content back with it.
    val bytes = structural.copy(card =
      structural.card
        .asInstanceOf[LayoutNode.Component]
        .copy(slots =
          Map(
            "entity_id" -> SlotSource(literal = Some("sensor.a")),
            "tint" -> SlotSource(transform = "$attr.tint")
          )
        )
    )
    assert(
      bytes.validate().exists(_.contains("as BYTES")),
      clue = bytes.validate()
    )
  }

  test("structure seeds its signal on its own cell wrapper") {
    val html = Renderer.create(structural).renderPage(tinted("red"))
    assert(html.contains("data-signals=\"{_c__tint: 'red'}\""), clue = html)
    assert(html.contains("data-style:background=\"$_c__tint\""), clue = html)
  }

  test("a change to structure's signal sends a frame and patches nothing") {
    val r = Renderer.create(structural)
    val holds = documentHolds(r, tinted("red"))
    val log = FragmentLog("test").touched("c", 1L)
    val out =
      resumeNow(r, log, holds, tinted("blue"), 1L, Set.empty, Map.empty)
    assertEquals(
      out.map(_.patch),
      List(frame(Renderer.signalName("c", "tint") -> "blue")),
      clue = events(out).map(_.renderString)
    )
    // The structural element itself is what must not move: a morph aimed at it
    // would re-send the region's content as a side effect of a colour change.
    assertEquals(elementPatches(events(out)), Nil)
  }
}
