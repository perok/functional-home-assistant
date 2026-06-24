package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
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
  *   - `POST /sse/surface/open|close/:id` open/close a popup surface (per
  *     connection); `POST /sse/navigate/:slug` swap the viewed dashboard in
  *     place. The state lives in the connection's [[Session]]; the resulting
  *     patches ride the same SSE stream.
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
    case GET -> Root              => pageResponse(defaultSlug)
    case GET -> Root / "d" / slug => pageResponse(slug)

    case GET -> Root / "sse" / "dashboard" / slug / "patch" =>
      if (renderers.contains(slug)) sseStream(slug) else NotFound()

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
      withSession(req)((session, renderer) =>
        openSurface(session, renderer, id)
      )

    case req @ POST -> Root / "sse" / "surface" / "close" / id =>
      withSession(req)((session, _) =>
        session.open.update(_ - id) *>
          session.control.offer(
            Datastar.removeElement("#" + Renderer.surfaceRootId(id))
          )
      )

    case req @ POST -> Root / "sse" / "navigate" / slug =>
      withSession(req)((session, _) => navigate(session, slug))
  }

  /** The per-connection SSE stream: a `conn` signal, then entity-change patches
    * (main page + open surfaces), the session control channel (popup/navigate
    * patches), live-reload body repaints, and a heartbeat.
    */
  private def sseStream(slug: String): IO[Response[IO]] =
    for {
      conn <- IO.randomUUID.map(_.toString)
      session <- Session.create(slug)
      _ <- sessions.register(conn, session)
      // Seed the open set with this dashboard's default tab panels, so the
      // baked-inline default tabs receive live updates from the first paint.
      _ <- renderers
        .get(slug)
        .traverse_(_.get.flatMap(r => session.open.set(r.defaultOpenSurfaces)))

      patches = stateStore.changes
        .evalMap(changedPatches(session, _))
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
                // The repaint re-bakes the body (default tabs included), so
                // reset the diff cache AND re-seed the open set to match.
                (session.lastRendered.set(Map.empty) *>
                  session.open.set(r.defaultOpenSurfaces) *>
                  stateStore.snapshot)
                  .map(st =>
                    Some(
                      Datastar
                        .patch(
                          r.renderBody(st),
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
      entityId: String
  ): IO[List[ServerSentEvent]] =
    for {
      slug <- session.slug.get
      renderer <- renderers.get(slug).traverse(_.get)
      states <- stateStore.snapshot
      open <- session.open.get
      out <- renderer match {
        case None => IO.pure(List.empty[ServerSentEvent])
        case Some(r) =>
          val mainIds =
            r.componentsFor(entityId).toList ++ r.dynamicContainerIds
          val surfaceIds = open.toList.flatMap(sid =>
            r.surfaceComponentsFor(sid, entityId).toList ++ r.surfaceDynamicIds(
              sid
            )
          )
          val ids = (mainIds ++ surfaceIds).distinct
          val rendered =
            ids.flatMap(id => r.renderNodeById(id, states).map(id -> _))
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

  /** Open a surface for this connection: evict same-group siblings, mark it
    * open, and append its rendered HTML into its mount (`#popups` by default).
    */
  private def openSurface(
      session: Session,
      renderer: Renderer,
      id: String
  ): IO[Unit] =
    renderer.surface(id) match {
      case None => IO.unit
      case Some(surf) =>
        for {
          // Exclusivity group: close any open sibling sharing the group first.
          _ <- surf.group.traverse_ { g =>
            session.open.get.flatMap { open =>
              open.toList
                .filter(sid =>
                  sid != id && renderer
                    .surface(sid)
                    .flatMap(_.group)
                    .contains(g)
                )
                .traverse_(sid =>
                  session.open.update(_ - sid) *>
                    session.control.offer(
                      Datastar.removeElement("#" + Renderer.surfaceRootId(sid))
                    )
                )
            }
          }
          _ <- session.open.update(_ + id)
          states <- stateStore.snapshot
          // An inline mount (tab panel) is REPLACED in place (`inner`), so only
          // one tab shows at a time; the default overlay mount STACKS popups
          // (`append` into `#popups`).
          mount = "#" + surf.mount.getOrElse("popups")
          mode = if (surf.mount.isDefined) PatchMode.Inner else PatchMode.Append
          _ <- renderer
            .renderSurface(id, states)
            .traverse_(html =>
              session.control.offer(Datastar.patch(html, mode, Some(mount)))
            )
        } yield ()
    }

  /** In-place navigate: re-point the session at `slug`, reset its popups + diff
    * cache, clear the popup mount, and inner-patch the body. The URL is updated
    * client-side in the trigger expression, so this is identical for a forward
    * navigate and a Back/Forward `popstate` re-sync.
    */
  private def navigate(session: Session, slug: String): IO[Unit] =
    renderers.get(slug) match {
      case None => IO.unit
      case Some(ref) =>
        for {
          renderer <- ref.get
          states <- stateStore.snapshot
          _ <- session.slug.set(slug)
          // Reset popups, but seed the target dashboard's default tab panels
          // (its body is rendered with them baked in below).
          _ <- session.open.set(renderer.defaultOpenSurfaces)
          _ <- session.lastRendered.set(Map.empty)
          _ <- session.control.offer(
            Datastar.patch("""<div id="popups"></div>""", PatchMode.Outer, None)
          )
          _ <- session.control.offer(
            Datastar.patch(
              renderer.renderBody(states),
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
  )(f: (Session, Renderer) => IO[Unit]): IO[Response[IO]] =
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
                .flatMap(_.traverse_(f(session, _))) *> NoContent()
          }
      }

  private def connOf(body: Json): Option[String] =
    body.hcursor.get[String]("conn").toOption

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
        BadRequest(s"""{"success":false,"error":"${err.getMessage}"}""")
    }

  private def pageResponse(slug: String): IO[Response[IO]] =
    renderers.get(slug) match {
      case None => NotFound()
      case Some(ref) =>
        (ref.get, stateStore.snapshot).flatMapN { (renderer, states) =>
          Ok(page(slug, renderer.renderPage(states), renderer.stylesheets))
            .map(_.withContentType(`Content-Type`(MediaType.text.html)))
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
