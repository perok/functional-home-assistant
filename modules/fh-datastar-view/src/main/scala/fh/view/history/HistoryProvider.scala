package fh.view.history

import cats.effect.IO
import fh.view.query.{Answer, QueryIdentity, QueryRequest}

import java.time.Instant

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

/** Answers a history query: one entity's readings over one window, as DATA. */
final class HistoryProvider private (series: SeriesStore) {

  /** The bucket is the version, and it works as one because the past is
    * immutable: two renders inside it read the same points.
    */
  def answer(
      identity: QueryIdentity,
      entityId: String,
      window: Window,
      asOf: Instant
  ): IO[Answer] =
    series
      .get(identity, entityId, window, asOf)
      .map(s => Answer(window.bucketOf(asOf).getEpochSecond, Series.toJson(s)))
}

object HistoryProvider {

  def create(series: SeriesStore): IO[HistoryProvider] =
    IO.pure(new HistoryProvider(series))
}
