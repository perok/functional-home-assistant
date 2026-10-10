package fh.view.runtime

import fh.view.query.QuerySnapshot
import fh.view.runtime.RendererTestOps.*

import cats.effect.unsafe.implicits.global
import cats.syntax.traverse.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Region,
  SignalBind,
  SlotSource,
  Surface
}
import fh.view.testkit.DashboardBuilders.{col, lit, st}
import fh.view.testkit.TestIds.{setId, given}
import io.circe.Json

import scala.concurrent.duration.*

/** Is [[Renderer.renderInputs]] a sound cache key (ADR 0012)? A
  * too-discriminating key costs a render; a too-coarse one serves bytes that no
  * longer match state, silently. So the property is
  *
  * {{{renderInputs(a) == renderInputs(b) => render(a) == render(b)}}}
  *
  * over all pairs of a timeline, since the failure is a pair, not a step. The
  * timeline runs through a real [[StateStore]], so the stamp tested is the one
  * it assigns, dedup included.
  */
class RenderInputsSuite extends munit.FunSuite {

  private val cards = Map(
    "col" -> CardDef(
      """<div>{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    ),
    "card" -> CardDef(
      """<div><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state", "unit")
    ),
    // A bake owner beside a live leaf: an owner holds regions, so it is
    // structure and never cached, and the leaf cannot see the selection. The
    // key is entity versions.
    "banner" -> CardDef(
      template =
        """<div><i>{{bakeIndex}}</i>{{#bar}}{{{html}}}{{/bar}}<div id="{{hostId}}">{{#branch}}{{{html}}}{{/branch}}</div></div>""",
      regions = Map("bar" -> Region(), "branch" -> Region(Region.Baked))
    ),
    "bannerBar" -> CardDef("""<b>{{title}}</b>""", slots = List("title")),
    "btn" -> CardDef("""<button>{{label}}</button>""", slots = List("label")),
    // The signal's entity reaches the patch form only as a binding (ADR 0017),
    // the asymmetry the key must reflect.
    "gauge" -> CardDef(
      """<div><span>{{state}}</span><em {{{live__bind}}}>{{live}}</em></div>""",
      slots = List("state", "live")
    )
  )

  private def bound(entity: String) = LayoutNode.Component(
    "card",
    slots = Map(
      "state" -> SlotSource(Some(entity)),
      "unit" -> SlotSource(
        Some(entity),
        "('unit_of_measurement' in attr ? attr['unit_of_measurement'] : '')"
      )
    )
  )

