package fh.view

import cats.effect.IO
import org.http4s.{HttpApp, Response, Status}

/** A terminal fh error.
  *
  * The project's rule (the user's Phase 6 directive): conditions that are
  * genuinely terminal — a malformed request, a resource that does not exist, a
  * misconfigured workspace — are raised as this exception and we fail
  * immediately, rather than threading the failure back through return types
  * (`Either`, `Option`, sentinel strings) that every caller must re-inspect.
  *
  * The HTTP status is part of the value: the helpers ([[FHError.notFound]],
  * [[FHError.badCondition]], …) pick the code that makes sense for the
  * condition at the raise site, so the boundary ([[FHError.handle]]) can turn
  * ANY `FHError` into `status + message` without knowing what raised it.
  *
  * NOT `final`: a caller may extend it to carry extra structure while staying
  * mappable by the same handler.
  */
case class FHError(status: Int, message: String)
    extends RuntimeException(message)

object FHError {

  /** 400 — the caller asked for something incoherent (a bad body, a dashboard
    * that fails validation): the request itself is at fault.
    */
  def badCondition(message: String): FHError = FHError(400, message)

  /** 404 — the named thing does not exist here (an unknown slug, an artifact
    * this home does not serve).
    */
  def notFound(message: String): FHError = FHError(404, message)

  /** 503 — a dependency the request needs is not available right now (HA
    * unreachable, a feature disabled): retryable, not the caller's fault.
    */
  def unavailable(message: String): FHError = FHError(503, message)

  /** 500 — an fh invariant broke (a misconfigured workspace, an impossible
    * internal state): terminal and not the caller's fault.
    */
  def internal(message: String): FHError = FHError(500, message)

  /** The `status + message` response for one [[FHError]] — the single mapping,
    * used by both the app-level [[handle]] and any route that recovers locally.
    */
  def response(e: FHError): Response[IO] =
    Response[IO](Status.fromInt(e.status).getOrElse(Status.InternalServerError))
      .withEntity(e.message)

  /** The boundary: recover any [[FHError]] raised while serving a request into
    * its `status + message`; re-raise anything else so Ember still reports it
    * as a 500 (an unnamed bug must not masquerade as a handled condition).
    */
  def handle(app: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO] { req =>
      app.run(req).recoverWith { case e: FHError => IO.pure(response(e)) }
    }
}
