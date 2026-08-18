package fh.view.runtime

import scala.util.chaining.*
import api.homeassistant.HomeAssistantApi
import cats.data.OptionT
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.build.{
  AddonBootstrap,
  DashboardBuild,
  DumpRefresh,
  LibPackage,
  Site,
  SystemPkl
}
import fh.view.FHError
import fh.view.model.{Dashboard, DomId, NodeId, SignalId}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{
  `Cache-Control`,
  `Content-Type`,
  `If-None-Match`,
  ETag
}
import org.http4s.ServerSentEvent

import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.duration.*

/** HTTP surface for the dashboards. Construct via [[Server.resource]], which
  * runs the per-slug recorders.
  *
  * Opening a surface, switching a tab and closing a popup are all one host-swap
  * ([[swapHost]]); going to ANOTHER dashboard is not a route here at all, but
  * an ordinary document load of `/d/:slug` (ADR 0002).
  *
  * Nothing is pushed. A state change is RECORDED once per slug
  * ([[sharedPatchPublishers]]) and each connection then pulls what it is owed
  * ([[pull]]), rendering against its own `holds` and its own selections. That
  * is why there is no audience tag on a patch and no per-client filter at the
  * wire edge: a patch exists only because the session that will send it asked.
  *
  * The slug set is NOT fixed at startup ([[Server.LiveSite]]): the entrypoint's
  * `dashboards` map is re-read on every reload (ADR 0021) and [[push]] installs
  * one at runtime (ADR 0010).
  */
