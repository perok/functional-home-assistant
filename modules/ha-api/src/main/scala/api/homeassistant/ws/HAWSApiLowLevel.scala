package api.homeassistant.ws

import cats.syntax.all.*
import cats.effect.std.Console
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.server.WSCommandPhaseServerPayload
import cats.effect.std.{MapRef, Queue}
import cats.effect.{Deferred, IO, Ref, Resource}
import io.circe.syntax.*
import fs2.{Chunk, Stream}
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

  /** Subscribe, yielding the decoded event stream. Acquire completes only when
    * HA has ACCEPTED the subscription (its `result` ack has arrived — a
    * rejection raises here, not on a later pull), so `use` begins with the
    * subscription demonstrably live: a caller may safely issue a command whose
    * events it must not miss afterwards. Release unsubscribes, best-effort.
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
      // Chunks, not payloads: a coalesced frame is handed over whole so the
      // batch HA sent is the batch the consumer sees. See `receiveStreamDecode`.
      response: Queue[IO, Chunk[WSCommandPhaseServerPayload]]
  )

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body): IO[Unit] =
      wsClient.sendText(in.asJson.noSpaces)

    /** Decode received frames, ONE ELEMENT PER PAYLOAD.
      *
      * With `coalesce_messages` enabled a frame is a JSON ARRAY of payloads (HA
      * wraps even a single one), so a frame yields a whole fs2 `Chunk`. A bare
      * object still decodes, so this works either way.
      *
      * The chunk is preserved all the way to the consumer: routing groups a
      * frame's payloads by id and offers each group to its subscription as ONE
      * chunk (see the receive loop and `subscribeStream`), so a burst HA
      * coalesced into a frame stays one batch — which is what lets
      * `HaFeed.pump` fold it into a single store update.
      */
    def receiveStreamDecode[Body: Decoder](
        debugFrames: Boolean
    ): Stream[IO, Body] = {
      // TODO ping pong on WSFrame?
      // Branch on the SHAPE, and name `decodeList` explicitly rather than
      // summoning `Decoder[List[Body]]` — that would resolve back to this very
      // decoder. Not a `given` either, for the same reason: it is passed to
      // `decode` by hand below.
      val batch: Decoder[List[Body]] = Decoder.instance(c =>
        if (c.value.isArray) Decoder.decodeList[Body].apply(c)
        else c.as[Body].map(List(_))
      )

      wsClient.receiveStream
        .evalMap {
          case WSFrame.Text(data, true) =>
            Console[IO]
              .println(s"<-- Receiving: ${data.take(100)}")
              .whenA(debugFrames) *>
              decode[List[Body]](data)(using batch).liftTo[IO].onError { err =>
                Console[IO].errorln(
                  s"receiveStreamDecode error decoding: $data"
                ) >>
                  Console[IO].printStackTrace(err)
              }
          case unknown =>
            IO.raiseError(
              new Throwable(s"receiveStreamDecode received unknown: $unknown")
            )
        }
        .flatMap(payloads => Stream.chunk(Chunk.from(payloads)))
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
      pingTimeout: FiniteDuration = 10.seconds,
      // Trace every frame in both directions. Off by default: with
      // `subscribe_entities` this is one line per state change of the whole
      // house, so it is a deliberate debugging switch, never ambient logging.
      debugFrames: Boolean = false
  ): Resource[IO, HAWSApiLowLevel[IO]] = {
    import cats.effect.std.Queue

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
          // and subscriptions alike). Carries CHUNKS so a coalesced frame is
          // routed as one batch rather than element by element.
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[
              IO,
              Chunk[WSCommandPhaseServerPayload]
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
                  Console[IO]
                    .println(s"--> Sending ${toSend.noSpaces}")
                    .whenA(debugFrames) *>
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
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServerPayload](debugFrames)
            // Route a whole FRAME at a time: group its payloads by id and hand
            // each subscription its group as one chunk. Doing this per payload
            // would dissolve the batch HA deliberately coalesced.
            .chunks
            .evalTap { frame =>
              frame.toList
                .groupBy(_.id)
                .toList
                .traverse_ { (id, payloads) =>
                  idQueue(id).get.flatMap {
                    case Some(queue) => queue.offer(Chunk.from(payloads))
                    case None        =>
                      Console[IO].errorln(
                        s"Received message, but not receivers: $payloads"
                      )
                  }
                } >> IO.monotonic.flatMap(lastActivity.set)
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

              /** Queue `msg` for the wire and route its response frames to a
                * fresh queue. Acquire returns once the drain fiber has taken it
                * and allocated an id — the frame may still be in flight, so
                * this is NOT "HA has replied". Release runs the command's own
                * unsubscribe (if any) and drops the route. Undecoded — each
                * caller applies the command's own codec.
                *
                * The queue is handed over raw (not as a `Stream`) so a caller
                * can take the leading `result` frame in `IO` and then read the
                * rest as a stream: the QUEUE holds the position, so both views
                * of it compose without losing a frame. Its elements are whole
                * FRAMES (a chunk of this id's payloads), which is what keeps a
                * coalesced batch intact.
                */
              private def openRoute(
                  msg: CommandPhase & CommandResponse[?]
              ): Resource[IO, Queue[IO, Chunk[WSCommandPhaseServerPayload]]] = {
                val send = for {
                  payloadQueue <- Queue
                    .unbounded[IO, Chunk[WSCommandPhaseServerPayload]]
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

                    // A best-effort unsubscribe. It must neither fail nor block
                    // teardown: HA errors on an id it has already forgotten (a
                    // rejected subscription), and on a DEAD socket the reply can
                    // never arrive — racing `terminated` is what stops teardown
                    // hanging there, which would strand the supervisor instead
                    // of letting it reconnect.
                    finalizationIO.void
                      .race(terminated.get.void)
                      .attempt
                      .flatMap {
                        case Left(err) =>
                          Console[IO].errorln(
                            s"unsubscribe of $id failed: ${err.getMessage}"
                          )
                        case Right(_) => IO.unit
                      }
                      .guarantee(idQueue.unsetKey(id))
                  }
                  .map((q, _) => q)
              }

              def sendCommand[Response](
                  command: CommandPhase &
                    CommandResponse.WithSingleResponse[Response]
              ): IO[Response] =
                // A one-shot answer is the first payload of the first frame for
                // this id; nothing else can be addressed to it.
                openRoute(command).use(
                  _.take.flatMap(frame => command.decodeMessage(frame(0)))
                )

              def subscribeStream[Result](
                  msg: CommandPhase & CommandResponse.AsStream[Result]
              ): Resource[IO, Stream[IO, Result]] =
                openRoute(msg).evalMap { frames =>
                  // ACQUIRE = ACCEPTED: consume the leading `result` ack here,
                  // so `use` begins only once HA confirms the subscription and a
                  // rejection raises at the acquire rather than on some later
                  // pull.
                  frames.take.flatMap { first =>
                    msg
                      .decodeMessage(first(0))
                      .as(
                        // HA can coalesce the ack and this subscription's first
                        // events into ONE frame, so whatever shared the ack's
                        // frame is replayed ahead of the queue — as a chunk, so
                        // even that boundary case stays batched.
                        (Stream.chunk(first.drop(1)) ++
                          Stream.fromQueueUnterminatedChunk(frames))
                          .evalMapChunk(msg.decodeStreamMessage)
                      )
                  }
                }
            }
          }

          // The feature-enablement phase, before anything else is sent: HA
          // then batches a burst of events into one frame, which the receive
          // stream turns back into one chunk (see `receiveStreamDecode`).
          _ <- op
            .sendCommand[Unit](CommandPhase.supported_features())
            .toResource

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
