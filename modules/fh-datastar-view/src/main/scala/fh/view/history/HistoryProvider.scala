package fh.view.history

import cats.effect.IO
import fh.view.query.{Answer, QueryIdentity, QueryRequest}

import java.time.Instant

/** Parsing a history query. PURE and instance-free, deliberately: it needs no
  * store, no HA connection and no JavaScript engine, so `Dashboard.validate`
  * can reject a bad chart everywhere a dashboard is built rather than only
  * where a provider happened to be wired in. What needs those is
  * [[HistoryProvider]], and it is only reached at render time.
  */
object HistoryQuery {

  val Name: String = "history"

  /** `entity` and `window`, and nothing else.
    *
    * The size and the unit used to live here too, and moving them out is the
    * point of the split: they say how the answer is DRAWN, which is the
    * transform's business, not the question's. This file's own doc used to
    * describe that division — "`entity` and `window` are the question; the rest
    * is how it is drawn" — without the types being able to act on it.
    *
    * What it buys is sharing by construction: two cards charting one sensor
    * over one window at different sizes are now the same REQUEST, so they cost
    * one fetch whether or not anything remembers to key a cache carefully.
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

/** Answers a history query: one entity's readings over one window, as DATA.
  *
  * One cache, not two. It held a second — drawings, keyed by series and style —
  * until drawing moved to the transform stage, and that half did not move so
  * much as stop being the provider's business at all. What is left is the one
  * thing a provider is for: fetching, deduplicated by the bucket, shared by
  * every viewer inside it.
  */
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