class Server(
    api: HomeAssistantApi[IO],
    stateStore: StateStore,
    // Every dashboard this instance currently serves and which one answers `/`
    // ([[Server.LiveSite]]) — one hot-swappable renderer STATE per slug, paired
    // with that slug's fragment log. Membership is LIVE: a key added to the
    // entrypoint appears on the next reload, a removed one disappears, and
    // `push` mints one at runtime.
    site: Server.LiveSite,
    sessions: Sessions,
    // Starts the per-slug recorder for a slug that enters the registry after
    // startup. Scoped to `Server.resource`, so those fibers die with the server.
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
    dumpRefresh: Option[IO[DumpRefresh.Result]] = None,
    // How long a document's session waits to be adopted ([[Server.AdoptionWindow]]).
    // A parameter only so a suite can watch the reap without waiting 30s.
    adoptionWindow: FiniteDuration = Server.AdoptionWindow,
    // How long a session outlives its stream ([[Server.LingerWindow]]). Same
    // reason it is a parameter.
    lingerWindow: FiniteDuration = Server.LingerWindow
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Resolved per REQUEST, not at construction: the entrypoint can rename or
    // delete the dashboard `/` used to serve, and `/` must still answer.
    case req @ GET -> Root => site.defaultSlug.flatMap(pageResponse(_, req))
    case req @ GET -> Root / "d" / slug => pageResponse(slug, req)

    // Locally cached theme assets (stylesheets/scripts/fonts); a name that
    // isn't cached is a 404 — the page then references the original URL.
    case GET -> Root / "assets" / name => assets.serve(name)

    // The PWA files — the manifest + service worker (the install mechanism,
    // see [[PwaAssets]]) and the icons. Fixed names, so `PwaAssets` serves them
    // no-cache, never immutable — the browser must revalidate them to learn
    // about updates (see the object doc).
    case GET -> Root / "manifest.webmanifest" =>
      PwaAssets.serve("manifest.webmanifest")
    case GET -> Root / "sw.js"        => PwaAssets.serve("sw.js")
    case GET -> Root / "icon-192.png" => PwaAssets.serve("icon-192.png")
    case GET -> Root / "icon-512.png" => PwaAssets.serve("icon-512.png")

    // The bundled frontend (src/js -> vite). The name carries a content hash
    // and `FrontendAssets` only answers for names the manifest lists, so this
    // needs no path sanitising and the response can be `immutable`: a rebuilt
    // bundle is a different URL, never a stale hit.
    case req @ GET -> Root / "web" / file if FrontendAssets.serves(file) =>
      StaticFile
        .fromResource(s"/web/$file", Some(req))
        .map(
          _.putHeaders(
            Header.Raw(
              CIString("Cache-Control"),
              "public, max-age=31536000, immutable"
            )
          )
        )
        .getOrElseF(NotFound())

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
      // The 404 gate lives INSIDE the stream ([[sseStream]]), on its own single
      // lookup, not here — see that method.
      sseStream(slug, req)

    // The error page's recovery stream ([[recoverStream]]): unlike `patch`, a
    // dedicated stream with no session/conn/cursor — the error page opens it and
    // reloads on the first `_reload` signal ([[errorPage]]). The slug lookup
    // happens INSIDE the stream ([[recoverStream]]) so it cannot race it.
    case GET -> Root / "sse" / "dashboard" / slug / "recover" =>
      recoverStream(slug)

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
      case e: FHError => FHError.logged(e)
      case err        => InternalServerError(err.getMessage)
    }

  /** The current renderer for `slug`, or `None` if no such dashboard is
    * registered. Reads through the registry `Ref`, so it sees slugs pushed
    * after startup.
    */
  private def rendererFor(slug: String): IO[Option[Renderer]] =
    liveFor(slug).flatMap(
      _.traverse(_.renderer.get).map(_.flatMap(_.rendererOf))
    )

  /** Test seam: the live state for one slug (its renderer signal + its log), so
    * a suite can assert on what a path wrote to the ledger rather than only on
    * what reached the wire.
    */
  private[runtime] def liveSlug(slug: String): IO[Server.LiveSlug] =
    liveFor(slug).map(_.getOrElse(sys.error(s"no live slug '$slug'")))

  private def liveFor(slug: String): IO[Option[Server.LiveSlug]] =
    site.liveFor(slug)

  /** One background RECORDING loop per slug: one subscription to the state
    * stream, writing what each frame did to the slug's changelog and then
    * ringing its doorbell. It renders nothing and sends nothing — every byte is
    * produced by the session that will receive it ([[Server.sseStream]]), from
    * the same [[Patches.resume]] a reconnect uses.
    *
    * What that buys is that a client's DOM is decided against a record of THAT
    * client's DOM, so one viewer's selections, filters and disconnections can
    * no longer be baked into another's bytes. What it costs is the fan-out: N
    * viewers of one slug currently render N times, which is what wiring the
    * per-slug [[RenderCache]] into the resume path is for
    * (docs/adr/0012-each-session-renders-what-it-is-owed.md).
    *
    * Renderer hot-swap: `switchMap` re-arms on every reload with the CURRENT
    * renderer. A change landing in the brief switch window may be dropped —
    * harmless, because every connection does a full body repaint on reload
    * ([[reloadRepaints]]).
    *
    * Started once per slug, by [[sharedPatchPublishers]] reconciling against
    * the registry — whether the slug was there at startup, added by an edit to
    * the entrypoint, or minted by [[push]].
    *
    * The narrowing here bounds *what* is recorded, never *how often* — see the
    * event-coalescing entry in TODO2.md.
    */
  private def publisherFor(
      slug: String,
      live: Server.LiveSlug
  ): Stream[IO, Nothing] =
    live.renderer.discrete.zipWithIndex.switchMap {
      case (Server.RendererState.Failed(_), _) =>
        // A failed dashboard records nothing and rings nothing: there is no
        // renderer to diff against, and the error page has no sessions. The
        // doorbell stays frozen at the last successful build's version. The
        // transition back to `Ready` re-arms this arm with the fresh renderer
        // and rotates the log identity (arm > 0), so every old cursor is
        // invalid and a reconnect repaints — the same argument that covers a
        // change landing in the switch window below.
        Stream.empty
      case (Server.RendererState.Ready(renderer), arm) =>
        // A fresh log IDENTITY per SWAP, in the ref every connection reads: a
        // cursor issued against the previous renderer's log names versions this
        // one never had, so it must not be resumable
        // (docs/adr/0011-the-live-connection.md).
        //
        // Not on the FIRST arm, though. `discrete` emits the current renderer
        // immediately, and rotating there invalidates cursors for no reason —
        // the log the `LiveSlug` was created with is already this renderer's.
        // It also races the page route: a document served in that window would
        // advertise the old id, get its first connect refused, and repaint a
        // body it already had.
        Stream.exec(
          IO.whenA(arm > 0)(Server.freshLog.flatMap(live.log.set))
        ) ++
          stateStore.changes.evalMap(
            // The doorbell rings AFTER the log is written, or a session woken by
            // it could read a log that does not yet describe the version it was
            // told about — and would then set its position past changes it
            // never saw.
            recordFrame(slug, renderer, live.log, _).flatMap(live.doorbell.set)
          )
    }.drain

  /** Keep exactly one publisher running per registered slug, for as long as it
    * is registered — the ONE place a recorder is started or stopped.
    *
    * It reconciles against the registry rather than starting from a startup
    * snapshot, which is what makes membership live: a slug added by an edit to
    * the entrypoint, or by [[push]], gets its recorder here; a slug the
    * entrypoint dropped has its recorder cancelled here. (The same `toAdd` /
    * `toCancel` shape as `ServerApp.watchSourcesWith`'s import reconcile.)
    */
  def sharedPatchPublishers: Stream[IO, Nothing] =
    Stream
      .eval(Ref[IO].of(Map.empty[String, IO[Unit]]))
      .flatMap { active =>
        site.changes.evalMap { registered =>
          active.get.flatMap { current =>
            val toAdd =
              registered.toList.filterNot { case (slug, _) =>
                current.contains(slug)
              }
            val toCancel = current.keySet -- registered.keySet
            for {
              added <- toAdd.traverse { case (slug, live) =>
                supervisor
                  .supervise(publisherFor(slug, live).compile.drain)
                  .map(slug -> _.cancel)
              }
              _ <- toCancel.toList.traverse_(current.getOrElse(_, IO.unit))
              _ <- active.set((current ++ added) -- toCancel)
            } yield ()
          }
        }
      }
      .drain

  /** Readiness seam for tests (mirroring [[StateStore.changeSubscribers]]):
    * await a connection's session before moving an entity, so the frame is
    * recorded with that client's surfaces in view.
    */
  private[runtime] def connectedSessions: Stream[IO, Int] = sessions.liveStreams

  /** Install `dashboard` under its slug, live, without evaluating any Pkl — the
    * component-developer story (ADR 0010, persona 4): they author cards the
    * server holds no source for, evaluate on their laptop, and push the RESULT.
    * Viable only because the wire model is self-contained (every card carries
    * its own template), so this needs nothing but the JSON.
    *
    * An EXISTING slug reuses its `SignallingRef` — setting it repaints open
    * connections exactly as live reload does, which is the push/look/edit loop.
    * A NEW slug is simply installed; its publisher follows from the registry
    * ([[sharedPatchPublishers]]), the same way a slug the entrypoint added
    * does.
    *
    * Ephemeral by design: this touches no file, so a restart returns the
    * instance to its on-disk dashboards.
    *
    * '''A pushed slug is never reclaimed while the process lives''', and that
    * is deliberate — nothing else can decide that a developer is finished with
    * one, so nothing quietly deletes it. A reload only ever removes a slug the
    * ENTRYPOINT dropped, and the registry decides that from the slug's own
    * origin ([[Server.Origin]]) rather than from a caller's memory of what the
    * site used to own. The cost is paid per pushed slug and is not free: each
    * new one holds a `Renderer` + a fragment log, and its publisher runs a diff
    * pass on every state batch for the life of the process. A long-lived
    * instance that is pushed to all day accumulates both. Removing one is a
    * USER action that does not exist yet — see TODO2.md ("an overlay to drop a
    * pushed dashboard").
    */
  def push(validated: Dashboard.Validated): IO[Unit] =
    site.installPushed(
      validated.dashboard.slug,
      Server.RendererState.Ready(Renderer.fromValidated(validated))
    )

  /** The imperative shell around [[Patches.plan]] + [[Patches.record]]: read
    * the snapshot and the open sets, run the pure pass, write the changelog,
    * ring the doorbell.
    *
    * No client `uiState` reaches it and nothing is rendered, so what a slug
    * pays per frame is one selection pass however many viewers it has — and
    * there is no longer anything a viewer could be told that another viewer
    * decided.
    *
    * '''A slug nobody is watching records nothing''' and says so
    * ([[FragmentLog.skipped]]). A dashboard with no browser on it is the NORMAL
    * state of a home instance, and the selection pass over every changed entity
    * is the last thing that ran there unconditionally.
    *
    * That gate is safe only because of an ordering [[pageResponse]] keeps: a
    * session is registered BEFORE the snapshot its page renders from is read. A
    * frame that decided to skip did so before that registration, hence before
    * that read, so the version it skipped is one the document already contains
    * — and a pull for it is a no-op rather than a hole. Register later and the
    * window between the two is silent staleness.
    */
  private[runtime] def recordFrame(
      slug: String,
      renderer: Renderer,
      log: Ref[IO, FragmentLog],
      changes: List[StateChange]
  ): IO[Long] =
    (stateStore.current, sessions.openSets(slug), sessions.floor(slug))
      .flatMapN { (store, opens, floor) =>
        val before = Patches.beforeSnapshot(store.entities, changes)
        // Membership is applied to the graph BEFORE the gate, and for every
        // group rather than the visible ones: the member graph tracks the state
        // stream, not who is watching. A frame that records nothing still moves
        // members, and the page that loads after it renders from the graph.
        val membership =
          renderer.members.syncMembers(changes, before, store.entities)
        if (opens.isEmpty)
          log.update(_.skipped(store.version)).as(store.version)
        else {
          // What is worth recording: the surfaces some client can actually SEE,
          // not merely has selected. A tab panel inside a hidden `If` branch is
          // in its client's open set and on nobody's screen. Each session is
          // filtered against its OWN set before the union, because a chain is
          // one client's.
          val visible = opens
            .flatMap(o =>
              o.filter(renderer.visibleSurface(_, o, store.entities))
            )
            .toSet
          val req = Patches.plan(
            renderer,
            store.entities,
            before,
            membership,
            store.version,
            changes,
            visible
          )
          // Written and pruned in ONE update, so the log a session reads is
          // never one where this frame has landed but the stale history it
          // makes prunable is still there — and, more to the point, so a
          // concurrent write cannot be lost between two of them.
          log
            .update(l =>
              floor.foldLeft(Patches.record(renderer, l, req))(_.pruned(_))
            )
            .as(store.version)
        }
      }

  /** Drop the session a later document in the same tab superseded, unless a
    * stream is still holding it ([[Session.supersede]]). Deregistered under
    * reference equality, so a `conn` some later document happens to reuse is
    * never unrouted.
    */
  private def retire(conn: String): IO[Unit] =
    sessions.get(conn).flatMap {
      case None    => IO.unit
      case Some(s) =>
        s.supersede.flatMap(IO.whenA(_)(sessions.deregisterIf(conn, s)))
    }

  /** The per-connection SSE stream: a `conn` signal, then the slug's shared
    * patches (filtered to what this client can see, with any [[Varying]]
    * resolved against its selections), the session control channel, live-reload
    * body repaints, and a heartbeat. An unknown slug is a 404 — the gate lives
    * at the tail, on the stream's own single lookup ([[liveFor]]).
    */
  private def sseStream(slug: String, req: Request[IO]): IO[Response[IO]] =
    val uiState = Server.uiStateOf(req)
    for {
      // The session was established by the document this stream belongs to,
      // which is where its `holds` came from. A `conn` naming nothing — a
      // reaped session, a bookmarked SSE URL, a server restart — is not an
      // error: a fresh session is minted under the SAME id, so the client keeps
      // the `conn` it already has and only loses the suppression its `holds`
      // would have given (bytes, never staleness).
      // `None` means this URL named no session — a bookmarked SSE endpoint, or
      // a client whose document predates the signal. Every ordinary load
      // carries one, because the document minted it.
      named = Server.connOf(req)
      conn <- named.fold(IO.randomUUID.map(_.toString))(IO.pure)
      // This tab's PREVIOUS session, named by the page that replaced it. A
      // reload mints a fresh `conn` (the document is the only thing that can
      // say what it painted), so without this the session it replaced sits in
      // the registry for the whole linger window holding an old `position` —
      // and the floor is the LOWEST position, so a handful of reloads keeps the
      // changelog un-prunable for minutes. Retiring is all that is wanted here:
      // the old `holds` describes a DOM that no longer exists, so there is
      // nothing in it worth adopting.
      _ <- Server.prevConnOf(req).filterNot(_ == conn).traverse_(retire)
      adopted <- adoptOrMint(slug, conn)
      (session, epoch) = adopted
      liveOpt <- liveFor(slug)
      rendererOpt <- liveOpt
        .traverse(_.renderer.get)
        .map(_.flatMap(_.rendererOf))
      // Seed the open set from this client's ui state — its selected tab
      // panels AND the popup it says it still has open, which is now the same
      // kind of selection read from the same map — so all of them receive live
      // updates from the first paint and a reconnect does not silently orphan
      // the dialog on screen.
      // Warn on any off ui-state value.
      _ <- Server
        .cursorAnomaly(req)
        .traverse_(w => IO.println(s"[warn] $w"))
      _ <- rendererOpt.traverse_ { r =>
        warnAnomalies(r, uiState) *>
          session.open.set(
            r.selectedSurfaces(uiState)
          )
      }
      // On (re)connect, heal whatever the DOM missed while the stream was down —
      // the shared/per-session passes only stream FUTURE changes, so without this
      // a reconnected client would show pre-drop values until each entity next
      // ticks. Either the cursor names precisely what this DOM holds (resume), or
      // the whole body is repainted from the current snapshot.
      // Home-Assistant-feed liveness, PUSHED from the server (it owns the
      // `healthy` signal). Emitted on connect as well as on transitions, even
      // though the document now seeds the true value: the window between that
      // render and this connect is a parse, a module load and a round trip, and
      // health moving inside it would otherwise leave a wrong banner up until
      // the next transition — which can be hours. One small signal per connect,
      // and the alternative (having the client send its value back so the
      // server can compare) costs the same bytes on every reconnect instead.
      //
      // This is concept 1 of the two disconnect concepts
      // (see [[Server.page]]): the backend knows when it can't reach HA, so it
      // emits the `haDown` signal directly rather than the client inferring it
      // from a stalled beat. Concept 2 (browser<->server transport) stays
      // client-side — only the browser can observe its own dropped SSE.
      healthPatch = (h: Boolean) =>
        Datastar.patchSignals(s"""{"${Server.HaDownSignal}":${!h}}""")

      control = Stream.fromQueueUnterminated(session.control)
      reloads = reloadRepaints(session, uiState)
      // Emit `haDown` on connect (the initial `discrete` value) and on every
      // health transition.
      // ...and only when it differs from what this client was last told. The
      // document renders the banner's value into the page and records it on the
      // session, so an ordinary load is already correct and needs no patch;
      // what survives is the case this exists for, health moving between that
      // render and this connect.
      haDown = healthy.discrete.changes.evalMapFilter { h =>
        val down = !h
        session.haDown.modify {
          case Some(`down`) => (Some(down), None)
          case _            => (Some(down), Some(healthPatch(h)))
        }
      }
      // What this client has actually been TOLD, as opposed to what it has been
      // served (`session.position`), now lives on the SESSION: a reconnect
      // measures its cursor against it ([[openingPatches]]), so it has to
      // outlive the stream that announced it. The floor still reads `position`,
      // whose semantics are written up on [[Session]].
      // Something for an idle connection to carry, so an intermediary doesn't
      // reap it — and the place the cursor catches up, since a pull that owed
      // this client nothing now sends nothing at all. A quiet house still costs
      // only the COMMENT (see [[Server.KeepAliveInterval]]); the signal goes out
      // once per position change and then stops.
      keepAlive = Stream
        .awakeEvery[IO](Server.KeepAliveInterval)
        .evalMap(_ =>
          (session.position.get, session.told.get).flatMapN {
            (position, sent) =>
              if (position == sent) IO.pure(Server.keepAliveComment)
              else
                session.told.set(position).as(Server.versionSignal(position))
          }
        )

      // This connection PULLS. The doorbell says how far its slug's changelog
      // reaches; everything else — what changed, whether this client already has
      // it, which surfaces it can see — is answered here, against this session's
      // own record, by the same `Patches.resume` a reconnect runs.
      //
      // No subscription to acquire and so no window to nest around: a
      // `SignallingRef` hands a new subscriber the current value, so a frame
      // recorded before this stream existed still wakes it. `.discrete`
      // coalescing is wanted too — versions landing while this session renders
      // collapse into one pull, which is what a slow client should get.
      live = Stream
        .eval(liveOpt.traverse(l => session.open.get.map(l -> _)))
        .flatMap {
          case None            => Stream.empty
          case Some((l, open)) =>
            Stream
              .eval(openingPatches(slug, l, session, req, uiState, open))
              .flatMap(Stream.emits) ++
              l.doorbell.discrete
                .evalMap(pull(l, session, _))
                .flatMap(Stream.emits)
                .merge(control)
                .merge(reloads)
                .merge(haDown)
                .merge(keepAlive)
        }

      // Registration is BRACKETED to the stream rather than done in the handler
      // above: a handler that registers and then never reaches a running body —
      // it raised, or ember dropped the response — would leave the session in
      // the registry for the life of the process, and every leftover one is
      // read by `openSets` on every state batch. Acquiring here is still early
      // enough: the `conn` signal a client needs before it can POST an action
      // is the first element of this same stream.
      //
      // The release does not deregister: it hands the session to the LINGER
      // ([[Session.release]]), so a client that drops and comes back inside the
      // window resumes against its own `holds` instead of being repainted.
      // Conditional on still owning it, because a displaced stream releases
      // after its successor has already taken over and must not put a live
      // session out to pasture on its way out.
      stream = (Stream.bracket(sessions.register(conn, session))(_ =>
        session
          .release(epoch)
          .flatMap(_.traverse_(reapAfter(conn, session, _, lingerWindow)))
        // Announced ONLY when this stream minted it. The document seeds `conn`
        // into the page's signals and puts it on this URL, so telling an ordinary
        // load its own id is telling it something it already said — one patch per
        // connect, and every reconnect is a connect.
      ) >> (Stream.emits(
        Option
          .when(named.isEmpty)(
            Datastar.patchSignals(s"""{"${Server.ConnSignal}":"$conn"}""")
          )
          .toList
      ) ++ live))
        // A second live stream for one session DISPLACES the first. Two streams
        // sharing one `holds` map is the one way this record can go wrong on
        // its own: each would record bytes the other sent, and each would then
        // suppress a change the client never received. Sending to a socket
        // nobody reads is merely wasteful; claiming a digest for it is
        // permanent staleness.
        .interruptWhen(session.tenure.discrete.map(_ != Tenure.Held(epoch)))
      // The 404 gate is on THIS stream's own (single) lookup, not in the route:
      // a route-side registry read and this one could disagree (a slug
      // removed between them) and answer a 200 empty-body SSE instead of a
      // 404. Nothing has been registered for an absent slug by this point —
      // registration is bracketed to `stream`, which never runs — and the
      // session `adoptOrMint` created is unreferenced garbage.
      resp <- liveOpt match
        case None    => NotFound()
        case Some(_) => Ok(stream)
    } yield resp

  /** The error page's recovery stream ([[errorPage]]): the slug's
    * `Failed -> Ready` transitions as reloads, and nothing else — no session,
    * no `conn`, no holds, no cursor, no `openingPatches`
    * ([[recoverTransitions]]).
    *
    * The ONE slug lookup lives here, not in the route: a slug removed between a
    * route-side lookup and the stream would have answered a 200 empty-body SSE
    * instead of a 404.
    */
  private def recoverStream(slug: String): IO[Response[IO]] =
    liveFor(slug).flatMap {
      case None       => NotFound()
      case Some(live) => Ok(recoverTransitions(live).merge(keepAliveComments))
    }

  /** The recover stream's state changes ([[recoverStream]]), as the error page
    * must react to them. Its FIRST element doubles as the connection marker: a
    * comment under `Failed` (the browser discards SSE comments before any
    * listener, so Datastar never even receives it) or an immediate reload when
    * the fix landed between the page's render and this connect. After that, the
    * one rule: reload unless the state is an UNCHANGED `Failed` — the page
    * already shows that message, so a reload would just loop.
    */
  private def recoverTransitions(
      live: Server.LiveSlug
  ): Stream[IO, ServerSentEvent] =
    live.renderer.discrete.zipWithPrevious.map {
      // The connection marker: the stream's FIRST element, sent once it has
      // subscribed under the CURRENT state — a comment under `Failed`, or an
      // immediate reload when the fix landed between the page's render and
      // this connect.
      case (None, st) =>
        Some(
          if (st.rendererOf.isDefined) Server.reloadPatch
          else Server.recoverOpenMarker
        )
      // The one rule: reload unless the state is an UNCHANGED `Failed`.
      case (prev, st) =>
        Option.unless(unchangedFailed(prev, st))(Server.reloadPatch)
    }.unNone

  /** Whether the state change is a no-op for the error page: still `Failed`
    * with the SAME message, so the page already shows it and a reload would
    * just loop. Every other change — a fix, a break, a re-broken edit with a
    * different error — must repaint the page.
    */
  private def unchangedFailed(
      prev: Option[Server.RendererState],
      current: Server.RendererState
  ): Boolean =
    (prev, current) match
      case (
            Some(Server.RendererState.Failed(m1)),
            Server.RendererState.Failed(m2)
          ) =>
        m1 == m2
      case _ => false

  /** A pure keep-alive comment at the shared stream's cadence — what a stream
    * that only waits on state ([[recoverStream]]) needs to keep an intermediary
    * from reaping it. The shared stream's keep-alive is richer (it catches the
    * cursor up too), so it drives its own; both read the same interval and the
    * same [[Server.keepAliveComment]].
    */
  private val keepAliveComments: Stream[IO, ServerSentEvent] =
    Stream.awakeEvery[IO](Server.KeepAliveInterval).as(Server.keepAliveComment)

  /** One session's pull: what THIS client is owed from `position + 1`, rendered
    * against the current snapshot and its own selections.
    *
    * `position + 1` exactly, where a client's cursor gets `>=`
    * ([[resumeFrom]]). The difference is who is claiming: a client can hold
    * version V having seen only part of it, where a position is what this
    * server itself last SENT, so V is complete by construction.
    *
    * The doorbell's version is what the position advances to, not the store's:
    * the snapshot may already be ahead of what the changelog describes, and
    * claiming that would skip whatever the next frame is about to record.
    *
    * A pull ALWAYS advances and always says so, even when it owed this client
    * nothing. Under a shared push the cursor could only advance where a batch
    * had been decided for everybody, so a client whose patches were all
    * filtered away still had to be told; here "nothing owed" is computed for
    * THIS client against its own record, which is exactly the claim the cursor
    * makes. The signal is also what tells a browser the frame reached it.
    */
  private[runtime] def pull(
      live: Server.LiveSlug,
      session: Session,
      version: Long
  ): IO[List[ServerSentEvent]] =
    session.position.get.flatMap { position =>
      if (version <= position) IO.pure(Nil)
      else
        (
          OptionT(live.renderer.get.map(_.rendererOf)),
          OptionT.liftF(live.log.get),
          OptionT.liftF(stateStore.current),
          OptionT.liftF(session.holds.get),
          OptionT.liftF(session.open.get)
        ).flatMapN { (renderer, log, store, holds, open) =>
          // A failed dashboard has nothing to render: the silent frame, the
          // same bytes a version this client is owed nothing for produces.
          // `rendererOf` is the tuple's option: a None short-circuits the
          // flatMap before any of the refs below are even run.
          Patches
            .resume(
              renderer,
              live.cache,
              log,
              holds,
              store.entities,
              position + 1,
              open,
              // The LIVE selection, not the one this connection arrived with: a
              // tab select moves it mid-stream.
              renderer.uiStateFrom(open)
            )
            .flatMap { patches =>
              session.holds
                .update(patches.foldLeft(_)(Patches.applied)) *>
                // The cursor rides LAST below, which is what makes it an ack:
                // a client echoing it applied the patches in front of it. Only
                // recorded when there are bytes — a silent frame announces
                // nothing, so `told` must not move for it.
                IO.whenA(patches.nonEmpty)(session.told.set(version)) *>
                session.position
                  .set(version)
                  // `position` advances whatever happened; the SIGNAL only goes
                  // out with bytes it belongs to. A frame this client was owed
                  // nothing for is silence on the wire, and the keepalive
                  // carries the cursor forward within one interval — safe for
                  // the reasons written up on [[Session.position]].
                  .as(
                    if (patches.isEmpty) Nil
                    else
                      // The cursor goes through `encode` as a patch rather than
                      // being appended as an event, so it MERGES with this
                      // batch's own signal frame when nothing separates them —
                      // which is every batch whose nodes only moved a signal
                      // slot. It stays a separate, trailing event whenever an
                      // element patch sits in between, which is what its ack
                      // meaning requires.
                      Patches.encode(
                        patches :+
                          Addressed(Server.versionPatch(version))
                      )
                  )
            }
            .pipe(OptionT.liftF)
        }.value
          .map(_.getOrElse(Nil))
    }

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

  /** What a (re)connecting client is sent before the live streams start. Three
    * outcomes, narrowest first (ADR 0011): '''reload''' when the `<head>`'s
    * unpatchable part moved ([[Renderer.headHash]]) and nothing else is worth
    * sending; '''resume''' when the cursor provably names what this DOM holds;
    * '''repaint''' the whole body.
    *
    * A stale theme or `<title>` ([[Renderer.styleHash]]) is orthogonal to all
    * three and is repaired by [[headPatches]] in front of whichever applies, so
    * a re-themed dashboard does not cost the client its scroll position.
    *
    * '''The repaint is where every doubt lands.''' It is always correct and
    * merely expensive, where a wrong resume is silently stale forever.
    *
    * The log is read ONCE, outside any `modify`, so a reconnect never
    * serializes against the live diff path.
    *
    * '''The doorbell is read BEFORE the log, and that order is load bearing.'''
    * A resume can only answer for versions the CHANGELOG describes, and the
    * store runs ahead of it: the recorder writes the log on its own fiber, so
    * between a change landing in the store and being recorded there is a window
    * in which `store.version` names a change `since` cannot see. Claiming that
    * version would tell the client it is current through a change it was never
    * sent — and the pull that would have carried it is then skipped (`version
    * <= position`), so it is lost until that entity next moves. Reading the
    * doorbell first bounds the claim by what was knowable before we looked; a
    * change recorded while we were looking is simply re-offered by the next
    * pull, which this client's `holds` then suppresses if it turns out to have
    * it.
    *
    * A REPAINT is the exception and claims the store: it renders the whole body
    * from that snapshot, so the client provably holds all of it.
    */
  private def openingPatches(
      slug: String,
      live: Server.LiveSlug,
      session: Session,
      req: Request[IO],
      uiState: Map[String, String],
      open: Set[String]
  ): IO[List[ServerSentEvent]] =
    (
      OptionT.liftF(live.doorbell.get),
      OptionT(live.renderer.get.map(_.rendererOf)),
      OptionT.liftF(live.log.get),
      OptionT.liftF(stateStore.current),
      OptionT.liftF(session.holds.get),
      OptionT.liftF(session.told.get)
    )
      .flatMapN { (covered, renderer, log, store, holds, told) =>
        val cursor = Server.cursorOf(req)
        if (cursor.exists(_.headHash != renderer.headHash))
          OptionT.pure[IO](List(Server.reloadPatch))
        else {
          val head =
            if (cursor.exists(_.styleHash != renderer.styleHash))
              Server.headPatches(renderer, slug)
            else Nil
          // `Patches.resume` is TOTAL — a container whose history aged out is
          // answered with a fill for THAT mount, not a refusal — so the only
          // reasons left to repaint the body are the genuinely global ones
          // checked here: no cursor at all, a cursor minted against another log
          // (a restart or a renderer swap, which is every dashboard change),
          // one ahead of this store (a restart with a rewound counter), or one
          // from before a GAP — a stretch this slug passed over because nobody
          // was watching it ([[FragmentLog.reaches]]), which is what a client
          // returning after its session was reaped presents.
          // ...plus the one thing only the CLIENT can answer: did it actually
          // apply what we last claimed it has? A resume trusts `holds`, and
          // `holds` records what was SENT, which is not proof of receipt — a
          // stream that broke mid-batch, or a tab frozen while the socket kept
          // filling, leaves this session claiming digests that DOM never got,
          // and every later resume then computes "nothing owed" forever.
          //
          // The cursor is that proof. It is server-set, but it rides LAST in
          // its batch (`pull`), so a client echoing version V demonstrably
          // applied everything before it. Behind `told` ⇒ bytes we claimed were
          // lost ⇒ `holds` is unproven and the body is repainted.
          //
          // This does NOT fire on an ordinary tab switch: while a stream is
          // closed nothing is sent, so `told` cannot move, and the returning
          // client's echo still matches it.
          val resumedIO = cursor
            .filter(c =>
              c.logId == log.id && c.version <= store.version &&
                log.reaches(c.version) && c.version >= told
            )
            .traverse(c =>
              Patches
                .resume(
                  renderer,
                  live.cache,
                  log,
                  holds,
                  store.entities,
                  resumeFrom(req, c),
                  open,
                  uiState
                )
            )
          // Lazy: rendering the whole body is the cost this exists to avoid.
          // TRACED, because a repaint is the largest thing that ever puts
          // fragments in this DOM and it knows exactly what it put where — the
          // same claim the DOCUMENT makes from the same render. Clearing
          // `holds` instead would leave the client's open surfaces unclaimed
          // and re-sent on the very next pull.
          lazy val painted = renderer.renderBodyTraced(store.entities, uiState)
          lazy val repaint = Datastar.patch(
            painted.html,
            PatchMode.Inner,
            Some("#dashboard")
          )
          // An open popup needs no restore branch of its own: its nodes are in
          // `open`, so the resume rule reconciles them on their own ids, and a
          // body repaint replaces `#dashboard` only — `#popups` lives in the
          // chrome outside it, so the dialog is never disturbed.
          //
          // What DOES need saying is a claim this dashboard no longer recognises
          // (its surface renamed or removed): that dialog belongs to nothing, is
          // in nobody's open set, and would otherwise sit on screen forever.
          val orphan = Option
            .when(
              uiState.get(Dashboard.PopupHostId).exists(_.nonEmpty) &&
                renderer.openPopup(uiState).isEmpty
            )(
              Datastar.patch(
                s"""<div id="${Dashboard.PopupHostId}"></div>""",
                PatchMode.Outer,
                None
              )
            )
            .toList
          // What this connection is about to be told, recorded against the
          // session before it is told: a resume's patches establish and
          // invalidate exactly as a live one's do, and a REPAINT forgets
          // everything — it replaces the body wholesale with no per-node trace,
          // so every claim the document made now describes bytes that are gone.
          // ...and the position with it, which is what the pull loop starts
          // from. A repaint painted the whole snapshot, so it claims that; a
          // resume could only answer for what the changelog covered when this
          // connection began, so it claims THAT — see the doorbell note above.
          val result = resumedIO.flatMap { resumed =>
            val claim = resumed.fold(store.version)(_ => covered)
            val record = resumed.fold(
              session.holds.set(painted.own.map { case (id, p) =>
                id -> Held(Some(Digest.of(p.html)), p.signals)
              })
            )(patches =>
              session.holds.update(patches.foldLeft(_)(Patches.applied))
            ) *> session.position.set(claim) *> session.told.set(claim)
            record.as(
              head ++ resumed.fold(List(repaint))(_.map(_.patch.toSse)) ++
                orphan :+ Server.cursorSignals(renderer, log.id, claim)
            )
          }

          OptionT.liftF(result)
        }
      }
      .value
      // A failed slug has no document to open, so no claim to bookkeep:
      // whatever a stale connection or a bookmarked SSE URL asks for, the
      // error page is a reload away. Defensive — the error page opens the
      // dedicated `recover` stream instead, so this is only reachable by a
      // slug that went `Failed` mid-session or by direct URL entry.
      .map(_.getOrElse(List(Server.reloadPatch)))

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
          .map { case (previous, current) =>
            (previous.flatMap(_.rendererOf), current.rendererOf)
          }
          .evalMap {
            case (_, None) | (None, _) =>
              // A swap that involved a failed dashboard: the page either
              // shows (or is about to show) the error document, which has no
              // #dashboard to target and no head to patch — so the watching
              // connection is told to RELOAD, from the error page to the
              // dashboard or back.
              IO.pure(List(Server.reloadPatch))
            case (Some(prev), Some(r)) if prev.headHash != r.headHash =>
              IO.pure(List(Server.reloadPatch))
            case (Some(prev), Some(r)) =>
              // The repaint re-bakes the body (selected tabs included), so
              // re-seed the open set to match. Reuses this client's selection
              // (closed over).
              (session.open.set(r.selectedSurfaces(uiState)) *>
                (stateStore.current, live.log.get).tupled)
                .flatMap { case (store, log) =>
                  val head =
                    if (prev.styleHash != r.styleHash)
                      Server.headPatches(r, session.slug)
                    else Nil
                  // A repaint painted the whole snapshot, so this client is
                  // both served and told through it — the same claim
                  // [[openingPatches]] makes for its own repaint. Leaving
                  // `told` behind here would let the keepalive announce a LOWER
                  // version than the swap just did.
                  // TRACED, so the repaint says what it painted — the same
                  // claim `openingPatches` makes for its own. Load-bearing for
                  // signal slots: this body carries fresh inline seeds, so a
                  // record left describing the PREVIOUS dashboard's values
                  // would suppress the frame a value's return needs.
                  val painted = r.renderBodyTraced(store.entities, uiState)
                  session.holds.set(painted.own.map { case (id, p) =>
                    id -> Held(Some(Digest.of(p.html)), p.signals)
                  }) *>
                    session.position.set(store.version) *>
                    session.told
                      .set(store.version)
                      .as(
                        head ++ List(
                          Datastar.patch(
                            painted.html,
                            PatchMode.Inner,
                            Some("#dashboard")
                          ),
                          // A swap rotates the log identity and can move the style
                          // hash, and live batches carry only the version now — so
                          // this is where the client learns the rest. Without it a
                          // reconnect would quote a log that no longer exists and be
                          // answered with a body repaint.
                          Server.cursorSignals(r, log.id, store.version)
                        )
                      )
                }
          }
          .flatMap(Stream.emits)
      }

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
    * open", and one host-swap primitive covers open, close and stack alike.
    *
    * A swap of the POPUP host does NOT touch the client's `ui_<hostId>` — the
    * tap that asked for the swap already set it, exactly as a tab button sets
    * its own. One mechanism for every selection, and the browser keeps the one
    * bit of per-session state a reconnect restores the dialog from.
    *
    * The fill itself — render, and say what it put where — is
    * [[Patches.hostFill]]. What stays here is the half a state-group flip does
    * NOT share: a tab switch is one client's choice, so it records no
    * [[Mutation]] and its trace goes to that session alone, where a flip is
    * server truth every client must be replayed.
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
      store <- stateStore.current
      states = store.entities
      // The arriving surface, rendered once — the bytes go to this connection
      // and the per-node trace to THIS SESSION's record. Nothing shared is
      // touched: one client switching a tab says nothing about anyone else's
      // DOM, and no [[Mutation]] is recorded for the same reason.
      filled = Patches.hostFill(renderer, host, newSurface, states, uiState)
      _ <- filled match {
        case Some((patch, html)) =>
          session.holds.update(Patches.applied(_, patch)) *>
            session.control.offer(
              Datastar.patch(html, PatchMode.Inner, Some("#" + host))
            )
        // Nothing holds the host now: the popup closed, or the surface would
        // not render. Its contents leave this client's DOM, so its claims go
        // with them.
        case None =>
          session.holds.update(
            _ -- Patches.hostEvicts(renderer, host)
          ) *> IO.whenA(newSurface.isEmpty)(
            session.control.offer(
              Datastar.patch(
                s"""<div id="$host"></div>""",
                PatchMode.Outer,
                None
              )
            )
          )
      }
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

  /** The stream that owns `conn` for this request, and the epoch it now holds.
    *
    * A `conn` naming nothing — a reaped session, a bookmarked SSE URL, a server
    * restart, or a client that changed dashboards — is not an error: a fresh
    * session is minted under the SAME id, so the client keeps the `conn` it
    * already has and loses only the suppression its `holds` would have given
    * (bytes, never staleness).
    *
    * A registered session can also be reaped between the lookup and the adopt,
    * which [[Session.adopt]] reports rather than hides — the reaper is allowed
    * to win that race precisely because losing it costs one fatter patch.
    */
  private def adoptOrMint(slug: String, conn: String): IO[(Session, Int)] =
    sessions
      .get(conn)
      .map(_.filter(_.slug == slug))
      .flatMap(_.flatTraverse(s => s.adopt.map(_.map(s -> _))))
      .flatMap {
        case Some(adopted) => IO.pure(adopted)
        // A session minted by its own stream is Held(1) from birth.
        case None => Session.create(slug).flatTap(_.adopt).map(_ -> 1)
      }

  /** Drop `conn`'s session once `after` has passed, unless its tenure has moved
    * off `expected` in the meantime. Both reasons a session waits to die use
    * this: a document that never opened a stream ([[Tenure.Fresh]]) and a
    * stream that ended ([[Tenure.Lingering]]). One mechanism, because they are
    * the same question asked about different states.
    *
    * Necessary because the document, not the stream, creates the session:
    * without this, every abandoned page load would leave one in the registry
    * for the life of the process, and every one of them is read by
    * [[Sessions.openSets]] on every state batch.
    *
    * It cannot race a stream that is starting. [[Session.relinquish]] and
    * [[Session.adopt]] both decide on the same ref, so a reconnect that lands
    * while this sleeps makes the transition fail rather than merely making this
    * read stale — and only a reaper that WON the transition touches the
    * registry, by identity ([[Sessions.deregisterIf]]).
    */
  private def reapAfter(
      conn: String,
      session: Session,
      expected: Tenure,
      after: FiniteDuration
  ): IO[Unit] =
    supervisor
      .supervise(
        IO.sleep(after) *>
          session
            .relinquish(expected)
            .flatMap(IO.whenA(_)(sessions.deregisterIf(conn, session)))
      )
      .void

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
      case Some(renderer) => nodeDebugJson(renderer, id)
    }

  /** The debug payload for a registered slug ([[nodeDebug]]). */
  private def nodeDebugJson(
      renderer: Renderer,
      id: String
  ): IO[Response[IO]] =
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
    *
    * A whole SITE may be pushed too — an evaluated `site.pkl` carries a
    * `dashboards` map, and that is now the natural file to push. Then the keys
    * are the slugs (the URL's is ignored, since a site names its own) and it is
    * all-or-nothing: any dashboard that fails to validate fails the push,
    * because a half-installed site is not a state the developer asked for.
    */
  private def pushResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    req.bodyText.compile.string
      .map(io.circe.parser.parse)
      .flatMap {
        case Left(err) =>
          BadRequest(s"push body is not JSON: ${err.getMessage}")
        case Right(json)
            if json.asObject.exists(_.contains(Site.DashboardsKey)) =>
          pushSite(json)
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
              case e: FHError => FHError.logged(e)
              case err        => InternalServerError(err.getMessage)
            }
      }

  /** Install every dashboard a pushed SITE names, plus its default slug. */
  private def pushSite(json: Json): IO[Response[IO]] =
    Site
      .decode(json)
      .flatMap { site =>
        site.dashboards.collect { case (slug, Left(err)) =>
          s"'$slug': $err"
        } match {
          case Nil =>
            val ready = site.dashboards.collect { case (slug, Right(v)) =>
              slug -> v
            }
            ready.traverse_ { case (_, v) => push(v) } *>
              this.site.setPreferred(site.default) *>
              Ok(
                s"pushed ${ready.size} dashboard(s): ${site.slugs.mkString(", ")}"
              )
          case errors =>
            BadRequest(
              s"site push rejected (${errors.size} dashboard(s) failed):\n" +
                errors.mkString("\n")
            )
        }
      }
      .handleErrorWith {
        case e: FHError => FHError.logged(e)
        case err        => InternalServerError(err.getMessage)
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
    * pkl-core 0.32.1 contains no `If-None-Match`/`ETag`/`Cache-Control`
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
      case Some(live) => pageFor(slug, live, req)
    }

  /** The document for a registered slug ([[pageResponse]]): the error page when
    * its renderer is `Failed`, the full dashboard when it is `Ready`.
    */
  private def pageFor(
      slug: String,
      live: Server.LiveSlug,
      req: Request[IO]
  ): IO[Response[IO]] =
    (
      live.renderer.get,
      live.log.get,
      IO.randomUUID.map(_.toString)
    ).flatMapN { (state, log, conn) =>
      state match
        case Server.RendererState.Failed(message) =>
          errorPage(slug, message, req)
        case Server.RendererState.Ready(renderer) =>
          renderPage(slug, renderer, log, conn, req)
    }

  /** The full dashboard document ([[page]]) for a `Ready` slug: mint this
    * client's session, record every painted node, and hand back the page whose
    * `restore` cursor the stream will answer to.
    */
  private def renderPage(
      slug: String,
      renderer: Renderer,
      log: FragmentLog,
      conn: String,
      req: Request[IO]
  ): IO[Response[IO]] = {
    val uiState = Server.uiStateOf(req)
    // The editor embeds the dashboard as `?edit=1`; that turns on the
    // per-node inspection overlay (Focus / Debug). Off for normal viewers.
    val editMode = req.uri.query.params.get("edit").contains("1")
    // What this document is showing, and so also what it must hand back
    // on connect for the stream to agree with it — the ui state (the
    // open popup included) AND the version it was rendered at. That
    // last part is what stops the first connect repainting a body the
    // document already contains.
    //
    // The popup claim is NARROWED first: a document does not show a
    // dialog this dashboard cannot serve, so it must not seed one back
    // either — on the signal or in the connect URL.
    val restoreUi = renderer.openPopup(uiState) match {
      case Some(sid) => uiState.updated(Dashboard.PopupHostId, sid)
      case None      => uiState - Dashboard.PopupHostId
    }
    // The surfaces this client will have open. They are the resume
    // rule's second candidate set, and they are also what the recorder
    // reads to decide a frame is worth recording at all — hence the
    // ordering below.
    val open = renderer.selectedSurfaces(uiState)
    for {
      // The session this document belongs to, established HERE — the
      // document is the first and largest thing that puts fragments in
      // this client's DOM, and the only place that knows what they were.
      // Its `holds` therefore has ONE meaning for its whole life: bytes
      // this client was sent. It is also why no node needs a per-
      // selection key: this render used THIS viewer's `uiState`, where a
      // shared record would have to hold one digest per selection to
      // avoid claiming somebody else's tab.
      // ONE read, used twice: the banner this page renders and the
      // record of what it told this client must be the same value, or the
      // stream will either repeat it or skip a real change.
      live <- healthy.get
      session <- Session
        .create(slug)
        .flatTap(_.open.set(open))
        .flatTap(_.haDown.set(Some(!live)))
      // REGISTERED BEFORE THE SNAPSHOT IS READ, and that order is load
      // bearing, not tidiness: [[recordFrame]] skips a frame no session
      // is watching, so a session registered after the read could be
      // handed a version the log never described. Registering first makes
      // any skipped version one this page already contains — see the
      // argument in `recordFrame`.
      _ <- sessions.register(conn, session)
      _ <- reapAfter(conn, session, Tenure.Fresh, adoptionWindow)
      store <- stateStore.current
      // ONE render, used twice: the bytes go to the browser and the
      // per-node trace seeds `holds`. Fingerprinting separately means
      // walking the open surfaces a second time, node by node, to
      // re-derive what the page just composed.
      //
      // Every painted node, not just the open surfaces' — the document
      // contains all of it, so recording less would be a claim that is
      // merely narrower, not safer.
      painted = renderer.renderPageTraced(
        store.entities,
        uiState,
        renderer.openPopup(uiState)
      )
      _ <- session.holds.set(painted.own.map { case (id, p) =>
        id -> Held(Some(Digest.of(p.html)), p.signals)
      })
      _ <- session.position.set(store.version)
      // The page renders the cursor into its own signals, so the document
      // IS an announcement — and the first one. Without this a client
      // that connects, misses a batch and reconnects would be measured
      // against -1 and trusted.
      _ <- session.told.set(store.version)
      _ <- warnAnomalies(renderer, uiState)
      // What this document is showing, and so also what it must hand
      // back on connect for the stream to agree with it — the ui state
      // (the open popup included) AND the version it was rendered at.
      // That last part is what stops the first connect repainting a body
      // the document already contains.
      restore = Server.Restore(
        restoreUi,
        conn,
        Some(
          Server.Cursor(
            renderer.headHash,
            renderer.styleHash,
            log.id,
            store.version
          )
        )
      )
      resp <- Ok(
        page(
          slug,
          painted.html,
          renderer.stylesheets.map(assets.rewrite),
          renderer.scripts.map(assets.rewrite),
          renderer.inlineScripts,
          renderer.title,
          Server.ingressPrefixOf(req),
          restore,
          editMode,
          haDown = !live
        )
      )
    } yield resp.withContentType(`Content-Type`(MediaType.text.html))
  }

  /** A self-contained error document for a slug whose eval/build failed: no
    * renderer, so no theme, no session/conn minting, no cursor. The `<base
    * href>` still honors the ingress prefix so links resolve behind the HA
    * proxy. The editor link is the write path — fixing the source here recovers
    * the dashboard live (the reload loop re-evals on the edit).
    *
    * Recovery is Datastar's `@get` on the dedicated `recover` stream
    * ([[recoverStream]]), not a meta-refresh: the module opens
    * `sse/dashboard/<slug>/recover` and the `_reload` signal — sent exactly
    * when the slug transitions `Failed -> Ready` — triggers `data-effect`'s
    * reload. A poll would re-eval on a fixed schedule; this reloads precisely
    * at the moment the fix lands. The Datastar module is the page's only
    * dependency beyond itself.
    */
  private def errorPage(
      slug: String,
      message: String,
      req: Request[IO]
  ): IO[Response[IO]] = {
    val baseHref = Server.ingressPrefixOf(req).fold("/")(p => s"$p/")
    val title = Server.escapeHtml(slug)
    val body =
      s"""<!doctype html>
         |<html>
         |<head>
         |  <meta charset="utf-8">
         |  <meta name="viewport" content="width=device-width, initial-scale=1">
         |  <base href="$baseHref">
         |  <title>Dashboard $title</title>
         |  <script type="module" src="${assets.rewrite(
          Server.DatastarCdn
        )}"></script>
         |</head>
         |<body data-init="@get('sse/dashboard/$slug/recover', ${Server.SseRetry})">
         |  <div data-signals="{${Server.ReloadSignal}: false}"
         |       data-effect="$$${Server.ReloadSignal} && window.location.reload()">
         |    <h1>Dashboard $title failed to build</h1>
         |    <pre>${Server.escapeHtml(message)}</pre>
         |    <p>Fix the source in the editor — the dashboard reloads automatically.</p>
         |    <p><a href="edit/file/${Site.EntryFile}">Edit ${Site.EntryFile}</a></p>
         |  </div>
         |</body>
         |</html>""".stripMargin
    Ok(body).map(_.withContentType(`Content-Type`(MediaType.text.html)))
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
      inlineScripts: List[String],
      title: Option[String],
      ingressPrefix: Option[String],
      restore: Server.Restore,
      editMode: Boolean,
      // Upstream HA liveness AT RENDER TIME. Seeded rather than hardcoded
      // `false`, which is what it was: a page loaded while HA is unreachable
      // then renders as healthy and stays that way until the stream connects
      // and corrects it — a wrong banner on the one screen whose job is to
      // report that. The stream still pushes the value on connect, because the
      // window between this render and that connect is real.
      haDown: Boolean
  ): String = {
    // The theme's inline scripts come LAST of the three, but they are classic
    // scripts among deferred module ones, so they still run first — which is
    // what they are for (a document-level listener the first paint already
    // needs). Emitted verbatim, like `styles` and `chrome`: a theme is authored
    // source, not user input.
    val links = (
      stylesheets
        .map(href => s"""  <link rel="stylesheet" href="$href">""") ++
        scripts
          .map(src => s"""  <script type="module" src="$src"></script>""") ++
        inlineScripts.map(js => s"""  <script>$js</script>""")
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
           |<script src="${FrontendAssets.url(
            "overlay"
          )}"></script>""".stripMargin
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
    // How one fetch event classifies the connection: 2 = gave up, 1 = trying,
    // 0 = fine.
    val sseState =
      "evt.detail.type === 'retries-failed' ? 2 : " +
        "(evt.detail.type === 'retrying' || evt.detail.type === 'error') ? 1 : 0"
    // ...and 2 LATCHES. Every other fetch type classifies as 0, so without this
    // any event following `retries-failed` cleared the banner — and since the
    // handler is debounced, a `finished` arriving in the same 600ms window could
    // swallow the failure before it ever painted. Either way the page went
    // silently back to looking connected while it was not, which is the one
    // state the banner exists to make undeniable.
    //
    // Terminal by design: once the retries are exhausted nothing reconnects on
    // its own, so the only ways out are the banner's Reload button and the user
    // reloading — both of which build a new document and a new signal store.
    val sseLatched = s"$$_sse >= 2 ? 2 : ($sseState)"
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
    // The popup host is the ONE selection with no card template to seed it —
    // it lives in `theme.chrome`, outside every node — so the shell declares
    // `ui_<hostId>` and mirrors it, exactly as a tabs mount does for its own.
    val popupSignalName = Server.UiSignalPrefix + Dashboard.PopupHostId
    val popupParamName = Server.UiParamPrefix + Dashboard.PopupHostId
    val popupSeed = Server.escapeHtml(
      Server.escapeJsString(
        restore.uiState.getOrElse(Dashboard.PopupHostId, "")
      )
    )
    val connBanner =
      s"""<div data-signals="{${Server.HaDownSignal}: $haDown, _sse: 0, ${Server.ReloadSignal}: false, $popupSignalName: '$popupSeed', ${Server.ConnSignal}: '${Server
          .escapeJsString(restore.conn)}'}"
         |     data-effect="$$${Server.ReloadSignal} && window.location.reload(); fhUrl('$popupParamName', $$$popupSignalName)"
         |     data-on:datastar-fetch__debounce.600ms="$$_sse = $sseLatched">
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
       |  <link rel="manifest" href="${PwaAssets.manifestUrl}">
       |  $pageTitle
       |  <script>${Server.UrlSyncScript}</script>
       |  <script>${Server.swRegisterCall}</script>
       |$links
       |  <script type="module" src="${assets.rewrite(
        Server.DatastarCdn
      )}"></script>
       |</head>
       |<body data-init="@get('sse/dashboard/$slug/patch${restore.query}', ${Server.SseRetry})">
       |<script>fhConn('${Server.escapeJsString(restore.conn)}')</script>
       |$connBanner
       |$body
       |$editAssets
       |<script>${Server.scrollCall(slug)}</script>
       |</body>
       |</html>
       |""".stripMargin
  }
}

