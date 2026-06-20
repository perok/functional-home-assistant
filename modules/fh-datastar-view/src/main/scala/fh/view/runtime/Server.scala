package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import fs2.Stream
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.ServerSentEvent

import scala.concurrent.duration.*

/** HTTP surface for the dashboard.
  *
  *   - `GET /` full server-rendered page; opens the SSE on load.
  *   - `GET /sse/datastar-patch` live stream of `datastar-patch-elements`
  *     fragments.
  *   - `POST /sse/action/:domain/:service/:id` call a HA service; the resulting
  *     state change flows back over the persistent SSE stream.
  */
class Server(
    api: HomeAssistantApi[IO],
    stateStore: StateStore,
    renderer: Renderer
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root =>
      stateStore.snapshot.flatMap { states =>
        Ok(page(renderer.renderPage(states)))
          .map(_.withContentType(`Content-Type`(MediaType.text.html)))
      }

    case GET -> Root / "sse" / "datastar-patch" =>
      val patches: Stream[IO, ServerSentEvent] =
        stateStore.changes
          .evalMap { entityId =>
            stateStore.snapshot.map { states =>
              renderer
                .componentsFor(entityId)
                .toList
                .flatMap(id => renderer.renderComponent(id, states))
            }
          }
          .flatMap(fragments =>
            Stream.emits(fragments.map(Datastar.patchElements))
          )

      // Comment heartbeat keeps proxies/clients from dropping an idle stream.
      val heartbeat: Stream[IO, ServerSentEvent] =
        Stream
          .awakeEvery[IO](15.seconds)
          .as(ServerSentEvent(data = None, comment = Some("keep-alive")))

      Ok(patches.merge(heartbeat))

    // No-data action (toggle, open/close, lock, play/pause, scene activate...).
    case POST -> Root / "sse" / "action" / domain / service / entityId =>
      callService(domain, service, entityId, Json.obj())

    // Single-value action (brightness, cover position, target temperature...).
    // The value rides in the URL path (Datastar builds it via `'.../key/' + $sig`).
    case POST -> Root / "sse" / "action" / domain / service / entityId / dataKey / dataValue =>
      callService(
        domain,
        service,
        entityId,
        Json.obj(dataKey -> Server.parseValue(dataValue))
      )
  }

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

  /** Full HTML document wrapping the rendered dashboard. */
  private def page(body: String): String =
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <title>Home Assistant</title>
       |  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css">
       |  <script type="module" src="${Server.DatastarCdn}"></script>
       |</head>
       |<body data-init="@get('/sse/datastar-patch')">
       |$body
       |</body>
       |</html>
       |""".stripMargin
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
