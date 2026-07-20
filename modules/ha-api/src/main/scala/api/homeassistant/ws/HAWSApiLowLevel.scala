package api.homeassistant.ws

import cats.syntax.all.*
import api.homeassistant.ws.protocol.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.protocol.server.{
  WSCommandPhaseServer,
  WSCommandPhaseServerPayload,
  WSHAError
}
import cats.effect.kernel.Deferred
import cats.effect.std.{MapRef, Mutex, QueueSource}
import cats.effect.{IO, Ref, Resource}
import io.circe.syntax.*
import io.circe.parser.*
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
  // TODO sendSync return Int id, deferred to receiveStream to get response for that id
  // TODO move WSCommandPhaseClient into just being a trait?
  // def send(in: WSCommandPhaseClient): F[Unit]
  // TODO subsctiveEvents(event_type = "state_changed")
  // def subscribeStateChanged: Resource[IO, QueueSource[IO, WSCommandPhaseServer]]

  def sendCommand[Response](
      command: CommandPhase & CommandResponse.AsResult[Response]
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

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body): IO[Unit] =
      wsClient.sendText(in.asJson.noSpaces)

    def receiveStreamDecode[Body: Decoder]: Stream[IO, Body] =
      wsClient.receiveStream.evalMap {
        case WSFrame.Text(data, true) =>
          // println(s"<-- Receiving: ${data.take(100)}") TODO only when DEBUG
          decode[Body](data).liftTo[IO].onError { err =>
            IO.println(s"receiveStreamDecode error decoding: $data")
          }
        case unknown =>
          IO.raiseError(
            new Throwable(s"receiveStreamDecode received unknown: $unknown")
          )
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
          mutex <- Mutex[IO].toResource
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

          // Overview of listeners for one specific message
          idDeferreds <- MapRef
            .ofSingleImmutableMap[IO, Int, Deferred[
              IO,
              WSCommandPhaseServerPayload
            ]]()
            .toResource

          // TODO id must be input
          setDeferred =
            (
              IO.deferred[WSCommandPhaseServerPayload],
              incrementer
            ).tupled.toResource
              .flatMap((deferred, id) =>
                Resource
                  .make(idDeferreds.setKeyValue(id, deferred))(_ =>
                    idDeferreds.unsetKey(id)
                  )
                  .as(deferred)
              )

          // Overview of listeners for specific ha subscriptions
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[
              IO,
              WSCommandPhaseServerPayload
            ]]()
            .toResource

          // Route each message by id. A pending DEFERRED (a command awaiting its
          // `result`) takes priority and is mutually exclusive with the queue:
          // HA always sends a subscribe command's `result` ack BEFORE any of its
          // events, so the ack completes the deferred (and is NOT enqueued),
          // while every later event — no deferred left for that id — lands in
          // the subscription's queue. This mutual exclusion is what lets a
          // subscription register its queue BEFORE the ack (closing the
          // first-event race) without the ack itself polluting the queue.
          // TODO errors in parsing before passing on the message can deadlock things
          _ <- topic.subscribeUnbounded
            .through(
              _.evalMap(command =>
                idDeferreds(command.id).get.flatMap {
                  case Some(deferred) =>
                    idDeferreds.unsetKey(command.id) >>
                      deferred.complete(command).void
                  case None =>
                    idQueue(command.id).get.flatMap {
                      case Some(queue) => queue.offer(command)
                      case None        => IO.unit
                    }
                }
              )
            )
            .compile
            .drain
            .background

          // Idle keepalive: when no frame has arrived for `pingInterval`, send a
          // `ping` (through the shared id/mutex so ids stay monotonic) and wait
          // for the matching `pong` — which the dispatch above routes back to
          // this deferred by id. A missed pong means the socket is dead: mark
          // the connection terminated so `awaitClosed` fires. Live traffic keeps
          // `lastActivity` fresh, so a busy connection is never pinged.
          _ <- {
            val pingId = "ping"
            def sendPing: IO[Boolean] =
              IO.deferred[WSCommandPhaseServerPayload].flatMap { pong =>
                mutex.lock
                  .surround(
                    incrementer.flatMap { id =>
                      idDeferreds.setKeyValue(id, pong) *>
                        ha
                          .sendText(
                            Json
                              .obj(
                                "id" -> Json.fromInt(id),
                                "type" -> Json.fromString(pingId)
                              )
                              .noSpaces
                          )
                          .as(id)
                    }
                  )
                  .flatMap { id =>
                    pong.get
                      .timeout(pingTimeout)
                      .as(true)
                      .handleError(_ => false)
                      .guarantee(idDeferreds.unsetKey(id))
                  }
              }
            def loop: IO[Unit] =
              (IO.monotonic, lastActivity.get).flatMapN { (now, last) =>
                val idle = now - last
                if (idle >= pingInterval)
                  sendPing.flatMap {
                    case true  => loop
                    case false =>
                      terminated
                        .complete(
                          Left(new Throwable("Home Assistant ping timed out"))
                        )
                        .void
                  }
                else IO.sleep(pingInterval - idle) >> loop
              }
            loop.background
          }
        } yield {

          def sendCommandPhase(id: Int, in: Json): IO[Unit] = {
            // Everything in command phase has id https://developers.home-assistant.io/docs/api/websocket/#command-phase
            val idJson = Json.obj(("id" -> Json.fromInt(id)))
            val toSend = in.deepMerge(idJson)
            println(s"--> Sending ${toSend.noSpaces}")
            ha.sendText(toSend.noSpaces)
          }

          def sendCommandWrapper[Response](
              command: CommandPhase & CommandResponse[Response],
              // Runs UNDER the id/send mutex, after the id is allocated and just
              // before the frame goes out. A subscription uses it to register
              // its receive queue for `id` before the subscribe frame is sent,
              // so an event that HA pushes immediately after the `result` ack
              // (render_template's single initial render) can't be lost in the
              // gap between ack and registration.
              beforeSend: Int => IO[Unit] = _ => IO.unit
          ): IO[(Int, Response)] =
            // error code=id_reuse
            // Mutex s around incrementer and send.
            // It always has to send the id's linearly.
            // Waiting can happen async. That's why we trick around with the ref stuff.
            (
              IO.ref[Option[Deferred[IO, WSCommandPhaseServerPayload]]](None),
              IO.ref[Option[Int]](None)
            ).flatMapN { (deferredRef, idRef) =>
              mutex.lock
                .surround(
                  (
                    IO.deferred[WSCommandPhaseServerPayload]
                      .flatTap(d => deferredRef.set(Some(d))),
                    incrementer.flatTap(id => idRef.set(Some(id)))
                  )
                    .flatMapN { (deferred, id) =>
                      //  TODO idDeffereds set as Resource
                      idDeferreds.setKeyValue(id, deferred) >>
                        beforeSend(id) >>
                        sendCommandPhase(
                          id,
                          (command: CommandPhase).asJson
                        )
                    }
                )
                .flatMap(_ => deferredRef.get.map(_.get).flatMap(_.get))
                .guarantee(
                  // On cancel because of failure then idRef can be not set. Therefore voidError
                  idRef.get.map(_.get).flatMap(idDeferreds.unsetKey).voidError
                )
            }.flatMap(_.parsedPayload.liftTo[IO])
              .map {
                // The logic afterwards expects to parse empty json
                // If it's a success without data.
                // But the result key is not set on errors, so this helps
                // with aligning those two cases.
                case d @ WSCommandPhaseServer.result(
                      _,
                      true,
                      None,
                      _
                    ) =>
                  d.copy(result = Some(Json.Null))
                case other => other
              }
              .flatMap {
                case WSCommandPhaseServer.result(
                      id,
                      true,
                      Some(result),
                      _
                    ) =>
                  given Decoder[Response] = command.resultDecoder
                  result
                    .as[Response]
                    .liftTo[IO]
                    .map(response => (id, response))
                case WSCommandPhaseServer.result(
                      _,
                      false,
                      _,
                      error
                    ) =>
                  IO.raiseError(
                    error
                      .flatMap(json => json.as[WSHAError].toOption)
                      .getOrElse(
                        new Exception(
                          s"Message $command failed. Error:\n$error"
                        )
                      )
                  )
                case nonsense =>
                  IO.raiseError(
                    new Exception(s"$command responded with $nonsense")
                  )
              }

          new HAWSApiLowLevel[IO] {
            def awaitClosed: IO[Unit] =
              terminated.get.flatMap(IO.fromEither)

            def receiveStream: Stream[IO, WSCommandPhaseServerPayload] =
              topic.subscribeUnbounded

            def sendCommand[Response](
                command: CommandPhase & CommandResponse.AsResult[Response]
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
            ): Resource[IO, QueueSource[IO, Result]] =
              Resource
                .eval(Queue.unbounded[IO, WSCommandPhaseServerPayload])
                .flatMap { q =>
                  Resource
                    .make(
                      // Register `q` for the subscription id BEFORE the subscribe
                      // frame is sent (beforeSend runs under the send mutex), so
                      // no event is lost between the `result` ack and queue
                      // registration — the bug that made render_template one-shot
                      // hang on its single initial event.
                      sendCommandWrapper(
                        msg,
                        beforeSend = id => idQueue.setKeyValue(id, q)
                      ).map(_._1)
                    )(id =>
                      sendCommandWrapper(
                        CommandPhase.unsubscribe_events(id)
                      ).void
                        .guarantee(idQueue.unsetKey(id))
                    )
                    .as(
                      (q: QueueSource[IO, WSCommandPhaseServerPayload])
                        .map(msg.decodeMessage)
                    )
                }
          }
        }
      }
  }
}
