package fh.view.model

import fh.view.history.ChartStyle
import fh.view.runtime.{Cel, Conditions, EntityState}

/** Slot transforms in [CEL](https://cel.dev), over `value` (the value before
  * the step), the named inputs, `dashboard_slug` and `time_zone`. A whole
  * entity is the struct `fh.Entity {state, attr, entity_id, domain}`; its
  * `attr` is an adapted JVM map, so a raw `attr['x']` on an absent key is an
  * error: reads are guarded with `attr[?'x']`. `str(x)` renders numbers exactly
  * as the engine renders a bare numeric result.
  *
  * Compiled once at validation. A failing evaluation renders its error message
  * in that one card; `null` renders `""`.
  */
object Transform {

  type Compiled = Cel.Program

  /** The engine-free tier (ADR 0028), selected by form alone: a source read and
    * its [[Step.Simple]] steps are fast-path work, a [[Step.Cel]] engine work,
    * and nothing infers one from the other. A [[Predicate]] source is its bool
    * half, so a condition fills a slot as directly as it decides a `c.iff`.
    *
    * A step belongs here when it is a static lookup and TOTAL over every value
    * a live entity can produce — the load-bearing half, since
    * `double('unknown')` would put an error on a wall panel. Speed is the
    * other: ~0.9 kB per CEL evaluation against ~45 B for a direct read
    * (`RenderBench.cel` vs `direct`). Where a step has a CEL spelling
    * (documented on its case), TransformSuite holds [[format]] byte-equal to it
    * over a hostile sweep and pins the few divergences where CEL would error.
    */
  def read(part: Part, entity: EntityState): Any = part match
    case Part.State      => entity.state
    case Part.Attr(name) => entity.javaAttributes.get(name)
    case Part.Whole      => entity.celValue

  /** Text as CEL renders a bare result: `str(x)`. */
  def text(v: Any): String = v match
    case s: String => s
    case null      => ""
    case other     => Cel.stringify(other)

  /** One simple step over `value`, an operand read by `operand`. */
  def format(step: Step.Simple, value: Any, operand: Value => Any): Any =
    val ops = Step.operands(step)
    formatWith(
      step,
      value,
      ops.lift(0).fold(null: Any)(operand),
      ops.lift(1).fold(null: Any)(operand)
    )

  /** [[format]] over its operands already read, in [[Step.operands]] order:
    * what a composed pipeline calls, so a render builds no reader.
    */
  def formatWith(step: Step.Simple, value: Any, a: Any, b: Any): Any =
    step match
      case Step.Count =>
        value match
          case xs: List[?] => xs.size.toLong
          case _           => 0L
      case Step.AllOf =>
        value match
          case xs: List[?] => xs.forall(isTrue)
          case _           => false
      case Step.AnyOf =>
        value match
          case xs: List[?] => xs.exists(isTrue)
          case _           => false
      case Step.Compare(op, _) => Conditions.compare(text(value), text(a), op)
      case Step.Not            => !isTrue(value)
      case Step.Prefix(_)      => text(a) + text(value)
      case Step.Suffix(_)      => text(value) + text(a)
      case Step.WithUnit(_)    =>
        val v = text(value)
        a match
          case unit: String if v.nonEmpty => v + " " + unit
          case _                          => v
      // Operands without an `Option`: this runs per resolve of every slider
      // track, and two `Some`s and their boxes each time showed in RenderBench.
      case Step.Percent(_, _) =>
        num(value) match
          case Some(v) if isNum(a) && isNum(b) =>
            val min = dbl(a)
            val max = dbl(b)
            Cel.numToString(roundAway((v - min) * 100.0 / (max - min))) + " %"
          case _ => "0 %"
      case Step.Fill(_, _) =>
        num(value) match
          case Some(v) if isNum(a) && isNum(b) =>
            val min = dbl(a)
            val max = dbl(b)
            Cel.numToString(100.0 - (v - min) * 100.0 / (max - min)) + "%"
          case _ => "100%"
      case Step.Duration(scale) =>
        num(value) match
          case Some(v) =>
            val t = roundAway(v * scale).toLong
            if (t <= 0) "0s"
            else if (t >= 3600) s"${t / 3600}h ${t % 3600 / 60}m"
            else if (t >= 60) s"${t / 60}m"
            else s"${t}s"
          case None => ""
      case Step.Match(cases, _) =>
        cases.get(text(value)) match
          case Some(Lit.Bool(flag)) => flag
          case Some(lit)            => text(lit.raw)
          case None                 =>
            a match
              case flag: Boolean => flag
              case other         => text(other)

  private def isTrue(v: Any): Boolean = v match
    case b: Boolean => b
    case _          => false

  /** As CEL's `double()` accepts; anything else is the absent form. */
  private def num(v: Any): Option[Double] = v match
    case n: java.lang.Number => Some(n.doubleValue)
    case s: String           => s.toDoubleOption
    case _                   => None

  /** [[num]] defined, and its value. */
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

  /** A CEL source and the typed names it compiles against: the named inputs it
    * reads, empty for nearly every slot, and the type its `value` is declared
    * as, `None` for a CEL source, which has none.
    */
  case class CelKey(
      src: String,
      env: List[(String, ValueType)] = Nil,
      value: Option[ValueType] = Some(ValueType.Entity)
  )

  def parse(src: String): Either[String, Compiled] = parse(CelKey(src))

  def parse(key: CelKey): Either[String, Compiled] =
    Cel.parse(key.src, key.env, key.value)

  /** What it produces, or why it does not compile. */
  def resultType(key: CelKey): Either[String, ValueType] =
    Cel.resultType(key.src, key.env, key.value)

  /** Over a whole entity, as a node variable's start is. Never throws into the
    * render: a failure returns its message.
    */
  def run(expr: Compiled, entity: EntityState, dashboardSlug: String): String =
    SlotValue.text(runValue(expr, entity.celValue, dashboardSlug))

  def runValue(
      expr: Compiled,
      value: Any,
      dashboardSlug: String,
      values: Map[String, Object] = Map.empty
  ): SlotValue =
    Cel.runValue(expr, value, dashboardSlug, values)

  /** How a fetch's answer becomes the hole's content: its [[Step.Chart]], or
    * the answer as JSON when it has none.
    */
  enum Stage derives CanEqual {
    case Passthrough
    case Chart(params: ChartStyle = ChartStyle())
  }

  object Stage {

    def key(s: Stage): String = s match {
      case Stage.Passthrough  => "passthrough"
      case Stage.Chart(style) => s"chart:$style"
    }
  }

}
