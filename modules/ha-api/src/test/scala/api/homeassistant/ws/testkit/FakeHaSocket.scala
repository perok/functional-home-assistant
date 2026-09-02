package api.homeassistant.ws.testkit

import cats.effect.std.Queue
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.client.websocket.{WSClient, WSConnection, WSFrame}

/** A Home Assistant WebSocket stub at the FRAME level: a `WSClient[IO]` the
  * REAL [[api.homeassistant.ws.HAWSApiLowLevel]] connects through.
  *
  * `FakeHomeAssistant` (in fh-datastar-view) stubs one level higher — it
  * implements `HAWSApiLowLevel` itself — which is right for dashboard tests but
  * means the transport is never exercised: the auth handshake, the id routing,
  * the ack-then-events ordering, coalesced framing, ping/pong liveness and the
  * decode-failure path are all bypassed. This stub exists so those can be
  * tested against the real implementation.
  *
  * It speaks enough of the protocol to be realistic, not all of it: the auth
  * phase, an auto-ack for the commands the runtime issues, and `pong`. Test
  * bodies drive everything else through [[emit]] / [[emitFrame]], so a test can
  * produce framings real HA is hard to provoke into (an ack sharing a frame
  * with its first event, a burst in one array, a malformed frame).
  */
final class FakeHaSocket private (
    token: String,
    // Frames the client will receive. `None` ends the stream, i.e. the socket
    // closed.
    outgoing: Queue[IO, Option[WSFrame]],
    sentRef: Ref[IO, Vector[Json]],
    // Set once `supported_features` enables it, mirroring HA: from then on every
    // frame is a JSON ARRAY of payloads, even a single one.
    coalescing: Ref[IO, Boolean],
    answerPings: Ref[IO, Boolean],
    rejected: Ref[IO, Set[String]],
    held: Ref[IO, Set[String]]
) {

  /** The client under test connects through this. */
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

  // --- What the client sent us ----------------------------------------------

  /** Every command frame the client has sent, in order, decoded. */
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

  /** The default server: ack what the runtime issues, `pong` a `ping`. Anything
    * a test wants to happen instead of (or after) this, it drives itself.
    */
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
            // Ack BEFORE switching framing, so one connection exercises both the
            // bare-object and the array shape.
            emit(ack(id)) *> coalescing.set(true)
          case _ => emit(ack(id))
        }
    }
  }

  // --- Driving the client ---------------------------------------------------

  /** Send payloads to the client. Coalescing decides the framing: once enabled,
    * everything given here rides in ONE array frame (which is how a burst stays
    * a single fs2 chunk); before that, one frame each.
    */
  def emit(payloads: Json*): IO[Unit] =
    coalescing.get.flatMap {
      case true  => emitFrame(Json.arr(payloads*).noSpaces)
      case false => payloads.toList.traverse_(p => emitFrame(p.noSpaces))
    }

  /** Send raw text, bypassing every shape check — for malformed frames and for
    * pinning a framing regardless of the coalescing flag.
    */
  def emitFrame(text: String): IO[Unit] =
    outgoing.offer(Some(WSFrame.Text(text)))

  /** End the receive stream: the socket closed. */
  def close: IO[Unit] = outgoing.offer(None)

  /** Stop answering `ping`, so the keepalive marks the connection dead. */
  def stopAnsweringPings: IO[Unit] = answerPings.set(false)

  /** Make HA refuse a command type, as it does for an unknown subscription. */
  def reject(commandType: String): IO[Unit] = rejected.update(_ + commandType)

  /** Record a command type but send NO automatic reply, so the test owns the
    * response — the only way to control what shares a frame with what.
    */
  def hold(commandType: String): IO[Unit] = held.update(_ + commandType)

  // --- Payload builders -----------------------------------------------------

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

  /** The id the client allocated for its `n`th command (1-based), so a test can
    * address a subscription it did not choose the id for.
    */
  def idOf(n: Int): IO[Int] =
    sentRef.get.map(_(n - 1).hcursor.get[Int]("id").getOrElse(0))
}

object FakeHaSocket {

  val Token = "test-token"

  /** A socket already offering `auth_required`, as HA does on connect. */
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
