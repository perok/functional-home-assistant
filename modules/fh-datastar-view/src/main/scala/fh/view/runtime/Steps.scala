package fh.view.runtime

import fh.view.model.{Lit, Op, Part, Step, Value}

/** What each simple step makes of the value before it, its operands already
  * read: the arithmetic [[Evaluator]] composes, which decides what is read and
  * when.
  *
  * The engine-free tier (ADR 0028), selected by form alone: a source read and
  * its [[Step.Simple]] steps are fast-path work, a [[Step.Cel]] engine work,
  * and nothing infers one from the other. A condition is its bool half, so it
  * fills a slot as directly as it decides a `c.iff`: one compiler runs both.
  *
  * A step belongs here when it is a static lookup and TOTAL over every value a
  * live entity can produce — the load-bearing half, since `double('unknown')`
  * would put an error on a wall panel. Speed is the other: ~0.9 kB per CEL
  * evaluation against ~45 B for a direct read (`RenderBench.cel` vs `direct`).
  * Where a step has a CEL spelling (documented on its case), StepsSuite holds
  * the evaluator byte-equal to it over a hostile sweep and pins the few
  * divergences where CEL would error.
  */
object Steps {

  def read(part: Part, entity: EntityState): Any = part match
    case Part.State      => entity.state
    case Part.Attr(name) => entity.attribute(name)
    case Part.Whole      => entity.celValue

  /** Text as CEL renders a bare result: `str(x)`. */
  def text(v: Any): String = v match
    case s: String => s
    case null      => ""
    case other     => Cel.stringify(other)

  /** One simple step over `value`, its operands already read, in
    * [[Step.operands]] order, so a render builds no reader.
    */
  def formatWith(step: Step.Simple, value: Any, a: Any, b: Any): Any =
    step match
      case Step.Count =>
        value match
          case xs: List[?] => xs.size.toLong
          case _           => null
      case Step.Prefix(_)   => text(a) + text(value)
      case Step.Suffix(_)   => text(value) + text(a)
      case Step.WithUnit(_) =>
        val v = text(value)
        a match
          case unit: String if v.nonEmpty => v + " " + unit
          case _                          => v
      // Operands without an `Option`: this runs per resolve of every slider
      // track, and two `Some`s and their boxes each time showed in RenderBench.
      case Step.Percent(_, _) =>
        number(value) match
          case Some(v) if isNum(a) && isNum(b) =>
            val min = dbl(a)
            val max = dbl(b)
            Cel.numToString(roundAway((v - min) * 100.0 / (max - min))) + " %"
          case _ => null
      case Step.Fill(_, _) =>
        number(value) match
          case Some(v) if isNum(a) && isNum(b) =>
            val min = dbl(a)
            val max = dbl(b)
            Cel.numToString(100.0 - (v - min) * 100.0 / (max - min)) + "%"
          case _ => null
      case Step.Duration(scale) =>
        number(value) match
          case Some(v) =>
            val t = roundAway(v * scale).toLong
            if (t <= 0) "0s"
            else if (t >= 3600) s"${t / 3600}h ${t % 3600 / 60}m"
            else if (t >= 60) s"${t / 60}m"
            else s"${t}s"
          case None => null
      case Step.Match(cases, _) =>
        (if (value == null) None else cases.get(text(value))) match
          case Some(Lit.Bool(flag)) => flag
          case Some(lit)            => text(lit.raw)
          case None                 =>
            a match
              case flag: Boolean => flag
              case other         => text(other)
      case Step.AllOf | Step.AnyOf =>
        value match
          // A list CEL built, the one a list source's own run did not fuse.
          case xs: List[?] =>
            var rest = xs
            kleene(step == Step.AnyOf)(rest.nonEmpty) {
              val h = rest.head
              rest = rest.tail
              h
            }
          case _ => null
      case Step.Compare(op, to) => compare(value, a, op, to)
      case Step.Not             =>
        value match
          case b: Boolean => !b
          case _          => null

  /** Kleene's and (`decides = false`) or or (`true`), the one spelling of the
    * rule (ADR 0035): `next` is read while `more` holds, stopping at the item
    * that decides, else `null` if an item had none. Inline, so a caller's item
    * reads expand in place and allocate nothing.
    */
  private[view] inline def kleene(decides: Boolean)(inline more: Boolean)(
      inline next: Any
  ): Any =
    var unknown = false
    var decided = false
    while (!decided && more) {
      next match
        case b: java.lang.Boolean => decided = b.booleanValue == decides
        case _                    => unknown = true
    }
    if (decided) decides else if (unknown) null else !decides

  /** The operand's type picks the comparison, as CEL's checker picks an
    * overload: a number literal on the number line, `eq` included, a text side
    * parsed and not holding when it is no number (HA's `numeric_state`); a text
    * literal as text; a bool literal as equality; between two reads, two
    * numbers on the number line, anything else as text, ordering numeric when
    * both parse.
    */
  private def compare(
      value: Any,
      operand: Any,
      op: Op,
      to: Value.Operand
  ): Boolean =
    to match
      // `ne` is not `eq`, as in CEL, where values of two types are unequal.
      case Value.Literal(Lit.Whole(_) | Lit.Real(_)) =>
        if (op == Op.Ne) !number(value).exists(_ == dbl(operand))
        else number(value).exists(onLine(_, dbl(operand), op))
      case Value.Literal(Lit.Bool(_)) =>
        op match
          case Op.Eq => text(value) == text(operand)
          case Op.Ne => text(value) != text(operand)
          case _     => false
      case Value.Literal(Lit.Text(_)) =>
        textCompare(text(value), text(operand), op)
      case _: Value.Entity =>
        (value, operand) match
          case (l: java.lang.Number, r: java.lang.Number) =>
            onLine(l.doubleValue, r.doubleValue, op)
          case _ => textCompare(text(value), text(operand), op)

  // Equality as text; ordering numeric when both parse, else false.
  private def textCompare(lhs: String, rhs: String, op: Op): Boolean =
    op match
      case Op.Eq => lhs == rhs
      case Op.Ne => lhs != rhs
      case _     =>
        (lhs.toDoubleOption, rhs.toDoubleOption) match
          case (Some(l), Some(r)) => onLine(l, r, op)
          case _                  => false

  private def onLine(l: Double, r: Double, op: Op): Boolean = op match
    case Op.Eq  => l == r
    case Op.Ne  => l != r
    case Op.Lt  => l < r
    case Op.Lte => l <= r
    case Op.Gt  => l > r
    case Op.Gte => l >= r

  /** As CEL's `double()` accepts; anything else is no number. */
  def number(v: Any): Option[Double] = v match
    case n: java.lang.Number => Some(n.doubleValue)
    case s: String           => s.toDoubleOption
    case _                   => None

  /** [[number]] defined, without its `Option`. */
  private def isNum(v: Any): Boolean = v match
    case _: java.lang.Number => true
    case s: String           => s.toDoubleOption.isDefined
    case _                   => false

  private def dbl(v: Any): Double = v match
    case n: java.lang.Number => n.doubleValue
    case s: String           => s.toDouble
    case _                   => Double.NaN

  // As CEL's `math.round`.
  private def roundAway(d: Double): Double =
    BigDecimal(d).setScale(0, BigDecimal.RoundingMode.HALF_UP).toDouble
}
