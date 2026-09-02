package fh.api

import cats.ApplicativeThrow
import org.http4s.Uri
import cats.syntax.all.*

object utils {
  def haUriHttpToWS[F[_]: ApplicativeThrow](httpUri: Uri): F[Uri] = {
    // `Uri.Scheme` is open, so an http/https-only match never was exhaustive:
    // any other scheme threw a `MatchError` out of the map, which the caller's
    // `F` could not tell from a bug. The failure is now the same raise as a
    // missing scheme.
    val websocketScheme: Either[Throwable, Uri.Scheme] =
      httpUri.scheme
        .toRight(new Exception("No schema on url"))
        .flatMap {
          case Uri.Scheme.http  => Right(Uri.Scheme.unsafeFromString("ws"))
          case Uri.Scheme.https => Right(Uri.Scheme.unsafeFromString("wss"))
          case other            =>
            Left(new Exception(s"Not an http(s) url: ${other.value}"))
        }

    websocketScheme
      .liftTo[F]
      .map(scheme => httpUri.copy(scheme = Some(scheme)) / "api" / "websocket")
  }
}
