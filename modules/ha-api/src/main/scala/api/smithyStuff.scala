package api

import cats.effect.*
import cats.syntax.all.*

object DocumentJson {
  // https://github.com/disneystreaming/smithy4s/discussions/954
  import smithy4s.Document
  import smithy4s.Document.*
  import io.circe.{Json, JsonObject}
  import smithy4s.codecs.PayloadError

  // TODO StateStore.docTojson
  @deprecated
  val decoder: Document.Decoder[Json] = new Document.Decoder[Json] {
    def decode(document: Document): Either[PayloadError, Json] = {
      def toJson(d: Document): Json = {
        d match {
          case DNumber(value)  => Json.fromBigDecimal(value)
          case DBoolean(value) => Json.fromBoolean(value)
          case DString(value)  => Json.fromString(value)
          case DNull           => Json.Null
          case DArray(value)   => Json.fromValues(value.map(toJson))
          case DObject(value)  =>
            val newMap = value.map { case (k, v) => k -> toJson(v) }
            Json.fromJsonObject(JsonObject.fromMap(newMap))
        }
      }
      Right(toJson(document))
    }
  }

  private lazy val decoders = {
    // Instead of Json.read due to max arity default setting
    import smithy4s.json.Json
    Json.payloadCodecs
      .withJsoniterCodecCompiler(Json.jsoniter.withMaxArity(99999))
      .decoders
  }

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

  // JsonCodec
  /** circe `Json` -> smithy `Document`, the reverse of [[decoder]]. Lets a WS
    * JSON payload be decoded into a smithy4s type via its schema
    * (`Document.Decoder.fromSchema`), so the WS API can return the same typed
    * shapes the REST leg did — without a second HTTP client.
    */
  // def fromJson(json: Json): Document =
  //  json.fold(
  //    DNull,
  //    b => DBoolean(b),
  //    n => DNumber(n.toBigDecimal.getOrElse(BigDecimal(n.toDouble))),
  //    s => DString(s),
  //    arr => DArray(arr.map(fromJson)),
  //    obj => DObject(obj.toMap.map { case (k, v) => k -> fromJson(v) })
  //  )

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
