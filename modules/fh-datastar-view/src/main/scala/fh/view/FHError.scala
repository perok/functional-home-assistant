package fh.view

import cats.effect.IO
import org.http4s.{HttpApp, Response, Status}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** A terminal condition, raised rather than threaded back as an `Either` every
  * caller re-inspects. The raise site picks the status, so [[handle]] maps any
  * one without knowing what raised it.
  */
case class FHError(status: Int, message: String)
    extends RuntimeException(message)

object FHError {

  private val log = Slf4jLogger.getLogger[IO]

  def badCondition(message: String): FHError = FHError(400, message)

  def notFound(message: String): FHError = FHError(404, message)

  /** Retryable: HA unreachable, a feature disabled. */
  def unavailable(message: String): FHError = FHError(503, message)

  /** An fh invariant broke. */
  def internal(message: String): FHError = FHError(500, message)

  def response(e: FHError): Response[IO] =
    Response[IO](Status.fromInt(e.status).getOrElse(Status.InternalServerError))
      .withEntity(e.message)

  /** A 5xx is ours and the client sees only a status, so it is logged here or
    * nowhere.
    */
  def logged(e: FHError): IO[Response[IO]] =
    IO.whenA(e.status >= 500)(
      log.error(s"${e.status}: ${e.message}")
    ).as(response(e))

  /** Anything else is re-raised, so an unnamed bug stays a 500. */
  def handle(app: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO] { req =>
      app.run(req).recoverWith { case e: FHError => logged(e) }
    }
}
