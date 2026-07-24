package api.homeassistant.ws

import cats.syntax.all.*
import cats.effect.std.Console
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.server.WSCommandPhaseServerPayload
import cats.effect.std.{MapRef, QueueSource, Queue}
import cats.effect.{IO, Ref, Resource}
import io.circe.syntax.*
import fs2.Stream
import io.circe.parser.decode
import io.circe.{Decoder, Encoder, Json}
import org.http4s.Uri
import org.http4s.client.websocket.{
  WSClient,
  WSConnectionHighLevel,
  WSFrame,
  WSRequest
}

import scala.concurrent.duration.*

/** The single WebSocket seam `HomeAssistantApi` is built on. Routes frames by
  * id only; each command owns its codec, so this layer stays codec-agnostic.
  */
trait HAWSApiLowLevel[F[_]] {

  def sendCommand[Response](
      command: CommandPhase & CommandResponse.WithSingleResponse[Response]
  ): IO[Response]

  def subscribeStream[Result](
      msg: CommandPhase & CommandResponse.AsStream[Result]
  ): Resource[IO, QueueSource[IO, Result]]

  /** Completes when this connection is no longer usable — the receive stream
    * ended (socket closed) or a keepalive ping went unanswered — raising with
    * the cause when the close was abnormal, returning `unit` on a clean end.
    *
    * The connection supervises its OWN liveness (idle ping/pong + receive-loop
    * death) and reports it here; a holder races its work against this to learn
    * that the resource has effectively closed itself, then tears it down and
    * reconnects. Never completes while the connection is healthy.
    */
  def awaitClosed: IO[Unit]
}

object HAWSApiLowLevel {

