package fh.view.runtime

import api.homeassistant.ServiceTarget
import cats.effect.{IO, Ref}
import fh.view.FHError
import io.circe.Json
import io.circe.parser.parse
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Request, Response, Status}

/** A tap over HA's REST API (`POST /api/services/<domain>/<service>`), which
  * attributes the call to the bearer token's user as the WS `call_service`
  * does.
  */
class ServiceCallsRestSuite extends munit.CatsEffectSuite {

  private case class Seen(
      path: String,
      token: Option[String],
      body: Option[Json]
  )

  private def ha(reply: Response[IO]): IO[(Client[IO], Ref[IO, List[Seen]])] =
    IO.ref(List.empty[Seen]).map { seen =>
      val app = HttpApp[IO] { (req: Request[IO]) =>
        req.bodyText.compile.string.flatMap { text =>
          val token = req.headers.get[Authorization].collect {
            case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t
          }
          seen.update(
            _ :+ Seen(req.uri.path.renderString, token, parse(text).toOption)
          ) *> IO.pure(reply)
        }
      }
      (Client.fromHttpApp(app), seen)
    }

  private def turnOn(client: Client[IO]): IO[Unit] =
    ServiceCalls.overRest(client, uri"http://core.test")("user-token")(
      "light",
      "turn_on",
      ServiceTarget.Entity("light.overetasje"),
      Json.obj("brightness" -> Json.fromInt(80))
    )

  private def refusedWith(reply: Response[IO]): IO[String] =
    ha(reply).flatMap((client, _) => turnOn(client).attempt).map {
      case Left(e: FHError) => e.getMessage
      case other            => fail(s"expected an FHError, got $other")
    }

  test("the call is the token's, with the entity among the service data") {
    ha(Response[IO](Status.Ok).withEntity("[]")).flatMap { (client, seen) =>
      turnOn(client) *> seen.get.map { calls =>
        assertEquals(
          calls,
          List(
            Seen(
              "/api/services/light/turn_on",
              Some("user-token"),
              Some(
                Json.obj(
                  "brightness" -> Json.fromInt(80),
                  "entity_id" -> Json.fromString("light.overetasje")
                )
              )
            )
          )
        )
      }
    }
  }

  test("HA's own reason is what the user is told") {
    refusedWith(
      Response[IO](Status.BadRequest)
        .withEntity("""{"message":"Brightness is out of range"}""")
    ).map(assertEquals(_, "Brightness is out of range"))
  }

  test("a bare 400 still says HA refused, not that something broke") {
    refusedWith(Response[IO](Status.BadRequest).withEntity("400: Bad Request"))
      .map(assertEquals(_, "Home Assistant refused the action (HTTP 400)."))
  }

  test("a 401 says the user may not do this") {
    refusedWith(
      Response[IO](Status.Unauthorized).withEntity("401: Unauthorized")
    )
      .map(assertEquals(_, "Home Assistant does not allow you to do that."))
  }
}
