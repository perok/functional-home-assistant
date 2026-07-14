package fh.view.testkit

import fh.view.model.{Dashboard, LayoutNode, SlotSource}
import fh.view.testkit.DashboardBuilders.col

/** The world a functional test drives: a [[Dashboard]] under test plus the
  * entities a [[FakeHomeAssistant]] is seeded from — assembled with a builder
  * so a test never has to keep those two in sync by hand.
  *
  * The entities the dashboard REFERENCES are seeded automatically: [[entities]]
  * walks the produced dashboard for every entity id its cards bind and resolves
  * each against the [[Scene.registry]] (the shared [[HouseFixture]] house), so
  * a card placed in the layout brings its entity with it. `[[entity]]` adds
  * entities BEYOND what the dashboard references — a control-only test that
  * clicks a light with no card for it, a dynamic-group member matched by query
  * rather than named in a slot — and doubles as a resolution source, so a test
  * can introduce (or restate) an entity the registry doesn't carry.
  *
  * Two entry points feed the same machinery: [[Scene.empty]] accumulates root
  * cards via [[card]] (the hand-built Tier-B dashboard), while [[Scene.of]]
  * wraps a ready dashboard (the Pkl-built Tier-A capstone) — both auto-seed
  * what they reference.
  */
final class Scene private (
    private val children: Vector[LayoutNode],
    private val prebuilt: Option[Dashboard],
    private val extras: Vector[FixtureEntity]
) {

  /** Append a card to the root `col` (only meaningful for a [[Scene.empty]]
    * scene; a prebuilt dashboard already fixes its layout). The entity the card
    * binds is seeded automatically — see [[entities]].
    */
  def card(node: LayoutNode): Scene =
    new Scene(children :+ node, prebuilt, extras)

  /** Seed one entity BEYOND what the dashboard references (a control target
    * with no card, a dynamic-group member). Also a resolution source, so this
    * entity satisfies a dashboard reference the registry doesn't carry — or
    * restates one it does, overriding its seeded state.
    */
  def entity(e: FixtureEntity): Scene =
    new Scene(children, prebuilt, extras :+ e)

  /** [[entity]] for several at once. */
  def entities(es: FixtureEntity*): Scene =
    new Scene(children, prebuilt, extras ++ es)

  /** The dashboard under test: the prebuilt one, or a [[FixtureDashboard]] over
    * the accumulated root cards.
    */
  def dashboard: Dashboard =
    prebuilt.getOrElse(FixtureDashboard.build(col(children*)))

  /** The seed for the fake: every entity the dashboard references (resolved
    * against the registry + [[extras]]) followed by any extras not already
    * pulled in, deduplicated by id. An extra shadows the registry, so a test
    * can seed a referenced entity at a non-default state. A referenced id that
    * neither the registry nor an extra supplies fails loudly here — the "the
    * entities exist" check the builder now owns.
    */
  def entities: List[FixtureEntity] = {
    val resolve = Scene.registry ++ extras.map(e => e.entityId -> e)
    val referenced = Scene.referencedEntityIds(dashboard).map { id =>
      resolve.getOrElse(
        id,
        throw new IllegalStateException(
          s"dashboard references entity '$id' that no fixture supplies — add it " +
            s"to HouseFixture or seed it with `.entity(...)`. Known: " +
            resolve.keys.toList.sorted.mkString(", ")
        )
      )
    }
    (referenced ++ extras).distinctBy(_.entityId)
  }
}

object Scene {

  /** The shared house every scene resolves references against, keyed by id. */
  private val registry: Map[String, FixtureEntity] =
    HouseFixture.all.map(e => e.entityId -> e).toMap

  /** A scene that builds its dashboard from cards added via [[Scene.card]]. */
  def empty: Scene = new Scene(Vector.empty, None, Vector.empty)

  /** A scene over a ready-made dashboard (e.g. one evaluated through the Pkl
    * pipeline); its referenced entities still auto-seed.
    */
  def of(dashboard: Dashboard): Scene =
    new Scene(Vector.empty, Some(dashboard), Vector.empty)

  /** Every entity id the dashboard binds: each slot's own `entityId`, each
    * component's subject `entity_id` (the card's one entity), across the main
    * layout and every surface's content. Dynamic groups match by query rather
    * than a named id, so their members are seeded via [[Scene.entity]].
    */
  private def referencedEntityIds(d: Dashboard): List[String] = {
    def fromSlots(
        slots: Map[String, SlotSource],
        subject: Option[String]
    ): List[String] =
      slots.values.toList.flatMap(_.entityId) ++ subject.toList

    def walk(n: LayoutNode): List[String] = n match {
      case c: LayoutNode.Component =>
        fromSlots(c.slots, c.subjectEntity) ++ c.children.flatMap(walk)
      case dyn: LayoutNode.Dynamic =>
        dyn.cases.flatMap(_.slots.values.toList.flatMap(_.entityId))
    }

    (walk(d.card) ++ d.surfaces.values.toList.flatMap(s =>
      walk(s.content)
    )).distinct
  }
}
