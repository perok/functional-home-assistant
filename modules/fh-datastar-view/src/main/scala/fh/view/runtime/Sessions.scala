package fh.view.runtime

import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.Stream
import cats.syntax.all.*
import cats.effect.std.Queue
import fh.view.model.NodeId
import fs2.concurrent.SignallingRef
import org.http4s.ServerSentEvent

/** Who owns a session right now, as ONE value rather than parallel
  * "adopted?"/"connected?"/"dropped?" flags — they are the same fact, and a
  * session's whole life is a walk through these in order:
  *
  * {{{
  * Fresh -> Held(1) -> Lingering(1) -> Held(2) -> ... -> Reaped
  * }}}
  *
  * Every transition is guarded by the tenure it expects to replace
  * ([[Session.adopt]], [[Session.release]], [[Session.relinquish]]), which is
  * what makes the reaper unable to race a stream: both ask the same ref, so
  * whoever loses sees the other's answer instead of acting on a stale read.
  * Separate flags would make "reaped but held" representable and turn that race
  * back into a window.
  *
  *   - `Fresh` — a document made it; nothing has connected. A page abandoned
  *     before `data-init` fires never leaves this state, which is what the
  *     adoption reaper looks for.
  *   - `Held(epoch)` — one stream owns it. The epoch is what a stream compares
  *     against to notice it has been DISPLACED by a later one.
  *   - `Lingering(epoch)` — its stream ended and nobody has taken it since. The
  *     session is still live and still recorded for: this is the window in
  *     which a reconnect costs only what moved.
  *   - `Reaped` — dropped. Terminal, and refused by [[Session.adopt]], so a
  *     stream that lost the race to the reaper mints a new session instead of
  *     adopting a corpse nothing is registered under.
  */
enum Tenure derives CanEqual {
  case Fresh
  case Held(epoch: Int)
  case Lingering(epoch: Int)
  case Reaped
}

