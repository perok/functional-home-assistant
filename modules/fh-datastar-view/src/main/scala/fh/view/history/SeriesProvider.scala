package fh.view.history

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.Request

import java.time.Instant

/** Who a series is read as — part of the cache key, because HA scopes recorder
  * data by user and a per-user provider must not share entries by omission.
  */
opaque type SeriesIdentity = String

object SeriesIdentity {

  val Instance: SeriesIdentity = "instance"

  def user(id: String): SeriesIdentity = s"user:$id"
}

/** The read counterpart of [[fh.view.runtime.ServiceCalls]]: HA attributes a
  * read to the connection's owner, so reading as the viewer is per request.
  * Only the instance identity exists today.
  */
trait SeriesProvider {

  def identify(req: Request[IO]): IO[SeriesIdentity]

  def series(
      identity: SeriesIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Series]
}

object SeriesProvider {

  /** History alone when [[Retention]] proves it covers the window; otherwise
    * both in parallel, keeping whichever covers more time. The history half is
    * also what teaches [[Retention]].
    */
  def asInstance(
      source: SeriesSource,
      retention: Retention,
      target: Int = Downsample.DefaultTarget
  ): SeriesProvider = new SeriesProvider {

    def identify(req: Request[IO]): IO[SeriesIdentity] =
      IO.pure(SeriesIdentity.Instance)

    def series(
        identity: SeriesIdentity,
        entityId: String,
        window: Window,
        asOf: Instant
    ): IO[Series] = {
      val start = window.startAt(asOf)

      val fromHistory =
        source
          .raw(start, asOf, entityId)
          .map(Series.fromHistory)
          .flatTap(s => retention.record(s.oldest, asOf))

      val fromStatistics =
        source
          .statistics(start, asOf, entityId, window.statistics)
          .map(Series.fromStatistics)

      retention
        .covers(window)
        .ifM(
          fromHistory,
          (fromHistory, fromStatistics).parMapN(widest)
        )
        .map(s => s.copy(points = Downsample.lttb(s.points, target)))
    }
  }

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
