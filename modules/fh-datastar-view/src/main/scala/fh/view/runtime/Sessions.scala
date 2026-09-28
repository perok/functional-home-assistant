package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.Stream
import cats.syntax.all.*
import cats.effect.std.Queue
import fh.view.model.NodeId
import fs2.concurrent.SignallingRef

/** `Fresh -> Held(1) -> Lingering(1) -> Held(2) -> ... -> Reaped`, one value so
  * "reaped but held" is unrepresentable. Every transition is guarded by the
  * tenure it replaces, so the reaper cannot race a stream.
  *
  *   - `Fresh`: a document made it; an abandoned page never leaves it.
  *   - `Held(epoch)`: a stream owns it; the epoch detects displacement.
  *   - `Lingering(epoch)`: its stream ended; a reconnect now costs only what
  *     moved.
  *   - `Reaped`: terminal, and refused by [[Session.adopt]].
  */
enum Tenure derives CanEqual {
  case Fresh
  case Held(epoch: Int)
  case Lingering(epoch: Int)
  case Reaped
}

/** One dashboard client, normally created by the document it loaded: the page
  * render is the only place that knows what it put in the DOM. A session minted
  * for an unknown `conn` ([[Server.adoptOrMint]], [[Server.sessionFor]]) starts
  * with empty `holds`, so its resume re-sends rather than under-sends.
  *
  * '''The one rule for a per-client record''': written only where bytes are
  * sent to this client, never from what another client or a shared structure
  * believes.
  *
  *   - `open`: selected tab panels and popup; only these are recorded and
  *     rendered, so a closed popup costs nothing.
  *   - `control`: frames an action POST produces for this stream.
  *   - `holds`: per node, what this DOM was last sent.
  *   - `position`: how far this session has been served; its pull resumes from
  *     here.
  *   - `told`: the newest cursor put on its wire, the most it could echo back
  *     ([[Server.openingPatches]]). A site that emits one without recording it
  *     leaves this low, which loses the check rather than breaking anything.
  *   - `haDown`: the liveness last told, `None` for nothing.
  *
  * '''`position` may run ahead of the client's cursor''' (the signal can ride
  * the keepalive). Safe because the client's cursor is the authority at
  * reconnect (ADR 0011), and the extra pruning [[Sessions.floor]] allows costs
  * at most one container refill: [[FragmentLog.pruned]] leaves `fragments`
  * alone, and `skipped` needs zero sessions.
  */
case class Session(
    slug: String,
    open: Ref[IO, Set[String]],
    // Here because a pull has no request to read them off (issue #209).
    vars: Ref[IO, Map[(NodeId, String), String]],
    control: Queue[IO, SseFrame],
    holds: Ref[IO, Map[NodeId, Held]],
    haDown: Ref[IO, Option[Boolean]],
    position: Ref[IO, Long],
    told: Ref[IO, Long],
    tenure: SignallingRef[IO, Tenure]
) {

  /** The new epoch, or `None` if reaped. Every earlier epoch must stop: two
    * streams on one `holds` suppress what the other was owed.
    */
  def adopt: IO[Option[Int]] =
    tenure.modify {
      case Tenure.Reaped       => (Tenure.Reaped, None)
      case Tenure.Fresh        => (Tenure.Held(1), Some(1))
      case Tenure.Held(e)      => (Tenure.Held(e + 1), Some(e + 1))
      case Tenure.Lingering(e) => (Tenure.Held(e + 1), Some(e + 1))
    }

  /** A no-op unless `epoch` still holds it (a displaced stream releases after
    * its successor took over). Returns the tenure the reaper should expect.
    */
  def release(epoch: Int): IO[Option[Tenure]] =
    tenure.modify {
      case Tenure.Held(e) if e == epoch =>
        (Tenure.Lingering(e), Some(Tenure.Lingering(e)))
      case t => (t, None)
    }

  /** Not while held: `sessionStorage` is copied into a duplicated tab, so the
    * named predecessor may be a live tab.
    */
  def supersede: IO[Boolean] =
    tenure.modify {
      case held: Tenure.Held => (held, false)
      case Tenure.Reaped     => (Tenure.Reaped, false)
      case _                 => (Tenure.Reaped, true)
    }

  /** `false`: someone took it while the reaper slept; leave the registry alone.
    */
  def relinquish(expected: Tenure): IO[Boolean] =
    tenure.modify {
      case t if t == expected => (Tenure.Reaped, true)
      case t                  => (t, false)
    }
}

object Session {
  def create(slug: String): IO[Session] =
    for {
      o <- Ref[IO].of(Set.empty[String])
      v <- Ref[IO].of(Map.empty[(NodeId, String), String])
      q <- Queue.unbounded[IO, SseFrame]
      h <- Ref[IO].of(Map.empty[NodeId, Held])
      // A stream-minted session told nothing; assume no banner state.
      d <- Ref[IO].of(Option.empty[Boolean])
      p <- Ref[IO].of(0L)
      // -1: 0 is a real version a client could hold.
      s <- Ref[IO].of(-1L)
      t <- SignallingRef[IO].of(Tenure.Fresh: Tenure)
    } yield Session(slug, o, v, q, h, d, p, s, t)
}

/** Live connections by `conn`, so an action POST finds its stream. */
final class Sessions(ref: SignallingRef[IO, Map[String, Session]]) {

  /** Test seam: `Held` sessions only, since a document registers before its
    * stream connects and a lingering one has nobody to send to. The
    * `SignallingRef` is for this alone.
    */
  def liveStreams: Stream[IO, Int] =
    ref.discrete.evalMap(
      _.values.toList
        .traverse(_.tenure.get)
        .map(_.count {
          case Tenure.Held(_) => true
          case _              => false
        })
    )

  def register(conn: String, session: Session): IO[Unit] =
    ref.update(_.updated(conn, session))

  /** By identity: a later document may have reused `conn`. */
  def deregisterIf(conn: String, session: Session): IO[Unit] =
    ref.update(_.filterNot { case (k, v) => k == conn && (v eq session) })

  def get(conn: String): IO[Option[Session]] = ref.get.map(_.get(conn))

  def all: IO[Map[String, Session]] = ref.get

  def forSlug(slug: String): IO[List[Session]] =
    ref.get.map(_.values.filter(_.slug == slug).toList)

  /** The slowest session's position ([[FragmentLog.pruned]]). A session still
    * rendering its document reads 0, which keeps history — the safe direction.
    */
  def floor(slug: String): IO[Option[Long]] =
    forSlug(slug).flatMap(_.traverse(_.position.get)).map(_.minOption)

  /** Separately: visibility is one client's chain, and a union would mix one
    * client's tab with another's branch.
    */
  def openSets(slug: String): IO[List[Set[String]]] =
    forSlug(slug).flatMap(_.traverse(_.open.get))
}

object Sessions {
  def create: IO[Sessions] =
    SignallingRef[IO].of(Map.empty[String, Session]).map(new Sessions(_))
}