/** One dashboard client. Normally created by the DOCUMENT it loaded, and that
  * is the case worth understanding: the page render is the first thing that
  * puts fragments in this client's DOM, so it is the only place that can say
  * what they were. A stream adopts the session its `conn` names ([[adopt]]).
  *
  * Two other paths mint one, both for a `conn` this process does not have — a
  * stream on a bookmarked SSE URL or after a restart ([[Server.adoptOrMint]]),
  * and a surface tap on a page whose session was reaped while it sat idle
  * ([[Server.sessionFor]], ADR 0024). Neither weakens what `holds` means: they
  * start EMPTY, which says "nothing was sent to this DOM that we know of", and
  * the resume that follows re-sends rather than under-sends. The invariant is
  * about what may be WRITTEN there, not about who allocated the record.
  *
  *   - `slug`: which dashboard this connection views — fixed for its lifetime,
  *     because going to another dashboard is an ordinary document load (ADR
  *     0002) and therefore a new connection.
  *   - `open`: the surface ids (popups) this client currently has open. A
  *     surface's nodes are recorded and rendered only while it is in here, so a
  *     closed popup costs nothing.
  *   - `control`: server-pushed patches destined for *this* connection's stream
  *     — popup insert/remove (the entity-change loop can't carry them, as
  *     they're triggered by action POSTs on other fibers).
  *   - `holds`: what THIS client's DOM has, per node — the digest of the bytes
  *     it was last sent, seeded by the document's own render. The answer to "is
  *     this worth sending?", asked per client rather than on everyone's behalf.
  *   - `position`: how far this session has been SERVED, as a store version.
  *     The cursor its pull loop resumes from - and deliberately not the same
  *     thing as the cursor the client is holding, see below.
  *   - `told`: the newest version this client was ever ANNOUNCED — the last
  *     [[Server.cursorSignals]]/[[Server.versionSignal]] actually put on its
  *     wire, seeded by the document, which renders one into the page. The most
  *     this client could possibly echo back, and therefore the yardstick a
  *     reconnect's cursor is measured against ([[Server.openingPatches]]).
  *     Written wherever such a signal is emitted, and nowhere else: a site that
  *     emits one without recording it here leaves this reading LOW, which costs
  *     the protection rather than breaking it (a client is trusted that we
  *     could have checked).
  *   - `haDown`: the HA liveness this client was last TOLD, `None` when nothing
  *     has told it. Seeded by the document, which renders the banner's initial
  *     value into the page, and updated wherever the server emits a new one —
  *     so the stream can skip saying what the page already says. Same shape and
  *     the same rule as `holds`, for the one piece of client state that is not
  *     a node.
  *   - `tenure`: who owns it and whether it is still alive — see [[Tenure]].
  *
  * What goes wrong with a per-client record is that it drifts from what the
  * client actually has, so there is exactly one rule: it is written where bytes
  * are SENT to this client — the document's own render here, and
  * [[Addressed.establishes]] where a connection keeps a patch. Never from what
  * some other client was told, and never from what a shared structure believes.
  *
  * '''`position` may run ahead of the cursor the client is holding, on
  * purpose.''' A pull that owes this client nothing still advances it, and the
  * signal that would tell the client need not go out on that frame - it can
  * ride the keepalive instead. Two things make that safe, and they are worth
  * being able to point at rather than re-deriving:
  *
  *   - '''The client's cursor is the authority; `position` is only the server's
  *     record of the last truth it told.''' While the stream lives, pulls
  *     resume from `position`; the client's own value matters only at
  *     RECONNECT, where it is presented and re-derived against (ADR 0011).
  *   - '''What a stale-low client cursor costs is bounded to a refill.'''
  *     `position` feeds [[Sessions.floor]], so running ahead prunes slightly
  *     more than the client's real state warrants - but [[FragmentLog.pruned]]
  *     only drops MUTATIONS and raises per-container horizons, leaving
  *     `fragments` untouched, and `completeFrom` (the full-repaint trigger)
  *     moves only in `skipped`, which needs ZERO sessions on the slug and so
  *     cannot fire while this one exists. Worst case: one container refilled on
  *     a reconnect landing inside the lag window. Never staleness.
  *
  * Note what is NOT claimed: that the two positions are equivalent. A pull owes
  * nothing partly because of `holds` suppression - a node whose bytes did not
  * change for THIS DOM - and `holds` dies with the session, so the same cursor
  * would produce patches after a reap. This is a bound on the damage, not a
  * proof of equality, and the bound is what makes withholding the signal a free
  * choice rather than a risk.
  */
