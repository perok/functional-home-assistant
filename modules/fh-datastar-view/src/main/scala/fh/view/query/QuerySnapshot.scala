package fh.view.query

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{NodeId, SlotAsk, SlotQuery, SlotRead}

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
  * answer, so [[value]] cannot miss and has no `Option` to unwrap. That is
  * architecture §0 carried in a type: the first render a browser gets is
  * COMPLETE, so "the answer has not arrived" is not a state a render may be in
  * — it either has every answer, or it never starts.
  *
  * The shape this replaced is worth knowing, because it looked harmless. A
  * failing query was dropped from the map and the slot rendered empty, and an
  * empty one was a DEFAULT ARGUMENT on nine render entry points — so a path
  * that read a query and was handed no answers compiled clean and shipped a
  * hole. Absence is not modelled here any more precisely because nothing could
  * tell the two readings of it apart.
  *
  * '''It also carries the NODE VARIABLES each node read''' (issue #209), and
  * that is not a convenience. A node declares an ASK — a query whose parameters
  * may be references — and what it actually asked depends on the values in
  * scope for THIS viewer. Holding the environment beside the answers makes the
  * pair inseparable: there is no way to look up a read resolved against one set
  * of values in a snapshot fetched for another, because the resolution happens
  * here, from the values this snapshot was built with.
  *
  * The values are per RENDER and the plans that name the asks are per RENDERER
  * — which is the whole reason they live here rather than on a `NodePlan`. A
  * plan is memoised per authored position and reused across sessions, so a
  * viewer's chosen window held there would be served to the next viewer.
  *
  * One element of it is a [[Staged]]: what a provider's answer BECAME once its
  * stage ran, and a `value` rather than `html` because `passthrough` yields the
  * provider's JSON. Both names were `Fragment`/`Fragments` until this landed,
  * which collided with the FRAGMENT this pipeline already means — a node's own
  * HTML (`docs/terminology.md`).
  */
final class QuerySnapshot private (
    private val answers: Map[SlotRead, Staged],
    // What each node's variables hold for the viewer this render is for.
    // Absent for the overwhelming majority of nodes, which read none.
    private val vars: Map[NodeId, Map[String, String]]
) {

  /** The values in scope at `node`, already folded over the ancestor chain and
    * over this viewer's choices. Empty is the common answer and the right one.
    */
  def varsAt(node: NodeId): Map[String, String] =
    vars.getOrElse(node, Map.empty)

  /** What `node` actually asked, resolving its references against this render's
    * values — the one place an ask becomes a read.
    */
  def read(node: NodeId, ask: SlotAsk): SlotRead = ask.resolve(varsAt(node))

  /** The version entry for each query, for the render key. Total, so a node
    * rendered before its answer arrived is not a case this has to describe —
    * there is no such render.
    */
  def versions(node: NodeId, asks: List[SlotAsk]): Map[SlotRead, Long] =
    asks.view.map(read(node, _)).map(r => r -> staged(r).version).toMap

  def value(node: NodeId, ask: SlotAsk): String =
    staged(read(node, ask)).value

  /** What this holds, for tests and for [[QuerySnapshot.resolve]]'s own dedupe.
    */
  def reads: Set[SlotRead] = answers.keySet

  private def staged(read: SlotRead): Staged =
    answers.getOrElse(
      read,
      // Reachable only by rendering a node whose queries were not among those
      // resolved for this render — which is a wiring bug in the render path,
      // not a state. It is loud because the alternative is the hole this type
      // exists to make unrepresentable.
      throw FHError.internal(
        s"render reads ${QuerySnapshot.describe(read)} but it was " +
          "not resolved for this render — the caller resolved a different " +
          "set, or none at all"
      )
    )
}

object QuerySnapshot {

  /** What a render that reads NO query passes. Not a default argument anywhere:
    * a caller says this because it knows the render reads nothing, never
    * because it has nothing to hand over.
    */
  val empty: QuerySnapshot = new QuerySnapshot(Map.empty, Map.empty)

  /** Answers already in hand — for tests, and for any caller that resolved by
    * some other route. Still a statement of what this render HAS, which is why
    * it takes the whole map rather than offering an `updated`: a snapshot is
    * assembled once and then read, never grown while a walk is under way.
    */
  def of(
      answers: Map[SlotRead, Staged],
      vars: Map[NodeId, Map[String, String]] = Map.empty
  ): QuerySnapshot = new QuerySnapshot(answers, vars)

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
    * '''`requests` is a MEMO, not a totality proof, and that changed with node
    * variables.''' It holds what `Dashboard.validate` parsed, which is every
    * read the dashboard makes at its DECLARED values — and a viewer who picks a
    * different window asks something the build never saw. So a read that is not
    * in it is parsed here rather than refused; what keeps that sound is the
    * write boundary, which already refused any value a declared reader could
    * not parse. Only a read that fails BOTH is a wiring bug worth raising on,
    * and it says so.
    */
  def resolve(
      resolver: QueryResolver,
      requests: Map[SlotRead, (QueryRequest, StageRequest)],
      reads: List[SlotRead],
      vars: Map[NodeId, Map[String, String]],
      identity: QueryIdentity,
      asOf: Instant
  ): IO[QuerySnapshot] = {
    val wanted = reads.distinct
    def parsed(read: SlotRead): IO[(QueryRequest, StageRequest)] =
      requests
        .get(read)
        .orElse(Queries.parseRead(read).toOption)
        .liftTo[IO](
          FHError.internal(
            s"${describe(read)} reached a render and does not parse — the " +
              "build did not see it (a chosen value) and it is not a request " +
              "either, so something wrote a value the write path never checked"
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
        guard(read.query)(resolver.stage(plans(read)._2, answers(read.query)))
          .map(read -> _)
      }
    } yield new QuerySnapshot(staged.toMap, vars)
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
