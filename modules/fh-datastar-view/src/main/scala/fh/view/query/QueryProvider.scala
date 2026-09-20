package fh.view.query

import cats.effect.IO
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
  * The version is the whole contract between a provider and the render cache,
  * and it doubles as the provider's caching policy:
  *
  *   - a stable number (history's bucket) means every viewer inside it shares
  *     one answer, and the entry expires by time moving rather than by a timer;
  *   - a number that moves every call (`asOf.toEpochMilli`, the honest default
  *     for content with no natural shelf life) never matches, so the node never
  *     serves from cache and the provider is asked every render. Uncached by
  *     construction — no opt-out flag, and the failure mode is cost rather than
  *     staleness.
  *
  * It must be NON-DECREASING: `RenderInputs.isAtLeast` compares with `>=`. "When
  * this became current" always satisfies that; a content hash would not, and a
  * provider that can only offer one is the trigger to compare queries by
  * equality instead.
  *
  * Version and bytes travel together so that a version with no bytes is
  * unrepresentable. A render holding one would enter the cache claiming a
  * version it never drew, pinning the empty answer until the version moved.
  */
final case class Fragment(version: Long, html: String) derives CanEqual

/** One query with its parameters already parsed — the thing `parse` produces
  * and the renderer holds.
  *
  * A closure rather than a `(provider, typedRequest)` pair, which is what keeps
  * the request type private to the provider: the pipeline would otherwise need
  * a type member or an existential to carry it, and would still be unable to do
  * anything with it.
  */
trait PreparedQuery {
  def resolve(identity: QueryIdentity, asOf: Instant): IO[Fragment]
}

/** A named answerer of queries.
  *
  * Caching, dedupe and eviction are deliberately ABSENT from this interface.
  * Bucket expiry is a property of append-only-past data, not of queries: it
  * works for history because the past is immutable and only the tail grows, but
  * a forecast changes in the FUTURE, a camera still changes continuously, and a
  * template render changes arbitrarily. A shared cache would have to be
  * configured by every provider into the shape it needed, which is worse than
  * none — so each provider holds whatever it needs, and says what it did
  * through [[Fragment.version]].
  */
trait QueryProvider {
  def name: String

  /** Parse, don't validate: this runs once at `Dashboard.validate` and yields
    * the thing that can do the work, so a bad parameter is a build error naming
    * the dashboard rather than a blank card at render time.
    */
  def parse(params: Map[String, String]): Either[String, PreparedQuery]
}

/** The providers this instance knows, by name. */
final class QueryProviders private (byName: Map[String, QueryProvider]) {

  def parse(q: SlotQuery): Either[String, PreparedQuery] =
    byName.get(q.provider) match {
      case Some(p) => p.parse(q.params)
      case None    =>
        Left(
          s"unknown query provider '${q.provider}'" +
            (if (byName.isEmpty) " — none are registered"
             else s" — one of ${byName.keys.toList.sorted.mkString(", ")}")
        )
    }

  def names: Set[String] = byName.keySet
}

object QueryProviders {

  /** What a dashboard validated outside the server gets. A query slot is then
    * a build error naming the provider it asked for, which is the right answer
    * for a test fixture that did not mean to have one.
    */
  val empty: QueryProviders = QueryProviders(Map.empty)

  def of(providers: QueryProvider*): QueryProviders =
    QueryProviders(providers.map(p => p.name -> p).toMap)
}
