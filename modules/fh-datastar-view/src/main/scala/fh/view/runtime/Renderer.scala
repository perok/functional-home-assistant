package fh.view.runtime

import com.samskivert.mustache.Template
import fh.view.build.LibPackage
import fh.view.model.{
  Access,
  Cell,
  Dashboard,
  DomId,
  LayoutNode,
  NodeId,
  Reads,
  SetId,
  SignalBind,
  SignalId,
  SlotSource,
  Surface
}

/** One node as a WHOLESALE render left it: the bytes a later per-node patch
  * would produce for it (the patch form — so a digest of these is comparable
  * with what the pull path renders), and the signals its inline seed just put
  * in that client's store.
  *
  * A product, not a sum: a node has bytes AND signals, established together by
  * the same render. Carrying them as one value is what lets a session's record
  * fall straight out of a page render rather than being re-derived by a second
  * pass over the painted ids.
  */
private[runtime] case class Painted(
    html: String,
    signals: Map[SignalId, String]
)

/** Which of a signal slot's two renderings a walk is producing (ADR 0017).
  *
  * The distinction exists only for slots with `signal = true`; every other slot
  * renders identically either way, and a node with no signal slot produces the
  * same bytes for both — [[Renderer.Traced]] relies on that, handing the same
  * `String` reference back rather than executing a template twice.
  *
  *   - [[Document]] — the value inline (which is what a JS-less browser gets,
  *     and all it ever gets) plus the `data-signals` seed, so the element
  *     carries its own signal and a first paint, a mount fill or a member
  *     insert needs no frame to be correct.
  *   - [[Patch]] — neither. The value is not in these bytes, so the node's
  *     digest does not move when only a signal slot does, `Patches.morph`
  *     suppresses the element patch, and one frame carries the values instead.
  *     That suppression IS the feature.
  */
private[runtime] enum SlotForm derives CanEqual {
  case Document, Patch

  def isPatch: Boolean = this == SlotForm.Patch
}

/** What a node's own rendering reads, reduced to a comparable value — see
  * [[Renderer.renderInputs]] for what goes in each half and why.
  *
  * The asymmetry to hold on to whenever this changes: a key that is TOO
  * DISCRIMINATING costs a wasted render, and the digest then shows nothing
  * moved so nothing is sent — CPU, no bug. A key that is TOO COARSE serves a
  * client bytes that no longer match its state, silently and permanently. When
  * in doubt, over-discriminate.
  */
case class RenderInputs(entities: Map[String, Long]) derives CanEqual {

  /** Whether this was rendered from a snapshot at or ahead of `other` on every
    * entity it reads — the partial order [[RenderCache]] uses to refuse an
    * install that would replace current bytes with superseded ones.
    *
    * PARTIAL on purpose. Different key sets are not ordered at all: an entity
    * appearing or vanishing changes what the node reads, not how fresh it is,
    * and calling that "behind" would let a stale generation sit unchallenged.
    * Only a same-shaped, entity-for-entity comparison answers `true`.
    */
  def isAtLeast(other: RenderInputs): Boolean =
    entities.sizeIs == other.entities.size &&
      other.entities.forall((e, v) => entities.get(e).exists(_ >= v))
}

/** A container is just a Component whose template splices its rendered
  * `children` (`{{#children}}{{{html}}}{{/children}}`), so container kinds
  * (row, column, grid, …) are templates rather than cases here.
  *
  * Ids are location-derived from a node's index path ([[LayoutNode.pathId]])
  * unless an author named the node ([[LayoutNode.Component.id]]) — authors
  * never invent one. A surface is a separate layout tree whose ids are
  * namespaced (`s_<id>__…`) so they cannot collide with the main page.
  */
