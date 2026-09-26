package fh.view.runtime

import com.github.mustachejava.Mustache
import fh.view.build.LibPackage
import fh.view.query.{Queries, QuerySnapshot, QueryRequest}
import fh.view.model.{
  Access,
  Cell,
  ChromeColors,
  Dashboard,
  DomId,
  LayoutNode,
  NodeId,
  Reads,
  SlotAsk,
  SlotQuery,
  SlotRead,
  SlotShape,
  SetId,
  Transform,
  SignalBind,
  SignalId,
  SlotSource,
  SlotValue,
  Surface
}
import scala.jdk.CollectionConverters.*

/** One node as a wholesale render left it: the digest of its patch-form bytes
  * (comparable with what a pull renders) and the signals its seed set.
  */
private[runtime] case class Painted(
    // The digest, not the bytes: holding them kept 99 kB of a 128 kB page a
    // second time. [[Traced.rootOwn]] carries the root's real bytes.
    digest: Digest,
    signals: Map[SignalId, SlotValue]
)

/** A host's parts and what each node inside them now holds. Built here because
  * the two shapes claim different ids: set members are separate renders, so
  * each part is hashed; a state group's branch is one walk under a root with no
  * rendering of its own, so it claims the walk's per-node digests
  * ([[Renderer.Traced]]).
  */
private[runtime] case class HostContent(
    parts: List[(NodeId, String)],
    claims: Map[NodeId, Held]
)

/** A signal slot's two renderings (ADR 0017). `Document` has the value inline
  * plus its `data-signals` seed, so a first paint or fill needs no frame.
  * `Patch` has neither, so a node's digest does not move when only a signal
  * slot does and one signal frame carries the value — the point of the feature.
  * Without a signal slot both forms are the same `String`, which
  * [[Renderer.Traced]] relies on.
  */
private[runtime] enum SlotForm derives CanEqual {
  case Document, Patch

  def isPatch: Boolean = this == SlotForm.Patch
}

/** Per render, never cached on the renderer ([[Renderer.varEnv]]). */
type VarEnv = Map[NodeId, Map[String, String]]

/** What a node's own rendering reads ([[Renderer.renderInputs]]). Too
  * discriminating costs a wasted render; too coarse serves stale bytes silently
  * and forever. When in doubt, over-discriminate.
  */
case class RenderInputs(
    entities: Map[String, Long],
    queries: Map[SlotRead, Long] = Map.empty
) derives CanEqual {

  /** The partial order [[RenderCache]] uses to refuse superseded bytes.
    * Different key sets are unordered, which also keeps two viewers on
    * different windows from displacing each other's chart.
    */
  def isAtLeast(other: RenderInputs): Boolean =
    sameOrAhead(entities, other.entities) &&
      sameOrAhead(queries, other.queries)

  private def sameOrAhead[K](
      mine: Map[K, Long],
      theirs: Map[K, Long]
  ): Boolean =
    mine.sizeIs == theirs.size &&
      theirs.forall((k, v) => mine.get(k).exists(_ >= v))
}

/** Container kinds are templates splicing `children`, not cases here. A surface
  * is a separate tree whose ids are namespaced (`s_<id>__…`).
  */