case class Session(
    slug: String,
    open: Ref[IO, Set[String]],
    control: Queue[IO, ServerSentEvent],
    holds: Ref[IO, Map[NodeId, Held]],
    haDown: Ref[IO, Option[Boolean]],
    position: Ref[IO, Long],
    told: Ref[IO, Long],
    tenure: SignallingRef[IO, Tenure]
) {

  /** Take this session's stream. Returns the epoch the caller now owns, or
    * `None` if it has already been [[Tenure.Reaped]] — every earlier epoch is
    * superseded and must stop, or two streams would write one `holds` map and
    * each would suppress what the other was owed.
    */
  def adopt: IO[Option[Int]] =
    tenure.modify {
      case Tenure.Reaped       => (Tenure.Reaped, None)
      case Tenure.Fresh        => (Tenure.Held(1), Some(1))
      case Tenure.Held(e)      => (Tenure.Held(e + 1), Some(e + 1))
      case Tenure.Lingering(e) => (Tenure.Held(e + 1), Some(e + 1))
    }

  /** This stream is done; start the linger. A no-op unless `epoch` is still the
    * one holding it — a DISPLACED stream releases after its successor already
    * took over, and must not put a live session out to pasture on its way out.
    *
    * Returns the tenure the reaper should then expect to find, so the caller
    * cannot schedule a reap against a state this never reached.
    */
  def release(epoch: Int): IO[Option[Tenure]] =
    tenure.modify {
      case Tenure.Held(e) if e == epoch =>
        (Tenure.Lingering(e), Some(Tenure.Lingering(e)))
      case t => (t, None)
    }

  /** Drop it for good, but only from exactly `expected`. `false` means someone
    * reconnected (or a later stream took it) while the reaper slept, and the
    * caller must leave the registry alone.
    */
  /** Retire this session because a LATER document in the same tab has
    * superseded it — unless a stream is still holding it.
    *
    * The guard is what makes the tab-storage handoff safe. `sessionStorage` is
    * copied into a duplicated tab (and, in Chrome, into one opened via
    * `target=_blank`), so the id a document names as its predecessor may belong
    * to a tab that is very much alive. Retiring only a `Fresh` or `Lingering`
    * session means that case is a no-op instead of pulling the rug from under a
    * live viewer.
    */
  def supersede: IO[Boolean] =
    tenure.modify {
      case held: Tenure.Held => (held, false)
      case Tenure.Reaped     => (Tenure.Reaped, false)
      case _                 => (Tenure.Reaped, true)
    }

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
      q <- Queue.unbounded[IO, ServerSentEvent]
      h <- Ref[IO].of(Map.empty[NodeId, Held])
      // `None`, not `Some(false)`: a session minted by a stream (a bookmarked
      // SSE URL, a restart) has told this client nothing, and must not assume
      // the banner it never rendered.
      d <- Ref[IO].of(Option.empty[Boolean])
      p <- Ref[IO].of(0L)
      // -1, not 0: a session minted by a STREAM has announced nothing, and 0 is
      // a real version a client could be holding. Its `holds` are empty anyway,
      // so a resume against it re-sends rather than under-sends.
      s <- Ref[IO].of(-1L)
      t <- SignallingRef[IO].of(Tenure.Fresh: Tenure)
    } yield Session(slug, o, q, h, d, p, s, t)
}

/** Registry of live connections keyed by their minted `conn` id, so an action
  * POST (a separate request, carrying `conn` among its Datastar signals) can
  * find and drive the SSE stream it belongs to.
  */
final class Sessions(ref: SignallingRef[IO, Map[String, Session]]) {

  /** How many sessions have a live STREAM — the readiness seam a test needs
    * before it moves an entity.
    *
    * [[Tenure.Held]] exactly: a document registers its session before any
    * stream exists, so counting registrations is met while the browser has not
    * connected yet, and a change in that window is observable only as a first
    * paint. A LINGERING session does not count either, for the same reason read
    * the other way round — it is still registered, still recorded for, and has
    * nobody to send to. A `SignallingRef` rather than a `Ref` for exactly this;
    * nothing in the server watches it.
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

  /** Only if `conn` still names THIS session. A reaper wakes into a world where
    * its `conn` may have been re-used by a later document, and dropping that
    * one would unroute a live client.
    */
  def deregisterIf(conn: String, session: Session): IO[Unit] =
    ref.update(_.filterNot { case (k, v) => k == conn && (v eq session) })

  def get(conn: String): IO[Option[Session]] = ref.get.map(_.get(conn))

  /** Every registered connection, by `conn` — for [[Server.forgetSessions]],
    * which needs the keys as well as the sessions.
    */
  def all: IO[Map[String, Session]] = ref.get

  /** Every live session viewing `slug` — their open sets are the recording
    * pass's render gate.
    */
  def forSlug(slug: String): IO[List[Session]] =
    ref.get.map(_.values.filter(_.slug == slug).toList)

  /** How far behind `slug`'s slowest session is, or `None` when nothing is
    * watching it. The changelog below this describes changes no session can
    * still ask for ([[FragmentLog.pruned]]).
    *
    * A session that has just been registered but whose document has not
    * finished rendering reads `0` and pins the floor there for the length of
    * one page render. That is the safe direction — it keeps history nobody
    * needs, where the other way round would drop history someone does.
    */
  def floor(slug: String): IO[Option[Long]] =
    forSlug(slug).flatMap(_.traverse(_.position.get)).map(_.minOption)

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
