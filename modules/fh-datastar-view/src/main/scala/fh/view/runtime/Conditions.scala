package fh.view.runtime

import fh.view.model.{ExprValue, Op, Predicate}

/** The one interpreter of [[Predicate]], shared by clause guards, state
  * conditions and counts.
  */
private[runtime] object Conditions {

  /** A `Cmp` naming another entity is false here; use [[matchesIn]]. */
  def matches(p: Predicate, st: EntityState): Boolean =
    matchesIn(p, st, Map.empty)

  def matchesIn(
      p: Predicate,
      subject: EntityState,
      states: Map[String, EntityState]
  ): Boolean =
    p match {
      case Predicate.And(items) => items.forall(matchesIn(_, subject, states))
      case Predicate.Or(items)  => items.exists(matchesIn(_, subject, states))
      case Predicate.Not(item)  => !matchesIn(item, subject, states)
      case c: Predicate.Count   =>
        compare(
          present(c.tally, states).toString,
          StateStore.jsonToString(c.value),
          c.op
        )
      // Rather than reading the subject's value by accident.
      case Predicate.Cmp(_, _, _, Some(other)) if !states.contains(other) =>
        false
      case Predicate.Cmp(property, op, value, entity) =>
        val st = entity.flatMap(states.get).getOrElse(subject)
        compare(propertyOf(property, st), StateStore.jsonToString(value), op)
    }

  /** Expression values as CEL reads them; a tally is how many are present now.
    */
  def resolve(
      values: Map[String, ExprValue],
      states: Map[String, EntityState]
  ): Map[String, Object] =
    if (values.isEmpty) Map.empty
    else
      values.view
        .mapValues[Object] {
          case ExprValue.Text(s)  => s
          case ExprValue.Whole(n) => java.lang.Long.valueOf(n)
          case ExprValue.Real(d)  => java.lang.Double.valueOf(d)
          case ExprValue.Flag(b)  => java.lang.Boolean.valueOf(b)
          case ExprValue.Count(t) =>
            java.lang.Long.valueOf(present(t, states).toLong)
          // Subject-free (`validate`), as a state condition is.
          case ExprValue.Holds(p) =>
            java.lang.Boolean.valueOf(matchesIn(p, EntityState.none, states))
        }
        .toMap

  /** Unguarded means present, as for a set member. */
  def present(t: Predicate.Tally, states: Map[String, EntityState]): Int =
    t.candidates.count(id =>
      t.when.get(id).forall(g => states.get(id).exists(matchesIn(g, _, states)))
    )

  /** No `reg:`: registry facts fold away at build time, so one here is a build
    * bug.
    */
  def propertyOf(property: String, st: EntityState): String =
    property match {
      case "domain"                           => st.domain
      case "state"                            => st.state
      case "entity_id"                        => st.entityId
      case other if other.startsWith("attr:") =>
        st.attributes
          .get(other.stripPrefix("attr:"))
          .map(StateStore.jsonToString)
          .getOrElse("")
      case _ => ""
    }

  // Ordering is numeric, false unless both parse; equality compares strings.
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
