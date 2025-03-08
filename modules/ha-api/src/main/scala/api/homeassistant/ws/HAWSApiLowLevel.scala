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
}

object HAWSApiLowLevel {

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body): IO[Unit] =
      wsClient.sendText(in.asJson.noSpaces)

    def receiveStreamDecode[Body: Decoder]: Stream[IO, Body] =
      wsClient.receiveStream.evalMap {
        case WSFrame.Text(data, true) =>
          println(s"<-- Receiving: ${data.take(100)}")
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
                case None =>
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
      secretToken: String
  ): Resource[IO, HAWSApiLowLevel[IO]] = {
    import fs2.concurrent.Topic
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
          // TODO ping pong in the background https://developers.home-assistant.io/docs/api/websocket/#pings-and-pongs

          mutex <- Mutex[IO].toResource
          // Always new unique id
          incrementer <- Ref[IO]
            .of(1)
            .map(ref => ref.getAndUpdate(_ + 1))
            .toResource

          // Receives all messages. All can listen
          // TODO worth skipping this and go right to defered and queue?
          //  Means that we accept loosing messages that are not subscribed in any way
          topic <- Topic[IO, WSCommandPhaseServerPayload].toResource
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServerPayload]
            .through(topic.publish)
            // TODO onError restart
            .compile
            .drain
            .background

          // Overview of listeners for one specific message
          idDeferreds <- MapRef
            .ofSingleImmutableMap[IO, Int, Deferred[
              IO,
              WSCommandPhaseServerPayload
            ]]()
            .toResource

          // Overview of listeners for specific ha subscriptions
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[
              IO,
              WSCommandPhaseServerPayload
            ]]()
            .toResource

          // Subscribe to all events and push them to listeners overview
          // TODO errors in parsing before passing on the message can deadlock things
          _ <- topic.subscribeUnbounded
            .through(
              _.evalMap(command =>
                (
                  idQueue(command.id).get.flatMap {
                    case Some(queue) => queue.offer(command)
                    case None        => IO.unit
                  },
                  idDeferreds(command.id).get.flatMap {
                    case Some(deferred) =>
                      // There is only one deferred complete, so we can safely try to remove it
                      idDeferreds.unsetKey(command.id) >>
                        deferred.complete(command)
                    case None => IO.unit
                  }
                ).parTupled
              )
            )
            .compile
            .drain
            .background
        } yield {

          def sendCommandPhase(id: Int, in: Json): IO[Unit] = {
            // Everything in command phase has id https://developers.home-assistant.io/docs/api/websocket/#command-phase
            val idJson = Json.obj(("id" -> Json.fromInt(id)))
            val toSend = in.deepMerge(idJson)
            println(s"--> Sending ${toSend.noSpaces}")
            ha.sendText(toSend.noSpaces)
          }

          def sendCommandWrapper[Response](
              command: CommandPhase & CommandResponse[Response]
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
                .make(
                  sendCommandWrapper(msg).map(_._1)
                )(id =>
                  sendCommandWrapper(CommandPhase.unsubscribe_events(id)).void
                )
                .flatMap { id =>
                  //  TODO idQueue set as Resource
                  // TODO needs to happen before the sendCommand to ensure receiving everything
                  Resource.make(
                    Queue
                      .unbounded[IO, WSCommandPhaseServerPayload]
                      .flatMap(q =>
                        idQueue
                          .setKeyValue(id, q)
                          .as(q)
                      )
                      .nested
                      .map { r =>
                        // TODO handle better
                        val rr = r.parsedPayload.fold(throw _, identity)
                        msg.f(rr)
                      }
                      .value
                  )(_ => idQueue.unsetKey(id))
                }
          }
        }
      }

  }

}
