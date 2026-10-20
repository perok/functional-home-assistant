package fh.view.runtime

import com.github.mustachejava.Mustache
import fh.view.build.LibPackage
import fh.view.query.{Queries, QuerySnapshot, QueryRequest}
import fh.view.model.{
  CallReach,
  Access,
  Cell,
  ChromeColors,
  Dashboard,
  DomId,
  ExprValue,
  LayoutNode,
  MemberId,
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
  Surface,
  TapCall,
  ThemeClasses
}
import scala.jdk.CollectionConverters.*

/** One node as a wholesale render left it: the digest of its patch-form bytes
  * (comparable with what a pull renders) and the signals its seed set.
  */
private[runtime] case class Painted(
    // The digest, not the bytes: holding them kept 99 kB of a 128 kB page a
    // second time.
    digest: Digest,
    signals: Map[SignalId, SlotValue]
)

/** A walk's accumulator: what each own-rendering node painted, and the signals
  * a structural node's wrapper seeded. Structure has no bytes of its own to
  * digest, but the client holds its seed all the same; left out, a node's first
  * frame after a page load re-sent every value it already had.
  */
private[runtime] final class Trace {
  val own = new java.util.HashMap[NodeId, Painted]()
  val seeded = new java.util.HashMap[NodeId, Map[SignalId, SlotValue]]()
  def holds: Map[NodeId, Held] =
    own.asScala.view.map { case (id, p) =>
      id -> Held(Some(p.digest), p.signals)
    }.toMap ++
      seeded.asScala.view.map { case (id, s) => id -> Held(signals = s) }
}

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

/** Per render, never cached on the renderer ([[VarGraph.env]]). */
type VarEnv = Map[NodeId, Map[String, String]]

/** Where a render walk is: the static tree, or below a set member, with its
  * clause and the steps down from it, which a nested set's id spells
  * ([[MemberGraph.innerSetId]]).
  */
private[runtime] enum WalkAt derives CanEqual {
  case Static
  case InMember(member: MemberId, clause: Int, path: List[LayoutNode.Step])

  def down(step: LayoutNode.Step): WalkAt = this match {
    case Static      => Static
    case m: InMember => m.into(step)
  }
}

