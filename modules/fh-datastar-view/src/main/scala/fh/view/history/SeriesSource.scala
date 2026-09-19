package fh.view.history

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import cats.effect.IO

import java.time.Instant

/** The two reads a series can come from, and nothing else.
  *
  * Narrow on purpose. [[SeriesProvider]] calls two of `HomeAssistantApi`'s
  * twenty methods, and taking the whole trait would declare a dependency on the
  * entity registry, the service catalogue and the live feed that it does not
  * have — visible immediately in a test, which would need a stub for all of
  * them to exercise a downsampler.
  */
trait SeriesSource {

  /** Raw rows, bounded silently by what HA's recorder still holds. */
  def raw(
      start: Instant,
      end: Instant,
      entityId: String
  ): IO[List[HistoryPoint]]

  /** Pre-bucketed statistics. Empty for an entity with no `state_class`, which
    * is an answer rather than a failure.
    */
  def statistics(
      start: Instant,
      end: Instant,
      entityId: String,
      period: StatisticsPeriod
  ): IO[List[StatisticPoint]]
}

object SeriesSource {

  /** One entity per call, because that is the caching unit: ten entities in one
    * request share a round trip but share no cache entry, and the round trip is
    * not where the cost is — ten chatty sensors over 24 hours measured 910 KB
    * against 8 KB for one.
    */
  def fromApi(api: HomeAssistantApi[IO]): SeriesSource = new SeriesSource {

    def raw(
        start: Instant,
        end: Instant,
        entityId: String
    ): IO[List[HistoryPoint]] =
      api
        .historyDuringPeriod(start, end, List(entityId))
        .map(_.getOrElse(entityId, Nil))

    def statistics(
        start: Instant,
        end: Instant,
        entityId: String,
        period: StatisticsPeriod
    ): IO[List[StatisticPoint]] =
      api
        .statisticsDuringPeriod(start, Some(end), List(entityId), period)
        .map(_.getOrElse(entityId, Nil))
  }
}
