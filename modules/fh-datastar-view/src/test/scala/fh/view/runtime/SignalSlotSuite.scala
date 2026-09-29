package fh.view.runtime

import fh.view.query.QuerySnapshot
import fh.view.runtime.RendererTestOps.*

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  SetId,
  SignalBind,
  SignalId,
  SlotSource,
  Surface,
  Transform
}
import fh.view.testkit.DashboardBuilders.{asComponent, st}
import fh.view.testkit.FixtureEntity
import fh.view.testkit.TestIds.given

/** Signal slots (ADR 0017). The contract is negative, so every test asserts
  * what is not on the wire as well as what is: a broken implementation still
  * updates the card, because the morph the frame should replace is still sent.
  */
class SignalSlotSuite extends ServerHarness {

  /** Declared first: a fixture reads it, and a later constructor statement
    * would still be null. It is also, hashed, the tail of the signal path it
    * produces.
    */
  private val fillPct = "str(attr['brightness']) + '%'"

  // Each is also, hashed, the tail of its signal path (`t` + 8 hex of SHA-256;
  // see `Renderer.transformSegment`, which hashes everything but `state`).
  private val friendlyRead =
    "'friendly_name' in attr ? attr['friendly_name'] : entity_id"
  private val brightnessRead =
    "'brightness' in attr ? attr['brightness'] : null"
  private val rgbRead = "'rgb_color' in attr ? attr['rgb_color'] : null"
  private val tintRead = "'tint' in attr ? attr['tint'] : null"

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
        "label" -> SlotSource(transform = friendlyRead),
        "value" -> SlotSource(signal = Some(SignalBind.Text))
      )
    )

  private val dash = Dashboard(cards, gauge("sensor.a"))
  private val leaf: NodeId = "c"

  /** Keyed by what it reads, not by the node showing it (issue #134). Spelled
    * through the production derivation: written out twice, a drift would bind a
    * signal nothing patches.
    */
  private def sig(entity: String, transform: String = "state"): SignalId =
    Renderer.signalName(leaf, "", Some(entity), transform, SignalBind.Text)

  /** A two-way binding stays scoped to its node (ADR 0025). */
  private def bound(node: NodeId, slot: String): SignalId =
    Renderer.signalName(node, slot, None, "", SignalBind.Bind)

  private def at(state: String, name: String = "Hall") =
    Map("sensor.a" -> st("sensor.a", state, "friendly_name" -> name.asJson))

  extension (s: String) private def asJson = io.circe.Json.fromString(s)

  private def renderer = Renderer.create(dash)

  /** Slot values are strings on the wire; [[Patch.Signals]] is `Json` so the
    * cursor, a nested object, can merge into the same patch.
    */
  private def frame(kv: (fh.view.model.SignalId, String)*): Patch.Signals =
    Patch.Signals(kv.map { case (k, v) =>
      k -> io.circe.Json.fromString(v)
    }.toMap)

  test("the document form carries the value inline AND seeds its signal") {
    val html = renderer.renderPage(at("21.4"))
    // Inline, for a browser that will never run JavaScript.
    assert(html.contains(">21.4<"), clue = html)
    // The seed makes a Datastar client correct before any frame. Nested,
    // because `mergePatch` would store a flat `_e.sensor.a.state` as one
    // literal key that the `$_e.sensor.a.state` read never matches.
    assert(
      html.contains("data-signals=\"{_e: {sensor: {a: {state: '21.4'}}}}\""),
      clue = html
    )
    assert(html.contains("data-text=\"$_e.sensor.a.state\""), clue = html)
  }

  test("the patch form carries neither the value nor the seed") {
    val patch =
      renderer
        .renderNodeById(leaf, at("21.4"), fragments = QuerySnapshot.empty)
        .get
    assertEquals(patch.contains("21.4"), false, clue = patch)
    assertEquals(patch.contains("data-signals"), false, clue = patch)
    // A morph that dropped the binding would leave the element inert for good.
    assert(patch.contains("data-text=\"$_e.sensor.a.state\""), clue = patch)
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
    // A signal-free node must not pay for a second template execute. Asserted
    // on one walk, the only place the two forms can share bytes. Structure has
    // no patch form at all (`Traced` carries none), so there is nothing to
    // check for it.
    val freeR = Renderer.create(plain)
    val free =
      freeR.renderBodyTraced(at("21.4"), fragments = QuerySnapshot.empty)
    val freeId = free.own.keys.head
    assert(
      free.own(freeId).digest == Digest.of(free.html),
      clue = "a signal-free node rendered twice"
    )
    val signalled =
      renderer.renderBodyTraced(at("21.4"), fragments = QuerySnapshot.empty)
    val signalledId = signalled.own.keys.head
    val signalledPatch = renderer
      .renderNodeById(signalledId, at("21.4"), fragments = QuerySnapshot.empty)
      .get
    assert(signalled.own(signalledId).digest == Digest.of(signalledPatch))
    assert(signalled.own(signalledId).digest != Digest.of(signalled.html))
    assert(signalled.html.contains("21.4"), clue = signalled.html)
    assertEquals(signalledPatch.contains("21.4"), false, signalledPatch)
  }

  /** What a real client starts from. */
  private def documentHolds(
      r: Renderer,
      states: Map[String, EntityState]
  ): Map[NodeId, Held] =
    r.renderPageTraced(states).own.map { case (id, p) =>
      id -> Held(Some(p.digest), p.signals)
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
      List(frame(sig("sensor.a") -> "21.5")),
      clue = events(out).map(_.render)
    )
    assertEquals(elementPatches(events(out)), Nil)
  }

  test("a change to a NON-signal slot still morphs the card") {
    val r = renderer
    val out = resumeFrom(r, at("21.4"), at("21.4", name = "Landing"))
    val morphs = out.map(_.patch).collect { case m: Patch.Morph => m }
    assertEquals(morphs.size, 1, clue = events(out).map(_.render))
    assert(morphs.head.html.contains("Landing"), clue = morphs.head.html)
    assertEquals(out.map(_.patch).collect { case s: Patch.Signals => s }, Nil)
  }

  test("a frame is not re-sent for a value the client already holds") {
    val r = renderer
    assertEquals(resumeFrom(r, at("21.4"), at("21.4")), Nil)
  }

  test("the document's holds suppress the first tick's morph") {
    // Seeded from the document form while the pull renders the patch form, a
    // mismatch would send one pointless morph per signal node per page load.
    val r = renderer
    val out = resumeFrom(r, at("21.4"), at("21.5"))
    assertEquals(elementPatches(events(out)), Nil)
  }

  test("two signal slots on one node share ONE data-signals attribute") {
    // A per-slot seed puts two `data-signals` on one element and the browser
    // silently keeps one, so the second slot never updates.
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
            transform = friendlyRead,
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
      html.contains(
        "data-signals=\"{_e: {sensor: {a: " +
          "{state: '21.4', tb1663a46: 'Hall'}}}}\""
      ),
      clue = html
    )
  }

  test("a member's children name their signals under the MEMBER") {
    // A member's children have no ids, so their signals ride on the member's
    // wrapper. Naming them under a child would seed a signal no element binds.
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
                  "label" -> SlotSource(transform = friendlyRead),
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
    assert(html.contains("data-text=\"$_e.light.a.state\""), clue = html)
    assertEquals(
      r.signalsFor("c_light_a", states),
      Map(sig("light.a") -> "on")
    )
  }

  /** Everything that moves with brightness: text, two-way bind, a custom
    * property and an attribute. Any one wrong re-renders the card and the other
    * three buy nothing, so the whole set is asserted.
    */
  private val sliderish = Dashboard(
    Map(
      "slider" -> CardDef(
        """<div class="slider" style="--_end: {{fill}}" {{{fill__bind}}}>""" +
          """<span class="fh-reading" {{{state__bind}}}>{{state}}</span>""" +
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
          transform = brightnessRead,
          signal = Some(SignalBind.Text)
        ),
        "value" -> SlotSource(
          transform = brightnessRead,
          signal = Some(SignalBind.Bind)
        ),
        "fill" -> SlotSource(
          transform = fillPct,
          signal = Some(SignalBind.Style("--_end"))
        ),
        "tint" -> SlotSource(
          transform = rgbRead,
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
    // The two-way one takes the signal's name, not a `$`-read, because it
    // writes back.
    assert(
      html.contains("""data-text="$_e.light.a.t62b081ec""""),
      clue = html
    )
    assert(
      html.contains("""data-attr:title="$_e.light.a.t26900b50""""),
      clue = html
    )
    assert(
      html.contains(s"""data-style:--_end="$$${sig("light.a", fillPct)}""""),
      clue = html
    )
    // Scoped to its node: an input writes it back, and must not drive another
    // card's readout.
    assert(html.contains("""data-bind="_c__value""""), clue = html)
    assert(html.contains("--_end: 40%"), clue = html)
    assert(html.contains("""value="40""""), clue = html)
    // A canned binding cannot compose a URL, so the action names the signal.
    assert(html.contains("""@post('x/' + $_c__value)"""), clue = html)
  }

  test("every wire spelling the authoring layer emits decodes") {
    // The other end of the `components.test.pkl` fact of the same name: the
    // grammar is declared twice, a Pkl typealias regex and this parser, and a
    // spelling only one accepts binds nothing, silently.
    assertEquals(
      List(
        "text",
        "bind",
        "style:--_end",
        "attr:value",
        "class:fh-disabled"
      ).map(SignalBind.parse),
      List(
        SignalBind.Text,
        SignalBind.Bind,
        SignalBind.Style("--_end"),
        SignalBind.Attr("value"),
        SignalBind.Class("fh-disabled")
      ).map(Some(_))
    )
    assertEquals(SignalBind.parse("attr:"), None)
    assertEquals(SignalBind.parse("attr"), None)
  }

  // `""` sets `disabled`, so only a real `false` turns it off. Both ends are
  // pinned, the seed and the no-JS bytes, because they fail independently.
  private val boolOff: Transform.Simple =
    Transform.Simple.Match(Map("unavailable" -> true), otherwise = false)

  private val boolDash = Dashboard(
    Map(
      "sw" -> CardDef(
        """<button {{#off}}disabled{{/off}} {{{off__bind}}}>go</button>""",
        slots = List("off")
      )
    ),
    LayoutNode.Component(
      "sw",
      Map(
        "entity_id" -> SlotSource(literal = Some("light.a")),
        "off" -> SlotSource(
          transform = boolOff,
          bypassUnavailable = false,
          signal = Some(SignalBind.Attr("disabled"))
        )
      )
    )
  )

  test("a boolean slot binds bare, and seeds an unquoted boolean") {
    val html = Renderer.create(boolDash).renderPage(lit(40))
    val s = sig("light.a", Transform.Simple.key(boolOff))
    // Bare `$sig`: the value is a real boolean, so the plugin's own `false ->
    // removeAttribute` is the whole mechanism.
    assert(html.contains(s"""data-attr:disabled="$$$s""""), clue = html)
    // Unquoted: `'false'` would seed a truthy string, and the attribute would
    // be set with JS and clear without. Asserted on the leaf segment, since the
    // seed is nested.
    assert(html.contains(s"${s.segments.last}: false"), clue = html)
    assert(!html.contains("'false'"), clue = html)
  }

  test("a false slot leaves the attribute out of the plain HTML") {
    // Mustache drives `{{#off}}` off `java.lang.Boolean`, and the string
    // "false" is truthy there, so a stringified value would leave a no-JS
    // browser with a permanently disabled button while everything above passed.
    val off = Renderer.create(boolDash).renderPage(lit(40))
    assert(!off.contains("<button disabled"), clue = off)

    val on = Renderer
      .create(boolDash)
      .renderPage(Map("light.a" -> st("light.a", "unavailable")))
    assert(on.contains("<button disabled"), clue = on)
  }

  test("a brightness tick moves four values and sends no element patch") {
    val r = Renderer.create(sliderish)
    val log = FragmentLog("test").touched(leaf, 1L)
    val out = resumeNow(
      r,
      log,
      r.renderPageTraced(lit(40)).own.map { case (id, p) =>
        id -> Held(Some(p.digest), p.signals)
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
          sig("light.a", brightnessRead) -> "41",
          bound(leaf, "value") -> "41",
          sig("light.a", fillPct) -> "41%"
        )
      ),
      clue = events(out).map(_.render)
    )
    assertEquals(elementPatches(events(out)), Nil)
  }

  /** Nothing on the card paints it; only the click expression reads it (ADR
    * 0017).
    */
  private val lockService = "state == 'locked' ? 'lock/unlock' : 'lock/lock'"

  /** The template never learns which tier filled `service`. */
  private val lockCard = CardDef(
    """<article data-on:click="@post('a/' + {{{service__read}}})">""" +
      """<span {{{state__bind}}}>{{state}}</span></article>""",
    slots = List("state", "service")
  )

  private def tile(service: SlotSource) = Dashboard(
    Map("lock" -> lockCard),
    LayoutNode.Component(
      "lock",
      Map(
        "entity_id" -> SlotSource(literal = Some("lock.front")),
        "state" -> SlotSource(
          transform = "state",
          signal = Some(SignalBind.Text)
        ),
        "service" -> service
      )
    )
  )

  private val lockish = tile(
    SlotSource(transform = lockService, signal = Some(SignalBind.Handler))
  )

  private def lock(state: String) =
    Map("lock.front" -> st("lock.front", state))

  test("one template serves a literal service and a signalled one") {
    val static = Renderer
      .create(tile(SlotSource(literal = Some("light/toggle"))))
      .renderPage(lock("locked"))
    assert(static.contains("""@post('a/' + 'light/toggle')"""), clue = static)
    assert(!static.contains("light/toggle'}"), clue = static)

    val live = Renderer.create(lockish).renderPage(lock("locked"))
    assert(
      live.contains(s"""@post('a/' + $$${sig("lock.front", lockService)})"""),
      clue = live
    )
  }

  test("a handler signal binds nothing, and the service leaves the bytes") {
    val html = Renderer.create(lockish).renderPage(lock("locked"))
    // Every other kind emits an attribute, so a `data-*` here would mean the
    // wrong kind.
    assert(!html.contains("""data-attr:service"""), clue = html)
    assert(!html.contains("""data-text="$_e.lock.front.t"""), clue = html)
    // The service is in the seed, for a correct first paint (ADR 0017), and
    // only there: the element is byte-identical either way, which keeps it in
    // the identity cache.
    def click(s: String): String =
      Renderer
        .create(lockish)
        .renderPage(lock(s))
        .split("data-on:click=", 2)(1)
        .takeWhile(_ != '>')
    assertEquals(click("locked"), click("unlocked"), clue = html)
  }

  test("locking moves the service signal and sends NO element patch") {
    val r = Renderer.create(lockish)
    val log = FragmentLog("test").touched(leaf, 1L)
    val out = resumeNow(
      r,
      log,
      r.renderPageTraced(lock("locked")).own.map { case (id, p) =>
        id -> Held(Some(p.digest), p.signals)
      },
      lock("unlocked"),
      1L,
      Set.empty,
      Map.empty
    )
    assertEquals(
      out.map(_.patch),
      List(
        frame(
          sig("lock.front") -> "unlocked",
          sig("lock.front", lockService) -> "lock/lock"
        )
      ),
      clue = events(out).map(_.render)
    )
    // The claim the design rests on: with the URL in the element, every
    // lock/unlock repainted the tile.
    assertEquals(elementPatches(events(out)), Nil)
  }

  /** A bare container over two signal leaves, so the leaves are the patch units
    * and a merge has to reach both.
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

  /** The shape issue #134 was opened on. */
  private val twiceOver = Dashboard(
    cards + ("col" -> CardDef(
      "<div>{{#children}}{{{html}}}{{/children}}</div>",
      regions = Map("children" -> Region())
    )),
    LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(gauge("sensor.a"), gauge("sensor.a"))
    )
  )

  test("one entity on two nodes is ONE signal, carried once") {
    // Node-scoped names made these two signals, equal forever, riding every
    // frame twice.
    val r = Renderer.create(twiceOver)
    val log = FragmentLog("test")
      .touched(NodeId.derived("c_0"), 1L)
      .touched(NodeId.derived("c_1"), 1L)
    val out = resumeNow(
      r,
      log,
      documentHolds(r, at("21.4")),
      at("21.5"),
      1L,
      Set.empty,
      Map.empty
    )
    assertEquals(
      out.map(_.patch),
      List(frame(sig("sensor.a") -> "21.5")),
      clue = events(out).map(_.render)
    )
    assertEquals(elementPatches(events(out)), Nil)
    val html = r.renderPage(at("21.4"))
    assertEquals(
      "data-text=\"\\$_e\\.sensor\\.a\\.state\"".r.findAllIn(html).size,
      2,
      clue = html
    )
  }

  test(
    "issue #134's frame: one entity in three places, nine slots, three entries"
  ) {
    // #134's measurement: one light in three places through the same transforms
    // was 9 entries under node-scoped names, of which 3 were distinct.
    val trio = Map(
      "trio" -> CardDef(
        "<i {{{state__bind}}}>{{state}}</i><b {{{fill__bind}}}></b>" +
          "<u {{{tint__bind}}}></u>",
        slots = List("state", "fill", "tint")
      ),
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      )
    )
    def place = LayoutNode.Component(
      "trio",
      Map(
        "entity_id" -> SlotSource(literal = Some("light.a")),
        "state" -> SlotSource(signal = Some(SignalBind.Text)),
        "fill" -> SlotSource(
          transform = fillPct,
          signal = Some(SignalBind.Style("--_end"))
        ),
        "tint" -> SlotSource(
          transform = rgbRead,
          signal = Some(SignalBind.Attr("title"))
        )
      )
    )
    val r = Renderer.create(
      Dashboard(
        trio,
        LayoutNode.Component(
          "col",
          regions = LayoutNode.kids(place, place, place)
        )
      )
    )
    val log = List("c_0", "c_1", "c_2").foldLeft(FragmentLog("test"))((l, id) =>
      l.touched(NodeId.derived(id), 1L)
    )
    // All three readings move; a fixture where only one moved would prove
    // nothing about sharing.
    def lightAt(bright: Int, state: String, rgb: String) =
      Map(
        "light.a" -> st(
          "light.a",
          state,
          "brightness" -> io.circe.Json.fromInt(bright),
          "rgb_color" -> rgb.asJson
        )
      )
    val out = resumeNow(
      r,
      log,
      documentHolds(r, lightAt(40, "on", "warm")),
      lightAt(41, "dim", "cool"),
      1L,
      Set.empty,
      Map.empty
    )
    val entries = out.map(_.patch).collect { case s: Patch.Signals => s.values }
    assertEquals(entries.map(_.size), List(3), clue = entries)
    assertEquals(elementPatches(events(out)), Nil)
  }

  test("a fill seeds an entity the page has never shown") {
    // Why the seed cannot move to one document-level `data-signals`: a surface
    // can introduce an entity nothing on the page reads, so no frame is coming
    // and the fill's own bytes are the only way to make it correct (ADR 0017).
    val r = Renderer.create(
      Dashboard(
        cards,
        gauge("sensor.a"),
        surfaces = Map("panel" -> Surface(gauge("sensor.unseen")))
      )
    )
    val states = at("21.4") ++
      Map(
        "sensor.unseen" -> st(
          "sensor.unseen",
          "7",
          "friendly_name" -> "New".asJson
        )
      )
    val page = r.renderPage(states)
    assertEquals(page.contains("sensor.unseen"), false, clue = page)
    val fill = r
      .renderSurfaceTraced("panel", states, fragments = QuerySnapshot.empty)
      .map(_.html)
      .get
    assert(fill.contains("data-text=\"$_e.sensor.unseen.state\""), clue = fill)
    assert(
      fill.contains("data-signals=\"{_e: {sensor: {unseen: {state: '7'"),
      clue = fill
    )
  }

  private def both(a: String, b: String) =
    Map(
      "sensor.a" -> st("sensor.a", a, "friendly_name" -> "A".asJson),
      "sensor.b" -> st("sensor.b", b, "friendly_name" -> "B".asJson)
    )

  test("a frame MERGES every node the batch touched") {
    // `signalFrame` collects across all candidates, so two entities moving in
    // one HA frame are one frame.
    val r = Renderer.create(twoNodes)
    val log = FragmentLog("test")
      .touched(NodeId.derived("c_0"), 1L)
      .touched(NodeId.derived("c_1"), 1L)
    val held = r.renderPageTraced(both("1", "2")).own.map { case (id, p) =>
      id -> Held(Some(p.digest), p.signals)
    }
    val out =
      resumeNow(r, log, held, both("9", "8"), 1L, Set.empty, Map.empty)
    assertEquals(
      out.map(_.patch),
      List(
        frame(
          sig("sensor.a") -> "9",
          sig("sensor.b") -> "8"
        )
      ),
      clue = events(out).map(_.render)
    )
  }

  test("coalesced versions collapse to one frame carrying the LATEST value") {
    // Versions landing mid-render collapse into one pull. Safe because the pull
    // selects from `position + 1`, not the version it woke for, and the log
    // holds versions, never values, so a frame renders the current snapshot.
    val r = renderer
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
      List(frame(sig("sensor.a") -> "21.6")),
      clue = events(out).map(_.render)
    )
  }

  private def fixture(states: Map[String, EntityState])(id: String) =
    FixtureEntity(id, states(id).state, states(id).attributes)

  test(
    "three frames over two nodes, then ONE pull: one event, both nodes"
  ) {
    // Three versions reach the log before this session pulls, two for the same
    // node: one pull must merge across versions and across nodes.
    live(twoNodes, both("21.4", "44")) { ts =>
      for {
        v <- ts.viewer()
        // HA sends every attribute on a change, the name the label reads
        // included.
        _ <- ts.record(fixture(both("21.5", "44"))("sensor.a"))
        _ <- ts.record(fixture(both("21.6", "44"))("sensor.a"))
        _ <- ts.record(fixture(both("21.6", "48"))("sensor.b"))
        first <- v.pull
        position <- v.session.position.get
        again <- v.pull
      } yield {
        // `sensor.a` appears once despite moving twice.
        assertEquals(
          first.map(_.data),
          List(
            Some(
              s"""signals {"_cursor":{"storeVersion":$position},"_e":{"sensor":""" +
                """{"a":{"state":"21.6"},"b":{"state":"48"}}}}"""
            )
          ),
          clue = first.map(_.render)
        )
        assertEquals(again, Nil)
      }
    }
  }

  test("the cursor merges into a signals-only batch, and not past a morph") {
    // `encode` merges adjacent signal patches, and the cursor rides as a patch,
    // so a value tick is one event.
    val values = frame(sig("sensor.a") -> "21.5")
    val cursor = Server.versionPatch(27L)
    assertEquals(
      Patches.encode(List(Addressed(values), Addressed(cursor))).map(_.data),
      List(
        Some(
          """signals {"_cursor":{"storeVersion":27},""" +
            """"_e":{"sensor":{"a":{"state":"21.5"}}}}"""
        )
      )
    )
    // Echoing the cursor claims to have applied what came before it, so it must
    // not overtake a morph (ADR 0011).
    val withMorph = Patches.encode(
      List(
        Addressed(values),
        Addressed(Patch.Morph("<div id=\"c\"></div>")),
        Addressed(cursor)
      )
    )
    assertEquals(withMorph.size, 3, clue = withMorph.map(_.render))
    assertEquals(withMorph.last.data, Some(cursor.toSse.data.get))
  }

  test("a member leaving sends the remove and NO signal for it") {
    // A `Gone` is not a candidate, so nothing is sent for a departed value. The
    // client's store keeps it, which is safe: a member returning with the same
    // value reads a correct store, and a different value is a difference the
    // record sees. The leak is bounded, since a set's candidates are static
    // (ADR 0003).
    def member(id: String) = LayoutNode.Component(
      "gauge",
      Map(
        "entity_id" -> SlotSource(literal = Some(id)),
        "label" -> SlotSource(transform = friendlyRead),
        "value" -> SlotSource(signal = Some(SignalBind.Text))
      )
    )
    // Two candidates: one leaving an otherwise empty set takes the wholesale
    // refill path instead.
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
    // The log must know the members, or the group is not established and a
    // departure refills the host wholesale.
    val known = r.members.syncMembers(Membership.empty, Nil, on, on).membership
    val held = r.renderPageTraced(on).own.map { case (id, p) =>
      id -> Held(Some(p.digest), p.signals)
    }
    val seededLog = List("c_light_a", "c_light_b")
      .foldLeft(FragmentLog("test"))((l, id) =>
        l.touched(NodeId.derived(id), 0L)
      )
    val delta = r.members
      .syncMembers(
        known,
        List(
          StateChange(
            "light.a",
            Some(st("light.a", "on")),
            st("light.a", "off")
          )
        ),
        on,
        gone
      )
      .deltas
    val log = Patches.record(
      r,
      seededLog,
      Patches.DiffRequest(
        staticIds = Nil,
        sets = List(SetId.of(NodeId.derived("c"), LayoutNode.SetNode())),
        flips = Nil,
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
      clue = events(out).map(_.render)
    )
  }

  // Validation: both failures are otherwise silent.

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

  test("a handler slot is rejected unless the card READS it") {
    def card(tpl: String) = Dashboard(
      Map("t" -> CardDef(tpl, slots = List("service"))),
      LayoutNode.Component(
        "t",
        Map(
          "entity_id" -> SlotSource(literal = Some("lock.front")),
          "service" -> SlotSource(
            transform = lockService,
            signal = Some(SignalBind.Handler)
          )
        )
      )
    )
    // There is no binding to place, so the `__bind` rule cannot be what checks
    // it.
    assertEquals(
      card("""<i data-on:click="@post('a')"></i>""").validate(),
      List(
        "c: card 't' has slot 'service' marked as a signal slot, but no part " +
          "of its template places {{{service__read}}} or {{service__signal}} " +
          "— the value would stop updating"
      )
    )
    // `__read` is for composing a URL, `__signal` the bare name for anything
    // else.
    assertEquals(card("""<i x="{{{service__read}}}"></i>""").validate(), Nil)
    assertEquals(card("""<i x="${{service__signal}}"></i>""").validate(), Nil)
  }

  test("a live non-signal slot cannot be read as a JS expression") {
    // A live value has no answer before the paint, and refusing it is the rule:
    // it would move the element's bytes every tick.
    val bad = Dashboard(
      Map(
        "t" -> CardDef(
          """<i x="{{{service__read}}}"></i>""",
          slots = List("service")
        )
      ),
      LayoutNode.Component(
        "t",
        Map(
          "entity_id" -> SlotSource(literal = Some("lock.front")),
          "service" -> SlotSource(transform = lockService)
        )
      )
    )
    assertEquals(
      bad.validate(),
      List(
        "c: card 't' reads slot 'service' as {{{service__read}}}, but the " +
          "slot is live and not a signal — its value moves in the element's " +
          "bytes, so make it a signal slot or a literal"
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

  test("the subject slot cannot be a signal slot") {
    // `entity_id` is what every other slot resolves against, and a signal moves
    // in the browser alone, so the server would keep resolving against the old
    // entity. The two halves of the renderer disagreed about it, so neither
    // reading is right and it is a build error.
    val subject = Dashboard(
      Map(
        "gauge" -> CardDef(
          "<i {{{entity_id__bind}}}>{{value}}</i>",
          slots = List("value")
        )
      ),
      LayoutNode.Component(
        "gauge",
        Map(
          "entity_id" -> SlotSource(
            transform = "state",
            signal = Some(SignalBind.Text)
          ),
          "value" -> SlotSource()
        )
      )
    )
    assertEquals(
      subject.validate(),
      List(
        "c: slot 'entity_id' cannot be a signal slot — it names the entity " +
          "the card's other slots read, which is a build-time fact, not a " +
          "value that moves"
      )
    )
  }

  /** Structure is never a patch target, but a signal is not bytes. Both halves
    * are asserted, validate accepting it and `signalsFor` updating it, because
    * either alone is a silent failure: the seed would be written once and never
    * updated.
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
          transform = tintRead,
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
    // The half the rule still owns: `tint` as bytes could only reach the DOM by
    // patching the section, carrying the region's content with it.
    val bytes = structural.copy(card =
      structural.card.asComponent
        .copy(slots =
          Map(
            "entity_id" -> SlotSource(literal = Some("sensor.a")),
            "tint" -> SlotSource(transform = tintRead)
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
    assert(
      html.contains("data-signals=\"{_e: {sensor: {a: {t9902cc28: 'red'}}}}\""),
      clue = html
    )
    assert(
      html.contains("data-style:background=\"$_e.sensor.a.t9902cc28\""),
      clue = html
    )
  }

  test("a change to structure's signal sends a frame and patches nothing") {
    val r = Renderer.create(structural)
    val holds = documentHolds(r, tinted("red"))
    val log = FragmentLog("test").touched("c", 1L)
    val out =
      resumeNow(r, log, holds, tinted("blue"), 1L, Set.empty, Map.empty)
    assertEquals(
      out.map(_.patch),
      List(frame(sig("sensor.a", tintRead) -> "blue")),
      clue = events(out).map(_.render)
    )
    assertEquals(elementPatches(events(out)), Nil)
  }
}
