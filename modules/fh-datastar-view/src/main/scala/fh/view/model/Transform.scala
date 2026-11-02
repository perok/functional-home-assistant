package fh.view.model

import fh.view.history.ChartStyle
import fh.view.runtime.{Cel, EntityState}

/** Slot transforms in [CEL](https://cel.dev), over `state`, `attr`,
  * `entity_id`, `domain`, `dashboard_slug` and `time_zone`; only the slot's own
  * entity is reachable, plus what the node's expression values say about others
  * (a count, as a number). `attr` is an adapted JVM map, so a raw `attr['x']`
  * on an absent key is an error: reads are guarded with `attr[?'x']`. `str(x)`
  * renders numbers exactly as the engine renders a bare numeric result.
  *
  * Compiled once at validation. A failing evaluation renders its error message
  * in that one card; `null` renders `""`.
  */
object Transform {

  type Compiled = Cel.Program

  /** The engine-free tier (ADR 0028), selected by form alone: a source read and
    * its [[Step.Format]]s are fast-path work, a [[Step.Cel]] engine work, and
    * nothing infers one from the other. A [[Predicate]] source is its bool
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

  /** Text as CEL renders a bare result: `str(x)`. */
  def text(v: Any): String = v match
    case s: String => s
    case null      => ""
    case other     => Cel.stringify(other)

  /** One format step over `value`, an operand read by `operand`. */
  def format(step: Step.Format, value: Any, operand: Value => Any): SlotValue =
    step match
      case Step.Prefix(t)   => text(operand(t)) + text(value)
      case Step.Suffix(t)   => text(value) + text(operand(t))
      case Step.WithUnit(u) =>
        val v = text(value)
        operand(u) match
          case unit: String if v.nonEmpty => v + " " + unit
          case _                          => v
      case Step.Percent(lo, hi) =>
        (num(value), num(operand(lo)), num(operand(hi))) match
          case (Some(v), Some(min), Some(max)) =>
            Cel.numToString(roundAway((v - min) * 100.0 / (max - min))) + " %"
          case _ => "0 %"
      case Step.Fill(lo, hi) =>
        (num(value), num(operand(lo)), num(operand(hi))) match
          case (Some(v), Some(min), Some(max)) =>
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
      case Step.Match(cases, otherwise) =>
        cases.get(text(value)) match
          case Some(Lit.Bool(b)) => b
          case Some(lit)         => text(lit.raw)
          case None              =>
            operand(otherwise) match
              case b: Boolean => b
              case other      => text(other)

  /** As CEL's `double()` accepts; anything else is the absent form. */
  private def num(v: Any): Option[Double] = v match
    case n: java.lang.Number => Some(n.doubleValue)
    case s: String           => s.toDoubleOption
    case _                   => None

  // As CEL's `math.round`.
  private def roundAway(d: Double): Double =
    BigDecimal(d).setScale(0, BigDecimal.RoundingMode.HALF_UP).toDouble

  /** A CEL source and the typed names it compiles against: the node's
    * expression values it reads, empty for nearly every slot, and whether it
    * has an entity to read (`state`, `attr`, `entity_id`, `domain`).
    */
  case class CelKey(
      src: String,
      env: List[(String, ExprValue.Kind)] = Nil,
      entity: Boolean = true
  )

  def parse(src: String): Either[String, Compiled] = Cel.parse(src)

  def parse(key: CelKey): Either[String, Compiled] =
    Cel.parse(key.src, key.env, key.entity)

  def yieldsBool(key: CelKey): Boolean =
    Cel.yieldsBool(key.src, key.env, key.entity)

  def isBoolTyped(key: CelKey): Boolean =
    Cel.isBoolTyped(key.src, key.env, key.entity)

  def nonTextType(key: CelKey): Option[String] =
    Cel.nonTextType(key.src, key.env, key.entity)

  /** Never throws into the render: a failure returns its message. */
  def run(expr: Compiled, entity: EntityState, dashboardSlug: String): String =
    Cel.run(expr, entity, dashboardSlug)

  def runValue(
      expr: Compiled,
      entity: EntityState,
      dashboardSlug: String,
      values: Map[String, Object] = Map.empty
  ): SlotValue =
    Cel.runValue(expr, entity, dashboardSlug, values)

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
