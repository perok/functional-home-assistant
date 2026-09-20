package fh.view.query

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.SlotQuery
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant

/** What each query a render reads resolved to — the query counterpart of the
  * `Map[String, EntityState]` snapshot the state half travels as.
  *
  * A snapshot, resolved BEFORE a walk rather than during one. That is forced
  * rather than tidy: a render is a synchronous walk building a string, and
  * answering a query is an `IO` over a WebSocket and a JavaScript engine. So
  * the pipeline's shape is unchanged — a render stays a pure function of a
  * snapshot, and this is a second snapshot beside the first.
  */
final case class Fragments(fragments: Map[SlotQuery, Fragment]) {

  /** The version entry for each query, dropping the ones this snapshot has
    * nothing for.
    *
    * A MISSING query is a distinct key from any version it could have, exactly
    * as an entity the state snapshot does not hold is — so a node rendered
    * before its answer arrived cannot be mistaken for one rendered after. The
    * alternative, substituting a zero, would make those two renders share a
    * cache entry and serve the empty one.
    */
  def forQueries(queries: List[SlotQuery]): Map[SlotQuery, Long] =
    queries.flatMap(q => fragments.get(q).map(q -> _.version)).toMap

  def html(query: SlotQuery): Option[String] =
    fragments.get(query).map(_.html)
}

object Fragments {

  /** What every render that reads no query passes — and what a render whose
    * answers have not arrived passes too. Those are the same thing: no bytes,
    * and no claim to a version.
    */
  val none: Fragments = Fragments(Map.empty)

  private val log = Slf4jLogger.getLogger[IO]

  /** Answer every query a render needs, in parallel.
    *
    * A query that fails is ABSENT from the result rather than failing the
    * render: a provider that could not answer leaves a hole in the page, not a
    * blank page. Absent also means it claims no version, so the next render
    * asks again rather than repeating the failure until the version would have
    * moved.
    *
    * A query with no parsed request is dropped the same way and is not an error
    * here: `Dashboard.validate` is what rejects an unknown provider or a bad
    * parameter, so reaching this point without one is a bug already caught
    * upstream, and a render is the wrong place to discover it.
    */
  def resolve(
      resolver: QueryResolver,
      requests: Map[SlotQuery, QueryRequest],
      queries: List[SlotQuery],
      identity: QueryIdentity,
      asOf: Instant
  ): IO[Fragments] =
    queries.distinct
      .flatMap(q => requests.get(q).map(q -> _))
      .parTraverse { case (query, request) =>
        resolver.one(identity, request, asOf).attempt.flatMap {
          case Right(fragment) => IO.pure(Some(query -> fragment))
          case Left(e)         =>
            log
              .warn(e)(s"query: ${query.provider} ${query.params} failed")
              .as(None)
        }
      }
      .map(entries => Fragments(entries.flatten.toMap))
}