private[runtime] object WalkAt {
  extension (m: WalkAt.InMember)
    def into(step: LayoutNode.Step): WalkAt.InMember =
      m.copy(path = m.path :+ step)
}

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

  private val themeClasses = dashboard.theme.classes
  private val cellOpen = s"""<div class="${themeClasses.expandAll("fh-cell")}"""
  private val groupOpen =
    s"""<div class="${themeClasses.expandAll("fh-cell fh-group")}"""

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
    val setIds: List[NodeId] =
      indexed.toList.collect { case (id, _: LayoutNode.SetNode) => id }.sorted

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
    * computes the same value. Typed, or a `bool` slot's `false` reaches a
    * section as the truthy string `"false"`.
    */
  private val identityCache =
    new java.util.concurrent.ConcurrentHashMap[(String, String), SlotValue]()

  private val surfaceIndexes: Map[String, Index] =
    dashboard.surfaces.map { case (sid, s) =>
      sid -> new Index(s.content, Renderer.surfacePrefix(sid))
    }

  private val allIndexed: Map[NodeId, (LayoutNode, String)] =
    (mainIndex :: surfaceIndexes.values.toList).flatMap { idx =>
      idx.indexed.map { case (id, n) => id -> (n, idx.idPrefix) }
    }.toMap

  /** Lazy: the member graph, the ancestry and the surface graph are built
    * below.
    */
  private[runtime] lazy val vars: VarGraph =
    new VarGraph(
      // A group inside a member's clause has an id of its own, and sees its
      // host's scope.
      scopes = {
        val byNode = dashboard.varScopes.filter(_._2.nonEmpty)
        byNode ++ surfaces.varBakeOwnerIds.flatMap(gid =>
          byNode.get(surfaces.hostOf(gid)).map(gid -> _)
        )
      },
      panelsSelected = surfaces.varBakeOwnerIds.toList
        .flatMap(gid => surfaces.varSelecting(gid).map(gid -> _))
        .toMap,
      asksAt = queriesForNode
    )

  /** Where a session opening on `states` starts each variable that has a start:
    * its expression over the declarer's subject, where every reader takes the
    * result. Otherwise the declared value stands, as for a stale link.
    */
  def startsFor(states: Map[String, EntityState]): Map[VarKey, String] =
    vars.starts.toList.flatMap { (key, start) =>
      states
        .get(start.entityId)
        .map(transforms.run(start.transform, _, dashboard.slug))
        .filter(v =>
          VarKey.ValueToken.matches(v) && refusals(Map(key -> v)).isEmpty
        )
        .map(key -> _)
    }.toMap

  /** One line per refused choice. Every reader must still parse and read only
    * an entity this dashboard shows — the read-side twin of an action's bound
    * (ADR 0023), without which a variable fed to `entity` charts any sensor.
    */
  def refusals(choices: Map[VarKey, String]): List[String] = {
    val env = vars.env(choices)
    choices.toList.flatMap { (key, value) =>
      val why = (vars
        .readersOf(key)
        .flatMap(readsAt(_, env))
        .flatMap(r => refusal(r.query)) ++
        vars.panelsSelectedBy(key).flatMap { gid =>
          Option.unless(surfaces.memberAt(gid, value).isDefined)(
            s"$gid takes ${Surface.membersText(surfaces.memberValues(gid))}"
          )
        }).distinct
      Option.when(why.nonEmpty)(
        s"'$value' is not a value '${key.name}' can take: ${why.mkString("; ")}"
      )
    }
  }

  /** `offered` on top of `kept`, each under [[refusals]] on its own, so one
    * stale value costs only its own variable. In key order, so which of two
    * clashing values wins does not depend on a map's.
    */
  def adopt(
      kept: Map[VarKey, String],
      offered: Map[VarKey, String]
  ): (Map[VarKey, String], List[String]) =
    offered.toList
      .sortBy((key, _) => (key.declarer, key.name))
      .foldLeft((kept, List.empty[String])) {
        case ((acc, refused), (key, value)) =>
          refusals(acc + (key -> value)) match {
            case Nil  => (acc + (key -> value), refused)
            case more => (acc, refused ++ more)
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

  /** A target has its own rendering ([[ownRenderingOf]]), so its descendants
    * are not asked.
    */
  def readsForPull(
      targets: List[NodeId],
      hosts: List[NodeId],
      states: Map[String, EntityState],
      selections: Selections,
      env: VarEnv
  ): List[SlotRead] =
    (targets.flatMap(id => readsAt(id, env) ++ setReadsAbove(id, env)) ++
      hosts.flatMap { gid =>
        members.setContainer(gid) match {
          case Some(_) =>
            setReads(gid, env) ++ setReadsAbove(gid, env)
          case None =>
            surfaces
              .resolveActiveByState(gid, states)
              .flatMap(surfaces.bakeGroup(gid).lift)
              .toList
              .flatMap(queriesForSurface(_, states, selections, env))
        }
      }).distinct

  // What the sets above a node read: a member's render reaches its nested
  // sets, whose members are asked under their own ids.
  private def setReadsAbove(id: NodeId, env: VarEnv): List[SlotRead] =
    if (membersUnder.isEmpty) Nil
    else ancestry.ancestorsOf(id).toList.flatMap(setReads(_, env))

  /** Every member's reads under set `gid`, nested sets' included, each at this
    * viewer's values. Every clause, not the matching one: the snapshot is
    * resolved before the walk decides which matches.
    */
  private def setReads(gid: NodeId, env: VarEnv): List[SlotRead] =
    membersUnder.getOrElse(gid, Nil).flatMap(readsAt(_, env))

  private lazy val membersUnder: Map[NodeId, List[NodeId]] =
    memberAsks.keys.toList
      .flatMap(m =>
        ancestry
          .ancestorsOf(m)
          .filter(members.setContainer(_).isDefined)
          .map(_ -> m)
      )
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sorted)
      .toMap

  /** Everything a member's subtree asks, under the member's id: what a fill or
    * a placement renders, every clause's, since the snapshot is resolved before
    * the walk decides which matches.
    */
  private lazy val memberAsks: Map[NodeId, List[SlotAsk]] =
    members.memberIds.toList.flatMap { m =>
      val asks = members.clauseNodesOf(m).flatMap(Renderer.subtreeAsks).distinct
      Option.when(asks.nonEmpty)(m -> asks)
    }.toMap

  def readsAt(id: NodeId, env: VarEnv): List[SlotRead] =
    queriesForNode(id).map(_.resolve(env.getOrElse(id, Map.empty)))

  private val prefixToRoot: Map[String, String] =
    Map(mainIndex.idPrefix -> "") ++
      surfaceIndexes.map { case (sid, idx) => idx.idPrefix -> sid }

  private val rootOfIndexed: Map[NodeId, String] =
    allIndexed.view.mapValues { case (_, prefix) =>
      prefixToRoot(prefix)
    }.toMap

  private val rootToPrefix: Map[String, String] = prefixToRoot.map(_.swap)

  /** A node and its tree's id prefix: the static tree's, or a member's or a
    * node below one, as the member's current clause has it.
    */
  private def nodeAt(
      id: NodeId,
      states: Map[String, EntityState]
  ): Option[(LayoutNode, String)] =
    allIndexed
      .get(id)
      .orElse(
        members
          .nodeAt(id, states)
          .map((m, n) => (n, rootToPrefix.getOrElse(m.root, "")))
      )

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

  /** Static nodes only: which members bind an entity depends on the membership
    * the recorder holds ([[Membership.binding]]).
    */
  def componentsFor(entityId: String): Set[NodeId] =
    mainIndex.byEntity.getOrElse(entityId, Set.empty)

  /** Empty for a candidate set (its members have ids of their own). */
  def entitiesForNode(
      id: NodeId,
      states: Map[String, EntityState]
  ): List[String] =
    nodeAt(id, states) match {
      case Some((c: LayoutNode.Component, _)) => c.liveEntities
      case _                                  => Nil
    }

  /** A member's are its whole subtree's ([[memberAsks]]); a node below one asks
    * its own in whichever clause holds it.
    */
  private def queriesForNode(id: NodeId): List[SlotAsk] =
    allIndexed.get(id) match {
      case Some((c: LayoutNode.Component, _)) =>
        c.queries
      case _ => memberAsks.getOrElse(id, belowMemberAsks.getOrElse(id, Nil))
    }

  // Built once: a tick asks for every target it pulls.
  private lazy val belowMemberAsks: Map[NodeId, List[SlotAsk]] =
    members.memberIdsBelow.flatMap { id =>
      val asks = members.clauseNodesAt(id).flatMap(_.queries).distinct
      Option.when(asks.nonEmpty)(id -> asks)
    }.toMap

  /** The bound a variable write is held to (ADR 0023). The model's static walk,
    * not [[Index.byEntity]], which stops at a set: this must answer for
    * entities nothing renders yet.
    */
  def references(entityId: String): Boolean =
    dashboard.referencedEntities.contains(entityId)

  def reachOf(call: TapCall): Option[CallReach] = dashboard.calls.get(call)

  /** Wider than [[references]] — see [[Dashboard.watchedEntities]]. */
  def watchedEntities: Set[String] = dashboard.watchedEntities

  /** The surfaces whose static nodes bind `entityId`: the candidates a tick
    * asks [[SurfaceGraph.visibleSurface]] of, rather than every shown surface.
    */
  def surfacesBinding(entityId: String): List[String] =
    surfacesByEntity.getOrElse(entityId, Nil)

  private lazy val surfacesByEntity: Map[String, List[String]] =
    surfaceIndexes.toList
      .flatMap((sid, idx) => idx.byEntity.keys.map(_ -> sid))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sorted)
      .toMap

  def surfaceComponentsFor(surfaceId: String, entityId: String): Set[NodeId] =
    surfaceIndexes
      .get(surfaceId)
      .fold(Set.empty)(_.byEntity.getOrElse(entityId, Set.empty))

  def surface(surfaceId: String): Option[Surface] =
    dashboard.surfaces.get(surfaceId)

  def stylesheets: List[String] = dashboard.theme.stylesheets

  def deferredStylesheets: List[String] = dashboard.theme.deferredStylesheets

  val themeColorTags: String = Renderer.themeColorTags(dashboard)

  val chromeColors: Option[ChromeColors] = ChromeColors.from(dashboard.theme)

  def scripts: List[String] = dashboard.theme.scripts

  /** Cards first, as in the cascade [[themeStyleTag]] writes. */
  def inlineScripts: List[String] =
    dashboard.cardScripts ++ dashboard.theme.inlineScripts

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
      selections: Selections = Selections.none,
      fragments: QuerySnapshot
  ): Traced =
    traced(
      dashboard.card,
      LayoutNode.rootId("", dashboard.card),
      "",
      states,
      selections,
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
      selections: Selections = Selections.none,
      fragments: QuerySnapshot
  ): Map[NodeId, Painted] =
    pageInto(out, states, selections, fragments).own.asScala.toMap

  /** [[renderPageInto]], returning what the client now holds, structure's seeds
    * included: what a page load commits as the session's `holds`.
    */
  private[runtime] def renderPageHolds(
      out: Sink,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot
  ): Map[NodeId, Held] =
    pageInto(out, states, selections, fragments).holds

  private def pageInto(
      out: Sink,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot
  ): Trace = {
    val own = new Trace
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
        selections,
        fragments,
        own
      )
    val dialogInto: Option[java.io.Writer => Unit] =
      selections.popup.flatMap(sid =>
        surfaceWalk(out, sid, states, selections, fragments, own)
      )
    // Not mustache's `execute(ctx)`, whose `StringWriter` grows from 16 chars
    // and copies the page twice more.
    val _ = out.append(themeStyleTag)
    val scope = new Renderer.PageScope(
      Map("body" -> bodyInto) ++ dialogInto.map("popups" -> _)
    )
    Templates.run(chromeTemplate, out, scope)
    own
  }

  /** [[renderSurfaceTraced]] as a writer hole into the page's buffer. */
  private def surfaceWalk(
      out: Sink,
      surfaceId: String,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot,
      trace: Trace
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
        selections,
        fragments,
        trace
      )
    }

  /** Bare content: the host and any frame live in `theme.chrome`. */
  private[runtime] def renderSurfaceTraced(
      surfaceId: String,
      states: Map[String, EntityState],
      selections: Selections = Selections.none,
      fragments: QuerySnapshot
  ): Option[Traced] =
    dashboard.surfaces.get(surfaceId).map { s =>
      traced(
        s.content,
        LayoutNode.rootId(Renderer.surfacePrefix(surfaceId), s.content),
        Renderer.surfacePrefix(surfaceId),
        states,
        selections,
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
      selections: Selections = Selections.none,
      form: SlotForm = SlotForm.Patch,
      fragments: QuerySnapshot
  ): Option[String] =
    nodeAt(id, states)
      .filter((node, _) => ownRenderingOf(node))
      .flatMap { case (node, prefix) =>
        render(node, id, prefix, states, selections, form, fragments)
      }

  /** Whether a member is its own patch target in every clause: a leaf card. A
    * member that can hold nodes is patched through them, and placed whole.
    */
  def memberIsTarget(id: NodeId): Boolean =
    members.clauseNodesAt(id).forall(ownRenderingOf)

  /** A member's whole element in document form, with what it claims for the
    * record: what an arrival or a clause switch inserts. Per viewer, since a
    * member holding regions can bake a viewer's selection.
    */
  def renderMemberTraced(
      id: NodeId,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot
  ): Option[Traced] =
    members
      .memberAt(id, states)
      .map(memberTraced(_, states, selections, fragments))

  private def memberTraced(
      m: Member,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot
  ): Traced =
    traced(
      m.node,
      m.id,
      rootToPrefix.getOrElse(m.root, ""),
      states,
      selections,
      fragments,
      WalkAt.InMember(m.id, m.clause, Nil)
    )

  /** `s_<sid>__c`, what a state group's host holds; the same scheme the
    * build-phase hoist uses.
    */
  def surfaceContentId(surfaceId: String): NodeId =
    LayoutNode.nodeId(Renderer.surfacePrefix(surfaceId), Nil)

  def queryRequests: Map[SlotRead, QueryRequest] = parsedQueries

  private def readsIn(idx: Index, env: VarEnv): List[SlotRead] =
    (idx.asks.flatMap { case (id, asks) =>
      asks.map(_.resolve(env.getOrElse(id, Map.empty)))
    } ++ idx.setIds.flatMap(setReads(_, env))).distinct

  /** The surfaces shown inside it included. */
  def queriesForSurface(
      surfaceId: String,
      states: Map[String, EntityState],
      selections: Selections,
      env: VarEnv
  ): List[SlotRead] =
    surfaces
      .shownWithin(surfaceId, states, selections)
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
    val shown = surfaces.shownSurfaces(open, states)
    (readsIn(mainIndex, env) ++
      shown.toList.sorted.flatMap(readsOfSurface(_, env))).distinct
  }

  /** The resume's second candidate set: nothing may have recorded an open
    * surface while nobody viewed it, so only re-rendering can tell.
    */
  def surfaceNodeIds(surfaceId: String): Set[NodeId] =
    surfaceIndexes.get(surfaceId).fold(Set.empty)(_.indexed.keySet)

  /** A fill of either container: a set's members, in DOM order, or a state
    * group's branch.
    */
  private[runtime] def renderHost(
      container: NodeId,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot
  ): HostContent =
    members.setContainer(container) match {
      case Some(setId) =>
        val traced = members
          .membersOf(setId, states)
          .toList
          .map(m => m.id -> memberTraced(m, states, selections, fragments))
        HostContent(
          traced.map((id, t) => id -> t.html),
          traced.flatMap(_._2.claims).toMap
        )
      case None =>
        surfaces
          .resolveActiveByState(container, states)
          .flatMap(surfaces.bakeGroup(container).lift)
          .flatMap(sid =>
            renderSurfaceTraced(sid, states, selections, fragments).map(t =>
              HostContent(List(surfaceContentId(sid) -> t.html), t.claims)
            )
          )
          .getOrElse(HostContent(Nil, Map.empty))
    }

  /** One derivation for both id sources (the static tree and set members), so a
    * var added to [[Dashboard.structuralVars]] reaches both. `bakeIndex`
    * depends on a selection, so it is not here ([[resolveBakeTraced]]).
    */
  private def structuralVars(
      id: NodeId,
      states: Map[String, EntityState]
  ): Map[String, String] =
    Dashboard.structuralVars(id, hostId(groupOf(id, states)), dashboard.slug)

  /** The bake group `id` owns: inside a set member, its current clause's
    * ([[Surface.group]]).
    */
  private def groupOf(id: NodeId, states: Map[String, EntityState]): NodeId =
    members
      .nodeAt(id, states)
      .fold(id)((m, _) => surfaces.groupAt(id, m.clause))

  /** Whether a node may be a log key or a patch target: decided by its card
    * alone. Structure's element contains its regions, so patching it would
    * re-send them; a set root composes its members. Both keep their
    * [[elementId]] for structural patches. `Dashboard.validate` enforces the
    * same rule when it rejects a live bytes slot on structure.
    */
  private def ownRenderingOf(node: LayoutNode): Boolean = node match {
    case c: LayoutNode.Component =>
      !dashboard.cards.get(c.card).exists(_.isStructure)
    case _: LayoutNode.SetNode => false
  }

  /** [[ownRenderingOf]] for a node of the static tree; a member's or one below
    * it is decided by its current clause, so it goes through [[nodeAt]].
    */
  private def hasOwnRendering(id: NodeId): Boolean =
    allIndexed.get(id).exists((n, _) => ownRenderingOf(n))

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
      gid: NodeId,
      selections: Selections,
      states: Map[String, EntityState]
  ): (Map[String, String], Option[(String, String)]) = {
    val group = surfaces.bakeGroup(gid)
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
      activeBakeIndex(gid, selections, states) match {
        case Some(idx) => bakeMember(idx)
        // A state group with no matching branch: the hole renders empty.
        case None => (Map.empty, None)
      }
  }

  /** `None` for a node owning no group, or a state group with no branch
    * holding.
    */
  private def activeBakeIndex(
      gid: NodeId,
      selections: Selections,
      states: Map[String, EntityState]
  ): Option[Int] =
    if (surfaces.bakeGroup(gid).isEmpty) None
    else if (surfaces.isStateGroup(gid))
      surfaces.resolveActiveByState(gid, states)
    else Some(surfaces.resolveActive(gid, selections))

  /** The render cache's key (ADR 0012): the content version of each entity that
    * can move this node's bytes — not those read only through signal slots,
    * whose values are absent from the patch form (ADR 0017). A version, not the
    * value: `EntityState.hashCode` walks the attributes. A missing entity has
    * no entry, a distinct key.
    *
    * No selection (only structure reads one, and structure is never cached) and
    * no children (a descendant's tick would invalidate every ancestor). `None`
    * means not cacheable: exactly [[ownRenderingOf]]'s `false`.
    */
  def renderInputs(
      id: NodeId,
      states: Map[String, EntityState],
      fragments: QuerySnapshot
  ): Option[RenderInputs] =
    members.nodeAt(id, states) match {
      case Some((m, c)) =>
        Option.when(ownRenderingOf(c))(
          RenderInputs(
            // The subject chose the clause, so it keys the bytes even when no
            // slot reads it; the clause is named too, since every clause of a
            // candidate mints the same ids below the member, and different key
            // sets never compare ([[RenderInputs.isAtLeast]]).
            versions(
              m.node.subjectEntity.toList ++ c.liveEntitiesAsBytes,
              states
            ) + (Renderer.clauseKey(m.clause) -> 0L),
            fragments.versions(id, c.queries)
          )
        )
      case None =>
        allIndexed.get(id).collect {
          case (c: LayoutNode.Component, _) if ownRenderingOf(c) =>
            RenderInputs(
              versions(c.liveEntitiesAsBytes, states),
              fragments.versions(id, c.queries)
            )
        }
    }

  /** The render cache's pre-check: equal byte-slot values, equal bytes. The
    * key's `contentVersion` moves on any change, so without this every
    * brightness tick on the shipped `EntityCard` re-renders to identical bytes.
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
        val plan = planOf(id, c, states)
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

  /** A leaf's patch form is written directly, not taken from a [[traced]] walk,
    * which builds the document form, its digest and the trace maps a tick
    * throws away. A leaf has no children and no bake group (its card declares
    * no region), so this is its whole body; [[wrapperInto]] is shared with the
    * walk, and `DigestPropertySuite` pins that the two agree.
    */
  private def render(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      selections: Selections,
      form: SlotForm,
      fragments: QuerySnapshot
  ): Option[String] = node match {
    case c: LayoutNode.Component if form.isPatch =>
      val plan = planOf(id, c, states)
      val resolved = resolvePlanned(plan, states, Map.empty, fragments)
      Some(Sink.scratched { buf =>
        wrapperInto(buf, c, id, plan, resolved, SlotForm.Patch)
        Templates.run(
          plan.tpl,
          buf,
          NodeContext(resolved, Map.empty, SlotForm.Patch)
        )
        if (plan.wrapped) { val _ = buf.append("</div>") }
        buf.result
      })
    case _ =>
      Some(traced(node, id, idPrefix, states, selections, fragments).html)
  }

  /** The composed rendering and each node's own digest, kept from the one walk
    * so a fill or page load need not re-walk the subtree. `own` holds exactly
    * the nodes with their own rendering, digested from what [[renderNodeById]]
    * returns — which is what makes them comparable.
    */
  private[runtime] case class Traced(
      html: String,
      own: Map[NodeId, Painted],
      seeded: Map[NodeId, Map[SignalId, SlotValue]] = Map.empty
  ) {

    def claims: Map[NodeId, Held] =
      own.map { case (id, p) => id -> Held(Some(p.digest), p.signals) } ++
        seeded.map { case (id, s) => id -> Held(signals = s) }
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
      selections: Selections,
      fragments: QuerySnapshot,
      within: WalkAt = WalkAt.Static
  ): Traced = {
    val trace = new Trace
    val html =
      tracedHtml(
        node,
        id,
        idPrefix,
        states,
        selections,
        fragments,
        trace,
        within
      )
    Traced(html, trace.own.asScala.toMap, trace.seeded.asScala.toMap)
  }

  /** A subtree's document bytes, its trace into the caller's accumulator. The
    * buffer is presized: growing it is the copy this walk exists to remove.
    */
  private def tracedHtml(
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot,
      trace: Trace,
      within: WalkAt
  ): String = {
    val out = Sink.buffer(
      Renderer.NodeBytesHint * (node match {
        case c: LayoutNode.Component => planOf(id, c, states).nodeCount
        case _                       => nodeCount(node)
      })
    )
    tracedInto(
      out,
      node,
      id,
      idPrefix,
      states,
      selections,
      fragments,
      trace,
      within
    )
    out.result
  }

  /** A node's document bytes appended to `out`, and every own-rendering node's
    * patch digest into `trace`. An accumulator, not per-level maps merged
    * upward: those allocated per node per paint and re-copied every ancestor's.
    * `within` is set below a set member: its nodes are ordinary, but a nested
    * set's id carries the member's clause ([[MemberGraph.innerSetId]]).
    */
  private def tracedInto(
      out: Sink,
      node: LayoutNode,
      id: NodeId,
      idPrefix: String,
      states: Map[String, EntityState],
      selections: Selections,
      fragments: QuerySnapshot,
      trace: Trace,
      within: WalkAt = WalkAt.Static
  ): Unit =
    node match {
      case c: LayoutNode.Component =>
        val gid = within match {
          case WalkAt.Static      => id
          case m: WalkAt.InMember => surfaces.groupAt(id, m.clause)
        }
        val (bakeIndex, bakeSel) = resolveBakeTraced(gid, selections, states)
        // Resolved once for both forms; what a paint cannot change comes from
        // the plan ([[NodePlan]]).
        val plan = planOf(id, c, states)
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

        def withinOf(region: String, i: Int): WalkAt =
          within.down(LayoutNode.Step(region, i))

        def childId(region: String, i: Int, child: LayoutNode) =
          (child, within) match {
            case (set: LayoutNode.SetNode, m: WalkAt.InMember) =>
              val at = m.into(LayoutNode.Step(region, i))
              members.innerSetId(at.member, at.clause, at.path, set)
            case _ =>
              LayoutNode.childId(
                idPrefix,
                id,
                LayoutNode.Step(region, i),
                child
              )
          }

        def wrapper(buf: Sink, form: SlotForm): Unit =
          wrapperInto(buf, c, id, plan, resolved, form)

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
                      selections,
                      fragments,
                      trace,
                      within = withinOf(region, i)
                    )
                  }
              }.toMap
            val bakedHtml: Map[String, List[String]] =
              bakeSel match {
                case Some((region, sid)) if !inline.contains(region) =>
                  renderSurfaceTraced(sid, states, selections, fragments)
                    .map { t =>
                      t.own.foreach { case (nid, p) => trace.own.put(nid, p) }
                      t.seeded.foreach { case (nid, sg) =>
                        trace.seeded.put(nid, sg)
                      }
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
                        selections,
                        fragments,
                        trace,
                        within = withinOf(region, i)
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
                        selections,
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

        def documentInto(o: Sink): Unit = {
          wrapper(o, SlotForm.Document)
          bodyInto(o, SlotForm.Document)
          if (wrapped) { val _ = o.append("</div>") }
        }
        // One form: the sink digests the document bytes as they pass.
        val inlineDigest: Digest | Null =
          if (plan.ownRendering && !twoForms) out.digesting(documentInto)
          else {
            documentInto(out)
            null
          }

        // Wrapper included and in patch form, as `renderNodeById` produces.
        val own = Option.when(plan.ownRendering) {
          if (twoForms) {
            val digest = Sink.scratched(_.digesting { buf =>
              wrapper(buf, SlotForm.Patch)
              bodyInto(buf, SlotForm.Patch)
              if (wrapped) { val _ = buf.append("</div>") }
            })
            Painted(digest, resolved.signals)
          } else Painted(inlineDigest.nn, resolved.signals)
        }
        // Keyed by the plan's id, not this paint's: `holds` keeps the key per
        // session, and a fresh string each paint was a third of an entry.
        own match
          case Some(p) => val _ = trace.own.put(plan.id, p)
          case None if resolved.signals.nonEmpty =>
            val _ = trace.seeded.put(plan.id, resolved.signals)
          case None => ()
      // Each member is walked like any node, so the trace holds each patch
      // target inside it; the root has no rendering.
      case s: LayoutNode.SetNode =>
        val setId = SetId.of(id, s)
        out
          .append(groupOpen)
          .append(Renderer.cellClasses(s.cell, themeClasses))
          .append("""" id="""")
          .append(setId)
          .append("\">")
        members.membersOf(setId, states).foreach { m =>
          tracedInto(
            out,
            m.node,
            m.id,
            idPrefix,
            states,
            selections,
            fragments,
            trace,
            within = WalkAt.InMember(m.id, m.clause, Nil)
          )
        }
        val _ = out.append("</div>")
    }

  private def wrapperInto(
      buf: Sink,
      c: LayoutNode.Component,
      id: NodeId,
      plan: NodePlan,
      resolved: Resolved,
      form: SlotForm
  ): Unit =
    if (plan.wrapped) {
      cellOpenInto(buf, c.cell, resolved, form)
      buf
        .append(""" id="""")
        .append(id)
        .append('"')
      if (!form.isPatch)
        Datastar.seedAttrInto(buf, plan.signalSeed, resolved.signals)
      cellBindingsInto(buf, resolved)
      // On the declarer, so a param lives as long as what it chooses for, not
      // as long as a control choosing it (ADR 0005).
      c.vars.toList
        .sortBy(_._1)
        .filterNot((name, _) => c.notInUrl(name))
        .foreach { (name, declared) =>
          val _ = buf
            .append(' ')
            .append(
              VarKey(id, name).urlMirrorAttr(declared, c.starts.contains(name))
            )
        }
      val _ = buf.append('>')
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
      .map(memberTraced(_, states, Selections.none, fragments).html)

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
      constants: Map[String, SlotValue],
      bindings: Map[String, String],
      paint: Map[String, SlotValue],
      bake: Map[String, String],
      liveBindings: Map[String, String],
      signalSlots: List[String],
      signals: Map[SignalId, SlotValue]
  )

  /** A component's `<div class="fh-cell …"`, its live classes and custom
    * properties ([[Dashboard.cellClassSlot]], [[Dashboard.cellStyleSlot]])
    * inline in the document form only, where signal values are not withheld.
    * Their bindings are [[cellBindingsInto]].
    */
  private def cellOpenInto(
      buf: Sink,
      cell: Option[Cell],
      r: Resolved,
      form: SlotForm
  ): Unit = {
    buf.append(cellOpen).append(Renderer.cellClasses(cell, themeClasses))
    if (!form.isPatch)
      cellSlots(r, Dashboard.CellClassPrefix)
        .filter(slot => r.paint.get(slot).exists(SlotValue.truthy))
        .foreach { slot =>
          buf
            .append(' ')
            .append(
              themeClasses
                .expandAll(slot.stripPrefix(Dashboard.CellClassPrefix))
            )
        }
    val _ = buf.append('"')
    if (!form.isPatch) {
      val props = cellSlots(r, Dashboard.CellStylePrefix).flatMap(slot =>
        r.paint
          .get(slot)
          .map(v =>
            slot.stripPrefix(Dashboard.CellStylePrefix) + ":" + SlotValue.text(
              v
            )
          )
      )
      if (props.nonEmpty)
        val _ = buf
          .append(""" style="""")
          .append(Server.escapeHtml(props.mkString(";")))
          .append('"')
    }
  }

  /** In both forms, and AFTER the wrapper's own `data-signals`: the bundle
    * applies an element's attributes in order, so a binding ahead of the seed
    * reads a signal that does not exist yet (`Cannot read properties of
    * undefined`, the first `LiveCellClassSmokeSuite` run).
    */
  private def cellBindingsInto(buf: Sink, r: Resolved): Unit =
    (cellSlots(r, Dashboard.CellClassPrefix) ++
      cellSlots(r, Dashboard.CellStylePrefix)).foreach { slot =>
      val key = slot + "__bind"
      r.bindings.get(key).orElse(r.liveBindings.get(key)).foreach { b =>
        buf.append(' ').append(b)
      }
    }

  private def cellSlots(r: Resolved, prefix: String): List[String] =
    r.signalSlots.filter(_.startsWith(prefix))

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
            if (v == null) {
              val c: SlotValue | Null = resolved.constants.getOrElse(name, null)
              if (c != null) v = SlotValue.scoped(c)
            }
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
      constants: Map[String, SlotValue],
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

  private def planOf(
      id: NodeId,
      node: LayoutNode.Component,
      states: Map[String, EntityState]
  ): NodePlan = {
    val cached = nodePlans.get(id)
    if (cached != null && (cached.node eq node)) cached
    else {
      val p = buildPlan(id, node, states)
      nodePlans.put(id, p)
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
    val constB = Map.newBuilder[String, SlotValue]
    val dynB = List.newBuilder[(String, Option[String], SlotSource)]
    val dynInhB = List.newBuilder[(String, SlotSource)]
    slots.foreach { case (slot, source) =>
      // The section guard (ADR 0017), for every slot: a withheld signal value
      // is a false section, so `{{#value}}` would delete its own element and
      // binding on the first patch. Uniform because a card may not know its
      // tier (`EntityCard`'s icon is a signal for some domains only).
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
                      _ => resolveStateSlotValue(srcEntity, st, states)
                    )
                    constB += ((slot, once))
                    constB += ((slot + "__read", Datastar.jsValue(once)))
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
      structural = structuralVars(id, states),
      constants = constB.result(),
      dynamic = dynB.result(),
      bindings = named.flatMap { case (slot, kind, signal) =>
        List(
          s"${slot}__bind" -> Datastar.binding(signal, kind, themeClasses),
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
      ownRendering = ownRenderingOf(c),
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
              _ => resolveStateSlotValue(srcEntity, source, states)
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
        s"${slot}__bind" -> Datastar.binding(signal, kind, themeClasses),
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

  /** The signal values one node carries (ADR 0017), never its children's:
    * descending would name a child's signal under its parent and leave the
    * child's binding on a signal nothing writes.
    */
  def signalsFor(
      id: NodeId,
      states: Map[String, EntityState]
  ): Map[SignalId, SlotValue] =
    // Not gated on own rendering: structure's signal slots would be seeded
    // once and then never move.
    nodeAt(id, states)
      .map((node, _) => signalsOfSlots(id, node, states))
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
      val plan = planOf(id, c, states)
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

  /** Resolves before any state arrives, so a `domain` action still works. */
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
        case t: String            =>
          transforms.runValue(
            Transform.CelKey(t, ExprValue.env(source.values)),
            st,
            dashboard.slug,
            Conditions.resolve(source.values, states)
          )
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

  /** A render key's entry naming a member's clause ([[Renderer.renderInputs]]).
    * NUL-led, so no entity id can collide with it.
    */
  def clauseKey(clause: Int): String =
    if (clause < ClauseKeys.length) ClauseKeys(clause)
    else s"\u0000clause $clause"

  // Interned: a key is built on every render-cache lookup.
  private val ClauseKeys: Vector[String] =
    Vector.tabulate(16)(i => s"\u0000clause $i")

  /** A subtree's asks, stopping at a nested set, whose members ask their own.
    */
  private[runtime] def subtreeAsks(node: LayoutNode): List[SlotAsk] =
    node match {
      case c: LayoutNode.Component =>
        c.queries ++ c.allChildren.flatMap(subtreeAsks)
      case _: LayoutNode.SetNode => Nil
    }

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
        dashboard.cardScripts,
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
  private def cellClasses(cell: Option[Cell], classes: ThemeClasses): String =
    cell
      .map(_.classes.flatMap(classes.expand))
      .filter(_.nonEmpty)
      .fold("")(_.mkString(" ", " ", ""))

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
