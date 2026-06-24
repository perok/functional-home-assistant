package fh.view.runtime

import fh.view.model.{
  Dashboard,
  DynamicCase,
  LayoutNode,
  Op,
  Predicate,
  SlotSource,
  Surface
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
  *
  * A dashboard's **surfaces** (popups, later tabs) are separate layout trees
  * rendered on demand by [[renderSurface]] and kept live only while a
  * connection has them open. Their node ids are namespaced (`s_<id>__…`) so
  * they never collide with the main page; [[surfaceComponentsFor]] /
  * [[surfaceDynamicIds]] are the surface-scoped equivalents of the main-page
  * update indices.
  */
class Renderer(
    dashboard: Dashboard,
    templates: Templates,
    transforms: Transforms
) {

  /** An addressable index over one layout tree; generated ids carry `idPrefix`
    * (empty for the main page, `s_<id>__` for a surface).
    */
  private class Index(root: LayoutNode, val idPrefix: String) {
    val indexed: Map[String, (LayoutNode, List[Int])] = {
      def walk(
          node: LayoutNode,
          path: List[Int]
      ): List[(String, (LayoutNode, List[Int]))] = {
        val self = (idPrefix + LayoutNode.pathId(path)) -> (node, path)
        node match {
          case c: LayoutNode.Component =>
            self :: c.children.zipWithIndex.flatMap { case (ch, i) =>
              walk(ch, path :+ i)
            }
          case _: LayoutNode.Dynamic => List(self)
        }
      }
      walk(root, Nil).toMap
    }

    val byEntity: Map[String, Set[String]] =
      indexed.toList
        .collect { case (id, (c: LayoutNode.Component, _)) => id -> c }
        .flatMap { case (id, c) => c.liveEntities.map(_ -> id) }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

    val dynamicIds: List[String] =
      indexed.collect { case (id, (_: LayoutNode.Dynamic, _)) => id }.toList
  }

  private val mainIndex = new Index(dashboard.card, "")

  private val surfaceIndexes: Map[String, Index] =
    dashboard.surfaces.map { case (sid, s) =>
      sid -> new Index(s.content, Renderer.surfacePrefix(sid))
    }

  /** Every addressable node (main + all surfaces) keyed by its generated id,
    * paired with its path and the id prefix needed to re-render it.
    */
  private val allIndexed: Map[String, (LayoutNode, List[Int], String)] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap { idx =>
      idx.indexed.map { case (id, (n, p)) => id -> (n, p, idx.idPrefix) }
    }.toMap

  /** Dynamic group container ids on the main page — re-evaluated on every state
    * change (their membership is data-dependent, so they can't be
    * reverse-indexed).
    */
  val dynamicContainerIds: List[String] = mainIndex.dynamicIds

  /** Surfaces shown by default on the main page — each tabs container's first
    * (`initial`) panel. A connection seeds its open set with these so the baked
    * default tab receives live updates from the first paint (and on a navigate
    * swap), with no user interaction.
    */
  val defaultOpenSurfaces: Set[String] =
    mainIndex.indexed.values
      .collect {
        case (c: LayoutNode.Component, _) if c.card == "tabs" =>
          c.params.get("initial")
      }
      .flatten
      .toSet

  def componentsFor(entityId: String): Set[String] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty)

  /** Main-page node ids whose HTML this entity drives, scoped to one surface.
    */
  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[String] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty))

  def surfaceDynamicIds(surfaceId: String): List[String] =
    surfaceIndexes.get(surfaceId).fold(List.empty)(_.dynamicIds)

  /** The surface's declaration (content/group/mount), if it exists. */
  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  /** External stylesheet URLs the theme wants `<link>`-ed (e.g. Pico). */
  def stylesheets: List[String] = dashboard.theme.stylesheets

  /** The theme as one `<style>` block: design tokens as `:root` custom
    * properties (dark overrides under `@media (prefers-color-scheme: dark)`, so
    * the page follows the browser) followed by the theme's inline `styles`.
    * Lives inside the patched body so a live reload (or a navigate swap)
    * repaints it. Empty when the theme carries no tokens or styles.
    */
  private val themeStyle: String = {
    val theme = dashboard.theme
    def vars(tokens: Map[String, String]): String =
      tokens.toList
        .sortBy(_._1)
        .map { case (name, value) => s"--$name:$value;" }
        .mkString

    val parts = List(
      if (theme.tokens.isEmpty) ""
      else s":root{color-scheme:light dark;${vars(theme.tokens)}}",
      if (theme.tokensDark.isEmpty) ""
      else
        s"@media (prefers-color-scheme:dark){:root{${vars(theme.tokensDark)}}}",
      theme.styles
    ).filter(_.nonEmpty)

    if (parts.isEmpty) "" else parts.mkString("<style>", "", "</style>")
  }

  /** The dashboard body: theme + the walked layout tree, without the page
    * shell. This is what a navigate swap `inner`-patches into the stable
    * `#dashboard` container (so navigation also swaps the theme).
    */
  def renderBody(states: Map[String, EntityState]): String =
    s"$themeStyle${render(dashboard.card, Nil, "", states)}"

  /** The full page: a stable shell (`#dashboard` body + `#popups` overlay
    * mount) so in-place navigation and popups have fixed patch targets.
    */
  def renderPage(states: Map[String, EntityState]): String =
    s"""<main class="container" id="dashboard">${renderBody(
        states
      )}</main><div id="popups"></div>"""

  /** Render a surface wrapped in its mount element — a `<dialog open>` carrying
    * the surface's root id (`s_<id>`), so closing can `remove` it by selector.
    * `None` if the surface id is unknown.
    */
  def renderSurface(
      surfaceId: String,
      states: Map[String, EntityState]
  ): Option[String] =
    dashboard.surfaces.get(surfaceId).map { s =>
      val rootId = Renderer.surfaceRootId(surfaceId)
      val inner =
        render(s.content, Nil, Renderer.surfacePrefix(surfaceId), states)
      s.mount match {
        // An inline mount (a tab panel) renders into a named container in the
        // page and is swapped by an `inner` patch (one of its group at a time),
        // so it needs no overlay chrome or close control — just the id'd
        // wrapper so live patches and group-eviction can target it.
        case Some(_) =>
          s"""<div id="$rootId" class="tab-panel-content">$inner</div>"""
        // The default overlay popup: a modal `<dialog>` appended to `#popups`,
        // with a close control wired to the (backend-known) id so inline popups
        // close without the author knowing the generated id. Authors can still
        // add their own `closePopup(id)` button to a registered surface.
        case None =>
          val close =
            s"""<button class="popup-close" data-on:click="@post('/sse/surface/close/$surfaceId')">✕</button>"""
          s"""<dialog id="$rootId" open class="popup">$close$inner</dialog>"""
      }
    }

  /** Render a single addressable node (for live SSE patches), main or surface.
    */
  def renderNodeById(
      id: String,
      states: Map[String, EntityState]
  ): Option[String] =
    allIndexed.get(id).map { case (node, path, prefix) =>
      render(node, path, prefix, states)
    }

  private def render(
      node: LayoutNode,
      path: List[Int],
      idPrefix: String,
      states: Map[String, EntityState]
  ): String =
    node match {
      case c: LayoutNode.Component =>
        val id = idPrefix + LayoutNode.pathId(path)
        val childrenHtml = c.children.zipWithIndex.map { case (child, i) =>
          render(child, path :+ i, idPrefix, states)
        }
        // A tabs container bakes its `initial` panel inline (rendered with that
        // surface's id prefix, so the baked HTML and a later switch-back share
        // ids) — the first paint shows it with no open round-trip. `panel` is
        // empty for every other component (the template ignores it).
        val panel = c.params
          .get("initial")
          .flatMap(initId =>
            dashboard.surfaces
              .get(initId)
              .map(s =>
                render(s.content, Nil, Renderer.surfacePrefix(initId), states)
              )
          )
          .getOrElse("")
        // `id` stays available to the template (e.g. the slider derives its
        // signal name from it) even though it is no longer the morph target.
        val html = renderTemplate(
          c.card,
          Map("id" -> id, "panel" -> panel) ++ c.params,
          c.slots,
          childrenHtml,
          states
        )
        // The backend owns the Datastar morph target: any component that can be
        // live-patched (it depends on entities) is wrapped in an id'd element so
        // templates don't have to carry `id="{{id}}"` themselves. The wrapper is
        // layout-neutral (`.fh-cell { display: contents }`).
        if (c.liveEntities.nonEmpty)
          s"""<div class="fh-cell" id="$id">$html</div>"""
        else html
      case d: LayoutNode.Dynamic =>
        renderDynamic(idPrefix + LayoutNode.pathId(path), d, states)
    }

  private def renderDynamic(
      id: String,
      d: LayoutNode.Dynamic,
      states: Map[String, EntityState]
  ): String = {
    val children =
      states.toList
        .sortBy(_._1)
        .filter { case (_, st) =>
          d.query.forall(Renderer.matches(_, st))
        }
        .flatMap { case (entityId, st) =>
          d.cases
            .find(c => Renderer.matches(c.when, st))
            .map(renderCase(id, entityId, _, states))
        }
    s"""<div id="$id">${children.mkString}</div>"""
  }

  private def renderCase(
      groupId: String,
      entityId: String,
      c: DynamicCase,
      states: Map[String, EntityState]
  ): String = {
    // The matched entity is injected as the `entity_id` param, so every
    // inheriting slot (no own `entityId`) binds to it — including the label slot
    // (`$attr.friendly_name`). A slot that names its own entity keeps it (a
    // cross-entity slot is not overridden by the match), and a constant literal
    // reads no entity at all.
    val autoParams = Map(
      "id" -> s"${groupId}_${Renderer.sanitize(entityId)}",
      "entity_id" -> entityId
    )
    renderTemplate(c.card, autoParams ++ c.params, c.slots, Nil, states)
  }

  private def renderTemplate(
      cardName: String,
      params: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: List[String],
      states: Map[String, EntityState]
  ): String =
    templates.components.get(cardName) match {
      case None => "" // TODO should be a hard failure?
      case Some(tpl) =>
        val resolved = slots.map { case (slot, source) =>
          val value = source.literal match {
            // A constant literal: used verbatim, reading no entity and running no
            // transform — the cheap path for a hardcoded label/action.
            case Some(text) => text
            case None       =>
              // The slot's entity is its own `entityId`, or the component's
              // `entity_id` param when it leaves it unset (slot-level
              // inheritance — the card's one entity, or the matched entity in a
              // dynamic case). It resolves against that entity's state even
              // before any has arrived (so `$domain` actions still resolve);
              // with no entity at all the state is empty.
              // TODO should throw or log a warning?
              val srcEntity = source.entityId.orElse(params.get("entity_id"))
              val st =
                srcEntity
                  .flatMap(states.get)
                  .getOrElse(
                    EntityState(srcEntity.getOrElse(""), "", Map.empty)
                  )
              // An unavailable/unknown entity on a value-display slot shows its
              // raw state and never enters the transform — that bypass, not the
              // transform, is what keeps such states readable. Identity slots
              // leave it off so an action still resolves.
              if (source.bypassUnavailable && st.unavailable) st.state
              else {
                val out = transforms.run(source.transform, st)
                if (out.nonEmpty) out else source.default.getOrElse("")
              }
          }
          slot -> value
        }
        tpl.execute(Renderer.javaContext(params ++ resolved, childrenHtml))
    }
}

