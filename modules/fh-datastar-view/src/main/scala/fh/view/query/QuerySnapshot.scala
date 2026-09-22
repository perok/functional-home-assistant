package fh.view.query

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{NodeId, SlotAsk, SlotQuery, SlotRead}

import java.time.Instant

/** Every query a render reads, answered — a second snapshot beside the
  * `Map[String, EntityState]` one, resolved BEFORE the walk because a render is
  * a synchronous string build and answering is `IO`.
  *
  * TOTAL over what it was built for: [[resolve]] raises rather than returning a
  * partial answer, so [[value]] cannot miss (architecture §0).
  *
  * It also carries this viewer's node variables, so a read is always resolved
  * against the values its answers were fetched for. They are per render, not on
  * a `NodePlan`, which is shared across sessions.
  */
final class QuerySnapshot private (
    private val answers: Map[SlotRead, Staged],
    private val vars: Map[NodeId, Map[String, String]]
) {

  def varsAt(node: NodeId): Map[String, String] =
    vars.getOrElse(node, Map.empty)

  /** The one place an ask becomes a read. */
  def read(node: NodeId, ask: SlotAsk): SlotRead = ask.resolve(varsAt(node))

  def versions(node: NodeId, asks: List[SlotAsk]): Map[SlotRead, Long] =
    asks.view.map(read(node, _)).map(r => r -> staged(r).version).toMap

  def value(node: NodeId, ask: SlotAsk): String =
    staged(read(node, ask)).value

  def reads: Set[SlotRead] = answers.keySet

  private def staged(read: SlotRead): Staged =
    answers.getOrElse(
      read,
      // A wiring bug (the caller resolved a different set), so loud.
      throw FHError.internal(
        s"render reads ${QuerySnapshot.describe(read)} but it was " +
          "not resolved for this render — the caller resolved a different " +
          "set, or none at all"
      )
    )
}

object QuerySnapshot {

  /** For a render that reads no query. Never a default argument. */
  val empty: QuerySnapshot = new QuerySnapshot(Map.empty, Map.empty)

  /** Answers already in hand. The whole map at once: a snapshot is never grown
    * during a walk.
    */
  def of(
      answers: Map[SlotRead, Staged],
      vars: Map[NodeId, Map[String, String]] = Map.empty
  ): QuerySnapshot = new QuerySnapshot(answers, vars)

  /** Answer every query a render needs, in parallel, or raise — each caller
    * decides what a failure means for its path (an error page, or kept bytes
    * and a toast).
    *
    * `requests` is a memo of what `validate` parsed at DECLARED values; a
    * viewer's chosen value is parsed here instead. The write boundary already
    * refused any value a reader could not parse, so failing both is a bug.
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

    // Two levels: one fetch per QUERY, one drawing per (query, stage).
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
    } yield new QuerySnapshot(staged.toMap, vars)
  }

  private def describe(read: SlotRead): String =
    s"query '${read.query.provider} ${read.query.params}'"

  /** A non-`FHError` failure becomes a 503 naming the query: the dashboard is
    * fine, the recorder or engine is not.
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