class Renderer(
    dashboard: Dashboard,
    templates: Templates,
    transforms: Transforms,
    // Who may see this dashboard (issue #89), already folded with the site-wide
    // default by `Site.decode`. It rides here rather than beside the renderer
    // because the rule changes exactly when the dashboard does — a live reload
    // swaps one value and the gate cannot be reading last build's rule.
    //
    // Defaulted so the test helper `Renderer.create` and any construction that
    // predates access control still compile; the default is the restrictive
    // one, so forgetting to resolve demands a login rather than serving to all.
    val access: Access = Access.default
) {

  /** An addressable index over one layout tree; generated ids carry `idPrefix`
    * (empty for the main page, `s_<id>__` for a surface).
    */
  private class Index(root: LayoutNode, val idPrefix: String) {

    /** `child -> parent` for this tree, recorded by the SAME walk that mints
      * the ids so the two cannot disagree — see [[NodeAncestry]].
      */
    val parents: Map[NodeId, NodeId] = {
      def walk(node: LayoutNode, id: NodeId): List[(NodeId, NodeId)] =
        node match {
          case c: LayoutNode.Component =>
            LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              val cid = LayoutNode.childId(idPrefix, id, step, ch)
              (cid -> id) :: walk(ch, cid)
            }
          case _: LayoutNode.SetNode => Nil
        }
      walk(root, LayoutNode.rootId(idPrefix, root)).toMap
    }

    val indexed: Map[NodeId, LayoutNode] = {
      def walk(node: LayoutNode, id: NodeId): List[(NodeId, LayoutNode)] = {
        val self = id -> node
        node match {
          case c: LayoutNode.Component =>
            self :: LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              walk(ch, LayoutNode.childId(idPrefix, id, step, ch))
            }
          // A member container is a LEAF of the static index: its children are
          // members, addressed by `memberId` rather than by a path (see
          // [[Member]]).
          case _: LayoutNode.SetNode => List(self)
        }
      }
      walk(root, LayoutNode.rootId(idPrefix, root)).toMap
    }

    val byEntity: Map[String, Set[NodeId]] =
      indexed.toList
        .collect { case (id, c: LayoutNode.Component) => id -> c }
        .flatMap { case (id, c) => c.liveEntities.map(_ -> id) }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

  }

  private val mainIndex = new Index(dashboard.card, "")

  /** Does the browser's `<head>` still match the UNPATCHABLE part of this
    * dashboard — the theme's `<link>`ed stylesheets, its scripts (module and
    * inline), and its `chrome` frame? A mismatch is the one thing neither a
    * body patch nor a head patch can repair (a `<link>` can be added but not
    * un-applied, a script cannot be un-run, and the chrome is the frame the
    * body patch lands INSIDE), so it is the one thing worth a full page
    * **reload** (docs/adr/0011-the-live-connection.md).
    *
    * The rest of the head — tokens, inline CSS, `<title>` — is [[styleHash]]
    * instead: it patches.
    *
    * Deliberately NOT a hash of the whole dashboard. Cards and layout live in
    * the body, which the repaint already re-sends in full, so hashing them
    * would reload the page on every edit for no gain. And it is not needed to
    * gate the resume either: any dashboard change arrives via a renderer swap,
    * which rotates the fragment log's `logId` and rejects the cursor anyway.
    *
    * STABLE ACROSS RESTARTS by design: an add-on restart on an HA update must
    * not refresh every browser when the theme is byte-identical. That is
    * exactly why it cannot double as `logId`, whose whole job is to NOT survive
    * one.
    */
  val headHash: String = Renderer.headFingerprint(dashboard)

  /** The PATCHABLE part of the head: the theme's tokens and inline CSS (which
    * are exactly [[themeStyleTag]]) plus the authored `<title>`. A mismatch
    * costs two small element patches, not a reload — see `Server.headPatches`.
    *
    * Same restart-stability contract as [[headHash]].
    */
  val styleHash: String = Renderer.styleFingerprint(dashboard)

  /** Surfaced so the caller that builds a renderer logs them once, rather than
    * the renderer printing from inside a pure render path (same split as
    * [[uiStateAnomalies]]).
    */
  val warnings: List[String] = dashboard.warnings

  /** Cards that opted out of the `.fh-cell` wrapper
    * ([[fh.view.model.CardDef.wrapAsCell]] = false) — their template root must
    * stay a direct child of a framework-structural parent (the tab anchors).
    */
  private val noWrapCards: Set[String] =
    dashboard.cards.collect { case (name, cd) if !cd.wrapAsCell => name }.toSet

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

  private val allIndexed: Map[NodeId, (LayoutNode, String)] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap { idx =>
      idx.indexed.map { case (id, n) => id -> (n, idx.idPrefix) }
    }.toMap

  private val prefixToRoot: Map[String, String] =
    Map(mainIndex.idPrefix -> "") ++
      surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }

  /** Every statically-indexed node -> the layout tree it sits in. The one fact
    * both decision halves need from the index, so it is derived once here
    * rather than either of them being handed the index itself.
    */
  private val rootOfIndexed: Map[NodeId, String] =
    allIndexed.view.mapValues { case (_, prefix) =>
      prefixToRoot(prefix)
    }.toMap

  /** Who is in every candidate set, and in what order — the live half of the
    * node graph, beside the static [[allIndexed]]. It decides presence and
    * order; the renderer paints.
    *
    * EXPOSED rather than re-`export`ed. A delegating wrapper would let
    * `renderer.affectedSets(…)` keep reading as though the renderer decided
    * membership, which is the confusion the split exists to end; making callers
    * write `renderer.members.affectedSets(…)` puts the seam in the call site.
    */
  private[runtime] val members: MemberGraph = new MemberGraph(
    allIndexed.collect { case (id, (s: LayoutNode.SetNode, _)) => id -> s },
    rootOfIndexed
  )

  /** Containment, from the tree rather than from how ids are spelled. Built
    * from the SAME walks that mint the ids (each `Index`'s `parents`) plus the
    * two edges only the member graph knows — see [[NodeAncestry]].
    */
  private[runtime] val ancestry: NodeAncestry =
    NodeAncestry.fromParents(
      (mainIndex :: surfaceIndexes.values.toList)
        .flatMap(_.parents)
        .toMap ++ members.parentEdges
    )

  /** Which parts of the dashboard are showing, and to whom — selection and
    * visibility. The other decision half, exposed on the same terms as
    * [[members]] and for the same reason.
    */
  private[runtime] val surfaces: SurfaceGraph =
    new SurfaceGraph(dashboard.surfaces, rootOfIndexed, members)

  /** The main page's nodes binding `entityId` — materialised members included,
    * which is the whole point of materialising them: a member re-renders
    * because something it binds moved, exactly as a static component does.
    *
    * That covers a case slot naming a SECOND entity, which was authorable and
    * silently never ticked: the only selector was the group's query, and a
    * change to an entity the query does not match touches no group.
    */
  def componentsFor(entityId: String): Set[NodeId] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty) ++
      members.membersBinding(entityId, "")

  /** Empty for a candidate set — its members are per-entity children with ids
    * of their own — and for an unknown id.
    */
  def entitiesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _)) => c.liveEntities
      case _                                  => members.liveEntitiesOf(id)
    }

  /** Whether this dashboard names `entityId` at all — the bound an action POST
    * is held to (ADR 0023). Delegates to the model's static walk rather than
    * reading [[Index.byEntity]], which stops at a candidate set: a set's
    * members are reached through the member graph at RUN time, and this
    * question has to be answerable about entities nothing is currently
    * rendering.
    */
  def references(entityId: String): Boolean =
    dashboard.referencedEntities.contains(entityId)

  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[NodeId] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty)) ++
      members.membersBinding(entityId, surfaceId)

  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  /** `<link>`-ed by the page, e.g. BeerCSS. */
  def stylesheets: List[String] = dashboard.theme.stylesheets

  /** `<link>`-ed off the critical path — see [[fh.view.model.Theme]]. */
  def deferredStylesheets: List[String] = dashboard.theme.deferredStylesheets

  /** The `<meta name="theme-color">` pair — see [[Renderer.themeColorTags]]. */
  val themeColorTags: String = Renderer.themeColorTags(dashboard)

  /** Injected as `<script type="module">`, e.g. beer.min.js. */
  def scripts: List[String] = dashboard.theme.scripts

  /** Inlined as classic `<script>`, e.g. the slider's press-and-hold gate. */
  def inlineScripts: List[String] = dashboard.theme.inlineScripts

  /** `None` falls back to the slug, at the caller. */
  def title: Option[String] = dashboard.title

  /** The page's whole stylesheet as one id'd `<style>` element, in the order
    * that makes the cascade work (ADR 0020):
    *
    *   1. design tokens as `:root` custom properties (dark overrides under
    *      `@media (prefers-color-scheme: dark)`, so the page follows the
    *      browser),
    *   2. `dashboard.css` — the base every dashboard gets: the `fh-` layout
    *      contract, the `--fh-*` variables, the runtime's own classes,
    *   3. every card's own `css`, the structure its markup needs,
    *   4. the theme's `styles`.
    *
    * Later beats earlier by document order, so a theme overrides a card and a
    * card overrides the base — without either of the two lower layers having to
    * know it might be overridden. Empty when there is nothing in any of them.
    *
    * Deliberately OUTSIDE `#dashboard`, i.e. not part of [[renderBody]]. Riding
    * inside the repainted body would let a reload or navigate swap it too, but
    * it is static and it is BIG: on a small demo dashboard it is 7.7 KB of the
    * 9.6 KB a repaint sends, which would be re-transmitted on every reconnect
    * that cannot resume. So it is sent only when it actually changed, morphed
    * by its stable id ([[Renderer.ThemeStyleId]]) — on a navigate to a
    * differently-themed dashboard, and on a reconnect whose [[styleHash]] no
    * longer matches (`Server.headPatches`).
    */
  val themeStyleTag: String = {
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
      dashboard.css,
      dashboard.cardCss,
      theme.styles
    ).filter(_.nonEmpty)

    // Always the element, even when empty: a navigate into a themed dashboard
    // needs something to morph, and morphing an id that isn't there is a no-op
    // the client reports as an error.
    parts.mkString(
      s"""<style id="${Renderer.ThemeStyleId}">""",
      "",
      "</style>"
    )
  }

  /** Owns the `#dashboard` swap target and, for a theme that uses popups, the
    * popup host's placement. An empty `theme.chrome` falls back to a minimal
    * frame with no popup host — see [[Theme.chrome]] for the contract.
    */
  private val chromeTemplate: Template = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compiler.compile(chrome)
  }

  /** Without the page shell and without the theme ([[themeStyleTag]] sits
    * outside it): what a repaint or navigate `inner`-patches into `#dashboard`.
    */
  def renderBody(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): String =
    renderBodyTraced(states, uiState).html

  /** [[renderBody]] with the per-node trace — including every surface BAKED
    * into it, which is what a page load needs to seed the log for the surfaces
    * the client can already see.
    */
  private[runtime] def renderBodyTraced(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Traced =
    traced(
      dashboard.card,
      LayoutNode.rootId("", dashboard.card),
      "",
      states,
      uiState
    )

  /** The style sits BEFORE the chrome, so every patch target inside it can be
    * repainted without re-sending the CSS.
    *
    * A restored `popup` is BAKED into the host's `{{{popups}}}` hole, the way a
    * selected tab panel is baked into its owner: otherwise the dialog cannot
    * appear until the stream connects and patches it in, which a refresh sees
    * as the dashboard painting first and the dialog popping in late. A theme
    * whose chrome has no hole renders without it — the var is optional in the
    * contract, and the patch still arrives.
    */
  def renderPage(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None
  ): String = renderPageTraced(states, uiState, popup).html

  /** The page is the one render that must tell the log what it did. Every other
    * node starts with NO entry, which reads as "you are up to date" and is
    * true: the document was server-rendered from current state. An open surface
    * is the exception — with no entry, the first live tick would hand the
    * client its own surface straight back.
    */
  private[runtime] def renderPageTraced(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None
  ): Traced = {
    val body = renderBodyTraced(states, uiState)
    val dialog = popup.flatMap(renderSurfaceTraced(_, states, uiState))
    val page = themeStyleTag + chromeTemplate.execute(
      Renderer.javaContext(
        Map(
          "body" -> body.html,
          // The dialog a refresh is restoring, baked into the host exactly as
          // the connect would patch it — same render, so the two are
          // byte-identical and the later patch is a no-op morph.
          "popups" -> dialog.fold("")(_.html)
        ),
        Map.empty
      )
    )
    // The whole page is never a patch target — a repaint replaces `#dashboard`
    // wholesale — so it has no second form of its own. Its NODES do, and those
    // are in `own`.
    Traced(
      page,
      page,
      body.own ++ dialog.fold(Map.empty[NodeId, Painted])(_.own)
    )
  }

  /** Bare content, with no wrapper: every surface is chrome-less, because the
    * host it swaps into and any frame around it live in `theme.chrome` rather
    * than per-surface.
    */
  def renderSurface(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] =
    renderSurfaceTraced(surfaceId, states, uiState).map(_.html)

  /** [[renderSurface]] with the per-node trace — what a FILL uses, so the log
    * learns what the fill put in each node without re-rendering the subtree.
    */
  private[runtime] def renderSurfaceTraced(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[Traced] =
    dashboard.surfaces.get(surfaceId).map { s =>
      traced(
        s.content,
        LayoutNode.rootId(Renderer.surfacePrefix(surfaceId), s.content),
        Renderer.surfacePrefix(surfaceId),
        states,
        uiState
      )
    }

  /** `uiState` is threaded through so a node that owns a bake group — a `tabs`
    * host that also binds a live entity — re-bakes the viewer's selected member
    * on a live patch rather than the default one.
    */
  def renderNodeById(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      // THE patch path, so the patch form is the default here and the document
      // walk is what asks for the other one. A caller wanting bytes to put in a
      // client's DOM wholesale wants `Document`.
      form: SlotForm = SlotForm.Patch
  ): Option[String] =
    members
      .memberAt(id, states)
      .map(renderMember(_, states, form))
      .orElse(renderIndexed(id, states, uiState, form))

  private def renderIndexed(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      form: SlotForm
  ): Option[String] =
    allIndexed
      .get(id)
      .filter(_ => hasOwnRendering(id))
      .map { case (node, prefix) =>
        render(node, id, prefix, states, uiState, form)
      }

  /** `s_<sid>__c` — what a state group's mount holds, and so what a flip
    * removes or places. The same scheme the build-phase hoist uses, so a
    * branch's build-time id and the id a flip records are one story.
    */
  def surfaceContentId(surfaceId: String): NodeId =
    LayoutNode.nodeId(Renderer.surfacePrefix(surfaceId), Nil)

  /** The resume path's SECOND candidate set. A surface a client has open holds
    * nodes the cursor alone would not name, because nothing may have rendered
    * that surface at all while nobody was viewing it — so the log has no
    * version to compare and only re-rendering can tell whether the DOM is
    * current.
    */
  def surfaceNodeIds(surfaceId: String): Set[NodeId] =
    surfaceIndexes.get(surfaceId).fold(Set.empty)(_.indexed.keySet)

  /** In DOM order. Paired rather than concatenated because a fill owes the log
    * a digest per member: the mount's contents are re-supplied wholesale, so
    * the next live diff must compare against what this fill actually put there.
    */
  def renderMembers(
      groupId: SetId,
      states: Map[String, EntityState]
  ): List[(NodeId, String)] =
    members
      .membersOf(groupId, states)
      .toList
      .map(m => m.id -> renderMember(m, states, SlotForm.Document))

  /** What a wholesale FILL carries, for EITHER kind of container: a candidate
    * set's members, or a state group's one active branch. Both are "what is in
    * this mount", so they answer here rather than at each fill site.
    */
  def renderMount(
      container: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): List[(NodeId, String)] =
    members.setContainer(container) match {
      case Some(setId) => renderMembers(setId, states)
      case None        =>
        surfaces
          .resolveActiveByState(container, states)
          .flatMap(surfaces.bakeGroup(container).lift)
          .flatMap(sid =>
            renderSurface(sid, states, uiState).map(surfaceContentId(sid) -> _)
          )
          .toList
    }

  /** Every LOG KEY must be resolvable here, because the log holds a digest
    * rather than HTML and a resume renders its candidates instead of reading
    * them back. It is [[renderNodeById]] and nothing else: a candidate set's
    * member is a node in the graph like any other, which is what this method's
    * second case used to exist for.
    *
    * `uiState` is the viewer this render is FOR. A node whose own markup reads
    * its own selection has one rendering per member, so rendering it without a
    * viewer hands everybody the default member's — on a resume, that is a
    * client's own tab flipped out from under it.
    *
    * `None` means the key names nothing that exists now (its group is gone, the
    * entity is no longer a member), which is exactly when there is nothing to
    * send. That it cannot crash is the point: an unresolvable key must be
    * dropped rather than take the resume with it.
    */
  def renderLogged(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty
  ): Option[String] = renderNodeById(id, states, uiState)

  /** The backend-injected structural template vars for one node — the ids an
    * author never composes.
    *
    * ONE derivation, deliberately, because there are two places a node id comes
    * from ([[LayoutNode.pathId]] for the static tree,
    * [[MemberGraph.memberIdOf]] for a group member), and injecting their vars
    * separately means a var added to one silently misses the other. The rule
    * this makes true:
    *
    * > Structural vars are a pure function of the node id in scope.
    *
    * So a container card used as a set clause gets its `hostId` off its member
    * id for free, with no per-call-site knowledge. `bakeIndex` is NOT here: it
    * is a function of the client's selection, not of the id, and it belongs to
    * the document path alone ([[resolveBakeTraced]]).
    */
  private def structuralVars(id: NodeId): Map[String, String] =
    Map(
      "id" -> id,
      "hostId" -> hostId(id),
      // The dashboard's slug, for the action URL a card builds in its own
      // TEMPLATE (the slider's commit). A tap builds its URL in a transform
      // instead and reads the same value as `$dashboardSlug` — one fact, and
      // each spelling names the mechanism that actually fills it.
      "dashboardSlug" -> dashboard.slug
    )

  /** Whether this node HAS a rendering of its own — the thing that decides
    * whether it may be a log key or a patch target at all.
    *
    * '''One question, asked of the CARD.''' A leaf card's template is its whole
    * fragment; a structural card's element contains the regions it holds, so a
    * patch aimed at it would carry their bytes back with it. Two shapes fail:
    *
    *   - any STRUCTURAL card ([[fh.view.model.CardDef.isStructure]]) — a
    *     container, a slider, a tabs host;
    *   - a candidate set root, which composes its members (each addressable in
    *     its own right) rather than having markup of its own.
    *
    * Neither loses anything by being excluded: their children are addressable
    * in their own right.
    *
    * This used to be three questions asked of a TEMPLATE's spelling — whether a
    * card declared a `self`, whether that `self` spliced `{{#children}}`, and
    * whether any child carried a mount — because a card's own bytes could
    * contain another node's. Regions removed the shape rather than the check:
    * every hole in a template is filled by a node, so "my bytes carry someone
    * else's" is unrepresentable and the card alone decides.
    *
    * The same rule `Dashboard.validate` enforces when it rejects a live BYTES
    * slot on structure: no patch target.
    *
    * They keep their [[elementId]] — a structural patch still names them, a
    * `remove` deletes that element and an `insert` anchors before it. What they
    * lose is being rendered BY ID.
    */
  private def hasOwnRendering(id: NodeId): Boolean =
    allIndexed.get(id).exists {
      // A LEAF renders itself and nothing else; STRUCTURE renders what it
      // holds, so patching it would re-send that. One question, asked of the
      // card — where it used to be three, asked of a template's spelling.
      case (c: LayoutNode.Component, _) =>
        !dashboard.cards.get(c.card).exists(_.isStructure)
      // A member container composes its members and renders nothing of its
      // own; the members are the log keys.
      case (_: LayoutNode.SetNode, _) => false
    }

  /** The node's OWN root element — the `.fh-cell` wrapper `render` emits, and
    * the ONE crossing from node id to DOM id.
    *
    * "What I morph" and "what I am" used to be different elements: a card with
    * a `self` was patched at `<id>-self` so its patch could not reach the
    * sibling holding its children. A node holds its regions in OTHER NODES now,
    * so there is nothing to exclude and one element does both jobs.
    */
  def elementId(id: NodeId): DomId = DomId.derived(id)

  /** The element a node's children live IN — an `Inner`/`append` target, and
    * the `{{hostId}}` a container's `mount` part writes.
    *
    * '''This is not a new id — for a bake owner it IS
    * [[fh.view.model.Surface.hostId]]''', so `Tabs` resolves to `c_2_panel`,
    * byte-identical to the `id="{{id}}_panel"` a template would otherwise
    * hardcode. That removes a duplication rather than adding one: the
    * alternative has Pkl and Scala deriving the same string independently, with
    * nothing checking they agree.
    *
    * A mount needs an id only where something FILLS it, which is exactly where
    * `bakeAs` already names it (a tab panel, an `If` branch). `Grid`/`Row`/
    * `Column` mounts are never fill targets — their children arrive nested — so
    * they fall back to the node's own id and simply never use it.
    */
  def hostId(id: NodeId): DomId =
    surfaces
      .bakeGroup(id)
      .headOption
      .flatMap(dashboard.surfaces.get)
      .map(_.hostId)
      .getOrElse(elementId(id))

  /** `(baked, structural, trace)` for a component that owns a bake group: the
    * SELECTED member as its `{{{bakeAs}}}` var, so the host renders the active
    * panel on first paint, plus `bakeIndex` so a tabs template can seed its
    * signal. Selection dispatches on activation mode — [[resolveActive]] for
    * user groups, [[resolveActiveByState]] for state groups.
    *
    * Baking happens exactly as a later open/switch/flip would wrap it, so first
    * paint and switch-back are byte-identical and the first live patch is a
    * no-op morph. No bake group leaves all three empty; absent Mustache vars
    * render as empty anyway.
    */
  private def resolveBakeTraced(
      id: NodeId,
      uiState: Map[String, String],
      states: Map[String, EntityState]
  ): (Map[String, String], Map[String, String], Map[NodeId, Painted]) = {
    val group = surfaces.bakeGroup(id)
    def bakeMember(
        idx: Int
    ): (Map[String, String], Map[String, String], Map[NodeId, Painted]) = {
      val sid = group(idx)
      val s = dashboard.surfaces(sid)
      // A baked member's nodes are part of what this render puts on screen, so
      // its trace joins this one's — that is how a page load fingerprints the
      // surfaces it baked without walking them again.
      val member = renderSurfaceTraced(sid, states, uiState)
      (
        Map(s.bakeAs.getOrElse("") -> member.fold("")(_.html)),
        Map("bakeIndex" -> idx.toString),
        member.fold(Map.empty[NodeId, Painted])(_.own)
      )
    }
    if (group.isEmpty) (Map.empty, Map.empty, Map.empty)
    else
      activeBakeIndex(id, uiState, states) match {
        case Some(idx) => bakeMember(idx)
        case None      =>
          // A state group with no matching branch: the host's {{{bakeAs}}} var
          // is explicitly the empty string (all members share one bakeAs — they
          // bake into one hole), so the wrapper renders empty instead of
          // leaving the var absent-but-meaningful.
          val as = group.headOption
            .flatMap(sid => dashboard.surfaces.get(sid).flatMap(_.bakeAs))
            .getOrElse("")
          (Map(as -> ""), Map.empty, Map.empty)
      }
  }

  /** WHICH member of `id`'s bake group is selected, dispatching on activation
    * mode — the one thing a bake owner's own rendering reads beyond its slots.
    * `None` for a node that owns no group, and for a state group whose branches
    * all fail.
    *
    * Split out of [[resolveBakeTraced]] because [[renderInputs]] needs the
    * selection WITHOUT rendering the member it selects. Sharing it is what
    * makes the cache key honest: a key derived independently could drift from
    * the render it claims to describe.
    */
  private def activeBakeIndex(
      id: NodeId,
      uiState: Map[String, String],
      states: Map[String, EntityState]
  ): Option[Int] =
    if (surfaces.bakeGroup(id).isEmpty) None
    else if (surfaces.isStateGroup(id))
      surfaces.resolveActiveByState(id, states)
    else Some(surfaces.resolveActive(id, uiState)._1)

  /** Everything a node's OWN rendering reads, as a comparable value — the key a
    * render cache needs
    * (docs/adr/0012-each-session-renders-what-it-is-owed.md).
    *
    * Two parts, and the split is the point:
    *
    *   - `entities` — the CONTENT VERSION of each entity the node's slots bind
    *     ([[entitiesForNode]]). A version rather than the value because
    *     [[EntityState]]'s synthesized `hashCode` recurses into the attribute
    *     map on every lookup, and because it is MORE discriminating than the
    *     render is: `lastUpdated` moves on ticks that change no rendered byte.
    *     An entity the snapshot does not hold has NO entry, which is a distinct
    *     key from any version it could have — [[resolveSlot]] renders such a
    *     slot from a synthetic empty state.
    *   - `vars` — the structural vars the bake group contributes (`bakeIndex`),
    *     taken as the RESOLVED value rather than the inputs it derives from.
    *     That is what keeps the key small where it could not otherwise be: a
    *     state group's branch is a QUANTIFIED predicate over the whole entity
    *     map ([[holds]]), so keying on its sources would key on every entity.
    *
    * Deliberately NOT included: the node's children. Including them would make
    * any descendant's tick invalidate every ancestor to the root, which is the
    * whole reason a per-node cache earns anything.
    *
    * `None` — NOT CACHEABLE — is what keeps that honest, and it is the reason
    * this returns an `Option` rather than a key that a caller has to know not
    * to trust. It is now exactly [[hasOwnRendering]]'s `false`: structure, or a
    * candidate set root. Both compose rather than render, and neither is a
    * patch target, so nothing is lost by excluding them.
    *
    * There used to be a SECOND excluded kind — a node whose own bytes carried
    * its children, so they moved when a child's entity moved while this key
    * stood still — and a plan for admitting it by rendering own markup with
    * holes and substituting separately-keyed child bytes in a second pass.
    * Regions did that instead, and did it in the model: a node's regions are
    * other nodes, each keyed and cached on its own, so no node's bytes carry a
    * child's and there is nothing left to admit.
    */
  def renderInputs(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Option[RenderInputs] =
    members
      .memberAt(id, states)
      .map(m =>
        RenderInputs(
          // The SUBJECT is in the key whether or not a slot reads it: the
          // materialised node is state-derived, so the entity that chose its
          // case has to be able to invalidate the bytes that case produced.
          versions(m.node.subjectEntity.toList ++ m.node.liveEntities, states)
        )
      )
      .orElse(
        Option
          // No `&& !ownBytesCarryChildren(id)` any more. That was a
          // CONSERVATIVE proxy for "my bytes carry my children", and it cost
          // every grouped slider its cache entry on the hot path even though
          // the head's bytes never held a member. A node with its own rendering
          // IS a leaf now, so it has no children to carry — the exclusion has
          // nothing left to exclude.
          .when(hasOwnRendering(id))(
            RenderInputs(versions(entitiesForNode(id), states))
          )
      )

  private def versions(
      entities: List[String],
      states: Map[String, EntityState]
  ): Map[String, Long] =
    entities.distinct
      .flatMap(e => states.get(e).map(e -> _.contentVersion))
      .toMap

  private def render(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      form: SlotForm
  ): String = {
    val t = traced(node, id, idPrefix, states, uiState)
    if (form.isPatch) t.patch else t.html
  }

  /** The composed rendering, and every node's OWN html inside it.
    *
    * The walk already computes both — a card's `self` is built and then spliced
    * into `template` — so the trace is a matter of not discarding it. Anything
    * needing per-node bytes after a wholesale render (a fill recording what it
    * put in a mount, the page seeding the log for its open surfaces) would
    * otherwise walk the whole subtree a SECOND time, node by node.
    *
    * `own` carries an entry only for nodes that have a rendering of their own
    * ([[hasOwnRendering]]) — the same set that may be a log key — and its bytes
    * are what [[renderNodeById]] would return for that id, which is what makes
    * the digests comparable at all.
    */
  private[runtime] case class Traced(
      html: String,
      // The same rendering with signal-slot values withheld — what a later
      // per-node patch will produce, and so what `own` is fingerprinted from.
      // The SAME reference as `html` for a subtree with no signal slot in it,
      // which is the normal case and costs nothing.
      patch: String,
      own: Map[NodeId, Painted]
  )

  private def traced(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Traced =
    node match {
      case c: LayoutNode.Component =>
        // Per REGION, because each hole is spliced with its own children and a
        // child's step names the region it sits in.
        val kidsByRegion: Map[String, List[Traced]] =
          c.regions.map { case (region, nodes) =>
            region -> nodes.zipWithIndex.map { case (child, i) =>
              val step = LayoutNode.Step(region, i)
              traced(
                child,
                LayoutNode.childId(idPrefix, id, step, child),
                idPrefix,
                states,
                uiState
              )
            }
          }
        val kids = kidsByRegion.toList.sortBy(_._1).flatMap(_._2)
        val childrenHtml = kidsByRegion.view.mapValues(_.map(_.html)).toMap
        val (baked, bakeIndex, bakedTrace) =
          resolveBakeTraced(id, uiState, states)
        // ONE template per card now. It used to be composed from two parts
        // spliced together, with the `self` shown a NARROWER var map than the
        // whole — no baked member — so a node's own rendering could not carry
        // what it hosted. A region's contents are other nodes, so that
        // separation is structural and one var map serves.
        val vars = structuralVars(id) ++ bakeIndex ++ baked
        // ONE walk, both forms — see [[Traced]]. Only the form differs between
        // the two calls, so a node with no signal slot anywhere under it does
        // the second not at all.
        def compose(
            form: SlotForm,
            kidsHtml: Map[String, List[String]]
        ): String =
          renderTemplate(c.card, vars, c.slots, kidsHtml, states, form)
        val html = compose(SlotForm.Document, childrenHtml)
        // The patch form is needed when THIS node's slots carry a signal, or
        // when a child's bytes (which ride inside these) differ between the
        // forms. Neither: the same String, by reference — no second execute and
        // no second wrapper.
        val twoForms =
          declaresSignals(c) || kids.exists(k => k.patch ne k.html)
        val patchChildren =
          if (twoForms) kidsByRegion.view.mapValues(_.map(_.patch)).toMap
          else childrenHtml
        val patchHtml =
          if (twoForms) compose(SlotForm.Patch, patchChildren) else html
        // EVERY node is a cell — containers included. The backend owns the id'd
        // `.fh-cell` wrapper, so templates never carry `id="{{id}}"` themselves
        // and authored `cell` classes (fh-cols-*, …) ride on it.
        //
        // The one exception is a card that opted out via
        // `CardDef.wrapAsCell = false`, which means exactly one thing: my root
        // must not be wrapped in a layout box (the tab anchors, which must stay
        // direct children of BeerCSS's `.tabs`). It does NOT imply "never a
        // morph target" — that is decided by card shape.
        //
        // A bake owner gets a wrapper like anything else, because the
        // self/mount split separates the two roles: the cell is the layout
        // item, the `self` element is the patch target. Conflating them denies
        // `Tabs`/`If` a cell and silently drops `.columns(n)` on them.
        //
        // The wrapper is also where a signal slot's `data-signals` SEED rides,
        // in the document form only — see [[seedAttr]]. One attribute for the
        // whole node, which is what makes two signal slots on one card work.
        def wrap(inner: String, form: SlotForm): String =
          if (noWrapCards(c.card)) inner
          else
            s"""<div class="fh-cell${Renderer.cellClasses(
                c.cell
              )}" id="$id"${seedAttr(id, c, states, form)}>$inner</div>"""
        val wrapped = wrap(html, SlotForm.Document)
        // What this node contributes to the trace: its whole (wrapped) patch
        // rendering when it is a LEAF, and nothing when it is structure.
        // Mirrors `renderNodeById` exactly — wrapper included, and in the PATCH
        // form, which is what that method produces.
        val patch =
          if (!twoForms) wrapped else wrap(patchHtml, SlotForm.Patch)
        val own = Option.when(hasOwnRendering(id))(
          Painted(patch, signalsOfSlots(id, c, states))
        )
        Traced(
          wrapped,
          patch,
          kids.foldLeft(bakedTrace)(_ ++ _.own) ++ own.map(id -> _)
        )
      // A container root composes its members and so has no own rendering; the
      // members do, and they are what a fill must fingerprint.
      case s: LayoutNode.SetNode =>
        // The match IS the proof: this node is a `SetNode`, which is exactly
        // the evidence `MemberGraph` mints its root [[SetId]]s from.
        val setId = SetId.of(id, s)
        val document = renderSet(setId, s.cell, states, SlotForm.Document)
        Traced(
          document,
          // A set root has no rendering of its own and is never a patch target,
          // so its two forms are only ever embedded in an ancestor's. Rendering
          // the patch one is worth it exactly when a member has a signal slot.
          if (
            members
              .membersOf(setId, states)
              .exists(m => declaresSignals(m.node))
          )
            renderSet(setId, s.cell, states, SlotForm.Patch)
          else document,
          members
            .membersOf(setId, states)
            .map { m =>
              m.id -> Painted(
                renderMember(m, states, SlotForm.Patch),
                memberSignals(m.id, m.node, states)
              )
            }
            .toMap
        )
    }

  private def renderSet(
      id: SetId,
      cell: Option[Cell],
      states: Map[String, EntityState],
      form: SlotForm
  ): String = {
    val children =
      members.membersOf(id, states).map(renderMember(_, states, form))
    // The group root is itself a cell (a first-class layout item in its
    // container) plus `.fh-group`, the themed flow container its per-entity
    // member cells live in. Authored `cell` classes (e.g. `fh-cols-full` to
    // span a parent grid) ride on it.
    s"""<div class="fh-cell fh-group${Renderer.cellClasses(
        cell
      )}" id="$id">${children.mkString}</div>"""
  }

  /** Render ONE set member by its entity — the by-key accessor into the graph.
    * `None` when the set id is unknown/not a set or the entity is not a current
    * member.
    */
  def renderMemberById(
      setId: SetId,
      entityId: String,
      states: Map[String, EntityState]
  ): Option[String] =
    members
      .membersOf(setId, states)
      .find(_.key == MemberKey.Entity(entityId))
      .map(renderMember(_, states, SlotForm.Document))

  /** A materialised member's own bytes.
    *
    * Every member gets the SAME id'd `.fh-cell` wrapper as a static component,
    * so it is an addressable patch target (in-place morph / insert / remove)
    * rather than only ever re-rendered as part of the whole group — which is
    * why the wrap here is UNCONDITIONAL (a `wrapAsCell = false` card has no
    * member morph target and is not usable as a set clause).
    */
  private def renderMember(
      m: Member,
      states: Map[String, EntityState],
      form: SlotForm
  ): String = {
    val html = renderTemplate(
      m.node.card,
      structuralVars(m.id),
      m.node.slots,
      // A member may render a SUBTREE — a card with children, not only a leaf.
      // Ordinary children come out INSIDE the member's bytes with no ids of
      // their own, so the member is the single patch target for them and a
      // child's entity changing re-renders it (which is why
      // [[Member.entitiesOf]] walks them). A nested SET is the exception: it
      // is addressable, and [[Member.entitiesOf]] stops there.
      Renderer.perRegion(m.node.regions)((child, step) =>
        memberChild(m, child, List(step), m.clause, states, form)
      ),
      states,
      form
    )
    // A member's children have no ids, so the seed here covers THEM too — see
    // [[memberSignals]]. That is why a member's wrapper carries the whole patch
    // unit's signals rather than only its own card's.
    s"""<div class="fh-cell${Renderer.cellClasses(m.node.cell)}" id="${m.id}"${
        if (form.isPatch) ""
        else Datastar.signalsAttr(memberSignals(m.id, m.node, states))
      }>$html</div>"""
  }

  /** One node inside a member. Ordinary children render whole and unaddressed —
    * the member is their patch target. A nested SET is the exception: it is an
    * addressable container of its own, so it renders as its group element and
    * its members are patched individually rather than through the tile.
    *
    * It needs no template support because the tile's own content is static —
    * `Dashboard.validate` already refuses a live BYTES slot on structure, and a
    * room's NAME is a registry fact, hence a literal.
    */
  private def memberChild(
      m: Member,
      node: LayoutNode,
      path: List[LayoutNode.Step],
      clauseIdx: Int,
      states: Map[String, EntityState],
      form: SlotForm
  ): String = node match {
    case c: LayoutNode.Component =>
      val html = renderTemplate(
        c.card,
        structuralVars(m.id),
        c.slots,
        Renderer.perRegion(c.regions)((child, step) =>
          memberChild(m, child, path :+ step, clauseIdx, states, form)
        ),
        states,
        form
      )
      s"""<div class="fh-cell${Renderer.cellClasses(c.cell)}">$html</div>"""
    case inner: LayoutNode.SetNode =>
      // A nested set is its own patch unit, so its members carry their own
      // values and their own seeds whatever form this tile is in — blanking
      // them here would withhold values no patch of THIS node restores.
      renderSet(
        members.innerSetId(m.id, clauseIdx, path, inner),
        inner.cell,
        states,
        SlotForm.Document
      )
  }

  private def renderTemplate(
      cardName: String,
      injected: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: Map[String, List[String]],
      states: Map[String, EntityState],
      form: SlotForm
  ): String =
    templates.components.get(cardName) match {
      case None =>
        // Unreachable by construction: Dashboard.validate resolves every card
        // reference before a Renderer is built.
        throw new IllegalStateException(
          s"unknown card '$cardName' — validate should have rejected this dashboard"
        )
      case Some(tpl) =>
        renderTemplateOf(tpl, injected, slots, childrenHtml, states, form)
    }

  /** Render an already-resolved template with the card's slot resolution, so a
    * card's markup and its parts can never disagree about what a slot means.
    *
    * `form` picks which rendering a SIGNAL slot gets, and changes nothing else
    * (see [[SlotForm]]). A card with no signal slot renders identically either
    * way, which is what lets the callers skip the second execute entirely.
    */
  private def renderTemplateOf(
      tpl: Template,
      injected: Map[String, String],
      slots: Map[String, SlotSource],
      childrenHtml: Map[String, List[String]],
      states: Map[String, EntityState],
      form: SlotForm
  ): String = {
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
          // the matched entity in a set clause). The `entity_id` slot
          // itself never inherits — it is the subject.
          val srcEntity =
            if (slot == "entity_id") source.entityId
            else source.entityId.orElse(subject)
          // `once` is identity-derived — its transform reads only
          // `$domain`/`$entity_id` (a service action, the slider's domain
          // config), both immutable for the life of the entity. So its value
          // never changes: resolve it ONCE per (entity, transform) and reuse
          // forever. This is what keeps the set render path slick — a candidate
          // set re-renders every matched card on every event, but those cards'
          // action/config slots become a cache lookup, not a JSONata eval.
          // `$entity_id` is in the key (the action URL embeds it), so two
          // entities never collide.
          //
          // `live` and `onRender` both re-resolve; they differ in whether the
          // entity is SUBSCRIBED, which is `liveEntities`' business, not this
          // one. That is why the memo asks only about `once`.
          if (source.reads == Reads.Once)
            identityCache.computeIfAbsent(
              (srcEntity.getOrElse(""), source.transform),
              _ => resolveSlot(srcEntity, source, states)
            )
          else resolveSlot(srcEntity, source, states)
      }
      slot -> value
    }
    // A signal slot contributes one extra var — the binding attribute the card
    // places — and, in the patch form, withholds its value. The binding is
    // present in BOTH forms: it is what the seeded signal feeds, and a morph
    // that dropped it would leave the element inert.
    val signalled = slots.toList.flatMap { case (slot, src) =>
      Renderer.signalBind(src).map(slot -> _)
    }
    val id = NodeId.derived(injected.getOrElse("id", ""))
    val bindings = signalled.flatMap { case (slot, kind) =>
      val signal = Renderer.signalName(id, slot)
      List(
        s"${slot}__bind" -> Datastar.binding(signal, kind),
        // The bare NAME, for the one thing a canned attribute cannot cover: a
        // card composing the signal into an expression of its own (the
        // slider's action URL reads its bound position). Not a binding, so it
        // does not compromise the plain form — but a card that uses it is
        // relying on a signal existing, which a plain-form client has not got.
        s"${slot}__signal" -> signal
      )
    }
    val shown =
      if (!form.isPatch) resolved
      else
        signalled.foldLeft(resolved)((acc, slot) => acc.updated(slot._1, ""))
    tpl.execute(
      Renderer.javaContext(injected ++ shown ++ bindings, childrenHtml)
    )
  }

  /** The `data-signals` seed for one patch unit: EVERY signal slot it carries,
    * as one attribute (ADR 0017).
    *
    * Node-level rather than per-slot, and that is not a detail — a per-slot
    * seed puts two `data-signals` attributes on one element the moment a card
    * has two signal slots, and the browser silently keeps one. It also spares
    * card authors the double escape, which has exactly one correct answer
    * ([[Datastar.signalsAttr]]).
    *
    * It rides on the `.fh-cell` wrapper, which is renderer-owned and appears in
    * precisely the renders that should carry it: a `self` card's patch renders
    * `<id>-self` alone and is correctly seedless, while its document render
    * includes the wrapper. `""` — no attribute — for the patch form and for
    * every node that opted into nothing.
    */
  private def seedAttr(
      id: NodeId,
      node: LayoutNode,
      states: Map[String, EntityState],
      form: SlotForm
  ): String =
    if (form.isPatch) ""
    else Datastar.signalsAttr(signalsOfSlots(id, node, states))

  /** The signal values one PATCH UNIT carries, named under its id (ADR 0017).
    *
    * '''Whether this descends into children is not a detail — it is the
    * difference between the two halves of the graph.''' In the static tree a
    * child is a node of its own: its own id, its own `.fh-cell` wrapper, its
    * own seed, patched on its own. A MEMBER's children are not — they render
    * with `structuralVars(m.id)` and no ids at all, because the member is their
    * patch target — so their slots belong to the member's namespace.
    *
    * Descending in the static case would name a child's signal under its parent
    * and seed it on the parent's wrapper, leaving the child's own binding
    * pointed at a signal nothing ever writes. Silent, and permanent.
    *
    * Empty for a node that opted into nothing, which is the normal case and the
    * one every caller short-circuits on.
    */
  def signalsFor(
      id: NodeId,
      states: Map[String, EntityState]
  ): Map[SignalId, String] =
    members
      .memberAt(id, states)
      .map(m => memberSignals(m.id, m.node, states))
      .orElse(
        // NOT gated on `hasOwnRendering`. Structure has signals like any other
        // node — its seed already rides its own `.fh-cell` wrapper in the
        // document form — and gating here was the half that made a signal slot
        // on structure seed once and then stand still forever. The two halves
        // have to agree, so `Dashboard.validate` no longer rejects them either.
        allIndexed
          .get(id)
          .map(_._1)
          .map(signalsOfSlots(id, _, states))
      )
      .getOrElse(Map.empty)

  /** One node's OWN slots, children excluded — the static-tree answer. */
  private def signalsOfSlots(
      id: NodeId,
      node: LayoutNode,
      states: Map[String, EntityState]
  ): Map[SignalId, String] = node match {
    case c: LayoutNode.Component =>
      val subject = c.slots
        .get("entity_id")
        .map(s => s.literal.getOrElse(resolveSlot(s.entityId, s, states)))
      c.slots.collect {
        case (slot, src) if Renderer.isSignalSlot(src) =>
          Renderer.signalName(id, slot) ->
            resolveSlot(src.entityId.orElse(subject), src, states)
      }
    case _: LayoutNode.SetNode => Map.empty
  }

  /** A member's, children INCLUDED — they share its id and its patch. Stops at
    * a nested set for the reason [[Member.entitiesOf]] does: that set is
    * addressable in its own right, so its members own their own signals.
    */
  private def memberSignals(
      id: NodeId,
      node: LayoutNode,
      states: Map[String, EntityState]
  ): Map[SignalId, String] = node match {
    case c: LayoutNode.Component =>
      c.allChildren.foldLeft(signalsOfSlots(id, c, states))(
        _ ++ memberSignals(id, _, states)
      )
    case _: LayoutNode.SetNode => Map.empty
  }

  /** Whether a node's own slots carry a signal — STRUCTURAL, resolving nothing.
    * What a walk asks before paying for a second template execute.
    */
  private def declaresSignals(node: LayoutNode): Boolean = node match {
    case c: LayoutNode.Component => c.slots.values.exists(Renderer.isSignalSlot)
    case _: LayoutNode.SetNode   => false
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
      val out = transforms.run(source.transform, st, dashboard.slug)
      if (out.nonEmpty) out else source.default.getOrElse("")
    }
  }
}

