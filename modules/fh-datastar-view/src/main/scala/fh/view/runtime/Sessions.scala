package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import org.http4s.ServerSentEvent

/** One connected dashboard client — i.e. one live SSE stream.
  *
  *   - `slug`: which dashboard this connection currently views. **Mutable** so
  *     an in-place navigate can re-point the connection at another dashboard
  *     without a page reload.
  *   - `open`: the surface ids (popups) this client currently has open. The
  *     change-loop renders + pushes a surface's nodes only while it's in here,
  *     so a closed popup costs nothing.
  *   - `control`: server-pushed patches destined for *this* connection's stream
  *     — popup mount/remove and the navigate body swap (the entity-change loop
  *     can't carry them, as they're triggered by action POSTs on other fibers).
  *   - `lastRendered`: this connection's private last-pushed-HTML diff cache.
  *     Per-connection because popup content differs per client (and it
  *     sidesteps the multi-client miss a shared cache would have).
  */
final case class Session(
    slug: Ref[IO, String],
    open: Ref[IO, Set[String]],
    control: Queue[IO, ServerSentEvent],
    lastRendered: Ref[IO, Map[String, String]]
)

object Session {
  def create(slug: String): IO[Session] =
    for {
      s <- Ref[IO].of(slug)
      o <- Ref[IO].of(Set.empty[String])
      q <- Queue.unbounded[IO, ServerSentEvent]
      lr <- Ref[IO].of(Map.empty[String, String])
    } yield Session(s, o, q, lr)
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
}

object Sessions {
  def create: IO[Sessions] =
    Ref[IO].of(Map.empty[String, Session]).map(new Sessions(_))
}
