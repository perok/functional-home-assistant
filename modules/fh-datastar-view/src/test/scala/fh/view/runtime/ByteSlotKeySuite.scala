package fh.view.runtime

import fh.view.testkit.TestCard
import fh.view.testkit.Conds

import fh.view.fetch.FetchSnapshot
import fh.view.runtime.RendererTestOps.*

import cats.effect.unsafe.implicits.global
import cats.syntax.traverse.*
import fh.view.model.{
  Part,
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Region,
  SignalBind,
  Step,
  Value,
  ValueExpr,
  TemplateSlot,
  Surface
}
import fh.view.testkit.DashboardBuilders.{col, lit, st}
import fh.view.testkit.TestIds.{setId, given}
import io.circe.Json

import scala.concurrent.duration.*

/** Is [[Renderer.byteSlotValues]] a sound key (ADR 0012)? It keys the render
  * cache and decides whether a session sends a morph, so a too-coarse key
  * serves or keeps bytes that no longer match state, silently. The property is
  *
  * {{{byteSlotValues(a) == byteSlotValues(b) => render(a) == render(b)}}}
  *
  * over all pairs of a timeline, since the failure is a pair, not a step. The
  * timeline runs through a real [[StateStore]], dedup included.
  */
class ByteSlotKeySuite extends munit.FunSuite {

  private val cards = Map(
    "col" -> TestCard(
      """<div>{{{children}}}</div>""",
      regions = Map("children" -> Region())
    ),
    "card" -> TestCard(
      """<div><span>{{state}}</span> {{#unit}}{{unit}}{{/unit}}</div>"""
    ),
    // A bake owner beside a live leaf: an owner holds regions, so it is
    // structure and never cached, and the leaf cannot see the selection.
    "banner" -> TestCard(
      template =
        """<div><i>{{bakeIndex}}</i>{{{bar}}}<div id="{{hostId}}">{{{branch}}}</div></div>""",
      regions = Map("bar" -> Region(), "branch" -> Region(Region.Baked))
    ),
    "bannerBar" -> TestCard("""<b>{{title}}</b>"""),
    "btn" -> TestCard("""<button>{{label}}</button>"""),
    // The signal's entity reaches the patch form only as a binding (ADR 0017),
    // the asymmetry the key must reflect.
    "gauge" -> TestCard(
      """<div><span>{{state}}</span><em {{{live.bind}}}>{{live}}</em></div>"""
    )
  )

  private def bound(entity: String) = LayoutNode.Component(
    "card",
    templateSlots = Map(
      "state" -> TemplateSlot(ValueExpr(Value.Entity(Some(entity)))),
      "unit" -> TemplateSlot(
        ValueExpr(
          Value.Entity(Some(entity), Part.Whole),
          List(
            Step.Cel(
              "('unit_of_measurement' in value.attr ? value.attr['unit_of_measurement'] : '')"
            )
          )
        )
      )
    )
  )

  private val anyLightOn: ValueExpr =
    Conds.count(
      candidates = List("light.a", "light.b"),
      when = Map(
        "light.a" -> Conds.cmp("state", Op.Eq, Json.fromString("on")),
        "light.b" -> Conds.cmp("state", Op.Eq, Json.fromString("on"))
      ),
      op = Op.Gt,
      value = Json.fromInt(0)
    )

