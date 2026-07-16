package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.build.{DashboardBuild, SystemPkl}
import fh.view.model.Dashboard
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{
  `Cache-Control`,
  `Content-Type`,
  `If-None-Match`,
  ETag
}
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
  * Live entity patches are split by what they depend on. Main-page nodes whose
  * HTML is a pure function of entity state — including STATE-selected bake
  * groups (If/else hosts and their active branches, whose selection is server
  * truth) — are rendered ONCE per slug by [[sharedPatchPublishers]] (one
  * subscription to the state stream per dashboard, per-slug diff cache) and
  * fanned out to every connection viewing that slug over `sharedTopic`. Only
  * what truly differs per client stays per-session in [[changedPatches]]:
  * open-surface nodes and USER bake-group-owner nodes (their HTML depends on
  * the client's `uiState`), plus the state groups those pull in (nested in an
  * open popup, or with a user owner in a branch). Construct via
  * [[Server.resource]], which creates the topic and runs the publishers.
  *
  * The slug set is NOT fixed at startup: [[push]] installs a pre-evaluated
  * dashboard at runtime (ADR 0010), which is why the registry is a `Ref` and
  * the shared fan-out is one multiplexed topic rather than a map of them.
  */
class Server(
    api: HomeAssistantApi[IO],
    stateStore: StateStore,
    // One hot-swappable renderer per dashboard slug (live reload swaps in place;
    // `.discrete` drives a body repaint over SSE). A `Ref` because the slug set
    // is not fixed at startup: `push` mints one at runtime (ADR 0010).
    renderers: Ref[IO, Map[String, SignallingRef[IO, Renderer]]],
    defaultSlug: String,
    sessions: Sessions,
    // Fan-out of the shared main-page patches, fed by the per-slug publishers
    // and tagged with the slug they came from; every connection subscribes ONCE
    // and keeps only its current slug's events.
    //
    // Why one multiplexed topic rather than a topic per slug: a connection
    // subscribes when it opens, so a per-slug map would freeze the slug set at
    // connect time and a slug pushed later could never reach an open
    // connection. Tagging is what lets `push` mint a slug at runtime.
    sharedTopic: Topic[IO, (String, ServerSentEvent)],
    // Starts the per-slug shared-patch publisher for a slug minted by `push`.
    // Scoped to `Server.resource`, so those fibers die with the server.
    supervisor: Supervisor[IO],
    // Local cache of the themes' external assets ([[AssetCache]]): page URLs
    // are rewritten through it and `/assets/:name` serves from it. The empty
    // default (pass-through, no local assets) keeps tests ceremony-free.
    assets: AssetCache = AssetCache.empty,
    // The live home's Pkl artifacts (schema + dump) served over `/system/pkl/`
    // for pkl-lsp / the editor / remote authors. The empty default serves
    // nothing (404) — the server's own eval uses in-memory interception, not
    // this route.
    systemPkl: SystemPkl = SystemPkl.empty
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root              => pageResponse(defaultSlug, req)
    case req @ GET -> Root / "d" / slug => pageResponse(slug, req)

    // Locally cached theme assets (stylesheets/scripts/fonts); a name that
    // isn't cached is a 404 — the page then references the original URL.
    case GET -> Root / "assets" / name => assets.serve(name)

    // The live home's Pkl artifacts — the domain schema + the freshly-rendered
    // per-home dump — as source text. pkl-lsp (behind the `/edit` editor) and
    // remote authors fetch these to resolve `import
    // "http://<home>/system/pkl/{hass,dump}.pkl"`; the server's own eval never
    // hits this route (it resolves those imports via in-memory interception).
    // The package-discovery index (before the `:name` route, which would
    // otherwise swallow the 3-segment path as `name = "packages"`): current
    // versions + metadata sha256 of the packages this home serves — what
    // `fh pull` reads before rewriting the laptop's pins.
    case GET -> Root / "system" / "pkl" / "packages" =>
      systemPkl.packagesIndex match {
        case Some(json) =>
          Ok(json).map(
            _.putHeaders(`Content-Type`(MediaType.application.json))
          )
        case None => NotFound()
      }

    case req @ GET -> Root / "system" / "pkl" / name =>
      systemPkl.module(name) match {
        case Some(text) => systemPklResponse(text, req)
        case None       => NotFound()
      }

    // The instance's resolved lib packages (ADR 0010): the metadata JSON at
    // `<name>@<version>`, the module zip at `<name>@<version>.zip` — exactly
    // pkl's remote-package protocol, so a laptop workspace resolves
    // `package://fh.invalid/fh-dashboard@<v>` from this instance with one
    // `http.rewrites` line (`https://fh.invalid/` → `http://<home>/system/pkl/
    // packages/`), landing on the same sha256-pinned artifacts the instance
    // itself evaluates. No cache headers: pkl fetches per resolve, and a
    // proxy-cached zip would turn the dev-image drift case (lib bytes changed
    // under an unchanged version) into a confusing stale-checksum failure.
    case GET -> Root / "system" / "pkl" / "packages" / file =>
      systemPkl.packageArtifact(file) match {
        case Some(bytes) =>
          val mediaType =
            if (file.endsWith(".zip")) MediaType.application.zip
            else MediaType.application.json
          Ok(bytes).map(_.putHeaders(`Content-Type`(mediaType)))
        case None => NotFound()
      }

    // Edit-mode node inspection ("debug this node"): the live entity state of
    // every entity a rendered node binds. Read-only; used by the overlay the
    // dashboard injects when embedded in the editor preview (`?edit=1`).
    case GET -> Root / "edit" / "node" / slug / id / "debug" =>
      nodeDebug(slug, id)

    // Install a pre-evaluated dashboard under `slug`, live (ADR 0010, persona
    // 4). The body is the SAME `{cards, card}` wire JSON the Pkl layer emits —
    // pushing simply skips that layer, which is why a component developer can
    // ship cards this server has no source for.
    //
    // NOTE — unauthenticated, deliberately, matching the port it rides: the
    // direct port is documented as unauthenticated and the server already
    // drives Home Assistant with its own token, so anyone who can reach this
    // can already control every device. It is nonetheless a WRITE: when auth
    // lands for the direct port it must cover this route.
    case req @ POST -> Root / "system" / "push" / slug =>
      pushResponse(slug, req)

    case req @ GET -> Root / "sse" / "dashboard" / slug / "patch" =>
      renderers.get.flatMap { rs =>
        if (rs.contains(slug)) sseStream(slug, req) else NotFound()
      }

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
    * renders. Only nodes whose HTML is a pure function of entity state qualify:
    * USER bake-group owners (uiState-dependent) are excluded and stay
    * per-session ([[changedPatches]]); STATE-selected groups qualify and are
    * handled here — selection flips and active-branch liveness included (see
    * [[sharedPatches]]).
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
  /** The current renderer for `slug`, or `None` if no such dashboard is
    * registered. Reads through the registry `Ref`, so it sees slugs pushed
    * after startup.
    */
  private def rendererFor(slug: String): IO[Option[Renderer]] =
    renderers.get.flatMap(_.get(slug).traverse(_.get))

  /** The shared render/diff loop for ONE slug: one subscription to the state
    * stream, one diff cache, publishing slug-tagged patches to [[sharedTopic]].
    * Started once per slug — at startup by [[Server.resource]], or on demand by
    * [[push]] for a slug minted at runtime.
    */
  private def publisherFor(
      slug: String,
      ref: SignallingRef[IO, Renderer]
  ): Stream[IO, Nothing] =
    ref.discrete
      .switchMap { renderer =>
        Stream.eval(Ref[IO].of(Map.empty[String, String])).flatMap { cache =>
          stateStore.changes
            .evalMap(sharedPatches(renderer, cache, _))
            .flatMap(Stream.emits)
        }
      }
      .map(sse => (slug, sse))
      .through(sharedTopic.publish)

  /** Start every currently-registered slug's publisher. Slugs pushed later get
    * theirs from [[push]] via the supervisor.
    */
  def sharedPatchPublishers: Stream[IO, Nothing] =
    Stream
      .eval(renderers.get)
      .flatMap { rs =>
        rs.toList
          .map { case (slug, ref) => publisherFor(slug, ref) }
          .foldLeft(Stream.empty.covaryAll[IO, Nothing])(_.merge(_))
      }

  /** Current number of subscribers on the shared-patch topic, as a signal
    * stream — a test seam (mirroring [[StateStore.changeSubscribers]]) to await
    * an SSE connection's shared subscription before emitting a change, since
    * the topic only reaches already-subscribed consumers.
    *
    * Not per-slug: one multiplexed topic means one subscription per connection,
    * whatever it is viewing.
    */
  private[runtime] def sharedSubscribers: Stream[IO, Int] =
    sharedTopic.subscribers

  /** Install `dashboard` under its slug, live, without evaluating any Pkl — the
    * component-developer story (ADR 0010, persona 4): they author cards the
    * server holds no source for, evaluate on their laptop, and push the RESULT.
    * Viable only because the wire model is self-contained (every card carries
    * its own template), so this needs nothing but the JSON.
    *
    * An EXISTING slug reuses its `SignallingRef` — setting it repaints open
    * connections exactly as live reload does, which is the push/look/edit loop.
    * A NEW slug also needs its publisher started, hence the supervisor.
    *
    * Ephemeral by design: this touches no file, so a restart returns the
    * instance to its on-disk dashboards, and the file watcher's next reconcile
    * reclaims a slug that shadows a real entry.
    */
  def push(dashboard: Dashboard): IO[Unit] =
    SignallingRef[IO].of(Renderer.create(dashboard)).flatMap { fresh =>
      renderers
        .modify { rs =>
          rs.get(dashboard.slug) match {
            case Some(existing) => (rs, Some(existing))
            case None           => (rs + (dashboard.slug -> fresh), None)
          }
        }
        .flatMap {
          case Some(existing) => existing.set(Renderer.create(dashboard))
          case None           =>
            supervisor
              .supervise(publisherFor(dashboard.slug, fresh).compile.drain)
              .void
        }
    }

  /** The shared per-slug render/diff for one state change: the affected
    * main-page static components (reverse index, minus the USER bake-group
    * owners), the query-affected dynamic groups, plus everything state-selected
    * surfaces contribute — all rendered against the current snapshot and diffed
    * against the slug's shared cache. Returns the SSE patches — child-scoped
    * for a dynamic member update, per-entity insert/remove for a small
    * membership delta, a whole-group morph otherwise (see [[diffPatches]]). No
    * `uiState`: by construction these nodes don't read it.
    *
    * The state-selected extension (ADR 0002's shared/per-session split, cut by
    * activation mode):
    *
    *   - '''Flips''': each state group whose selection this change moves
    *     ([[Renderer.affectedStateGroups]], main-rooted; minus the session-only
    *     ones, whose branch HTML bakes a cookie-selected member and therefore
    *     rides [[changedPatches]]) gets its HOST re-rendered — [[Renderer]]'s
    *     bake picks the newly-selected member against CURRENT state — morphed,
    *     and its members' cache entries pruned ([[flipStateGroup]]).
    *   - '''Active-member liveness''': for each surface in the main-rooted
    *     transitive active set ([[Renderer.activeStateSurfaces]], excluding
    *     just-flipped subtrees — their host morph re-rendered them wholesale —
    *     and session-only subtrees) patch its components binding the changed
    *     entity plus its query-affected dynamics. Inactive members are never
    *     consulted — that IS the hidden-branch no-updates guarantee, and it is
    *     structural: their ids simply never enter the patch set.
    */
  private[runtime] def sharedPatches(
      renderer: Renderer,
      cache: Ref[IO, Map[String, String]],
      change: StateChange
  ): IO[List[ServerSentEvent]] =
    stateStore.snapshot.flatMap { states =>
      val before = beforeSnapshot(states, change)
      val flips = renderer
        .affectedStateGroups(change, before, states)
        .filterNot(renderer.sessionOnlyStateGroups)
      val flipped = flips.toSet
      val activeSids = renderer.activeStateSurfaces(
        states,
        excluding = flipped ++ renderer.sessionOnlyStateGroups
      )
      val staticIds =
        (renderer
          .componentsFor(change.entityId)
          .toList
          // User owners bake a cookie-selected member (per-session); a
          // session-only state owner bakes one transitively (its branch holds
          // tabs). State owners otherwise stay in the shared pass — selection
          // included, their HTML is a pure function of entity state.
          .filterNot(id =>
            renderer.userBakeOwnerIds(id) ||
              renderer.sessionOnlyStateGroups(id)
          ) ++
          activeSids.toList.flatMap(sid =>
            renderer.surfaceComponentsFor(sid, change.entityId).toList
          )).distinct
          // A flipped host is patched (with prune) by the flip path; don't
          // also morph it as a plain static.
          .filterNot(flipped)
      val dynamics =
        renderer.affectedDynamics(change) ++
          activeSids.toList.flatMap(sid =>
            renderer.affectedSurfaceDynamics(sid, change)
          )
      cache.modify(
        diffPatches(
          renderer,
          _,
          staticIds,
          dynamics,
          flips,
          change,
          states,
          before,
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

  /** Diff a set of static component ids + a set of affected dynamic groups + a
    * set of flipped state groups against `cache`, returning the updated cache
    * and the SSE patches to emit. The single diff contract shared by the
    * per-slug ([[sharedPatches]]) and per-session ([[changedPatches]]) passes.
    *
    *   - A flipped state group morphs its HOST (the newly-selected member baked
    *     against current state) and prunes its members' cache entries
    *     ([[flipStateGroup]]). Flips run FIRST: the prune must precede any diff
    *     that could suppress a member fragment against a pre-flip entry.
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
      flips: List[String],
      change: StateChange,
      states: Map[String, EntityState],
      before: Map[String, EntityState],
      uiState: Map[String, String]
  ): (Map[String, String], List[ServerSentEvent]) = {
    val (cacheAfterFlips, flipPatches) =
      flips.foldLeft((cache, List.empty[Patch])) { case ((c, acc), gid) =>
        val (c2, ps) = flipStateGroup(renderer, c, gid, states, uiState)
        (c2, acc ++ ps)
      }
    val rendered =
      staticIds.flatMap(id =>
        renderer.renderNodeById(id, states, uiState).map(id -> _)
      )
    val (cacheAfterStatic, staticPatches) =
      rendered.foldLeft((cacheAfterFlips, List.empty[Patch])) {
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
    (finalCache, (flipPatches ++ staticPatches ++ dynPatches).map(_.toSse))
  }

  /** Patch one FLIPPED state-selected bake group: re-render its host node — the
    * bake owner, whose render bakes the newly-selected member against CURRENT
    * state ([[Renderer]]'s `resolveBake`) — morph it, and prune the group's
    * cache entries: the host id plus every member's `s_<sid>__` node prefix.
    * The same prune contract as [[repaintGroup]], and for the same reason:
    * hidden-branch churn deliberately leaves member entries stale (the silence
    * guarantee), so a flip must drop them — otherwise a re-revealed node whose
    * HTML happens to equal its pre-flip entry would be suppressed while the
    * client's DOM (repainted by this very morph) has moved on. Emits nothing
    * when the host HTML is unchanged (defensive; the caller only passes groups
    * whose selection actually moved).
    */
  private def flipStateGroup(
      renderer: Renderer,
      cache: Map[String, String],
      gid: String,
      states: Map[String, EntityState],
      uiState: Map[String, String]
  ): (Map[String, String], List[Patch]) =
    renderer.renderNodeById(gid, states, uiState) match {
      case None       => (cache, Nil)
      case Some(html) =>
        if (cache.get(gid).contains(html)) (cache, Nil)
        else {
          val prefixes = renderer.bakeMemberPrefixes(gid)
          val pruned = cache.filterNot { case (k, _) =>
            k == gid || prefixes.exists(k.startsWith)
          }
          (pruned.updated(gid, html), List(Patch.Morph(html)))
        }
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
              case None       => (c, acc) // defensive: not renderable, skip
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
      case None       => (cache, Nil)
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
      // Seed the open set with this client's selected tab panels (from its
      // cookies), so the baked-inline tabs receive live updates from the first
      // paint. Warn on any off cookie value.
      _ <- rendererFor(slug).flatMap(_.traverse_ { r =>
        warnAnomalies(r, uiState) *>
          session.open.set(r.selectedSurfaces(uiState))
      })

      // Shared main-page patches, rendered once per slug (see
      // sharedPatchPublishers). The session's slug can change mid-connection
      // (navigate), so keep only the current slug's events — the filter is read
      // per event, not fixed at connect, so it follows a navigate. A
      // dropped-or-duplicate fragment around the navigate moment is harmless:
      // navigate does a full body repaint, and Datastar morphs are idempotent.
      //
      // One subscription to the multiplexed topic, so a slug that did not exist
      // when this connection opened (pushed since) still reaches it.
      shared = sharedTopic
        .subscribe(64)
        .evalFilter { case (s, _) => session.slug.get.map(_ == s) }
        .map { case (_, sse) => sse }
      // What truly differs per client: open-surface nodes and user
      // bake-group-owner nodes (plus the state groups those pull in),
      // re-rendered per state change with this session's uiState/open set and
      // diffed against its own cache.
      patches = stateStore.changes
        .evalMap(changedPatches(session, _, uiState))
        .flatMap(Stream.emits)
      control = Stream.fromQueueUnterminated(session.control)
      reloads = reloadRepaints(session, uiState)
      heartbeat = Stream
        .awakeEvery[IO](15.seconds)
        .as(ServerSentEvent(data = None, comment = Some("keep-alive")))

      stream = (Stream.emit(
        Datastar.patchSignals(s"""{"${Server.ConnSignal}":"$conn"}""")
      ) ++
        shared.merge(patches).merge(control).merge(reloads).merge(heartbeat))
        .onFinalize(sessions.deregister(conn))
      resp <- Ok(stream)
    } yield resp

  /** Live-reload body repaints for one connection. Follows the session's
    * CURRENT dashboard: watch every renderer, but only repaint when the one
    * that reloaded is the one this connection is viewing now (it may have
    * navigated since connecting).
    *
    * The watched set is the registry as it stands when this connection opens. A
    * slug pushed LATER is therefore not watched by this connection — it still
    * receives that slug's shared entity patches (the topic is multiplexed) and
    * renders it correctly on navigate, but a re-push of it would not repaint
    * here until the page is reloaded. The push/look/edit loop is unaffected,
    * since the slug exists before the developer opens it.
    */
  private def reloadRepaints(
      session: Session,
      uiState: Map[String, String]
  ): Stream[IO, ServerSentEvent] =
    Stream
      .eval(renderers.get)
      .flatMap(rs => Stream.emits(rs.toList))
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
      .parJoinUnbounded

  /** Re-render the nodes a changed entity drives that are truly per-connection
    * — for each open surface, that surface's components/dynamics, plus any
    * main-page USER bake-group owner (its HTML bakes the client's
    * cookie-selected member, so it can't be shared) — and emit only the
    * fragments whose HTML actually changed (per-session diff). All other
    * main-page nodes ride the shared per-slug pass ([[sharedPatchPublishers]]).
    *
    * State-selected surfaces are shared by default, but two shapes are
    * per-session by nature and mirrored here (the counterpart of
    * [[sharedPatches]]'s exclusions):
    *
    *   - a state group nested INSIDE an open surface (a popup only this session
    *     has open) — per-session by containment: its flips and its active
    *     member's liveness ride this session's diff cache;
    *   - a [[Renderer.sessionOnlyStateGroups]] group (a user-selected owner
    *     somewhere in a branch): its host morph bakes THIS session's
    *     cookie-selected member, so its flips — and its active subtree's
    *     liveness, which the shared pass skipped — render here with the
    *     session's `uiState`.
    */
  private[runtime] def changedPatches(
      session: Session,
      change: StateChange,
      uiState: Map[String, String]
  ): IO[List[ServerSentEvent]] =
    for {
      slug <- session.slug.get
      renderer <- rendererFor(slug)
      states <- stateStore.snapshot
      open <- session.open.get
      out <- renderer match {
        case None    => IO.pure(List.empty[ServerSentEvent])
        case Some(r) =>
          val before = beforeSnapshot(states, change)
          // State-group flips this session must patch itself: groups inside
          // its open surfaces (containment), plus the main-rooted session-only
          // ones (rendered with this session's uiState).
          val openFlips = open.toList.flatMap(sid =>
            r.affectedStateGroupsIn(sid, change, before, states)
          )
          val sessionOnlyFlips = r
            .affectedStateGroups(change, before, states)
            .filter(r.sessionOnlyStateGroups)
          val flips = (openFlips ++ sessionOnlyFlips).distinct
          val flipped = flips.toSet
          // Active state members visible only to this session: those nested
          // inside its open surfaces, plus the main-rooted subtrees the shared
          // pass skipped as session-only (all-active minus shared-active is
          // exactly those). Just-flipped subtrees are excluded — the flip's
          // host morph re-renders them wholesale.
          val openNested = open.toList.flatMap(sid =>
            r.activeStateSurfacesIn(sid, states, flipped).toList
          )
          val sessionOnlySids =
            if (r.sessionOnlyStateGroups.isEmpty) Set.empty[String]
            else
              r.activeStateSurfaces(states, flipped) --
                r.activeStateSurfaces(
                  states,
                  flipped ++ r.sessionOnlyStateGroups
                )
          val sids = (open.toList ++ openNested ++ sessionOnlySids).distinct
          // Static components: main-page owners whose bake is per-session
          // (user-selected, or state-selected with a user owner in a branch)
          // binding this entity (a dynamic group is never a bake owner, so
          // main dynamics all belong to the shared pass), plus each visible
          // surface's components binding it.
          val mainIds =
            r.componentsFor(change.entityId)
              .toList
              .filter(id =>
                r.userBakeOwnerIds(id) || r.sessionOnlyStateGroups(id)
              )
          val surfaceStaticIds = sids.flatMap(sid =>
            r.surfaceComponentsFor(sid, change.entityId).toList
          )
          val staticIds =
            (mainIds ++ surfaceStaticIds).distinct.filterNot(flipped)
          // Dynamic groups this change can move the entity in/out of, per
          // visible surface (surface-namespaced ids never collide across
          // surfaces).
          val dynamics =
            sids.flatMap(sid => r.affectedSurfaceDynamics(sid, change)).distinct
          session.lastRendered.modify(
            diffPatches(
              r,
              _,
              staticIds,
              dynamics,
              flips,
              change,
              states,
              before,
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
      case None       => IO.unit
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
    rendererFor(slug).flatMap {
      case None           => IO.unit
      case Some(renderer) =>
        for {
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
            case None          => NoContent() // stale/unknown connection
            case Some(session) =>
              session.slug.get
                .flatMap(rendererFor)
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
      case Right(_)  => NoContent()
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
    rendererFor(slug).flatMap {
      case None           => NotFound()
      case Some(renderer) =>
        stateStore.snapshot.flatMap { states =>
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

  /** Serve one `/system/pkl/` artifact as `text/plain`, with `no-cache` + an
    * `ETag` (and a `304` for a matching `If-None-Match`).
    *
    * **`no-cache` is the load-bearing header, not the ETag.** `dump.pkl` is
    * this home's LIVE entity dump: it is rewritten whenever the home's registry
    * changes, under a URL that never changes. Anything that stores it — a
    * browser, a proxy on the split-horizon remote path
    * (`docs/pwa-remote-access.md`) — would hand an author completions for
    * devices they no longer own, with no way to tell. `no-cache` does not
    * forbid storing; it forbids REUSING without revalidating, which is exactly
    * the contract we want: cheap when unchanged, never silently stale.
    *
    * **The ETag is for clients that revalidate — which today is none of them.**
    * pkl is the primary consumer and it does no conditional requests at all:
    * pkl-core 0.31.1 contains no `If-None-Match`/`ETag`/`Cache-Control`
    * handling anywhere (verified against the jar), so its module reader
    * unconditionally GETs the full body and its only caching is the
    * per-evaluator in-memory module cache, keyed by resolved URI, which never
    * consults an HTTP validator. So the ETag is dead weight *for pkl* — it is
    * here for the consumers that do revalidate: browser/editor JS fetching the
    * dump, and any remote tooling that wants to ask "did this home's entity set
    * change?" without pulling a ~450KB dump every time. Hashing that dump per
    * request is trivial next to shipping it, and this route is hit at
    * editor-session start, never on the live hot path.
    *
    * The ETag is a strong validator over the exact bytes served, so it is
    * correct by construction: same text ⇒ same tag.
    */
  /** Decode a pushed dashboard and install it live under `slug`.
    *
    * The body goes through the SAME [[DashboardBuild.decode]] as the server's
    * own eval path, so a push is validated identically — an unknown card
    * reference or an uncompilable slot transform is rejected rather than
    * installed. That matters more here than on the eval path: the pushing
    * developer has no server logs, so the failure has to come back on the wire.
    * Hence 400 + the validation message, which is the CLI's error output.
    *
    * The slug comes from the URL, not the body: it is the address the developer
    * asked for, and forcing it keeps `/d/<slug>` and the registry key in step
    * (the same `copy(slug = ...)` the eval path applies at decode time).
    */
  private def pushResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    req.bodyText.compile.string
      .map(io.circe.parser.parse)
      .flatMap {
        case Left(err) =>
          BadRequest(s"push body is not JSON: ${err.getMessage}")
        case Right(json) =>
          DashboardBuild
            .decode(json)
            .map(_.copy(slug = slug))
            .flatMap(d => push(d).as(d))
            .flatMap(d => Ok(s"pushed ${d.slug} (${d.cards.size} cards)"))
            .handleErrorWith(err => BadRequest(err.getMessage))
      }

  private def systemPklResponse(
      text: String,
      req: Request[IO]
  ): IO[Response[IO]] = {
    val etag = EntityTag(systemPklHash(text))
    val fresh = req.headers
      .get[`If-None-Match`]
      .exists {
        // A bare `If-None-Match: *` matches any existing representation.
        case `If-None-Match`(None)       => true
        case `If-None-Match`(Some(tags)) =>
          tags.exists(t => t.tag == etag.tag)
      }
    val cacheHeaders =
      (ETag(etag), `Cache-Control`(CacheDirective.`no-cache`()))
    if (fresh) NotModified().map(_.putHeaders(cacheHeaders._1, cacheHeaders._2))
    else
      Ok(text).map(
        _.withContentType(`Content-Type`(MediaType.text.plain))
          .putHeaders(cacheHeaders._1, cacheHeaders._2)
      )
  }

  /** Strong ETag payload: a hex SHA-256 of the served source text. Not a
    * security boundary — just a stable content fingerprint — but SHA-256 keeps
    * it collision-free in practice, so a changed dump can never reuse a tag.
    */
  private def systemPklHash(text: String): String = {
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.iterator.map(b => f"${b & 0xff}%02x").mkString
  }

  private def pageResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    rendererFor(slug).flatMap {
      case None           => NotFound()
      case Some(renderer) =>
        val uiState = Server.uiStateOf(req)
        // The editor embeds the dashboard as `?edit=1`; that turns on the
        // per-node inspection overlay (Focus / Debug). Off for normal viewers.
        val editMode = req.uri.query.params.get("edit").contains("1")
        stateStore.snapshot.flatMap { states =>
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
       |$body
       |$editAssets
       |</body>
       |</html>
       |""".stripMargin
  }
}

object Server {

  /** Build the server with the shared-patch topic and run the per-slug
    * publishers ([[Server.sharedPatchPublishers]]) for the life of the
    * resource. The single construction point (ServerApp and tests) so the
    * shared fan-out is never accidentally left un-driven.
    *
    * `renderers` seeds the registry; it is not the final word — [[Server.push]]
    * adds to it at runtime, and the supervisor here owns the publishers those
    * pushed slugs start, so they end with the resource like the seeded ones.
    */
  def resource(
      api: HomeAssistantApi[IO],
      stateStore: StateStore,
      renderers: Map[String, SignallingRef[IO, Renderer]],
      defaultSlug: String,
      sessions: Sessions,
      assets: AssetCache = AssetCache.empty,
      systemPkl: SystemPkl = SystemPkl.empty
  ): Resource[IO, Server] =
    for {
      topic <- Topic[IO, (String, ServerSentEvent)].toResource
      registry <- Ref[IO].of(renderers).toResource
      supervisor <- Supervisor[IO]
      server = new Server(
        api,
        stateStore,
        registry,
        defaultSlug,
        sessions,
        topic,
        supervisor,
        assets,
        systemPkl
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