object Renderer {

  /** Build a renderer from a (validated) dashboard, compiling its template and
    * transform libraries up front. The single construction point so call sites
    * never wire `Templates`/`Transforms` by hand.
    */
  def create(dashboard: Dashboard, access: Access = Access.default): Renderer =
    new Renderer(
      dashboard,
      Templates.from(dashboard),
      Transforms.from(dashboard),
      access
    )

  /** Build a renderer from a PROVEN dashboard ([[Dashboard.Validated]]) — the
    * production construction point (decode / reload / push all go through
    * [[Dashboard.validated]] first). Uses the proof's pre-compiled transforms,
    * so nothing is recompiled and the transform-setup invariant throw of
    * [[Transforms.from]] is unreachable by type.
    */
  def fromValidated(v: Dashboard.Validated): Renderer =
    new Renderer(
      v.dashboard,
      Templates.from(v.dashboard),
      Transforms.fromValidated(v),
      v.access
    )

  /** 12 hex of SHA-256 over the part of `<head>` only a reload can change — the
    * same idiom as `fh-home@1.0.0-g<hash>`. See [[Renderer.headHash]].
    *
    * The `<base href>` is excluded on purpose: it is per-REQUEST (the ingress
    * prefix), not per-dashboard, so folding it in would make one dashboard hash
    * differently depending on how it was reached.
    *
    * Over the decoded model rather than the evaluated JSON text, so it is blind
    * to key order and formatting. `toString` is a deterministic rendering here:
    * the nested `Map`s are keyed by `String`, whose `hashCode` is specified, so
    * an immutable map's iteration order is a pure function of its contents and
    * its (decode-order) insertion sequence — no identity hashes, nothing
    * JVM-run-specific.
    */
  private[runtime] def headFingerprint(dashboard: Dashboard): String =
    fingerprint(
      (
        dashboard.theme.stylesheets,
        dashboard.theme.deferredStylesheets,
        dashboard.theme.scripts,
        dashboard.theme.inlineScripts,
        dashboard.theme.chrome,
        // The theme-colour metas are the one piece of the head derived from
        // TOKENS that a style patch cannot repair, so they belong in the hash
        // that reloads rather than the one that patches. Only these two values,
        // not the whole token map: every other token still patches.
        themeColorTags(dashboard)
      ).toString
    )

