package fh.view.model

import fh.view.history.ChartStyle
import fh.view.runtime.{Cel, EntityState}
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredDecoder}

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

  /** The engine-free tier (ADR 0027, 0028), selected by form alone: a slot
    * holding a `Simple` object is fast-path work, a CEL string engine work, and
    * nothing infers one from the other.
    *
    * A shape belongs here when it is a static lookup and TOTAL over every value
    * a live entity can produce — the load-bearing half, since
    * `double('unknown')` would put an error on a wall panel. Speed is the
    * other: ~0.9 kB per CEL evaluation against ~45 B for a direct read
    * (`RenderBench.cel` vs `direct`).
    *
    * Each case is defined by the idiomatic CEL below; TransformSuite holds
    * [[runSimple]] byte-equal to it over a hostile sweep, and pins the few
    * divergences where CEL would error. Closed: anything more is CEL.
    *
    * On the wire `kind` names the shape, `value` or `match`, and a value's `op`
    * names its operator: the [[Simple.Op]] case, lower-camel.
    */
  sealed trait Simple derives CanEqual

  object Simple {

    enum Op extends Simple {

      /** CEL: `state`. */
      case State

      /** CEL: `attr[?'attribute']`. Absent renders `""`. */
      case Attr(attribute: String)

      /** CEL: `state + attr[?'attribute'].optMap(u, ' ' + u).orValue('')`. A
        * non-String unit counts as absent (CEL would error).
        */
      case SuffixUnit(attribute: String)

      /** CEL: `'literal' + state`. */
      case Prefix(literal: String)

      /** CEL: `state + 'literal'`. */
      case Suffix(literal: String)

      /** CEL: `attr[?'attribute'].optMap(v, str(math.round((double(v) - min) *
        * 100.0 / (max - min))) + ' %').orValue('0 %')`, rounding half away from
        * zero like `math.round`. Numbers or numeric strings, as `double()`
        * takes; unparseable renders `0 %` (CEL would error).
        */
      case Percent(attribute: String, min: Double, max: Double)

      /** CEL: `attr[?'attribute'].optMap(v, str(100.0 - ((double(v) - min) *
        * 100.0 / (max - min)))).orValue('100') + '%'`, for a right-anchored
        * track. Unparseable renders `100%`.
        */
      case Fill(attribute: String, min: Double, max: Double)

      /** CEL:
        * {{{
        * cel.bind(t, int(math.round(double(state) * scale)),
        *   t <= 0 ? '0s'
        *   : t >= 3600 ? str(t / 3600) + 'h ' + str(t % 3600 / 60) + 'm'
        *   : t >= 60 ? str(t / 60) + 'm'
        *   : str(t) + 's')
        * }}}
        *
        * `scale` is seconds per unit: HA's `duration` class fixes no unit (45
        * from one appliance, 2700 from another). Coarser than the reading on
        * purpose — appliances update slowly. Unparseable renders `""`: a
        * dishwasher between programmes reports `unknown`, and `0s` would claim
        * it just finished.
        */
      case Duration(scale: Double)
    }

    /** CEL: `cel.bind(m, {'k': 'v', …}, state in m ? m[state] : 'otherwise')`.
      *
      * A map, so duplicate keys and first-match questions are unrepresentable.
      * Values may be booleans, the only thing that turns a boolean attribute
      * off; CEL requires one type across them. `otherwise` is required: the
      * runtime does not know a domain's states, and HA adds new ones, so an
      * unmatched state must degrade rather than blank a panel.
      */
    final case class Match(cases: Map[String, SlotValue], otherwise: SlotValue)
        extends Simple

    export Op.{State, Attr, SuffixUnit, Prefix, Suffix, Percent, Fill, Duration}

    /** As CEL's `double()` accepts; anything else is the absent form. */
    private[Transform] def num(v: Any | Null): Option[Double] = v match {
      case n: java.lang.Number => Some(n.doubleValue)
      case s: String           => scala.util.Try(s.toDouble).toOption
      case _                   => None
    }

    // Boolean first: the narrower type goes first.
    private given Decoder[SlotValue] =
      Decoder[Boolean]
        .map(b => b: SlotValue)
        .or(Decoder[String].map(s => s: SlotValue))

    private given Decoder[Op] =
      ConfiguredDecoder.derived(using
        Configuration.default.withDiscriminator("op")
      )

    // By hand only to pick the level: circe's derivation flattens a nested
    // sum to its innermost case, so it cannot read `kind` and then `op`.
    given Decoder[Simple] = Decoder[String].at("kind").flatMap {
      case "Op"    => Decoder[Op].map(o => o: Simple)
      case "Match" =>
        ConfiguredDecoder
          .derived[Match](using Configuration.default)
          .map(m => m: Simple)
      case other => Decoder.failedWithMessage(s"unknown simple kind `$other`")
    }

    /** Injective, for signal names and the once-cache. */
    def key(s: Simple): String = s match {
      case Simple.State            => "state"
      case Simple.Attr(n)          => s"attr:$n"
      case Simple.SuffixUnit(n)    => s"unit:$n"
      case Simple.Prefix(lit)      => s"prefix:$lit"
      case Simple.Suffix(lit)      => s"suffix:$lit"
      case m: Simple.Match         => matchKey(m)
      case Simple.Percent(n, a, b) => s"percent:$n:$a:$b"
      case Simple.Fill(n, a, b)    => s"fill:$n:$a:$b"
      case Simple.Duration(scale)  => s"duration:$scale"
    }

    /** Length-prefixed: a match has variable arity, and a key holding the
      * separator could forge another map — two transforms sharing a signal.
      */
    private def matchKey(m: Simple.Match): String = {
      def sized(s: String) = s"${s.length}:$s"
      // `true` and `"true"` are different transforms.
      def value(v: SlotValue) = v match
        case s: String  => sized(s)
        case b: Boolean => s"b:$b"
      val body = m.cases.toSeq
        .sortBy(_._1)
        .map((k, v) => sized(k) + value(v))
        .mkString
      s"match:${m.cases.size}:$body${value(m.otherwise)}"
    }
  }

  /** Rendered exactly as [[run]] renders the equivalent CEL. No engine
    * fallback.
    */
  def runSimple(s: Simple, entity: EntityState): String =
    SlotValue.text(runSimpleValue(s, entity))

  /** Keeps a [[Simple.Match]] arm's boolean. */
  def runSimpleValue(s: Simple, entity: EntityState): SlotValue =
    s match {
      case Simple.State      => entity.state
      case Simple.Attr(name) =>
        Cel.stringify(entity.javaAttributes.get(name))
      case Simple.SuffixUnit(name) =>
        entity.javaAttributes.get(name) match {
          case u: String => entity.state + " " + u
          case _         => entity.state
        }
      case Simple.Prefix(lit)             => lit + entity.state
      case Simple.Suffix(lit)             => entity.state + lit
      case Simple.Match(cases, otherwise) =>
        cases.getOrElse(entity.state, otherwise)
      case Simple.Percent(name, min, max) =>
        Simple.num(entity.javaAttributes.get(name)) match {
          case Some(v) =>
            Cel.numToString(roundAway((v - min) * 100.0 / (max - min))) + " %"
          case None => "0 %"
        }
      case Simple.Fill(name, min, max) =>
        Simple.num(entity.javaAttributes.get(name)) match {
          case Some(v) =>
            Cel.numToString(100.0 - (v - min) * 100.0 / (max - min)) + "%"
          case None => "100%"
        }
      case Simple.Duration(scale) =>
        Simple.num(entity.state) match {
          case Some(v) =>
            val t = roundAway(v * scale).toLong
            if (t <= 0) "0s"
            else if (t >= 3600) s"${t / 3600}h ${t % 3600 / 60}m"
            else if (t >= 60) s"${t / 60}m"
            else s"${t}s"
          case None => ""
        }
    }

  // As CEL's `math.round`.
  private def roundAway(d: Double): Double =
    BigDecimal(d).setScale(0, BigDecimal.RoundingMode.HALF_UP).toDouble

  /** A CEL source and the typed names it compiles against: the node's
    * expression values it reads, empty for nearly every slot.
    */
  case class CelKey(src: String, env: List[(String, ExprValue.Kind)] = Nil)
      derives CanEqual

  def parse(src: String): Either[String, Compiled] = Cel.parse(src)

  def parse(key: CelKey): Either[String, Compiled] = Cel.parse(key.src, key.env)

  def yieldsBool(key: CelKey): Boolean = Cel.yieldsBool(key.src, key.env)

  def isBoolTyped(key: CelKey): Boolean = Cel.isBoolTyped(key.src, key.env)

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

  /** How a fetch's answer becomes the hole's content. Not a [[Simple]]: a chart
    * is not a total static lookup.
    */
  enum Stage derives CanEqual {

    /** Spelled on the wire, because the Pkl default derives it. */
    case Passthrough

    /** Typed on the wire, unlike a fetch's params (ADR 0031). */
    case Chart(params: ChartStyle = ChartStyle())
  }

  object Stage {

    def key(s: Stage): String = s match {
      case Stage.Passthrough  => "passthrough"
      case Stage.Chart(style) => s"chart:$style"
    }

    private given Configuration =
      Configuration.default.withDefaults.withDiscriminator("stage")

    given Decoder[Stage] = ConfiguredDecoder.derived
  }

}
