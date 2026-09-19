package fh.view.history

import fh.view.model.SeriesRead

import java.time.Instant

/** Which bucket each series was last fetched in — the series half of what a
  * render reads, and the counterpart of the `Map[String, EntityState]` snapshot
  * the state half travels as.
  *
  * A snapshot, resolved BEFORE a walk rather than during one. That is forced
  * rather than tidy: a render is a synchronous walk building a string, and
  * fetching a series is an `IO` over a WebSocket. So the pipeline's shape is
  * unchanged — a render stays a pure function of a snapshot, and this is a
  * second snapshot beside the first.
  *
  * Epoch SECONDS rather than `Instant` in the key it produces, because
  * `RenderInputs` compares versions numerically and a bucket is exactly that: a
  * number that only goes up, for a window whose earlier points can never move.
  */
final case class SeriesBuckets(buckets: Map[SeriesRead, Instant]) {

  /** The version entry for each read, dropping the ones this snapshot has
    * nothing for.
    *
    * A MISSING read is a distinct key from any bucket it could have, exactly as
    * an entity the state snapshot does not hold is — so a node rendered before
    * its series arrived cannot be mistaken for one rendered after. The
    * alternative, substituting a zero, would make those two renders share a
    * cache entry and serve the empty one.
    */
  def forReads(reads: List[SeriesRead]): Map[SeriesRead, Long] =
    reads.flatMap(r => buckets.get(r).map(r -> _.getEpochSecond)).toMap

  def updated(read: SeriesRead, bucket: Instant): SeriesBuckets =
    SeriesBuckets(buckets.updated(read, bucket))
}

object SeriesBuckets {

  /** What every render that reads no series passes, which is all of them until
    * a chart card exists.
    */
  val none: SeriesBuckets = SeriesBuckets(Map.empty)

  /** The bucket a read is in at `asOf`, for a window this build knows. An
    * unknown window name yields nothing rather than raising: `Dashboard.validate`
    * is what rejects one, so reaching here with a bad name is already a bug
    * caught upstream, and a render is the wrong place to discover it.
    */
  def at(reads: List[SeriesRead], asOf: Instant): SeriesBuckets =
    SeriesBuckets(
      reads.flatMap { r =>
        Window.byName(r.window).map(w => r -> w.bucketOf(asOf))
      }.toMap
    )
}
