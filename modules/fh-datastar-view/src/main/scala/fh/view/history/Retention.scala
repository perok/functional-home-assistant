package fh.view.history

import cats.effect.{IO, Ref}

import java.time.{Duration as JDuration, Instant}

/** How far back HA's recorder holds raw rows, learned from answers because
  * `purge_keep_days` is reported nowhere.
  *
  * The maximum row age seen across ALL entities, not one entity's oldest row: a
  * sensor created two days ago also answers two days, and that says nothing
  * about retention. It only grows; a lowered `purge_keep_days` costs a short
  * history answer, which [[SeriesProvider]] handles anyway.
  */
final class Retention private (ref: Ref[IO, JDuration]) {

  def observed: IO[JDuration] = ref.get

  /** `false` means "not proven", not "proven absent". */
  def covers(window: Window): IO[Boolean] =
    ref.get.map(_.getSeconds >= window.span.toSeconds)

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
