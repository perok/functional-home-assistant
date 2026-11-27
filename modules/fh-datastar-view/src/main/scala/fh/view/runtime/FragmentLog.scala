package fh.view.runtime

import fh.view.model.{Ix, SignalId, SlotValue}

import scala.collection.immutable.BitSet

/** What one client has for one node: the byte-slot values its bytes were
  * rendered from ([[Renderer.byteSlotValues]]) and its signal values (ADR
  * 0017). One map keyed by the node's [[fh.view.model.Ix]], so a host fill's
  * invalidation is the containment test `Patches.applied` already runs.
  * `values` is optional because a signal frame establishes only the signals.
  *
  * `shows`, on a host (a set or a pick): the root of each thing it shows, in
  * the order sent. `None` is unknown, never empty; arrays compare by reference,
  * so read it through `toList`.
  */
private[runtime] case class Held(
    values: Option[ByteValues] = None,
    signals: Map[SignalId, SlotValue] = Map.empty,
    shows: Option[IArray[Ix]] = None
) {

  /** `later` says nothing by being absent, so re-sending one of two signals
    * cannot drop the other, and a morph leaves the host's list alone; new bytes
    * bring their own values.
    */
  def merge(later: Held): Held =
    Held(
      later.values.orElse(values),
      signals ++ later.signals,
      later.shows.orElse(shows)
    )
}

/** Which node or host changed at which version: a node whose bytes moved, a
  * host whose decided keys moved, and the root of each key that arrived, each
  * by its [[fh.view.model.Ix]], so a change in one clause of a set member is
  * not a change in the other, which a viewer may be shown. What a client shows
  * is its session's `holds`, so the log says only where to look, and it is
  * bounded by the index: nothing in it needs pruning.
  *
  * A cursor minted against another `id` (restart, renderer swap) is refused:
  * versions mean nothing across logs.
  *
  * `stamp` is the renderer whose recorder writes it, `None` under `Failed`. A
  * swap sets the renderer after the log, so a reader can meet a log another
  * renderer numbers; it takes one only under its own ([[numberedBy]]). No clock
  * is read: versions order everything (ADR 0011).
  */
private[runtime] case class FragmentLog(
    id: String,
    stamp: Option[Renderer.Stamp] = None,
    // A missing entry reads "unchanged since the log began", which the
    // client's own page already shows ([[completeFrom]]).
    fragments: Map[Ix, Long] = Map.empty,
    // The oldest version the whole log describes ([[skipped]], [[reaches]]).
    completeFrom: Long = 0
) {

  /** Nobody watched this version. The history goes too: [[reaches]] now refuses
    * every cursor at or below it, and a later session starts above it
    * ([[Server.recordFrame]]), so an idle slug keeps one number.
    */
  def skipped(version: Long): FragmentLog =
    if (completeFrom > version) this
    else FragmentLog(id = id, stamp = stamp, completeFrom = version + 1)

  /** The recorder's first frame is the state it subscribed at, recorded
    * nowhere, so the log describes only what comes after it.
    */
  def seeded(version: Long): FragmentLog = copy(completeFrom = version + 1)

  def numberedBy(r: Renderer.Stamp): Boolean = stamp.exists(_ eq r)

  /** The id a cursor minted under `r` may quote: a log `r` does not number must
    * not let that page resume against it later.
    */
  def idUnder(r: Renderer.Stamp): String =
    if (numberedBy(r)) id else FragmentLog.NoLog

  /** `v + 1`: a document at V holds all of V, so it needs only `(V, now]`;
    * reading `v` would repaint every first connect to an idle dashboard.
    */
  def reaches(v: Long): Boolean = completeFrom <= v + 1

  /** Why this log cannot name what a page painted under `r` missed after `v`,
    * if it cannot: the one test a live pull ([[Patches.Watched]]) and an
    * opening ([[Opening.of]]) both resume on.
    */
  def cannotResume(r: Renderer.Stamp, v: Long): Option[RepaintReason] =
    if (!numberedBy(r)) Some(RepaintReason.OtherLog)
    else if (!reaches(v)) Some(RepaintReason.BeforeGap)
    else None

  // A fragment's version never goes backwards.
  def touched(ix: Ix, at: Long): FragmentLog =
    if (fragments.get(ix).exists(_ > at)) this
    else copy(fragments = fragments.updated(ix, at))

  /** `>=`, since a client can hold part of version V; re-sending V is
    * idempotent. Places only: the caller renders from the current snapshot (ADR
    * 0012). A `BitSet`, since every pull builds one: a `Set[Ix]` boxes each.
    */
  def since(v: Long): BitSet =
    fragments.iterator
      .collect { case (ix, at) if at >= v => ix.toInt }
      .to(BitSet)
}

private[runtime] object FragmentLog {

  /** No log has it, so a cursor quoting it always repaints. */
  val NoLog: String = ""

  /** Reaches no cursor until its recorder [[FragmentLog.seeded]] it: a page
    * served before that holds a version nothing recorded the way to.
    */
  def fresh(id: String, stamp: Option[Renderer.Stamp]): FragmentLog =
    FragmentLog(id, stamp, completeFrom = Long.MaxValue)
}
