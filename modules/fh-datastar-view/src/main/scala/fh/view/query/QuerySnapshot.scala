package fh.view.query

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{NodeId, SlotAsk, SlotRead}

/** Every query a render reads, answered — a second snapshot beside the
  * `Map[String, EntityState]` one, resolved BEFORE the walk because a render is
  * a synchronous string build and answering is `IO`.
  *
  * TOTAL over what it was built for, so [[value]] cannot miss (architecture
  * §0): a read [[resolve]] could not answer holds [[Staged.failed]].
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

  /** Answer every query a render needs, in parallel. Only a read that does not
    * parse raises, since that is a wiring bug.
    *
    * `requests` is a memo of what `validate` parsed at DECLARED values; a
    * viewer's chosen value is parsed here instead. The write boundary already
    * refused any value a reader could not parse, so failing both is a bug.
    */
  def resolve(
      resolver: QueryResolver,
      requests: Map[SlotRead, QueryRequest],
      reads: List[SlotRead],
      vars: Map[NodeId, Map[String, String]],
      identity: QueryIdentity
  ): IO[QuerySnapshot] = {
    val wanted = reads.distinct
    def parsed(read: SlotRead): IO[QueryRequest] =
      requests
        .get(read)
        .orElse(Queries.parse(read.query).toOption)
        .liftTo[IO](
          FHError.internal(
            s"${describe(read)} reached a render and does not parse — the " +
              "build did not see it (a chosen value) and it is not a request " +
              "either, so something wrote a value the write path never checked"
          )
        )

    // Two levels: one fetch per QUERY, one drawing per (query, stage). A
    // failure is that read's, not the render's; the caches bound each wait.
    for {
      plans <- wanted.traverse(r => parsed(r).map(r -> _)).map(_.toMap)
      answers <- plans.toList
        .map { case (read, qr) => read.query -> qr }
        .distinctBy(_._1)
        .parTraverse { case (q, qr) =>
          resolver.answer(identity, qr).attempt.map(q -> _)
        }
        .map(_.toMap)
      staged <- wanted.parTraverse { read =>
        answers(read.query)
          .liftTo[IO]
          .flatMap(resolver.stage(identity, plans(read), read.stage, _))
          .attempt
          .map(s => read -> s.getOrElse(Staged.failed(read.stage)))
      }
    } yield new QuerySnapshot(staged.toMap, vars)
  }

  private def describe(read: SlotRead): String =
    s"query '${read.query.provider} ${read.query.params}'"
}
