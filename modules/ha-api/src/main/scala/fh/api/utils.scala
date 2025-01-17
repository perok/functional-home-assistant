package fh.api

import cats.ApplicativeThrow
import org.http4s.Uri
import cats.syntax.all.*

object utils {
  def haUriHttpToWS[F[_]: ApplicativeThrow](httpUri: Uri): F[Uri] = {
    httpUri.scheme
      .liftTo[F](new Exception("No schema on url"))
      .map {
        case Uri.Scheme.http  => Uri.Scheme.unsafeFromString("ws")
        case Uri.Scheme.https => Uri.Scheme.unsafeFromString("wss")
      }
      .map(websocketSchema =>
        httpUri.copy(scheme = Some(websocketSchema)) / "api" / "websocket"
      )
  }
}