object Renderer {

  /** Build a renderer from a (validated) dashboard, compiling its template and
    * transform libraries up front. The single construction point so call sites
    * never wire `Templates`/`Transforms` by hand.
    */
  def create(dashboard: Dashboard): Renderer =
    new Renderer(
      dashboard,
      Templates.from(dashboard),
      Transforms.from(dashboard)
    )

  /** Id prefix for a surface's inner nodes (`s_<id>__c_0`). */
  def surfacePrefix(surfaceId: String): String =
    s"${surfaceRootId(surfaceId)}__"

  /** A surface's mount/root element id (`s_<id>`) — the `remove` selector on
    * close.
    */
  def surfaceRootId(surfaceId: String): String = s"s_${sanitize(surfaceId)}"

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
      val _ = m.put("children", list)
    }
    m
  }

  /** Evaluate a query predicate against one entity's live state. The entity's
    * id and domain come off the [[EntityState]] itself.
    */
  def matches(p: Predicate, st: EntityState): Boolean =
    p match {
      case Predicate.And(items) => items.forall(matches(_, st))
      case Predicate.Or(items)  => items.exists(matches(_, st))
      case Predicate.Not(item)  => !matches(item, st)
      case Predicate.Cmp(property, op, value) =>
        val lhs = property match {
          case "domain" => st.domain
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
