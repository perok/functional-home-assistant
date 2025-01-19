package api.homeassistant.ws

import cats.syntax.all.*
import api.homeassistant.ws.client.{CommandPhase, CommandResponse}
import api.homeassistant.ws.server.WSCommandPhaseServer
import cats.effect.kernel.Deferred
import cats.effect.std.{MapRef, QueueSource}
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
      command: CommandPhase & CommandResponse[Response]
  ): IO[(Int, Response)]

  def sendCommandStream(
      msg: CommandPhase & CommandResponse.Stream
  ): Resource[IO, QueueSource[IO, WSCommandPhaseServer]]
}

object HAWSApiLowLevel {

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body): IO[Unit] =
      wsClient.sendText(in.asJson.noSpaces)

    def receiveStreamDecode[Body: Decoder]: Stream[IO, Body] =
      wsClient.receiveStream.evalMap {
        case WSFrame.Text(data, true) =>
          println(s"Receiving: $data")
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
        import authentication.WSAuthenticationPhase
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

          // Always new unique id
          incrementer <- Ref[IO]
            .of(1)
            .map(ref => ref.getAndUpdate(_ + 1))
            .toResource

          // Receives all messages. All can listen
          topic <- Topic[IO, WSCommandPhaseServer].toResource
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServer]
            .through(topic.publish)
            .compile
            .drain
            .background

          // Overview of listeners for one specific message
          idDeferreds <- MapRef
            .ofSingleImmutableMap[IO, Int, Deferred[IO, WSCommandPhaseServer]]()
            .toResource

          // Overview of listeners for specific ha subscriptions
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[IO, WSCommandPhaseServer]]()
            .toResource

          // Subscribe to all events and push them to listeners overview
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
            println(s"Sending ${toSend.noSpaces}")
            ha.sendText(toSend.noSpaces)
          }

          new HAWSApiLowLevel[IO] {
            def receiveStream: Stream[IO, WSCommandPhaseServer] =
              topic.subscribeUnbounded

            def sendCommand[Response](
                command: CommandPhase & CommandResponse[Response]
            ): IO[(Int, Response)] =
              (IO.deferred[WSCommandPhaseServer], incrementer).flatMapN {
                (deferred, id) =>
                  //  TODO idDeffereds set as Resource
                  (idDeferreds.setKeyValue(id, deferred) >>
                    sendCommandPhase(
                      id,
                      (command: CommandPhase).asJson
                    ) >> deferred.get)
                    .guarantee(idDeferreds.unsetKey(id))
                    .flatMap {
                      case WSCommandPhaseServer.result(
                            _,
                            true,
                            result,
                            _
                          ) =>
                        given Decoder[Response] = command.decoder

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
                          new Exception(s"$command failed with $error")
                        )
                      case nonsense =>
                        IO.raiseError(
                          new Exception(s"$command responded with $nonsense")
                        )
                    }
              }

            //
            // todo https://developers.home-assistant.io/docs/api/websocket#fire-an-event

            // todo https://developers.home-assistant.io/docs/api/websocket#subscribe-to-trigger

            // https://developers.home-assistant.io/docs/api/websocket#subscribe-to-events
            // TODO event types
            def sendCommandStream(
                msg: CommandPhase & CommandResponse.Stream
            ): Resource[IO, QueueSource[IO, WSCommandPhaseServer]] =
              Resource
                .make(
                  sendCommand(msg).map(_._1)
                )(id => sendCommand(CommandPhase.unsubscribe_events(id)).void)
                .flatMap { id =>
                  //  TODO idQueue set as Resource
                  // TODO needs to happen before the sendCommand to ensure receiving everything
                  Resource.make(
                    Queue
                      .unbounded[IO, WSCommandPhaseServer]
                      .flatMap(q => idQueue.setKeyValue(id, q).as(q))
                  )(_ => idQueue.unsetKey(id))
                }
          }
        }
      }

  }

}
