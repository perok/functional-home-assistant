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

/** Routes frames by id only; each command owns its codec. */
trait HAWSApiLowLevel[F[_]] {

  def sendCommand[Response](
      command: CommandPhase & CommandResponse.WithSingleResponse[Response]
  ): IO[Response]

  /** Acquire completes once HA has acked the subscription (a rejection raises
    * here), so a command issued in `use` cannot outrun it. Release
    * unsubscribes, best-effort.
    */
  def subscribeStream[Result](
      msg: CommandPhase & CommandResponse.AsStream[Result]
  ): Resource[IO, Stream[IO, Result]]

  /** Completes when the socket ends or a ping goes unanswered, raising the
    * cause on an abnormal close. A holder races its work against this and
    * reconnects.
    */
  def awaitClosed: IO[Unit]
}

object HAWSApiLowLevel {

  // Frame tracing is DEBUG: with `subscribe_entities` it is a line per state
  // change of the whole house.
  private val log = Slf4jLogger.getLogger[IO]

  /** The cause is the only clue why; a clean socket end has none to reuse. */
  private def closed(cause: Either[Throwable, Unit]): Throwable =
    cause.fold(
      identity,
      _ => new Exception("Home Assistant connection is closed")
    )

  private case class Command(
      message: CommandPhase,
      id: Deferred[IO, Int],
      // Whole frames, so a batch HA coalesced reaches the consumer as one.
      // `None` is the connection's death.
      response: Queue[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
  )

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body): IO[Unit] =
      wsClient.sendText(in.asJson.noSpaces)

    /** With `coalesce_messages` a frame is a JSON array (even of one); a bare
      * object still decodes. The frame stays one chunk through routing to the
      * consumer, which is what lets `HaFeed.pump` fold a burst into a single
      * store update.
      */
    def receiveStreamDecode[Body: Decoder](): Stream[IO, Body] = {
      // Not `Decoder[List[Body]]` and not a `given`: either resolves back to
      // this very decoder.
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
      // Idle-based: any received frame resets it, so a busy connection is
      // never pinged. A pong missing after `pingTimeout` kills the connection.
      // https://developers.home-assistant.io/docs/api/websocket/#pings-and-pongs
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
          incrementer <- Ref[IO]
            .of(1)
            .map(ref => ref.getAndUpdate(_ + 1))
            .toResource

          // Left = abnormal, Right = clean socket end.
          terminated <- IO.deferred[Either[Throwable, Unit]].toResource
          // A signal, so a frame cancels the pending ping outright rather than
          // refreshing a deadline.
          lastActivity <- IO.monotonic
            .flatMap(SignallingRef[IO].of)
            .toResource

          // A `Ref`, not `MapRef`: death has to reach every route at once.
          routes <- Ref[IO]
            .of(
              Map
                .empty[Int, Queue[IO, Option[
                  Chunk[WSCommandPhaseServerPayload]
                ]]]
            )
            .toResource

          // Every route gets `None`, so no caller or stream is left blocked on a
          // queue nothing will feed. Idempotent: the receive loop, the drain
          // fiber and the keepalive all call it.
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
                  // https://developers.home-assistant.io/docs/api/websocket/#command-phase
                  val idJson = Json.obj(("id" -> Json.fromInt(id)))
                  val toSend = msg.message.asJson.deepMerge(idJson)
                  log.debug(s"--> Sending ${toSend.noSpaces}") *>
                    ha.sendText(toSend.noSpaces)
                }
              } yield ()
            }
            .compile
            .drain
            // Otherwise every later command waits for an id nobody allocates.
            .attempt
            .flatMap(die)
            .void
            .background

          // Registration precedes the send, so an ack can never beat its route.
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServerPayload]()
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
            .attempt
            .flatMap(die)
            .void
            .background

          op = {
            new HAWSApiLowLevel[IO] {
              def awaitClosed: IO[Unit] =
                terminated.get.flatMap(IO.fromEither)

              private val raiseIfDead: IO[Unit] =
                terminated.tryGet.flatMap(
                  _.traverse_(cause => IO.raiseError(closed(cause)))
                )

              /** Acquire returns once an id is allocated, not once HA replied.
                * The queue is handed over raw so a caller can take the leading
                * `result` frame in `IO` and stream the rest without losing one.
                */
              private def openRoute(
                  msg: CommandPhase & CommandResponse[?]
              ): Resource[
                IO,
                Queue[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
              ] = {
                val send = for {
                  // `die` has been and gone; it will never close a route
                  // registered after it.
                  _ <- raiseIfDead
                  payloadQueue <- Queue
                    .unbounded[IO, Option[Chunk[WSCommandPhaseServerPayload]]]
                  idDeferred <- IO.deferred[Int]
                  _ <- messageQueue.offer(
                    Command(msg, idDeferred, payloadQueue)
                  )
                  // The drain fiber may be gone, and then no id is ever minted.
                  id <- idDeferred.get.race(terminated.get).flatMap {
                    case Left(id)     => IO.pure(id)
                    case Right(cause) => IO.raiseError[Int](closed(cause))
                  }
                  // `die` running between the check and the registration passed
                  // over this route. Either order ends with a `None` queued.
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

                    // Must neither fail nor block teardown: HA errors on an id
                    // it already forgot (a rejected subscription), and a dead
                    // socket never replies, which would strand the supervisor.
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
                openRoute(command).use(
                  nextFrame(_).flatMap(frame => command.decodeMessage(frame(0)))
                )

              def subscribeStream[Result](
                  msg: CommandPhase & CommandResponse.AsStream[Result]
              ): Resource[IO, Stream[IO, Result]] =
                openRoute(msg).evalMap { frames =>
                  nextFrame(frames).flatMap { first =>
                    msg
                      .decodeMessage(first(0))
                      .as(
                        // HA can coalesce the ack with the first events, so the
                        // rest of its frame is replayed ahead of the queue.
                        (Stream.chunk(first.drop(1)) ++
                          Stream.fromQueueNoneTerminatedChunk(frames))
                          .evalMapChunk(msg.decodeStreamMessage)
                      )
                  }
                }
            }
          }

          // Before anything else is sent, so HA coalesces from the first event.
          _ <- op
            .sendCommand[Unit](CommandPhase.supported_features())
            .toResource

          _ <- {
            val ping: IO[Unit] =
              op.sendCommand[Unit](CommandPhase.ping())
                .timeout(pingTimeout)
                .void

            lastActivity.discrete
              // A dead socket produces no frames, so this cannot spin against
              // one. Traffic during a ping cancels it: that traffic is the
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