  /** The colour a phone paints its own chrome with — the browser's URL bar, and
    * an installed PWA's status bar — as one `<meta name="theme-color">` per
    * scheme.
    *
    * It is the dashboard's BACKGROUND, not its accent: the bar sits directly
    * above the page, and any other colour reads as a stripe of unrelated UI
    * rather than as the top of the dashboard. The manifest's own `theme_color`
    * (a single value, and all a cold launch has) says the same thing; these
    * metas are what let it follow the device's light/dark scheme, since they
    * override the manifest once the document is up.
    *
    * Emitted from [[fh.view.model.Theme.tokens]]/`tokensDark`, so a theme that
    * retunes its background moves the phone's chrome with it.
    */
  private[runtime] def themeColorTags(dashboard: Dashboard): String =
    List(
      "light" -> dashboard.theme.tokens.get(ChromeToken),
      "dark" -> dashboard.theme.tokensDark.get(ChromeToken)
    ).collect { case (scheme, Some(color)) =>
      s"""<meta name="theme-color" media="(prefers-color-scheme: $scheme)" content="$color">"""
    }.mkString("\n  ")

  /** The HA-named token [[themeColorTags]] reads the chrome colour from. */
  private val ChromeToken = "primary-background-color"

  /** 12 hex over the patchable part of `<head>`. See [[Renderer.styleHash]].
    */
  private[runtime] def styleFingerprint(dashboard: Dashboard): String =
    fingerprint(
      (
        dashboard.theme.tokens,
        dashboard.theme.tokensDark,
        dashboard.theme.styles,
        // The other two layers of the same `<style>` element: a card's CSS
        // changing is exactly as patchable as the theme's, and leaving them out
        // would let a reconnect keep a stale stylesheet that still hashes equal.
        dashboard.css,
        dashboard.cardCss,
        dashboard.title
      ).toString
    )