  /** `c_2` is a banner bound to sensor.t whose bake group is chosen by a count
    * of the lights: inputs that appear nowhere in its `entitiesForNode`. `c_3`
    * is a candidate group over lights.
    */
  private val dashboard = Dashboard(
    cards,
    col(
      bound("sensor.t"),
      bound("sensor.other"),
      LayoutNode.Component(
        "banner",
        regions = Map(
          "bar" -> List(
            LayoutNode.Component(
              "bannerBar",
              templateSlots = Map(
                "title" -> TemplateSlot(
                  ValueExpr(Value.Entity(Some("sensor.t")))
                )
              )
            )
          )
        )
      ),
      LayoutNode.SetNode(
        candidates = List("light.a", "light.b"),
        members = List("light.a", "light.b").map { id =>
          id -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(Conds.cmp("state", Op.Eq, Json.fromString("on"))),
                LayoutNode.Component(
                  "btn",
                  Map(
                    "entity_id" -> lit(id),
                    "label" -> TemplateSlot(
                      ValueExpr(
                        Value.Entity(None, Part.Whole),
                        List(Step.Cel("value.state"))
                      )
                    )
                  )
                )
              ),
              LayoutNode.SetClause(
                None,
                LayoutNode.Component(
                  "btn",
                  Map("entity_id" -> lit(id), "label" -> lit("off"))
                )
              )
            )
          )
        }.toMap
      ),
      LayoutNode.Component(
        "gauge",
        templateSlots = Map(
          "state" -> TemplateSlot(
            ValueExpr(Value.Entity(Some("sensor.t")))
          ),
          "live" -> TemplateSlot(
            ValueExpr(Value.Entity(Some("sensor.other"))),
            signal = Some(SignalBind.Text)
          )
        )
      )
    ),
    surfaces = Map(
      "lit" -> Surface(
        bound("sensor.a"),
        bakeInto = Some("c_2"),
        bakeAs = Some("branch"),
        bakeIndex = Some(0),
        activation = Activation.State(Some(anyLightOn))
      ),
      "dark" -> Surface(
        bound("sensor.b"),
        bakeInto = Some("c_2"),
        bakeAs = Some("branch"),
        bakeIndex = Some(1),
        activation = Activation.State()
      )
    )
  )

  private val renderer = Renderer.create(dashboard)

  private val initial = Map(
    "sensor.t" -> st(
      "sensor.t",
      "12.4",
      "unit_of_measurement" -> Json.fromString("°C")
    ),
    "sensor.other" -> st("sensor.other", "1"),
    "light.a" -> st("light.a", "off"),
    "light.b" -> st("light.b", "off")
  )

  /** Every step's snapshot, the first included, as the store keeps them. */
  private def timeline(
      steps: List[EntityState]
  ): List[Map[String, EntityState]] =
    (for {
      store <- StateStore.inMemory(initial)
      first <- store.snapshot
      rest <- steps.traverse(s => store.update(s) *> store.snapshot)
    } yield first :: rest).timeout(10.seconds).unsafeRunSync()

  private val steps = List(
    st("sensor.t", "12.9", "unit_of_measurement" -> Json.fromString("°C")),
    // A re-seed of the same content with a fresher timestamp: deduped, so no
    // key may move.
    st("sensor.t", "12.9", "unit_of_measurement" -> Json.fromString("°C"))
      .copy(lastUpdated =
        Some(java.time.Instant.parse("2026-08-04T10:00:00Z"))
      ),
    st("sensor.other", "2"),
    // Flips c_2's bake group and changes a set member's case.
    st("light.a", "on"),
    // The adversarial step: an entity c_2 does not bind, leaving the count's
    // comparison where it was. Neither its bytes nor its key may move.
    st("light.b", "on"),
    st("light.a", "off"),
    st("light.b", "off")
  )

  private val line = timeline(steps)

  /** `c` and `c_3` compose rather than render, so they have no entry. */
  private val ids: List[NodeId] = List("c_0", "c_1", "c_2", "c_4")

  test("agreeing on byte-slot values means agreeing on the bytes") {
    for {
      id <- ids
      (a, i) <- line.zipWithIndex
      (b, j) <- line.zipWithIndex
      key <- renderer
        .byteSlotValues(id, Shared.of(a), fragments = FetchSnapshot.empty)
        .toList
      if renderer
        .byteSlotValues(id, Shared.of(b), fragments = FetchSnapshot.empty)
        .contains(key)
    } assertEquals(
      renderer
        .renderNodeById(
          id,
          Shared.of(a),
          Viewing(Selections.none, FetchSnapshot.empty)
        ),
      renderer
        .renderNodeById(
          id,
          Shared.of(b),
          Viewing(Selections.none, FetchSnapshot.empty)
        ),
      clue =
        s"$id keyed identically at steps $i and $j but rendered differently"
    )
  }

  test("a signal slot is not in the key") {
    // `live` is a signal on c_4, absent from the patch form (ADR 0017), so
    // keying on it would re-render to identical bytes. The all-pairs property
    // above covers soundness if this narrowing goes too far.
    val key =
      renderer
        .byteSlotValues(
          "c_4",
          Shared.of(line.head),
          fragments = FetchSnapshot.empty
        )
        .get
    assertEquals(key.keySet, Set("state"))
  }

  test("a signal slot's entity still reaches the reverse index") {
    // The loud-vs-silent half: the key may drop `live`, but
    // `componentsFor` may not, or no frame is ever computed for the signal.
    val nodes = renderer.componentsFor("sensor.other")
    assert(nodes.contains(NodeId.derived("c_4")), clue = nodes)
  }

  test("a set member's key covers everything its clause dispatch reads") {
    // A renderer per side, so neither can reuse what the other rendered.
    for {
      entity <- List("light.a", "light.b")
      (a, i) <- line.zipWithIndex
      (b, j) <- line.zipWithIndex
      ra = Renderer.create(dashboard)
      rb = Renderer.create(dashboard)
      id = ra.members.memberIdOf(setId("c_3"), entity)
      key <- ra
        .byteSlotValues(id, Shared.of(a), fragments = FetchSnapshot.empty)
        .toList
      if rb
        .byteSlotValues(id, Shared.of(b), fragments = FetchSnapshot.empty)
        .contains(key)
    } assertEquals(
      ra.renderNodeById(
        id,
        Shared.of(a),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      rb.renderNodeById(
        id,
        Shared.of(b),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      clue =
        s"$entity keyed identically at steps $i and $j but rendered differently"
    )
  }

  test("the key is not trivially discriminating — it hits where it must") {
    def key(id: NodeId, at: Int) =
      renderer
        .byteSlotValues(
          id,
          Shared.of(line(at)),
          fragments = FetchSnapshot.empty
        )
        .get

    // Without this the cache would miss on every HA reconnect.
    assertEquals(key("c_0", 1), key("c_0", 2))
    assertEquals(key("c_0", 2), key("c_0", 3))
    // Not `c_2`: a bake owner is structure and has no key (below).
  }

  test("an absent entity keys differently from any version it could hold") {
    val absent =
      renderer
        .byteSlotValues(
          "c_0",
          Shared.of(Map.empty),
          fragments = FetchSnapshot.empty
        )
        .get
    assertNotEquals(
      absent,
      renderer
        .byteSlotValues(
          "c_0",
          Shared.of(line.head),
          fragments = FetchSnapshot.empty
        )
        .get
    )
  }

  test("STRUCTURE has NO key") {
    // The root column's rendering moves with any descendant, and the key
    // excludes children, so it cannot be cached: `None`, not a key a caller
    // must know not to trust.
    assertEquals(
      renderer.byteSlotValues(
        "c",
        Shared.of(line.head),
        fragments = FetchSnapshot.empty
      ),
      None
    )
    assertNotEquals(
      renderer.renderBody(line.head),
      renderer.renderBody(line.last)
    )
  }

  /** A bake owner that reads an entity itself, as a signal: structure cannot
    * carry live bytes (`validate`).
    */
  private val tabsOwner: Dashboard =
    Dashboard(
      cards + ("signalBanner" -> cards("banner").copy(
        template = cards("banner").template
          .replace("<i>", "<b {{{title.bind}}}></b><i>")
      )),
      LayoutNode.Component(
        "signalBanner",
        templateSlots = Map(
          "title" -> TemplateSlot(
            ValueExpr(Value.Entity(Some("sensor.t"))),
            signal = Some(SignalBind.Text)
          )
        ),
        regions = Map(
          "bar" -> List(LayoutNode.Component("btn", Map("label" -> lit("A"))))
        )
      ),
      surfaces = dashboard.surfaces.map { case (sid, s) =>
        sid -> s.copy(bakeInto = Some("c"))
      }
    )

  test("a bake owner with a live signal of its own has no key either") {
    val tabs = Renderer.create(tabsOwner)
    assertEquals(
      tabs.byteSlotValues(
        "c",
        Shared.of(line.head),
        fragments = FetchSnapshot.empty
      ),
      None
    )
    // Its element contains what it holds, so patching it would re-send that.
    assertEquals(
      tabs.renderNodeById(
        "c",
        Shared.of(line.head),
        Viewing(Selections.none, FetchSnapshot.empty)
      ),
      None
    )
  }

  test("a node that composes rather than renders has no key") {
    assertEquals(
      renderer.byteSlotValues(
        "c_3",
        Shared.of(line.head),
        fragments = FetchSnapshot.empty
      ),
      None
    )
    assertEquals(
      renderer
        .renderNodeById(
          "c_3",
          Shared.of(line.head),
          Viewing(Selections.none, FetchSnapshot.empty)
        ),
      None
    )
  }

  /** '''No cacheable node owns a bake group.''' The key is byte-slot values
    * only, so two viewers on two tabs would otherwise share one entry. It holds
    * because `Dashboard.validate` requires a `bakeInto` target to declare a
    * baked region, and a card with a region is structure: the join of two rules
    * in different files, true until one is relaxed for a good local reason.
    */
  test("no cacheable node owns a bake group") {
    def check(label: String, r: Renderer, states: Map[String, EntityState]) = {
      val owners = r.surfaces.varBakeOwnerIds ++ r.surfaces.stateBakeOwnerIds
      assert(owners.nonEmpty, s"$label exercises no bake group at all")
      owners.foreach(id =>
        assertEquals(
          r.byteSlotValues(
            id,
            Shared.of(states),
            fragments = FetchSnapshot.empty
          ),
          None,
          s"$label: bake owner '$id' has a cache key, but its bytes can carry " +
            "the viewer's selection"
        )
      )
    }
    // Both activation kinds, since only one reads the viewer: `dashboard` is
    // state-activated, `tabsOwner` user-selected.
    check("state-activated", renderer, line.head)
    check("user-selected", Renderer.create(tabsOwner), line.head)
  }

  test("a member's clause is in its key, even when no slot value moves") {
    // Two clauses, two cards, no live slot: every clause of a candidate mints
    // the same ids, so without the clause the key could not tell them apart.
    val twoCards = Dashboard(
      cards,
      LayoutNode.SetNode(
        candidates = List("light.a"),
        members = Map(
          "light.a" -> LayoutNode.SetMember(
            List(
              LayoutNode.SetClause(
                Some(Conds.cmp("state", Op.Eq, Json.fromString("on"))),
                LayoutNode.Component("btn", Map("label" -> lit("A")))
              ),
              LayoutNode.SetClause(
                None,
                LayoutNode.Component("bannerBar", Map("title" -> lit("A")))
              )
            )
          )
        )
      )
    )
    val r = Renderer.create(twoCards)
    val id = r.members.memberIdOf(setId("c"), "light.a")
    def at(state: String) = Shared.of(Map("light.a" -> st("light.a", state)))
    def bytes(state: String) =
      r.renderNodeById(
        id,
        at(state),
        Viewing(Selections.none, FetchSnapshot.empty)
      )
    assertNotEquals(bytes("on"), bytes("off"))
    assertNotEquals(
      r.byteSlotValues(id, at("on"), FetchSnapshot.empty),
      r.byteSlotValues(id, at("off"), FetchSnapshot.empty)
    )
  }

  test("a value the bytes do not show sends one redundant morph, then none") {
    // The reverse of the property above does not hold: a slot read only as a
    // section's test renders the same for any non-empty value. A session then
    // sends a morph to the bytes the client already has (no DOM change) and
    // records the new values, so the next tick on them sends nothing.
    val r = Renderer.create(
      Dashboard(
        Map("card" -> TestCard("<b>{{#v}}set{{/v}}</b>")),
        LayoutNode.Component(
          "card",
          Map(
            "entity_id" -> TemplateSlot(ValueExpr.literal("sensor.a")),
            "v" -> TemplateSlot(ValueExpr(Value.Entity(None)))
          )
        )
      )
    )
    val id = NodeId.derived("c")
    val viewing = Viewing(Selections.none, FetchSnapshot.empty)
    def state(s: String) =
      Shared.of(Map("sensor.a" -> EntityState("sensor.a", s, Map.empty)))
    val cache = RenderCache.create.unsafeRunSync()
    def morph(holds: Map[NodeId, Held], s: String) =
      Patches.morph(r, cache, holds, state(s), viewing, id).unsafeRunSync()
    val painted = r.renderBodyTraced(state("on"), viewing).own
    val holds = painted.map((k, p) => k -> p.held)
    val onBytes = r.renderNodeById(id, state("on"), viewing)
    assertEquals(r.renderNodeById(id, state("playing"), viewing), onBytes)

    val sent = morph(holds, "playing")
    sent.map(_.patch) match
      case Some(Patch.Morph(html)) => assertEquals(Some(html), onBytes)
      case other                   => fail(s"expected one morph, got $other")
    val after = holds ++ sent.fold(Map.empty[NodeId, Held])(_.establishes)
    assertEquals(morph(after, "playing"), None)
  }
}
