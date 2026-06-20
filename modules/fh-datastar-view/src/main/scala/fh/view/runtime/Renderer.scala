package fh.view.runtime

import fh.view.model.{
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource
}

/** Renders the recursive dashboard layout tree from current entity state.
  *
  * Every node is a `Component` referencing a shared template by name; a
  * container is just a Component whose template splices its rendered `children`
  * (`{{#children}}{{{html}}}{{/children}}`), so container kinds (row, column,
  * grid, …) are defined as templates rather than special-cased here.
  *
  * Addressable nodes get a stable, location-based id derived from their index
  * path in the tree ([[LayoutNode.pathId]]) — authors never invent ids.
  * [[componentsFor]] + [[dynamicContainerIds]] drive the live update loop, and
  * [[renderNodeById]] re-renders a single patchable node.
  */
class Renderer(dashboard: Dashboard, templates: Templates) {

  /** Every addressable node keyed by its generated id, paired with its path
    * (needed to re-render a container's children by id).
    */
  private val indexed: Map[String, (LayoutNode, List[Int])] = {
    def walk(
        node: LayoutNode,
        path: List[Int]
    ): List[(String, (LayoutNode, List[Int]))] = {
      val self = LayoutNode.pathId(path) -> (node, path)
      node match {
        case c: LayoutNode.Component =>
          self :: c.children.zipWithIndex.flatMap { case (ch, i) =>
            walk(ch, path :+ i)
          }
        case _: LayoutNode.Dynamic => List(self)
      }
    }
    walk(dashboard.layout, Nil).toMap
  }

  /** Reverse index: entityId -> components that depend on it. */
  private val byEntity: Map[String, Set[String]] =
    indexed.toList
      .collect { case (id, (c: LayoutNode.Component, _)) => id -> c }
      .flatMap { case (id, c) => c.entities.map(_ -> id) }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** Dynamic group container ids — re-evaluated on every state change (their
    * membership is data-dependent, so they can't be reverse-indexed).
    */
  val dynamicContainerIds: List[String] =
    indexed.collect { case (id, (_: LayoutNode.Dynamic, _)) => id }.toList

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
    indexed.get(id).map { case (node, path) => render(node, path, states) }

  private def render(
      node: LayoutNode,
      path: List[Int],
      states: Map[String, EntityState]
  ): String =
    node match {
      case c: LayoutNode.Component =>
        val id = LayoutNode.pathId(path)
        val childrenHtml = c.children.zipWithIndex.map { case (child, i) =>
          render(child, path :+ i, states)
        }
        // `id` stays available to the template (e.g. the slider derives its
        // signal name from it) even though it is no longer the morph target.
        val html = renderTemplate(
          c.template,
          Map("id" -> id) ++ c.params,
          c.slots,
          childrenHtml,
          states
        )
        // The backend owns the Datastar morph target: any component that can be
        // live-patched (it depends on entities) is wrapped in an id'd element so
        // templates don't have to carry `id="{{id}}"` themselves. The wrapper is
        // layout-neutral (`.fh-cell { display: contents }`).
        if (c.entities.nonEmpty)
          s"""<div class="fh-cell" id="$id">$html</div>"""
        else html
      case d: LayoutNode.Dynamic =>
        renderDynamic(LayoutNode.pathId(path), d, states)
    }

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
    renderTemplate(c.template, autoParams ++ c.params, boundSlots, Nil, states)
  }

  private def renderTemplate(
      templateName: String,
      params: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: List[String],
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
        tpl.execute(Renderer.javaContext(params ++ resolved, childrenHtml))
    }
}

object Renderer {

  /** Slug an entity id into a valid HTML id fragment. */
  def sanitize(entityId: String): String =
    entityId.replaceAll("[^A-Za-z0-9_]", "_")

  /** Build the jmustache context at the Java boundary: the string slot/param
    * context plus, when present, a `children` list of `{html}` maps for
    * container templates (`{{#children}}{{{html}}}{{/children}}`). Kept here so
    * the rest of the renderer works in plain `Map[String, String]`.
    */
  private def javaContext(
      context: Map[String, String],
      childrenHtml: List[String]
  ): java.util.Map[String, AnyRef] = {
    val m = new java.util.HashMap[String, AnyRef](context.size + 1)
    context.foreach { case (k, v) => m.put(k, v) }
    if (childrenHtml.nonEmpty) {
      val list = new java.util.ArrayList[AnyRef](childrenHtml.size)
      childrenHtml.foreach(h =>
        list.add(java.util.Collections.singletonMap("html", h))
      )
      m.put("children", list)
    }
    m
  }

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
