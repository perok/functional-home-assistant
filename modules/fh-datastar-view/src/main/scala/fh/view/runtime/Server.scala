package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fh.view.model.Dashboard
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef, Topic}
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.ServerSentEvent

import scala.concurrent.duration.*

/** One DOM patch the diff pass wants to send, rendered to a Datastar SSE event
  * at the edge ([[Patch.toSse]]). The diff no longer yields a uniform "HTML to
  * morph" — a [[Remove]] carries no HTML — so the two diff passes speak this
  * small ADT and only touch [[Datastar]] here.
  *
  *   - [[Morph]]: outer-morph an existing element (its `id` is inside `html`).
  *   - [[Insert]]: add a new element with an explicit `mode`/`selector`
  *     (`before` its DOM successor, or `append` into the group root).
  *   - [[Remove]]: delete the element matching `selector` (no HTML).
  */
private enum Patch:
  case Morph(html: String)
  case Insert(html: String, mode: PatchMode, selector: String)
  case Remove(selector: String)

  def toSse: ServerSentEvent = this match
    case Patch.Morph(html)             => Datastar.patchElements(html)
    case Patch.Insert(html, mode, sel) => Datastar.patch(html, mode, Some(sel))
    case Patch.Remove(sel)             => Datastar.remove(sel)

/** HTTP surface for the dashboards.
  *
  *   - `GET /` the default dashboard; `GET /d/:slug` a specific one.
  *   - `GET /sse/dashboard/:slug/patch` the per-connection live stream of
  *     `datastar-patch-elements` fragments. On connect it mints a `conn` id and
  *     pushes it as a signal, so action POSTs can correlate to this stream.
  *   - `POST /sse/action/:domain/:service/:id[/:k/:v]` call a HA service.
  *   - `POST /sse/surface/open/:id` open a surface (popup or tab panel);
  *     `POST /sse/popup/close` close the (at most one) open popup;
  *     `POST /sse/navigate/:slug` swap the viewed dashboard in place. Open,
  *     switch, and close are all the same host-swap ([[swapHost]]) — evict
  *     whatever occupies the surface's host, patch the new occupant in (or
  *     patch it empty, for a close). The state lives in the connection's
  *     [[Session]]; the resulting patches ride the same SSE stream.
  *
  * Live entity patches are split by what they depend on. Main-page nodes that
  * don't own a bake group are a pure function of entity state, so they are
  * rendered ONCE per slug by [[sharedPatchPublishers]] (one subscription to the
  * state stream per dashboard, per-slug diff cache) and fanned out to every
  * connection viewing that slug over `sharedTopics`. Only what truly differs
  * per client stays per-session in [[changedPatches]]: open-surface nodes and
  * bake-group-owner nodes (their HTML depends on the client's `uiState`).
  * Construct via [[Server.resource]], which creates the topics and runs the
  * publishers.
  */
