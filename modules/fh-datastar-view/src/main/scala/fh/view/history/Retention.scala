package fh.view.history

import cats.effect.{IO, Ref}

import java.time.{Duration as JDuration, Instant}

/** How far back HA's recorder still holds raw rows — LEARNED, not configured.
  *
  * `purge_keep_days` is per-installation, nothing in the dump reports it, and
  * asking for more than it keeps is not an error: HA answers with whatever
  * survives. So the only way to know is to look at what came back, and the one
  * sound reading of that is a LOWER BOUND — if a row that old exists, the
  * recorder keeps at least that much.
  *
  * A lower bound rather than "the oldest row for this entity" because those are
  * not the same fact and confusing them is the trap here: a sensor created two
  * days ago also returns two days for a thirty-day window, and concluding "the
  * recorder keeps two days" from it would send every later chart to statistics
  * for no reason. Taking the MAXIMUM across every entity ever asked for cannot
  * be fooled that way — one old row anywhere proves the retention for all of
  * them.
  *
  * It only grows, within a process. Lowering `purge_keep_days` on a running
  * instance therefore leaves this optimistic until a restart; the cost is a
  * history call that comes back short, which [[SeriesProvider]] already handles
  * because it is the same case as never having learned anything.
  *
  * Note that HA's recorder and this project's own recorder (the per-dashboard
  * fiber in `docs/terminology.md`) are different things. This one is HA's.
  */
final class Retention private (ref: Ref[IO, JDuration]) {

  /** The greatest age a row has actually been seen at. */
  def observed: IO[JDuration] = ref.get

  /** Whether raw history is known to reach all the way back across `window`. A
    * `false` means "not proven", not "proven absent" — the caller asks both
    * sources and keeps the better answer.
    */
  def covers(window: Window): IO[Boolean] =
    ref.get.map(_.getSeconds >= window.span.toSeconds)

  /** Record what a history answer proved. `oldest` is the oldest row it
    * carried; an answer with no rows proves nothing and is ignored.
    */
  def record(oldest: Option[Instant], asOf: Instant): IO[Unit] =
    oldest.fold(IO.unit) { at =>
      val age = JDuration.between(at, asOf)
      ref.update(current => if (age.compareTo(current) > 0) age else current)
    }
}

object Retention {
  def create: IO[Retention] =
    Ref[IO].of(JDuration.ZERO).map(new Retention(_))
}
