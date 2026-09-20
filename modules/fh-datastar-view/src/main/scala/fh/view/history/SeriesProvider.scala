package fh.view.history

import cats.effect.IO
import cats.syntax.all.*
import fh.view.query.QueryIdentity
import org.http4s.Request

import java.time.Instant

/** How a chart gets its data — the read counterpart of
  * [[fh.view.runtime.ServiceCalls]], and a seam for the same reason: HA
  * attributes a read to whoever owns the connection, so reading as the person
  * looking at the chart is a property of the REQUEST and needs a connection of
  * its own.
  *
  * Only the instance identity exists today. The seam is here anyway because
  * retrofitting identity into a cache after the fact is how a permission leak
  * gets written.
  */
trait SeriesProvider {

  /** Who this request reads as. Derived once per request, then carried, so
    * nothing downstream has to hold an http4s `Request` to know.
    */
  def identify(req: Request[IO]): IO[QueryIdentity]

  def series(
      identity: QueryIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Series]
}

object SeriesProvider {

  /** The instance's own identity, over the connection everything else already
    * reads through.
    *
    * Source selection is the interesting part, and it is decided by what HA
    * actually holds rather than by a threshold:
    *
    *   - `retention` says raw history reaches back across the window → ask
    *     history alone. Finer resolution, and one call.
    *   - otherwise → ask BOTH, in parallel, and keep whichever covers more
    *     time. History wins a tie because its resolution is finer.
    *
    * The parallel arm is the discovery mechanism as well as the fallback: the
    * history half is what teaches [[Retention]], so a window that needed two
    * calls once may need one afterwards. Two calls is also the honest cost of
    * not knowing — a sensor created yesterday and an instance that purges daily
    * produce identical short answers, and only asking statistics separates
    * them.
    */
  def asInstance(
      source: SeriesSource,
      retention: Retention,
      target: Int = Downsample.DefaultTarget
  ): SeriesProvider = new SeriesProvider {

    def identify(req: Request[IO]): IO[QueryIdentity] =
      IO.pure(QueryIdentity.Instance)

    def series(
        identity: QueryIdentity,
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

  /** Whichever series says more about the window. Compared on the time it
    * COVERS, not on how many points it has: statistics is coarser by design, so
    * counting points would always pick raw history and defeat the fallback.
    */
  private def widest(history: Series, statistics: Series): Series =
    (history.span, statistics.span) match {
      case (Some(h), Some(s)) => if (s.compareTo(h) > 0) statistics else history
      case (Some(_), None)    => history
      case (None, Some(_))    => statistics
      case (None, None)       => history
    }
}
