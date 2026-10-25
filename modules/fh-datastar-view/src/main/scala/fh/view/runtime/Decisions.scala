package fh.view.runtime

import fh.view.model.{NodeId, SetId}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** What one state snapshot decides, each thing once: which members a set holds
  * and in which clause, which branch a state group shows, and whether a
  * `valueWhile` holds. The pre-read, the walk, the render key and the paint ask
  * here, so they cannot disagree (pipeline doc §1a).
  *
  * Kept by the snapshot's IDENTITY: the store hands every reader of a version
  * the same map, so every viewer rendering it shares these, and a map that is
  * not that one decides afresh. Never the recorder's `Membership` (§4b).
  */
private[runtime] final class Decisions(val states: Map[String, EntityState]) {
  private val sets = new ConcurrentHashMap[SetId, GroupMembers]()
  private val members = new ConcurrentHashMap[NodeId, Option[Member]]()
  private val branches = new ConcurrentHashMap[NodeId, Option[Int]]()
  private val gates = new ConcurrentHashMap[String, java.lang.Boolean]()

  def set(gid: SetId)(derive: => GroupMembers): GroupMembers =
    Decisions.once(sets, gid)(derive)

  def member(id: NodeId)(derive: => Option[Member]): Option[Member] =
    Decisions.once(members, id)(derive)

  def branch(gid: NodeId)(derive: => Option[Int]): Option[Int] =
    Decisions.once(branches, gid)(derive)

  def holds(key: String)(derive: => Boolean): Boolean =
    Decisions.once(gates, key)(java.lang.Boolean.valueOf(derive)).booleanValue
}

private[runtime] object Decisions {

  // Not `computeIfAbsent`: a derivation that asks for another key in the same
  // map (a group's member, under a `limit`) throws "Recursive update".
  private def once[K, V](m: ConcurrentHashMap[K, V], k: K)(derive: => V): V =
    val held = m.get(k)
    if (held != null) held.nn
    else {
      val v = derive
      m.putIfAbsent(k, v)
      v
    }
}

/** The last two snapshots' [[Decisions]]: the recorder asks of a frame's
  * "before" and "after" in turn, and one slot would make each evict the other.
  */
private[runtime] final class Decided {
  private val last = new AtomicReference(
    (new Decisions(Map.empty), new Decisions(Map.empty))
  )

  def at(states: Map[String, EntityState]): Decisions = {
    val (newest, previous) = last.get
    if (newest.states eq states) newest
    else if (previous.states eq states) previous
    else {
      val made = new Decisions(states)
      last.set((made, newest))
      made
    }
  }
}
