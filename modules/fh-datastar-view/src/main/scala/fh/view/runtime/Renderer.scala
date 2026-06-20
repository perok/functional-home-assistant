package fh.view.runtime

import fh.view.model.{
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource
}

import scala.jdk.CollectionConverters.*

/** Renders the recursive dashboard layout tree from current entity state.
  *
  * Composition is done in Scala (row/column containers); leaves reference
  * shared templates by name. Addressable nodes get a stable, location-based id
  * derived from their index path in the tree ([[LayoutNode.pathId]]) — authors
  * never invent ids. [[componentsFor]] + [[dynamicContainerIds]] drive the live
  * update loop, and [[renderNodeById]] re-renders a single patchable node.
  */
class Renderer(dashboard: Dashboard, templates: Templates) {

  /** Every addressable node (Component / Dynamic) keyed by its generated id. */
  private val nodeIndex: Map[String, LayoutNode] = {
    def walk(node: LayoutNode, path: List[Int]): List[(String, LayoutNode)] =
      node match {
        case LayoutNode.Row(cs)      => children(cs, path)(walk)
        case LayoutNode.Column(cs)   => children(cs, path)(walk)
        case c: LayoutNode.Component => List(LayoutNode.pathId(path) -> c)
        case d: LayoutNode.Dynamic   => List(LayoutNode.pathId(path) -> d)
      }
    walk(dashboard.layout, Nil).toMap
  }

  /** Reverse index: entityId -> static components that depend on it. */
  private val byEntity: Map[String, Set[String]] =
    nodeIndex.toList
      .collect { case (id, c: LayoutNode.Component) => id -> c }
      .flatMap { case (id, c) => c.entities.map(_ -> id) }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** Dynamic group container ids — re-evaluated on every state change (their
    * membership is data-dependent, so they can't be reverse-indexed).
    */
  val dynamicContainerIds: List[String] =
    nodeIndex.collect { case (id, _: LayoutNode.Dynamic) => id }.toList

  def componentsFor(entityId: String): Set[String] =
    byEntity.getOrElse(entityId, Set.empty)

  /** Render the full page as the walked layout tree. */
  def renderPage(states: Map[String, EntityState]): String =
    s"""<main class="container">${render(
        dashboard.layout,
        Nil,
        states
      )}</main>"""

  /** Render a single addressable node (for live SSE patches). */
  def renderNodeById(
      id: String,
      states: Map[String, EntityState]
  ): Option[String] =
    nodeIndex.get(id).map {
      case c: LayoutNode.Component => renderComponent(id, c, states)
      case d: LayoutNode.Dynamic   => renderDynamic(id, d, states)
      case _                       => ""
    }

  private def children[A](nodes: List[LayoutNode], path: List[Int])(
      f: (LayoutNode, List[Int]) => List[A]
  ): List[A] =
    nodes.zipWithIndex.flatMap { case (n, i) => f(n, path :+ i) }

  private def render(
      node: LayoutNode,
      path: List[Int],
      states: Map[String, EntityState]
  ): String = {
    def renderChildren(cs: List[LayoutNode]): String =
      cs.zipWithIndex.map { case (c, i) =>
        render(c, path :+ i, states)
      }.mkString

    node match {
      case LayoutNode.Row(cs) =>
        s"""<div class="fh-row">${renderChildren(cs)}</div>"""
      case LayoutNode.Column(cs) =>
        s"""<div class="fh-col">${renderChildren(cs)}</div>"""
      case c: LayoutNode.Component =>
        renderComponent(LayoutNode.pathId(path), c, states)
      case d: LayoutNode.Dynamic =>
        renderDynamic(LayoutNode.pathId(path), d, states)
    }
  }

  private def renderComponent(
      id: String,
      c: LayoutNode.Component,
      states: Map[String, EntityState]
  ): String =
    renderTemplate(c.template, Map("id" -> id) ++ c.params, c.slots, states)

  private def renderDynamic(
      id: String,
      d: LayoutNode.Dynamic,
      states: Map[String, EntityState]
  ): String = {
    val children =
      states.toList
        .sortBy(_._1)
        .filter { case (entityId, st) =>
          d.query.forall(Renderer.matches(_, entityId, st))
        }
        .flatMap { case (entityId, st) =>
          d.cases
            .find(c => Renderer.matches(c.when, entityId, st))
            .map(renderCase(id, entityId, st, _, states))
        }
    s"""<div id="$id">${children.mkString}</div>"""
  }

  private def renderCase(
      groupId: String,
      entityId: String,
      st: EntityState,
      c: DynamicCase,
      states: Map[String, EntityState]
  ): String = {
    val label =
      st.attributes
        .get("friendly_name")
        .map(StateStore.jsonToString)
        .filter(_.nonEmpty)
        .getOrElse(entityId)
    val autoParams = Map(
      "id" -> s"${groupId}_${Renderer.sanitize(entityId)}",
      "entity" -> entityId,
      "label" -> label
    )
    // Rebind each slot to the matched entity (jsonnet uses a placeholder).
    val boundSlots = c.slots.view.mapValues(_.copy(entity = entityId)).toMap
    renderTemplate(c.template, autoParams ++ c.params, boundSlots, states)
  }

  private def renderTemplate(
      templateName: String,
      params: Map[String, String],
      slots: Map[String, SlotSource],
      states: Map[String, EntityState]
  ): String =
    templates.components.get(templateName) match {
      case None => ""
      case Some(tpl) =>
        val resolved = slots.map { case (slot, source) =>
          val value = states
            .get(source.entity)
            .map(_.slotValue(source.attribute))
            .filter(_.nonEmpty)
            .orElse(source.default)
            .getOrElse("")
          slot -> value
        }
        tpl.execute((params ++ resolved).asJava)
    }
}

object Renderer {

  /** Slug an entity id into a valid HTML id fragment. */
  def sanitize(entityId: String): String =
    entityId.replaceAll("[^A-Za-z0-9_]", "_")

  /** Evaluate a query predicate against one entity's live state. */
  def matches(p: Predicate, entityId: String, st: EntityState): Boolean =
    p match {
      case Predicate.And(items) => items.forall(matches(_, entityId, st))
      case Predicate.Or(items)  => items.exists(matches(_, entityId, st))
      case Predicate.Not(item)  => !matches(item, entityId, st)
      case Predicate.Cmp(property, op, value) =>
        val lhs = property match {
          case "domain" => entityId.takeWhile(_ != '.')
          case "state"  => st.state
          case other if other.startsWith("attr:") =>
            st.attributes
              .get(other.stripPrefix("attr:"))
              .map(StateStore.jsonToString)
              .getOrElse("")
          case _ => ""
        }
        val rhs = StateStore.jsonToString(value)
        op match {
          case Op.Eq => lhs == rhs
          case Op.Ne => lhs != rhs
          case Op.Lt | Op.Lte | Op.Gt | Op.Gte =>
            (lhs.toDoubleOption, rhs.toDoubleOption) match {
              case (Some(l), Some(r)) =>
                op match {
                  case Op.Lt  => l < r
                  case Op.Lte => l <= r
                  case Op.Gt  => l > r
                  case Op.Gte => l >= r
                  case _      => false
                }
              case _ => false
            }
        }
    }
}