object Server {

  /** One slug's live state: either a `Ready` renderer (serving, recording,
    * hot-swappable) or a `Failed` dashboard (a build/eval error — still
    * registered, still watched, served as an error page, recovered by the next
    * successful reload). The ADT lives in the ref so a repair is one `.set`; it
    * is consumed at the top-level seams only ([[Server.rendererFor]],
    * [[Server.publisherFor]], [[Server.openingPatches]],
    * [[Server.reloadRepaints]], `pageResponse`) — everything else reads
    * [[RendererState.rendererOf]] and sees the `Option[Renderer]` the rest of
    * `Server` is written against.
    */
  private[runtime] enum RendererState:
    case Ready(renderer: Renderer)
    case Failed(message: String)

    /** Collapse to the renderer, or `None` for a failed dashboard — the shape
      * every consumer below already models (`rendererFor` returns
      * `Option[Renderer]`, `pull`'s silent frame is `Nil`).
      */
    def rendererOf: Option[Renderer] = this match
      case Ready(r)  => Some(r)
      case Failed(_) => None

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
      renderer: SignallingRef[IO, RendererState],
      log: Ref[IO, FragmentLog],
      // Shared by every session viewing this slug: N sessions woken by one ring
      // of the doorbell render each node once between them. Not rotated on a
      // renderer swap — [[RenderCache]] invalidates by renderer identity, which
      // has no window a pull can slip through.
      cache: RenderCache,
      // The doorbell: the newest store version this slug's changelog covers.
      // Sessions watch it and pull; nothing is pushed. `.discrete` coalescing is
      // the point — several versions landing while a session renders collapse
      // into one pull, and a new subscriber gets the current value immediately,
      // so there is no window between connecting and being caught up.
      doorbell: SignallingRef[IO, Long]
  )

  private[runtime] object LiveSlug {
    def of(state: RendererState): IO[LiveSlug] =
      SignallingRef[IO].of(state).flatMap(create)

    def create(renderer: SignallingRef[IO, RendererState]): IO[LiveSlug] =
      (
        freshLog.flatMap(Ref[IO].of),
        RenderCache.create,
        SignallingRef[IO].of(0L)
      ).mapN(LiveSlug(renderer, _, _, _))
  }

  /** An empty log with a fresh identity. Minted per slug at startup and again
    * on every renderer swap.
    */
  private[runtime] val freshLog: IO[FragmentLog] =
    IO.randomUUID.map(id => FragmentLog(id.toString))

  /** Where a live slug came from — and, for one the ENTRYPOINT names, what it
    * last evaluated to.
    *
    * The origin is what makes the two rules the reload path has to obey
    * checkable rather than remembered: only a [[Origin.FromSite]] slug may be
    * reclaimed (a [[Origin.Pushed]] one is nobody's to delete, ADR 0010), and a
    * slug whose content is unchanged must not be re-installed (a write rotates
    * the fragment log and repaints every open browser).
    *
    * The content is the `Dashboard` model, not the [[Renderer]] built from it:
    * two evaluations of an unedited file produce equal models and different
    * renderers.
    */
  private[runtime] enum Origin {
    case FromSite(content: Either[String, Dashboard])
    case Pushed
  }

  private[runtime] case class Entry(live: LiveSlug, origin: Origin)

  /** One transition [[LiveSite]] made, and the line it is worth in the log.
    * `None` is deliberate: a dashboard that changed and still builds is the
    * ordinary case, already covered by the reload's summary line.
    */
  private[runtime] enum Change {
    case Added(slug: String, error: Option[String])
    case Broke(slug: String, error: String)
    case Recovered(slug: String)
    case Rebuilt(slug: String)
    case Removed(slug: String)

    def describe: (String, Option[String]) = this match {
      case Added(slug, None)      => (slug, Some(s"Dashboard '$slug' added"))
      case Added(slug, Some(err)) =>
        (slug, Some(s"Dashboard '$slug' added, but broken: $err"))
      case Broke(slug, err) =>
        (slug, Some(s"Dashboard '$slug' is now broken: $err"))
      case Recovered(slug) => (slug, Some(s"Dashboard '$slug' recovered"))
      case Rebuilt(slug)   => (slug, None)
      case Removed(slug)   =>
        (slug, Some(s"Dashboard '$slug' removed (no longer in the site)"))
    }
  }

  /** Every dashboard the instance serves right now, what each of them was built
    * from, and which of them answers `/` — the live counterpart of the
    * entrypoint's `dashboards` map (ADR 0021). Owned by the caller rather than
    * by [[Server]] because the reload path (`ServerApp`) writes it while the
    * routes read it.
    *
    * Membership is a `SignallingRef` for one reason: [[changes]] is what starts
    * and stops the per-slug recorders ([[Server.sharedPatchPublishers]]), so
    * installing or removing a slug IS starting or stopping its publisher, with
    * no second path to keep in step.
    *
    * '''An evaluated site is applied here, not diffed by the caller.'''
    * [[applySite]] takes what the entrypoint evaluated to and works out the
    * installs, the swaps and the removals itself, because that decision needs
    * the previous content — which lives beside the slug ([[Origin]]). A caller
    * keeping its own copy of that content is the same fact in two places, and
    * nothing would notice the two drifting apart.
    */
  private[runtime] class LiveSite(
      entries: SignallingRef[IO, Map[String, Entry]],
      // The slug the entrypoint asked to serve at `/`, re-set by every reload.
      // A preference, not an answer: it may name a slug that no longer exists.
      preferred: Ref[IO, Option[String]],
      // Served at `/` when the instance has nothing at all — a workspace whose
      // entrypoint has never evaluated. It is the slug the boot registered its
      // `Failed` state under, so `/` shows the error rather than a 404.
      fallback: String
  ) {

    def liveFor(slug: String): IO[Option[LiveSlug]] =
      entries.get.map(_.get(slug).map(_.live))

    def names: IO[List[String]] = entries.get.map(_.keys.toList.sorted)

    def changes: Stream[IO, Map[String, LiveSlug]] =
      entries.discrete.map(_.view.mapValues(_.live).toMap)

    /** Install a dashboard the developer PUSHED (ADR 0010): a swap for a slug
      * already registered (which repaints its open connections), otherwise a
      * new dashboard. A slug the entrypoint owns keeps its origin, so the next
      * reload restores it; a new one is [[Origin.Pushed]] and therefore outside
      * everything [[applySite]] may reclaim.
      */
    def installPushed(slug: String, state: RendererState): IO[Unit] =
      LiveSlug.of(state).flatMap { fresh =>
        entries
          .modify { es =>
            es.get(slug) match {
              case Some(existing) => (es, Some(existing.live))
              case None => (es + (slug -> Entry(fresh, Origin.Pushed)), None)
            }
          }
          .flatMap(_.traverse_(_.renderer.set(state)))
      }

    /** Apply an evaluated entrypoint: install what is new or changed, leave
      * what is unchanged alone, and drop the slugs the site no longer names.
      *
      * Only a slug this same method installed is ever dropped — a pushed one is
      * not the entrypoint's to reclaim, which the origin decides rather than
      * the caller remembering.
      */
    /** The slug the SITE asks for at `/` — a preference, since it may name a
      * dashboard that does not exist. Set by every reload ([[applySite]]) and
      * by a pushed site, which names its own default the same way.
      */
    def setPreferred(slug: Option[String]): IO[Unit] = preferred.set(slug)

    def applySite(
        dashboards: List[(String, Either[String, Dashboard.Validated])],
        prefer: Option[String]
    ): IO[List[Change]] =
      for {
        _ <- setPreferred(prefer)
        current <- entries.get
        plan = planSite(current, dashboards)
        // Only a slug that actually changes pays for a renderer, a fresh log
        // and a cache; an unchanged one costs a comparison.
        installs <- plan.installs.traverse { case (slug, result, change) =>
          val state = stateOf(result)
          val entry = Entry(_, Origin.FromSite(result.map(_.dashboard)))
          current.get(slug) match {
            case Some(existing) =>
              IO.pure((slug, entry(existing.live), state, change))
            case None =>
              LiveSlug.of(state).map(live => (slug, entry(live), state, change))
          }
        }
        _ <- entries.update { es =>
          installs.foldLeft(es -- plan.removals) {
            case (acc, (slug, e, _, _)) =>
              // A slug that appeared since `entries.get` (a concurrent push) keeps
              // ITS live slug — the fresh one built above is dropped rather than
              // swapped in under open connections.
              acc + (slug -> acc.get(slug).fold(e)(o => e.copy(live = o.live)))
          }
        }
        _ <- installs.traverse_ { case (_, entry, state, _) =>
          entry.live.renderer.set(state)
        }
      } yield installs.map(_._4) ++ plan.removals.toList.sorted.map(
        Change.Removed(_)
      )

    /** Every dashboard the ENTRYPOINT owns shows `message`: what a site that
      * will not EVALUATE means, since nothing can be attributed to one slug.
      * Membership is untouched — the file no longer says what it is — and a
      * pushed slug is not the entrypoint's to break.
      */
    def failSite(message: String): IO[List[Change]] =
      entries.get.flatMap { current =>
        val broken = current.toList.sortBy(_._1).collect {
          case (slug, Entry(live, Origin.FromSite(was)))
              if was != Left(message) =>
            (
              slug,
              live,
              was.fold(
                _ => Change.Rebuilt(slug),
                _ => Change.Broke(slug, message)
              )
            )
        }
        entries.update(es =>
          broken.foldLeft(es) { case (acc, (slug, _, _)) =>
            acc.updatedWith(slug)(
              _.map(_.copy(origin = Origin.FromSite(Left(message))))
            )
          }
        ) *>
          broken
            .traverse_ { case (_, live, _) =>
              live.renderer.set(RendererState.Failed(message))
            }
            .as(broken.map(_._3))
      }

    /** The slug `/` serves right now. */
    def defaultSlug: IO[String] =
      (preferred.get, names).mapN(defaultSlugFor).map(_.getOrElse(fallback))
  }

  /** What [[LiveSite.applySite]] has to do, decided purely from the current
    * entries and the evaluated site — the part worth testing without a server.
    */
  private[runtime] case class SitePlan(
      installs: List[(String, Either[String, Dashboard.Validated], Change)],
      removals: Set[String]
  )

  private[runtime] def planSite(
      current: Map[String, Entry],
      dashboards: List[(String, Either[String, Dashboard.Validated])]
  ): SitePlan = {
    val installs = dashboards.sortBy(_._1).flatMap { case (slug, result) =>
      val content = result.map(_.dashboard)
      current.get(slug) match {
        case Some(Entry(_, Origin.FromSite(was))) if was == content => None
        case Some(Entry(_, Origin.FromSite(was)))                   =>
          Some(
            (
              slug,
              result,
              (was, content) match {
                case (Left(_), Right(_))  => Change.Recovered(slug)
                case (_, Left(message))   => Change.Broke(slug, message)
                case (Right(_), Right(_)) => Change.Rebuilt(slug)
              }
            )
          )
        case Some(Entry(_, Origin.Pushed)) =>
          Some((slug, result, Change.Rebuilt(slug)))
        case None =>
          Some((slug, result, Change.Added(slug, content.left.toOption)))
      }
    }
    val named = dashboards.map(_._1).toSet
    val removals = current.collect {
      case (slug, Entry(_, Origin.FromSite(_))) if !named.contains(slug) => slug
    }.toSet
    SitePlan(installs, removals)
  }

  /** A dashboard that built becomes a live renderer; one that did not becomes
    * its error page, watched and rebuilt live on a fix (ADR 0018).
    */
  private[runtime] def stateOf(
      result: Either[String, Dashboard.Validated]
  ): RendererState = result match {
    case Right(validated) =>
      RendererState.Ready(Renderer.fromValidated(validated))
    case Left(message) => RendererState.Failed(message)
  }

  private[runtime] object LiveSite {
    def of(
        renderers: Map[String, SignallingRef[IO, RendererState]],
        content: Map[String, Either[String, Dashboard]],
        defaultSlug: String
    ): IO[LiveSite] =
      for {
        // Pair each seeded renderer with its own fragment log here, so the
        // caller (ServerApp, tests) never has to know the log exists.
        seeded <- renderers.toList
          .traverse { case (slug, r) =>
            LiveSlug
              .create(r)
              .map(live =>
                slug -> Entry(
                  live,
                  // A boot-seeded slug came from the entrypoint; anything the
                  // seed has no content for is treated as pushed, so a reload
                  // installs over it rather than reclaiming it blind.
                  content.get(slug).fold(Origin.Pushed)(Origin.FromSite(_))
                )
              )
          }
          .map(_.toMap)
        entries <- SignallingRef[IO].of(seeded)
        preferred <- Ref[IO].of(Option(defaultSlug).filter(_.nonEmpty))
      } yield new LiveSite(entries, preferred, defaultSlug)
  }

  /** Which of `slugs` should be served at `/`: the authored preference when it
    * still names a registered dashboard, else the one named `dashboard`, else
    * the first. `None` only when nothing is registered.
    *
    * The preference is honoured even when that dashboard FAILED to build — its
    * error page is the point (it stays fixable in the editor rather than
    * silently bouncing to a different dashboard), which is why this reads
    * membership and never build status.
    */
  private[runtime] def defaultSlugFor(
      preferred: Option[String],
      slugs: List[String]
  ): Option[String] =
    preferred
      .filter(slugs.contains)
      .orElse(Option.when(slugs.contains(DefaultSlug))(DefaultSlug))
      .orElse(slugs.sorted.headOption)

  /** The slug a site with no stated preference serves at `/` when it has one by
    * that name — and the name a boot with nothing to serve registers its
    * failure under.
    */
  val DefaultSlug: String = "dashboard"

  /** Build the server and run the per-slug recorders
    * ([[Server.sharedPatchPublishers]]) for the life of the resource. The
    * single construction point (ServerApp and tests), so the changelog is never
    * accidentally left un-written — with nothing recording, every session's
    * pull would find an empty log and the dashboard would simply stop moving.
    *
    * `site` is not the final word on what is served — it is written after
    * construction by every reload and by [[Server.push]], and the supervisor
    * here owns the publishers those slugs start, so they end with the resource
    * like the ones present at startup.
    */
  def resource(
      api: HomeAssistantApi[IO],
      stateStore: StateStore,
      renderers: Map[String, SignallingRef[IO, RendererState]],
      defaultSlug: String,
      sessions: Sessions,
      assets: AssetCache = AssetCache.empty,
      healthy: Signal[IO, Boolean] = Signal.constant(true),
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None,
      adoptionWindow: FiniteDuration = AdoptionWindow,
      lingerWindow: FiniteDuration = LingerWindow
  ): Resource[IO, Server] =
    LiveSite
      // Nothing here is reloaded (this form exists for a fixed set of
      // renderers), so the seed carries no evaluated content.
      .of(renderers, Map.empty, defaultSlug)
      .toResource
      .flatMap(
        withSite(
          api,
          stateStore,
          _,
          sessions,
          assets,
          healthy,
          systemPkl,
          dumpRefresh,
          adoptionWindow,
          lingerWindow
        )
      )

  /** [[resource]] against a site the CALLER owns — what production uses, since
    * the reload path writes the same registry the routes read.
    */
  def withSite(
      api: HomeAssistantApi[IO],
      stateStore: StateStore,
      site: LiveSite,
      sessions: Sessions,
      assets: AssetCache,
      healthy: Signal[IO, Boolean],
      systemPkl: SystemPkl,
      dumpRefresh: Option[IO[DumpRefresh.Result]],
      adoptionWindow: FiniteDuration = AdoptionWindow,
      lingerWindow: FiniteDuration = LingerWindow
  ): Resource[IO, Server] =
    for {
      supervisor <- Supervisor[IO]
      server = new Server(
        api,
        stateStore,
        site,
        sessions,
        supervisor,
        assets,
        healthy,
        systemPkl,
        dumpRefresh,
        adoptionWindow,
        lingerWindow
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
      site: LiveSite,
      sessions: Sessions,
      assets: AssetCache = AssetCache.empty,
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None
  ): Resource[IO, Server] =
    withSite(
      feed.api,
      feed.store,
      site,
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
    * wherever both name the same fact ([[uiStateOf]]).
    */
  private[runtime] case class Restore(
      uiState: Map[String, String],
      // The session this document just established, minted HERE because the
      // document is the first thing that puts fragments in this client's DOM
      // and the only place that knows what they were.
      conn: String,
      // What this document already SHOWS: the store version it was rendered at,
      // and the log it belongs to. Without it the first connect has no cursor
      // and takes the no-cursor branch, which inner-patches a body the document
      // already contains — the whole page, sent twice, on every load.
      cursor: Option[Cursor] = None
  ) {

    /** `?ui.<id>=<v>&<cursor>&conn=<id>`. Never empty — every document names
      * the session it established. The open popup rides as `ui.<PopupHostId>`
      * like any other selection. `&amp;` because this lands in an HTML
      * attribute.
      */
    def query: String = {
      val params = uiState.toList.sorted.map { case (id, v) =>
        s"$UiParamPrefix${encode(id)}=${encode(v)}"
      } ++ cursor.toList.flatMap(c =>
        List(
          s"${cursorParam(HeadHashSignal)}=${encode(c.headHash)}",
          s"${cursorParam(StyleHashSignal)}=${encode(c.styleHash)}",
          s"${cursorParam(LogIdSignal)}=${encode(c.logId)}",
          s"${cursorParam(StoreVersionSignal)}=${c.version}"
        )
      ) :+ s"$ConnSignal=${encode(conn)}"
      params.mkString("?", "&amp;", "")
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

  /** The Datastar signal name carrying the per-connection `conn` id, echoed
    * back in each action POST body (`connOf`) so a POST correlates to its
    * stream.
    *
    * SEEDED BY THE DOCUMENT, which is what mints it — in the page's signals and
    * on the `data-init` URL, one value in two carriers. The stream announces it
    * only when it had to mint one itself (a bookmarked SSE endpoint), because
    * telling an ordinary load its own id is telling it what it already said,
    * once per connect and every reconnect is a connect.
    */
  val ConnSignal: String = "conn"

  /** The namespace the resume cursor lives under, and the reason it is
    * `_`-prefixed: Datastar's default request filter is `exclude: /(^|\.)_/`,
    * so nesting the four cursor fields here keeps them out of every request BUT
    * the one that reads them.
    *
    * They used to be four top-level signals, which meant every action POST
    * carried them for a server that never looks. The SSE GET puts them back
    * with an explicit `filterSignals` ([[SseOptions]]) — an include, because
    * include and exclude are ANDed and the default exclude would otherwise
    * still drop them.
    *
    * Nested rather than four `_cursor_x` names because Datastar MERGES nested
    * objects rather than replacing them (`Nt` in the pinned bundle keeps an
    * existing object and recurses per key), which is what lets a live batch
    * patch `{_cursor:{storeVersion}}` without wiping the three fields it does
    * not mention.
    */
  val CursorSignal: String = "_cursor"

  /** The Datastar signal name carrying upstream-HA liveness, PUSHED by the
    * server (it owns `healthy`). `true` means the backend can't reach Home
    * Assistant; the HA disconnect banner renders `data-show` off it (see
    * [[Server.page]]). Concept 1 of the two disconnect concepts — the
    * browser<->server transport (concept 2) is derived client-side instead.
    *
    * `_`-prefixed because the server never reads it from a request body — it
    * WRITES it, and the page reads it in a `data-show`. Datastar's default
    * request filter excludes `/(^|\.)_/`, so without the prefix it rode every
    * surface-action POST for nobody.
    */
  val HaDownSignal: String = "_haDown"

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

  val PrevConnParam: String = "prev"

  /** The recover stream's connection marker ([[recoverStream]]): an SSE COMMENT
    * line, not an event — the browser's EventSource discards comments before
    * any listener, so Datastar never even receives it (it costs a `: …` line,
    * not a `data:` event). Its only job is to be the stream's first element,
    * the proof a test can await that the stream subscribed under the current
    * state.
    */
  private[runtime] val recoverOpenMarker: ServerSentEvent =
    ServerSentEvent(comment = Some("recover-open"))

  /** The page shell's own JavaScript, read from the frontend bundle
    * (`src/js/shell.ts` -> vite -> managed resources).
    *
    * Inlined into every document's `<head>` rather than linked, and a CLASSIC
    * script rather than a module: `fhConn` is called from a `<script>` in the
    * middle of the body and `fhUrl` from Datastar's first `data-effect`, so a
    * deferred module would define these names too late. Inlining also keeps it
    * to one round trip, which matters on a page whose whole point is painting
    * before the stream connects.
    *
    * Defines `fhUrl` (the URL mirror, ADR 0005), `fhConn` (the session handoff,
    * see [[PrevConnParam]]) and `fhScroll` (the scroll offset, ADR 0002). The
    * `prev` parameter name is protocol shared with that constant, and the
    * sessionStorage keys are the TypeScript's own — see the module doc there.
    *
    * A HARD failure when the resource is missing, not a fallback to nothing: a
    * page without these helpers looks fine and then silently loses the tab
    * selection, the session handoff and the scroll position. Missing means the
    * frontend bundle did not run, which is a broken build, not a mode to
    * support.
    */
  val UrlSyncScript: String = FrontendAssets.content("shell")

  /** Install the service worker on every load — see the `fhRegisterSw` helper
    * in the shell. Inlined alongside [[UrlSyncScript]] for the same reason: it
    * must run before Datastar's deferred module (so this document can start
    * cache-firsting its `web/` and `assets/` immediately), and a classic script
    * makes that true.
    *
    * The URL rides the frontend manifest via [[PwaAssets.swUrl]] — nothing here
    * spells `sw.js` out. The call itself is a no-op unless the context is
    * secure and SWs are supported; the manifest `<link>`, not the SW, is what
    * drives installability.
    */
  val swRegisterCall: String =
    s"fhRegisterSw('${escapeJsString(PwaAssets.swUrl)}')"

  /** The last line of the document: restore this slug's scroll offset — and, if
    * the shell never ran, SAY SO.
    *
    * The guard is a second, separate `<script>` from the inlined shell, and
    * that is what makes it work: a parse error in one script tag does not stop
    * the browser running the next, so this one is reached precisely when the
    * shell is broken. Without it the symptom is a page that looks perfect and
    * has quietly lost the tab selection, the session handoff and the scroll
    * position, with only a `fhScroll is not defined` in the console to say why.
    *
    * The BUILD is the real guard ([[FrontendAssets]] and the
    * `fh-assert-self-contained` vite plugin, which fails on the split that
    * causes this); this is the one that survives everything the build cannot
    * see — a hand-edited bundle, a proxy mangling the response, an old browser
    * refusing the syntax.
    */
  private[runtime] def scrollCall(slug: String): String = {
    val id = escapeJsString(slug)
    s"if(window.fhScroll)fhScroll('$id');" +
      "else console.error('fh: the page shell did not run \\u2014 tab selection, " +
      "session handoff and scroll restore are all disabled on this page')"
  }

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
    * A reload would also work, but it throws away every bit of client-side
    * state on the page — an open popup, a slider mid-drag, scroll position — to
    * re-send a stylesheet.
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

  /** The cursor as ONE required shape rather than four independent lookups.
    *
    * The difference is what a PARTIAL payload does. Four `toOption` reads make
    * a missing field indistinguishable from a missing store, so a signal
    * payload carrying three of the four quietly falls through to the document's
    * query params — whose version is frozen at page render, so every reconnect
    * resumes from the original version and re-derives the whole page. `holds`
    * suppresses most of it, which is precisely why nobody would notice. A
    * decoder makes that case a `Left`, which [[cursorAnomaly]] can report.
    */
  private val cursorDecoder: Decoder[Cursor] =
    Decoder.forProduct4[Cursor, String, String, String, Long](
      HeadHashSignal,
      StyleHashSignal,
      LogIdSignal,
      StoreVersionSignal
    )(Cursor.apply)

  /** A request that carries a live signal store but no readable cursor in it.
    *
    * Not the same as a first connect, which carries no store at all
    * ([[hasSignals]]) and legitimately uses the query params. This one HAS a
    * store and the cursor is not in it — a client-side signal filter that
    * excluded it, or a page from a previous release — and the only symptom is a
    * resume that is quietly larger than it should be, forever.
    */
  private[runtime] def cursorAnomaly(req: Request[IO]): Option[String] =
    signalsOf(req)
      // Emptiness is the whole discriminator, and "has a store" is not:
      // Datastar sets the param on every GET whatever the store holds, so a
      // first connect arrives as `{}` — it fires `data-init` from <body> before
      // the descendants' `data-signals` are merged, which is exactly why
      // `Restore` puts the cursor on the URL. Treating `{}` as "a store with
      // the cursor missing" makes every ordinary page load an anomaly.
      .filter(_.keys.exists(_.nonEmpty))
      .flatMap(_.downField(CursorSignal).as(using cursorDecoder).left.toOption)
      .map(f =>
        "reconnect carried a signal store with no readable cursor " +
          s"(${f.getMessage}) — resuming from the document's frozen params " +
          "instead. Check the client's filterSignals: the four cursor signals " +
          "must reach the SSE GET."
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
      .flatMap(_.downField(CursorSignal).as(using cursorDecoder).toOption)
      .orElse(cursorFromQuery(req))

  /** The cursor a freshly-loaded DOCUMENT hands back on its first connect
    * ([[Restore]]), read from plain query params.
    *
    * Signals win where both exist, the same precedence [[uiStateOf]] and
    * [[uiStateOf]] uses and for the same reason: a reconnect re-serialises the
    * live signal store, and a stale param baked into the `data-init` URL at
    * page render must never override it. Without that rule a client would
    * resume from its ORIGINAL page version forever, and silently miss
    * everything since.
    */
  private def cursorFromQuery(req: Request[IO]): Option[Cursor] = {
    val p = req.uri.query.params
    for {
      hash <- p.get(cursorParam(HeadHashSignal))
      styleHash <- p.get(cursorParam(StyleHashSignal))
      logId <- p.get(cursorParam(LogIdSignal))
      version <- p.get(cursorParam(StoreVersionSignal)).flatMap(_.toLongOption)
    } yield Cursor(hash, styleHash, logId, version)
  }

  /** The `data-init` URL's name for one cursor field — the same dotted path the
    * signal store uses, so the two carriers of one fact cannot drift apart.
    */
  private[runtime] def cursorParam(field: String): String =
    s"$CursorSignal.$field"

  /** Whether this request carries a live signal store with anything IN it —
    * i.e. it is a RECONNECT rather than a freshly-loaded document's first
    * connect.
    *
    * The emptiness test is the point: Datastar sets the `datastar` param on
    * every GET regardless, so a first connect arrives carrying `{}`, and the
    * presence of the param alone says nothing.
    */
  private[runtime] def hasSignals(req: Request[IO]): Boolean =
    signalsOf(req).exists(_.keys.exists(_.nonEmpty))

  /** Which session this request belongs to. Signals first, then the plain query
    * param, for the reason [[cursorOf]] gives: a reconnect re-serialises the
    * live store, and the param baked into the `data-init` URL at page render is
    * the FIRST connect's carrier only.
    *
    * A reconnect naming a session that is gone is not an error — a fresh one is
    * minted under the same id, and the client keeps the `conn` it already has.
    */
  private[runtime] def connOf(req: Request[IO]): Option[String] =
    signalsOf(req)
      .flatMap(_.get[String](ConnSignal).toOption)
      .orElse(req.uri.query.params.get(ConnSignal))
      .filter(_.nonEmpty)

  private def signalsOf(req: Request[IO]): Option[io.circe.ACursor] =
    req.uri.query.params
      .get("datastar")
      .flatMap(io.circe.parser.parse(_).toOption)
      .map(_.hcursor)

  /** The WHOLE cursor: three facts identifying which renderer and which log a
    * client's DOM belongs to, plus where it has got to.
    *
    * Sent only where the first three can actually change — on connect, and on a
    * renderer swap. Every live batch sends [[versionSignal]] alone.
    */
  private[runtime] def cursorSignals(
      renderer: Renderer,
      logId: String,
      version: Long
  ): ServerSentEvent =
    Datastar.patchSignals(
      s"""{"$CursorSignal":{"$HeadHashSignal":"${renderer.headHash}",""" +
        s""""$StyleHashSignal":"${renderer.styleHash}",""" +
        s""""$LogIdSignal":"$logId",""" +
        s""""$StoreVersionSignal":$version}}"""
    )

  /** Just how far this client has got — the only part of the cursor a live
    * batch moves.
    */
  private[runtime] def versionSignal(version: Long): ServerSentEvent =
    versionPatch(version).toSse

  /** [[versionSignal]] as a PATCH rather than a wire event, so a batch that
    * ends with it can merge it into its own signal frame — see
    * [[Patches.encode]]. A value tick with no element patches is then one
    * `datastar-patch-signals` on the wire instead of two.
    *
    * Nested, and merged rather than replaced by the client, so naming only the
    * version leaves the other three cursor fields standing.
    */
  private[runtime] def versionPatch(version: Long): Patch =
    Patch.Signals(
      Map(
        SignalId.derived(CursorSignal) ->
          Json.obj(StoreVersionSignal -> Json.fromLong(version))
      )
    )

  /** The session this tab used BEFORE the document that opened this stream —
    * written by [[UrlSyncScript]] from `sessionStorage`, which is per-tab and
    * survives a reload, where a cookie would be per-browser and make two tabs
    * fight over one session.
    *
    * It is a retirement notice, never an identity to adopt: the id is not
    * reused, so two tabs can never end up on one session and the displacement
    * rule keeps its narrow job (a reconnect racing its predecessor's teardown).
    */
  private[runtime] def prevConnOf(req: Request[IO]): Option[String] =
    req.uri.query.params.get(PrevConnParam).filter(_.nonEmpty)

  /** What the SSE GET carries back, as a REGEX (Datastar compiles a string
    * pattern with `RegExp`, it is not a glob).
    *
    * An include is needed at all because the cursor is `_`-prefixed and the
    * default exclude would drop it; and once an include is given, the default
    * exclude has to be neutralised (`(?!)` never matches) because the two are
    * ANDed. So this list is the WHOLE of what a reconnect tells the server, and
    * anything not named here is invisible to it — `_val_*` slider state and
    * `_sse`/`_reload` deliberately, but also any future signal somebody adds
    * expecting the server to see it.
    *
    * '''Declared before [[SseRetry]], which reads it.''' A `val` that names a
    * `val` defined later in the same object reads `null` — the fields are
    * initialised in source order and nothing warns. That shipped: every page
    * carried `include:'null'`, a regex matching no signal name, so no reconnect
    * carried a cursor, a `conn` or a tab selection, and a tab returning from
    * the background resumed from the version frozen into its `data-init` at
    * page load.
    *
    * Getting it wrong does not fail loudly on its own, which is what
    * [[cursorAnomaly]] is for — except in this exact case: an include matching
    * nothing produces an EMPTY signal store, which is what a first connect
    * sends too, so the warning cannot fire. `ServerRoutesSuite` asserts the
    * served page instead.
    */
  private[runtime] val SseInclude: String =
    s"^($ConnSignal$$|${UiSignalPrefix}|$CursorSignal\\.)"

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
  val SseRetry: String =
    s"{retry:'always',filterSignals:{include:'$SseInclude',exclude:'(?!)'}}"

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
    * The CURSOR is the one exception, and it earns it. A pull that owes this
    * client nothing sends nothing, so the cursor would otherwise sit still
    * while the server moved on; the heartbeat carries it instead, bounding the
    * lag at one interval. It is emitted only when the position actually moved
    * since this stream last said so, so a quiet night is still comments.
    *
    * Sent to every connection, including direct LAN ones that need no keepalive
    * at all — skipping those is possible but deliberately not done, see
    * TODO2.md.
    */
  val KeepAliveInterval: FiniteDuration = 25.seconds

  /** How long a document's session waits for the stream that should adopt it
    * ([[Session.adopt]]). Sized by the gap between a page rendering and
    * `data-init` firing — a parse, a module load, one round trip — so a few
    * seconds, not minutes.
    *
    * Deliberately SHORT, and shorter than [[LingerWindow]], because the two
    * zero-stream states are not the same fact. A `Fresh` session was never
    * adopted: either its stream is about to arrive or the load was abandoned,
    * and abandoned is what a burst of reloads produces. A `Lingering` one had a
    * stream and lost it, which is a phone waking or a lid closing and deserves
    * patience. Collapsing them into one idle timer would make the common
    * accident wait for the rare one.
    *
    * Too short costs only the client its `holds` seed — bytes on its first
    * patch, never staleness. Too long is a session per abandoned load, read by
    * every state batch and holding the changelog floor down until it expires.
    * So this errs short.
    */
  val AdoptionWindow: FiniteDuration = 10.seconds

  /** How long a session outlives the stream that was holding it
    * ([[Tenure.Lingering]]).
    *
    * It is not really "how long we keep a session" — it is '''how long a
    * returning client can be told only what moved.''' A drop costs the client
    * nothing but bytes: without a session its reconnect still resumes off the
    * changelog, and only without THAT does it repaint. So this is sized by how
    * long a dashboard is realistically away and still worth the exactness — a
    * phone waking, a laptop lid, a wifi handover — not by how long the client
    * might live.
    *
    * The cost of too long is one map per absent client, read by every state
    * batch and keeping its slug recording. The cost of too short is a fatter
    * first patch. Neither is a correctness edge, which is why this is a plain
    * constant and not a policy.
    */
  val LingerWindow: FiniteDuration = 2.minutes

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

  /** For a single-quoted JS string literal (a seeded signal value inside a
    * Datastar expression). Backslash FIRST, or the escapes added here would
    * themselves be escaped.
    */
  private[runtime] def escapeJsString(s: String): String =
    s.replace("\\", "\\\\").replace("'", "\\'")

  /** Ampersand FIRST, or the entity replacements are double-escaped. */
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