  private def fingerprint(s: String): String =
    LibPackage.sha256(s.getBytes("UTF-8")).take(12)

  /** Id of the theme's `<style>` element — stable across dashboards, so a
    * navigate can morph one theme into another.
    */
  val ThemeStyleId: String = "fh-theme"

  /** The signal a signal slot's value lives in: `_<nodeId>__<slot>` (ADR 0017).
    *
    * `_`-prefixed deliberately. Datastar's default request filter excludes any
    * signal whose name starts with an underscore, so these never join the body
    * of an action POST or an SSE reconnect — a dashboard's worth of live values
    * on every request is exactly the cost this feature exists to remove.
    *
    * ONE derivation, read from both ends: the renderer builds the binding the
    * card places (`<slot>__bind`), and the pull path names the same signal in
    * its frame. Written out twice, a drift would be silent in the worst way —
    * the card binds a signal nothing ever patches, so the value is simply
    * frozen at whatever the last wholesale render seeded.
    *
    * The id is the PATCH UNIT's, not necessarily the slot's own node: a
    * member's children have no id of their own, and the member is their patch
    * target. Two children of one member therefore share a namespace, so a slot
    * NAME means one value within a patch unit.
    */
  def signalName(id: NodeId, slot: String): SignalId =
    SignalId.derived(s"_${id}__$slot")

