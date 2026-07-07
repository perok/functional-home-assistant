package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fh.view.model.Dashboard
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.ServerSentEvent

import scala.concurrent.duration.*

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
    sharedTopics: Map[String, Topic[IO, ServerSentEvent]]
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root              => pageResponse(defaultSlug, req)
    case req @ GET -> Root / "d" / slug => pageResponse(slug, req)

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
                    .evalMap(sharedChangedHtml(renderer, cache, _))
                    .flatMap(Stream.emits)
                    .map(Datastar.patchElements)
              }
            }
        }
        patches.through(topic.publish)
      }
      .foldLeft(Stream.empty.covaryAll[IO, Nothing])(_.merge(_))

  /** The shared per-slug render/diff for one state change: the affected
    * main-page node ids (reverse index + query-affected dynamic groups), minus
    * the bake-group owners, rendered against the current snapshot and diffed
    * against the slug's shared cache. Returns only the fragments whose HTML
    * actually changed. No `uiState`: by construction these nodes don't read it.
    */
  private def sharedChangedHtml(
      renderer: Renderer,
      cache: Ref[IO, Map[String, String]],
      change: StateChange
  ): IO[List[String]] =
    stateStore.snapshot.flatMap { states =>
      val ids =
        (renderer.componentsFor(change.entityId).toList ++
          renderer.affectedDynamicIds(change))
          .filterNot(renderer.bakeOwnerIds)
      val rendered =
        ids.flatMap(id => renderer.renderNodeById(id, states).map(id -> _))
      cache.modify { c =>
        val changed = rendered.filterNot { case (id, html) =>
          c.get(id).contains(html)
        }
        (c ++ changed, changed.map(_._2))
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
      _ <- renderers
        .get(slug)
        .traverse_(_.get.flatMap { r =>
          warnAnomalies(r, uiState) *>
            session.open.set(r.selectedSurfaces(uiState))
        })

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
  private def changedPatches(
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
          // Reverse-indexed components that bind this entity, narrowed to the
          // bake-group owners (a dynamic group is never a bake owner, so the
          // affected-dynamic ids all belong to the shared pass).
          val mainIds =
            r.componentsFor(change.entityId).toList.filter(r.bakeOwnerIds)
          // Per open surface: its components binding this entity, plus only
          // the dynamic groups this change can move the entity in/out of.
          val surfaceIds = open.toList.flatMap(sid =>
            r.surfaceComponentsFor(sid, change.entityId).toList ++
              r.affectedSurfaceDynamicIds(sid, change)
          )
          val ids = (mainIds ++ surfaceIds).distinct
          val rendered =
            ids.flatMap(id =>
              r.renderNodeById(id, states, uiState).map(id -> _)
            )
          session.lastRendered
            .modify { cache =>
              val changed = rendered.filterNot { case (id, html) =>
                cache.get(id).contains(html)
              }
              (cache ++ changed, changed.map(_._2))
            }
            .map(_.map(Datastar.patchElements))
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

  private def pageResponse(slug: String, req: Request[IO]): IO[Response[IO]] =
    renderers.get(slug) match {
      case None => NotFound()
      case Some(ref) =>
        val uiState = Server.uiStateOf(req)
        (ref.get, stateStore.snapshot).flatMapN { (renderer, states) =>
          warnAnomalies(renderer, uiState) *>
            Ok(
              page(
                slug,
                renderer.renderPage(states, uiState),
                renderer.stylesheets,
                renderer.title
              )
            ).map(_.withContentType(`Content-Type`(MediaType.text.html)))
        }
    }

  /** Full HTML document wrapping the rendered dashboard. The theme owns all
    * presentation (its tokens + inline CSS travel inside the body;
    * `stylesheets` are `<link>`-ed here). `data-init` opens this dashboard's
    * SSE stream; the `popstate` handler re-syncs the in-place view to the URL
    * on Back/Forward.
    */
  private def page(
      slug: String,
      body: String,
      stylesheets: List[String],
      title: Option[String]
  ): String = {
    val links = stylesheets
      .map(href => s"""  <link rel="stylesheet" href="$href">""")
      .mkString("\n")
    // The authored per-dashboard title, or the slug when unset. Escaped for the
    // HTML `<title>` element (an authored title is untrusted text).
    val pageTitle = Server.escapeHtml(title.getOrElse(slug))
    // On Back/Forward, derive the slug from the URL and re-post the swap (no
    // pushState — the browser already moved). `/d/<slug>` or `/` -> default.
    val popstate =
      s"@post('/sse/navigate/' + (window.location.pathname.split('/d/')[1] || '$defaultSlug'))"
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <title>$pageTitle</title>
       |$links
       |  <script type="module" src="${Server.DatastarCdn}"></script>
       |</head>
       |<body data-init="@get('/sse/dashboard/$slug/patch')" data-on:popstate__window="$popstate">
       |$body
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
      sessions: Sessions
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
        topics
      )
      _ <- server.sharedPatchPublishers.compile.drain.background
    } yield server

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
