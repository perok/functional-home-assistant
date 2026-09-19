package fh.view.runtime

import fh.view.model.{Op, Predicate}

/** Evaluating a [[Predicate]] against live entity state — the one place the
  * query wire AST is interpreted.
  *
  * Its own object rather than a corner of the renderer: three unrelated callers
  * ask the same question. A set member's clause guard ([[MemberSource]]), a
  * state surface's activation condition (`Renderer.holds`), and a `Count`
  * aggregate nested inside either. None of them is about membership, and none
  * needs a renderer to answer.
  */
private[runtime] object Conditions {

  /** Evaluate a query predicate against one entity's live state. The entity's
    * id and domain come off the [[EntityState]] itself.
    *
    * A `Cmp` naming another entity cannot be resolved from a single state, so
    * this treats it as false; use [[matchesIn]] where the snapshot is
    * available.
    */
  def matches(p: Predicate, st: EntityState): Boolean =
    matchesIn(p, st, Map.empty)

  /** [[matches]] with the snapshot in hand, so a guard may name a DIFFERENT
    * entity than its subject. `Cmp.entity` absent still means "the subject".
    */
  def matchesIn(
      p: Predicate,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Boolean =
    p match {
      case Predicate.And(items) => items.forall(matchesIn(_, subject, states))
      case Predicate.Or(items)  => items.exists(matchesIn(_, subject, states))
      case Predicate.Not(item)  => !matchesIn(item, subject, states)
      case Predicate.Count(candidates, when, op, value) =>
        // A candidate with no guard is unconditionally present — the same rule
        // a set member with an unguarded clause follows.
        val n = candidates.count(id =>
          when
            .get(id)
            .forall(g => states.get(id).exists(matchesIn(g, _, states)))
        )
        compare(n.toString, StateStore.jsonToString(value), op)
      case Predicate.Cmp(_, _, _, Some(other)) if !states.contains(other) =>
        // Named an entity the snapshot does not have: it can never hold, and
        // saying so beats reading the subject's value by accident.
        false
      case Predicate.Cmp(property, op, value, entity) =>
        val st = entity.flatMap(states.get).getOrElse(subject)
        compare(propertyOf(property, st), StateStore.jsonToString(value), op)
    }

  /** One entity property, as the string a comparison or an ordering reads.
    * `reg:` is deliberately absent: a registry fact is build-time data, so a
    * comparison on one folds away before it reaches here and an ordering on one
    * leaves the candidates pre-sorted. Seeing a `reg:` here means the build
    * emitted something it should have resolved.
    */
  def propertyOf(property: String, st: EntityState): String =
    property match {
      case "domain" => st.domain
      case "state"  => st.state
      // The entity's identity itself — what lets a state-activation condition
      // pin one entity ("entity X is in state Y") and a candidate set enumerate
      // an explicit entity set.
      case "entity_id"                        => st.entityId
      case other if other.startsWith("attr:") =>
        st.attributes
          .get(other.stripPrefix("attr:"))
          .map(StateStore.jsonToString)
          .getOrElse("")
      case _ => ""
    }

  /** Ordering ops compare NUMERICALLY, and are false unless both sides parse as
    * numbers; equality ops compare the raw strings.
    */
  private def compare(lhs: String, rhs: String, op: Op): Boolean = {
    def numeric(cmp: (Double, Double) => Boolean): Boolean =
      (lhs.toDoubleOption, rhs.toDoubleOption) match {
        case (Some(l), Some(r)) => cmp(l, r)
        case _                  => false
      }
    op match {
      case Op.Eq  => lhs == rhs
      case Op.Ne  => lhs != rhs
      case Op.Lt  => numeric(_ < _)
      case Op.Lte => numeric(_ <= _)
      case Op.Gt  => numeric(_ > _)
      case Op.Gte => numeric(_ >= _)
    }
  }
}
