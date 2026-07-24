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

// TODO its high level
trait HAWSApiLowLevel[F[_]] {
  // def receiveStream: Stream[F, WSCommandPhaseServer]
  // TODO move WSCommandPhaseClient into just being a trait?
  // def send(in: WSCommandPhaseClient): F[Unit]
  // TODO subsctiveEvents(event_type = "state_changed")
  // def subscribeStateChanged: Resource[IO, QueueSource[IO, WSCommandPhaseServer]]

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

          // Receives all messages. All can listen
          // TODO worth skipping this and go right to defered and queue?
          //  Means that we accept loosing messages that are not subscribed in any way
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

          // Overview of listeners for specific ha subscriptions
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[
              IO,
              WSCommandPhaseServerPayload
            ]]()
            .toResource

          messageQueue <- Queue.bounded[IO, Command](10).toResource
          _ <- Stream
            .fromQueueUnterminated(messageQueue)
            // It always has to send the id's linearly.
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

          // Route each message by id. A pending DEFERRED (a command awaiting its
          // `result`) takes priority and is mutually exclusive with the queue:
          // HA always sends a subscribe command's `result` ack BEFORE any of its
          // events, so the ack completes the deferred (and is NOT enqueued),
          // while every later event — no deferred left for that id — lands in
          // the subscription's queue. This mutual exclusion is what lets a
          // subscription register its queue BEFORE the ack (closing the
          // first-event race) without the ack itself polluting the queue.
          // TODO errors in parsing before passing on the message can deadlock things
          // TODO any point in keeping things here? the topic could just do this immediately?
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

              //
              // todo https://developers.home-assistant.io/docs/api/websocket#fire-an-event

              // todo https://developers.home-assistant.io/docs/api/websocket#subscribe-to-trigger

              // https://developers.home-assistant.io/docs/api/websocket#subscribe-to-events
              // TODO event type
              //
              // TODO [info] Receiving: {"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"sensor.ams_1a4e_daycost","old_state":{"entity_id":"sensor.ams_1a4e_daycost","state":"113.37","attributes":{"unit_of_measurement":"NOK","device_class":"monetary","friendly_name":"AMS reader Current day cost"},"last_changed":"2025-01-19T21:06:13.631582+00:00","last_reported":"2025-01-19T21:06:13.631582+00:00","last_updated":"2025-01-19T21:06:13.631582+00:00","context":{"id":"01JJ066DZZHGND4KT787YF4F0M","parent_id":null,"user_id":null}},"new_state":{"entity_id":"sensor.ams_1a4e_daycost","state":"113.38","attributes":{"unit_of_measurement":"NOK","device_class":"monetary","friendly_name":"AMS reader Current day cost"},"last_changed":"2025-01-19T21:06:16.124989+00:00","last_reported":"2025-01-19T21:06:16.124989+00:00","last_updated":"2025-01-19T21:06:16.124989+00:00","context":{"id":"01JJ066GDWGK26020G3AKHHVAZ","parent_id":null,"user_id":null}}},"origin":"LOCAL","time_fired":"2025-01-19T21:06:16.124989+00:00","context":{"id":"01JJ066GDWGK26020G3AKHHVAZ","parent_id":null,"user_id":null}},"id":2}
              //   type: trigger
              //   etc
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

          // TODO use the actual API instead of hardcoding
          // https://developers.home-assistant.io/docs/api/websocket/#pings-and-pongs
          // Idle keepalive: when no frame has arrived for `pingInterval`, send a
          // `ping` (through the shared id/mutex so ids stay monotonic) and wait
          // for the matching `pong` — which the dispatch above routes back to
          // this deferred by id. A missed pong means the socket is dead: mark
          // the connection terminated so `awaitClosed` fires. Live traffic keeps
          // `lastActivity` fresh, so a busy connection is never pinged.
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
