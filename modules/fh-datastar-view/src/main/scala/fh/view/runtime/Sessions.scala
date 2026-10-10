package fh.view.runtime

import cats.effect.{Deferred, IO}
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

/** What one client's session knows. '''The one rule for a per-client record''':
  * written only where bytes are sent to this client, never from what another
  * client or a shared structure believes.
  *
  *   - `open`: selected tab panels and popup; only these are recorded and
  *     rendered, so a closed popup costs nothing.
  *   - `vars`: the viewer's node-variable choices, here because a pull has no
  *     request to read them off (issue #209).
  *   - `holds`: per node, what this DOM was last sent.
  *   - `position`: how far this session has been served; its pull resumes from
  *     here.
  *   - `told`: the newest cursor put on its wire, the most it could echo back
  *     ([[Server.openingPatches]]). A site that emits one without recording it
  *     leaves this low, which loses the check rather than breaking anything.
  *
  * '''`position` may run ahead of the client's cursor''' (the signal can ride
  * the keepalive). Safe because the client's cursor is the authority at
  * reconnect (ADR 0011), and the extra pruning [[Sessions.floor]] allows costs
  * at most one container refill: [[FragmentLog.pruned]] leaves `fragments`
  * alone, and `skipped` needs zero sessions.
  */
final case class SessionState(
    open: Set[String],
    vars: Map[VarKey, String],
    holds: Map[NodeId, Held],
    position: Long,
    told: Long
)

object SessionState {
  // -1: 0 is a real version a client could hold.
  val initial: SessionState =
    SessionState(Set.empty, Map.empty, Map.empty, 0L, -1L)
}

/** A stream's mailbox: the owner appends a step's frames, the stream takes
  * whatever has collected in one go.
  */
final class Outlet private (pending: SignallingRef[IO, Vector[SseFrame]]) {

  def frames: Stream[IO, SseFrame] =
    pending.discrete
      .filter(_.nonEmpty)
      .evalMap(_ => pending.getAndSet(Vector.empty))
      .flatMap(Stream.emits)

  /** Until the stream has taken everything put here. A pull waits for it, so
    * versions landing while a slow client reads collapse into one pull.
    */
  def drained: IO[Unit] = pending.waitUntil(_.isEmpty)

  private[runtime] def put(frames: Vector[SseFrame]): IO[Unit] =
    IO.whenA(frames.nonEmpty)(pending.update(_ ++ frames))

  private[runtime] def takeAll: IO[Vector[SseFrame]] =
    pending.getAndSet(Vector.empty)
}

object Outlet {
  def create: IO[Outlet] =
    SignallingRef[IO].of(Vector.empty[SseFrame]).map(new Outlet(_))
}

/** One dashboard client, normally created by the document it loaded: the page
  * render is the only place that knows what it put in the DOM. A session minted
  * for an unknown `conn` ([[Server.adoptOrMint]], [[Server.sessionFor]]) starts
  * with empty `holds`, so its resume re-sends rather than under-sends.
  *
  * '''One owner.''' Its [[SessionState]] is a value only the owner fiber moves,
  * one [[Session.run]] at a time, and a step's frames reach the client in the
  * order the steps ran: a pull, a selection write, the opening, a repaint and
  * the keepalive all go through it. A step that raises leaves the state as it
  * was and sends nothing. Frames for a client with no stream (a write landing
  * while it reconnects) wait as a backlog and go out first when one attaches.
  *
  * '''What others read''' ([[state]]) is the last state a step left, with one
  * exception that runs ahead: [[reveal]].
  *
  *   - `haDown`: the liveness last told, `None` for nothing. Outside the owner:
  *     a signal frame no `holds` depends on.
  */
