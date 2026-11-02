package fh.view.runtime

import fh.view.model.{
  FetchTemplate,
  Lit,
  Op,
  Predicate,
  Transform,
  Value,
  ValueExpr
}

/** The one interpreter of [[Predicate]], shared by clause guards, state
  * conditions, counts and a condition filling a slot (`Value.Condition`).
  */
private[view] object Conditions {

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
      case f: Predicate.First => leader(f, states).contains(f.entity)
      // Rather than reading the subject's value by accident.
      case Predicate.Cmp(_, _, _, Some(other)) if !states.contains(other) =>
        false
      case Predicate.Cmp(property, op, value, entity) =>
        val st = entity.flatMap(states.get).getOrElse(subject)
        compare(propertyOf(property, st), StateStore.jsonToString(value), op)
    }

  /** Named inputs as CEL reads them: a literal as written, a count how many are
    * present now and the ids themselves without one, a fetch its answer
    * (`fetched`), an entity's part as it reads now.
    */
  def resolve(
      inputs: Map[String, ValueExpr],
      states: Map[String, EntityState],
      fetched: FetchTemplate => Object
  ): Map[String, Object] =
    if (inputs.isEmpty) Map.empty
    else
      inputs.view
        .mapValues[Object] {
          case ValueExpr(Value.Literal(Lit.Text(s)), _, _) => s
          case ValueExpr(Value.Literal(Lit.Bool(b)), _, _) =>
            java.lang.Boolean.valueOf(b)
          case ValueExpr(Value.Literal(Lit.Whole(n)), _, _) =>
            java.lang.Long.valueOf(n)
          case ValueExpr(Value.Literal(Lit.Real(d)), _, _) =>
            java.lang.Double.valueOf(d)
          case ValueExpr(Value.Present(t), Nil, _) =>
            Cel.arg(presentOf(t, states))
          case ValueExpr(Value.Present(t), _, _) =>
            java.lang.Long.valueOf(present(t, states).toLong)
          // Subject-free (`validate`), as a state condition is.
          case ValueExpr(Value.Condition(p), _, _) =>
            java.lang.Boolean.valueOf(matchesIn(p, EntityState.none, states))
          case ValueExpr(Value.Fetch(f), _, _)                     => fetched(f)
          case ValueExpr(Value.Entity(Some(id), part, _, _), _, _) =>
            Cel.arg(
              Transform.read(
                part,
                states.getOrElse(id, EntityState(id, "", Map.empty))
              )
            )
          // `validate` refuses any other shape (`ValueExpr.inputTy`).
          case _ => ""
        }
        .toMap

  /** Unguarded means present, as for a set member. */
  def present(t: Predicate.Tally, states: Map[String, EntityState]): Int =
    t.candidates.count(isPresent(t, _, states))

  /** What [[present]] counts: the ids a call over the set acts on. */
  def presentOf(
      t: Predicate.Tally,
      states: Map[String, EntityState]
  ): List[String] =
    t.candidates.filter(isPresent(t, _, states))

  private def isPresent(
      t: Predicate.Tally,
      id: String,
      states: Map[String, EntityState]
  ): Boolean =
    t.when.get(id).forall(g => states.get(id).exists(matchesIn(g, _, states)))

  /** The earliest wins a tie, as a set's stable sort keeps candidate order. */
  private def leader(
      f: Predicate.First,
      states: Map[String, EntityState]
  ): Option[String] =
    f.candidates
      .filter(isPresent(f.tally, _, states))
      .reduceLeftOption((best, id) =>
        if (MemberGraph.precedes(f.orderBy, id, best, states)) id else best
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
