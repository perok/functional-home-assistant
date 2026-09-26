package api.homeassistant.ws.testkit

import cats.effect.std.Queue
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.client.websocket.{WSClient, WSConnection, WSFrame}

/** An HA WebSocket stub at the frame level, which the real
  * [[api.homeassistant.ws.HAWSApiLowLevel]] connects through.
  * `FakeHomeAssistant` stubs a level higher, bypassing the transport. It
  * answers the auth phase, auto-acks the runtime's commands and pongs; tests
  * drive the rest through [[emit]] / [[emitFrame]], including framings real HA
  * is hard to provoke (an ack sharing a frame with its first event, a malformed
  * frame).
  */
final class FakeHaSocket private (
    token: String,
    // `None` ends the stream: the socket closed.
    outgoing: Queue[IO, Option[WSFrame]],
    sentRef: Ref[IO, Vector[Json]],
    // As HA does, once `supported_features` enables it every frame is a JSON
    // array, even of one.
    coalescing: Ref[IO, Boolean],
    answerPings: Ref[IO, Boolean],
    rejected: Ref[IO, Set[String]],
    held: Ref[IO, Set[String]]
) {

  val client: WSClient[IO] =
    WSClient[IO](respondToPings = true)(_ => connection)

  private def connection: Resource[IO, WSConnection[IO]] =
    Resource.pure(new WSConnection[IO] {
      def send(wsf: WSFrame): IO[Unit] = wsf match {
        case WSFrame.Text(data, _) => receiveFromClient(data)
        case _                     => IO.unit
      }
      def sendMany[G[_]: cats.Foldable, A <: WSFrame](wsfs: G[A]): IO[Unit] =
        wsfs.traverse_(send)
      def receive: IO[Option[WSFrame]] = outgoing.take
      def subprotocol: Option[String] = None
    })

  def sentCommands: IO[List[Json]] = sentRef.get.map(_.toList)

  private def commandType(json: Json): String =
    json.hcursor.get[String]("type").getOrElse("")

  private def receiveFromClient(data: String): IO[Unit] =
    parse(data).liftTo[IO].flatMap { json =>
      commandType(json) match {
        case "auth" =>
          val ok = json.hcursor.get[String]("access_token").contains(token)
          emitFrame(
            Json
              .obj(
                "type" -> Json.fromString(if (ok) "auth_ok" else "auth_invalid")
              )
              .noSpaces
          )
        case other =>
          sentRef.update(_ :+ json) *> autoRespond(json, other)
      }
    }

  /** A test drives anything else itself. */
  private def autoRespond(json: Json, tpe: String): IO[Unit] = {
    val id = json.hcursor.get[Int]("id").getOrElse(0)
    (rejected.get, held.get).flatMapN { (rejects, holds) =>
      if (holds(tpe)) IO.unit
      else if (rejects(tpe)) emit(error(id, s"$tpe not allowed"))
      else
        tpe match {
          case "ping" =>
            answerPings.get.ifM(
              emit(Json.obj("id" -> id.asJson, "type" -> "pong".asJson)),
              IO.unit
            )
          case "supported_features" =>
            // Ack before switching framing, so one connection exercises both
            // shapes.
            emit(ack(id)) *> coalescing.set(true)
          case _ => emit(ack(id))
        }
    }
  }

  /** Once coalescing is on, all in one array frame, which is how a burst stays
    * one fs2 chunk.
    */
  def emit(payloads: Json*): IO[Unit] =
    coalescing.get.flatMap {
      case true  => emitFrame(Json.arr(payloads*).noSpaces)
      case false => payloads.toList.traverse_(p => emitFrame(p.noSpaces))
    }

  /** Bypassing every shape check, for malformed frames and for pinning a
    * framing.
    */
  def emitFrame(text: String): IO[Unit] =
    outgoing.offer(Some(WSFrame.Text(text)))

  def close: IO[Unit] = outgoing.offer(None)

  def stopAnsweringPings: IO[Unit] = answerPings.set(false)

  /** As HA does for an unknown subscription. */
  def reject(commandType: String): IO[Unit] = rejected.update(_ + commandType)

  /** Recorded but unanswered, so the test owns the response: the only way to
    * control what shares a frame.
    */
  def hold(commandType: String): IO[Unit] = held.update(_ + commandType)

  def ack(id: Int): Json = Json.obj(
    "id" -> id.asJson,
    "type" -> "result".asJson,
    "success" -> true.asJson,
    "result" -> Json.Null
  )

  def error(id: Int, message: String): Json = Json.obj(
    "id" -> id.asJson,
    "type" -> "result".asJson,
    "success" -> false.asJson,
    "error" -> Json.obj(
      "code" -> "invalid_format".asJson,
      "message" -> message.asJson
    )
  )

  def event(id: Int, event: Json): Json = Json.obj(
    "id" -> id.asJson,
    "type" -> "event".asJson,
    "event" -> event
  )

  /** 1-based, so a test can address a subscription whose id it did not choose.
    */
  def idOf(n: Int): IO[Int] =
    sentRef.get.map(_(n - 1).hcursor.get[Int]("id").getOrElse(0))
}

object FakeHaSocket {

  val Token = "test-token"

  /** Already offering `auth_required`, as HA does on connect. */
  def create(token: String = Token): IO[FakeHaSocket] =
    for {
      outgoing <- Queue.unbounded[IO, Option[WSFrame]]
      _ <- outgoing.offer(
        Some(WSFrame.Text(Json.obj("type" -> "auth_required".asJson).noSpaces))
      )
      sent <- Ref[IO].of(Vector.empty[Json])
      coalescing <- Ref[IO].of(false)
      pings <- Ref[IO].of(true)
      rejected <- Ref[IO].of(Set.empty[String])
      held <- Ref[IO].of(Set.empty[String])
    } yield new FakeHaSocket(
      token,
      outgoing,
      sent,
      coalescing,
      pings,
      rejected,
      held
    )
}