  private case class Command(
      message: CommandPhase,
      response: Queue[IO, WSCommandPhaseServerPayload]
  )

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body): IO[Unit] =
      wsClient.sendText(in.asJson.noSpaces)

    def receiveStreamDecode[Body: Decoder]: Stream[IO, Body] = {
      // TODO ping pong on WSFrame?
      wsClient.receiveStream.evalMap {
        case WSFrame.Text(data, true) =>
          println(s"<-- Receiving: ${data.take(100)}") // TODO only when DEBUG
          decode[Body](data).liftTo[IO].onError { err =>
            Console[IO].errorln(s"receiveStreamDecode error decoding: $data") >>
              Console[IO].printStackTrace(err)
          }
        case unknown =>
          IO.raiseError(
            new Throwable(s"receiveStreamDecode received unknown: $unknown")
          )
      }
    }

    def receiveDecode[Body: Decoder](
        validate: PartialFunction[Body, Body] = (b: Body) => b
    ): IO[Body] =
      wsClient.receive.flatMap {
        case Some(WSFrame.Text(data, true)) =>
          decode[Body](data)
            .liftTo[IO]
            .flatMap { response =>
              validate.lift(response) match {
                case Some(value) => value.pure[IO]
                case None        =>
                  IO.raiseError(new Exception(s"Wrong msg: $response"))
              }
            }
        case Some(unknown) =>
          IO.raiseError(new Throwable(s"Received unknown: $unknown"))
        case None => IO.raiseError(new Throwable("Connection is closed"))
      }

  def apply(
      client: WSClient[IO],
      uri: Uri,
      secretToken: String,
      // Keepalive cadence. HA closes idle sockets and intermediaries drop quiet
      // TCP connections, so when no frame has arrived for `pingInterval` we send
      // a `ping` and expect a `pong` within `pingTimeout`; a missed pong marks
      // the connection dead (see `awaitClosed`). Idle-based: live traffic
      // (`state_changed` events) resets the timer, so a busy connection is never
      // pinged. https://developers.home-assistant.io/docs/api/websocket/#pings-and-pongs
      pingInterval: FiniteDuration = 30.seconds,
      pingTimeout: FiniteDuration = 10.seconds
  ): Resource[IO, HAWSApiLowLevel[IO]] = {
    import fs2.concurrent.Topic
    import cats.effect.std.Queue

    // TODO coalesce https://github.com/home-assistant/developers.home-assistant/pull/2128/files
    // https://developers.home-assistant.io/docs/api/websocket/#feature-enablement-phase
    client
      .connectHighLevel(WSRequest(uri))
      .evalTap { ha =>
        import api.homeassistant.ws.protocol.authentication.WSAuthenticationPhase
        for {
          _ <- ha.receiveDecode[WSAuthenticationPhase] {
            case a @ WSAuthenticationPhase.auth_required => a
          }

          _ <- ha.sendEncode(WSAuthenticationPhase.auth(secretToken))

          _ <- ha.receiveDecode[WSAuthenticationPhase] {
            case a @ WSAuthenticationPhase.auth_ok => a
          }
        } yield ()
      }
      .flatMap { ha =>
        for {
          // Always new unique id
          incrementer <- Ref[IO]
            .of(1)
            .map(ref => ref.getAndUpdate(_ + 1))
            .toResource

          // Fired once when the connection dies (receive loop ended, or a ping
          // went unanswered), carrying the cause; `awaitClosed` surfaces it so a
          // holder can reconnect. Left = abnormal, Right = clean socket end.
          terminated <- IO.deferred[Either[Throwable, Unit]].toResource
          // Monotonic timestamp of the last frame received. Drives the idle
          // ping: a live stream keeps this fresh so no ping is ever sent.
          lastActivity <- IO.monotonic.flatMap(Ref[IO].of).toResource

          topic <- Topic[IO, WSCommandPhaseServerPayload].toResource
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServerPayload]
            .evalTap(_ => IO.monotonic.flatMap(lastActivity.set))
            .through(topic.publish)
            .compile
            .drain
            // The receive loop only ends when the socket closes (or a frame
            // fails to decode). Either way the connection is done: report it so
            // the holder reconnects instead of hanging on a dead socket.
            .attempt
            .flatMap(res => terminated.complete(res).void)
            .background

          // id -> waiting-queue for every in-flight command (single responses
          // and subscriptions alike).
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[
              IO,
              WSCommandPhaseServerPayload
            ]]()
            .toResource

          // One drain fiber, so id allocation + registration + send stay linear
          // (HA rejects reused ids).
          messageQueue <- Queue.bounded[IO, Command](10).toResource
          _ <- Stream
            .fromQueueUnterminated(messageQueue)
            .evalMap { msg =>
              for {
                id <- incrementer
                _ <- idQueue.setKeyValue(id, msg.response)
                _ <- {
                  // Everything in command phase has id https://developers.home-assistant.io/docs/api/websocket/#command-phase
                  val idJson = Json.obj(("id" -> Json.fromInt(id)))
                  val toSend = msg.message.asJson.deepMerge(idJson)
                  println(s"--> Sending ${toSend.noSpaces}")
                  ha.sendText(toSend.noSpaces)
                }
              } yield ()
            }
            .attempt
            .evalTap {
              case Left(err) => Console[IO].printStackTrace(err)
              case Right(_)  => IO.unit
            }
            .compile
            .drain
            .background

          // Registration precedes the send, so the queue always exists when HA
          // answers — ack and later events land in order, no first-event race.
          // TODO errors in parsing before passing on the message can deadlock things
          _ <- topic.subscribeUnbounded
            .through(
              _.evalMap(command =>
                idQueue(command.id).get.flatMap {
                  case Some(queue) => queue.offer(command)
                  case None        => IO.unit
                }
              )
            )
            .compile
            .drain
            .background

          op = {
            def sendCommandWrapper[Response](
                command: CommandPhase &
                  CommandResponse.WithSingleResponse[Response]
            ): IO[(Int, Response)] =
              for {
                payloadQueue <- Queue.unbounded[IO, WSCommandPhaseServerPayload]
                _ <- messageQueue.offer(
                  Command(command, payloadQueue)
                )

                payload <- payloadQueue.take

                // Streamed requests will do cleanup in resource finalizers
                _ <- command match {
                  case _: CommandResponse.AsStream[?] =>
                    IO.unit
                  case _ =>
                    idQueue.unsetKey(payload.id)
                }

                output <- command
                  .decodeMessage(payload)
                  .map(res => (payload.id, res))

              } yield output

            new HAWSApiLowLevel[IO] {
              def awaitClosed: IO[Unit] =
                terminated.get.flatMap(IO.fromEither)

              def receiveStream: Stream[IO, WSCommandPhaseServerPayload] =
                topic.subscribeUnbounded

              def sendCommand[Response](
                  command: CommandPhase &
                    CommandResponse.WithSingleResponse[Response]
              ): IO[Response] =
                sendCommandWrapper(command).map(_._2)

              def subscribeStream[Result](
                  msg: CommandPhase & CommandResponse.AsStream[Result]
              ): Resource[IO, QueueSource[IO, Result]] = for {
                id <- sendCommandWrapper(
                  msg
                ).map(_._1).toResource

                q <- idQueue(id).get.toResource.map(_.get)

                _ <- Resource.onFinalize(
                  sendCommandWrapper(
                    CommandPhase.unsubscribe_events(id)
                  ).void
                    .guarantee(idQueue.unsetKey(id))
                )

              } yield {
                import cats.effect.unsafe.implicits.global

                (q: QueueSource[IO, WSCommandPhaseServerPayload])
                  .map(qRes =>
                    // TODO expose as stream instead?
                    msg.decodeStreamMessage(qRes).unsafeRunSync()
                  )
              }
            }
          }

          // Idle keepalive: ping only after `pingInterval` of silence; a missed
          // pong marks the connection dead so `awaitClosed` fires. Live traffic
          // refreshes `lastActivity`, so a busy connection is never pinged.
          _ <- {
            val ping: IO[Boolean] =
              op.sendCommand[Unit](CommandPhase.ping())
                .timeout(pingTimeout)
                .as(true)
                .handleErrorWith(err =>
                  Console[IO].printStackTrace(err).as(false)
                )

            val tick: IO[Boolean] =
              (IO.monotonic, lastActivity.get).flatMapN { (now, last) =>
                val idle = now - last
                if (idle >= pingInterval) ping
                else IO.sleep(pingInterval - idle).as(true)
              }

            Stream
              .repeatEval(tick)
              .evalMap {
                case true  => IO.unit
                case false =>
                  terminated
                    .complete(
                      Left(new Throwable("Home Assistant ping timed out"))
                    )
                    .void
              }
              .compile
              .lastOrError
              .background
          }
        } yield op
      }
  }
}
