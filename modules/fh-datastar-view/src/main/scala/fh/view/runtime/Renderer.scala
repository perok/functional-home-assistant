package fh.view.runtime

import fh.view.model.Dashboard

import scala.jdk.CollectionConverters.*

/** Renders dashboard components and the full page from current entity state.
  *
  * Component-keyed: [[componentsFor]] gives the reverse index used by the live
  * update loop to find which components a changed entity affects. Re-rendering
  * a component always pulls its FULL slot set (a component may read several
  * entities).
  */
class Renderer(dashboard: Dashboard, templates: Templates) {

  /** Reverse index: entityId -> components that depend on it. */
  private val byEntity: Map[String, Set[String]] =
    dashboard.registry.toList
      .flatMap { case (componentId, cdef) =>
        cdef.entities.map(entity => entity -> componentId)
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  def componentsFor(entityId: String): Set[String] =
    byEntity.getOrElse(entityId, Set.empty)

  /** Render one component to an HTML fragment whose root carries
    * `id=componentId`.
    */
  def renderComponent(
      componentId: String,
      states: Map[String, EntityState]
  ): Option[String] =
    for {
      template <- templates.components.get(componentId)
      cdef <- dashboard.registry.get(componentId)
    } yield {
      val context = cdef.slots.map { case (slot, source) =>
        val value = states
          .get(source.entity)
          .map(_.slotValue(source.attribute))
          .getOrElse("")
        slot -> value
      }
      template.execute(context.asJava)
    }

  /** Render the full page: each component's HTML injected into the layout
    * shell.
    */
  def renderPage(states: Map[String, EntityState]): String = {
    val context: Map[String, String] =
      dashboard.registry.keys.flatMap { id =>
        renderComponent(id, states).map(id -> _)
      }.toMap
    templates.layout.execute(context.asJava)
  }
}
