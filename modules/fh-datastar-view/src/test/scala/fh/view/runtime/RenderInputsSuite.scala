package fh.view.runtime

import cats.effect.unsafe.implicits.global
import cats.syntax.traverse.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  DynamicCase,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Quantifier,
  SlotSource,
  Surface
}
import fh.view.testkit.DashboardBuilders.{col, lit, st}
import fh.view.testkit.TestIds.given
import io.circe.Json

import scala.concurrent.duration.*

/** Phase 0 of docs/plan-session-pulled-changelog.md: is
  * [[Renderer.renderInputs]] a sound cache key?
  *
  * Only one direction can hurt. A key that is too DISCRIMINATING costs a wasted
  * render and nothing else. A key that is too COARSE serves a client bytes that
  * no longer match its state — silently, and for as long as the entry lives.
  * So the property under test is one implication:
  *
  * {{{ renderInputs(a) == renderInputs(b)  =>  render(a) == render(b) }}}
  *
  * and it is checked over ALL PAIRS of a timeline, because the failure mode is
  * a pair that agrees on the key and disagrees on the bytes — not a step.
  * (Its contrapositive is the precision claim, so one loop covers both.)
  *
  * The timeline is driven through a real [[StateStore]] rather than
  * hand-written `contentVersion`s: the stamp under test is the one the store
  * assigns, including its dedup.
  */
class RenderInputsSuite extends munit.FunSuite {

  private val cards = Map(
    "col" -> CardDef(
      """<div>{{#children}}{{{html}}}{{/children}}</div>"""
    ),
    "card" -> CardDef(
      """<div><span>{{state}}</span> {{unit}}</div>""",
      slots = List("state", "unit")
    ),
    // A bake owner with a `self` that binds a live entity AND prints
    // `bakeIndex` — the shape where both halves of the key are load-bearing at
    // once.
    "banner" -> CardDef(
      template = """<div>{{{self}}}{{{mount}}}</div>""",
      self = Some("""<div id="{{selfId}}"><b>{{title}}</b><i>{{bakeIndex}}</i></div>"""),
      mount = Some("""<div id="{{mountId}}">{{{branch}}}</div>"""),
      slots = List("title")
    ),
    "btn" -> CardDef("""<button>{{label}}</button>""", slots = List("label"))
  )

  private def bound(entity: String) = LayoutNode.Component(
    "card",
    slots = Map(
      "state" -> SlotSource(Some(entity)),
      "unit" -> SlotSource(Some(entity), "$attr.unit_of_measurement")
    )
  )