final class Session private (
    val slug: String,
    val haDown: Ref[IO, Option[Boolean]],
    val tenure: SignallingRef[IO, Tenure],
    inbox: Queue[IO, Session.Command],
    published: Ref[IO, SessionState]
) {
  import Session.{Command, Step}

  /** Run `step` against the current state once the steps before it are done.
    * Its answer is returned once its frames are queued; its error is raised
    * here, and nothing it built is kept. A step must not `run` on its own
    * session: the owner is busy running it, so that waits forever.
    */
  def run[A](step: SessionState => IO[Step[A]]): IO[A] =
    Deferred[IO, Either[Throwable, A]].flatMap { reply =>
      inbox.offer(Command.Run(step, reply)) *> reply.get.rethrow
    }

  def update(f: SessionState => SessionState): IO[Unit] =
    run(s => IO.pure(Step(f(s), Nil, ())))

  /** The last state a step left (and any [[reveal]] since). For readers that
    * must not wait on the owner — the recorder, pruning; never the base of a
    * change, which is [[run]]'s.
    */
  def state: IO[SessionState] = published.get

  /** Show the recorder `surfaces` before the running step reads the store: a
    * frame recorded in between without them would never reach the panel being
    * filled. Wider than the state is safe (it records a little more), and the
    * step's end, or its failure, publishes the exact set.
    */
  def reveal(surfaces: Set[String]): IO[Unit] =
    published.update(s => s.copy(open = s.open ++ surfaces))

  /** Deliver to `outlet` from now on, the backlog first. */
  def attach(outlet: Outlet): IO[Unit] = inbox.offer(Command.Attach(outlet))

  /** A no-op unless `outlet` is still the one attached, so a displaced stream's
    * end does not detach its successor. What it never took goes back to the
    * backlog.
    */
  def detach(outlet: Outlet): IO[Unit] = inbox.offer(Command.Detach(outlet))

  /** Test seam: what a session with no stream has queued for one. */
  private[runtime] def takeBacklog: IO[List[SseFrame]] =
    Deferred[IO, List[SseFrame]].flatMap(reply =>
      inbox.offer(Command.TakeBacklog(reply)) *> reply.get
    )

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

  private def own(
      current: SessionState,
      backlog: Vector[SseFrame],
      outlet: Option[Outlet]
  ): IO[Unit] =
    inbox.take.flatMap {
      case run: Command.Run[?] =>
        step(current, backlog, outlet, run).flatMap(own(_, _, outlet))
      case Command.Attach(next) =>
        outlet
          .foldMapM(_.takeAll)
          .flatMap(left => next.put(left ++ backlog)) *>
          own(current, Vector.empty, Some(next))
      case Command.Detach(gone) if outlet.exists(_ eq gone) =>
        gone.takeAll.flatMap(left => own(current, left ++ backlog, None))
      case Command.Detach(_)          => own(current, backlog, outlet)
      case Command.TakeBacklog(reply) =>
        reply.complete(backlog.toList) *> own(current, Vector.empty, outlet)
    }

  private def step[A](
      current: SessionState,
      backlog: Vector[SseFrame],
      outlet: Option[Outlet],
      run: Command.Run[A]
  ): IO[(SessionState, Vector[SseFrame])] =
    run.step(current).attempt.flatMap {
      case Right(done) =>
        val frames = done.frames.toVector
        outlet
          .fold(IO.pure(backlog ++ frames))(_.put(frames).as(backlog))
          .flatTap(_ => published.set(done.next))
          .flatTap(_ => run.reply.complete(Right(done.answer)))
          .tupleLeft(done.next)
      case Left(e) =>
        (published.set(current) *> run.reply.complete(Left(e)))
          .as(current -> backlog)
    }
}

object Session {

  /** One step of a session: the state it leaves, the frames that tell its
    * client, in order, and the caller's answer.
    */
  final case class Step[A](
      next: SessionState,
      frames: List[SseFrame],
      answer: A
  )

  private[runtime] enum Command {
    case Run[A](
        step: SessionState => IO[Step[A]],
        reply: Deferred[IO, Either[Throwable, A]]
    )
    case Attach(outlet: Outlet)
    case Detach(outlet: Outlet)
    case TakeBacklog(reply: Deferred[IO, List[SseFrame]])
  }

  /** The owner is started bare, not supervised: parked on an inbox only the
    * session reaches, it is collected with the session. So a reap never has to
    * stop it, and a POST still holding a reaped session is answered rather than
    * left waiting.
    */
  def create(
      slug: String,
      initial: SessionState = SessionState.initial
  ): IO[Session] =
    for {
      published <- Ref[IO].of(initial)
      inbox <- Queue.unbounded[IO, Command]
      // A stream-minted session told nothing; assume no banner state.
      haDown <- Ref[IO].of(Option.empty[Boolean])
      tenure <- SignallingRef[IO].of(Tenure.Fresh: Tenure)
      session = new Session(slug, haDown, tenure, inbox, published)
      _ <- session.own(initial, Vector.empty, None).start
    } yield session
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
    * rendering its document reads 0, which keeps history — the safe direction,
    * and the one a step's lag is in too: a position is published after the
    * frames that reach it are queued.
    */
  def floor(slug: String): IO[Option[Long]] =
    forSlug(slug).flatMap(_.traverse(_.state.map(_.position))).map(_.minOption)

  /** Separately: visibility is one client's chain, and a union would mix one
    * client's tab with another's branch.
    */
  def openSets(slug: String): IO[List[Set[String]]] =
    forSlug(slug).flatMap(_.traverse(_.state.map(_.open)))
}

object Sessions {
  def create: IO[Sessions] =
    SignallingRef[IO].of(Map.empty[String, Session]).map(new Sessions(_))
}
