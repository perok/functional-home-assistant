package fh.view.testkit

import fh.view.model.{Dashboard, LayoutNode, SlotSource}
import fh.view.testkit.DashboardBuilders.col

/** A [[Dashboard]] plus the entities a [[FakeHomeAssistant]] is seeded from, so
  * a test never keeps the two in sync by hand. Every entity the dashboard
  * references (slots, subjects, set candidates) is resolved against the shared
  * [[HouseFixture]] house and seeded; [[entity]] adds one beyond that, such as
  * a control target with no card, and can override or supply a referenced one.
  * [[Scene.empty]] builds from cards, [[Scene.of]] wraps a ready dashboard.
  */
final class Scene private (
    private val children: Vector[LayoutNode],
    private val prebuilt: Option[Dashboard],
    private val extras: Vector[FixtureEntity]
) {

  /** Only meaningful for [[Scene.empty]]; a prebuilt dashboard fixes its
    * layout.
    */
  def card(node: LayoutNode): Scene =
    new Scene(children :+ node, prebuilt, extras)

  /** Also a resolution source: it supplies a referenced entity the registry
    * lacks, or overrides a seeded state.
    */
  def entity(e: FixtureEntity): Scene =
    new Scene(children, prebuilt, extras :+ e)

  def entities(es: FixtureEntity*): Scene =
    new Scene(children, prebuilt, extras ++ es)

  def dashboard: Dashboard =
    prebuilt.getOrElse(FixtureDashboard.build(col(children*)))

  /** Deduplicated by id, an extra shadowing the registry. A referenced id
    * nothing supplies fails loudly here.
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

  private val registry: Map[String, FixtureEntity] =
    HouseFixture.all.map(e => e.entityId -> e).toMap

  def empty: Scene = new Scene(Vector.empty, None, Vector.empty)

  def of(dashboard: Dashboard): Scene =
    new Scene(Vector.empty, Some(dashboard), Vector.empty)

  /** Slot `entityId`s, subject `entity_id`s and set candidates, across the
    * layout and every surface.
    */
  private def referencedEntityIds(d: Dashboard): List[String] = {
    def fromSlots(
        slots: Map[String, SlotSource],
        subject: Option[String]
    ): List[String] =
      slots.values.toList.flatMap(_.entityId) ++ subject.toList

    def walk(n: LayoutNode): List[String] = n match {
      case c: LayoutNode.Component =>
        fromSlots(c.slots, c.subjectEntity) ++ c.allChildren.flatMap(walk)
      case set: LayoutNode.SetNode =>
        // A clause node is an ordinary component, reached by the same walk.
        set.candidates ++ set.members.values.toList
          .flatMap(_.clauses)
          .flatMap(cl => walk(cl.node))
    }

    (walk(d.card) ++ d.surfaces.values.toList.flatMap(s =>
      walk(s.content)
    )).distinct
  }
}
