package api

import cats.effect.*
import cats.syntax.all.*

object DocumentJson {
  import io.circe.{Decoder, DecodingFailure, Json}

  private lazy val decoders = {
    // Raised max arity because HA's get_states/get_services blobs exceed the
    // jsoniter default.
    import smithy4s.json.Json
    Json.payloadCodecs
      .withJsoniterCodecCompiler(Json.jsoniter.withMaxArity(99999))
      .decoders
  }

  def fromJson[A: smithy4s.Schema](json: Json): Either[Throwable, A] = {
    import smithy4s.Blob
    import io.circe.Printer

    decoders
      .fromSchema(implicitly[smithy4s.Schema[A]])
      .decode(
        Blob.view(
          Printer.noSpaces.printToByteBuffer(json)
        )
      )
      .leftMap(err =>
        new Throwable(
          s"Decoding circe json to smithy failed: ${json.noSpaces.take(50)}",
          err
        )
      )
  }

  /** How a command's `AsResult[A]` takes a smithy type into the circe-typed WS
    * protocol.
    */
  def circeDecoderFor[A: smithy4s.Schema]: Decoder[A] =
    Decoder.instance(cursor =>
      fromJson[A](cursor.value)
        .leftMap(err => DecodingFailure.fromThrowable(err, List.empty))
    )

}

object Middleware {
  import org.http4s.client.*
  import smithy4s.Hints
  import smithy4s.http4s._
  import org.http4s.headers.Authorization
  import org.http4s.*

  private def middleware(bearerToken: String): Client[IO] => Client[IO] = {
    inputClient =>
      Client[IO] { request =>
        val newRequest = request.putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, bearerToken))
        )

        inputClient.run(newRequest)
      }
  }

  def apply(bearerToken: String): ClientEndpointMiddleware[IO] =
    new ClientEndpointMiddleware.Simple[IO] {
      private val mid = middleware(bearerToken)
      def prepareWithHints(
          serviceHints: Hints,
          endpointHints: Hints
      ): Client[IO] => Client[IO] = {
        serviceHints.get[smithy.api.HttpBearerAuth] match {
          case Some(_) =>
            endpointHints.get[smithy.api.Auth] match {
              case Some(auths) if auths.value.isEmpty => identity
              case _                                  => mid
            }
          case None => identity
        }
      }
    }

}
