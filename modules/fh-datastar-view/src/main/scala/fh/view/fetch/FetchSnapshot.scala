package fh.view.fetch

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.model.{NodeId, SlotAsk, SlotRead}

/** Every fetch a render reads, answered — a second snapshot beside the
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
final class FetchSnapshot private (
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
        s"render reads ${FetchSnapshot.describe(read)} but it was " +
          "not resolved for this render — the caller resolved a different " +
          "set, or none at all"
      )
    )
}

object FetchSnapshot {

  /** For a render that reads no fetch. Never a default argument. */
  val empty: FetchSnapshot = new FetchSnapshot(Map.empty, Map.empty)

  /** Answers already in hand. The whole map at once: a snapshot is never grown
    * during a walk.
    */
  def of(
      answers: Map[SlotRead, Staged],
      vars: Map[NodeId, Map[String, String]] = Map.empty
  ): FetchSnapshot = new FetchSnapshot(answers, vars)

  /** Answer every fetch a render needs, in parallel. A failure is that read's,
    * not the render's; the caches bound each wait.
    */
  def resolve(
      resolver: FetchResolver,
      reads: List[SlotRead],
      vars: Map[NodeId, Map[String, String]],
      identity: FetchIdentity
  ): IO[FetchSnapshot] = {
    val wanted = reads.distinct
    // Two levels: one call per FETCH, one drawing per (fetch, stage).
    for {
      answers <- wanted
        .map(_.fetch)
        .distinct
        .parTraverse(q => resolver.answer(identity, q).attempt.map(q -> _))
        .map(_.toMap)
      staged <- wanted.parTraverse { read =>
        answers(read.fetch)
          .liftTo[IO]
          .flatMap(resolver.stage(identity, read.fetch, read.stage, _))
          .attempt
          .map(s => read -> s.getOrElse(Staged.failed(read.stage)))
      }
    } yield new FetchSnapshot(staged.toMap, vars)
  }

  private def describe(read: SlotRead): String = s"fetch '${read.fetch}'"
}
