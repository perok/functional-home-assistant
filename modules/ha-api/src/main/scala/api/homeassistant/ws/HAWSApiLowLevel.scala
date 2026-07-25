package api.homeassistant.ws

import cats.syntax.all.*
import cats.effect.std.Console
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.server.WSCommandPhaseServerPayload
import cats.effect.std.{MapRef, Queue}
import cats.effect.{Deferred, IO, Ref, Resource}
import io.circe.syntax.*
import fs2.{Stream, Pull}
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

  /** Subscribe, yielding the decoded event stream. The Resource ACQUIRE
    * registers the id and sends the command, so events are being captured from
    * the moment `use` begins — a caller may subscribe before doing work whose
    * events it must not miss. Release unsubscribes.
    */
  def subscribeStream[Result](
      msg: CommandPhase & CommandResponse.AsStream[Result]
  ): Resource[IO, Stream[IO, Result]]

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
      id: Deferred[IO, Int],
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
                _ <- msg.id.complete(id)
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
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServerPayload]
            .evalTap { command =>
              idQueue(command.id).get.flatMap {
                case Some(queue) => queue.offer(command)
                case None        =>
                  Console[IO].errorln(
                    s"Received message, but not receivers: $command"
                  )
              } >>
                IO.monotonic.flatMap(lastActivity.set)
            }
            .compile
            .drain
            // The receive loop only ends when the socket closes (or a frame
            // fails to decode). Either way the connection is done: report it so
            // the holder reconnects instead of hanging on a dead socket.
            .attempt
            .flatMap(res => terminated.complete(res).void)
            .background

          op = {
            new HAWSApiLowLevel[IO] {
              def awaitClosed: IO[Unit] =
                terminated.get.flatMap(IO.fromEither)

              /** Send `msg` and route its response frames to a fresh queue.
                * Acquire allocates the id, registers the route and sends;
                * release runs the command's own unsubscribe (if any) and drops
                * the route. Undecoded — each caller applies the command's own
                * codec.
                */
              private def open(
                  msg: CommandPhase & CommandResponse[?]
              ): Resource[IO, Stream[IO, WSCommandPhaseServerPayload]] = {
                val send = for {
                  payloadQueue <- Queue
                    .unbounded[IO, WSCommandPhaseServerPayload]
                  idDeferred <- IO.deferred[Int]
                  _ <- messageQueue.offer(
                    Command(msg, idDeferred, payloadQueue)
                  )
                  id <- idDeferred.get
                } yield (payloadQueue, id)

                Resource
                  .make(send) { (_, id) =>
                    val finalizationIO = msg match {
                      case finalizer: CommandResponse.WithFinalization[?] =>
                        sendCommand(finalizer.finalizationMessage(id))
                      case _ => IO.unit
                    }

                    finalizationIO.guarantee(idQueue.unsetKey(id)).void
                  }
                  // Dequeues in batches, so a burst of frames reaches the
                  // consumer as one chunk.
                  .map((q, _) => Stream.fromQueueUnterminated(q))
              }

              def sendCommand[Response](
                  command: CommandPhase &
                    CommandResponse.WithSingleResponse[Response]
              ): IO[Response] =
                open(command)
                  .use(_.head.compile.lastOrError)
                  .flatMap(command.decodeMessage)

              def subscribeStream[Result](
                  msg: CommandPhase & CommandResponse.AsStream[Result]
              ): Resource[IO, Stream[IO, Result]] =
                open(msg).map { frames =>
                  // The first frame is the subscribe ack (decoded to surface an
                  // HA error); everything after it is an event.
                  frames.pull.uncons1
                    .flatMap {
                      case Some((ack, events)) =>
                        Pull.eval(msg.decodeMessage(ack)) >> events.pull.echo
                      case None => Pull.done
                    }
                    .stream
                    .evalMapChunk(msg.decodeStreamMessage)
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
