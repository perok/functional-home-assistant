package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.Stream
import cats.syntax.all.*
import cats.effect.std.Queue
import fh.view.model.NodeId
import fs2.concurrent.SignallingRef
import org.http4s.ServerSentEvent

/** One dashboard client. Created by the DOCUMENT it loaded, not by the SSE
  * stream that follows: the page render is the first thing that puts fragments
  * in this client's DOM, so it is the only place that can say what they were. A
  * stream adopts the session its `conn` names ([[adopt]]).
  *
  *   - `slug`: which dashboard this connection views — fixed for its lifetime,
  *     because going to another dashboard is an ordinary document load (ADR
  *     0002) and therefore a new connection.
  *   - `open`: the surface ids (popups) this client currently has open. A
  *     surface's nodes are recorded and rendered only while it is in here, so a
  *     closed popup costs nothing.
  *   - `control`: server-pushed patches destined for *this* connection's stream
  *     — popup mount/remove (the entity-change loop can't carry them, as
  *     they're triggered by action POSTs on other fibers).
  *   - `holds`: what THIS client's DOM has, per node — the digest of the bytes
  *     it was last sent, seeded by the document's own render. The answer to "is
  *     this worth sending?", asked per client rather than on everyone's behalf.
  *   - `position`: how far this session has been served, as a store version.
  *     The cursor its pull loop resumes from.
  *   - `epoch`: which stream owns it — see [[adopt]].
  *
  * What goes wrong with a per-client record is that it drifts from what the
  * client actually has, so there is exactly one rule: it is written where bytes
  * are SENT to this client — the document's own render here, and
  * [[Addressed.establishes]] where a connection keeps a patch. Never from what
  * some other client was told, and never from what a shared structure believes.
  */
case class Session(
    slug: String,
    open: Ref[IO, Set[String]],
    control: Queue[IO, ServerSentEvent],
    holds: Ref[IO, Map[NodeId, Digest]],
    position: Ref[IO, Long],
    epoch: SignallingRef[IO, Int]
) {

  /** Take this session's stream. Returns the epoch the caller now owns — every
    * earlier one is superseded and must stop, or two streams would write one
    * `holds` map and each would suppress what the other was owed.
    *
    * `0` means nobody has ever connected, which is what tells the reaper a page
    * load was abandoned before it opened a stream.
    */
  def adopt: IO[Int] = epoch.updateAndGet(_ + 1)
}

object Session {
  def create(slug: String): IO[Session] =
    for {
      o <- Ref[IO].of(Set.empty[String])
      q <- Queue.unbounded[IO, ServerSentEvent]
      h <- Ref[IO].of(Map.empty[NodeId, Digest])
      p <- Ref[IO].of(0L)
      e <- SignallingRef[IO].of(0)
    } yield Session(slug, o, q, h, p, e)
}

/** Registry of live connections keyed by their minted `conn` id, so an action
  * POST (a separate request, carrying `conn` among its Datastar signals) can
  * find and drive the SSE stream it belongs to.
  */
final class Sessions(ref: SignallingRef[IO, Map[String, Session]]) {

  /** How many sessions have a live STREAM — the readiness seam a test needs
    * before it moves an entity.
    *
    * Adopted, not merely registered: a document registers its session before
    * any stream exists ([[Session.adopt]]), so a bare count is met while the
    * browser has not connected yet, and a change in that window is observable
    * only as a first paint. A `SignallingRef` rather than a `Ref` for exactly
    * this; nothing in the server watches it.
    */
  def liveStreams: Stream[IO, Int] =
    ref.discrete.evalMap(
      _.values.toList.traverse(_.epoch.get).map(_.count(_ > 0))
    )

  def register(conn: String, session: Session): IO[Unit] =
    ref.update(_.updated(conn, session))

  def deregister(conn: String): IO[Unit] = ref.update(_ - conn)

  def get(conn: String): IO[Option[Session]] = ref.get.map(_.get(conn))

  /** Every live session viewing `slug` — their open sets are the recording
    * pass's render gate.
    */
  def forSlug(slug: String): IO[List[Session]] =
    ref.get.map(_.values.filter(_.slug == slug).toList)

  /** Each connection's open set SEPARATELY, because visibility is a property of
    * one client's chain of selections: a surface is only really on screen if
    * everything containing it is, and that is answered against the same
    * session's set. Unioning first would mix one client's tab with another's
    * branch and call the result visible.
    */
  def openSets(slug: String): IO[List[Set[String]]] =
    forSlug(slug).flatMap(_.traverse(_.open.get))
}

object Sessions {
  def create: IO[Sessions] =
    SignallingRef[IO].of(Map.empty[String, Session]).map(new Sessions(_))
}
