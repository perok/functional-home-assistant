package api

import cats.effect.*
import cats.syntax.all.*

object DocumentJson {
  import io.circe.Json

  private lazy val decoders = {
    // Raised max arity because HA's get_states/get_services blobs exceed the
    // jsoniter default.
    import smithy4s.json.Json
    Json.payloadCodecs
      .withJsoniterCodecCompiler(Json.jsoniter.withMaxArity(99999))
      .decoders
  }

  /** Decode circe JSON into a smithy4s type via its schema — how a command's
    * result decoder plugs smithy types into the circe-typed protocol.
    */
  def fromJson2[A: smithy4s.Schema](json: Json): Either[Throwable, A] = {
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

}

// https://github.com/disneystreaming/smithy4s/discussions/558#discussioncomment-3987014
// raw string..
/* object MyRestJsonBuilder
    extends SimpleProtocolBuilder[smithy4s.http4s.SimpleRestJsonBuilder](
      // notable change from the definition of `SimpleRestJsonBuilder`
      CodecAPI.nativeStringsAndBlob(
        //
        smithy4s.http.json.codecs(
          smithy4s.api.SimpleRestJson.protocol.hintMask ++ HintMask(InputOutput)
        )
      )
    )
 */

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
