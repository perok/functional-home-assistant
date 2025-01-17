package api.homeassistant.ws

import cats.syntax.all.*
import api.homeassistant.ws.client.WSCommandPhaseClient
import api.homeassistant.ws.server.WSCommandPhaseServer
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

// TODO its high level
trait HAWSApiLowLevel[F[_]] {
  def receiveStream: Stream[F, WSCommandPhaseServer]
  // TODO sendSync return Int id, deferred to receiveStream to get response for that id
  def send(in: WSCommandPhaseClient): F[Unit]
  // TODO subsctiveEvents(event_type = "state_changed")
  def subscribeStateChanged: Resource[IO, QueueSource[IO, WSCommandPhaseServer]]
}

object HAWSApiLowLevel {

  extension (wsClient: WSConnectionHighLevel[IO])
    def sendEncode[Body: Encoder](in: Body) =
      wsClient.sendText(in.asJson.noSpaces)

    def receiveStreamDecode[Body: Decoder] =
      wsClient.receiveStream.evalMap {
        case WSFrame.Text(data, true) =>
          // TODO handle [info] {"id":null,"type":"result","success":false,"error":{"code":"invalid_format","message":"Message incorrectly formatted."}}
          decode[Body](data).liftTo[IO].onError { err =>

            pprint("")
            err.printStackTrace()
            IO.println("ERR") >>
              IO.println(decode[Json](data))
          }
        case unknown =>
          IO.raiseError(new Throwable(s"Received unknown: $unknown"))
      }

    def receiveDecode[Body: Decoder](
        validate: PartialFunction[Body, Body] = (b: Body) => b
    ) =
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
    import fs2.concurrent.Channel
    import fs2.concurrent.Topic
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

          // Receives all messages. All can listen
          topic <- Topic[IO, WSCommandPhaseServer].toResource
          _ <- ha
            .receiveStreamDecode[WSCommandPhaseServer]
            .through(topic.publish)
            .compile
            .drain
            .background

          // Overview of listeners for specific ha subscriptions
          idQueue <- MapRef
            .ofSingleImmutableMap[IO, Int, Queue[IO, WSCommandPhaseServer]]()
            .toResource

          // Subscribe to all events and push them to listeners overview
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

          // Always new unique id
          incrementer <- Ref[IO]
            .of(1)
            .map(ref => ref.getAndUpdate(_ + 1))
            .toResource
        } yield {

          def sendRaw(in: Int => WSCommandPhaseClient): IO[Int] =
            incrementer.flatMap(id => ha.sendEncode(in(id)).as(id))

          def sendEvent(event: Int => WSCommandPhaseClient): IO[Int] =
            topic.subscribeAwaitUnbounded.use(commands =>
              sendRaw(id => event(id)).flatTap(id =>
                commands
                  .collectFirst {
                    case a @ WSCommandPhaseServer.result(id, _, _, _) =>
                      a
                  }
                  .compile
                  .lastOrError
                  .flatMap {
                    case WSCommandPhaseServer.result(_, true, _, _) =>
                      IO.unit
                    case WSCommandPhaseServer
                          .result(_, false, _, error) =>
                      IO.raiseError(
                        new Exception(s"subscribing failed: $error")
                      )
                  }
                  // Try to ensure that subscription is cancelled, if there
                  // are other reasons for things failing
                  .onError(_ =>
                    sendRaw(cancelId =>
                      WSCommandPhaseClient
                        .unsubscribe_events(cancelId, id)
                    ).void
                  )
              )
            )

          new HAWSApiLowLevel[IO] {
            def receiveStream: Stream[IO, WSCommandPhaseServer] =
              topic.subscribeUnbounded

            def send(in: WSCommandPhaseClient): IO[Unit] = ha.sendEncode(in)

            //
            // todo https://developers.home-assistant.io/docs/api/websocket#fire-an-event

            // todo https://developers.home-assistant.io/docs/api/websocket#subscribe-to-trigger

            // https://developers.home-assistant.io/docs/api/websocket#subscribe-to-events
            // TODO stream instead?
            // TODO event types
            def subscribeStateChanged
                : Resource[IO, QueueSource[IO, WSCommandPhaseServer]] =
              Resource
                .make(
                  sendEvent(id =>
                    WSCommandPhaseClient
                      .subscribe_events(id, Some("state_changed"))
                  )
                )(id =>
                  sendEvent(cancelId =>
                    WSCommandPhaseClient.unsubscribe_events(cancelId, id)
                  ).void
                )
                .evalMap { id =>
                  Queue
                    .synchronous[IO, WSCommandPhaseServer]
                    .flatMap(q => idQueue(id).set(Some(q)).as(q))
                }
          }
        }
      }

  }

}
