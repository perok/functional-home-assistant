package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.build.{
  AddonBootstrap,
  DashboardBuild,
  DumpRefresh,
  LibPackage,
  SystemPkl
}
import fh.view.FHError
import fh.view.model.{Dashboard, DomId, NodeId}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef, Topic}
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

import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.duration.*

/** HTTP surface for the dashboards.
  *
  *   - `GET /` the default dashboard; `GET /d/:slug` a specific one.
  *   - `GET /sse/dashboard/:slug/patch` the per-connection live stream of
  *     `datastar-patch-elements` fragments. On connect it mints a `conn` id and
  *     pushes it as a signal, so action POSTs can correlate to this stream.
  *   - `POST /sse/action/:domain/:service/:id[/:k/:v]` call a HA service.
  *   - `POST /sse/surface/open/:id` open a surface (popup or tab panel);
  *     `POST /sse/popup/close` close the (at most one) open popup. Open,
  *     switch, and close are all the same host-swap ([[swapHost]]) — evict
  *     whatever occupies the surface's host, patch the new occupant in (or
  *     patch it empty, for a close). The state lives in the connection's
  *     [[Session]]; the resulting patches ride the same SSE stream. Going to
  *     ANOTHER dashboard is not a route here — it is an ordinary document load
  *     of `/d/:slug` (ADR 0002).
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
    // One hot-swappable renderer per dashboard slug, paired with that slug's
    // fragment log (live reload swaps the renderer in place; `.discrete` drives a
    // body repaint over SSE). A `Ref` because the slug set is not fixed at
    // startup: `push` mints one at runtime (ADR 0010).
    renderers: Ref[IO, Map[String, Server.LiveSlug]],
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
    // Whether the upstream Home Assistant feed is live ([[HaFeed.healthy]]). The
    // SSE heartbeat only beats while this is true, so the client disconnect
    // banner also lights up on an upstream freeze — not just a browser-side
    // drop. Constant-`true` default keeps tests/standalone construction simple.
    healthy: Signal[IO, Boolean] = Signal.constant(true),
    // The live home's Pkl artifacts (schema + dump) served over `/system/pkl/`
    // for pkl-lsp / the editor / remote authors. The empty default serves
    // nothing (404) — the server's own eval never hits this route (it resolves
    // the packages offline from the seeded cache via `moduleCacheDir`).
    systemPkl: SystemPkl = SystemPkl.empty,
    // The on-demand dump refresh behind `POST /system/dump/refresh`
    // (fetch + validate-then-swap + renderer reload, wired by ServerApp —
    // see [[DumpRefresh]]). None (tests, BuildApp-less setups) makes the
    // route a 404.
    dumpRefresh: Option[IO[DumpRefresh.Result]] = None
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root              => pageResponse(defaultSlug, req)
    case req @ GET -> Root / "d" / slug => pageResponse(slug, req)

    // Locally cached theme assets (stylesheets/scripts/fonts); a name that
    // isn't cached is a 404 — the page then references the original URL.
    case GET -> Root / "assets" / name => assets.serve(name)

    // The live home's Pkl artifacts — the domain schema + the freshly-rendered
    // per-home dump — as source text for pkl-lsp (behind the `/edit` editor)
    // and remote authors; the server's own eval never hits these routes (it
    // resolves the packages offline from the seeded cache via
    // `moduleCacheDir`). The laptop companion (the `fh` scala-cli script) is
    // distributed from the GitHub repo (`scripts/fh.sc`), not from the
    // instance; it drives the routes below.

    // The package-discovery index (before the `:name` route, which would
    // otherwise swallow the 3-segment path as `name = "packages"`): current
    // versions + metadata sha256 of the packages this home serves — what
    // `fh pull` reads before rewriting the laptop's pins.
    case GET -> Root / "system" / "pkl" / "packages" =>
      guardSystemPkl(
        systemPkl.packagesIndex.flatMap(json =>
          Ok(json).map(_.putHeaders(`Content-Type`(MediaType.application.json)))
        )
      )

    // The workspace scaffold a laptop's `fh init` fetches and writes verbatim:
    // the machine-AGNOSTIC, byte-identical files (ADR 0010). The per-machine
    // `.fh/machine.json` is NOT served — `fh` writes its own (its cache dir + the
    // instance URL). Before the `:name` catch-all so these exact names win.
    case GET -> Root / "system" / "pkl" / "base.pkl" =>
      Ok(AddonBootstrap.BaseManifest)
        .map(_.putHeaders(`Content-Type`(MediaType.text.plain)))
    case GET -> Root / "system" / "pkl" / "PklProject" =>
      Ok(AddonBootstrap.ConsumerManifest)
        .map(_.putHeaders(`Content-Type`(MediaType.text.plain)))
    case GET -> Root / "system" / "pkl" / "gitignore" =>
      Ok(AddonBootstrap.GitignoreTemplate)
        .map(_.putHeaders(`Content-Type`(MediaType.text.plain)))

    case req @ GET -> Root / "system" / "pkl" / name =>
      guardSystemPkl(systemPkl.module(name).flatMap(systemPklResponse(_, req)))

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
      guardSystemPkl(systemPkl.packageArtifact(file).flatMap { bytes =>
        val mediaType =
          if (file.endsWith(".zip")) MediaType.application.zip
          else MediaType.application.json
        Ok(bytes).map(_.putHeaders(`Content-Type`(mediaType)))
      })

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

    // Recreate the entity dump on demand (the /edit editor's "refresh dump"
    // button): re-fetch from HA, validate every dashboard against the new dump
    // package in a staged copy, and swap the `@fh-home` pin only if nothing that
    // builds today breaks — the previous immutable package version stays in the
    // cache as the trail (no dated backup file). Same auth story as /system/push
    // above: unauthenticated on a port documented as such; when auth lands for
    // the direct port it must cover this route.
    case POST -> Root / "system" / "dump" / "refresh" =>
      dumpRefresh match {
        case None         => NotFound()
        case Some(action) =>
          action.flatMap(result =>
            Ok(Server.dumpRefreshJson(result).noSpaces).map(
              _.putHeaders(`Content-Type`(MediaType.application.json))
            )
          )
      }

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
  }

  /** The shared shape of the `/system/pkl/` routes: their `SystemPkl` calls
    * raise [[FHError]] for anything a home does not serve, mapped here to its
    * `status + message` — locally, the same as [[pushResponse]], so these
    * routes behave identically whether exercised through the app-level
    * [[FHError.handle]] or directly in a test; a non-`FHError` is an unnamed
    * bug and becomes a 500, same as there.
    */
  private def guardSystemPkl(io: IO[Response[IO]]): IO[Response[IO]] =
    io.handleErrorWith {
      case e: FHError => IO.pure(FHError.response(e))
      case err        => InternalServerError(err.getMessage)
    }

  /** The current renderer for `slug`, or `None` if no such dashboard is
    * registered. Reads through the registry `Ref`, so it sees slugs pushed
    * after startup.
    */
  private def rendererFor(slug: String): IO[Option[Renderer]] =
    liveFor(slug).flatMap(_.traverse(_.renderer.get))

  private def liveFor(slug: String): IO[Option[Server.LiveSlug]] =
    renderers.get.map(_.get(slug))

  /** One background render/diff loop per slug: one subscription to the state
    * stream, one diff cache, publishing slug-tagged patches to [[sharedTopic]]
    * — so each affected main-page fragment is rendered ONCE per state change
    * and fanned out to every connection viewing the slug, instead of N viewers
    * doing N identical renders. Only nodes whose HTML is a pure function of
    * entity state qualify: USER bake-group owners (uiState-dependent) are
    * excluded and stay per-session ([[changedPatches]]); STATE-selected groups
    * qualify and are handled here — selection flips and active-branch liveness
    * included (see [[sharedPatches]]).
    *
    * Renderer hot-swap: `switchMap` re-arms on every reload with the CURRENT
    * renderer and a FRESH per-slug diff cache. A change landing in the brief
    * switch window may be dropped — harmless, because every connection does a
    * full body repaint on reload ([[reloadRepaints]]).
    *
    * Started once per slug — at startup by [[Server.resource]], or on demand by
    * [[push]] for a slug minted at runtime.
    *
    * FUTURE (ADR): under a burst of state_changed events (HA fires them
    * constantly), coalesce — debounce/batch the stream and re-render at most
    * every X ms, collapsing repeated touches of the same node into one
    * render+push. The narrowing here bounds *what* re-renders; batching would
    * bound *how often*. (Fold this into the dynamic-groups ADR when the perf
    * model is settled.)
    */
  private def publisherFor(
      slug: String,
      live: Server.LiveSlug
  ): Stream[IO, Nothing] =
    live.renderer.discrete.zipWithIndex
      .switchMap { case (renderer, arm) =>
        // A fresh log IDENTITY per SWAP, in the ref every connection reads: a
        // cursor issued against the previous renderer's log names versions this
        // one never had, so it must not be resumable
        // (docs/adr/0011-the-live-connection.md).
        //
        // Not on the FIRST arm, though. `discrete` emits the current renderer
        // immediately, and rotating there invalidates cursors for no reason —
        // the log the `LiveSlug` was created with is already this renderer's.
        // It also raced the page route: a document served in that window
        // advertised the old id and its first connect was refused, repainting a
        // body it already had.
        Stream.exec(
          IO.whenA(arm > 0)(Server.freshLog.flatMap(live.log.set))
        ) ++
          stateStore.changes
            .evalMap(sharedPatches(renderer, live.log, _))
            .flatMap(Stream.emits)
      }
      .map(sse => (slug, sse))
      .through(sharedTopic.publish)

  /** Start every currently-registered slug's publisher. Slugs pushed later get
    * theirs from [[push]] via the supervisor.
    */
  def sharedPatchPublishers: Stream[IO, Nothing] =
    Stream
      .eval(renderers.get)
      .flatMap(rs =>
        Stream
          .emits(rs.toList.map { case (slug, live) =>
            publisherFor(slug, live)
          })
          .covary[IO]
          .parJoinUnbounded
      )

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
  def push(validated: Dashboard.Validated): IO[Unit] =
    (
      SignallingRef[IO].of(Renderer.fromValidated(validated)),
      Server.freshLog.flatMap(Ref[IO].of)
    ).flatMapN { (renderer, log) =>
      val fresh = Server.LiveSlug(renderer, log)
      val slug = validated.dashboard.slug
      renderers
        .modify { rs =>
          rs.get(slug) match {
            case Some(existing) => (rs, Some(existing))
            case None           => (rs + (slug -> fresh), None)
          }
        }
        .flatMap {
          case Some(existing) =>
            existing.renderer.set(Renderer.fromValidated(validated))
          case None =>
            supervisor
              .supervise(publisherFor(slug, fresh).compile.drain)
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
    *     ones, whose branch HTML bakes a client-selected member and therefore
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
      log: Ref[IO, FragmentLog],
      change: StateChange
  ): IO[List[ServerSentEvent]] =
    (stateStore.current, Server.stampNow).flatMapN { (store, millis) =>
      val req = Patches.plan(
        renderer,
        store.entities,
        Stamp(store.version, millis),
        change,
        Patches.Scope.Shared
      )
      // The log's id is read INSIDE the modify, so the cursor names the log the
      // batch was diffed against — never one a concurrent renderer swap rotated
      // in, which would leave the client quoting a log its version was not from.
      log
        .modify { l =>
          val (next, patches) = Patches.diff(renderer, l, req)
          (next, (next.id, patches))
        }
        .map { case (logId, patches) =>
          // Advance the clients' cursor to what they were just sent — but only when
          // something WAS sent. A batch that emitted nothing leaves every cursor
          // where it was, so a later resume re-sends a superset of what that client
          // needs (harmless: every fragment patch is an idempotent morph), which is
          // the right direction to err in.
          if (patches.isEmpty) patches
          else
            patches :+ Server.cursorSignals(renderer, logId, req.stamp.version)
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
      liveOpt <- liveFor(slug)
      rendererOpt <- liveOpt.traverse(_.renderer.get)
      // Seed the open set with this client's selected tab panels (from its
      // signals) plus the popup it says it still has open (from its signal — see
      // [[Server.PopupSignal]]), so BOTH receive live updates from the first
      // paint and a reconnect does not silently orphan the dialog on screen.
      // Warn on any off ui-state value.
      _ <- rendererOpt.traverse_ { r =>
        warnAnomalies(r, uiState) *>
          session.open.set(
            r.selectedSurfaces(uiState) ++ Server.claimedPopup(req, r)
          )
      }
      // On (re)connect, heal whatever the DOM missed while the stream was down —
      // the shared/per-session passes only stream FUTURE changes, so without this
      // a reconnected client would show pre-drop values until each entity next
      // ticks. Either the cursor names precisely what this DOM holds (resume), or
      // the whole body is repainted from the current snapshot.
      // Home-Assistant-feed liveness, PUSHED from the server (it owns the
      // `healthy` signal). This is concept 1 of the two disconnect concepts
      // (see [[Server.page]]): the backend knows when it can't reach HA, so it
      // emits the `haDown` signal directly rather than the client inferring it
      // from a stalled beat. Concept 2 (browser<->server transport) stays
      // client-side — only the browser can observe its own dropped SSE.
      healthPatch = (h: Boolean) =>
        Datastar.patchSignals(s"""{"${Server.HaDownSignal}":${!h}}""")

      // What truly differs per client: open-surface nodes and user
      // bake-group-owner nodes (plus the state groups those pull in),
      // re-rendered per state change with this session's uiState/open set and
      // diffed against its own cache.
      patches = stateStore.changes
        .evalMap(changedPatches(session, _, uiState))
        .flatMap(Stream.emits)
      control = Stream.fromQueueUnterminated(session.control)
      reloads = reloadRepaints(session, uiState)
      // Emit `haDown` on connect (the initial `discrete` value) and on every
      // health transition.
      haDown = healthy.discrete.changes.map(healthPatch)
      // Something for an idle connection to carry, so an intermediary doesn't
      // reap it — a COMMENT, which no signal ever needs to know about (see
      // [[Server.KeepAliveInterval]]).
      keepAlive = Stream
        .awakeEvery[IO](Server.KeepAliveInterval)
        .as(Server.keepAliveComment)

      // Shared main-page patches, rendered once per slug (see
      // sharedPatchPublishers) and tagged with it, so drop every other slug's.
      // One subscription to the multiplexed topic, so a slug that did not exist
      // when this connection opened (pushed since) still reaches it.
      //
      // The subscription is acquired BEFORE the opening patches read the
      // snapshot, and that order is the whole point of nesting them: a change
      // published in between is then queued for this connection instead of
      // being published to nobody and lost until the next reconnect. Erring the
      // other way is safe — a change caught by both arrives once in the opening
      // paint and once as a patch, and a patch is an idempotent morph.
      //
      // UNBOUNDED, and that is a correctness requirement, not a capacity
      // choice. A bounded subscription backpressures `publish`, and there is
      // ONE topic for every slug — so a single client that stops reading would
      // stall the shared publisher for every viewer of every dashboard. Nor
      // could we drop instead: the resume cursor rides this same stream, so
      // dropping a patch while keeping a later cursor would leave the client
      // claiming a version whose changes it never applied, and `since` would
      // never re-send them.
      //
      // What bounds it is the CONNECTION, not the queue: ember gives every
      // socket write an idle timeout (60s by default), so a peer that stops
      // reading is torn down and this subscription released with it.
      live = Stream
        .resource(sharedTopic.subscribeAwaitUnbounded)
        .flatMap { tagged =>
          val shared =
            tagged.collect { case (s, sse) if s == session.slug => sse }
          Stream
            .eval(
              session.open.get.flatMap(open =>
                liveOpt.traverse(openingPatches(slug, _, req, uiState, open))
              )
            )
            .flatMap(opening => Stream.emits(opening.toList.flatten)) ++
            shared
              .merge(patches)
              .merge(control)
              .merge(reloads)
              .merge(haDown)
              .merge(keepAlive)
        }

      stream = (Stream.emit(
        Datastar.patchSignals(s"""{"${Server.ConnSignal}":"$conn"}""")
      ) ++ live)
        .onFinalize(sessions.deregister(conn))
      resp <- Ok(stream)
    } yield resp

  /** What a (re)connecting client is sent before the live streams start. Three
    * outcomes, narrowest first (ADR 0011):
    *
    *   1. '''Reload''' when the client's `<head>` no longer matches this
    *      dashboard's UNPATCHABLE part ([[Renderer.headHash]]) — new
    *      stylesheets, scripts or chrome. Nothing else is sent: the page is
    *      about to re-render itself from scratch.
    *   1. '''Resume''' when the cursor provably names what this DOM holds.
    *   1. '''Repaint''' the whole body — the default, and where every doubt
    *      lands.
    *
    * A stale THEME or `<title>` ([[Renderer.styleHash]]) is orthogonal to all
    * three: it is repaired by [[headPatches]] in front of whichever outcome
    * applies, so a re-themed dashboard no longer costs the client its scroll
    * position and its open popup.
    *
    * '''The repaint is the default and every doubt falls back to it''': no
    * cursor (a fresh page load, whose body is server-rendered anyway), an
    * unparseable one, a cursor from a log this server no longer has (restart,
    * renderer swap — which also covers every dashboard change, since one
    * implies the other), a cursor from the FUTURE (a version this store never
    * reached — a restart with a rewound counter), or one so old the log can no
    * longer say what changed ([[FragmentLog.since]] returning `None`). A
    * repaint is always correct and merely expensive; a wrong resume is silently
    * stale forever.
    *
    * The cursor signals are re-emitted with the resume, because the resume
    * itself brings the client up to the log's current version.
    *
    * '''One rule covers the surfaces too.''' A tab panel's or popup's nodes
    * used to need painting fresh on every resume — their HTML baked a
    * client-selected member, and their only diff cache died with the previous
    * connection. Under the self/mount split a container patches its `self`
    * alone, so nothing about those nodes is per-client any more: they are
    * simply candidates, reached through the session's `open` set, and sent only
    * if they actually differ ([[Patches.resume]]). An open popup needs no
    * branch of its own either — a body repaint replaces `#dashboard` only, and
    * `#popups` lives in the chrome outside it, so the dialog is never
    * disturbed.
    *
    * The log is read ONCE, outside any `modify`, so a reconnect never
    * serializes against the live diff path.
    */
  /** The version a resume should ask for, which is NOT always the cursor's.
    *
    * `FragmentLog.since` uses `>=` because a cursor pushed alongside a patch
    * batch can be held by a client that saw only part of that store version —
    * one version can produce several batches — so version V must be re-sent to
    * a client claiming V.
    *
    * A DOCUMENT's cursor is a different kind of claim. The page was rendered
    * from one snapshot, so it has ALL of V by construction, and asking for
    * `>= V` hands it back everything the document already contains. It is
    * complete through V, so it needs `> V`.
    *
    * The two are told apart by where the cursor came from: signals mean a
    * reconnect ([[cursorOf]]), plain query params mean a first load
    * ([[Restore]]).
    */
  private def resumeFrom(req: Request[IO], c: Server.Cursor): Long =
    if (Server.hasSignals(req)) c.version else c.version + 1

  private def openingPatches(
      slug: String,
      live: Server.LiveSlug,
      req: Request[IO],
      uiState: Map[String, String],
      open: Set[String]
  ): IO[List[ServerSentEvent]] =
    (live.renderer.get, live.log.get, stateStore.current).mapN {
      (renderer, log, store) =>
        val cursor = Server.cursorOf(req)
        if (cursor.exists(_.headHash != renderer.headHash))
          List(Server.reloadPatch)
        else {
          val head =
            if (cursor.exists(_.styleHash != renderer.styleHash))
              Server.headPatches(renderer, slug)
            else Nil
          // `Patches.resume` is TOTAL now — a container whose history aged out is
          // answered with a fill for THAT mount, not a refusal. So the only
          // reasons left to repaint the body are the genuinely global ones
          // checked here: no cursor at all, a cursor minted against another log,
          // or one ahead of this store.
          val resumed = cursor
            .filter(c => c.logId == log.id && c.version <= store.version)
            .map(c =>
              Patches
                .resume(renderer, log, store.entities, resumeFrom(req, c), open)
            )
          // Lazy: rendering the whole body is the cost this exists to avoid.
          lazy val repaint = Datastar.patch(
            renderer.renderBody(store.entities, uiState),
            PatchMode.Inner,
            Some("#dashboard")
          )
          // An open popup needs no restore branch of its own any more: its nodes
          // are in `open`, so the resume rule reconciles them on their own ids,
          // and a body repaint replaces `#dashboard` only — `#popups` lives in
          // the chrome outside it, so the dialog is never disturbed.
          //
          // What DOES need saying is a claim this dashboard no longer recognises
          // (its surface renamed or removed): that dialog belongs to nothing, is
          // in nobody's open set, and would otherwise sit on screen forever.
          val orphan = Option
            .when(
              Server.popupOf(req).nonEmpty &&
                Server.claimedPopup(req, renderer).isEmpty
            )(
              Datastar.patch(
                s"""<div id="${Dashboard.PopupHostId}"></div>""",
                PatchMode.Outer,
                None
              )
            )
            .toList
          head ++ resumed.getOrElse(List(repaint)) ++ orphan :+
            Server.cursorSignals(renderer, log.id, store.version)
        }
    }

  /** Live-reload body repaints for one connection: watch the ONE renderer this
    * connection views (its slug is fixed for the connection's lifetime — going
    * elsewhere is a document load) and repaint on every swap.
    *
    * A reload that changed the head's UNPATCHABLE part ([[Renderer.headHash]] —
    * `<link>`ed stylesheets, scripts, chrome) sends the watching browser a page
    * RELOAD instead: the body morph would leave the old ones in place, so the
    * page would keep the previous look until manually refreshed. A changed
    * theme or title ([[Renderer.styleHash]]) rides along with the repaint as
    * [[headPatches]]. `zipWithPrevious` is what makes both comparable — the
    * decision is "did the head change across this swap", not "does it differ
    * from some baseline".
    */
  private def reloadRepaints(
      session: Session,
      uiState: Map[String, String]
  ): Stream[IO, ServerSentEvent] =
    Stream
      .eval(liveFor(session.slug))
      .unNone
      .flatMap { live =>
        live.renderer.discrete.zipWithPrevious
          .drop(1)
          .evalMap { case (previous, r) =>
            if (previous.exists(_.headHash != r.headHash))
              IO.pure(List(Server.reloadPatch))
            else
              // The repaint re-bakes the body (selected tabs included), so
              // reset the diff cache AND re-seed the open set to match. Reuses
              // this client's selection (closed over).
              (session.lastRendered.update(_.cleared) *>
                session.open.set(r.selectedSurfaces(uiState)) *>
                stateStore.snapshot)
                .map { st =>
                  val head =
                    if (previous.exists(_.styleHash != r.styleHash))
                      Server.headPatches(r, session.slug)
                    else Nil
                  head :+ Datastar.patch(
                    r.renderBody(st, uiState),
                    PatchMode.Inner,
                    Some("#dashboard")
                  )
                }
          }
          .flatMap(Stream.emits)
      }

  /** Re-render the nodes a changed entity drives that are truly per-connection
    * — for each open surface, that surface's components/dynamics, plus any
    * main-page USER bake-group owner (its HTML bakes the client's
    * client-selected member, so it can't be shared) — and emit only the
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
    *     client-selected member, so its flips — and its active subtree's
    *     liveness, which the shared pass skipped — render here with the
    *     session's `uiState`.
    */
  private[runtime] def changedPatches(
      session: Session,
      change: StateChange,
      uiState: Map[String, String]
  ): IO[List[ServerSentEvent]] =
    for {
      renderer <- rendererFor(session.slug)
      store <- stateStore.current
      millis <- Server.stampNow
      open <- session.open.get
      out <- renderer match {
        case None    => IO.pure(List.empty[ServerSentEvent])
        case Some(r) =>
          val req = Patches.plan(
            r,
            store.entities,
            Stamp(store.version, millis),
            change,
            Patches.Scope.Session(open, uiState)
          )
          session.lastRendered.modify(Patches.diff(r, _, req))
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
    *
    * A swap of the POPUP host also updates the client's [[Server.PopupSignal]],
    * so the browser carries that one bit of per-session state and a reconnect
    * can restore the dialog. Emitted here, next to the patch that made it true,
    * so the signal cannot disagree with what is actually in the host.
    *
    * '''A fill INVALIDATES the log entries for what it just re-supplied.'''
    * This is the one obligation every path that touches the DOM owes the ledger
    * (docs/plan-one-shared-log.md, statement (2)): the fill put the CURRENT
    * render into the mount, so an entry describing some earlier value is now a
    * lie, and a change BACK to that value would be diffed as "unchanged" and
    * suppressed while the client's DOM has moved on.
    *
    * Invalidating rather than re-fingerprinting is what statement (3) buys. The
    * plan asked each fill to write its members' fingerprints, which means
    * rendering every node in the surface a second time; but with content out of
    * the log a MISSING entry already means "unknown — send it", so dropping the
    * entries is correct for free. The cost is one redundant send per node on
    * the next change — including to other viewers, since the log is shared —
    * against a render of the whole surface per tab click.
    */
  private def swapHost(
      session: Session,
      renderer: Renderer,
      host: DomId,
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
      // What this swap re-supplies: the arriving surface's nodes, and the
      // departing ones' (their DOM is gone, so any entry for them describes
      // nothing).
      resupplied = (newSurface.toSet ++ renderer
        .surfacesAt(host)).flatMap(renderer.surfaceNodeIds)
      _ <- liveFor(session.slug).flatMap(
        _.traverse_(_.log.update(_.invalidateWhere(resupplied)))
      )
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
      _ <-
        if (host == Dashboard.PopupHostId)
          session.control.offer(Server.popupSignal(newSurface))
        else IO.unit
    } yield ()

  /** Resolve the connection (`conn` rides in the POST body among Datastar
    * signals) to its session + current renderer, run `f`, and return NoContent.
    */
  private def withSession(
      req: Request[IO]
  )(
      f: (Session, Renderer, Map[String, String]) => IO[Unit]
  ): IO[Response[IO]] = {
    // Datastar sends the signals as a JSON body; parse it directly (no
    // http4s-circe entity decoder dependency). It carries both `conn` and this
    // client's ui-state — swapHost/openSurface bake the selected tab.
    req.bodyText.compile.string
      .map(io.circe.parser.parse(_).toOption.flatMap { body =>
        connOf(body).map(_ -> Server.uiFromSignals(body.hcursor))
      })
      .flatMap {
        case None => BadRequest("""{"success":false,"error":"missing conn"}""")
        case Some((conn, uiState)) =>
          sessions.get(conn).flatMap {
            case None          => NoContent() // stale/unknown connection
            case Some(session) =>
              rendererFor(session.slug)
                .flatMap(_.traverse_(f(session, _, uiState))) *> NoContent()
          }
      }
  }

  private def connOf(body: Json): Option[String] =
    body.hcursor.get[String](Server.ConnSignal).toOption

  /** Log every bake-group anomaly [[Renderer.uiStateAnomalies]] reports for
    * this client's `uiState` (an off/hand-edited URL). Renderer stays pure — it
    * returns the warnings, the Server logs them.
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
          // `id` is a URL segment — an untrusted CLAIM about a node id, which
          // the renderer's index resolves (unknown ⇒ no entities, hence `[]`).
          val entities = renderer.entitiesForNode(NodeId.derived(id))
          val arr = Json.arr(entities.map { e =>
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
            .map(_.withSlug(slug))
            .flatMap(v => push(v).as(v))
            .flatMap(v =>
              Ok(
                s"pushed ${v.dashboard.slug} (${v.dashboard.cards.size} cards)"
              )
            )
            // decode raises FHError.badCondition for a malformed/invalid
            // dashboard (mapped to its 400 here since this route is also
            // exercised without the app-level FHError.handle); a non-FHError
            // is an unnamed bug and becomes a 500.
            .handleErrorWith {
              case e: FHError => IO.pure(FHError.response(e))
              case err        => InternalServerError(err.getMessage)
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
    * The ETag is a strong validator (hex SHA-256, [[LibPackage.sha256]]) over
    * the exact bytes served, so it is correct by construction: same text ⇒ same
    * tag.
    */
  private def systemPklResponse(
      text: String,
      req: Request[IO]
  ): IO[Response[IO]] = {
    val etag = EntityTag(LibPackage.sha256(text.getBytes(UTF_8)))
    val fresh = req.headers
      .get[`If-None-Match`]
      .exists {
        // A bare `If-None-Match: *` matches any existing representation.
        case `If-None-Match`(None)       => true
        case `If-None-Match`(Some(tags)) =>
          tags.exists(t => t.tag == etag.tag)
      }
    val cacheControl = `Cache-Control`(CacheDirective.`no-cache`())
    if (fresh) NotModified().map(_.putHeaders(ETag(etag), cacheControl))
    else
      Ok(text).map(
        _.withContentType(`Content-Type`(MediaType.text.plain))
          .putHeaders(ETag(etag), cacheControl)
      )
  }

  private def pageResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    liveFor(slug).flatMap {
      case None       => NotFound()
      case Some(live) =>
        (live.renderer.get, live.log.get, stateStore.current).flatMapN {
          (renderer, log, store) =>
            val uiState = Server.uiStateOf(req)
            // The editor embeds the dashboard as `?edit=1`; that turns on the
            // per-node inspection overlay (Focus / Debug). Off for normal viewers.
            val editMode = req.uri.query.params.get("edit").contains("1")
            val popup = req.uri.query.params
              .get(Server.PopupSignal)
              .filter(p => renderer.surface(p).nonEmpty)
            // What this document is showing, and so also what it must hand back
            // on connect for the stream to agree with it — the ui state, the
            // open popup, AND the version it was rendered at. That last part is
            // what stops the first connect repainting a body the document
            // already contains.
            val restore = Server.Restore(
              uiState,
              popup,
              Some(
                Server.Cursor(
                  renderer.headHash,
                  renderer.styleHash,
                  log.id,
                  store.version
                )
              )
            )
            // Tell the log what this document put on screen, for the surfaces
            // the client will have open. They are the resume rule's second
            // candidate set, and with no entry at all "unknown, send it" would
            // hand the client its own surfaces straight back. Node renders are
            // client-independent (a container patches its `self`, and the bake
            // lives on the document path), so this is sound to write into a
            // SHARED log.
            val open = renderer.selectedSurfaces(uiState) ++ popup
            val seedLog = live.log.update(l =>
              open
                .flatMap(renderer.surfaceNodeIds)
                .foldLeft(l)((acc, id) =>
                  renderer
                    .renderNodeById(id, store.entities)
                    .fold(acc)(html => acc.seed(id, html, store.version))
                )
            )
            warnAnomalies(renderer, uiState) *> seedLog *>
              Ok(
                page(
                  slug,
                  renderer.renderPage(store.entities, uiState, popup),
                  renderer.stylesheets.map(assets.rewrite),
                  renderer.scripts.map(assets.rewrite),
                  renderer.title,
                  Server.ingressPrefixOf(req),
                  restore,
                  editMode
                )
              ).map(_.withContentType(`Content-Type`(MediaType.text.html)))
        }
    }

  /** Full HTML document wrapping the rendered dashboard. The theme owns all
    * presentation (its tokens + inline CSS travel inside the body;
    * `stylesheets` are `<link>`-ed here). `data-init` opens this dashboard's
    * SSE stream. There is no history wiring: a dashboard is a real page, so
    * Back/Forward is the browser's (ADR 0002).
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
      restore: Server.Restore,
      editMode: Boolean = false
  ): String = {
    val links = (
      stylesheets
        .map(href => s"""  <link rel="stylesheet" href="$href">""") ++
        scripts
          .map(src => s"""  <script type="module" src="$src"></script>""")
    ).mkString("\n")
    val baseHref = ingressPrefix.fold("/")(p => s"$p/")
    val pageTitle = Server.titleTag(title, slug)
    // Edit-mode overlay (Focus / Debug per node), injected only when the editor
    // embeds this page with `?edit=1`. The config carries the slug + base so the
    // overlay can call the node-debug endpoint and message the parent editor.
    val editAssets =
      if (!editMode) ""
      else
        s"""<link rel="stylesheet" href="edit/overlay.css">
           |<script>window.__FH_EDIT__={"slug":"$slug","base":"$baseHref"};</script>
           |<script src="edit/overlay.js"></script>""".stripMargin
    // Connection indicators. TWO distinct, separately-SOURCED failures:
    //
    //   1. UPSTREAM HA FEED down (this server can't reach Home Assistant). The
    //      backend OWNS this fact (`healthy`), so it PUSHES the `haDown` signal
    //      over SSE (see `sseStream`) — no client-side inference. The banner
    //      just renders `data-show` off that signal.
    //   2. SSE TRANSPORT down (browser can't reach this server). Only the client
    //      can observe its own dropped stream, so this stays client-side.
    //      Datastar auto-retries a dropped `@get` (1s, doubling, capped at 30s,
    //      10 consecutive failures) and reports the lifecycle as a
    //      `datastar-fetch` CustomEvent whose `detail.type` is
    //      `error`/`retrying` (trouble), `retries-failed` (given up — the stream
    //      is dead and only a reload revives it), or anything else (`started`,
    //      `finished`, a patch type — the transport is alive). `data-on` binds
    //      it directly: the event is dispatched on `document` WITHOUT bubbling,
    //      and the plugin special-cases this name onto `document` for us, so
    //      neither `__window` (which cannot see it) nor a global+poll bridge is
    //      needed.
    //
    // Transport takes priority: a dead transport also freezes `haDown` updates,
    // so the HA banner is gated on `$_sse == 0`. Structure/behavior live here so
    // the indicators always render and are theme-independent (documented
    // primitives: `data-signals`, `data-on`, `data-show`); the LOOK is
    // theme-owned via `.fh-offline*` classes (each theme's `styles`, see
    // lib/theme-*.pkl).
    //
    // Datastar expressions read signals via `$name` (this pinned build, unlike
    // some Datastar doc examples, requires the sigil even for a bare read).
    val ha = "$" + Server.HaDownSignal
    val sseState =
      "evt.detail.type === 'retries-failed' ? 2 : " +
        "(evt.detail.type === 'retrying' || evt.detail.type === 'error') ? 1 : 0"
    // Both banners ship hidden by an INLINE `display:none`, or they flash on
    // every load: the Datastar module is deferred, so the browser paints the
    // markup before `data-show` first runs. It must be inline — `data-show`
    // clears the element's own `style.display` to reveal it, which cannot
    // override a stylesheet rule, so hiding these in the theme CSS would hide
    // them permanently.
    val hidden = """style="display:none""""
    // The banner state is DEBOUNCED so a sub-second blip never paints. Two
    // things flash without it: an ordinary visibility refetch (the phone-unlock
    // path this whole resume design serves), and a page reload — navigating away
    // aborts the stream, which fires `error` on the OUTGOING page and paints
    // "Reconnecting…" for an instant before it is replaced. Datastar's retry
    // backoff grows past this window, so a real outage still surfaces.
    //
    // The modifier separator is `__`, with the value after a `.`
    // (`__debounce.600ms`) — read off the pinned bundle's own parser
    // (`attr.split("__")`, then `mod.split(".")`), NOT from the vendored docs,
    // which show `.debounce_600ms`. That form silently becomes part of the EVENT
    // NAME: the listener binds to `datastar-fetch.debounce_600ms`, which never
    // fires, so `_sse` never updates and the banner never appears at all.
    // The popup this document has open, seeded from the URL so a REFRESH
    // restores the dialog (the signal itself dies with the document); the
    // effect mirrors it back on every change. Together these are a hand-rolled
    // `data-query-string` — see [[Server.UrlSyncScript]] and ADR 0005.
    // Two nested contexts, so two escapes: the value sits in a JS string literal
    // (Datastar parses the attribute as an expression) which sits in an HTML
    // attribute. HTML-escaping alone is not enough — `&#39;` decodes back to a
    // bare `'` and closes the literal early.
    val popupSeed =
      Server.escapeHtml(Server.escapeJsString(restore.popup.getOrElse("")))
    val connBanner =
      s"""<div data-signals="{${Server.HaDownSignal}: false, _sse: 0, ${Server.ReloadSignal}: false, ${Server.PopupSignal}: '$popupSeed'}"
         |     data-effect="$$${Server.ReloadSignal} && window.location.reload(); fhUrl('${Server.PopupSignal}', $$${Server.PopupSignal})"
         |     data-on:datastar-fetch__debounce.600ms="$$_sse = $sseState">
         |  <div class="fh-offline fh-offline-sse" $hidden role="status" aria-live="assertive" data-show="$$_sse > 0">
         |    <span $hidden data-show="$$_sse < 2">Reconnecting to the dashboard…</span>
         |    <span $hidden data-show="$$_sse >= 2">Dashboard connection lost. <button class="fh-offline-action" data-on:click="window.location.reload()">Reload</button></span>
         |  </div>
         |  <div class="fh-offline fh-offline-ha" $hidden role="status" aria-live="polite" data-show="$ha && $$_sse == 0">Home Assistant unavailable — reconnecting…</div>
         |</div>""".stripMargin
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <base href="$baseHref">
       |  $pageTitle
       |  <script>${Server.UrlSyncScript}</script>
       |$links
       |  <script type="module" src="${assets.rewrite(
        Server.DatastarCdn
      )}"></script>
       |</head>
       |<body data-init="@get('sse/dashboard/$slug/patch${restore.query}', ${Server.SseRetry})">
       |$connBanner
       |$body
       |$editAssets
       |</body>
       |</html>
       |""".stripMargin
  }
}

object Server {

  /** Wall clock for a [[Stamp]] — read once per diff pass, and used ONLY to age
    * [[Mutation]]s out of a [[FragmentLog]]. Nothing is ordered by it, so a
    * clock step (NTP, a suspended host waking) can widen or narrow a retention
    * window but cannot corrupt a cursor comparison.
    */
  private[runtime] val stampNow: IO[Long] = IO.realTime.map(_.toMillis)

  /** One slug's live state: the hot-swappable renderer and the fragment log its
    * cursors are valid for. ONE value rather than two slug-keyed maps, because
    * a swap must not be able to leave them out of step — and because the log
    * has to be reachable from a reconnecting connection ([[Server.sseStream]]),
    * not just from the publisher fiber that writes it.
    *
    * The log ref is stable per slug; a renderer swap replaces its CONTENTS with
    * a freshly-identified log ([[Server.publisherFor]]) rather than the ref.
    */
  private[runtime] case class LiveSlug(
      renderer: SignallingRef[IO, Renderer],
      log: Ref[IO, FragmentLog]
  )

  /** An empty log with a fresh identity. Minted per slug at startup and again
    * on every renderer swap.
    */
  private[runtime] val freshLog: IO[FragmentLog] =
    IO.randomUUID.map(id => FragmentLog(id.toString))

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
      healthy: Signal[IO, Boolean] = Signal.constant(true),
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None
  ): Resource[IO, Server] =
    for {
      topic <- Topic[IO, (String, ServerSentEvent)].toResource
      // Pair each seeded renderer with its own fragment log here, so the caller
      // (ServerApp, tests) never has to know the log exists.
      seeded <- renderers.toList
        .traverse { case (slug, r) =>
          freshLog.flatMap(Ref[IO].of).map(log => slug -> LiveSlug(r, log))
        }
        .map(_.toMap)
        .toResource
      registry <- Ref[IO].of(seeded).toResource
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
        healthy,
        systemPkl,
        dumpRefresh
      )
      _ <- server.sharedPatchPublishers.compile.drain.background
    } yield server

  /** Build the server fed by a [[HaFeed]] — the SINGLE place that couples the
    * two. A Server draws its `api`, its `store`, AND its health from one feed,
    * so a caller cannot wire live state from the feed but forget to forward
    * [[HaFeed.healthy]] (which would silently pin the `haDown` banner off — the
    * exact drift this method exists to prevent). Both [[ServerApp]] and the
    * test harness ([[TestServer]]) go through here, so tests exercise the real
    * feed (facade, supervision, health signal) rather than a bypass.
    */
  def fromFeed(
      feed: HaFeed,
      renderers: Map[String, SignallingRef[IO, Renderer]],
      defaultSlug: String,
      sessions: Sessions,
      assets: AssetCache = AssetCache.empty,
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None
  ): Resource[IO, Server] =
    resource(
      feed.api,
      feed.store,
      renderers,
      defaultSlug,
      sessions,
      assets,
      feed.healthy,
      systemPkl,
      dumpRefresh
    )

  /** The `POST /system/dump/refresh` response body — status plus what a caller
    * (the /edit editor) shows the user: the backup name on a swap, the
    * per-dashboard errors on a rejection.
    */
  def dumpRefreshJson(result: DumpRefresh.Result): Json = {
    result match {
      case DumpRefresh.Unchanged =>
        Json.obj("status" -> Json.fromString("unchanged"))
      case DumpRefresh.Swapped(version, _) =>
        Json.obj(
          "status" -> Json.fromString("swapped"),
          "version" -> Json.fromString(version)
        )
      case DumpRefresh.Rejected(errors) =>
        Json.obj(
          "status" -> Json.fromString("rejected"),
          "errors" -> Json.fromValues(errors.map { case (slug, err) =>
            Json.obj(
              "slug" -> Json.fromString(slug),
              "error" -> Json.fromString(err)
            )
          })
        )
    }
  }

  /** The largest fraction of a dynamic group's rendered members that may churn
    * (be added and/or removed by one state change) and still be patched
    * per-entity (`remove` / `insert`); beyond it the whole group repaints. The
    * comparison is strict (`churn < MaxChurnFraction * shown`), so exactly-half
    * churn — e.g. removing 1 of 2 members, or the last member — repaints, while
    * removing 1 of 4 patches per-entity. Tunable: raise it to favour per-entity
    * patches (smaller payloads, more patches), lower it to favour repaints.
    */
  val MaxChurnFraction: Double = 0.5

  /** The view state a freshly-loaded document has to hand back to the server on
    * connect: its bake-group selections and its open popup, both read off the
    * page URL (ADR 0005).
    *
    * It rides the `data-init` SSE URL as ordinary query params ([[query]])
    * because **the first connect carries no signals**: `data-init` fires from
    * the `<body>`, and the `data-signals` seeds live on descendants that
    * Datastar has not merged yet — so a signals-only read would render the
    * DEFAULT tab, and its repaint would morph the correct seed away and drag
    * the URL along with it. A reconnect is the opposite case and needs no help:
    * it re-serializes the live signal store, which wins over these params
    * wherever both name the same fact ([[uiStateOf]], [[popupOf]]).
    */
  private[runtime] case class Restore(
      uiState: Map[String, String],
      popup: Option[String],
      // What this document already SHOWS: the store version it was rendered at,
      // and the log it belongs to. Without it the first connect has no cursor
      // and takes the no-cursor branch, which inner-patches a body the document
      // already contains — the whole page, sent twice, on every load.
      cursor: Option[Cursor] = None
  ) {

    /** `?ui.<id>=<v>&popup=<id>&<cursor>`, or `""` when there is nothing to
      * restore. `&amp;` because this lands in an HTML attribute.
      */
    def query: String = {
      val params = uiState.toList.sorted.map { case (id, v) =>
        s"$UiParamPrefix${encode(id)}=${encode(v)}"
      } ++ popup.map(p => s"$PopupSignal=${encode(p)}").toList ++
        cursor.toList.flatMap(c =>
          List(
            s"$HeadHashSignal=${encode(c.headHash)}",
            s"$StyleHashSignal=${encode(c.styleHash)}",
            s"$LogIdSignal=${encode(c.logId)}",
            s"$StoreVersionSignal=${c.version}"
          )
        )
      if (params.isEmpty) "" else params.mkString("?", "&amp;", "")
    }

    private def encode(s: String): String =
      java.net.URLEncoder.encode(s, UTF_8)
  }

  /** The client's UI state — bake-group id -> selected member, as
    * `id -> rawValue`. Read from the page URL's `ui.<id>` query params and from
    * the `ui_<id>` Datastar signals, the two carriers of the same fact (ADR
    * 0005): the URL is what a first-paint GET and a refresh have, the signals
    * are what a reconnect and an action POST have. Signals win where both are
    * present — they are the live value; the URL trails them by a
    * `history.replaceState`.
    *
    * The value is left opaque here — interpretation and the untrusted-value
    * clamp live in [[Renderer.resolveActive]], so a stale or hand-edited URL
    * can never bake a non-existent surface.
    */
  def uiStateOf(req: Request[IO]): Map[String, String] =
    uiFromQuery(req) ++ signalsOf(req).fold(Map.empty)(uiFromSignals)

  /** UI state off the page URL — the carrier that survives a refresh and is
    * unique per document (a cookie is per-ORIGIN, so two browser tabs on the
    * same dashboard would overwrite each other's selection).
    */
  private def uiFromQuery(req: Request[IO]): Map[String, String] =
    req.uri.query.params.collect {
      case (k, v) if k.startsWith(UiParamPrefix) =>
        k.drop(UiParamPrefix.length) -> v
    }

  /** UI state off a Datastar signal payload — the `datastar` query param on a
    * GET, the JSON body on an action POST.
    */
  private[runtime] def uiFromSignals(c: io.circe.ACursor): Map[String, String] =
    c.keys.toList.flatten
      .filter(_.startsWith(UiSignalPrefix))
      .flatMap { k =>
        c.downField(k)
          .focus
          .flatMap(j => j.asString.orElse(j.asNumber.map(_.toString)))
          .map(k.drop(UiSignalPrefix.length) -> _)
      }
      .toMap

  /** Query-param and signal name prefixes for the client's UI state (a bake
    * group's selected member). Framework-owned like `conn`/`popup` — the
    * authoring layer composes the id onto them (`ui_{{id}}`), and ADR 0005's
    * "no signal-name literals in the backend" is about the authoring layer's
    * own names (`tab_`, `_val_`), not this protocol.
    */
  val UiParamPrefix: String = "ui."
  val UiSignalPrefix: String = "ui_"

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

  /** The Datastar signal name carrying upstream-HA liveness, PUSHED by the
    * server (it owns `healthy`). `true` means the backend can't reach Home
    * Assistant; the HA disconnect banner renders `data-show` off it (see
    * [[Server.page]]). Concept 1 of the two disconnect concepts — the
    * browser<->server transport (concept 2) is derived client-side instead.
    */
  val HaDownSignal: String = "haDown"

  /** The four resume signals (docs/adr/0011-the-live-connection.md), all PUSHED
    * by the server and never declared client-side. Datastar sends every
    * non-`_`-prefixed signal back with each backend action, so they ride the
    * reconnect URL for free and the server keeps no per-client state between
    * connections.
    *
    * NOT `_`-prefixed, and that is a deliberate exception: `_` is exactly the
    * convention for per-connection client state (`_sse`, the SSE-down banner),
    * which these ARE — but the prefix is what excludes a signal from the URL,
    * and riding the URL is their entire purpose.
    *
    *   - `headHash` — does the browser's `<head>` still match where it CANNOT
    *     be patched? Mismatch ⇒ page reload ([[Renderer.headHash]]).
    *   - `styleHash` — the patchable rest of the head. Mismatch ⇒ two element
    *     patches ([[headPatches]]), no reload.
    *   - `logId` — is the cursor even comparable? Mismatch ⇒ body repaint.
    *   - `storeVersion` — the cursor itself: how far behind this client is.
    */
  val HeadHashSignal: String = "headHash"
  val StyleHashSignal: String = "styleHash"
  val LogIdSignal: String = "logId"
  val StoreVersionSignal: String = "storeVersion"

  /** Which popup surface this client has open (`""` for none) — a fourth
    * URL-riding signal, for the same reason as the three above and answering
    * the one per-session question nothing else can.
    *
    * Without it the returning connection's open set is empty and the `<dialog
    * open>` still standing in that DOM belongs to no session: it would never be
    * updated again (and a body repaint cannot even remove it — the host lives
    * in `theme.chrome`, outside `#dashboard`). Losing a popup you left open is
    * not acceptable either; on a phone, backgrounding the tab is how you read a
    * notification, not how you dismiss a dialog.
    *
    * Mirrored into the `popup` URL param like the ui-state signals
    * ([[UrlSyncScript]]), so a REFRESH re-opens the dialog too — the signal
    * itself dies with the document.
    *
    * PUSHED by the server, from the one place that changes the popup host
    * ([[swapHost]]) plus the navigate that clears it — so it always names what
    * the server actually rendered there, rather than what a client-side
    * expression believed it asked for. At most one popup is open at a time (the
    * host holds one), so a single string is the whole state.
    */
  val PopupSignal: String = "popup"

  /** `fhUrl(key, value)` — mirror one piece of view state into the page URL
    * without navigating: set the param, or drop it when the value is empty.
    *
    * This is a hand-rolled `data-query-string`, which is a Pro plugin we don't
    * have (ADR 0005). Signals stay the LIVE carrier — they are what reaches the
    * server on a reconnect and on every action — and the URL is their mirror,
    * for the two things a signal cannot do: survive a refresh, and stay unique
    * per document (a cookie is per-origin, so a second browser tab on the same
    * dashboard would overwrite the first one's selection).
    *
    * `replaceState`, never `pushState`: this is view state, not navigation.
    * Back should leave the dashboard, not step back through tab clicks.
    *
    * A classic inline script so it is defined before the deferred Datastar
    * module evaluates the first `data-effect` that calls it.
    */
  val UrlSyncScript: String =
    "window.fhUrl=(k,v)=>{const u=new URL(location.href);" +
      "(v===''||v==null)?u.searchParams.delete(k):u.searchParams.set(k,v);" +
      "history.replaceState(null,'',u)};"

  /** Id of the page `<title>`, so a head patch can morph it by id like any
    * other element.
    */
  val TitleId: String = "fh-title"

  /** The page `<title>`: the authored one, or the slug when unset. Escaped — an
    * authored title is untrusted text.
    */
  private[runtime] def titleTag(title: Option[String], slug: String): String =
    s"""<title id="$TitleId">${escapeHtml(title.getOrElse(slug))}</title>"""

  /** Bring a client's `<head>` in line with this renderer WITHOUT reloading:
    * the theme `<style>` and the `<title>`, which together are everything
    * [[Renderer.styleHash]] covers. Sent on a navigate (the dashboard changed)
    * and on a reconnect whose `styleHash` no longer matches.
    *
    * A reload would also work and used to be what we did, but it throws away
    * every bit of client-side state on the page — an open popup, a slider
    * mid-drag, scroll position — to re-send a stylesheet.
    */
  private[runtime] def headPatches(
      renderer: Renderer,
      slug: String
  ): List[ServerSentEvent] =
    List(
      Datastar.patchElements(renderer.themeStyleTag),
      Datastar.patchElements(titleTag(renderer.title, slug))
    )

  /** Reload this page. `_`-prefixed — unlike the four above, this one is pure
    * per-connection client state with no reason to ride any URL, and the page
    * turns it into `window.location.reload()` via `data-effect`.
    *
    * A signal rather than a patched `<script>` element: it reuses the channel
    * already carrying the cursor instead of adding a second mechanism, and the
    * page declares the effect once where every other client behaviour lives.
    */
  val ReloadSignal: String = "_reload"

  private[runtime] val reloadPatch: ServerSentEvent =
    Datastar.patchSignals(s"""{"$ReloadSignal":true}""")

  /** What a reconnecting browser claims its DOM already holds. Every field is
    * required: a version without the log that issued it names nothing, and
    * without the hashes it could belong to a different compiled dashboard.
    */
  private[runtime] case class Cursor(
      headHash: String,
      styleHash: String,
      logId: String,
      version: Long
  )

  /** Read the cursor off the GET signal payload. Datastar serializes the signal
    * store into a `datastar` query param on every GET action, which is how the
    * cursor survives the visibility refetch that closes and reopens the stream
    * (verified in a browser, and live against a real instance — ADR 0011).
    *
    * `None` for anything short of all three fields, which covers a first load
    * (empty store), a partial patch, and a garbled param alike — every one of
    * them a repaint.
    */
  private[runtime] def cursorOf(req: Request[IO]): Option[Cursor] =
    signalsOf(req)
      .flatMap(c =>
        for {
          hash <- c.get[String](HeadHashSignal).toOption
          styleHash <- c.get[String](StyleHashSignal).toOption
          logId <- c.get[String](LogIdSignal).toOption
          version <- c.get[Long](StoreVersionSignal).toOption
        } yield Cursor(hash, styleHash, logId, version)
      )
      .orElse(cursorFromQuery(req))

  /** The cursor a freshly-loaded DOCUMENT hands back on its first connect
    * ([[Restore]]), read from plain query params.
    *
    * Signals win where both exist, the same precedence [[uiStateOf]] and
    * [[popupOf]] use and for the same reason: a reconnect re-serialises the
    * live signal store, and a stale param baked into the `data-init` URL at
    * page render must never override it. Without that rule a client would
    * resume from its ORIGINAL page version forever, and silently miss
    * everything since.
    */
  private def cursorFromQuery(req: Request[IO]): Option[Cursor] = {
    val p = req.uri.query.params
    for {
      hash <- p.get(HeadHashSignal)
      styleHash <- p.get(StyleHashSignal)
      logId <- p.get(LogIdSignal)
      version <- p.get(StoreVersionSignal).flatMap(_.toLongOption)
    } yield Cursor(hash, styleHash, logId, version)
  }

  /** The popup this client claims to have open ([[PopupSignal]]), or `None` for
    * a client that has none.
    *
    * The signal is authoritative WHEN PRESENT, empty string included — that is
    * how a client says "I closed it". Only a request that does not carry the
    * signal at all falls back to the query param, which is the first connect
    * ([[Restore]]); after that the signal always exists, so a stale param on a
    * reconnect's URL can never re-open a dialog the user dismissed.
    */
  private[runtime] def popupOf(req: Request[IO]): Option[String] =
    signalsOf(req)
      .flatMap(_.get[String](PopupSignal).toOption)
      .orElse(req.uri.query.params.get(PopupSignal))
      .filter(_.nonEmpty)

  /** The claimed popup, narrowed to one this dashboard can actually serve: a
    * registered surface that really does host at the popup mount. Anything else
    * (a renamed surface, a stale claim from another dashboard, a baked panel
    * id) is not adopted into the session's open set.
    */
  private[runtime] def claimedPopup(
      req: Request[IO],
      renderer: Renderer
  ): Option[String] =
    popupOf(req).filter(
      renderer.surface(_).exists(_.hostId == Dashboard.PopupHostId)
    )

  /** Whether this request carries the live signal store — i.e. it is a
    * RECONNECT rather than a freshly-loaded document's first connect.
    */
  private[runtime] def hasSignals(req: Request[IO]): Boolean =
    signalsOf(req).isDefined

  private def signalsOf(req: Request[IO]): Option[io.circe.ACursor] =
    req.uri.query.params
      .get("datastar")
      .flatMap(io.circe.parser.parse(_).toOption)
      .map(_.hcursor)

  /** [[PopupSignal]] as a patch-signals event: the open surface id, or `""` for
    * a closed host.
    */
  private[runtime] def popupSignal(surfaceId: Option[String]): ServerSentEvent =
    Datastar.patchSignals(s"""{"$PopupSignal":"${surfaceId.getOrElse("")}"}""")

  /** The resume signals as one patch-signals event. Emitted on connect and
    * after every shared patch batch, so a client's cursor names what it has
    * actually been sent.
    */
  private[runtime] def cursorSignals(
      renderer: Renderer,
      logId: String,
      version: Long
  ): ServerSentEvent =
    Datastar.patchSignals(
      s"""{"$HeadHashSignal":"${renderer.headHash}",""" +
        s""""$StyleHashSignal":"${renderer.styleHash}",""" +
        s""""$LogIdSignal":"$logId",""" +
        s""""$StoreVersionSignal":$version}"""
    )

  /** Options for the `data-init` `@get` that opens the SSE stream.
    *
    * The default retry mode (`auto`) retries a DROPPED connection but not a
    * completed one: a 200 whose body simply ends is "finished", and the client
    * sits there forever. This stream is never supposed to end, so ANY end is a
    * reason to reconnect, whoever ended it and however politely — a property
    * worth having outright rather than re-deriving per kind of end (a graceful
    * server shutdown, a dashboard swap, a future server-side close). It also
    * stops a non-200 (a slug that has since been deleted) leaving a frozen page
    * with no indication: the retries run out and the "connection lost" banner
    * appears.
    *
    * Verified against the pinned v1.0.2 bundle, not the docs: after the SSE
    * body is consumed it retries only on `retry === "always"`; everything else
    * falls through to `finished`.
    */
  val SseRetry: String = "{retry:'always'}"

  /** How often an idle SSE connection is given something to carry.
    *
    * WHY AT ALL: for INTERMEDIARIES, which is the normal case here rather than
    * the exception — the add-on is reached through Home Assistant's ingress
    * (nginx), and the remote path adds another hop (a tunnel, a CDN). Those
    * close a connection that has gone quiet for a minute or so. A dashboard at
    * night is quiet for hours.
    *
    * And it is the CHEAP option, which is not the intuition. Letting an idle
    * connection be reaped saves nothing: Datastar reconnects, costing a TCP+TLS
    * handshake, a GET carrying every signal, and the opening patches — perhaps
    * 1-2 KB, once a minute. This is ~15 bytes at this interval, about 2 KB an
    * hour, so dropping it would cost 30-60x more traffic and battery in
    * exchange for a connection that feels intermittently flaky.
    *
    * WHY A COMMENT ([[keepAliveComment]]) rather than re-emitting `haDown`: a
    * comment line is skipped by any conforming SSE parser, so it never reaches
    * Datastar's message handler, never touches the browser's signal store, and
    * never shows up as an event in devtools. Health needs no repeating anyway —
    * it is pushed on connect and on every transition (`healthy.discrete`), and
    * a client that missed one has reconnected, which re-sends it.
    *
    * FUTURE: a direct LAN connection needs none of this, and we could TELL. The
    * ingress hop announces itself (`X-Ingress-Path`, already read here for the
    * `<base href>`) and a reverse proxy conventionally sets `X-Forwarded-*`, so
    * this could be sent only to connections that arrived through a hop —
    * per-connection, since the request is right there. Not done yet: the win is
    * ~2 KB/hour, while a wrong guess is a connection that silently drops once a
    * minute, which is the failure nobody reports because it still works.
    */
  val KeepAliveInterval: FiniteDuration = 25.seconds

  /** The keepalive itself: an SSE comment, carrying no data, no event type and
    * no signal — just bytes on the wire. See [[KeepAliveInterval]].
    */
  private[runtime] val keepAliveComment: ServerSentEvent =
    ServerSentEvent(comment = Some("keepalive"))

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
  /** Escape a string for interpolation into a single-quoted JS string literal
    * (a seeded signal value inside a Datastar expression). Backslash first, or
    * the escapes we add would themselves be escaped.
    */
  private[runtime] def escapeJsString(s: String): String =
    s.replace("\\", "\\\\").replace("'", "\\'")

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
