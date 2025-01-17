package api

import cats.effect.*

object DocumentJson {
  // https://github.com/disneystreaming/smithy4s/discussions/954
  import smithy4s.Document
  import smithy4s.Document.*
  import io.circe.{Json, JsonObject}
  import smithy4s.codecs.PayloadError

  val decoder: Document.Decoder[Json] = new Document.Decoder[Json] {
    def decode(document: Document): Either[PayloadError, Json] = {
      def toJson(d: Document): Json = {
        d match {
          case DNumber(value)  => Json.fromBigDecimal(value)
          case DBoolean(value) => Json.fromBoolean(value)
          case DString(value)  => Json.fromString(value)
          case DNull           => Json.Null
          case DArray(value)   => Json.fromValues(value.map(toJson))
          case DObject(value) =>
            val newMap = value.map { case (k, v) => k -> toJson(v) }
            Json.fromJsonObject(JsonObject.fromMap(newMap))
        }
      }
      Right(toJson(document))
    }
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