class Server(
    api: HomeAssistantApi[IO],
    stateStore: StateStore,
    // One hot-swappable renderer per dashboard slug (live reload swaps in place;
    // `.discrete` drives a body repaint over SSE).
    renderers: Map[String, SignallingRef[IO, Renderer]],
    defaultSlug: String,
    sessions: Sessions,
    // Per-slug fan-out of the shared main-page patches (fed by
    // `sharedPatchPublishers`; every connection viewing the slug subscribes).
    sharedTopics: Map[String, Topic[IO, ServerSentEvent]],
    // Local cache of the themes' external assets ([[AssetCache]]): page URLs
    // are rewritten through it and `/assets/:name` serves from it. The empty
    // default (pass-through, no local assets) keeps tests ceremony-free.
    assets: AssetCache = AssetCache.empty,
    // Whether the upstream Home Assistant feed is live ([[HaFeed.healthy]]). The
    // SSE heartbeat only beats while this is true, so the client disconnect
    // banner also lights up on an upstream freeze — not just a browser-side
    // drop. Constant-`true` default keeps tests/standalone construction simple.
    healthy: Signal[IO, Boolean] = Signal.constant(true)
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root              => pageResponse(defaultSlug, req)
    case req @ GET -> Root / "d" / slug => pageResponse(slug, req)

    // Locally cached theme assets (stylesheets/scripts/fonts); a name that
    // isn't cached is a 404 — the page then references the original URL.
    case GET -> Root / "assets" / name => assets.serve(name)

    // Edit-mode node inspection ("debug this node"): the live entity state of
    // every entity a rendered node binds. Read-only; used by the overlay the
    // dashboard injects when embedded in the editor preview (`?edit=1`).
    case GET -> Root / "edit" / "node" / slug / id / "debug" =>
      nodeDebug(slug, id)

    case req @ GET -> Root / "sse" / "dashboard" / slug / "patch" =>
      if (renderers.contains(slug)) sseStream(slug, req) else NotFound()

    // No-data action (toggle, open/close, lock, play/pause, scene activate...).
    // `domain` is the SERVICE's domain, which is not always the entity's domain
    // (e.g. `homeassistant.toggle` on a `light.*`), so it's passed explicitly.
    case POST -> Root / "sse" / "action" / domain / service / entityId =>
      callService(domain, service, entityId, Json.obj())

    // Single-value action (brightness, cover position, target temperature...).
    case POST -> Root / "sse" / "action" / domain / service / entityId / dataKey / dataValue =>
      callService(
        domain,
        service,
        entityId,
        Json.obj(dataKey -> Server.parseValue(dataValue))
      )

    case req @ POST -> Root / "sse" / "surface" / "open" / id =>
      withSession(req)((session, renderer, uiState) =>
        openSurface(session, renderer, id, uiState)
      )

    case req @ POST -> Root / "sse" / "popup" / "close" =>
      withSession(req)((session, renderer, uiState) =>
        swapHost(session, renderer, Dashboard.PopupHostId, None, uiState)
      )

    case req @ POST -> Root / "sse" / "navigate" / slug =>
      withSession(req)((session, _, uiState) =>
        navigate(session, slug, uiState)
      )
  }

  /** One background stream per slug feeding its shared topic: render each
    * affected main-page fragment ONCE per state change and fan it out to every
    * connection viewing the slug — instead of N viewers doing N identical
    * renders. Only nodes whose HTML is a pure function of entity state qualify,
    * so bake-group owners (uiState-dependent) are excluded and stay per-session
    * ([[changedPatches]]).
    *
    * Renderer hot-swap: `switchMap` re-arms on every reload with the CURRENT
    * renderer and a FRESH per-slug diff cache. A change landing in the brief
    * switch window may be dropped — harmless, because every connection does a
    * full body repaint on reload ([[reloadRepaints]]).
    *
    * Run by [[Server.resource]] (or a test) — the class only defines it.
    *
    * FUTURE (ADR): under a burst of state_changed events (HA fires them
    * constantly), coalesce — debounce/batch the stream and re-render at most
    * every X ms, collapsing repeated touches of the same node into one
    * render+push. The narrowing here bounds *what* re-renders; batching would
    * bound *how often*. (Fold this into the dynamic-groups ADR when the perf
    * model is settled.)
    */
  def sharedPatchPublishers: Stream[IO, Nothing] =
    sharedTopics.toList
      .map { case (slug, topic) =>
        val patches = renderers.get(slug) match {
          case None => Stream.empty
          case Some(ref) =>
            ref.discrete.switchMap { renderer =>
              Stream.eval(Ref[IO].of(Map.empty[String, String])).flatMap {
                cache =>
                  stateStore.changes
                    .evalMap(sharedPatches(renderer, cache, _))
                    .flatMap(Stream.emits)
              }
            }
        }
        patches.through(topic.publish)
      }
      .foldLeft(Stream.empty.covaryAll[IO, Nothing])(_.merge(_))

  /** The shared per-slug render/diff for one state change: the affected
    * main-page static components (reverse index, minus the bake-group owners)
    * plus the query-affected dynamic groups, rendered against the current
    * snapshot and diffed against the slug's shared cache. Returns the SSE
    * patches — child-scoped for a dynamic member update, per-entity
    * insert/remove for a small membership delta, a whole-group morph otherwise
    * (see [[diffPatches]]). No `uiState`: by construction these nodes don't
    * read it.
    */
  private[runtime] def sharedPatches(
      renderer: Renderer,
      cache: Ref[IO, Map[String, String]],
      change: StateChange
  ): IO[List[ServerSentEvent]] =
    stateStore.snapshot.flatMap { states =>
      val staticIds =
        renderer
          .componentsFor(change.entityId)
          .toList
          .filterNot(renderer.bakeOwnerIds)
      val dynamics = renderer.affectedDynamics(change)
      cache.modify(
        diffPatches(
          renderer,
          _,
          staticIds,
          dynamics,
          change,
          states,
          beforeSnapshot(states, change),
          Map.empty
        )
      )
    }

  /** The snapshot as it was BEFORE this change — the current snapshot with the
    * changed entity rewound to its `previous` value (or dropped when it was
    * newly seen). Lets a dynamic group compute its membership before vs. after
    * from a single [[StateChange]], without the store tracking prior snapshots.
    */
  private def beforeSnapshot(
      states: Map[String, EntityState],
      change: StateChange
  ): Map[String, EntityState] =
    change.previous.fold(states - change.entityId)(p =>
      states.updated(change.entityId, p)
    )

  /** Diff a set of static component ids + a set of affected dynamic groups
    * against `cache`, returning the updated cache and the SSE patches to emit.
    * The single diff contract shared by the per-slug ([[sharedPatches]]) and
    * per-session ([[changedPatches]]) passes.
    *
    *   - Static components outer-morph when their HTML actually changed.
    *   - A dynamic group with an [[DynamicDelta.InPlace]] member re-renders and
    *     outer-morphs that ONE child; an add/remove is patched per-entity when
    *     the churn is a small fraction of the group, else the whole group
    *     repaints ([[renderDynamicGroup]] applies [[Server.MaxChurnFraction]]).
    *   - A whole-group repaint prunes that group's child cache entries so the
    *     next per-entity patch re-establishes from a known base.
    *
    * Pure (all rendering is pure over `states`); the caller wraps it in the
    * cache Ref's `modify`.
    */
  private def diffPatches(
      renderer: Renderer,
      cache: Map[String, String],
      staticIds: List[String],
      dynamics: List[(String, DynamicDelta)],
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      uiState: Map[String, String]
  ): (Map[String, String], List[ServerSentEvent]) = {
    val rendered =
      staticIds.flatMap(id =>
        renderer.renderNodeById(id, states, uiState).map(id -> _)
      )
    val (cacheAfterStatic, staticPatches) =
      rendered.foldLeft((cache, List.empty[Patch])) {
        case ((c, acc), (id, html)) =>
          if (c.get(id).contains(html)) (c, acc)
          else (c.updated(id, html), acc :+ Patch.Morph(html))
      }
    val (finalCache, dynPatches) =
      dynamics.foldLeft((cacheAfterStatic, List.empty[Patch])) {
        case ((c, acc), (gid, delta)) =>
          val (c2, ps) =
            renderDynamicGroup(renderer, c, gid, delta, change, states, before)
          (c2, acc ++ ps)
      }
    (finalCache, (staticPatches ++ dynPatches).map(_.toSse))
  }

  /** Patch one affected dynamic group. [[DynamicDelta.InPlace]] re-renders the
    * changed entity's single child and morphs it (unless a case change actually
    * moved membership — then it falls through to the membership path). An add
    * or remove diffs the group's rendered membership before vs. after and
    * either patches the delta per-entity or repaints the whole group
    * ([[renderMembershipChange]]).
    */
  private def renderDynamicGroup(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      delta: DynamicDelta,
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState]
  ): (Map[String, String], List[Patch]) =
    delta match {
      case DynamicDelta.InPlace =>
        // The query boundary was not crossed. Normally re-render just this
        // entity's card; but a case that gained/lost this entity moves the
        // rendered membership even at a fixed query match, so reconcile against
        // the actual member lists and fall through if they differ.
        val membersBefore = renderer.dynamicMembers(gid, before)
        val membersAfter = renderer.dynamicMembers(gid, states)
        if (membersBefore == membersAfter)
          renderer.renderDynamicChild(gid, change.entityId, states) match {
            case None => (cache, Nil) // not a current member — nothing to do
            case Some(html) =>
              val cid = renderer.dynamicChildId(gid, change.entityId)
              if (cache.get(cid).contains(html)) (cache, Nil)
              else (cache.updated(cid, html), List(Patch.Morph(html)))
          }
        else
          renderMembershipChange(
            renderer,
            cache,
            gid,
            membersBefore,
            membersAfter,
            states
          )
      case DynamicDelta.Added | DynamicDelta.Removed =>
        renderMembershipChange(
          renderer,
          cache,
          gid,
          renderer.dynamicMembers(gid, before),
          renderer.dynamicMembers(gid, states),
          states
        )
    }

  /** Apply a membership change to a dynamic group. When the churn (entities
    * added + removed) is a small enough fraction of the group's rendered size
    * ([[Server.MaxChurnFraction]]) AND the group is already established in the
    * cache, patch the delta per-entity: a `remove` patch per departed member
    * and an `insert` (`before` its successor in DOM order, or `append` into the
    * group) per new member. Otherwise — heavy churn, an empty/last-member
    * group, or a group not yet in the cache (post-reload) — repaint the whole
    * group and prune its child cache entries, so a client re-establishes from a
    * known base.
    *
    * Idempotency: the per-entity path fires only for an ESTABLISHED group, so
    * the first membership change after a renderer reload (fresh cache) always
    * repaints; a `remove` of an already-absent id is a no-op (see
    * [[Datastar.remove]]). Residual race: a client that missed an `insert` in
    * the connect gap (subscribed to the shared topic just after the patch) will
    * lack that child until the next whole-group repaint — an in-place morph
    * can't heal an id absent from that client's DOM. Bounded and self-healing;
    * whole-group repaints (heavy churn / reload) re-sync every client.
    */
  private def renderMembershipChange(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      membersBefore: List[String],
      membersAfter: List[String],
      states: Map[String, EntityState]
  ): (Map[String, String], List[Patch]) = {
    val beforeSet = membersBefore.toSet
    val afterSet = membersAfter.toSet
    val added = membersAfter.filterNot(beforeSet)
    val removed = membersBefore.filterNot(afterSet)
    val churn = added.size + removed.size
    val shown = membersBefore.size
    // Per-entity pays off only when the churn is a MINORITY of the group: at the
    // boundary (e.g. 1 of 2 members, or the last member) a whole-group repaint
    // is cheaper than juggling insert/remove patches. Strict `<` so exactly half
    // repaints. `MaxChurnFraction` is tunable.
    val perEntity = churn > 0 && churn < Server.MaxChurnFraction * shown
    val established =
      cache.contains(gid) || cache.keysIterator.exists(_.startsWith(gid + "_"))
    if (!perEntity || !established) repaintGroup(renderer, cache, gid, states)
    else {
      val (afterRemoves, removePatches) =
        removed.foldLeft((cache, List.empty[Patch])) { case ((c, acc), e) =>
          val cid = renderer.dynamicChildId(gid, e)
          (c - cid, acc :+ Patch.Remove("#" + cid))
        }
      val (afterAdds, addPatches) =
        added.sorted.foldLeft((afterRemoves, List.empty[Patch])) {
          case ((c, acc), e) =>
            renderer.renderDynamicChild(gid, e, states) match {
              case None => (c, acc) // defensive: not renderable, skip
              case Some(html) =>
                val cid = renderer.dynamicChildId(gid, e)
                // Insert before the first EXISTING (pre-change) member sorting
                // after this one; if none, append into the group root.
                val patch = membersBefore.find(_.compareTo(e) > 0) match {
                  case Some(succ) =>
                    Patch.Insert(
                      html,
                      PatchMode.Before,
                      "#" + renderer.dynamicChildId(gid, succ)
                    )
                  case None =>
                    Patch.Insert(html, PatchMode.Append, "#" + gid)
                }
                (c.updated(cid, html), acc :+ patch)
            }
        }
      // Drop the stale group-level cache entry: per-entity edits diverge the DOM
      // from the last whole-group render, so a later repaint must always re-emit
      // rather than diff against an entry that no longer describes the DOM.
      (afterAdds - gid, removePatches ++ addPatches)
    }
  }

  /** Repaint a whole dynamic group by id and prune its child cache entries (so
    * the next per-entity patch re-establishes from a known base). Emits nothing
    * when the group's HTML is unchanged (the defensive path).
    */
  private def repaintGroup(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      states: Map[String, EntityState]
  ): (Map[String, String], List[Patch]) =
    renderer.renderNodeById(gid, states) match {
      case None => (cache, Nil)
      case Some(html) =>
        if (cache.get(gid).contains(html)) (cache, Nil)
        else {
          val pruned = cache.filterNot { case (k, _) =>
            k == gid || k.startsWith(gid + "_")
          }
          (pruned.updated(gid, html), List(Patch.Morph(html)))
        }
    }

  /** The per-connection SSE stream: a `conn` signal, then the slug's shared
    * main-page patches, this session's own entity-change patches (open surfaces
    * + bake-group owners), the session control channel (popup/navigate
    * patches), live-reload body repaints, and a heartbeat.
    */
  private def sseStream(slug: String, req: Request[IO]): IO[Response[IO]] =
    val uiState = Server.uiStateOf(req)
    for {
      conn <- IO.randomUUID.map(_.toString)
      session <- Session.create(slug)
      _ <- sessions.register(conn, session)
      rendererOpt <- renderers.get(slug).traverse(_.get)
      // Seed the open set with this client's selected tab panels (from its
      // cookies), so the baked-inline tabs receive live updates from the first
      // paint. Warn on any off cookie value.
      _ <- rendererOpt.traverse_ { r =>
        warnAnomalies(r, uiState) *>
          session.open.set(r.selectedSurfaces(uiState))
      }
      // On (re)connect, repaint the body from the CURRENT snapshot. On first
      // load this reconciles the tiny gap between the server-rendered page and
      // the SSE opening; on a reconnect (Datastar re-runs `data-init`) it heals
      // a DOM that went stale while the stream was down — the shared/per-session
      // passes only stream FUTURE changes, so without this a reconnected client
      // would show pre-drop values until each entity next ticks.
      initialRepaint <- rendererOpt.traverse { r =>
        stateStore.snapshot.map(st =>
          Datastar.patch(
            r.renderBody(st, uiState),
            PatchMode.Inner,
            Some("#dashboard")
          )
        )
      }
      // Per-connection heartbeat, doubling as the disconnect-detection beat: an
      // incrementing `srvBeat` signal the client watches (see [[Server.page]]).
      // It beats ONLY while the upstream feed is healthy, so a stalled beat —
      // whether from a browser-side SSE drop or a dead HA feed — trips the
      // client banner. A signal patch is also SSE traffic, so it keeps
      // intermediaries from idling the connection out.
      beatCounter <- Ref[IO].of(0L)

      // Shared main-page patches, rendered once per slug (see
      // sharedPatchPublishers). The session's slug can change mid-connection
      // (navigate), so subscribe to EVERY slug's topic and keep only the
      // current slug's events — dashboards are few. A dropped-or-duplicate
      // fragment around the navigate moment is harmless: navigate does a full
      // body repaint, and Datastar morphs are idempotent.
      shared = sharedTopics.toList
        .map { case (s, topic) =>
          topic
            .subscribe(64)
            .evalFilter(_ => session.slug.get.map(_ == s))
        }
        .reduceOption(_.merge(_))
        .getOrElse(Stream.empty)
      // What truly differs per client: open-surface nodes and bake-group-owner
      // nodes, re-rendered per state change with this session's uiState/open
      // set and diffed against its own cache.
      patches = stateStore.changes
        .evalMap(changedPatches(session, _, uiState))
        .flatMap(Stream.emits)
      control = Stream.fromQueueUnterminated(session.control)
      reloads = reloadRepaints(session, uiState)
      heartbeat = Stream
        .awakeEvery[IO](Server.BeatInterval)
        .evalMap(_ => healthy.get)
        .evalMapFilter {
          case false => IO.pure(None)
          case true =>
            beatCounter
              .updateAndGet(_ + 1)
              .map(n =>
                Some(Datastar.patchSignals(s"""{"${Server.BeatSignal}":$n}"""))
              )
        }

      stream = (Stream.emit(
        Datastar.patchSignals(s"""{"${Server.ConnSignal}":"$conn"}""")
      ) ++
        Stream.emits(initialRepaint.toList) ++
        shared.merge(patches).merge(control).merge(reloads).merge(heartbeat))
        .onFinalize(sessions.deregister(conn))
      resp <- Ok(stream)
    } yield resp

  /** Live-reload body repaints for one connection. Follows the session's
    * CURRENT dashboard: watch every renderer, but only repaint when the one
    * that reloaded is the one this connection is viewing now (it may have
    * navigated since connecting).
    */
  private def reloadRepaints(
      session: Session,
      uiState: Map[String, String]
  ): Stream[IO, ServerSentEvent] =
    renderers.toList
      .map { case (s, ref) =>
        ref.discrete.drop(1).evalMapFilter { r =>
          session.slug.get.flatMap { cur =>
            if (cur != s) IO.pure(Option.empty[ServerSentEvent])
            else
              // The repaint re-bakes the body (selected tabs included), so
              // reset the diff cache AND re-seed the open set to match. Reuses
              // this client's cookie-derived selection (closed over).
              (session.lastRendered.set(Map.empty) *>
                session.open.set(r.selectedSurfaces(uiState)) *>
                stateStore.snapshot)
                .map(st =>
                  Some(
                    Datastar
                      .patch(
                        r.renderBody(st, uiState),
                        PatchMode.Inner,
                        Some("#dashboard")
                      )
                  )
                )
          }
        }
      }
      .reduceOption(_.merge(_))
      .getOrElse(Stream.empty)

  /** Re-render the nodes a changed entity drives that are truly per-connection
    * — for each open surface, that surface's components/dynamics, plus any
    * main-page bake-group owner (its HTML bakes the client's cookie-selected
    * member, so it can't be shared) — and emit only the fragments whose HTML
    * actually changed (per-session diff). All other main-page nodes ride the
    * shared per-slug pass ([[sharedPatchPublishers]]).
    */
  private[runtime] def changedPatches(
      session: Session,
      change: StateChange,
      uiState: Map[String, String]
  ): IO[List[ServerSentEvent]] =
    for {
      slug <- session.slug.get
      renderer <- renderers.get(slug).traverse(_.get)
      states <- stateStore.snapshot
      open <- session.open.get
      out <- renderer match {
        case None    => IO.pure(List.empty[ServerSentEvent])
        case Some(r) =>
          // Static components: main-page bake-group owners binding this entity
          // (a dynamic group is never a bake owner, so main dynamics all belong
          // to the shared pass), plus each open surface's components binding it.
          val mainIds =
            r.componentsFor(change.entityId).toList.filter(r.bakeOwnerIds)
          val surfaceStaticIds = open.toList.flatMap(sid =>
            r.surfaceComponentsFor(sid, change.entityId).toList
          )
          val staticIds = (mainIds ++ surfaceStaticIds).distinct
          // Dynamic groups this change can move the entity in/out of, per open
          // surface (surface-namespaced ids never collide across surfaces).
          val dynamics =
            open.toList
              .flatMap(sid => r.affectedSurfaceDynamics(sid, change))
              .distinct
          session.lastRendered.modify(
            diffPatches(
              r,
              _,
              staticIds,
              dynamics,
              change,
              states,
              beforeSnapshot(states, change),
              uiState
            )
          )
      }
    } yield out

  /** Open (or switch to) a surface for this connection: resolve its host —
    * [[fh.view.model.Surface.hostId]] — and hand off to [[swapHost]], the
    * single open/switch/close primitive.
    */
  private def openSurface(
      session: Session,
      renderer: Renderer,
      id: String,
      uiState: Map[String, String]
  ): IO[Unit] =
    renderer.surface(id) match {
      case None => IO.unit
      case Some(surf) =>
        swapHost(session, renderer, surf.hostId, Some(id), uiState)
    }

  /** Evict whatever surface(s) currently occupy `host`, set `newSurface` as the
    * sole occupant (or none, for a close), and patch the DOM to match. Open a
    * popup / switch a tab both call this with `newSurface = Some(id)`; closing
    * a popup calls it with `None`, which patches the host to an empty `<div>` —
    * removing the transient popup dialog (a `popup` container card in the
    * surface content, not backend chrome). No server state tracks "is a popup
    * open". One host-swap primitive replaces the old open/close/stack paths.
    */
  private def swapHost(
      session: Session,
      renderer: Renderer,
      host: String,
      newSurface: Option[String],
      uiState: Map[String, String]
  ): IO[Unit] =
    for {
      // Atomic read-modify-write: two concurrent surface actions on one
      // connection must not lose each other's update. The evicted set only
      // feeds the new set, so a single `update` suffices (no `.modify`).
      _ <- session.open.update { open =>
        val evict = open.filter(sid =>
          !newSurface.contains(sid) &&
            renderer.surface(sid).exists(_.hostId == host)
        )
        (open -- evict) ++ newSurface.toSet
      }
      states <- stateStore.snapshot
      _ <- newSurface match {
        case Some(sid) =>
          renderer
            .renderSurface(sid, states, uiState)
            .traverse_(html =>
              session.control.offer(
                Datastar.patch(html, PatchMode.Inner, Some("#" + host))
              )
            )
        case None =>
          session.control.offer(
            Datastar.patch(
              s"""<div id="$host"></div>""",
              PatchMode.Outer,
              None
            )
          )
      }
    } yield ()

  /** In-place navigate: re-point the session at `slug`, reset its popups + diff
    * cache, clear the popup mount, and inner-patch the body. The URL is updated
    * client-side in the trigger expression, so this is identical for a forward
    * navigate and a Back/Forward `popstate` re-sync.
    */
  private def navigate(
      session: Session,
      slug: String,
      uiState: Map[String, String]
  ): IO[Unit] =
    renderers.get(slug) match {
      case None => IO.unit
      case Some(ref) =>
        for {
          renderer <- ref.get
          states <- stateStore.snapshot
          _ <- session.slug.set(slug)
          _ <- warnAnomalies(renderer, uiState)
          // Reset popups, but seed the target dashboard's selected tab panels
          // (its body is rendered with them baked in below).
          _ <- session.open.set(renderer.selectedSurfaces(uiState))
          _ <- session.lastRendered.set(Map.empty)
          _ <- session.control.offer(
            Datastar.patch(
              s"""<div id="${Dashboard.PopupHostId}"></div>""",
              PatchMode.Outer,
              None
            )
          )
          _ <- session.control.offer(
            Datastar.patch(
              renderer.renderBody(states, uiState),
              PatchMode.Inner,
              Some("#dashboard")
            )
          )
        } yield ()
    }

  /** Resolve the connection (`conn` rides in the POST body among Datastar
    * signals) to its session + current renderer, run `f`, and return NoContent.
    */
  private def withSession(
      req: Request[IO]
  )(
      f: (Session, Renderer, Map[String, String]) => IO[Unit]
  ): IO[Response[IO]] = {
    // The action POST carries this client's cookies, so its ui-state is read
    // here and handed to the handler — swapHost/openSurface bake the
    // cookie-selected tab, and navigate seeds the target's selection.
    val uiState = Server.uiStateOf(req)
    // Datastar sends the signals (including `conn`) as a JSON body; parse it
    // directly (no http4s-circe entity decoder dependency).
    req.bodyText.compile.string
      .map(io.circe.parser.parse(_).toOption.flatMap(connOf))
      .flatMap {
        case None => BadRequest("""{"success":false,"error":"missing conn"}""")
        case Some(conn) =>
          sessions.get(conn).flatMap {
            case None => NoContent() // stale/unknown connection
            case Some(session) =>
              session.slug.get
                .flatMap(slug => renderers.get(slug).traverse(_.get))
                .flatMap(_.traverse_(f(session, _, uiState))) *> NoContent()
          }
      }
  }

  private def connOf(body: Json): Option[String] =
    body.hcursor.get[String](Server.ConnSignal).toOption

  /** Log every bake-group anomaly [[Renderer.uiStateAnomalies]] reports for
    * this client's `uiState` (an off/hand-edited cookie). Renderer stays pure —
    * it returns the warnings, the Server logs them.
    */
  private def warnAnomalies(
      renderer: Renderer,
      uiState: Map[String, String]
  ): IO[Unit] =
    renderer.uiStateAnomalies(uiState).traverse_(w => IO.println(s"[warn] $w"))

  /** Datastar reads live updates from the persistent SSE stream, so an action
    * POST just triggers the service and returns no content.
    */
  private def callService(
      domain: String,
      service: String,
      entityId: String,
      serviceData: Json
  ): IO[Response[IO]] =
    api.callService(domain, service, entityId, serviceData).attempt.flatMap {
      case Right(_) => NoContent()
      case Left(err) =>
        BadRequest(
          Json
            .obj(
              "success" -> Json.False,
              "error" -> Json.fromString(
                Option(err.getMessage).getOrElse(err.toString)
              )
            )
            .noSpaces
        )
    }

  /** Edit-mode "debug this node": the live state of every entity a rendered
    * node binds, as a JSON array of `{ entity_id, state, attributes }`. Backs
    * the overlay the dashboard injects when embedded in the editor preview.
    * Read-only; an unknown slug is a 404, an unknown/childless node is `[]`.
    */
  private def nodeDebug(slug: String, id: String): IO[Response[IO]] =
    renderers.get(slug) match {
      case None => NotFound()
      case Some(ref) =>
        (ref.get, stateStore.snapshot).flatMapN { (renderer, states) =>
          val arr = Json.arr(renderer.entitiesForNode(id).map { e =>
            states.get(e) match {
              case Some(st) =>
                Json.obj(
                  "entity_id" -> Json.fromString(e),
                  "state" -> Json.fromString(st.state),
                  "attributes" -> Json.fromFields(st.attributes.toList)
                )
              case None =>
                Json.obj(
                  "entity_id" -> Json.fromString(e),
                  "state" -> Json.Null,
                  "attributes" -> Json.obj()
                )
            }
          }*)
          Ok(arr.noSpaces)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
        }
    }

  private def pageResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    renderers.get(slug) match {
      case None => NotFound()
      case Some(ref) =>
        val uiState = Server.uiStateOf(req)
        // The editor embeds the dashboard as `?edit=1`; that turns on the
        // per-node inspection overlay (Focus / Debug). Off for normal viewers.
        val editMode = req.uri.query.params.get("edit").contains("1")
        (ref.get, stateStore.snapshot).flatMapN { (renderer, states) =>
          warnAnomalies(renderer, uiState) *>
            Ok(
              page(
                slug,
                renderer.renderPage(states, uiState),
                renderer.stylesheets.map(assets.rewrite),
                renderer.scripts.map(assets.rewrite),
                renderer.title,
                Server.ingressPrefixOf(req),
                editMode
              )
            ).map(_.withContentType(`Content-Type`(MediaType.text.html)))
        }
    }

  /** Full HTML document wrapping the rendered dashboard. The theme owns all
    * presentation (its tokens + inline CSS travel inside the body;
    * `stylesheets` are `<link>`-ed here). `data-init` opens this dashboard's
    * SSE stream; the `popstate` handler re-syncs the in-place view to the URL
    * on Back/Forward.
    *
    * All app URLs (here and in the authored card templates) are RELATIVE and
    * resolve against the emitted `<base href>`: `/` when served directly,
    * `{X-Ingress-Path}/` behind the HA ingress proxy (which strips the prefix
    * before proxying, so routing is unaffected). Fragments arriving later over
    * the shared SSE stream therefore resolve correctly for both kinds of client
    * with no per-connection rewriting.
    */
  private def page(
      slug: String,
      body: String,
      stylesheets: List[String],
      scripts: List[String],
      title: Option[String],
      ingressPrefix: Option[String],
      editMode: Boolean = false
  ): String = {
    val links = (
      stylesheets
        .map(href => s"""  <link rel="stylesheet" href="$href">""") ++
        scripts
          .map(src => s"""  <script type="module" src="$src"></script>""")
    ).mkString("\n")
    val baseHref = ingressPrefix.fold("/")(p => s"$p/")
    // The authored per-dashboard title, or the slug when unset. Escaped for the
    // HTML `<title>` element (an authored title is untrusted text).
    val pageTitle = Server.escapeHtml(title.getOrElse(slug))
    // On Back/Forward, derive the slug from the URL and re-post the swap (no
    // pushState — the browser already moved). `/d/<slug>` or `/` -> default.
    // The split works under any ingress prefix too.
    val popstate =
      s"@post('sse/navigate/' + (window.location.pathname.split('/d/')[1] || '$defaultSlug'))"
    // Edit-mode overlay (Focus / Debug per node), injected only when the editor
    // embeds this page with `?edit=1`. The config carries the slug + base so the
    // overlay can call the node-debug endpoint and message the parent editor.
    val editAssets =
      if (!editMode) ""
      else
        s"""<link rel="stylesheet" href="edit/overlay.css">
           |<script>window.__FH_EDIT__={"slug":"$slug","base":"$baseHref"};</script>
           |<script src="edit/overlay.js"></script>""".stripMargin
    // Connection watchdog + disconnect banner. A `<body>` child OUTSIDE
    // `#dashboard`, so body morphs never remove it and its signals persist. The
    // server beats `srvBeat` every couple of seconds while the feed is healthy
    // (see `sseStream`); this interval checks, every 5s, whether a fresh beat
    // arrived since the last check. If not — the SSE dropped (browser side) or
    // the upstream HA feed froze (no beats) — `_online` goes false and the bar
    // shows. It re-hides itself the moment beats resume (Datastar auto-retries
    // the `@get` SSE, and `sseStream` re-emits from a fresh connection). Built
    // from documented primitives only (`data-signals`, `data-on-interval`,
    // `data-show`); inline-styled so it never depends on the active theme.
    val connBanner =
      s"""<div data-signals="{${Server.BeatSignal}: 0, _online: true, _lastBeat: -1}"
         |     data-on-interval="5000; _online = (${Server.BeatSignal} !== _lastBeat); _lastBeat = ${Server.BeatSignal}">
         |  <div data-show="!_online" role="status" aria-live="polite" style="position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#b00020;color:#fff;text-align:center;padding:6px 12px;font:600 14px/1.4 system-ui,-apple-system,sans-serif;box-shadow:0 1px 4px rgba(0,0,0,.4)">Disconnected — reconnecting…</div>
         |</div>""".stripMargin
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <base href="$baseHref">
       |  <title>$pageTitle</title>
       |$links
       |  <script type="module" src="${assets.rewrite(
        Server.DatastarCdn
      )}"></script>
       |</head>
       |<body data-init="@get('sse/dashboard/$slug/patch')" data-on:popstate__window="$popstate">
       |$connBanner
       |$body
       |$editAssets
       |</body>
       |</html>
       |""".stripMargin
  }
}

object Server {

  /** Build the server with one shared-patch topic per slug and run the per-slug
    * publishers ([[Server.sharedPatchPublishers]]) for the life of the
    * resource. The single construction point (ServerApp and tests) so the
    * shared fan-out is never accidentally left un-driven.
    */
  def resource(
      api: HomeAssistantApi[IO],
      stateStore: StateStore,
      renderers: Map[String, SignallingRef[IO, Renderer]],
      defaultSlug: String,
      sessions: Sessions,
      assets: AssetCache = AssetCache.empty,
      healthy: Signal[IO, Boolean] = Signal.constant(true)
  ): Resource[IO, Server] =
    for {
      topics <- renderers.keySet.toList
        .traverse(slug => Topic[IO, ServerSentEvent].tupleLeft(slug))
        .map(_.toMap)
        .toResource
      server = new Server(
        api,
        stateStore,
        renderers,
        defaultSlug,
        sessions,
        topics,
        assets,
        healthy
      )
      _ <- server.sharedPatchPublishers.compile.drain.background
    } yield server

  /** The largest fraction of a dynamic group's rendered members that may churn
    * (be added and/or removed by one state change) and still be patched
    * per-entity (`remove` / `insert`); beyond it the whole group repaints. The
    * comparison is strict (`churn < MaxChurnFraction * shown`), so exactly-half
    * churn — e.g. removing 1 of 2 members, or the last member — repaints, while
    * removing 1 of 4 patches per-entity. Tunable: raise it to favour per-entity
    * patches (smaller payloads, more patches), lower it to favour repaints.
    */
  val MaxChurnFraction: Double = 0.5

  /** The client's UI state read off request cookies: every `fhui_<id>` cookie
    * mapped to `id -> rawValue` (the `fhui_` prefix dropped). The value is left
    * opaque here — interpretation and the untrusted-value clamp live in
    * [[Renderer.resolveActive]], so a stale/hand-edited cookie can never bake a
    * non-existent surface. Empty when no `fhui_` cookies are present.
    */
  def uiStateOf(req: Request[IO]): Map[String, String] =
    req.cookies.collect {
      case c if c.name.startsWith(UiCookiePrefix) =>
        c.name.drop(UiCookiePrefix.length) -> c.content
    }.toMap

  /** Cookie-name prefix for the client's UI state (per-group tab index). Must
    * match the cookie name the authoring layer's tab click writes — see ADR
    * 0005. Stripped in [[uiStateOf]] to key [[Renderer.resolveActive]].
    */
  val UiCookiePrefix: String = "fhui_"

  /** The ingress path prefix the HA supervisor proxy announces via
    * `X-Ingress-Path` (e.g. `/api/hassio_ingress/<token>`), used as the page's
    * `<base href>`. The header is attacker-suppliable on the direct port and
    * the value lands in HTML, so anything but a strict absolute path of safe
    * characters is ignored (never escaped-and-trusted).
    */
  def ingressPrefixOf(req: Request[IO]): Option[String] =
    req.headers
      .get(org.typelevel.ci.CIString("X-Ingress-Path"))
      .map(_.head.value)
      .filter(IngressPathPattern.matches)

  /** Absolute path, safe chars only, no trailing slash, no `..` (excluded by
    * the character class rejecting `.`).
    */
  private val IngressPathPattern: scala.util.matching.Regex =
    "^(/[A-Za-z0-9_-]+)+$".r

  /** The Datastar signal name carrying the per-connection `conn` id: minted on
    * SSE connect (the initial patch-signals event) and echoed back in each
    * action POST body (`connOf`) so a POST correlates to its stream.
    */
  val ConnSignal: String = "conn"

  /** The Datastar signal name carrying the server heartbeat: a counter the SSE
    * stream increments every [[BeatInterval]] while the upstream feed is
    * healthy. The client watches it to detect a stalled connection (see
    * [[Server.page]]); it must beat faster than the client's check window.
    */
  val BeatSignal: String = "srvBeat"

  /** How often the SSE stream emits a [[BeatSignal]] tick. Kept well under the
    * client's disconnect check interval so a healthy connection always shows
    * fresh beats.
    */
  val BeatInterval: FiniteDuration = 2.seconds

  /** Datastar client bundle. Pinned — verify against current Datastar docs when
    * upgrading (SSE event names / `data-*` attribute syntax change across
    * releases).
    */
  val DatastarCdn: String =
    "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"

  /** Escape a string for interpolation into HTML text/attribute content (the
    * page `<title>`). Ampersand first so the entity replacements aren't
    * double-escaped.
    */
  def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  /** Parse a URL-path action value into the most specific JSON type (int, then
    * double, else string) so HA receives `brightness: 128` rather than `"128"`.
    */
  def parseValue(raw: String): Json =
    raw.toIntOption
      .map(Json.fromInt)
      .orElse(raw.toDoubleOption.flatMap(Json.fromDouble))
      .getOrElse(Json.fromString(raw))
}
