package fh.view.runtime

import com.samskivert.mustache.Template
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
  * [[componentsFor]] + [[affectedDynamicIds]] drive the live update loop, and
  * [[renderNodeById]] re-renders a single patchable node.
  *
  * A dashboard's **surfaces** (popups, later tabs) are separate layout trees
  * rendered on demand by [[renderSurface]] and kept live only while a
  * connection has them open. Their node ids are namespaced (`s_<id>__…`) so
  * they never collide with the main page; [[surfaceComponentsFor]] /
  * [[affectedSurfaceDynamicIds]] are the surface-scoped equivalents of the
  * main-page update indices.
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

  /** The surfaces baked into component `gid`'s host, ordered by their
    * `bakeIndex` (surface id as a stable tiebreak / fallback when a member
    * carries none). This is the ordered member list a cookie index selects
    * among.
    */
  private def bakeGroup(gid: String): List[String] =
    dashboard.surfaces.toList
      .collect {
        case (sid, s) if s.bakeInto.contains(gid) => (sid, s.bakeIndex)
      }
      .sortBy { case (sid, bi) => (bi.getOrElse(Int.MaxValue), sid) }
      .map(_._1)

  /** Resolve which member of `gid`'s bake group is active, given the client's
    * (untrusted) `uiState`. Parses `uiState.get(gid)` with `.toIntOption` and
    * keeps it only when it indexes a real member; otherwise falls back to the
    * group's `defaultOpen` member (or index 0). The second element is
    * `Some(warning)` ONLY when a value was present but off (unparseable, or an
    * int out of range) — `None` when the cookie is absent or valid. Pure: the
    * single source of truth for both the chosen index and the malformed check.
    */
  private[runtime] def resolveActive(
      gid: String,
      uiState: Map[String, String]
  ): (Int, Option[String]) = {
    val members = bakeGroup(gid)
    val n = members.size
    val fallback =
      members.indexWhere(sid =>
        dashboard.surfaces.get(sid).exists(_.defaultOpen)
      ) match {
        case -1 => 0
        case i  => i
      }
    uiState.get(gid) match {
      case None => (fallback, None)
      case Some(raw) =>
        raw.toIntOption.filter(i => i >= 0 && i < n) match {
          case Some(i) => (i, None)
          case None =>
            (
              fallback,
              Some(
                s"ui-state cookie fhui_$gid='$raw' is not a valid tab index " +
                  s"(0..${n - 1}); using $fallback"
              )
            )
        }
    }
  }

  /** Surfaces shown from the first paint with no user action, given the
    * client's `uiState` (default empty ⇒ today's behaviour). For each
    * `bakeInto` group the [[resolveActive]]-selected member is chosen;
    * ungrouped surfaces (`bakeInto = None`) contribute their `defaultOpen` ones
    * as before. A connection seeds its open set with these so the baked panels
    * receive live updates from the first paint (and on a navigate swap).
    */
  def selectedSurfaces(
      uiState: Map[String, String] = Map.empty
  ): Set[String] = {
    val (baked, unbaked) =
      dashboard.surfaces.toList.partition(_._2.bakeInto.isDefined)
    val fromGroups =
      baked
        .flatMap(_._2.bakeInto)
        .distinct
        .map(gid => bakeGroup(gid)(resolveActive(gid, uiState)._1))
        .toSet
    val fromUnbaked =
      unbaked.collect { case (sid, s) if s.defaultOpen => sid }.toSet
    fromGroups ++ fromUnbaked
  }

  /** Warnings for any bake group whose `uiState` value was present but off
    * (unparseable / out of range). Pure — returns data (the Server logs it), so
    * the renderer stays side-effect-free. Absent/valid cookies produce nothing.
    */
  def uiStateAnomalies(uiState: Map[String, String]): List[String] =
    dashboard.surfaces.toList
      .flatMap(_._2.bakeInto)
      .distinct
      .flatMap(gid => resolveActive(gid, uiState)._2)

  def componentsFor(entityId: String): Set[String] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty)

  /** Main-page node ids whose HTML this entity drives, scoped to one surface.
    */
  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[String] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty))

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

  /** The dashboard frame, compiled once (like [[themeStyle]]) — a Mustache
    * template with a single `{{{body}}}` hole, owning the `#dashboard` swap
    * target and (for a theme that uses popups) the popup host's placement. An
    * empty `theme.chrome` falls back to the minimal frame with no popup host —
    * see [[Theme.chrome]]/[[Dashboard.validate]] for the contract.
    */
  private val chromeTemplate: Template = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compiler.compile(chrome)
  }

  /** The dashboard body: theme + the walked layout tree, without the page
    * shell. This is what a navigate swap `inner`-patches into the stable
    * `#dashboard` container (so navigation also swaps the theme).
    */
  def renderBody(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    s"$themeStyle${render(dashboard.card, Nil, "", states, uiState)}"

  /** The full page: the theme's compiled `chrome` executed with `body =
    * renderBody(...)` — a stable `#dashboard` swap target (and, when the theme
    * provides one, the popup host) so in-place navigation and popups have fixed
    * patch targets.
    */
  def renderPage(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    chromeTemplate.execute(
      Renderer.javaContext(
        Map("body" -> renderBody(states, uiState)),
        Nil
      )
    )

  /** Render a surface's bare content, namespaced under its surface-scoped id
    * prefix (`s_<id>__…`, [[Renderer.surfacePrefix]]) so its inner nodes never
    * collide with the main page. Every surface is chrome-less — the host it
    * swaps into (the popup overlay or a `tabs` card's panel host) and any
    * frame/dialog around it lives in `theme.chrome`, not per-surface — so this
    * returns `render(...)` directly with no wrapper. `None` if the surface id
    * is unknown.
    */
  def renderSurface(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    dashboard.surfaces.get(surfaceId).map { s =>
      render(s.content, Nil, Renderer.surfacePrefix(surfaceId), states, uiState)
    }

  /** Render a single addressable node (for live SSE patches), main or surface.
    * `uiState` is threaded through so a node that owns a bake group (a `tabs`
    * host that also binds a live entity) re-bakes the client's cookie-selected
    * member on a live patch — not the default one.
    */
  def renderNodeById(
      id: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    allIndexed.get(id).map { case (node, path, prefix) =>
      render(node, path, prefix, states, uiState)
    }

  private def render(
      node: LayoutNode,
      path: List[Int],
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): String =
    node match {
      case c: LayoutNode.Component =>
        val id = idPrefix + LayoutNode.pathId(path)
        val childrenHtml = c.children.zipWithIndex.map { case (child, i) =>
          render(child, path :+ i, idPrefix, states, uiState)
        }
        // When this component's id owns a bake group (surfaces baked into it),
        // bake the `uiState`-selected member as its `{{{bakeAs}}}` var so a
        // `tabs` card's panel host renders the active tab on first paint, and
        // inject `bakeIndex` (a backend-known structural var, like `id`) so the
        // template can seed its signal to the selected index. The chrome wraps
        // the content just as a later open/switch would, so first-paint and
        // switch-back produce byte-identical HTML. With no cookie the selection
        // is the group's `defaultOpen` member (index 0) — behaviour identical to
        // before. No bake group → neither var is injected (absent Mustache vars
        // render empty).
        val group = bakeGroup(id)
        val (baked, structural): (Map[String, String], Map[String, String]) =
          if (group.isEmpty) (Map.empty, Map.empty)
          else {
            val idx = resolveActive(id, uiState)._1
            val sid = group(idx)
            val s = dashboard.surfaces(sid)
            (
              Map(
                s.bakeAs.getOrElse("") -> renderSurface(sid, states, uiState)
                  .getOrElse("")
              ),
              Map("bakeIndex" -> idx.toString)
            )
          }
        // `id` is a backend-injected template var (the author never supplies it).
        // Everything else fills from a slot, a baked surface var, or the injected
        // `bakeIndex`.
        val html = renderTemplate(
          c.card,
          Map("id" -> id) ++ structural ++ baked,
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
        .filter { case (_, st) =>
          d.query.forall(Renderer.matches(_, st))
        }
        .sortBy(_._1)
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
      case None =>
        // Unreachable by construction: Dashboard.validate resolves every card
        // reference before a Renderer is built.
        throw new IllegalStateException(
          s"unknown card '$cardName' — validate should have rejected this dashboard"
        )
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
        // Ordering ops compare numerically, and are false unless both sides
        // parse as numbers; equality ops compare the raw strings.
        def numeric(cmp: (Double, Double) => Boolean): Boolean =
          (lhs.toDoubleOption, rhs.toDoubleOption) match {
            case (Some(l), Some(r)) => cmp(l, r)
            case _                  => false
          }
        op match {
          case Op.Eq  => lhs == rhs
          case Op.Ne  => lhs != rhs
          case Op.Lt  => numeric(_ < _)
          case Op.Lte => numeric(_ <= _)
          case Op.Gt  => numeric(_ > _)
          case Op.Gte => numeric(_ >= _)
        }
    }
}
