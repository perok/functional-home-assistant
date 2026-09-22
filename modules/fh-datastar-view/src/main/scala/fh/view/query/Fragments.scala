package fh.view.query

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{SlotQuery, SlotRead}

import java.time.Instant

/** What each query a render reads resolved to — the query counterpart of the
  * `Map[String, EntityState]` snapshot the state half travels as.
  *
  * A snapshot, resolved BEFORE a walk rather than during one. That is forced
  * rather than tidy: a render is a synchronous walk building a string, and
  * answering a query is an `IO` over a WebSocket and a JavaScript engine. So
  * the pipeline's shape is unchanged — a render stays a pure function of a
  * snapshot, and this is a second snapshot beside the first.
  *
  * '''TOTAL over the queries it was built for.''' [[resolve]] is the only way
  * to build a non-empty one and it raises rather than returning a partial
  * answer, so [[html]] cannot miss and has no `Option` to unwrap. That is
  * architecture §0 carried in a type: the first render a browser gets is
  * COMPLETE, so "the answer has not arrived" is not a state a render may be in
  * — it either has every answer, or it never starts.
  *
  * The shape this replaced is worth knowing, because it looked harmless. A
  * failing query was dropped from the map and the slot rendered empty, and
  * `Fragments.none` was a DEFAULT ARGUMENT on nine render entry points — so a
  * path that read a query and was handed no answers compiled clean and shipped
  * a hole. Absence is not modelled here any more precisely because nothing
  * could tell the two readings of it apart.
  */
final class Fragments private (
    private val answers: Map[SlotRead, Fragment]
) {

  /** The version entry for each query, for the render key. Total, so a node
    * rendered before its answer arrived is not a case this has to describe —
    * there is no such render.
    */
  def forQueries(reads: List[SlotRead]): Map[SlotRead, Long] =
    reads.view.map(r => r -> fragment(r).version).toMap

  def html(read: SlotRead): String = fragment(read).html

  /** What this holds, for tests and for [[Fragments.resolve]]'s own dedupe. */
  def reads: Set[SlotRead] = answers.keySet

  private def fragment(read: SlotRead): Fragment =
    answers.getOrElse(
      read,
      // Reachable only by rendering a node whose queries were not among those
      // resolved for this render — which is a wiring bug in the render path,
      // not a state. It is loud because the alternative is the hole this type
      // exists to make unrepresentable.
      throw FHError.internal(
        s"render reads ${Fragments.describe(read)} but it was " +
          "not resolved for this render — the caller resolved a different " +
          "set, or none at all"
      )
    )
}

object Fragments {

  /** What a render that reads NO query passes. Not a default argument anywhere:
    * a caller says this because it knows the render reads nothing, never
    * because it has nothing to hand over.
    */
  val empty: Fragments = new Fragments(Map.empty)

  /** Answers already in hand — for tests, and for any caller that resolved by
    * some other route. Still a statement of what this render HAS, which is why
    * it takes the whole map rather than offering an `updated`: a snapshot is
    * assembled once and then read, never grown while a walk is under way.
    */
  def of(answers: Map[SlotRead, Fragment]): Fragments = new Fragments(answers)

  /** Answer every query a render needs, in parallel, or raise.
    *
    * '''A failure is terminal, not a hole.''' The previous shape logged and
    * dropped it, which reads as resilience and is not: on the document path it
    * ships a page with a blank chart in it, which architecture §0 forbids. So
    * this raises, and each caller decides what that means for ITS path — a
    * document render answers an error, a background refresh keeps the bytes it
    * has and says so in the toast. Neither of those is expressible from here,
    * which is why this does not choose.
    *
    * A query with no parsed request is a bug already caught upstream —
    * `Dashboard.validate` rejects an unknown provider or a bad parameter — so
    * it raises here too rather than being skipped: reaching a render without
    * one means validation was bypassed, and rendering on is how that ships.
    */
  def resolve(
      resolver: QueryResolver,
      requests: Map[SlotRead, (QueryRequest, StageRequest)],
      reads: List[SlotRead],
      identity: QueryIdentity,
      asOf: Instant
  ): IO[Fragments] = {
    val wanted = reads.distinct
    def parsed(read: SlotRead): IO[(QueryRequest, StageRequest)] =
      requests
        .get(read)
        .liftTo[IO](
          FHError.internal(
            s"${describe(read)} reached a render with no parsed request — " +
              "validate did not run on this build"
          )
        )

    // TWO levels, and the nesting IS the dedupe rule: one fetch per QUERY, one
    // drawing per (query, stage). Two cards charting the same sensor and window
    // at different sizes ask HA once and draw twice — which the provider used
    // to arrange privately with a second cache, and which is now what the key
    // says. Both levels are `parTraverse`d, so the fan-out is unchanged.
    for {
      plans <- wanted.traverse(r => parsed(r).map(r -> _)).map(_.toMap)
      answers <- plans.toList
        .map { case (read, (qr, _)) => read.query -> qr }
        .distinctBy(_._1)
        .parTraverse { case (q, qr) =>
          guard(q)(resolver.answer(identity, qr, asOf)).map(q -> _)
        }
        .map(_.toMap)
      staged <- wanted.parTraverse { read =>
        val (qr, sr) = plans(read)
        guard(read.query)(
          resolver.stage(identity, qr, sr, answers(read.query))
        ).map(read -> _)
      }
    } yield new Fragments(staged.toMap)
  }

  private def describe(read: SlotRead): String =
    s"query '${read.query.provider} ${read.query.params}'"

  /** Any non-`FHError` failure becomes a 503 naming the query. 503 and not 500
    * because the dashboard is fine and the recorder or the engine is not — the
    * difference between "come back" and "this build is broken".
    */
  private def guard[A](query: SlotQuery)(io: IO[A]): IO[A] =
    io.recoverWith {
      case e: FHError => IO.raiseError(e)
      case e          =>
        IO.raiseError(
          FHError.unavailable(
            s"query '${query.provider} ${query.params}' could not be " +
              s"answered: ${e.getMessage}"
          )
        )
    }
}