  /** A slot whose value travels as a signal. A constant `literal` never can —
    * [[fh.view.model.Dashboard.validate]] rejects that combination — and a
    * non-reactive (identity-derived) slot has no reason to: its value is fixed
    * for the life of the entity, so the inline seed is the whole story and a
    * frame would never carry anything new.
    *
    * This is also the seam a morph-only client profile would flip: answering
    * `None` everywhere yields the PLAIN form — no binding, no seed, the bytes
    * this renderer emitted before signal slots existed. See ADR 0017.
    */
  def signalBind(src: SlotSource): Option[SignalBind] =
    src.signal.filter(_ => src.literal.isEmpty && src.reads == Reads.Live)

  def isSignalSlot(src: SlotSource): Boolean = signalBind(src).isDefined

  // The id scheme lives in the model ([[LayoutNode]]) so the build-phase hoist
  // and the renderer share one story; these delegate.
  def surfacePrefix(surfaceId: String): String =
    LayoutNode.surfacePrefix(surfaceId)
  def sanitize(s: String): String = LayoutNode.sanitize(s)

  /** A node's authored layout-cell classes as the wrapper `class` suffix
    * (leading space included), `""` when absent/empty. Validated by
    * `Dashboard.validate` to be plain class tokens.
    */
  private def cellClasses(cell: Option[Cell]): String =
    cell.map(_.classes).filter(_.nonEmpty).fold("")(_.mkString(" ", " ", ""))

