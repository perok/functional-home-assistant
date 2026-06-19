https://medium.com/@patrickharned/no-boilerlate-in-scala3-automatic-case-class-generation-0f5970a112a7 ?

xml literla for dotty?

what data to expose as static?:
  - floors
  - areas
  - entities
  -


---

smity4s for codegen classes?
```
import io.circe.syntax._
import org.http4s.websocket.WebSocketFrame
import org.http4s.server.websocket.WebSocketBuilder2
import smithy4s.json.JsonPayloadCodecCompiler

// 1. Leverage Smithy4s schemas for strict type safety
val codecCompiler = JsonPayloadCodecCompiler.getCodec(Message.schema)

def wsRoutes(wsb: WebSocketBuilder2[IO]) = HttpRoutes.of[IO] {
  case GET -> Root / "ws" =>
    val send: fs2.Stream[IO, WebSocketFrame] = ??? // your outbound stream
    val receive: fs2.Pipe[IO, WebSocketFrame, Unit] = ??? // your inbound stream
    
    wsb.build(send, receive)
}
```
