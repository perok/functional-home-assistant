package fh.view.query

import cats.effect.IO
import fh.view.history.{ChartDraw, ChartStage, History, HistoryQuery, Window}
import fh.view.model.{SlotQuery, Transform}

import io.circe.Json

import scala.concurrent.duration.FiniteDuration

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
  * The version is all the runtime knows of when an answer moves, and it is
  * meant only per question: the same version is the same data, and it never
  * goes back (`RenderInputs.isAtLeast` compares with `>=`, so a content hash
  * will not do). WHEN it moves is the provider's policy — history's is its
  * bucket. It keys the render cache and the resolver's staged values, so one
  * that moves every call is never shared.
  */
final case class Answer(version: Long, data: Json) derives CanEqual

/** What reaches the walk: bytes, and the provider's version they were drawn
  * from. Together, so a version without bytes cannot enter the cache.
  */
final case class Staged(version: Long, value: String) derives CanEqual

object Staged {

  /** Below every real version, so the first answer after a failure moves the
    * render key.
    */
  val FailedVersion: Long = -1L

  /** A read that could not be answered, in its own hole: one chart's error,
    * never the page's. Data (passthrough) is simply absent — its hole is
    * escaped, so it cannot hold markup.
    */
  def failed(stage: Transform.Stage): Staged = stage match {
    case Transform.Stage.Chart(_) =>
      Staged(FailedVersion, label("error", "Chart unavailable"))
    case Transform.Stage.Passthrough => Staged(FailedVersion, "")
  }

  /** The runtime's copy of `core/text.pkl`'s `label`: one structure, whose
    * classes are the base CSS every dashboard carries, so what the runtime
    * writes looks like a label an author placed. `FailureLabelSuite` holds the
    * two copies equal. `text` is a constant, never a viewer's value.
    */
  def label(tone: String, text: String): String =
    s"""<span class="fh-label fh-label-$tone fh-text"><span class="fh-text-run">$text</span></span>"""
}

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
  * dedupe at different levels: one fetch per query, one staged value per
  * (query, stage).
  *
  * The two levels are cached by different owners. An answer is cached by its
  * PROVIDER, since when one moves is the provider's policy (a bucket, a push,
  * never). A staged value is cached HERE, for every stage and every provider
  * alike: a stage is a function of an answer, and a version names an answer, so
  * nothing about a provider has to be known to share one.
  */
final class QueryResolver private (
    history: History,
    chart: IO[ChartDraw],
    staged: SharedCache[QueryResolver.StageKey, String]
) {
  import QueryResolver.StageKey

  def answer(identity: QueryIdentity, request: QueryRequest): IO[Answer] =
    request match {
      case QueryRequest.History(entityId, window) =>
        history.answer(identity, entityId, window)
    }

  def stage(
      identity: QueryIdentity,
      query: QueryRequest,
      stage: Transform.Stage,
      answered: Answer
  ): IO[Staged] =
    staged
      .get(StageKey((identity, query), stage, answered.version))(
        run(stage, answered.data)
      )
      .map(Staged(answered.version, _))

  private def run(stage: Transform.Stage, data: Json): IO[String] =
    stage match {
      case Transform.Stage.Passthrough  => IO(data.noSpaces)
      case Transform.Stage.Chart(style) => ChartStage.draw(chart, style, data)
    }

  private[query] def keys: IO[Set[StageKey]] = staged.keys
}

object QueryResolver {

  /** Who asked, and what: identity is part of it because what a provider
    * answers may depend on who reads.
    */
  type Question = (QueryIdentity, QueryRequest)

  /** The question is in the key because a version alone does not name an answer
    * — two sensors over one window share a bucket. The stage carries every
    * style field: a 600px drawing served for a 300px ask is a squashed axis
    * rather than a visible error.
    */
  final case class StageKey(
      question: Question,
      stage: Transform.Stage,
      version: Long
  )

  /** A staged value needs no expiry: it is replaced in place when its answer's
    * version moves.
    */
  def create(
      history: History,
      chart: IO[ChartDraw],
      failureTtl: FiniteDuration = SharedCache.FailureTtl,
      onFailure: SharedCache.OnFailure[StageKey] = SharedCache.ignore
  ): IO[QueryResolver] =
    SharedCache
      .create[StageKey, String](
        (added, other) =>
          other.question != added.question || other.stage != added.stage,
        failureTtl,
        onFailure
      )
      .map(new QueryResolver(history, chart, _))
}
