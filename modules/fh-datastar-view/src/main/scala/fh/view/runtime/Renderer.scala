package fh.view.runtime

import com.github.mustachejava.Mustache
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
  Transform,
  SignalBind,
  SignalId,
  SlotSource,
  Surface
}
import scala.jdk.CollectionConverters.*

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
    // The DIGEST of this node's patch-form bytes, not the bytes. Every reader
    // wanted the fingerprint and threw the html away immediately, and holding
    // it meant a page kept 99 kB of a 128 kB document a second time — the
    // leaves, sliced out of the buffer that already held them. The one caller
    // that needs real bytes ([[Renderer.render]], one node) asks for the walk
    // ROOT's, which [[Traced.rootOwn]] carries.
    digest: Digest,
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
  *     carries its own signal and a first paint, a host fill or a member insert
  *     needs no frame to be correct.
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

  /** [[entitiesForNode]] minus the entities reached ONLY through signal slots —
    * what [[renderInputs]] keys on, because those are the reads whose movement
    * can change this node's bytes.
    */
  private def entitiesAsBytesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _)) => c.liveEntitiesAsBytes
      case _ => members.liveEntitiesAsBytesOf(id)
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
  private val chromeTemplate: Mustache = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compile("chrome", chrome)._1
  }

  /** The body — no page shell, no theme ([[themeStyleTag]] sits outside it):
    * what a repaint or navigate `inner`-patches into `#dashboard`, with the
    * per-node trace, including every surface BAKED into it, which is what a
    * page load needs to seed the log for the surfaces the client can already
    * see.
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

  /** A floor on the document's bytes, for sizing the one buffer it is written
    * into — the theme's style tag, a rough per-node figure, and slack for the
    * shell around it. A builder that grows instead pays amortized doubling
    * across the whole page, which is the copy this walk exists to remove.
    */
  private[runtime] def pageBytesHint: Int =
    themeStyleTag.length + Renderer.NodeBytesHint * nodeCount(dashboard.card) +
      4096

  /** The page walked straight into `out`, returning only the per-node trace.
    * '''This is the document path''' — the only one there is.
    *
    * It takes the sink rather than owning one so the SERVER's shell — head,
    * banners, closing scripts — is the same bytes: `Server.pageInto` writes
    * around this as a writer hole. Interpolating a finished body into a
    * document String instead costs a second full copy of the page.
    *
    * The style sits BEFORE the chrome, so every patch target inside it can be
    * repainted without re-sending the CSS. A restored `popup` is BAKED into the
    * host's `{{{popups}}}` hole, the way a selected tab panel is baked into its
    * owner: otherwise the dialog cannot appear until the stream connects and
    * patches it in, which a refresh sees as the dashboard painting first and
    * the dialog popping in late. A theme whose chrome has no hole renders
    * without it — the var is optional in the contract, and the patch still
    * arrives.
    *
    * The trace is what the page owes the log. Every other node starts with NO
    * entry, which reads as "you are up to date" and is true: the document was
    * server-rendered from current state. An open surface is the exception —
    * with no entry, the first live tick would hand the client its own surface
    * straight back.
    */
  private[runtime] def renderPageInto(
      out: Sink,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None
  ): Map[NodeId, Painted] = {
    val own = new java.util.HashMap[NodeId, Painted]()
    val pageOut = out
    // The body and the restored dialog are WRITER HOLES, not values: each is
    // walked straight into the page buffer when the chrome reaches its hole.
    // Building them as Strings first cost a full copy of the document each,
    // for holes written exactly once — 128 kB of the ~3.8 MB a page open
    // allocates, and two of the four live copies it held at peak.
    val root = dashboard.card
    val bodyInto: java.io.Writer => Unit = _ =>
      tracedInto(
        pageOut,
        root,
        LayoutNode.rootId("", root),
        "",
        states,
        uiState,
        own
      )
    val dialogInto: Option[java.io.Writer => Unit] =
      popup.flatMap(sid => surfaceWalk(pageOut, sid, states, uiState, own))
    // The style, the chrome, the body and the dialog go into ONE buffer, and
    // the two big holes write themselves into it. Not mustache's own
    // `execute(ctx)`: that renders into a `StringWriter` over a 16-char
    // `StringBuffer`, which grew five times writing a body this size under a
    // lock nothing shared, copied the buffer once on `toString`, and then `+`
    // copied the whole page AGAIN to prepend the style tag.
    val _ = pageOut.append(themeStyleTag)
    // The dialog a refresh is restoring, baked into the host exactly as the
    // connect would patch it — same render, so the two are byte-identical and
    // the later patch is a no-op morph. A theme whose chrome has no `popups`
    // hole simply never calls the walk.
    val scope = new Renderer.PageScope(
      Map("body" -> bodyInto) ++ dialogInto.map("popups" -> _)
    )
    Templates.run(chromeTemplate, pageOut, scope)
    own.asScala.toMap
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
  /** A surface as a WRITER HOLE for the page's chrome — the same walk
    * [[renderSurfaceTraced]] runs, tracing into the page's own buffer and
    * accumulator instead of building a String for mustache to splice. `None`
    * for a surface this dashboard does not have, which is what leaves the
    * chrome's hole empty.
    */
  private def surfaceWalk(
      out: Sink,
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      trace: java.util.HashMap[NodeId, Painted]
  ): Option[java.io.Writer => Unit] =
    // The writer is IGNORED, exactly as the region walks ignore theirs: the
    // page's bytes go to one buffer, and mustache's writer is that buffer.
    dashboard.surfaces.get(surfaceId).map { sfc => (_: java.io.Writer) =>
      val prefix = Renderer.surfacePrefix(surfaceId)
      tracedInto(
        out,
        sfc.content,
        LayoutNode.rootId(prefix, sfc.content),
        prefix,
        states,
        uiState,
        trace
      )
    }

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
      .flatMap { case (node, prefix) =>
        render(node, id, prefix, states, uiState, form)
      }

  /** `s_<sid>__c` — what a state group's host holds, and so what a flip removes
    * or places. The same scheme the build-phase hoist uses, so a branch's
    * build-time id and the id a flip records are one story.
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
    * a digest per member: the host's contents are re-supplied wholesale, so the
    * next live diff must compare against what this fill actually put there.
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
    * this host", so they answer here rather than at each fill site.
    */
  def renderHost(
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
      // instead and reads the same value as `dashboard_slug` — one fact, and
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
    * The card decides it ALONE, and that is what makes it one question rather
    * than a walk: every hole in a template is filled by a node, so no card's
    * bytes can contain another's.
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
    * One element does both jobs — what a patch targets IS what the node is —
    * because a node holds its regions in other nodes, so there is nothing for
    * the target to have to exclude.
    */
  def elementId(id: NodeId): DomId = DomId.derived(id)

  /** The element a node's BAKED region lives in — an `Inner`/`append` target,
    * and the `{{hostId}}` a structural card writes on it.
    *
    * '''This is not a new id — for a bake owner it IS
    * [[fh.view.model.Surface.hostId]]''', so `Tabs` resolves to `c_2_panel`,
    * byte-identical to the `id="{{id}}_panel"` a template would otherwise
    * hardcode. That removes a duplication rather than adding one: the
    * alternative has Pkl and Scala deriving the same string independently, with
    * nothing checking they agree.
    *
    * A region needs an id only where something FILLS it, which is exactly where
    * `bakeAs` already names it (a tab panel, an `If` branch). An EAGER region —
    * `Grid`/`Row`/`Column`, and every card's default one — is never a fill
    * target, because its children arrive nested in the same bytes, so those
    * nodes fall back to their own id and simply never use it.
    *
    * '''A CANDIDATE SET reaches the same fallback and does use it.''' A
    * `SetNode` has no card, so it declares no regions and has no `bakeAs` to
    * name one — but its members ARE filled into it (`Patches` anchors an
    * `Append` here, and a refill targets it with an `Inner`). The fallback is
    * right rather than lucky: a set has exactly one implicit hole and its
    * members are `<setId>_<slug>`, so the set's own element IS the thing they
    * live in and there is nothing for a second id to name.
    */
  def hostId(id: NodeId): DomId =
    surfaces
      .bakeGroup(id)
      .headOption
      .flatMap(dashboard.surfaces.get)
      .map(_.hostId)
      .getOrElse(elementId(id))

  /** `(bakeIndex vars, selection)` for a component that owns a bake group: the
    * index so a tabs template can seed its signal, plus the member the viewer's
    * selection names — its `bakeAs` region is filled from it. Selection
    * dispatches on activation mode — [[resolveActive]] for user groups,
    * [[resolveActiveByState]] for state groups.
    *
    * The selection is filled LAZILY, by the walk: the region's callback renders
    * the member's surface into the host's buffer only when the template
    * actually reaches the hole, and its trace joins the walk's own collection
    * on the way past. A state group with no matching branch selects nothing,
    * and the hole renders empty.
    */
  private def resolveBakeTraced(
      id: NodeId,
      uiState: Map[String, String],
      states: Map[String, EntityState]
  ): (Map[String, String], Option[(String, String)]) = {
    val group = surfaces.bakeGroup(id)
    def bakeMember(
        idx: Int
    ): (Map[String, String], Option[(String, String)]) = {
      val sid = group(idx)
      (
        Map("bakeIndex" -> idx.toString),
        dashboard.surfaces(sid).bakeAs.map(as => as -> sid)
      )
    }
    if (group.isEmpty) (Map.empty, None)
    else
      activeBakeIndex(id, uiState, states) match {
        case Some(idx) => bakeMember(idx)
        case None      =>
          // A state group with no matching branch: nothing selects, so the
          // region section renders empty. A group's members share one bakeAs
          // (they bake into one hole), so per-member naming never diverges.
          (Map.empty, None)
      }
  }

  /** WHICH member of `id`'s bake group is selected, dispatching on activation
    * mode — the one thing a bake owner's own rendering reads beyond its slots.
    * `None` for a node that owns no group, and for a state group whose branches
    * all fail.
    *
    * Split out of [[resolveBakeTraced]] so the SELECTION can be answered
    * without rendering the member it selects.
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
    * ONE part: the CONTENT VERSION of each entity whose movement can change
    * this node's BYTES ([[entitiesAsBytesForNode]]). Not every entity it binds:
    * a slot that travels as a signal is absent from the patch form entirely
    * (ADR 0017), so its value moving cannot move these bytes, and keying on it
    * would invalidate a generation whose re-render is identical. The reverse
    * index still wants the wider list — a signal has to make its node a
    * candidate or no frame is ever computed for it — which is why
    * [[fh.view.model.LayoutNode.Component]] carries both and neither derives
    * the other. A version rather than the value because [[EntityState]]'s
    * synthesized `hashCode` recurses into the attribute map on every lookup,
    * and because it is MORE discriminating than the render is: `lastUpdated`
    * moves on ticks that change no rendered byte. An entity the snapshot does
    * not hold has NO entry, which is a distinct key from any version it could
    * have — [[resolveSlot]] renders such a slot from a synthetic empty state.
    *
    * A viewer's SELECTION is not in it, and takes no argument here. Only
    * structure reads a selection, and structure is never cached: the leaf
    * beside it that a frame actually re-renders mentions no selection at all.
    *
    * Deliberately NOT included either: the node's children. Including them
    * would make any descendant's tick invalidate every ancestor to the root,
    * which is the whole reason a per-node cache earns anything.
    *
    * `None` — NOT CACHEABLE — is what keeps that honest, and it is the reason
    * this returns an `Option` rather than a key that a caller has to know not
    * to trust. It is now exactly [[hasOwnRendering]]'s `false`: structure, or a
    * candidate set root. Both compose rather than render, and neither is a
    * patch target, so nothing is lost by excluding them.
    */
  def renderInputs(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[RenderInputs] =
    members
      .memberAt(id, states)
      .map(m =>
        RenderInputs(
          // The SUBJECT is in the key whether or not a slot reads it: the
          // materialised node is state-derived, so the entity that chose its
          // case has to be able to invalidate the bytes that case produced.
          versions(
            m.node.subjectEntity.toList ++ m.node.liveEntitiesAsBytes,
            states
          )
        )
      )
      .orElse(
        Option.when(hasOwnRendering(id))(
          RenderInputs(versions(entitiesAsBytesForNode(id), states))
        )
      )

  /** The values of the slots that travel as BYTES — the render cache's
    * pre-check.
    *
    * [[renderInputs]] keys on a per-entity `contentVersion`, which moves
    * whenever ANYTHING about that entity moves. So a change reaching only
    * signal slots still moves the key, misses, and re-renders the node to
    * discover its bytes are identical — which on the shipped `entityCard` is
    * every brightness tick, because one slot (the name) reads the entity as
    * bytes and re-admits it to the key. These values answer the question
    * directly instead: equal values, equal bytes, so the entry's bytes stand.
    *
    * '''Only the byte slots, and that is the whole point.''' Building the full
    * [[Resolved]] would resolve every slot — the signal ones included, and on
    * the shipped card that is where the CEL transform lives — and hand most of
    * the saving back while still looking like a saving. Measured at 0.28 µs
    * against a 5.45 µs render per node (`RenderBench.byteSlotResolve` against
    * `tickRender`).
    *
    * '''`None` means "cannot answer cheaply", never "unchanged".''' Exactly two
    * shapes take it, and both are ordinary rather than defects:
    *
    *   - a MEMBER, which is not in `allIndexed` at all — its bytes are its
    *     whole subtree's, resolved through [[ResolvedMember]];
    *   - a DYNAMIC SUBJECT, where the subject decides every slot's inherited
    *     entity, its signal names and its bindings, so the byte slots alone do
    *     not determine the bytes ([[resolveDirect]] owns that node).
    *
    * A bake selection would be a third, because such a node's markup reads a
    * selection these values do not carry — but it cannot happen, and this
    * RAISES rather than degrading quietly if it ever does. A node reading its
    * own selection holds regions, which makes it structure, and structure has
    * no own rendering and is never cached (ADR 0012). The two conditions are
    * therefore mutually exclusive, and a `None` here would be indistinguishable
    * from the honest ones above while producing permanently stale bytes. Asking
    * `bakeGroup` is a map lookup, not a resolution, so the check is cheaper
    * than the fallback it replaced.
    */
  private[runtime] def byteSlotValues(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[Map[String, String]] =
    allIndexed.get(id).flatMap {
      case (c: LayoutNode.Component, _) if hasOwnRendering(id) =>
        if (surfaces.bakeGroup(id).nonEmpty)
          sys.error(
            s"node $id has its own rendering AND a bake group; ADR 0012 holds " +
              "these are exclusive (a bake owner holds regions, so it is " +
              "structure and is never cached). The render cache's byte-value " +
              "pre-check cannot see a selection, so this must be fixed rather " +
              "than tolerated."
          )
        val plan = planOf(id, id, c, states)
        if (plan.subjectDynamic) None
        else {
          val b = Map.newBuilder[String, String]
          plan.dynamic.foreach { case (slot, srcEntity, source) =>
            if (!plan.signalSlots.contains(slot))
              b += ((slot, resolveSlot(srcEntity, source, states)))
          }
          Some(b.result())
        }
      case _ => None
    }

  private def versions(
      entities: List[String],
      states: Map[String, EntityState]
  ): Map[String, Long] =
    entities.distinct
      .flatMap(e => states.get(e).map(e -> _.contentVersion))
      .toMap

  /** One node's bytes, in the requested form.
    *
    * The patch form comes out of the trace's `own` rather than from a second
    * field on [[Traced]], because `own` is where it was always going: it holds
    * exactly the nodes that HAVE a patch rendering, and its bytes are what the
    * digests are taken from, so reading it here is what makes "what
    * `renderNodeById` returns" and "what `holds` recorded" the same string by
    * construction instead of by two code paths agreeing.
    *
    * `None` for a node with no rendering of its own — which [[renderIndexed]]
    * has already excluded, so it does not arise.
    */
  private def render(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      form: SlotForm
  ): Option[String] = {
    val t = traced(node, id, idPrefix, states, uiState)
    if (form.isPatch) t.rootOwn else Some(t.html)
  }

  /** The composed rendering, and every node's OWN html inside it.
    *
    * The walk already computes both — a child is rendered before the parent it
    * is spliced into — so the trace is a matter of not discarding it. Anything
    * needing per-node bytes after a wholesale render (a fill recording what it
    * put in a host, the page seeding the log for its open surfaces) would
    * otherwise walk the whole subtree a SECOND time, node by node.
    *
    * `own` carries an entry only for nodes that have a rendering of their own
    * ([[hasOwnRendering]]) — the same set that may be a log key — and its bytes
    * are what [[renderNodeById]] would return for that id, which is what makes
    * the digests comparable at all.
    */
  private[runtime] case class Traced(
      html: String,
      own: Map[NodeId, Painted],
      // The walk ROOT's own patch-form bytes, where it has any. Only
      // [[Renderer.render]] wants real bytes out of a trace, and it always
      // wants the root's, so every other node keeps only its digest.
      rootOwn: Option[String] = None
  )

  /** A static floor on the nodes a subtree renders, for sizing its buffer: the
    * structure plus every region child. A set counts its candidates and members
    * — its live membership is bounded by both.
    */
  private def nodeCount(node: LayoutNode): Int = node match {
    case c: LayoutNode.Component =>
      1 + c.regions.values.view.map(_.map(nodeCount).sum).sum
    case s: LayoutNode.SetNode =>
      1 + math.max(s.candidates.size, s.members.size)
  }

  private def traced(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): Traced = {
    val own = new java.util.HashMap[NodeId, Painted]()
    val root = new Array[String](1)
    val html = tracedHtml(node, id, idPrefix, states, uiState, own, root)
    Traced(html, own.asScala.toMap, Option(root(0)))
  }

  /** A subtree's DOCUMENT bytes, with its trace written into the CALLER's
    * accumulator — the walk recursion's spine: a page is one buffer from the
    * root down and one trace map from the root down.
    *
    * The buffer is sized to the subtree's node count — a builder that grows
    * instead pays amortized doubling across the whole page, which is precisely
    * the copy this walk exists to remove. The count comes from the plan for a
    * component (the walk asks the same plan a moment later), and from the
    * static shape for a set.
    */
  private def tracedHtml(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      trace: java.util.HashMap[NodeId, Painted],
      // One slot for the WALK ROOT's own patch bytes — see [[Traced.rootOwn]].
      // `null` for a page, whose root is structure and has none.
      rootOwn: Array[String] | Null = null
  ): String = {
    val out = Sink.buffer(
      Renderer.NodeBytesHint * (node match {
        case c: LayoutNode.Component => planOf(id, id, c, states).nodeCount
        case _                       => nodeCount(node)
      })
    )
    tracedInto(out, node, id, idPrefix, states, uiState, trace, rootOwn, id)
    out.result
  }

  /** Renders the node's DOCUMENT bytes into `out` — appending, never
    * truncating, so a page is one buffer from the root down — and puts every
    * own-rendering node's patch bytes into `trace`, this node included.
    *
    * `trace` is an ACCUMULATOR, on purpose: children write where they land and
    * the root converts once. Returning a per-level own-map and merging upward
    * (`_ ++ _` per child) instead allocates a `Map2`/`Map3` and a tuple per
    * node per paint, and re-copies every ancestor's map k times for k children
    * — the same reason the bytes go through one buffer.
    */
  private def tracedInto(
      out: Sink,
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      trace: java.util.HashMap[NodeId, Painted],
      // Where to leave the ROOT's own patch bytes, and which id that is. Only
      // the root's are kept; every other node contributes a digest alone.
      rootOwn: Array[String] | Null = null,
      rootId: NodeId | Null = null
  ): Unit =
    node match {
      case c: LayoutNode.Component =>
        val (bakeIndex, bakeSel) = resolveBakeTraced(id, uiState, states)
        // ONE walk, both forms — see [[Traced]]. Only the form differs between
        // the two calls, so a node with no signal slot anywhere under it does
        // the second not at all.
        // Resolved ONCE for both forms — the transforms, the signal names and
        // the bindings do not depend on which form is being produced, and
        // re-deriving them for the patch render was the bulk of what a signal
        // slot cost a first paint.
        //
        // Everything about the node that a paint cannot change comes from the
        // PLAN ([[NodePlan]]) — the card's template, the structural vars, the
        // constant slot values, the bindings and signal names — so a paint
        // resolves only the entity-derived slots and assembles one map.
        val plan = planOf(id, id, c, states)
        val resolved = resolvePlanned(plan, states, bakeIndex)
        val tpl = plan.tpl
        // The patch form is what `own` is fingerprinted from, and `own` is the
        // only thing that reads one — so it is needed exactly where there is an
        // `own`: a node with its own rendering whose slots carry a signal.
        // Structure renders ONCE.
        //
        // The children passed here are the document ones on purpose, not a
        // patch-form set: a node with its own rendering has no regions
        // (`CardDef.isStructure` IS "has regions"), so there is nothing to
        // compose.
        val twoForms = plan.ownRendering && plan.declaresSignals
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
        // A bake owner gets a wrapper like anything else: the cell is the
        // LAYOUT item, which is a different job from being a patch target (it is
        // not one — it holds regions). Denying it a cell would silently drop
        // `.columns(n)` on every `Tabs`/`If`.
        //
        // The wrapper is also where a signal slot's `data-signals` SEED rides
        // (ADR 0017), and it rides the wrapper because the wrapper is
        // renderer-owned and appears in exactly the renders that should carry
        // it: the DOCUMENT render includes it, the PATCH render is the card's
        // own markup and is correctly seedless.
        //
        // ONE attribute for the whole node, not one per slot: two
        // `data-signals` on one element is what a per-slot seed produces the
        // moment a card has two signal slots, and the browser silently keeps
        // one of them. Values come from [[Resolved.signals]] — the same
        // resolution the template was rendered from, so the seeded value and
        // the painted value cannot disagree.
        //
        // Wrapper and body go into ONE buffer, so the body is written where it
        // is going rather than built and then copied in.
        val wrapped = plan.wrapped
        // Regions whose loops the visitor made INLINE (body exactly
        // `{{{html}}}`): their children are traced INTO this node's buffer at
        // the hole position and no child String exists. Any other region keeps
        // the string splice, rendered isolated exactly as before — a template
        // written differently loses speed, never bytes.
        val inline = plan.inline

        def childId(region: String, i: Int, child: LayoutNode) =
          LayoutNode.childId(idPrefix, id, LayoutNode.Step(region, i), child)

        def wrapper(buf: Sink, form: SlotForm): Unit =
          if (wrapped) {
            buf
              .append("""<div class="fh-cell""")
              .append(Renderer.cellClasses(c.cell))
              .append("""" id="""")
              .append(id)
              .append('"')
            if (!form.isPatch)
              Datastar.seedAttrInto(buf, plan.signalSeed, resolved.signals)
            buf.append('>')
          }

        def bodyInto(
            buf: Sink,
            form: SlotForm
        ): Unit = {
          // A leaf (no regions, no bake): the template runs against the bare
          // context — no walk, no builders, nothing to collect. A bake owner
          // with no AUTHORED regions (an `If` host) is not a leaf: its hole is
          // a region all the same, filled from the selection.
          if c.regions.isEmpty && bakeSel.isEmpty then
            Templates.run(
              tpl,
              buf,
              NodeContext(resolved, Map.empty, form)
            )
          else {
            // The mustache path's children: only the NON-inline regions, and
            // only when the card has any. Each child traces into the SHARED
            // accumulator — its own-map lands here, not through a per-level
            // merge.
            val childrenHtml: Map[String, List[String]] =
              c.regions.view.collect {
                case (region, nodes) if !inline.contains(region) =>
                  region -> nodes.zipWithIndex.map { case (child, i) =>
                    tracedHtml(
                      child,
                      childId(region, i, child),
                      idPrefix,
                      states,
                      uiState,
                      trace
                    )
                  }
              }.toMap
            // A baked member whose hole the visitor did NOT inline (a template
            // that writes the region differently) keeps the string splice —
            // the surface rendered isolated, its trace put into the shared
            // accumulator exactly as the inline path does. Slower, never
            // different bytes.
            val bakedHtml: Map[String, List[String]] =
              bakeSel match {
                case Some((region, sid)) if !inline.contains(region) =>
                  renderSurfaceTraced(sid, states, uiState)
                    .map { t =>
                      t.own.foreach { case (nid, p) => trace.put(nid, p) }
                      Map(region -> List(t.html))
                    }
                    .getOrElse(Map.empty)
                case _ => Map.empty
              }
            // The walk the region codes consult: children traced INTO `buf`,
            // their own-bytes landing in the shared accumulator on the way
            // past. A BAKED region walks the selected surface the same way —
            // its nodes keep their surface-derived ids — which is how a page
            // load fingerprints the surfaces it baked without walking them
            // twice.
            val walk: Map[String, java.io.Writer => Unit] =
              inline.view.collect {
                case region if c.regions.contains(region) =>
                  region -> { (_: java.io.Writer) =>
                    c.regions(region).zipWithIndex.foreach { case (child, i) =>
                      tracedInto(
                        buf,
                        child,
                        childId(region, i, child),
                        idPrefix,
                        states,
                        uiState,
                        trace
                      )
                    }
                  }
                case region if bakeSel.exists(_._1 == region) =>
                  val sid = bakeSel.get._2
                  region -> { (_: java.io.Writer) =>
                    dashboard.surfaces.get(sid).foreach { s =>
                      val prefix = Renderer.surfacePrefix(sid)
                      tracedInto(
                        buf,
                        s.content,
                        LayoutNode.rootId(prefix, s.content),
                        prefix,
                        states,
                        uiState,
                        trace
                      )
                    }
                  }
              }.toMap
            Templates.run(
              tpl,
              buf,
              NodeContext(resolved, childrenHtml ++ bakedHtml, form, walk)
            )
          }
        }

        // The trace keeps the DIGEST, never the bytes — every reader wanted
        // the fingerprint. The one exception is the walk ROOT, whose bytes
        // `renderNodeById` returns, and only then.
        val isRoot = rootOwn != null && id == rootId
        def documentInto(o: Sink): Unit = {
          wrapper(o, SlotForm.Document)
          bodyInto(o, SlotForm.Document)
          if (wrapped) { val _ = o.append("</div>") }
        }
        // No signal slot: the two forms are byte-identical, so the patch
        // fingerprint IS the document bytes about to be written, and the sink
        // fingerprints the run as it passes rather than the walk cutting it
        // back out afterwards. That is the whole of what a streaming page
        // needs from its destination, and a buffer still slices.
        val inlineDigest: Digest | Null =
          if (plan.ownRendering && !twoForms) {
            val (d, bytes) = out.digesting(isRoot)(documentInto)
            if (isRoot) rootOwn.nn(0) = bytes.nn
            d
          } else {
            documentInto(out)
            null
          }

        // What this node contributes to the trace: its whole (wrapped) patch
        // rendering when it is a LEAF, and nothing when it is structure.
        // Mirrors `renderNodeById` exactly — wrapper included, and in the PATCH
        // form, which is what that method produces.
        val own = Option.when(plan.ownRendering) {
          if (twoForms) {
            // Borrowed: these bytes are copied out by `result` and the buffer
            // itself is wanted by nobody.
            val bytes = Sink.scratched { buf =>
              wrapper(buf, SlotForm.Patch)
              bodyInto(buf, SlotForm.Patch)
              if (wrapped) { val _ = buf.append("</div>") }
              buf.result
            }
            if (isRoot) rootOwn.nn(0) = bytes
            Painted(Digest.of(bytes), resolved.signals)
          } else Painted(inlineDigest.nn, resolved.signals)
        }
        // The baked surface's trace is already in `trace` — the walk put it
        // there, or the fallback did — so `own` is the only addition here.
        own.foreach(trace.put(id, _))
      // A container root composes its members and so has no own rendering; the
      // members do, and they are what a fill must fingerprint.
      case s: LayoutNode.SetNode =>
        // The match IS the proof: this node is a `SetNode`, which is exactly
        // the evidence `MemberGraph` mints its root [[SetId]]s from.
        val setId = SetId.of(id, s)
        // ONE form. A set root has no rendering of its own and is never a patch
        // target, so a patch form of it could only ever be embedded in an
        // ancestor's — and an ancestor has no `own` either, so that form is
        // discarded unread all the way to the root. What IS read is each
        // MEMBER's patch rendering, below, which is a patch target.
        // Membership is evaluated ONCE and each member resolved once, then
        // both forms and the seed come off that. Evaluating `membersOf` twice
        // is not just a repeated lookup: it re-tests every candidate's `when`
        // against live state.
        val resolved =
          members
            .membersOf(setId, states)
            .map(m => m -> resolveMember(m, states))
        // The set wrapper and every member's DOCUMENT bytes go into the walk's
        // one buffer — a member String here would be this buffer's bytes
        // copied out and copied back in (issue #237). Each member's patch
        // fingerprint comes off the same pass: rendered separately only where
        // the forms actually differ (a signal slot somewhere in the subtree),
        // otherwise SLICED from the document bytes just written — the same
        // trick a static node's non-signal `own` uses, and one full member
        // render saved on every signal-less member, which is most of them.
        out
          .append("""<div class="fh-cell fh-group""")
          .append(Renderer.cellClasses(s.cell))
          .append("""" id="""")
          .append(setId)
          .append("\">")
        resolved.foreach { case (m, rm) =>
          val sigs = memberSignalsOf(rm)
          val digest =
            if (sigs.isEmpty)
              // No signal slot anywhere in the member's subtree: the seed
              // attr is absent either way ([[Datastar.signalsAttr]] of
              // nothing is "") and there is no value to withhold, so the
              // two forms are byte-identical — the sink fingerprints the run
              // as it writes it.
              out
                .digesting(false)(
                  renderResolvedMemberInto(_, m, rm, SlotForm.Document)
                )
                ._1
            else {
              renderResolvedMemberInto(out, m, rm, SlotForm.Document)
              Digest.of(Sink.scratched { buf =>
                renderResolvedMemberInto(buf, m, rm, SlotForm.Patch)
                buf.result
              })
            }
          trace.put(m.id, Painted(digest, sigs))
        }
        out.append("</div>")
    }

  private def renderSet(
      id: SetId,
      cell: Option[Cell],
      states: Map[String, EntityState],
      form: SlotForm
  ): String =
    setElement(
      id,
      cell,
      members.membersOf(id, states).map(renderMember(_, states, form))
    )

  /** The group root is itself a cell (a first-class layout item in its
    * container) plus `.fh-group`, the themed flow container its per-entity
    * member cells live in. Authored `cell` classes (e.g. `fh-cols-full` to span
    * a parent grid) ride on it.
    */
  private def setElement(
      id: SetId,
      cell: Option[Cell],
      members: Seq[String]
  ): String = {
    // Appends, not `mkString` inside an interpolation: `mkString` builds one
    // String and the interpolation copies it whole again — twice the group's
    // bytes per set render (issue #237).
    val out = Sink.buffer(
      160 + members.foldLeft(0)(_ + _.length)
    )
    out
      .append("""<div class="fh-cell fh-group""")
      .append(Renderer.cellClasses(cell))
      .append("""" id="""")
      .append(id)
      .append("\">")
    members.foreach(out.append)
    out.append("</div>")
    out.result
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
  ): String = renderResolvedMember(m, resolveMember(m, states), form)

  /** A member's card, resolved — and, recursively, every unaddressed node under
    * it. The counterpart of [[Resolved]] for the tree a member renders as ONE
    * patch unit.
    *
    * Worth more here than for a static node: a candidate set re-renders every
    * matched member on every event, and a member used to be resolved three
    * times per walk — once for the group's document bytes, once for its own
    * patch bytes, and once again by `memberSignals` for the seed.
    */
  private case class ResolvedMember(
      cardName: String,
      tpl: Mustache,
      resolved: Resolved,
      regions: Map[String, List[ResolvedChild]]
  )

  /** One node inside a member. Ordinary children render whole and unaddressed —
    * the member is their patch target. A nested SET is the exception: it is an
    * addressable container of its own, so it renders as its group element and
    * its members are patched individually rather than through the tile.
    */
  private enum ResolvedChild {

    /** No id: a child's `.fh-cell` is layout only, since the MEMBER is the
      * patch target for everything under it.
      */
    case Node(cell: Option[Cell], node: ResolvedMember)

    /** Bytes, not a resolved node — a nested set renders in the DOCUMENT form
      * whatever form encloses it (blanking its values here would withhold ones
      * no patch of THIS node restores), so they do not vary by form and are
      * rendered once.
      */
    case NestedSet(html: String)
  }

  private def resolveMember(
      m: Member,
      states: Map[String, EntityState]
  ): ResolvedMember = {
    val plan = planOf(m.id, m.id, m.node, states)
    ResolvedMember(
      m.node.card,
      plan.tpl,
      // `structuralVars(m.id)` for every node in the subtree, member and
      // children alike: the children have no ids of their own, so their signals
      // are minted in the MEMBER's namespace and seeded on its wrapper.
      resolvePlanned(plan, states, Map.empty),
      Renderer.perRegion(m.node.regions)((child, step) =>
        resolveChild(m, child, List(step), m.clause, states)
      )
    )
  }

  private def resolveChild(
      m: Member,
      node: LayoutNode,
      path: List[LayoutNode.Step],
      clauseIdx: Int,
      states: Map[String, EntityState]
  ): ResolvedChild = node match {
    case c: LayoutNode.Component =>
      // A member's children have no ids of their own, so their plan key is the
      // member's id plus the position under it — the same map a member's
      // structural vars are built from, one plan per authored position.
      val plan = planOf(s"$m.id\u0000${path.mkString("/")}", m.id, c, states)
      ResolvedChild.Node(
        c.cell,
        ResolvedMember(
          c.card,
          plan.tpl,
          resolvePlanned(plan, states, Map.empty),
          Renderer.perRegion(c.regions)((child, step) =>
            resolveChild(m, child, path :+ step, clauseIdx, states)
          )
        )
      )
    case inner: LayoutNode.SetNode =>
      ResolvedChild.NestedSet(
        renderSet(
          members.innerSetId(m.id, clauseIdx, path, inner),
          inner.cell,
          states,
          SlotForm.Document
        )
      )
  }

  /** Execute an already-resolved member in one form.
    *
    * Every member gets the SAME id'd `.fh-cell` wrapper as a static component,
    * so it is an addressable patch target (in-place morph / insert / remove)
    * rather than only ever re-rendered as part of the whole group — which is
    * why the wrap here is UNCONDITIONAL (a `wrapAsCell = false` card has no
    * member morph target and is not usable as a set clause).
    */
  private def renderResolvedMember(
      m: Member,
      rm: ResolvedMember,
      form: SlotForm
  ): String = {
    Sink.scratched { out =>
      renderResolvedMemberInto(out, m, rm, form)
      out.result
    }
  }

  /** The member's whole wrapped rendering, written into the CALLER's buffer.
    *
    * The document walk appends members straight into its one buffer — a member
    * String would be that buffer's bytes copied out and copied back in. Only
    * the patch path materializes ([[renderResolvedMember]] above), because a
    * member's patch bytes ARE its cache entry.
    */
  private def renderResolvedMemberInto(
      out: Sink,
      m: Member,
      rm: ResolvedMember,
      form: SlotForm
  ): Unit = {
    // A member's children have no ids, so the seed here covers THEM too — see
    // [[memberSignalsOf]]. That is why a member's wrapper carries the whole
    // patch unit's signals rather than only its own card's.
    out
      .append("""<div class="fh-cell""")
      .append(Renderer.cellClasses(m.node.cell))
      .append("""" id="""")
      .append(m.id)
      .append('"')
    if (!form.isPatch) out.append(Datastar.signalsAttr(memberSignalsOf(rm)))
    out.append('>')
    memberBodyInto(out, rm, form)
    out.append("</div>")
  }

  /** The member's own markup — everything inside its wrapper.
    *
    * Children of a region whose loop the visitor made INLINE (body exactly
    * `{{{html}}}`) are written into this buffer through the walk: the member's
    * engine run carries a [[NodeContext.regionWalk]] that appends the child's
    * wrapper and body at the hole position, and no child String exists. Any
    * other region keeps the string splice, each child rendered isolated exactly
    * as before — a template written differently loses speed, never bytes.
    * (Members' children have no ids, so the walk collects nothing: the member
    * is the whole subtree's patch target.)
    */
  private def memberBodyInto(
      out: Sink,
      rm: ResolvedMember,
      form: SlotForm
  ): Unit = {
    val inline = templates.inlineRegions.getOrElse(rm.cardName, Set.empty)
    val walk: Map[String, java.io.Writer => Unit] =
      inline.view.collect {
        case region if rm.regions.contains(region) =>
          region -> { (_: java.io.Writer) =>
            rm.regions(region).foreach {
              case ResolvedChild.NestedSet(html) => out.append(html)
              case ResolvedChild.Node(cell, n)   =>
                out
                  .append("""<div class="fh-cell""")
                  .append(Renderer.cellClasses(cell))
                  .append("""">""")
                memberBodyInto(out, n, form)
                out.append("</div>")
            }
          }
      }.toMap
    // Only the NON-inline regions need child strings: an inline region's
    // section never reads them (the walk answers first), and a region that is
    // not in the compiled set keeps its splice.
    val childrenHtml: Map[String, List[String]] =
      if (walk.isEmpty) memberChildrenHtml(rm, form)
      else
        rm.regions.view.collect {
          case (region, kids) if !walk.contains(region) =>
            region -> kids.map {
              case ResolvedChild.NestedSet(html) => html
              case ResolvedChild.Node(cell, n)   =>
                Sink.scratched { child =>
                  child
                    .append("""<div class="fh-cell""")
                    .append(Renderer.cellClasses(cell))
                    .append("""">""")
                  memberBodyInto(child, n, form)
                  child.append("</div>").result
                }
            }
        }.toMap
    executeInto(out, rm.tpl, rm.resolved, childrenHtml, form, walk)
  }

  /** Every region's children as splice strings — the pre-walk shape, kept for
    * templates whose loops are not inline-eligible.
    */
  private def memberChildrenHtml(
      rm: ResolvedMember,
      form: SlotForm
  ): Map[String, List[String]] =
    rm.regions.view
      .mapValues(_.map {
        case ResolvedChild.NestedSet(html) => html
        case ResolvedChild.Node(cell, n)   =>
          Sink.scratched { child =>
            child
              .append("""<div class="fh-cell""")
              .append(Renderer.cellClasses(cell))
              .append("""">""")
            memberBodyInto(child, n, form)
            child.append("</div>").result
          }
      })
      .toMap

  /** A resolved member's signals, children INCLUDED — they share its id and its
    * patch. Stops at a nested set for the reason [[Member.entitiesOf]] does:
    * that set is addressable in its own right, so its members own their own
    * signals — which is why [[ResolvedChild.NestedSet]] holds no resolution to
    * descend into.
    */
  private def memberSignalsOf(rm: ResolvedMember): Map[SignalId, String] =
    rm.regions.values.flatten.foldLeft(rm.resolved.signals) {
      case (acc, ResolvedChild.Node(_, n))   => acc ++ memberSignalsOf(n)
      case (acc, ResolvedChild.NestedSet(_)) => acc
    }

  /** Unreachable `None` by construction: `Dashboard.validate` resolves every
    * card reference before a Renderer is built.
    */
  private def templateOf(cardName: String): Mustache =
    templates.components.getOrElse(
      cardName,
      throw new IllegalStateException(
        s"unknown card '$cardName' — validate should have rejected this dashboard"
      )
    )

  /** A card's slots resolved, which is everything the two forms SHARE.
    *
    * The forms differ in one step and one only — a signal slot's value is
    * withheld from the patch form — so resolution happens once and
    * [[executeResolved]] is run per form. Before this split, asking for both
    * forms of a node re-ran the whole of [[resolvePlanned]] for the second:
    * every JSONata transform again, every signal name again, to arrive at the
    * same map and blank two entries in it. On a page of leaves with signal
    * slots that duplicated transform evaluation was the single largest cost of
    * a first paint.
    *
    * @param vars
    *   the card's own resolved slots, its injected structural vars and its
    *   signal bindings — form-independent, all of it.
    * @param signalSlots
    *   the slot names a PATCH form blanks. Empty for a card that opted into
    *   nothing, which is what makes both forms the same string there.
    * @param signals
    *   the same slots' values under their signal names — what the document
    *   form's seed carries and what `own` records as sent. Derived here rather
    *   than by [[signalsOfSlots]] because that would resolve, for a THIRD time,
    *   values this resolution already holds: the transform, the subject and the
    *   signal name are all the same ones.
    */
  /** One paint's resolution — LAYERS, not a merged map.
    *
    * The plan's constant layers ride by reference; only what a paint can
    * actually change is built fresh, and only as large as it is. Assembling one
    * immutable map per node per paint instead (structural, bake, constants,
    * live values, bindings — a `Map.newBuilder` and five `++=` per node) buys
    * nothing the layers do not already give: `fhGet` resolves a name through
    * them in the order such a builder would apply it, last-wins read as
    * first-asked.
    *
    * @param structural
    *   constants from the node's position (ids, inherited entity)
    * @param constants
    *   literal and identity-`once` slot values
    * @param bindings
    *   the `__bind`/`__signal` strings (constant subject)
    * @param paint
    *   the live slot values — empty when the card has none
    * @param bake
    *   the client's bake selection (empty off bake groups)
    * @param liveBindings
    *   the direct path's `__bind`/`__signal` strings — the subject is dynamic
    *   there, so the names cannot be precomputed
    * @param signalSlots
    *   slots withheld (blanked) in the patch form
    * @param signals
    *   the seed values, captured as they resolve
    */
  private case class Resolved(
      structural: Map[String, String],
      constants: Map[String, String],
      bindings: Map[String, String],
      paint: Map[String, String],
      bake: Map[String, String],
      liveBindings: Map[String, String],
      signalSlots: List[String],
      signals: Map[SignalId, String]
  )

  /** The template context, READ IN PLACE.
    *
    * mustache.java resolves a name against whatever object it is handed; for a
    * `java.util.Map` that means iterating its `entrySet` per lookup (a get-only
    * map answers every name empty — that cost a probe suite to find). A context
    * is one small object that answers `get` from the maps that already exist.
    *
    * It is also where the PATCH form withholds a signal slot's value, which is
    * why there is no second map: blanking through the map
    * (`signalSlots.foldLeft(vars)(_.updated(_, ""))`) is a whole new `HashMap`
    * per signal slot per node, to change two entries.
    *
    * `null` for an unknown name is what mustache.java expects, and the engine
    * factory's `defaultValue("")` turns it into the empty string, matching what
    * a `Map` context gives for a missing key.
    */
  private case class NodeContext(
      resolved: Resolved,
      childrenHtml: Map[String, List[String]],
      form: SlotForm,
      // Region name -> trace that region's children into the writer. Set only
      // by the document walk ([[tracedInto]]) for regions whose loops are
      // inline-eligible; the region codes consult it and fall back to the
      // string splice when absent (patch renders, members). The walk's own
      // bytes land in the caller's shared accumulator — nothing to return.
      regionWalk: Map[String, java.io.Writer => Unit] = Map.empty
  ) extends Templates.FhScope {

    /** One list per REGION, under the region's own name, so a template's
      * `{{#headActions}}` and `{{#children}}` splice different children. An
      * empty region contributes nothing, which is what makes the section vanish
      * rather than render an empty list.
      *
      * Resolved READ IN PLACE — the name answers from the maps that already
      * exist (the handler in [[Templates]] calls this directly; mustache.java
      * resolves Map scopes through `entrySet`, so a get-only map answers every
      * name empty, which cost a probe suite to find).
      */
    def fhGet(name: String): AnyRef =
      childrenHtml.get(name) match {
        case Some(htmls) if htmls.nonEmpty =>
          val list = new java.util.ArrayList[AnyRef](htmls.size)
          htmls.foreach(h =>
            list.add(java.util.Collections.singletonMap("html", h))
          )
          list
        case _ =>
          if (form.isPatch && resolved.signalSlots.contains(name)) ""
          else
            // The layers, in the precedence the old merged map encoded by
            // construction (its builder order, last-wins): a name answers
            // from the first layer that has it. `getOrElse` is by-name, so
            // the chain stops at the hit — the usual cost is two or three
            // failed lookups on maps smaller than the name being looked up.
            resolved.bindings
              .getOrElse(
                name,
                resolved.liveBindings
                  .getOrElse(
                    name,
                    resolved.paint
                      .getOrElse(
                        name,
                        resolved.constants
                          .getOrElse(
                            name,
                            resolved.bake
                              .getOrElse(
                                name,
                                resolved.structural
                                  .getOrElse(name, null)
                              )
                          )
                      )
                  )
              )
      }
  }

  /** Everything about one component node that a PAINT cannot change, derived
    * once and reused every paint after: the card's template, the structural
    * vars, the constant slot values (literals, and identity-derived `once`
    * slots), the pre-resolved entity of every entity-derived slot whose
    * inheritance chain is constant, the signal bindings and their names.
    *
    * This is the "hold a reference, not a re-derivation" end of the trade. The
    * plan is one small object per node — the same one-per-node bound the render
    * cache keeps — and a paint resolves only what can actually change: the
    * `live` slot values, the signal values, the bake selection. Rebuilding the
    * whole slot resolution per paint instead costs a builder, two lists, an
    * Option and three more collections for the signal half, to arrive at values
    * that mostly have not moved.
    *
    * Lifetime: one renderer generation — the dashboard is immutable for it, so
    * the map never needs invalidation; a hot reload builds a new renderer and
    * with it a new plan map. The node is held by IDENTITY and checked on
    * lookup, because a dynamic set's clause can hand a DIFFERENT node for the
    * same member id as state moves (the first clause whose `when` holds wins):
    * a lookup that fails the identity check recomputes — the cost the node had
    * before plans existed, never different bytes.
    */
  private case class NodePlan(
      node: LayoutNode.Component,
      tpl: Mustache,
      structural: Map[String, String],
      constants: Map[String, String],
      dynamic: List[(String, Option[String], SlotSource)],
      bindings: Map[String, String],
      signalSlots: List[String],
      signalNameBySlot: Map[String, SignalId],
      // The node's `data-signals` attribute with its values cut out. Fixed by
      // the plan, because the NAMES are; see [[Datastar.SignalSeed]].
      signalSeed: Datastar.SignalSeed,
      subjectDynamic: Boolean,
      ownRendering: Boolean,
      declaresSignals: Boolean,
      wrapped: Boolean,
      inline: Set[String],
      nodeCount: Int
  )

  private val nodePlans =
    new java.util.concurrent.ConcurrentHashMap[String, NodePlan]()

  /** The plan for `node` under key `key`, built on first paint and reused
    * after. `structuralId` is the id the node's structural vars name — its own
    * for a static component, the MEMBER's for a member or a member's child
    * (children have no ids of their own), which is also why the cache key is
    * separate: a child's key is its member's id plus its position under it.
    */
  private def planOf(
      key: String,
      structuralId: NodeId,
      node: LayoutNode.Component,
      states: Map[String, EntityState]
  ): NodePlan = {
    val cached = nodePlans.get(key)
    if (cached != null && (cached.node eq node)) cached
    else {
      val p = buildPlan(structuralId, node, states)
      nodePlans.put(key, p)
      p
    }
  }

  private def buildPlan(
      id: NodeId,
      c: LayoutNode.Component,
      states: Map[String, EntityState]
  ): NodePlan = {
    val slots = c.slots
    // The card's subject entity: the `entity_id` slot resolved against its
    // OWN entity (it DEFINES the subject, so it never inherits it). Normally
    // a literal — a constant the plan holds. A transform form (indirection)
    // grounds on its own entityId and CAN change per paint; that one shape is
    // the only thing a plan cannot precompute, and [[resolveDirect]] owns the
    // whole node then. `Some(subject)` below means "constant, and here it is".
    val subjectConst: Option[Option[String]] =
      slots.get(Dashboard.SubjectSlot) match {
        case None                           => Some(None)
        case Some(s) if s.literal.isDefined => Some(s.literal)
        case Some(_)                        => None
      }
    val constB = Map.newBuilder[String, String]
    val dynB = List.newBuilder[(String, Option[String], SlotSource)]
    val dynInhB = List.newBuilder[(String, SlotSource)]
    slots.foreach { case (slot, source) =>
      source.literal match {
        // A constant literal: used verbatim, reading no entity and running no
        // transform — the cheap path for a hardcoded label/action.
        case Some(text) => constB += ((slot, text))
        case None       =>
          subjectConst match {
            case Some(subject) =>
              // A slot's entity is its own `entityId`, or the subject when it
              // leaves it unset (slot-level inheritance — the card's entity, or
              // the matched entity in a set clause). The `entity_id` slot
              // itself never inherits — it is the subject.
              val srcEntity =
                if (slot == Dashboard.SubjectSlot) source.entityId
                else source.entityId.orElse(subject)
              // `once` is identity-derived — its transform reads only
              // `$domain`/`$entity_id` (a service action, the slider's domain
              // config), both immutable for the life of the entity. So its
              // value never changes and the PLAN holds it; the shared memo is
              // seeded exactly as the first paint seeded it, so the two paths
              // agree and cross-pollinate. `$entity_id` is in the key (the
              // action URL embeds it), so two entities never collide.
              //
              // `live` and `onRender` both re-resolve; they differ in whether
              // the entity is SUBSCRIBED, which is `liveEntities`' business,
              // not this one. That is why the memo asks only about `once`.
              if (source.reads == Reads.Once)
                constB += ((
                  slot,
                  identityCache.computeIfAbsent(
                    (srcEntity.getOrElse(""), source.valueKey),
                    _ => resolveSlot(srcEntity, source, states)
                  )
                ))
              else dynB += ((slot, srcEntity, source))
            // A dynamic subject makes every inheritance chain a per-paint
            // question; [[resolveDirect]] runs the node and the plan holds
            // nothing per-slot (and seeds no memo under a key that could be
            // wrong).
            case None => dynInhB += ((slot, source))
          }
      }
    }
    // A signal slot contributes one extra var — the binding attribute the card
    // places — and, in the patch form, withholds its value. The binding is
    // present in BOTH forms: it is what the seeded signal feeds, and a morph
    // that dropped it would leave the element inert. A signal slot is always
    // non-literal and `live` ([[Renderer.signalBind]]), so its NAME is
    // constant per node and only its VALUE is per-paint.
    val named = subjectConst match {
      case Some(subject) =>
        slots.toList.flatMap { case (slot, src) =>
          Renderer.signalBind(src).map { kind =>
            (
              slot,
              kind,
              Renderer.signalName(
                id,
                slot,
                src.entityId.orElse(subject),
                src.valueKey,
                kind
              )
            )
          }
        }
      case None => Nil
    }
    NodePlan(
      node = c,
      tpl = templateOf(c.card),
      structural = structuralVars(id),
      constants = constB.result(),
      dynamic = dynB.result(),
      bindings = named.flatMap { case (slot, kind, signal) =>
        List(
          s"${slot}__bind" -> Datastar.binding(signal, kind),
          // The bare NAME, for the one thing a canned attribute cannot cover:
          // a card composing the signal into an expression of its own (the
          // slider's action URL reads its bound position). Not a binding, so
          // it does not compromise the plain form — but a card that uses it is
          // relying on a signal existing, which a plain-form client has not
          // got.
          s"${slot}__signal" -> signal
        )
      }.toMap,
      signalSlots = named.map(_._1),
      signalNameBySlot = named.map { case (slot, _, signal) =>
        slot -> signal
      }.toMap,
      signalSeed = Datastar.seedFor(named.map(_._3)),
      subjectDynamic = subjectConst.isEmpty,
      ownRendering = hasOwnRendering(id),
      declaresSignals = slots.values.exists(Renderer.isSignalSlot),
      wrapped = !noWrapCards(c.card),
      inline = templates.inlineRegions.getOrElse(c.card, Set.empty),
      // The subtree floor once, not per paint: `tracedHtml` sizes its buffer
      // with it, and recomputing it per paint walked the whole subtree per
      // node — an O(subtree) iterator churn on every node of every paint.
      nodeCount = 1 + c.regions.values.view.map(_.map(nodeCount).sum).sum
    )
  }

  /** One paint's resolution from the plan: the constants, the per-paint entity
    * values, the bake index, and the signal values captured as they resolve — a
    * signal slot is always a `live` slot ([[Renderer.signalBind]]), so its
    * value is in hand exactly once and no read-back lookup is needed.
    * `bakeIndex` is the one structural input a paint can change (the client's
    * selection), so it rides the builder rather than the plan.
    */
  private def resolvePlanned(
      plan: NodePlan,
      states: Map[String, EntityState],
      bakeIndex: Map[String, String]
  ): Resolved = {
    if (plan.subjectDynamic) resolveDirect(plan, states, bakeIndex)
    else resolvePlannedSubject(plan, states, bakeIndex)
  }

  /** [[resolvePlanned]] for the constant-subject case, which is every card but
    * the indirection one.
    */
  private def resolvePlannedSubject(
      plan: NodePlan,
      states: Map[String, EntityState],
      bakeIndex: Map[String, String]
  ): Resolved = {
    // The only map a paint builds: the LIVE slot values. A card without any
    // (the common static case) builds nothing at all — the constants, the
    // structural vars and the binding strings ride the plan by reference, and
    // the name the template asks for is answered from whichever layer holds
    // it ([[NodeContext.fhGet]]). The old assembly built one merged map per
    // node per paint to say the same thing.
    val paintB = Map.newBuilder[String, String]
    paintB.sizeHint(plan.dynamic.size)
    val signalB =
      if (plan.signalNameBySlot.isEmpty) None
      else Some(Map.newBuilder[SignalId, String])
    plan.dynamic.foreach { case (slot, srcEntity, source) =>
      val value = resolveSlot(srcEntity, source, states)
      paintB += ((slot, value))
      plan.signalNameBySlot
        .get(slot)
        .foreach(sig => signalB.foreach(_ += ((sig, value))))
    }
    Resolved(
      plan.structural,
      plan.constants,
      plan.bindings,
      paint = paintB.result(),
      bake = bakeIndex,
      liveBindings = Map.empty,
      plan.signalSlots,
      signalB.fold(Map.empty[SignalId, String])(_.result())
    )
  }

  /** The straight per-paint resolution, kept for the one shape a plan cannot
    * precompute: a card whose `entity_id` slot is itself a transform, so the
    * subject — and with it every slot's inherited entity, every signal name and
    * binding — can change per paint. Everything else goes through
    * [[resolvePlanned]].
    */
  private def resolveDirect(
      plan: NodePlan,
      states: Map[String, EntityState],
      bakeIndex: Map[String, String]
  ): Resolved = {
    val injected = plan.structural ++ bakeIndex
    val slots = plan.node.slots
    val subject: Option[String] =
      slots.get(Dashboard.SubjectSlot).map { s =>
        s.literal.getOrElse(resolveSlot(s.entityId, s, states))
      }
    val resolved = slots.map { case (slot, source) =>
      val value = source.literal match {
        case Some(text) => text
        case None       =>
          val srcEntity =
            if (slot == Dashboard.SubjectSlot) source.entityId
            else source.entityId.orElse(subject)
          if (source.reads == Reads.Once)
            identityCache.computeIfAbsent(
              (srcEntity.getOrElse(""), source.valueKey),
              _ => resolveSlot(srcEntity, source, states)
            )
          else resolveSlot(srcEntity, source, states)
      }
      slot -> value
    }
    val signalled = slots.toList.flatMap { case (slot, src) =>
      Renderer.signalBind(src).map((slot, src, _))
    }
    val id = NodeId.derived(injected.getOrElse("id", ""))
    val named = signalled.map { case (slot, src, kind) =>
      (
        slot,
        kind,
        Renderer.signalName(
          id,
          slot,
          src.entityId.orElse(subject),
          src.valueKey,
          kind
        )
      )
    }
    val bindings = named.flatMap { case (slot, kind, signal) =>
      List(
        s"${slot}__bind" -> Datastar.binding(signal, kind),
        s"${slot}__signal" -> signal
      )
    }
    // Same layers as the planned path ([[NodeContext.fhGet]] resolves the
    // chain) — but here the slot map IS the paint layer (every slot resolves
    // per paint: the subject can move) and the binding strings are per-paint
    // too, so they ride `liveBindings` instead of the plan.
    Resolved(
      structural = plan.structural,
      constants = plan.constants,
      bindings = plan.bindings,
      paint = resolved,
      bake = bakeIndex,
      liveBindings = bindings.toMap,
      named.map(_._1),
      named.map { case (slot, _, signal) => signal -> resolved(slot) }.toMap
    )
  }

  /** Render one card INTO the caller's buffer.
    *
    * Into, rather than returning a `String`, because the caller is about to
    * wrap this in a `.fh-cell` and that wrapper used to be a second
    * interpolation — which copied the node's whole rendering again, and a
    * node's rendering contains its entire subtree. One copy per node per level
    * of nesting, for a wrapper of about forty bytes (issue #237).
    *
    * Not mustache.java's own `execute(ctx)` for the same reason: that allocates
    * a `StringWriter` over a `StringBuffer` of default capacity 16, so a
    * ~400-byte fragment grew its array five times, under a lock nothing shared.
    */
  private def executeInto(
      out: Sink,
      tpl: Mustache,
      r: Resolved,
      childrenHtml: Map[String, List[String]],
      form: SlotForm,
      // Region name -> write that region's children into `out` at the hole.
      // Set only where the caller can produce a region's bytes inline — the
      // document walk for a node's own regions, [[memberBodyInto]] for a
      // member's; the region codes fall back to the string splice when absent.
      regionWalk: Map[String, java.io.Writer => Unit] = Map.empty
  ): Unit =
    Templates.run(
      tpl,
      out,
      NodeContext(r, childrenHtml, form, regionWalk)
    )

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
      .map(m => memberSignalsOf(resolveMember(m, states)))
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
        .get(Dashboard.SubjectSlot)
        .map(s => s.literal.getOrElse(resolveSlot(s.entityId, s, states)))
      c.slots.collect {
        case (slot, src) if Renderer.isSignalSlot(src) =>
          val entity = src.entityId.orElse(subject)
          val kind = Renderer.signalBind(src).getOrElse(SignalBind.Text)
          Renderer.signalName(id, slot, entity, src.valueKey, kind) ->
            resolveSlot(entity, src, states)
      }
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
      // ONE wire fact, two forms: the Simple object never touches the engine,
      // the CEL string never leaves it — the form IS the tier (ADR 0028).
      val out = source.transform match {
        case sm: Transform.Simple => transforms.run(sm, st)
        case t: String            => transforms.run(t, st, dashboard.slug)
      }
      if (out.nonEmpty) out else source.default.getOrElse("")
    }
  }
}

object Renderer {

  /** The chrome template's scope: nothing to resolve, two holes to write.
    *
    * A page has no slots, no signals and no regions of its own — the body and
    * the restored dialog are the whole of it — so this answers every NAME with
    * `null` (which the factory's `defaultValue("")` turns into the empty
    * string, the same as an absent map key) and offers the two writers instead.
    */
  private final class PageScope(holes: Map[String, java.io.Writer => Unit])
      extends Templates.FhScope {
    def fhGet(name: String): AnyRef = null
    def regionWalk: Map[String, java.io.Writer => Unit] = Map.empty
    override def writerHoles: Map[String, java.io.Writer => Unit] = holes
  }

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
    * Keyed by WHAT IT READS — `(entity, transform)` — not by who shows it, so
    * one entity on three cards is one signal and one frame entry rather than
    * three equal-by-construction copies (issue #134). That is the same key
    * [[identityCache]] already uses for non-reactive slots.
    *
    * The path is `_e.<domain>.<object_id>.<transform>` and is read back as
    * `$_e.light.taklys.state`, which the pinned bundle rewrites to
    * `$['_e']['light']['taklys']['state']`. Segments are bracket-indexed so
    * they need not be JS identifiers, but the reference REGEX
    * (`\$([a-zA-Z_\d]\w*(?:[.-]\w+)*)`) requires `\w+` — and HA slugifies both
    * halves of an entity id, so every real one fits with no escaping. An id
    * that does not is hashed rather than spliced, because a stray character
    * would not fail: it would silently parse as a different path.
    */
  def signalName(
      id: NodeId,
      slot: String,
      entity: Option[String],
      transform: String,
      kind: SignalBind
  ): SignalId = kind match {
    // A two-way binding is INTERACTION state, not an entity value: the input
    // writes it back on every keystroke or drag. Sharing it by `(entity,
    // transform)` would let one card's drag drive another card's readout —
    // which is the confusion ADR 0025 separated `_<id>__slide` out to avoid.
    // So it stays scoped to the node that owns the control.
    case SignalBind.Bind => SignalId.derived(s"_${id}__$slot")
    case _               => displayPath(entity, transform)
  }

  /** [[signalName]]'s display half, memoised on exactly what the path is
    * derived from.
    *
    * Deriving it is pure but not free — an uncommon transform hashes, and
    * `MessageDigest.getInstance` is a fresh provider lookup each time — and it
    * runs once per signal slot per node on EVERY render, first paint and live
    * patch alike. The distinct pairs are the dashboard's authored ones, so the
    * memo is small and warms in one render.
    *
    * Static rather than per-Renderer (unlike [[identityCache]]) because the
    * derivation reads nothing from a dashboard. A hot-reload therefore leaves
    * entries behind, which is harmless twice over: they are a handful of short
    * strings, and the value is a pure function of the key that found it.
    */
  private def displayPath(entity: Option[String], transform: String): SignalId =
    displayPaths.computeIfAbsent(
      (entity, transform),
      key =>
        SignalId.derived(
          s"_e.${entitySegments(key._1)}.${transformSegment(key._2)}"
        )
    )

  private val displayPaths =
    new java.util.concurrent.ConcurrentHashMap[
      (Option[String], String),
      SignalId
    ]()

  private def isWord(s: String): Boolean =
    s.nonEmpty && s.forall(c => c.isLetterOrDigit || c == '_')

  /** `light.taklys` -> `light.taklys`, as two path segments. Anything that is
    * not two `\w+` halves collapses to one hashed segment.
    */
  private def entitySegments(entity: Option[String]): String = entity match {
    case Some(id) =>
      id.split('.') match {
        case Array(domain, obj) if isWord(domain) && isWord(obj) => id
        case _                                                   =>
          s"x${shortHash(id)}"
      }
    // A live slot naming no entity and inheriting no subject reads an empty
    // state, so its value depends on the transform alone and every such slot
    // in the dashboard genuinely shares one.
    case None => "_x"
  }

  /** The transform, as ONE `\w+` segment. Readable for the two shapes that
    * cover most slots, hashed for a computed expression (the slider's
    * `percentExpr`). The slot NAME cannot be used here: two cards naming one
    * transform differently would stop sharing, and one name over two transforms
    * would collide — either way the deduplication is lost.
    *
    * The three forms have disjoint prefixes, so the mapping stays injective.
    */
  private def transformSegment(transform: String): String = transform match {
    case "state" => "state"
    case other   => s"t${shortHash(other)}"
  }

  private def shortHash(s: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(4)
      .map(b => f"${b & 0xff}%02x")
      .mkString

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

  /** A rough starting size for one node's rendered bytes. Only a hint: too
    * small costs a copy, too large wastes a little, and neither is a bug. A
    * shipped leaf card lands around 1-2 kB with its wrapper and seed, so 512
    * made every leaf grow twice; a container is sized past this from its
    * children's real bytes.
    */
  private[runtime] val NodeBytesHint = 1024

}
