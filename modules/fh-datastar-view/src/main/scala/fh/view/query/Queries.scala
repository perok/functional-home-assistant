package fh.view.query

import cats.effect.IO
import cats.syntax.all.*
import fh.view.history.{
  ChartStage,
  ChartStyle,
  HistoryProvider,
  HistoryQuery,
  Window
}
import fh.view.model.{SlotQuery, SlotRead, Transform}

import io.circe.Json

import java.time.Instant

/** Who a query reads as.
  *
  * A cache-key component wherever a provider caches, and that is the whole
  * reason it is a type rather than a `String` passed around: HA's recorder data
  * is permission-scoped, so two people who must not see the same rows must not
  * share an entry. A provider that fills this in differently stops sharing by
  * construction, rather than by someone remembering to widen a key at the same
  * time.
  */
opaque type QueryIdentity = String

object QueryIdentity {

  /** The add-on's own identity: everybody shares, which is correct while the
    * server reads as itself.
    */
  val Instance: QueryIdentity = "instance"

  def user(id: String): QueryIdentity = s"user:$id"
}

/** What a PROVIDER answers with: data, and a number saying AS OF WHEN this
  * content became current.
  *
  * Data and not markup, which is the whole of the inversion. A provider
  * fetches; what its answer BECOMES is the slot's `transform` — a chart, or
  * nothing at all, in which case this JSON is what the card gets. The provider
  * has no opinion about presentation and no way to express one.
  *
  * The version is the contract with the render cache, and it doubles as the
  * provider's caching policy:
  *
  *   - a stable number (history's bucket) means every viewer inside it shares
  *     one answer, and the entry expires by time moving rather than by a timer;
  *   - a number that moves every call (`asOf.toEpochMilli`, the honest default
  *     for content with no natural shelf life) never matches, so the node never
  *     serves from cache and the provider is asked every render. Uncached by
  *     construction — no opt-out flag, and the failure mode is cost rather than
  *     staleness.
  *
  * It must be NON-DECREASING: `RenderInputs.isAtLeast` compares with `>=`.
  * "When this became current" always satisfies that; a content hash would not,
  * and a provider that can only offer one is the trigger to compare queries by
  * equality instead.
  */
final case class Answer(version: Long, data: Json) derives CanEqual

/** What reaches the WALK: bytes, and the version they were made from.
  *
  * The version is the provider's, unchanged by the stage — a stage is a
  * deterministic function of an answer and has no version of its own, which is
  * what keeps the non-decreasing rule true at every level.
  *
  * Version and bytes travel together so that a version with no bytes is
  * unrepresentable. A render holding one would enter the cache claiming a
  * version it never drew, pinning the empty answer until the version moved.
  */
final case class Fragment(version: Long, html: String) derives CanEqual

/** A query with its parameters parsed — one case per provider.
  *
  * A closed sum rather than a registry of trait instances, because there is no
  * plugin story here to pay for: one `match` says in code what providers exist,
  * where a name→instance map says only what somebody remembered to put in it.
  * Adding a provider is a case here, a member of the union in `core/slot.pkl`,
  * and an arm in [[QueryResolver]] — all three of which the compiler and the
  * Pkl typechecker point at.
  */
enum QueryRequest derives CanEqual {
  case History(entityId: String, window: Window)
}

/** A stage with ITS parameters parsed — the presentation half of the same
  * split, and a closed sum for the same reason.
  */
enum StageRequest derives CanEqual {
  case Passthrough
  case Chart(style: ChartStyle)
}

object Queries {

  /** Parse a wire query into a request, or say why it cannot be one.
    *
    * PURE, and that is what makes it callable straight from
    * `Dashboard.validate` with nothing wired: parsing needs no store, no
    * JavaScript engine and no connection — only resolving does. So a bad query
    * is a build error everywhere a dashboard is built, rather than wherever
    * somebody remembered to pass a provider in.
    */
  def parse(query: SlotQuery): Either[String, QueryRequest] =
    query.provider match {
      case HistoryQuery.Name => HistoryQuery.parse(query.params)
      case other             =>
        Left(
          s"unknown query provider '$other' — one of ${HistoryQuery.Name}"
        )
    }

  /** The same, for the stage half. Also pure, and also called from `validate`,
    * so a bad chart size is a build error rather than a render-time surprise.
    */
  def parseStage(stage: Transform.Stage): Either[String, StageRequest] =
    stage match {
      case Transform.Stage.Passthrough => Right(StageRequest.Passthrough)
      case Transform.Stage.Chart(ps)   =>
        ChartStyle.parse(ps).map(StageRequest.Chart.apply)
    }

  /** Both halves of one slot's read. */
  def parseRead(read: SlotRead): Either[String, (QueryRequest, StageRequest)] =
    (parse(read.query), parseStage(read.stage)).tupled
}

/** Answers parsed queries and runs parsed stages. Holds what resolving needs
  * and parsing does not: the per-provider caches, the HA connection, the
  * JavaScript context.
  *
  * The two halves are deliberately separate methods rather than one call. They
  * deduplicate at different levels — one fetch per query, one drawing per
  * (query, stage) — and a single entry point would have to rediscover that
  * split internally, which is what the provider used to do with two private
  * caches.
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

  def stage(request: StageRequest, answered: Answer): IO[Fragment] =
    request match {
      case StageRequest.Passthrough =>
        // No transform: the provider's JSON, as the slot's value. It goes in an
        // ESCAPED hole — it is an attribute value, not markup — which is the
        // other half of the rule that a drawn stage needs the raw one.
        IO.pure(Fragment(answered.version, answered.data.noSpaces))
      case StageRequest.Chart(style) =>
        chart
          .draw(style, answered.version, answered.data)
          .map(Fragment(answered.version, _))
    }
}
