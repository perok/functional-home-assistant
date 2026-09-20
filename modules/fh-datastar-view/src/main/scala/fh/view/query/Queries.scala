package fh.view.query

import cats.effect.IO
import fh.view.history.{ChartStyle, HistoryProvider, HistoryQuery, Window}
import fh.view.model.SlotQuery

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

/** What a provider answers with: markup, and a number saying AS OF WHEN this
  * content became current.
  *
  * The version is the whole contract with the render cache, and it doubles as
  * the provider's caching policy:
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
  case History(entityId: String, window: Window, style: ChartStyle)
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
}

/** Answers parsed queries. Holds what resolving needs and parsing does not: the
  * per-provider caches, the HA connection, the JavaScript context.
  */
final class QueryResolver(history: HistoryProvider) {

  def one(
      identity: QueryIdentity,
      request: QueryRequest,
      asOf: Instant
  ): IO[Fragment] = request match {
    case QueryRequest.History(entityId, window, style) =>
      history.fragment(identity, entityId, window, style, asOf)
  }
}
