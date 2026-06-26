package fh.view.runtime

import fh.view.model.{
  Dashboard,
  DynamicCase,
  LayoutNode,
  MountKind,
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
          case _: LayoutNode.Mount   => List(self)
        }
      }
      walk(root, Nil).toMap
    }

    /** Mount nodes in this tree, keyed by their element id (= prefixed pathId,
      * what a surface's `mount` field names). The renderer's `mountKind` reads
      * this so the open path picks insertion mode + chrome from the host, not a
      * card name.
      */
    val mounts: Map[String, MountKind] =
      indexed.collect { case (id, (m: LayoutNode.Mount, _)) => id -> m.mode }

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

  /** Memo for identity-derived (`reactive: false`) slot values, keyed by
    * `(entityId, transform)`. Such a slot reads only the entity's immutable
    * identity (`$domain`/`$entity_id`), so its value is stable for the life of
    * the entity — resolve once, then reuse (see `renderTemplate`). Lives on the
    * Renderer, which is rebuilt on hot-reload/navigate, so it never needs
    * invalidation; concurrent fibers may race to fill an entry but compute the
    * same value.
    */
  private val identityCache =
    new java.util.concurrent.ConcurrentHashMap[(String, String), String]()

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

  /** Every mount host (main + surfaces) keyed by element id, plus the built-in
    * page-level overlay `#popups`. The default for an unknown id is `Inline` —
    * a still-registered legacy panel id behaves as a tab panel.
    */
  private val mountKinds: Map[String, MountKind] =
    (mainIndex :: surfaceIndexes.values.toList)
      .flatMap(_.mounts)
      .toMap + ("popups" -> MountKind.Overlay)

  /** The [[MountKind]] of the mount a surface targets (default `#popups` when a
    * surface declares none); drives insertion mode + chrome in `Server`.
    */
  def mountKind(id: String): MountKind =
    mountKinds.getOrElse(
      id,
      if (id == "popups") MountKind.Overlay else MountKind.Inline
    )

  /** Dynamic group container ids on the main page. Their membership is
    * data-dependent (can't be reverse-indexed by entity), but a single change
    * only moves the *changed* entity in or out of a group, so a container needs
    * re-rendering only when the change touches its query — see
    * [[affectedDynamicIds]]. (The full list is kept for callers that re-render
    * every group, e.g. a navigate/reload repaint.)
    */
  val dynamicContainerIds: List[String] = mainIndex.dynamicIds

  /** Each dynamic container's query, by id (main + surfaces), for the
    * affected-by-change test. A group with no query matches every entity.
    */
  private val dynamicQueries: Map[String, Option[Predicate]] =
    allIndexed.collect { case (id, (d: LayoutNode.Dynamic, _, _)) =>
      id -> d.query
    }

  private def dynamicAffected(
      id: String,
      change: StateChange
  ): Boolean = {
    val query = dynamicQueries.getOrElse(id, None)
    def matchesQuery(st: EntityState): Boolean =
      query.forall(Renderer.matches(_, st))
    // The change touches the group iff the entity matched its query BEFORE or
    // AFTER the change — covering an add (¬prev ∧ cur), a remove (prev ∧ ¬cur),
    // and an in-place update of a member (prev ∧ cur). An entity that matches
    // neither leaves the group's HTML identical, so the group is skipped.
    change.previous.exists(matchesQuery) || matchesQuery(change.current)
  }

  /** Main-page dynamic container ids whose membership/contents this change can
    * affect — the changed entity matched the group's query before or after the
    * change. Unrelated entities are filtered out, sparing the whole-group
    * re-scan + re-render on every event.
    */
  def affectedDynamicIds(change: StateChange): List[String] =
    mainIndex.dynamicIds.filter(dynamicAffected(_, change))

  /** Surfaces shown from the first paint with no user action — every surface
    * flagged [[Surface.defaultOpen]] (a tabs container's default panel today).
    * A connection seeds its open set with these so the baked default panel
    * receives live updates from the first paint (and on a navigate swap). Read
    * straight off the surface registry, so the backend stays fully
    * card-agnostic — no `initial` slot, no card name.
    */
  val defaultOpenSurfaces: Set[String] =
    dashboard.surfaces.collect { case (sid, s) if s.defaultOpen => sid }.toSet

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

  /** Like [[affectedDynamicIds]], scoped to one open surface. */
  def affectedSurfaceDynamicIds(
      surfaceId: String,
      change: StateChange
  ): List[String] =
    surfaceIndexes
      .get(surfaceId)
      .fold(List.empty[String])(_.dynamicIds.filter(dynamicAffected(_, change)))

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
      )}</main>${renderMountElement(
        "popups",
        MountKind.Overlay,
        None,
        states
      )}"""

  /** Render a surface wrapped in its chrome card — the overlay `popup`
    * (`<dialog open>` + a wrapper-supplied close control) or, for an inline
    * mount (a tab panel), the chromeless `tabPanel`. Both carry the surface
    * root id (`s_<id>`) so live patches / group-eviction / close-by-selector
    * can target it. The chrome lives in the card library (`popup`/`tabPanel`),
    * not here — the renderer only renders the content as `children` and injects
    * the id + close action. `None` if the surface id is unknown.
    */
  def renderSurface(
      surfaceId: String,
      states: Map[String, EntityState]
  ): Option[String] =
    dashboard.surfaces.get(surfaceId).map { s =>
      val rootId = Renderer.surfaceRootId(surfaceId)
      val inner =
        render(s.content, Nil, Renderer.surfacePrefix(surfaceId), states)
      // Chrome follows the MOUNT KIND, not `mount.isEmpty`: an Inline mount (a
      // tab panel) gets the bare `tabPanel`, an Overlay mount (`#popups`) the
      // `<dialog>` `popup` with a close control.
      mountKind(s.mount.getOrElse("popups")) match {
        case MountKind.Inline =>
          renderChrome("tabPanel", Map("id" -> rootId), inner)
        case MountKind.Overlay =>
          renderChrome(
            "popup",
            Map(
              "id" -> rootId,
              "closeAction" -> s"@post('/sse/surface/close/$surfaceId')"
            ),
            inner
          )
      }
    }

  /** Wrap already-rendered surface content in a chrome card
    * (`popup`/`tabPanel`) from the library, splicing `inner` as the card's
    * single child and injecting the backend-known vars (`id`, `closeAction`).
    * Falls back to the bare inner HTML if the chrome card is absent.
    */
  private def renderChrome(
      card: String,
      injected: Map[String, String],
      inner: String
  ): String =
    templates.components
      .get(card)
      .fold(inner)(_.execute(Renderer.javaContext(injected, List(inner))))

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
        // A tabs container bakes its default panel inline via the SAME
        // `renderSurface` path a later switch-back uses (chrome + surface-id
        // prefix), so the baked HTML and a switch match exactly — the first
        // paint shows it with no open round-trip. The panel is the default-open
        // surface that mounts into THIS component (its `mount` slot equals the
        // surface's `mount`), so nothing card-specific is read — `panel` is empty
        // for every component without a matching default-open surface.
        val panel = c.slots
          .get("mount")
          .flatMap(_.literal)
          .flatMap { mountId =>
            dashboard.surfaces
              .collectFirst {
                case (sid, s) if s.defaultOpen && s.mount.contains(mountId) =>
                  sid
              }
              .flatMap(renderSurface(_, states))
          }
          .getOrElse("")
        // `id`/`panel` are backend-injected template vars (the author never
        // supplies them); `id` stays available to the template (e.g. the slider
        // derives its signal name from it) even though it is no longer the morph
        // target. Everything else fills from a slot.
        val html = renderTemplate(
          c.card,
          Map("id" -> id, "panel" -> panel),
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
      case m: LayoutNode.Mount =>
        renderMountElement(
          idPrefix + LayoutNode.pathId(path),
          m.mode,
          m.signals,
          states
        )
    }

  /** Render a mount host: an addressable `<div>` whose initial content is the
    * baked default-open surface(s) that target it (an Inline mount shows its
    * one default panel; an Overlay stacks any default-open popups). Its element
    * id is the id surfaces name, so a later open patches into the same node.
    * Shared by the layout [[LayoutNode.Mount]] and the page-level `#popups`
    * (`renderPage`).
    */
  private def renderMountElement(
      id: String,
      kind: MountKind,
      signals: Option[String],
      states: Map[String, EntityState]
  ): String = {
    // Inline panels are layout-neutral (`display:contents`); the visible wrapper
    // comes from the `tabPanel` chrome around the surface content.
    val cls = kind match {
      case MountKind.Inline  => """ class="tab-panel""""
      case MountKind.Overlay => ""
    }
    val sig = signals.fold("")(s => s""" data-signals="$s"""")
    val defaults = dashboard.surfaces.toList.collect {
      case (sid, s) if s.defaultOpen && s.mount.getOrElse("popups") == id =>
        sid
    }.sorted
    val baked = kind match {
      case MountKind.Inline =>
        defaults.headOption.flatMap(renderSurface(_, states)).getOrElse("")
      case MountKind.Overlay =>
        defaults.flatMap(renderSurface(_, states)).mkString
    }
    s"""<div$cls id="$id"$sig>$baked</div>"""
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
    // Set the matched entity as the card's subject: a literal `entity_id` slot
    // (the case stripped the build-time one). Every inheriting slot then binds
    // to it — including the label (`$attr.friendly_name`). A slot that names its
    // own entity keeps it; a constant literal reads no entity at all.
    val slots =
      c.slots.updated("entity_id", SlotSource(literal = Some(entityId)))
    val id = s"${groupId}_${Renderer.sanitize(entityId)}"
    renderTemplate(c.card, Map("id" -> id), slots, Nil, states)
  }

  private def renderTemplate(
      cardName: String,
      injected: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: List[String],
      states: Map[String, EntityState]
  ): String =
    templates.components.get(cardName) match {
      case None      => "" // TODO should be a hard failure?
      case Some(tpl) =>
        // The card's subject entity: the `entity_id` slot resolved against its
        // OWN entity (it DEFINES the subject, so it never inherits it). Normally
        // a literal; a transform form (indirection) grounds on its own entityId.
        val subject: Option[String] =
          slots.get("entity_id").map { s =>
            s.literal.getOrElse(resolveSlot(s.entityId, s, states))
          }
        val resolved = slots.map { case (slot, source) =>
          val value = source.literal match {
            // A constant literal: used verbatim, reading no entity and running no
            // transform — the cheap path for a hardcoded label/action.
            case Some(text) => text
            case None       =>
              // A slot's entity is its own `entityId`, or the subject when it
              // leaves it unset (slot-level inheritance — the card's entity, or
              // the matched entity in a dynamic case). The `entity_id` slot
              // itself never inherits — it is the subject.
              val srcEntity =
                if (slot == "entity_id") source.entityId
                else source.entityId.orElse(subject)
              // A `reactive: false` slot is identity-derived — its transform
              // reads only `$domain`/`$entity_id` (a service action, the
              // slider's domain config), both immutable for the life of the
              // entity. So its value never changes: resolve it ONCE per
              // (entity, transform) and reuse forever. This is what keeps the
              // dynamic render path slick — a dynamic group re-renders every
              // matched card on every event, but those cards' action/config
              // slots become a cache lookup, not a JSONata eval. Live slots
              // (`reactive: true`) always re-resolve. `$entity_id` is in the key
              // (the action URL embeds it), so two entities never collide.
              if (!source.reactive)
                identityCache.computeIfAbsent(
                  (srcEntity.getOrElse(""), source.transform),
                  _ => resolveSlot(srcEntity, source, states)
                )
              else resolveSlot(srcEntity, source, states)
          }
          slot -> value
        }
        tpl.execute(Renderer.javaContext(injected ++ resolved, childrenHtml))
    }

  /** Resolve a non-literal slot's value against its producing entity's state.
    * It resolves even before any state has arrived (so a `$domain` action still
    * resolves); with no entity at all the state is empty.
    */
  private def resolveSlot(
      srcEntity: Option[String],
      source: SlotSource,
      states: Map[String, EntityState]
  ): String = {
    val st =
      srcEntity
        .flatMap(states.get)
        .getOrElse(EntityState(srcEntity.getOrElse(""), "", Map.empty))
    // An unavailable/unknown entity on a value-display slot shows its raw state
    // and never enters the transform — that bypass, not the transform, is what
    // keeps such states readable. Identity slots leave it off so an action still
    // resolves.
    if (source.bypassUnavailable && st.unavailable) st.state
    else {
      val out = transforms.run(source.transform, st)
      if (out.nonEmpty) out else source.default.getOrElse("")
    }
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

  // The id scheme lives in the model ([[LayoutNode]]) so the build-phase hoist
  // and the renderer share one story; these delegate.
  def surfacePrefix(surfaceId: String): String =
    LayoutNode.surfacePrefix(surfaceId)
  def surfaceRootId(surfaceId: String): String =
    LayoutNode.surfaceRootId(surfaceId)
  def sanitize(s: String): String = LayoutNode.sanitize(s)

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

  // TODO a macro for rawer performance?
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
                  case _      => false // TODO remove, no fallbacks necessary
                }
              case _ => false
            }
        }
    }
}
