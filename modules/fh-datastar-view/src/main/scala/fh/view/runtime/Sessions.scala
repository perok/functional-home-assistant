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
  *   - `lastRendered`: this connection's private last-pushed-HTML diff cache
  *     for the fragments that are rendered per session (open surfaces,
  *     bake-group owners) — content that differs per client. Shared main-page
  *     fragments are diffed once per slug instead (`Server`'s shared patch
  *     pass), never here. A [[FragmentLog]] for one diff contract with that
  *     pass, but its versions are never resumed from: this log dies with the
  *     connection, so a reconnecting client has these painted fresh instead —
  *     see `Server.openingPatches` (ADR 0011). Its resume half (`id`,
  *     `mutations`, `horizon`) is therefore inert here: no cursor is ever
  *     compared against a session log. Sharing the type is what keeps ONE diff
  *     contract; the cost is that bookkeeping nothing reads.
  */
case class Session(
    slug: String,
    open: Ref[IO, Set[String]],
    control: Queue[IO, ServerSentEvent],
    lastRendered: Ref[IO, FragmentLog]
)

object Session {
  def create(slug: String): IO[Session] =
    for {
      o <- Ref[IO].of(Set.empty[String])
      q <- Queue.unbounded[IO, ServerSentEvent]
      id <- IO.randomUUID.map(_.toString)
      lr <- Ref[IO].of(FragmentLog(id))
    } yield Session(slug, o, q, lr)
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

  /** Every surface ANY client on `slug` currently has open — the shared pass's
    * render gate. Deliberately a union, and deliberately not a correctness
    * input: it decides what is worth RENDERING once for the slug, while who may
    * SEE each patch is decided per patch by its `Addressed` tag. Erring wide
    * therefore costs bytes on the server and nothing on the wire; erring narrow
    * would drop an update a client needed, so the union is the only safe
    * direction.
    */
  def openIn(slug: String): IO[Set[String]] =
    ref.get.flatMap(
      _.values
        .filter(_.slug == slug)
        .toList
        .foldMapA(_.open.get)
    )
}

object Sessions {
  def create: IO[Sessions] =
    Ref[IO].of(Map.empty[String, Session]).map(new Sessions(_))
}