  /** Build the jmustache context at the Java boundary: the string slot/param
    * context plus, when present, a `children` list of `{html}` maps for
    * container templates (`{{#children}}{{{html}}}{{/children}}`). Kept here so
    * the rest of the renderer works in plain `Map[String, String]`.
    */
  /** Render each region's children, keeping them under their region's name and
    * handing each child the [[LayoutNode.Step]] that reaches it. Every walk
    * that produces `childrenHtml` goes through here, so "which region am I in"
    * is answered in exactly one place.
    */
  private[runtime] def perRegion[A](children: Map[String, List[LayoutNode]])(
      f: (LayoutNode, LayoutNode.Step) => A
  ): Map[String, List[A]] =
    children.map { case (region, nodes) =>
      region -> nodes.zipWithIndex.map { case (n, i) =>
        f(n, LayoutNode.Step(region, i))
      }
    }

  private def javaContext(
      context: Map[String, String],
      childrenHtml: Map[String, List[String]]
  ): java.util.Map[String, AnyRef] = {
    import scala.jdk.CollectionConverters.*
    val m =
      new java.util.HashMap[String, AnyRef](context.size + childrenHtml.size)
    m.putAll(context.asJava)
    // One list per REGION, under the region's own name, so a template's
    // `{{#headActions}}` and `{{#children}}` splice different children. An
    // empty region contributes nothing, which is what makes the section vanish
    // rather than render an empty list.
    childrenHtml.foreach { case (region, htmls) =>
      if (htmls.nonEmpty) {
        val list = new java.util.ArrayList[AnyRef](htmls.size)
        htmls.foreach(h =>
          list.add(java.util.Collections.singletonMap("html", h))
        )
        val _ = m.put(region, list)
      }
    }
    m
  }

}
