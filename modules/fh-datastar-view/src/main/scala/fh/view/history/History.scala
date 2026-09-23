package fh.view.history

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.query.{Answer, QueryIdentity, QueryRequest}

import java.time.{Duration as JDuration, Instant}

/** Parsing a history query — pure, so `Dashboard.validate` can reject a bad one
  * with nothing wired.
  */
object HistoryQuery {

  val Name: String = "history"

  /** `entity` and `window` only: size and unit are how the answer is drawn (the
    * stage), so two sizes of one chart are one request and one fetch.
    */
  def parse(params: Map[String, String]): Either[String, QueryRequest] =
    for {
      entityId <- params
        .get("entity")
        .toRight(s"query '$Name' needs an 'entity' parameter")
      name <- params
        .get("window")
        .toRight(s"query '$Name' needs a 'window' parameter")
      window <- Window
        .byName(name)
        .toRight(
          s"query '$Name' has unknown window '$name' — one of " +
            Window.values.map(_.name).mkString(", ")
        )
    } yield QueryRequest.History(entityId, window)
}

/** `bucket` is the window's floor of "now", so an entry expires by its bucket
  * rolling rather than by a timer. Identity is in the key because HA scopes
  * recorder data by user.
  */
final case class SeriesKey(
    identity: QueryIdentity,
    entityId: String,
    window: Window,
    bucket: Instant
) {
  def expiresAt: Instant = bucket.plusSeconds(window.bucket.toSeconds)
}

/** The `history` provider: one entity's readings over one window, fetched once
  * per bucket and shared by everyone who asks, answered as DATA.
  *
  * `retention` is how far back HA's recorder holds raw rows, learned from
  * answers because `purge_keep_days` is reported nowhere. It is the maximum row
  * age seen across ALL entities — a sensor created two days ago also answers
  * two days — and only grows.
  */
final class History private (
    source: SeriesSource,
    cache: BucketCache[SeriesKey, Series],
    retention: Ref[IO, JDuration],
    target: Int
) {

  /** The bucket is the version, and it works as one because the past is
    * immutable: two renders inside it read the same points.
    */
  def answer(
      identity: QueryIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Answer] =
    series(identity, entityId, window, asOf)
      .map(s => Answer(window.bucketOf(asOf).getEpochSecond, Series.toJson(s)))

  def series(
      identity: QueryIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Series] =
    cache.get(
      SeriesKey(identity, entityId, window, window.bucketOf(asOf)),
      asOf
    )(fetch(entityId, window, asOf))

  private[history] def keys: IO[Set[SeriesKey]] = cache.keys

  /** History alone once retention is known to cover the window; otherwise both
    * in parallel, keeping whichever covers more time.
    */
  private def fetch(entityId: String, window: Window, asOf: Instant) = {
    val start = window.startAt(asOf)
    val fromHistory =
      source
        .raw(start, asOf, entityId)
        .map(Series.fromHistory)
        .flatTap(s =>
          s.oldest.traverse_ { at =>
            val age = JDuration.between(at, asOf)
            retention.update(r => if (age.compareTo(r) > 0) age else r)
          }
        )
    val fromStatistics =
      source
        .statistics(start, asOf, entityId, window.statistics)
        .map(Series.fromStatistics)
    retention.get
      .map(_.getSeconds >= window.span.toSeconds)
      .ifM(fromHistory, (fromHistory, fromStatistics).parMapN(History.widest))
      .map(s => s.copy(points = Downsample.lttb(s.points, target)))
  }
}

object History {

  def create(
      source: SeriesSource,
      target: Int = Downsample.DefaultTarget
  ): IO[History] =
    (
      BucketCache.create[SeriesKey, Series](_.expiresAt),
      Ref[IO].of(JDuration.ZERO)
    ).mapN(new History(source, _, _, target))

  // By time covered, not point count: statistics is coarser by design, so
  // counting points would always pick history. History wins a tie.
  private def widest(history: Series, statistics: Series): Series =
    (history.span, statistics.span) match {
      case (Some(h), Some(s)) => if (s.compareTo(h) > 0) statistics else history
      case (Some(_), None)    => history
      case (None, Some(_))    => statistics
      case (None, None)       => history
    }
}