  private val anyLightOn: Predicate =
    Predicate.Count(
      candidates = List("light.a", "light.b"),
      when = Map(
        "light.a" -> Predicate.Cmp("state", Op.Eq, Json.fromString("on")),
        "light.b" -> Predicate.Cmp("state", Op.Eq, Json.fromString("on"))
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
              slots = Map("title" -> SlotSource(Some("sensor.t")))
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
                Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
                LayoutNode.Component(
                  "btn",
                  Map(
                    "entity_id" -> lit(id),
                    "label" -> SlotSource(None, "state")
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
        slots = Map(
          "state" -> SlotSource(Some("sensor.t")),
          "live" -> SlotSource(
            Some("sensor.other"),
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
        activation = Activation.State(anyLightOn)
      ),
      "dark" -> Surface(
        bound("sensor.b"),
        bakeInto = Some("c_2"),
        bakeAs = Some("branch"),
        bakeIndex = Some(1),
        activation = Activation.State(Predicate.And(Nil))
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

  /** Every step's snapshot, the first included, as the store stamps them. */
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

  test("agreeing on renderInputs means agreeing on the bytes") {
    for {
      id <- ids
      (a, i) <- line.zipWithIndex
      (b, j) <- line.zipWithIndex
      key <- renderer
        .renderInputs(id, a, fragments = QuerySnapshot.empty)
        .toList
      if renderer
        .renderInputs(id, b, fragments = QuerySnapshot.empty)
        .contains(key)
    } assertEquals(
      renderer.renderNodeById(id, a, fragments = QuerySnapshot.empty),
      renderer.renderNodeById(id, b, fragments = QuerySnapshot.empty),
      clue =
        s"$id keyed identically at steps $i and $j but rendered differently"
    )
  }

  test("an entity reached ONLY through a signal slot is not in the key") {
    // sensor.other is a signal on c_4, absent from the patch form (ADR 0017),
    // so keying on it would throw away an identical generation. The all-pairs
    // property above covers soundness if this narrowing goes too far.
    val key =
      renderer
        .renderInputs("c_4", line.head, fragments = QuerySnapshot.empty)
        .get
    assertEquals(key.entities.keySet, Set("sensor.t"))
  }

  test("a signal slot's entity still reaches the reverse index") {
    // The loud-vs-silent half: the key may drop sensor.other, but
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
      key <- ra.renderInputs(id, a, fragments = QuerySnapshot.empty).toList
      if rb.renderInputs(id, b, fragments = QuerySnapshot.empty).contains(key)
    } assertEquals(
      ra.renderNodeById(id, a, fragments = QuerySnapshot.empty),
      rb.renderNodeById(id, b, fragments = QuerySnapshot.empty),
      clue =
        s"$entity keyed identically at steps $i and $j but rendered differently"
    )
  }

  test("the key is not trivially discriminating — it hits where it must") {
    def key(id: NodeId, at: Int) =
      renderer.renderInputs(id, line(at), fragments = QuerySnapshot.empty).get

    // Without this the cache would miss on every HA reconnect.
    assertEquals(key("c_0", 1), key("c_0", 2))
    assertEquals(key("c_0", 2), key("c_0", 3))
    // Not `c_2`: a bake owner is structure and has no key (below).
  }

  test("an absent entity keys differently from any version it could hold") {
    val absent =
      renderer
        .renderInputs("c_0", Map.empty, fragments = QuerySnapshot.empty)
        .get
    assertNotEquals(
      absent,
      renderer
        .renderInputs("c_0", line.head, fragments = QuerySnapshot.empty)
        .get
    )
    // No entry at all, so no stamp can collide with it.
    assertEquals(absent.entities, Map.empty[String, Long])
  }

  test("STRUCTURE has NO key") {
    // The root column's rendering moves with any descendant, and the key
    // excludes children, so it cannot be cached: `None`, not a key a caller
    // must know not to trust.
    assertEquals(
      renderer.renderInputs("c", line.head, fragments = QuerySnapshot.empty),
      None
    )
    assertNotEquals(
      renderer.renderBody(line.head),
      renderer.renderBody(line.last)
    )
  }

  /** The authoring layer refuses this combination, so the model is built
    * directly: the point is what the renderer answers.
    */
  private val tabsOwner: Dashboard =
    Dashboard(
      cards,
      LayoutNode.Component(
        "banner",
        slots = Map("title" -> SlotSource(Some("sensor.t"))),
        regions = LayoutNode.kids(
          LayoutNode.Component("btn", Map("label" -> lit("A")))
        )
      ),
      surfaces = dashboard.surfaces.map { case (sid, s) =>
        sid -> s.copy(bakeInto = Some("c"))
      }
    )

  test("a bake owner with a live slot of its own has no key either") {
    val tabs = Renderer.create(tabsOwner)
    assertEquals(
      tabs.renderInputs("c", line.head, fragments = QuerySnapshot.empty),
      None
    )
    // Its element contains what it holds, so patching it would re-send that.
    assertEquals(
      tabs.renderNodeById("c", line.head, fragments = QuerySnapshot.empty),
      None
    )
  }

  test("a node that composes rather than renders has no key") {
    assertEquals(
      renderer.renderInputs("c_3", line.head, fragments = QuerySnapshot.empty),
      None
    )
    assertEquals(
      renderer
        .renderNodeById("c_3", line.head, fragments = QuerySnapshot.empty),
      None
    )
  }

  /** '''No cacheable node owns a bake group.''' The key is entity versions
    * only, so two viewers on two tabs would otherwise share one slot. It holds
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
          r.renderInputs(id, states, fragments = QuerySnapshot.empty),
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
}
