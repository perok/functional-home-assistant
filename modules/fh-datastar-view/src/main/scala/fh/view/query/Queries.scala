package fh.view.query

import cats.effect.IO
import fh.view.history.{ChartStage, HistoryProvider, HistoryQuery, Window}
import fh.view.model.{SlotQuery, Transform}

import io.circe.Json

import java.time.Instant

/** Who a query reads as — part of every provider cache key, because HA scopes
  * recorder data by user and a per-user provider must not share by omission.
  */
opaque type QueryIdentity = String

object QueryIdentity {

  val Instance: QueryIdentity = "instance"

  def user(id: String): QueryIdentity = s"user:$id"
}

/** What a provider answers with: data (never markup — that is the stage's), and
  * the version it became current at.
  *
  * The version is the render cache's key and the provider's caching policy: a
  * stable one (history's bucket) is shared, one that moves every call is never
  * cached. It must be NON-DECREASING (`RenderInputs.isAtLeast` compares with
  * `>=`), so a content hash will not do.
  */
final case class Answer(version: Long, data: Json) derives CanEqual

/** What reaches the walk: bytes, and the provider's version they were drawn
  * from. Together, so a version without bytes cannot enter the cache.
  */
final case class Fragment(version: Long, html: String) derives CanEqual

/** A query with its parameters parsed. A closed sum, not a registry: adding a
  * provider is a case here, an arm in [[Queries.parse]] and [[QueryResolver]],
  * and a typed helper in its component's Pkl module.
  */
enum QueryRequest derives CanEqual {
  case History(entityId: String, window: Window)
}

object Queries {

  /** Pure, so `Dashboard.validate` makes a bad query a build error with nothing
    * wired.
    */
  def parse(query: SlotQuery): Either[String, QueryRequest] =
    query.provider match {
      case HistoryQuery.Name => HistoryQuery.parse(query.params)
      case other             =>
        Left(
          s"unknown query provider '$other' — one of ${HistoryQuery.Name}"
        )
    }
}

/** Answers parsed queries and runs parsed stages. Two methods because they
  * dedupe at different levels: one fetch per query, one drawing per (query,
  * stage).
  */
final class QueryResolver(history: HistoryProvider, chart: ChartStage) {

  def answer(
      identity: QueryIdentity,
      request: QueryRequest,
      asOf: Instant
  ): IO[Answer] = request match {
    case QueryRequest.History(entityId, window) =>
      history.answer(identity, entityId, window, asOf)
  }

  def stage(
      identity: QueryIdentity,
      query: QueryRequest,
      stage: Transform.Stage,
      answered: Answer
  ): IO[Fragment] =
    stage match {
      case Transform.Stage.Passthrough =>
        IO.pure(Fragment(answered.version, answered.data.noSpaces))
      case Transform.Stage.Chart(style) =>
        chart
          .draw(
            (identity, query),
            style,
            answered.version,
            answered.data
          )
          .map(Fragment(answered.version, _))
    }
}