  private val anyLightOn: Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString("on"))

  /** `c_0` binds sensor.t, `c_1` binds sensor.other, `c_2` is a banner bound to
    * sensor.t whose bake group is chosen by a QUANTIFIED condition over every
    * light — an input that appears nowhere in its `entitiesForNode`. `c_3` is a
    * dynamic group over lights.
    */
  private val dashboard = Dashboard(
    cards,
    col(
      bound("sensor.t"),
      bound("sensor.other"),
      LayoutNode.Component(
        "banner",
        slots = Map("title" -> SlotSource(Some("sensor.t")))
      ),
      LayoutNode.Dynamic(
        query = Some(Predicate.Cmp("domain", Op.Eq, Json.fromString("light"))),
        cases = List(
          DynamicCase(
            Predicate.Cmp("state", Op.Eq, Json.fromString("on")),
            "btn",
            slots = Map("label" -> SlotSource(None, "$state"))
          ),
          DynamicCase(
            Predicate.Cmp("domain", Op.Eq, Json.fromString("light")),
            "btn",
            slots = Map("label" -> lit("off"))
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
        activation = Activation.State(anyLightOn, Quantifier.Any)
      ),
      "dark" -> Surface(
        bound("sensor.b"),
        bakeInto = Some("c_2"),
        bakeAs = Some("branch"),
        bakeIndex = Some(1),
        activation = Activation.State(
          Predicate.Cmp("domain", Op.Ne, Json.fromString("__never__"))
        )
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

  /** Every step's snapshot, the starting one included — as the store stamps
    * them.
    */
  private def timeline(steps: List[EntityState]): List[Map[String, EntityState]] =
    (for {
      store <- StateStore.inMemory(initial)
      first <- store.snapshot
      rest <- steps.traverse(s => store.update(s) *> store.snapshot)
    } yield first :: rest).timeout(10.seconds).unsafeRunSync()

  private val steps = List(
    // Content moves: c_0 and c_2's title.
    st("sensor.t", "12.9", "unit_of_measurement" -> Json.fromString("°C")),
    // A re-seed of the SAME content with a fresher timestamp: stored, deduped,
    // and so no node's key may move.
    st("sensor.t", "12.9", "unit_of_measurement" -> Json.fromString("°C"))
      .copy(lastUpdated = Some(java.time.Instant.parse("2026-08-04T10:00:00Z"))),
    // An entity only c_1 binds.
    st("sensor.other", "2"),
    // Flips c_2's bake group (Any) AND changes a dynamic member's case.
    st("light.a", "on"),
    // THE adversarial step: an entity c_2 does not bind, whose change leaves
    // the quantified condition where it already was. c_2's bytes must not move,
    // and its key must say so.
    st("light.b", "on"),
    st("light.a", "off"),
    // Back to no light on: the group flips to `dark`.
    st("light.b", "off")
  )

  private val line = timeline(steps)

  /** The nodes with a rendering of their own, and so a cache entry. `c` (the
    * root column) and `c_3` (the dynamic root) compose rather than render, so
    * neither is addressable.
    */
  private val ids: List[NodeId] = List("c_0", "c_1", "c_2")

  test("agreeing on renderInputs means agreeing on the bytes") {
    for {
      id <- ids
      (a, i) <- line.zipWithIndex
      (b, j) <- line.zipWithIndex
      if renderer.renderInputs(id, a, Map.empty) ==
        renderer.renderInputs(id, b, Map.empty)
    } assertEquals(
      renderer.renderNodeById(id, a),
      renderer.renderNodeById(id, b),
      clue = s"$id keyed identically at steps $i and $j but rendered differently"
    )
  }

  test("a dynamic member's key covers everything its case dispatch reads") {
    for {
      entity <- List("light.a", "light.b")
      (a, i) <- line.zipWithIndex
      (b, j) <- line.zipWithIndex
      if renderer.dynamicChildInputs("c_3", entity, a) ==
        renderer.dynamicChildInputs("c_3", entity, b)
    } assertEquals(
      renderer.renderDynamicChild("c_3", entity, a),
      renderer.renderDynamicChild("c_3", entity, b),
      clue =
        s"$entity keyed identically at steps $i and $j but rendered differently"
    )
  }

  test("the key is not trivially discriminating — it hits where it must") {
    def key(id: NodeId, at: Int) =
      renderer.renderInputs(id, line(at), Map.empty)

    // A timestamp-only re-seed (step 2) keys the same as the content change
    // before it. Without this the cache would miss on every HA reconnect.
    assertEquals(key("c_0", 1), key("c_0", 2))
    // An unrelated entity moving (step 3) leaves c_0 alone...
    assertEquals(key("c_0", 2), key("c_0", 3))
    // ...and so does a light that does not move the quantified condition
    // (step 5), for the node whose bake group that condition selects.
    assertEquals(key("c_2", 4), key("c_2", 5))
    // But a light that DOES flip it (step 4) must not.
    assertNotEquals(key("c_2", 3), key("c_2", 4))
  }

  test("an absent entity keys differently from any version it could hold") {
    val absent = renderer.renderInputs("c_0", Map.empty, Map.empty)
    assertNotEquals(absent, renderer.renderInputs("c_0", line.head, Map.empty))
    // Not merely different — it carries no entry at all, so no stamp can
    // collide with it.
    assertEquals(absent.entities, Map.empty[String, Long])
  }

  test("a user group's selection is part of the key, per viewer") {
    // `c_2`'s group is state-activated, so uiState cannot move it — the
    // asymmetry a user-selected group does not have.
    assertEquals(
      renderer.renderInputs("c_2", line.head, Map.empty),
      renderer.renderInputs("c_2", line.head, Map("ui_c_2" -> "1"))
    )
  }

  /** The gap phase 1 closes, pinned so it is a decision rather than a surprise:
    * today [[Renderer.renderNodeById]] splices a node's children into its own
    * bytes, so a container's rendering moves when a child's entity moves while
    * its own key stands still. That is exactly the "too coarse" failure — and
    * the reason the key deliberately excludes children is that the render must
    * be split first (own markup with holes, then composition).
    */
  test("a container's bytes still depend on children the key excludes") {
    val root: NodeId = "c"
    assertEquals(
      renderer.renderInputs(root, line.head, Map.empty),
      renderer.renderInputs(root, line.last, Map.empty)
    )
    assertNotEquals(
      renderer.renderBody(line.head),
      renderer.renderBody(line.last)
    )
  }
}
