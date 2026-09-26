package fh.view.history

import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import cats.effect.IO

import java.time.Instant

/** The two `HomeAssistantApi` reads a series needs, so a test stubs two methods
  * rather than twenty.
  */
trait SeriesSource {

  def raw(
      start: Instant,
      end: Instant,
      entityId: String
  ): IO[List[HistoryPoint]]

  def statistics(
      start: Instant,
      end: Instant,
      entityId: String,
      period: StatisticsPeriod
  ): IO[List[StatisticPoint]]
}

object SeriesSource {

  /** One entity per call because that is the cache unit; batching saves a round
    * trip, which is not where the cost is (910 KB for ten sensors).
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
