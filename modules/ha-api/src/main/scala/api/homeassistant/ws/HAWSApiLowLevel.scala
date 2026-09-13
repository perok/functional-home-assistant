package api.homeassistant.ws

import cats.syntax.all.*
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.server.WSCommandPhaseServerPayload
import cats.effect.std.Queue
import cats.effect.{Deferred, IO, Ref, Resource}
import io.circe.syntax.*
import fs2.{Chunk, Stream}
import fs2.concurrent.SignallingRef
import io.circe.parser.decode
import io.circe.{Decoder, Encoder, Json}
import org.http4s.Uri
import org.typelevel.log4cats.slf4j.Slf4jLogger
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

  // Frame tracing lives at DEBUG. With `subscribe_entities` that is a line per
  // state change of the whole house, which is why it is not on by default —
  // but it is now a logger level like everything else, not a flag threaded
  // through the transport's constructor.
  private val log = Slf4jLogger.getLogger[IO]

  /** The error a caller sees for work issued on a dead connection: the cause
    * that killed it when there was one, since "closed" alone loses the only
    * clue why (a clean socket end has no exception to reuse).
    */
  private def closed(cause: Either[Throwable, Unit]): Throwable =
    cause.fold(
      identity,
      _ => new Exception("Home Assistant connection is closed")
    )

  private case class Command(
      message: CommandPhase,
      id: Deferred[IO, Int],
      // Chunks, not payloads: a coalesced frame is handed over whole so the
      // batch HA sent is the batch the consumer sees. `None` is the
      // connection's death, which closes the route rather than stranding it.
      response: Queue[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
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
    def receiveStreamDecode[Body: Decoder](): Stream[IO, Body] = {
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
            log.debug(s"<-- Receiving: ${data.take(100)}") *>
              decode[List[Body]](data)(using batch).liftTo[IO].onError { err =>
                log.error(err)(s"receiveStreamDecode error decoding: $data")
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
      pingTimeout: FiniteDuration = 10.seconds
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
          // Monotonic timestamp of the last frame received, as a SIGNAL: the
          // keepalive watches it rather than polling it, so a frame does not
          // just refresh a deadline, it cancels the pending ping outright.
          lastActivity <- IO.monotonic
            .flatMap(SignallingRef[IO].of)
            .toResource

          // id -> waiting-queue for every in-flight command (single responses
          // and subscriptions alike).
          //
          // A queue element is `Option[Chunk]`: a CHUNK so a coalesced frame is
          // routed as one batch rather than element by element, and OPTIONAL so
          // `None` can mean "this connection is over". A plain `Ref` rather than
          // `MapRef` because death has to reach EVERY route at once, which needs
          // the whole map.
          routes <- Ref[IO]
            .of(
              Map
                .empty[Int, Queue[IO, Option[
                  Chunk[WSCommandPhaseServerPayload]
                ]]]
            )
            .toResource

          // The single death rite: report the cause, then close every open
          // route. Callers waiting on a reply see `None` and fail; subscription
          // streams see `None` and TERMINATE — no one is left blocked on a queue
          // that can never be fed again. Idempotent, so the receive loop and the
          // keepalive can both call it.
          die = (cause: Either[Throwable, Unit]) =>
            terminated.complete(cause) *>
              routes.get.flatMap(_.values.toList.traverse_(_.offer(None)))

          // One drain fiber, so id allocation + registration + send stay linear
          // (HA rejects reused ids).
          messageQueue <- Queue.bounded[IO, Command](10).toResource
          _ <- Stream
            .fromQueueUnterminated(messageQueue)
            .evalMap { msg =>
              for {
                id <- incrementer
                _ <- routes.update(_.updated(id, msg.response))
                _ <- msg.id.complete(id)
                _ <- {
                  // Everything in command phase has id https://developers.home-assistant.io/docs/api/websocket/#command-phase
                  val idJson = Json.obj(("id" -> Json.fromInt(id)))
                  val toSend = msg.message.asJson.deepMerge(idJson)
                  log.debug(s"--> Sending ${toSend.noSpaces}") *>
                    ha.sendText(toSend.noSpaces)
                }
              } yield ()
            }
            .compile
            .drain
            // A failed send kills this loop, and every command queued after
            // that would block forever waiting for an id nobody allocates. So
            // the same death rite as the receive loop: report it, close every
            // route, and let the holder reconnect.
            .attempt
            .flatMap(die)
            .void
            .background

          // Registration precedes the send, so the queue always exists when HA
          // answers — ack and later events land in order, no first-event race.
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServerPayload]()
            // Route a whole FRAME at a time: group its payloads by id and hand
            // each subscription its group as one chunk. Doing this per payload
            // would dissolve the batch HA deliberately coalesced.
            .chunks
            .evalTap { frame =>
              routes.get.flatMap { open =>
                frame.toList
                  .groupBy(_.id)
                  .toList
                  .traverse_ { (id, payloads) =>
                    open.get(id) match {
                      case Some(queue) =>
                        queue.offer(Some(Chunk.from(payloads)))
                      case None =>
                        log.error(
                          s"Received message, but not receivers: $payloads"
                        )
                    }
                  }
              } >> IO.monotonic.flatMap(lastActivity.set)
            }
            .compile
            .drain
            // The receive loop only ends when the socket closes (or a frame
            // fails to decode). Either way the connection is done: report it so
            // the holder reconnects instead of hanging on a dead socket.
            .attempt
            .flatMap(die)
            .void
            .background

          op = {
            new HAWSApiLowLevel[IO] {
              def awaitClosed: IO[Unit] =
                terminated.get.flatMap(IO.fromEither)

              /** Fail if this connection has already died, carrying the cause
                * that killed it (an abnormal close names itself; a clean one
                * has no exception to reuse).
                */
              private val raiseIfDead: IO[Unit] =
                terminated.tryGet.flatMap(
                  _.traverse_(cause => IO.raiseError(closed(cause)))
                )

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
                * coalesced batch intact, and a `None` element is the
                * connection's death — see `die`.
                */
              private def openRoute(
                  msg: CommandPhase & CommandResponse[?]
              ): Resource[
                IO,
                Queue[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
              ] = {
                val send = for {
                  // Already dead: fail here rather than queue a command onto a
                  // socket that cannot answer. `die` has been and gone, so it
                  // will never close a route registered after it.
                  _ <- raiseIfDead
                  payloadQueue <- Queue
                    .unbounded[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
                  idDeferred <- IO.deferred[Int]
                  _ <- messageQueue.offer(
                    Command(msg, idDeferred, payloadQueue)
                  )
                  // The id is allocated by the drain fiber, so waiting on it
                  // blocks forever if that fiber is gone. Race the connection's
                  // death (which a dead drain fiber now reports) so a caller
                  // fails fast instead of hanging on an id nobody will mint.
                  id <- idDeferred.get.race(terminated.get).flatMap {
                    case Left(id)     => IO.pure(id)
                    case Right(cause) => IO.raiseError[Int](closed(cause))
                  }
                  // ...and close the race the check above cannot: if `die` ran
                  // between it and the registration, it passed over this route,
                  // so close it here. Either order ends with a `None` queued.
                  _ <- terminated.tryGet.flatMap(
                    _.traverse_(_ => payloadQueue.offer(None))
                  )
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
                          log.warn(
                            s"unsubscribe of $id failed: ${err.getMessage}"
                          )
                        case Right(_) => IO.unit
                      }
                      .guarantee(routes.update(_ - id))
                  }
                  .map((q, _) => q)
              }

              /** The next frame for this route, or the connection's death. One
                * `take`, so a caller can never block past the end of the
                * connection: `die` closes every route.
                */
              private def nextFrame(
                  queue: Queue[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
              ): IO[Chunk[WSCommandPhaseServerPayload]] =
                queue.take.flatMap(
                  _.liftTo[IO](
                    new Exception(
                      "Home Assistant connection closed before it answered"
                    )
                  )
                )

              def sendCommand[Response](
                  command: CommandPhase &
                    CommandResponse.WithSingleResponse[Response]
              ): IO[Response] =
                // A one-shot answer is the first payload of the first frame for
                // this id; nothing else can be addressed to it. A socket that
                // dies mid-command raises here rather than blocking forever.
                openRoute(command).use(
                  nextFrame(_).flatMap(frame => command.decodeMessage(frame(0)))
                )

              def subscribeStream[Result](
                  msg: CommandPhase & CommandResponse.AsStream[Result]
              ): Resource[IO, Stream[IO, Result]] =
                openRoute(msg).evalMap { frames =>
                  // ACQUIRE = ACCEPTED: consume the leading `result` ack here,
                  // so `use` begins only once HA confirms the subscription and a
                  // rejection raises at the acquire rather than on some later
                  // pull.
                  nextFrame(frames).flatMap { first =>
                    msg
                      .decodeMessage(first(0))
                      .as(
                        // HA can coalesce the ack and this subscription's first
                        // events into ONE frame, so whatever shared the ack's
                        // frame is replayed ahead of the queue — as a chunk, so
                        // even that boundary case stays batched. The queue is
                        // None-terminated, so the stream ENDS when the
                        // connection does instead of hanging on a dead route.
                        (Stream.chunk(first.drop(1)) ++
                          Stream.fromQueueNoneTerminatedChunk(frames))
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

          // Idle keepalive: HA closes idle sockets and intermediaries drop quiet
          // TCP connections, so a connection that has heard nothing for
          // `pingInterval` is pinged; an unanswered ping marks it dead and
          // `awaitClosed` fires.
          _ <- {
            val ping: IO[Unit] =
              op.sendCommand[Unit](CommandPhase.ping())
                .timeout(pingTimeout)
                .void

            lastActivity.discrete
              // Every received frame CANCELS the pending sleep and starts it
              // over, so a ping fires only after real silence and a busy
              // connection is never pinged at all. Two things follow for free.
              // A dead socket produces no activity, so there is nothing left to
              // trigger this — it cannot spin against one, where a polling loop
              // had to be stopped explicitly. And traffic arriving DURING a ping
              // cancels it mid-flight, which is right: that traffic is the
              // liveness the ping was asking for.
              .switchMap(_ =>
                Stream.sleep[IO](pingInterval) ++ Stream.eval(ping.attempt)
              )
              .collect { case Left(err) => err }
              .evalMap(err =>
                die(
                  Left(
                    new Exception("Home Assistant ping went unanswered", err)
                  )
                ).void
              )
              .compile
              .drain
              .background
          }
        } yield op
      }
  }
}
