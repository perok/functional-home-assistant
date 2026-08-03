package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import cats.effect.std.Queue
import org.http4s.ServerSentEvent

/** One connected dashboard client — i.e. one live SSE stream.
  *
  *   - `slug`: which dashboard this connection views — fixed for its lifetime,
  *     because going to another dashboard is an ordinary document load (ADR
  *     0002) and therefore a new connection.
  *   - `open`: the surface ids (popups) this client currently has open. The
  *     change-loop renders + pushes a surface's nodes only while it's in here,
  *     so a closed popup costs nothing.
  *   - `control`: server-pushed patches destined for *this* connection's stream
  *     — popup mount/remove (the entity-change loop can't carry them, as
  *     they're triggered by action POSTs on other fibers).
  */
case class Session(
    slug: String,
    open: Ref[IO, Set[String]],
    control: Queue[IO, ServerSentEvent]
)

object Session {
  def create(slug: String): IO[Session] =
    for {
      o <- Ref[IO].of(Set.empty[String])
      q <- Queue.unbounded[IO, ServerSentEvent]
    } yield Session(slug, o, q)
}

/** Registry of live connections keyed by their minted `conn` id, so an action
  * POST (a separate request, carrying `conn` among its Datastar signals) can
  * find and drive the SSE stream it belongs to.
  */
final class Sessions(ref: Ref[IO, Map[String, Session]]) {
  def register(conn: String, session: Session): IO[Unit] =
    ref.update(_.updated(conn, session))

  def deregister(conn: String): IO[Unit] = ref.update(_ - conn)

  def get(conn: String): IO[Option[Session]] = ref.get.map(_.get(conn))

  /** Each connection's open set SEPARATELY, because visibility is a property of
    * one client's chain of selections: a surface is only really on screen if
    * everything containing it is, and that is answered against the same
    * session's set. Unioning first would mix one client's tab with another's
    * branch and call the result visible.
    */
  def openSets(slug: String): IO[List[Set[String]]] =
    ref.get.flatMap(_.values.filter(_.slug == slug).toList.traverse(_.open.get))
}

object Sessions {
  def create: IO[Sessions] =
    Ref[IO].of(Map.empty[String, Session]).map(new Sessions(_))
}