class Renderer(
    dashboard: Dashboard,
    templates: Templates,
    transforms: Transforms,
    // Here, not beside the renderer, so a reload swaps rule and dashboard as
    // one value. The default is the restrictive one.
    val access: Access = Access.default,
    private val parsedQueries: Map[SlotRead, QueryRequest] = Map.empty
) {

  private class Index(root: LayoutNode, val idPrefix: String) {

    // Recorded by the walk that mints the ids, so the two cannot disagree.
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
          // A leaf here: its members are addressed by `memberId`, not a path.
          case _: LayoutNode.SetNode => List(self)
        }
      }
      walk(root, LayoutNode.rootId(idPrefix, root)).toMap
    }

    // So a render's reads cost the charts, not the tree.
    val asks: List[(NodeId, List[SlotAsk])] =
      indexed.toList.sortBy(_._1).collect {
        case (id, c: LayoutNode.Component) if c.queries.nonEmpty =>
          id -> c.queries
      }
    val setReads: List[SlotRead] =
      indexed.values.toList.flatMap {
        case s: LayoutNode.SetNode => dashboard.queriesIn(s)
        case _                     => Nil
      }.distinct

    /** Node variables in scope at each node, declared values only (issue #209).
      * A set is a leaf, which is why `validate` refuses a variable read inside
      * one.
      */
    val varScopes: Map[NodeId, Map[String, Renderer.InScope]] = {
      def walk(
          node: LayoutNode,
          id: NodeId,
          scope: Map[String, Renderer.InScope]
      ): List[(NodeId, Map[String, Renderer.InScope])] = node match {
        case c: LayoutNode.Component =>
          // A nested declaration replacing the entry is shadowing.
          val here = scope ++ c.vars.map { case (n, v) =>
            n -> Renderer.InScope(id, v)
          }
          (id -> here) :: LayoutNode.steps(c.regions).flatMap {
            case (step, ch) =>
              walk(ch, LayoutNode.childId(idPrefix, id, step, ch), here)
          }
        case _: LayoutNode.SetNode => List(id -> scope)
      }
      walk(root, LayoutNode.rootId(idPrefix, root), Map.empty)
        .filter(_._2.nonEmpty)
        .toMap
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

  /** The head's unpatchable part — linked stylesheets, scripts, `chrome` — so a
    * mismatch reloads the page (ADR 0011); the patchable rest is [[styleHash]].
    * Not the whole dashboard: a body edit would reload for nothing. Stable
    * across restarts, so an add-on restart does not refresh every browser —
    * which is why it cannot double as `logId`.
    */
  val headHash: String = Renderer.headFingerprint(dashboard)

  /** Tokens, inline CSS ([[themeStyleTag]]) and `<title>`: a mismatch costs two
    * element patches, not a reload.
    */
  val styleHash: String = Renderer.styleFingerprint(dashboard)

  val warnings: List[String] = dashboard.warnings

  // Their root must stay a direct child of a structural parent (tab anchors).
  private val noWrapCards: Set[String] =
    dashboard.cards.collect { case (name, cd) if !cd.wrapAsCell => name }.toSet

  /** `reactive: false` slot values by `(entityId, transform)`: they read only
    * identity, so they never change for this renderer's life. A racing fill
    * computes the same value.
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

  private val varScopes: Map[NodeId, Map[String, Renderer.InScope]] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap(_.varScopes).toMap

  /** Never cached on the renderer or a `NodePlan`: both outlive a session, so a
    * choice held there would be served to the next viewer.
    */
  def varEnv(choices: Map[(NodeId, String), String]): VarEnv =
    if (varScopes.isEmpty) Map.empty
    else
      varScopes.view.mapValues { scope =>
        scope.view.map { case (name, in) =>
          name -> choices.getOrElse((in.declarer, name), in.declared)
        }.toMap
      }.toMap

  val declarations: Map[(NodeId, String), String] =
    varScopes.values.flatten.map { case (name, in) =>
      (in.declarer, name) -> in.declared
    }.toMap

  /** Exact: a write both validates against every reader and re-renders them. */
  def readersOf(declarer: NodeId, name: String): List[NodeId] =
    varScopes.toList.collect {
      case (id, scope)
          if scope.get(name).exists(_.declarer == declarer) &&
            queriesForNode(id).exists(_.query.references.contains(name)) =>
        id
    }

  /** One line per refused choice. Every reader must still parse and read only
    * an entity this dashboard shows — the read-side twin of an action's bound
    * (ADR 0023), without which a variable fed to `entity` charts any sensor.
    */
  def refusals(choices: Map[(NodeId, String), String]): List[String] = {
    val env = varEnv(choices)
    choices.toList.flatMap { case ((declarer, name), value) =>
      val why = readersOf(declarer, name)
        .flatMap(readsAt(_, env))
        .flatMap(r => refusal(r.query))
        .distinct
      Option.when(why.nonEmpty)(
        s"'$value' is not a value '$name' can take: ${why.mkString("; ")}"
      )
    }
  }

  private def refusal(query: SlotQuery): Option[String] =
    Queries.parse(query) match {
      case Left(e)    => Some(e)
      case Right(req) =>
        req.entities
          .find(e => !references(e) && !dashboard.queriedEntities(e))
          .map(e => s"$e is not on this dashboard")
    }

  /** A target has its own rendering ([[hasOwnRendering]]), so its descendants
    * are not asked.
    */
  def readsForPull(
      targets: List[NodeId],
      hosts: List[NodeId],
      states: Map[String, EntityState],
      uiState: Map[String, String],
      env: VarEnv
  ): List[SlotRead] =
    (targets.flatMap(id => readsAt(id, env) ++ setReadsAbove(id)) ++
      hosts.flatMap { gid =>
        members.setContainer(gid) match {
          case Some(_) =>
            querySetReads.getOrElse(gid, Nil) ++ setReadsAbove(gid)
          case None =>
            surfaces
              .resolveActiveByState(gid, states)
              .flatMap(surfaces.bakeGroup(gid).lift)
              .toList
              .flatMap(queriesForSurface(_, states, uiState, env))
        }
      }).distinct

  // A member is not indexed, so what it reads is its set's.
  private def setReadsAbove(id: NodeId): List[SlotRead] =
    if (querySetReads.isEmpty) Nil
    else
      ancestry.ancestorsOf(id).toList.flatMap(querySetReads.getOrElse(_, Nil))

  private lazy val querySetReads: Map[NodeId, List[SlotRead]] =
    allIndexed.collect {
      case (id, (s: LayoutNode.SetNode, _))
          if dashboard.queriesIn(s).nonEmpty =>
        id -> dashboard.queriesIn(s)
    }

  def readsAt(id: NodeId, env: VarEnv): List[SlotRead] =
    queriesForNode(id).map(_.resolve(env.getOrElse(id, Map.empty)))

  private val prefixToRoot: Map[String, String] =
    Map(mainIndex.idPrefix -> "") ++
      surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }

  private val rootOfIndexed: Map[NodeId, String] =
    allIndexed.view.mapValues { case (_, prefix) =>
      prefixToRoot(prefix)
    }.toMap

  /** Presence and order in every candidate set. Exposed rather than exported,
    * so a call site says `renderer.members.…` and never reads as though the
    * renderer decided membership.
    */
  private[runtime] val members: MemberGraph = new MemberGraph(
    allIndexed.collect { case (id, (s: LayoutNode.SetNode, _)) => id -> s },
    rootOfIndexed
  )

  /** From the id-minting walks plus the member graph's edges, not from how ids
    * are spelled ([[NodeAncestry]]).
    */
  private[runtime] val ancestry: NodeAncestry =
    NodeAncestry.fromParents(
      (mainIndex :: surfaceIndexes.values.toList)
        .flatMap(_.parents)
        .toMap ++ members.parentEdges
    )

  /** Selection and visibility, exposed on the same terms as [[members]]. */
  private[runtime] val surfaces: SurfaceGraph =
    new SurfaceGraph(dashboard.surfaces, rootOfIndexed, members)

  /** Members included, so a case slot naming a second entity ticks: that entity
    * need not match the set's query.
    */
  def componentsFor(entityId: String): Set[NodeId] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty) ++
      members.membersBinding(entityId, "")

  /** Empty for a candidate set (its members have ids of their own). */
  def entitiesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _)) => c.liveEntities
      case _                                  => members.liveEntitiesOf(id)
    }

  /** Minus entities reached only through signal slots: those cannot change the
    * bytes ([[renderInputs]]).
    */
  private def entitiesAsBytesForNode(id: NodeId): List[String] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _)) => c.liveEntitiesAsBytes
      case _ => members.liveEntitiesAsBytesOf(id)
    }

  // Members are not indexed; what they read is their set's ([[setReadsAbove]]).
  private def queriesForNode(id: NodeId): List[SlotAsk] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _)) =>
        c.queries
      case _ => Nil
    }

  /** The bound an action is held to (ADR 0023). The model's static walk, not
    * [[Index.byEntity]], which stops at a set: this must answer for entities
    * nothing renders yet.
    */
  def references(entityId: String): Boolean =
    dashboard.referencedEntities.contains(entityId)

  /** Wider than [[references]] — see [[Dashboard.watchedEntities]]. */
  def watchedEntities: Set[String] = dashboard.watchedEntities

  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[NodeId] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty)) ++
      members.membersBinding(entityId, surfaceId)

  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  def stylesheets: List[String] = dashboard.theme.stylesheets

  def deferredStylesheets: List[String] = dashboard.theme.deferredStylesheets

  val themeColorTags: String = Renderer.themeColorTags(dashboard)

  val chromeColors: Option[ChromeColors] = ChromeColors.from(dashboard.theme)

  def scripts: List[String] = dashboard.theme.scripts

  def inlineScripts: List[String] = dashboard.theme.inlineScripts

  def title: Option[String] = dashboard.title

  /** Tokens, then `dashboard.css`, card css, theme styles: document order is
    * the cascade (ADR 0020). Outside `#dashboard` because it is most of a
    * repaint's bytes (7.7 of 9.6 KB on a small demo); it is patched by id only
    * when [[styleHash]] moves.
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

    // Always the element, even empty: a later head patch needs a target.
    parts.mkString(
      s"""<style id="${Renderer.ThemeStyleId}">""",
      "",
      "</style>"
    )
  }

  private val chromeTemplate: Mustache = {
    val chrome =
      if (dashboard.theme.chrome.nonEmpty) dashboard.theme.chrome
      else """<main class="container" id="dashboard">{{{body}}}</main>"""
    Templates.compile("chrome", chrome)._1
  }

  /** What a repaint `inner`-patches into `#dashboard`, with the per-node trace
    * of everything baked into it.
    */
  private[runtime] def renderBodyTraced(
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      fragments: QuerySnapshot
  ): Traced =
    traced(
      dashboard.card,
      LayoutNode.rootId("", dashboard.card),
      "",
      states,
      uiState,
      fragments
    )

  private[runtime] def pageBytesHint: Int =
    themeStyleTag.length + Renderer.NodeBytesHint * nodeCount(dashboard.card) +
      4096

  /** The document path: the page walked straight into `out`, returning the
    * per-node trace. A restored popup is baked into the chrome's `popups` hole,
    * so a refresh does not paint the dialog late.
    */
  private[runtime] def renderPageInto(
      out: Sink,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      popup: Option[String] = None,
      fragments: QuerySnapshot
  ): Map[NodeId, Painted] = {
    val own = new java.util.HashMap[NodeId, Painted]()
    // Writer holes, not Strings: building them first cost a full copy of the
    // document each (128 kB of a page open's ~3.8 MB).
    val root = dashboard.card
    val bodyInto: java.io.Writer => Unit = _ =>
      tracedInto(
        out,
        root,
        LayoutNode.rootId("", root),
        "",
        states,
        uiState,
        fragments,
        own
      )
    val dialogInto: Option[java.io.Writer => Unit] =
      popup.flatMap(sid =>
        surfaceWalk(out, sid, states, uiState, fragments, own)
      )
    // Not mustache's `execute(ctx)`, whose `StringWriter` grows from 16 chars
    // and copies the page twice more.
    val _ = out.append(themeStyleTag)
    val scope = new Renderer.PageScope(
      Map("body" -> bodyInto) ++ dialogInto.map("popups" -> _)
    )
    Templates.run(chromeTemplate, out, scope)
    own.asScala.toMap
  }

  /** [[renderSurfaceTraced]] as a writer hole into the page's buffer. */
  private def surfaceWalk(
      out: Sink,
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot,
      trace: java.util.HashMap[NodeId, Painted]
  ): Option[java.io.Writer => Unit] =
    // The writer is ignored: mustache's writer is `out`.
    dashboard.surfaces.get(surfaceId).map { sfc => (_: java.io.Writer) =>
      val prefix = Renderer.surfacePrefix(surfaceId)
      tracedInto(
        out,
        sfc.content,
        LayoutNode.rootId(prefix, sfc.content),
        prefix,
        states,
        uiState,
        fragments,
        trace
      )
    }

  /** Bare content: the host and any frame live in `theme.chrome`. */
  private[runtime] def renderSurfaceTraced(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      fragments: QuerySnapshot
  ): Option[Traced] =
    dashboard.surfaces.get(surfaceId).map { s =>
      traced(
        s.content,
        LayoutNode.rootId(Renderer.surfacePrefix(surfaceId), s.content),
        Renderer.surfacePrefix(surfaceId),
        states,
        uiState,
        fragments
      )
    }

  /** Every log key must resolve here: the log holds digests, and a resume
    * re-renders. `None` means the key names nothing that exists now, and is
    * dropped rather than failing the resume.
    */
  def renderNodeById(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String] = Map.empty,
      form: SlotForm = SlotForm.Patch,
      fragments: QuerySnapshot
  ): Option[String] =
    members
      .memberAt(id, states)
      .map(renderMember(_, states, form, fragments))
      .orElse(renderIndexed(id, states, uiState, form, fragments))

  private def renderIndexed(
      id: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      form: SlotForm,
      fragments: QuerySnapshot
  ): Option[String] =
    allIndexed
      .get(id)
      .filter(_ => hasOwnRendering(id))
      .flatMap { case (node, prefix) =>
        render(node, id, prefix, states, uiState, form, fragments)
      }

  /** `s_<sid>__c`, what a state group's host holds; the same scheme the
    * build-phase hoist uses.
    */
  def surfaceContentId(surfaceId: String): NodeId =
    LayoutNode.nodeId(Renderer.surfacePrefix(surfaceId), Nil)

  def queryRequests: Map[SlotRead, QueryRequest] = parsedQueries

  private def readsIn(idx: Index, env: VarEnv): List[SlotRead] =
    (idx.asks.flatMap { case (id, asks) =>
      asks.map(_.resolve(env.getOrElse(id, Map.empty)))
    } ++ idx.setReads).distinct

  /** The surfaces shown inside it included. */
  def queriesForSurface(
      surfaceId: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      env: VarEnv
  ): List[SlotRead] =
    surfaces
      .shownWithin(surfaceId, states, uiState)
      .toList
      .sorted
      .flatMap(readsOfSurface(_, env))
      .distinct

  private def readsOfSurface(surfaceId: String, env: VarEnv): List[SlotRead] =
    surfaceIndexes.get(surfaceId).toList.flatMap(readsIn(_, env))

  /** The body, this viewer's open surfaces and each state group's branch. An
    * unselected tab is fetched when switched to.
    */
  def queriesForPage(
      open: Set[String],
      states: Map[String, EntityState],
      env: VarEnv
  ): List[SlotRead] = {
    val shown = open ++ surfaces.activeStateSurfaces(states) ++
      open.flatMap(surfaces.activeStateSurfacesIn(_, states))
    (readsIn(mainIndex, env) ++
      shown.toList.sorted.flatMap(readsOfSurface(_, env))).distinct
  }

  /** The resume's second candidate set: nothing may have recorded an open
    * surface while nobody viewed it, so only re-rendering can tell.
    */
  def surfaceNodeIds(surfaceId: String): Set[NodeId] =
    surfaceIndexes.get(surfaceId).fold(Set.empty)(_.indexed.keySet)

  /** In DOM order, paired because a fill owes the record a digest per member.
    */
  def renderMembers(
      groupId: SetId,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
  ): List[(NodeId, String)] =
    members
      .membersOf(groupId, states)
      .toList
      .map(m => m.id -> renderMember(m, states, SlotForm.Document, fragments))

  /** A fill of either container: a set's members or a state group's branch. */
  private[runtime] def renderHost(
      container: NodeId,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot
  ): HostContent =
    members.setContainer(container) match {
      case Some(setId) =>
        val parts = renderMembers(setId, states, fragments)
        HostContent(
          parts,
          parts.map { case (id, html) => id -> Held.of(html) }.toMap
        )
      case None =>
        surfaces
          .resolveActiveByState(container, states)
          .flatMap(surfaces.bakeGroup(container).lift)
          .flatMap(sid =>
            renderSurfaceTraced(sid, states, uiState, fragments).map(t =>
              HostContent(List(surfaceContentId(sid) -> t.html), t.claims)
            )
          )
          .getOrElse(HostContent(Nil, Map.empty))
    }

  /** One derivation for both id sources (the static tree and set members), so a
    * var added here reaches both: structural vars are a pure function of the
    * node id. `bakeIndex` depends on a selection, so it is not here
    * ([[resolveBakeTraced]]).
    */
  private def structuralVars(id: NodeId): Map[String, String] =
    Map(
      "id" -> id,
      "hostId" -> hostId(id),
      // For a URL built in a template (the slider's commit); a transform
      // reads the same fact as `dashboard_slug`.
      "dashboardSlug" -> dashboard.slug
    )

  /** Whether a node may be a log key or a patch target: decided by its card
    * alone. Structure's element contains its regions, so patching it would
    * re-send them; a set root composes its members. Both keep their
    * [[elementId]] for structural patches. `Dashboard.validate` enforces the
    * same rule when it rejects a live bytes slot on structure.
    */
  private def hasOwnRendering(id: NodeId): Boolean =
    allIndexed.get(id).exists {
      case (c: LayoutNode.Component, _) =>
        !dashboard.cards.get(c.card).exists(_.isStructure)
      case (_: LayoutNode.SetNode, _) => false
    }

  /** The node's `.fh-cell` root: the one crossing from node id to DOM id. */
  def elementId(id: NodeId): DomId = DomId.derived(id)

  /** Where a node's baked region lives: for a bake owner it IS
    * [[fh.view.model.Surface.hostId]], so Pkl and Scala do not derive the same
    * string separately. Other nodes fall back to their own id, which only a
    * candidate set uses — its members are filled into its one implicit hole.
    */
  def hostId(id: NodeId): DomId =
    surfaces
      .bakeGroup(id)
      .headOption
      .flatMap(dashboard.surfaces.get)
      .map(_.hostId)
      .getOrElse(elementId(id))

  /** `(bakeIndex vars, (bakeAs, surface))` for a bake owner. The surface is
    * rendered lazily, when the template reaches the hole.
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
        // A state group with no matching branch: the hole renders empty.
        case None => (Map.empty, None)
      }
  }

  /** `None` for a node owning no group, or a state group with no branch
    * holding.
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

  /** The render cache's key (ADR 0012): the content version of each entity that
    * can move this node's bytes ([[entitiesAsBytesForNode]]) — not those read
    * only through signal slots, whose values are absent from the patch form
    * (ADR 0017). A version, not the value: `EntityState.hashCode` walks the
    * attributes. A missing entity has no entry, a distinct key.
    *
    * No selection (only structure reads one, and structure is never cached) and
    * no children (a descendant's tick would invalidate every ancestor). `None`
    * means not cacheable: exactly [[hasOwnRendering]]'s `false`.
    */
  def renderInputs(
      id: NodeId,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
  ): Option[RenderInputs] =
    members
      .memberAt(id, states)
      .map(m =>
        RenderInputs(
          // The subject chose the member's case, so it keys the bytes even
          // when no slot reads it.
          versions(
            m.node.subjectEntity.toList ++ m.node.liveEntitiesAsBytes,
            states
          ),
          // A member reads no variable (`validate` refuses one in a set).
          fragments.versions(id, m.node.queries)
        )
      )
      .orElse(
        Option.when(hasOwnRendering(id))(
          RenderInputs(
            versions(entitiesAsBytesForNode(id), states),
            fragments.versions(id, queriesForNode(id))
          )
        )
      )

  /** The render cache's pre-check: equal byte-slot values, equal bytes. The
    * key's `contentVersion` moves on any change, so without this every
    * brightness tick on the shipped `entityCard` re-renders to identical bytes.
    * Byte slots only — resolving the signal ones (where the CEL lives) would
    * give the saving back: 0.28 µs against a 5.45 µs render
    * (`RenderBench.byteSlotResolve` vs `tickRender`).
    *
    * `None` means "cannot answer cheaply", never "unchanged": a member, or a
    * dynamic subject (which decides every slot's entity). A bake owner raises
    * instead: it is structure and never cached, and a quiet `None` here would
    * serve stale bytes.
    */
  private[runtime] def byteSlotValues(
      id: NodeId,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
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
              b += (
                (
                  slot,
                  resolveSlot(srcEntity, source, states, fragments, plan.id)
                )
              )
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

  /** The patch form comes from the trace's root, so what `renderNodeById`
    * returns and what `holds` recorded are one string by construction.
    */
  private def render(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      form: SlotForm,
      fragments: QuerySnapshot
  ): Option[String] = {
    val t = traced(node, id, idPrefix, states, uiState, fragments)
    if (form.isPatch) t.rootOwn else Some(t.html)
  }

  /** The composed rendering and each node's own digest, kept from the one walk
    * so a fill or page load need not re-walk the subtree. `own` holds exactly
    * the nodes with their own rendering, digested from what [[renderNodeById]]
    * returns — which is what makes them comparable.
    */
  private[runtime] case class Traced(
      html: String,
      own: Map[NodeId, Painted],
      // Only [[Renderer.render]] wants real bytes, and only the root's.
      rootOwn: Option[String] = None
  ) {

    def claims: Map[NodeId, Held] =
      own.map { case (id, p) => id -> Held(Some(p.digest), p.signals) }
  }

  // A static floor for sizing a buffer; a set's live membership is bounded by
  // its candidates and members.
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
      uiState: Map[String, String],
      fragments: QuerySnapshot
  ): Traced = {
    val own = new java.util.HashMap[NodeId, Painted]()
    val root = new Array[String](1)
    val html =
      tracedHtml(node, id, idPrefix, states, uiState, fragments, own, root)
    Traced(html, own.asScala.toMap, Option(root(0)))
  }

  /** A subtree's document bytes, its trace into the caller's accumulator. The
    * buffer is presized: growing it is the copy this walk exists to remove.
    */
  private def tracedHtml(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot,
      trace: java.util.HashMap[NodeId, Painted],
      // [[Traced.rootOwn]]; `null` for a page, whose root is structure.
      rootOwn: Array[String] | Null = null
  ): String = {
    val out = Sink.buffer(
      Renderer.NodeBytesHint * (node match {
        case c: LayoutNode.Component => planOf(id, id, c, states).nodeCount
        case _                       => nodeCount(node)
      })
    )
    tracedInto(
      out,
      node,
      id,
      idPrefix,
      states,
      uiState,
      fragments,
      trace,
      rootOwn,
      id
    )
    out.result
  }

  /** A node's document bytes appended to `out`, and every own-rendering node's
    * patch digest into `trace`. An accumulator, not per-level maps merged
    * upward: those allocated per node per paint and re-copied every ancestor's.
    */
  private def tracedInto(
      out: Sink,
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      uiState: Map[String, String],
      fragments: QuerySnapshot,
      trace: java.util.HashMap[NodeId, Painted],
      rootOwn: Array[String] | Null = null,
      rootId: NodeId | Null = null
  ): Unit =
    node match {
      case c: LayoutNode.Component =>
        val (bakeIndex, bakeSel) = resolveBakeTraced(id, uiState, states)
        // Resolved once for both forms; what a paint cannot change comes from
        // the plan ([[NodePlan]]).
        val plan = planOf(id, id, c, states)
        val resolved = resolvePlanned(plan, states, bakeIndex, fragments)
        val tpl = plan.tpl
        // A second, patch-form render only where it can differ: own rendering
        // and a signal slot. Such a node has no regions, so the document
        // children below are all it composes.
        val twoForms = plan.ownRendering && plan.declaresSignals
        // Every node gets the renderer-owned `.fh-cell` wrapper unless its card
        // opts out (`wrapAsCell = false`: the tab anchors, which must stay
        // children of `.tabs`). A bake owner too — the cell is the layout
        // item, and without it `.columns(n)` on `Tabs`/`If` is dropped.
        //
        // The wrapper carries the one `data-signals` seed for the whole node
        // (ADR 0017), document form only: a second `data-signals` on an element
        // is silently dropped by the browser.
        val wrapped = plan.wrapped
        // Regions whose loop body is exactly `{{{html}}}` are traced into this
        // buffer at the hole; any other template splices strings — slower,
        // same bytes.
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
            val _ = buf.append('>')
          }

        def bodyInto(
            buf: Sink,
            form: SlotForm
        ): Unit = {
          // A bake owner with no authored regions (an `If` host) is not a
          // leaf: its hole is filled from the selection.
          if c.regions.isEmpty && bakeSel.isEmpty then
            Templates.run(
              tpl,
              buf,
              NodeContext(resolved, Map.empty, form)
            )
          else {
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
                      fragments,
                      trace
                    )
                  }
              }.toMap
            val bakedHtml: Map[String, List[String]] =
              bakeSel match {
                case Some((region, sid)) if !inline.contains(region) =>
                  renderSurfaceTraced(sid, states, uiState, fragments)
                    .map { t =>
                      t.own.foreach { case (nid, p) => trace.put(nid, p) }
                      Map(region -> List(t.html))
                    }
                    .getOrElse(Map.empty)
                case _ => Map.empty
              }
            // A baked region walks the selected surface under its own ids.
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
                        fragments,
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
                        fragments,
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

        val isRoot = rootOwn != null && id == rootId
        def documentInto(o: Sink): Unit = {
          wrapper(o, SlotForm.Document)
          bodyInto(o, SlotForm.Document)
          if (wrapped) { val _ = o.append("</div>") }
        }
        // One form: the sink digests the document bytes as they pass.
        val inlineDigest: Digest | Null =
          if (plan.ownRendering && !twoForms) {
            val (d, bytes) = out.digesting(isRoot)(documentInto)
            if (isRoot) rootOwn.nn(0) = bytes.nn
            d
          } else {
            documentInto(out)
            null
          }

        // Wrapper included and in patch form, as `renderNodeById` produces.
        val own = Option.when(plan.ownRendering) {
          if (twoForms) {
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
        own.foreach(trace.put(id, _))
      // The members are what a fill fingerprints; the root has no rendering.
      case s: LayoutNode.SetNode =>
        val setId = SetId.of(id, s)
        // Membership evaluated once: `membersOf` re-tests every candidate's
        // `when`.
        val resolved =
          members
            .membersOf(setId, states)
            .map(m => m -> resolveMember(m, states, fragments))
        // Members write into the walk's buffer (issue #237); a member's patch
        // form is rendered separately only when it has a signal slot.
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
        val _ = out.append("</div>")
    }

  private def renderSet(
      id: SetId,
      cell: Option[Cell],
      states: Map[String, EntityState],
      form: SlotForm,
      fragments: QuerySnapshot
  ): String =
    setElement(
      id,
      cell,
      members
        .membersOf(id, states)
        .map(renderMember(_, states, form, fragments))
    )

  private def setElement(
      id: SetId,
      cell: Option[Cell],
      members: Seq[String]
  ): String = {
    // Appends, not an interpolated `mkString`, which copies twice (issue #237).
    val out = Sink.buffer(
      160 + members.foldLeft(0)(_ + _.length)
    )
    val _ = out
      .append("""<div class="fh-cell fh-group""")
      .append(Renderer.cellClasses(cell))
      .append("""" id="""")
      .append(id)
      .append("\">")
    members.foreach(out.append)
    val _ = out.append("</div>")
    out.result
  }

  def renderMemberById(
      setId: SetId,
      entityId: String,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
  ): Option[String] =
    members
      .membersOf(setId, states)
      .find(_.key == MemberKey.Entity(entityId))
      .map(renderMember(_, states, SlotForm.Document, fragments))

  /** Always wrapped: the id'd cell is the member's patch target, so a
    * `wrapAsCell = false` card cannot be a set clause.
    */
  private def renderMember(
      m: Member,
      states: Map[String, EntityState],
      form: SlotForm,
      fragments: QuerySnapshot
  ): String = renderResolvedMember(m, resolveMember(m, states, fragments), form)

  /** A member's tree resolved once — one patch unit — serving its document
    * bytes, patch bytes and seed.
    */
  private case class ResolvedMember(
      cardName: String,
      tpl: Mustache,
      resolved: Resolved,
      regions: Map[String, List[ResolvedChild]]
  )

  /** A member's children are unaddressed; the member is their patch target. */
  private enum ResolvedChild {
    case Node(cell: Option[Cell], node: ResolvedMember)

    /** Always document form: blanking its values would withhold ones no patch
      * of this member restores.
      */
    case NestedSet(html: String)
  }

  private def resolveMember(
      m: Member,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
  ): ResolvedMember = {
    val plan = planOf(m.id, m.id, m.node, states)
    ResolvedMember(
      m.node.card,
      plan.tpl,
      // Children have no ids, so their signals are minted in the member's
      // namespace and seeded on its wrapper.
      resolvePlanned(plan, states, Map.empty, fragments),
      Renderer.perRegion(m.node.regions)((child, step) =>
        resolveChild(m, child, List(step), m.clause, states, fragments)
      )
    )
  }

  private def resolveChild(
      m: Member,
      node: LayoutNode,
      path: List[LayoutNode.Step],
      clauseIdx: Int,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
  ): ResolvedChild = node match {
    case c: LayoutNode.Component =>
      // Keyed by member id plus position: one plan per authored position.
      val plan = planOf(s"${m.id}\u0000${path.mkString("/")}", m.id, c, states)
      ResolvedChild.Node(
        c.cell,
        ResolvedMember(
          c.card,
          plan.tpl,
          resolvePlanned(plan, states, Map.empty, fragments),
          Renderer.perRegion(c.regions)((child, step) =>
            resolveChild(m, child, path :+ step, clauseIdx, states, fragments)
          )
        )
      )
    case inner: LayoutNode.SetNode =>
      ResolvedChild.NestedSet(
        renderSet(
          members.innerSetId(m.id, clauseIdx, path, inner),
          inner.cell,
          states,
          SlotForm.Document,
          fragments
        )
      )
  }

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

  private def renderResolvedMemberInto(
      out: Sink,
      m: Member,
      rm: ResolvedMember,
      form: SlotForm
  ): Unit = {
    // The seed covers the children too ([[memberSignalsOf]]).
    out
      .append("""<div class="fh-cell""")
      .append(Renderer.cellClasses(m.node.cell))
      .append("""" id="""")
      .append(m.id)
      .append('"')
    if (!form.isPatch) {
      // The baked seed: building the attribute per paint was 12.5% of a
      // candidate-set page open (`RenderBench.pageSet`).
      val values = memberSignalsOf(rm)
      Datastar.seedAttrInto(out, memberSeedOf(m, values), values)
    }
    val _ = out.append('>')
    memberBodyInto(out, rm, form)
    val _ = out.append("</div>")
  }

  /** Inline regions as in [[tracedInto]]; the walk collects nothing, since the
    * member is the whole subtree's patch target.
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
            rm.regions(region).foreach(memberChildInto(out, _, form))
          }
      }.toMap
    val childrenHtml: Map[String, List[String]] =
      rm.regions.view
        .filterKeys(!walk.contains(_))
        .mapValues(_.map { child =>
          Sink.scratched { buf =>
            memberChildInto(buf, child, form)
            buf.result
          }
        })
        .toMap
    executeInto(out, rm.tpl, rm.resolved, childrenHtml, form, walk)
  }

  private def memberChildInto(
      out: Sink,
      child: ResolvedChild,
      form: SlotForm
  ): Unit = child match {
    case ResolvedChild.NestedSet(html) => val _ = out.append(html)
    case ResolvedChild.Node(cell, n)   =>
      out
        .append("""<div class="fh-cell""")
        .append(Renderer.cellClasses(cell))
        .append("""">""")
      memberBodyInto(out, n, form)
      val _ = out.append("</div>")
  }

  /** Names are plan-time, only values are per paint. Rechecked by node
    * identity, like [[nodePlans]]: a clause can hand a new node for the same
    * member id. `Datastar.seedAttrInto` falls back if the values do not fit.
    */
  private val memberSeeds =
    new java.util.concurrent.ConcurrentHashMap[
      String,
      (LayoutNode.Component, Datastar.SignalSeed)
    ]()

  private def memberSeedOf(
      m: Member,
      values: Map[SignalId, SlotValue]
  ): Datastar.SignalSeed = {
    val key: String = m.id
    val cached = memberSeeds.get(key)
    if (cached != null && (cached._1 eq m.node)) cached._2
    else {
      val seed = Datastar.seedFor(values.keys)
      memberSeeds.put(key, (m.node, seed))
      seed
    }
  }

  /** Children included (they share the member's patch); a nested set's members
    * own theirs.
    */
  private def memberSignalsOf(rm: ResolvedMember): Map[SignalId, SlotValue] =
    rm.regions.values.flatten.foldLeft(rm.resolved.signals) {
      case (acc, ResolvedChild.Node(_, n))   => acc ++ memberSignalsOf(n)
      case (acc, ResolvedChild.NestedSet(_)) => acc
    }

  private def templateOf(cardName: String): Mustache =
    templates.components.getOrElse(
      cardName,
      throw new IllegalStateException(
        s"unknown card '$cardName' — validate should have rejected this dashboard"
      )
    )

  /** One paint's resolution as layers, not a merged map: the plan's constant
    * layers ride by reference, and [[NodeContext.fhGet]] reads them in
    * precedence order.
    *
    * @param structural
    *   constants from the node's position (ids, inherited entity)
    * @param constants
    *   literal and identity-`once` slot values, plus every slot's `__has`
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
      paint: Map[String, SlotValue],
      bake: Map[String, String],
      liveBindings: Map[String, String],
      signalSlots: List[String],
      signals: Map[SignalId, SlotValue]
  )

  /** The template context, read in place. Not a `java.util.Map`: mustache.java
    * resolves those through `entrySet`, so a get-only map answers every name
    * empty (that cost a probe suite to find). Also where the patch form
    * withholds signal values, instead of a new map per signal slot per node. An
    * unknown name answers `null`, which `defaultValue("")` renders empty.
    */
  private case class NodeContext(
      resolved: Resolved,
      childrenHtml: Map[String, List[String]],
      form: SlotForm,
      // Set by the walks for inline regions; absent, the region codes splice
      // `childrenHtml`.
      regionWalk: Map[String, java.io.Writer => Unit] = Map.empty
  ) extends Templates.FhScope {

    /** A region's children under its own name; an empty one is absent, so the
      * section vanishes.
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
          else {
            // Flat, not nested `getOrElse`s: those defaults were capturing
            // lambdas, 5.7% of a page open (`RenderBench.page`); a constant
            // `null` default is not. No `Option` either, for the same reason.
            //
            // `AnyRef` because `paint` can hold a boxed `Boolean`, which must
            // reach mustache as one: the string "false" is a truthy section.
            var v: AnyRef | Null = resolved.bindings.getOrElse(name, null)
            if (v == null) v = resolved.liveBindings.getOrElse(name, null)
            if (v == null) {
              val p: SlotValue | Null = resolved.paint.getOrElse(name, null)
              if (p != null) v = SlotValue.scoped(p)
            }
            if (v == null) v = resolved.constants.getOrElse(name, null)
            if (v == null) v = resolved.bake.getOrElse(name, null)
            if (v == null) v = resolved.structural.getOrElse(name, null)
            v
          }
      }
  }

  /** What a paint cannot change about one node, derived once per renderer so a
    * paint resolves only live slot values, signal values and the bake
    * selection. Held by node identity and rechecked on lookup: a set's clause
    * can hand a different node for the same member id as state moves.
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
      // The address variables are looked up by, never their values: a plan is
      // shared across sessions.
      id: NodeId,
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

  /** `structuralId` is the member's for a member's child, which has no id of
    * its own; hence a separate `key`.
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
    // `None`: the subject is a transform and can change per paint, the one
    // shape a plan cannot precompute ([[resolveDirect]]).
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
      // The section guard (ADR 0017), for every slot: a withheld signal value
      // is a false section, so `{{#value}}` would delete its own element and
      // binding on the first patch. Uniform because a card may not know its
      // tier (`entityCard`'s icon is a signal for some domains only).
      constB += ((slot + "__has", "1"))
      source.literal match {
        case Some(text) =>
          constB += ((slot, text))
          // The value as a JS expression; see `bindings` below.
          constB += ((slot + "__read", Datastar.jsLiteral(text)))
        case None =>
          subjectConst match {
            case Some(subject) =>
              // The subject never inherits; every other slot inherits it.
              val srcEntity =
                if (slot == Dashboard.SubjectSlot) source.entityId
                else source.entityId.orElse(subject)
              // `once` reads only identity, so the plan holds it; the memo is
              // keyed by entity, which is why a query slot never enters it.
              source.shape match
                case SlotShape.Query(_)  => dynB += ((slot, srcEntity, source))
                case SlotShape.State(st) =>
                  if (st.reads == Reads.Once) {
                    val once = identityCache.computeIfAbsent(
                      (srcEntity.getOrElse(""), st.valueKey),
                      _ => resolveStateSlot(srcEntity, st, states)
                    )
                    constB += ((slot, once))
                    constB += ((slot + "__read", Datastar.jsLiteral(once)))
                  } else dynB += ((slot, srcEntity, source))
            case None => dynInhB += ((slot, source))
          }
      }
    }
    // The binding is in both forms (a morph that dropped it would leave the
    // element inert); only the value is withheld. A signal slot is always
    // `live` and non-literal, so its name is constant per node.
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
          // For a card composing the signal into its own expression; such a
          // card relies on a signal the plain form does not have.
          s"${slot}__signal" -> signal,
          // The value as a JS expression whatever the tier, so one template
          // serves a static tap and a state-dependent one (issue #133).
          s"${slot}__read" -> s"$$$signal"
        )
      }.toMap,
      signalSlots = named.map(_._1),
      signalNameBySlot = named.map { case (slot, _, signal) =>
        slot -> signal
      }.toMap,
      id = id,
      signalSeed = Datastar.seedFor(named.map(_._3)),
      subjectDynamic = subjectConst.isEmpty,
      ownRendering = hasOwnRendering(id),
      declaresSignals = slots.values.exists(Renderer.isSignalSlot),
      wrapped = !noWrapCards(c.card),
      inline = templates.inlineRegions.getOrElse(c.card, Set.empty),
      // Once, not per paint: it walks the subtree.
      nodeCount = 1 + c.regions.values.view.map(_.map(nodeCount).sum).sum
    )
  }

  private def resolvePlanned(
      plan: NodePlan,
      states: Map[String, EntityState],
      bakeIndex: Map[String, String],
      fragments: QuerySnapshot
  ): Resolved = {
    if (plan.subjectDynamic) resolveDirect(plan, states, bakeIndex, fragments)
    else resolvePlannedSubject(plan, states, bakeIndex, fragments)
  }

  private def resolvePlannedSubject(
      plan: NodePlan,
      states: Map[String, EntityState],
      bakeIndex: Map[String, String],
      fragments: QuerySnapshot
  ): Resolved = {
    // The only map a paint builds.
    val paintB = Map.newBuilder[String, SlotValue]
    paintB.sizeHint(plan.dynamic.size)
    val signalB =
      if (plan.signalNameBySlot.isEmpty) None
      else Some(Map.newBuilder[SignalId, SlotValue])
    plan.dynamic.foreach { case (slot, srcEntity, source) =>
      val value =
        resolveSlotValue(srcEntity, source, states, fragments, plan.id)
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
      signalB.fold(Map.empty[SignalId, SlotValue])(_.result())
    )
  }

  /** For a subject that is itself a transform: every inherited entity, signal
    * name and binding can change per paint.
    */
  private def resolveDirect(
      plan: NodePlan,
      states: Map[String, EntityState],
      bakeIndex: Map[String, String],
      fragments: QuerySnapshot
  ): Resolved = {
    val injected = plan.structural ++ bakeIndex
    val slots = plan.node.slots
    val subject: Option[String] =
      slots.get(Dashboard.SubjectSlot).map { s =>
        s.literal.getOrElse(resolveStateSlot(s.entityId, s, states))
      }
    val resolved: Map[String, SlotValue] = slots.map { case (slot, source) =>
      val value: SlotValue = source.literal match {
        case Some(text) => text
        case None       =>
          val srcEntity =
            if (slot == Dashboard.SubjectSlot) source.entityId
            else source.entityId.orElse(subject)
          if (source.reads == Reads.Once)
            identityCache.computeIfAbsent(
              (srcEntity.getOrElse(""), source.valueKey),
              _ => resolveStateSlot(srcEntity, source, states)
            )
          else
            resolveSlotValue(srcEntity, source, states, fragments, plan.id)
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
        s"${slot}__signal" -> signal,
        s"${slot}__read" -> s"$$$signal"
      )
    }
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

  /** Into the caller's buffer: wrapping a returned String copies the whole
    * subtree once per nesting level (issue #237).
    */
  private def executeInto(
      out: Sink,
      tpl: Mustache,
      r: Resolved,
      childrenHtml: Map[String, List[String]],
      form: SlotForm,
      regionWalk: Map[String, java.io.Writer => Unit]
  ): Unit =
    Templates.run(
      tpl,
      out,
      NodeContext(r, childrenHtml, form, regionWalk)
    )

  /** The signal values one patch unit carries (ADR 0017). A member includes its
    * children, which have no ids; a static node does not — descending there
    * would name a child's signal under its parent and leave the child's binding
    * on a signal nothing writes.
    */
  def signalsFor(
      id: NodeId,
      states: Map[String, EntityState]
  ): Map[SignalId, SlotValue] =
    members
      .memberAt(id, states)
      // Signals only: a query slot has none.
      .map(m => memberSignalsOf(resolveMember(m, states, QuerySnapshot.empty)))
      .orElse(
        // Not gated on `hasOwnRendering`: structure's signal slots would be
        // seeded once and then never move.
        allIndexed
          .get(id)
          .map(_._1)
          .map(signalsOfSlots(id, _, states))
      )
      .getOrElse(Map.empty)

  /** Through the plan: re-deriving the names per tick was 9.4% of a signals
    * tick's allocation (`RenderBench.resumeSignals`).
    */
  private def signalsOfSlots(
      id: NodeId,
      node: LayoutNode,
      states: Map[String, EntityState]
  ): Map[SignalId, SlotValue] = node match {
    case c: LayoutNode.Component =>
      val plan = planOf(id, id, c, states)
      // A dynamic subject's plan holds no names, not because it has no
      // signals.
      if (plan.subjectDynamic) directSignalsOfSlots(id, c, states)
      else if (plan.signalNameBySlot.isEmpty) Map.empty
      else {
        val b = Map.newBuilder[SignalId, SlotValue]
        b.sizeHint(plan.signalNameBySlot.size)
        plan.dynamic.foreach { case (slot, srcEntity, source) =>
          plan.signalNameBySlot
            .get(slot)
            .foreach(sig =>
              b += ((sig, resolveStateSlotValue(srcEntity, source, states)))
            )
        }
        b.result()
      }
    case _: LayoutNode.SetNode => Map.empty
  }

  private def directSignalsOfSlots(
      id: NodeId,
      c: LayoutNode.Component,
      states: Map[String, EntityState]
  ): Map[SignalId, SlotValue] = {
    val subject = c.slots
      .get(Dashboard.SubjectSlot)
      .map(s => s.literal.getOrElse(resolveStateSlot(s.entityId, s, states)))
    c.slots.collect {
      case (slot, src) if Renderer.isSignalSlot(src) =>
        val entity = src.entityId.orElse(subject)
        val kind = Renderer.signalBind(src).getOrElse(SignalBind.Text)
        Renderer.signalName(id, slot, entity, src.valueKey, kind) ->
          resolveStateSlotValue(entity, src, states)
    }
  }

  /** Resolves before any state arrives, so a `$domain` action still works. */
  private def resolveSlot(
      srcEntity: Option[String],
      source: SlotSource,
      states: Map[String, EntityState],
      fragments: QuerySnapshot,
      node: NodeId
  ): String =
    SlotValue.text(
      resolveSlotValue(srcEntity, source, states, fragments, node)
    )

  /** For sites that can never hold a query slot (subject, `once`, signal):
    * taking no snapshot means a site that gains one stops compiling.
    */
  private def resolveStateSlot(
      srcEntity: Option[String],
      source: SlotSource,
      states: Map[String, EntityState]
  ): String = SlotValue.text(resolveStateSlotValue(srcEntity, source, states))

  /** Keeps a boolean boolean ([[SlotValue]]); `false` never falls through to
    * `default`.
    */
  private def resolveSlotValue(
      srcEntity: Option[String],
      source: SlotSource,
      states: Map[String, EntityState],
      fragments: QuerySnapshot,
      node: NodeId
  ): SlotValue = source.shape match {
    // Answered before the walk began; `QuerySnapshot` raises on a miss.
    case SlotShape.Query(ask) => fragments.value(node, ask)
    case SlotShape.State(st)  => resolveStateSlotValue(srcEntity, st, states)
  }

  private def resolveStateSlotValue(
      srcEntity: Option[String],
      source: SlotSource,
      states: Map[String, EntityState]
  ): SlotValue = {
    val st =
      srcEntity
        .flatMap(states.get)
        .getOrElse(EntityState(srcEntity.getOrElse(""), "", Map.empty))
    // The bypass, not the transform, keeps an unavailable entity readable.
    if (source.bypassUnavailable && st.unavailable) st.state
    else {
      // The form is the tier (ADR 0028).
      val out: SlotValue = source.transform match {
        case sm: Transform.Simple => transforms.runValue(sm, st)
        case t: String            => transforms.runValue(t, st, dashboard.slug)
        // Rejected by `validate`; the raw state keeps a bypass readable.
        case _: Transform.Stage => st.state
      }
      out match {
        case b: Boolean              => b
        case s: String if s.nonEmpty => s
        case _                       => source.default.getOrElse("")
      }
    }
  }
}

object Renderer {

  private[runtime] case class InScope(declarer: NodeId, declared: String)

  /** The chrome's scope: no names, only the body and dialog holes. */
  private final class PageScope(holes: Map[String, java.io.Writer => Unit])
      extends Templates.FhScope {
    def fhGet(name: String): AnyRef = null
    def regionWalk: Map[String, java.io.Writer => Unit] = Map.empty
    override def writerHoles: Map[String, java.io.Writer => Unit] = holes
  }

  def create(dashboard: Dashboard, access: Access = Access.default): Renderer =
    new Renderer(
      dashboard,
      Templates.from(dashboard),
      Transforms.from(dashboard),
      access
    )

  /** The production path: reuses the proof's compiled transforms. */
  def fromValidated(v: Dashboard.Validated): Renderer =
    new Renderer(
      v.dashboard,
      Templates.from(v.dashboard),
      Transforms.fromValidated(v),
      v.access,
      v.queries
    )

  /** [[Renderer.headHash]]. Not the `<base href>`, which is per request. Over
    * the model, not the JSON text: `toString` is deterministic, since the maps
    * are `String`-keyed and hold no identity hashes.
    */
  private[runtime] def headFingerprint(dashboard: Dashboard): String =
    fingerprint(
      (
        dashboard.theme.stylesheets,
        dashboard.theme.deferredStylesheets,
        dashboard.theme.scripts,
        dashboard.theme.inlineScripts,
        dashboard.theme.chrome,
        // The one token-derived head part a style patch cannot repair.
        themeColorTags(dashboard)
      ).toString
    )

  /** Follows the device's scheme outside the installed app; Chrome ignores it
    * inside one ([[PwaAssets]]).
    */
  private[runtime] def themeColorTags(dashboard: Dashboard): String =
    ChromeColors.from(dashboard.theme).fold("") { c =>
      List("light" -> c.light, "dark" -> c.dark)
        .map { case (scheme, color) =>
          s"""<meta name="theme-color" media="(prefers-color-scheme: $scheme)" content="$color">"""
        }
        .mkString("\n  ")
    }

  private[runtime] def styleFingerprint(dashboard: Dashboard): String =
    fingerprint(
      (
        dashboard.theme.tokens,
        dashboard.theme.tokensDark,
        dashboard.theme.styles,
        // The same `<style>` element; leaving these out lets a stale sheet
        // hash equal.
        dashboard.css,
        dashboard.cardCss,
        dashboard.title
      ).toString
    )

  private def fingerprint(s: String): String =
    LibPackage.sha256(s.getBytes("UTF-8")).take(12)

  val ThemeStyleId: String = "fh-theme"

  /** One derivation for both ends — the binding the card places and the name
    * the pull patches — since a drift would freeze the value silently (ADR
    * 0017). `_`-prefixed so live values never ride a request.
    *
    * A display signal is keyed by what it reads, `_e.<domain>.<object>.
    * <transform>`, so one entity on three cards is one signal (issue #134). The
    * pinned bundle's reference regex needs `\w+` segments; HA slugifies entity
    * ids, and anything else is hashed, since a stray character would silently
    * parse as another path.
    */
  def signalName(
      id: NodeId,
      slot: String,
      entity: Option[String],
      transform: String,
      kind: SignalBind
  ): SignalId = kind match {
    // Interaction state, scoped to its control: shared, one card's drag would
    // drive another's readout (ADR 0025).
    case SignalBind.Bind => SignalId.derived(s"_${id}__$slot")
    case _               => displayPath(entity, transform)
  }

  /** Memoised: it runs per signal slot per render and may hash. Static, since
    * it reads no dashboard; a reload leaves a few short, still-correct entries.
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

  private def entitySegments(entity: Option[String]): String = entity match {
    case Some(id) =>
      id.split('.') match {
        case Array(domain, obj) if isWord(domain) && isWord(obj) => id
        case _                                                   =>
          s"x${shortHash(id)}"
      }
    // No entity reads an empty state, so such slots genuinely share one.
    case None => "_x"
  }

  /** Not the slot name, which would break sharing or collide. The two forms'
    * prefixes are disjoint, so the mapping stays injective.
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

  /** Never for a literal (`validate` rejects it) or a non-live slot, whose
    * inline seed is the whole story. Answering `None` everywhere yields the
    * plain form (ADR 0017).
    */
  def signalBind(src: SlotSource): Option[SignalBind] =
    src.signal.filter(_ => src.literal.isEmpty && src.reads == Reads.Live)

  def isSignalSlot(src: SlotSource): Boolean = signalBind(src).isDefined

  def surfacePrefix(surfaceId: String): String =
    LayoutNode.surfacePrefix(surfaceId)

  // Leading space included; `validate` checks they are plain class tokens.
  private def cellClasses(cell: Option[Cell]): String =
    cell.map(_.classes).filter(_.nonEmpty).fold("")(_.mkString(" ", " ", ""))

  private[runtime] def perRegion[A](children: Map[String, List[LayoutNode]])(
      f: (LayoutNode, LayoutNode.Step) => A
  ): Map[String, List[A]] =
    children.map { case (region, nodes) =>
      region -> nodes.zipWithIndex.map { case (n, i) =>
        f(n, LayoutNode.Step(region, i))
      }
    }

  /** A shipped leaf is 1-2 kB with wrapper and seed; 512 made every leaf grow
    * twice.
    */
  private[runtime] val NodeBytesHint = 1024

}
