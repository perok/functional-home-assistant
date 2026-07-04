package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.Dashboard
import fs2.Stream
import fs2.concurrent.SignallingRef
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
  */
class Server(
    api: HomeAssistantApi[IO],
    stateStore: StateStore,
    // One hot-swappable renderer per dashboard slug (live reload swaps in place;
    // `.discrete` drives a body repaint over SSE).
    renderers: Map[String, SignallingRef[IO, Renderer]],
    defaultSlug: String,
    sessions: Sessions
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

  /** The per-connection SSE stream: a `conn` signal, then entity-change patches
    * (main page + open surfaces), the session control channel (popup/navigate
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

      // Each state change is re-rendered as it arrives. `changedPatches` already
      // narrows the work (reverse index for static components, query-affected
      // filter for dynamic groups) and the diff cache drops no-op pushes.
      // FUTURE (ADR): under a burst of state_changed events (HA fires them
      // constantly), coalesce — debounce/batch the stream per connection and
      // re-render at most every X ms, collapsing repeated touches of the same
      // node into one render+push. The narrowing here bounds *what* re-renders;
      // batching would bound *how often*. (Fold this into the dynamic-groups ADR
      // when the perf model is settled.)
      patches = stateStore.changes
        .evalMap(changedPatches(session, _, uiState))
        .flatMap(Stream.emits)
      control = Stream.fromQueueUnterminated(session.control)
      // Live-reload repaint follows the session's CURRENT dashboard: watch every
      // renderer, but only repaint when the one that reloaded is the one this
      // connection is viewing now (it may have navigated since connecting).
      reloads = renderers.toList
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
      heartbeat = Stream
        .awakeEvery[IO](15.seconds)
        .as(ServerSentEvent(data = None, comment = Some("keep-alive")))

      stream = (Stream.emit(Datastar.patchSignals(s"""{"conn":"$conn"}""")) ++
        patches.merge(control).merge(reloads).merge(heartbeat))
        .onFinalize(sessions.deregister(conn))
      resp <- Ok(stream)
    } yield resp

  /** Re-render the nodes a changed entity drives — main-page
    * components/dynamics plus, for each open surface, that surface's
    * components/dynamics — and emit only the fragments whose HTML actually
    * changed (per-connection diff).
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
          // Reverse-indexed components that bind this entity, plus only the
          // dynamic groups this change can move the entity in/out of (not every
          // group on every event).
          val mainIds =
            r.componentsFor(change.entityId).toList ++
              r.affectedDynamicIds(change)
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
    body.hcursor.get[String]("conn").toOption

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
                renderer.stylesheets
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
      stylesheets: List[String]
  ): String = {
    val links = stylesheets
      .map(href => s"""  <link rel="stylesheet" href="$href">""")
      .mkString("\n")
    // On Back/Forward, derive the slug from the URL and re-post the swap (no
    // pushState — the browser already moved). `/d/<slug>` or `/` -> default.
    val popstate =
      s"@post('/sse/navigate/' + (window.location.pathname.split('/d/')[1] || '$defaultSlug'))"
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <title>Home Assistant</title>
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

  /** The client's UI state read off request cookies: every `fhui_<id>` cookie
    * mapped to `id -> rawValue` (the `fhui_` prefix dropped). The value is left
    * opaque here — interpretation and the untrusted-value clamp live in
    * [[Renderer.resolveActive]], so a stale/hand-edited cookie can never bake a
    * non-existent surface. Empty when no `fhui_` cookies are present.
    */
  def uiStateOf(req: Request[IO]): Map[String, String] =
    req.cookies.collect {
      case c if c.name.startsWith("fhui_") => c.name.drop(5) -> c.content
    }.toMap

  /** Datastar client bundle. Pinned — verify against current Datastar docs when
    * upgrading (SSE event names / `data-*` attribute syntax change across
    * releases).
    */
  val DatastarCdn: String =
    "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"

  /** Parse a URL-path action value into the most specific JSON type (int, then
    * double, else string) so HA receives `brightness: 128` rather than `"128"`.
    */
  def parseValue(raw: String): Json =
    raw.toIntOption
      .map(Json.fromInt)
      .orElse(raw.toDoubleOption.flatMap(Json.fromDouble))
      .getOrElse(Json.fromString(raw))
}
