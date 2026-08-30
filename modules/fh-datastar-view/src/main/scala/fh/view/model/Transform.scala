package fh.view.model

import fh.view.runtime.{Cel, EntityState}

/** Per-slot value transforms, expressed as [CEL](https://cel.dev) (the "Common
  * Expression Language").
  *
  * Authored inline in the dashboard source as a string (see
  * [[SlotSource.transform]]) and evaluated by the renderer per live value.
  *
  * A transform reads the entity through bound variables — `state` (its raw
  * state String), `attr` (its full attribute map, indexed as
  * `attr['unit_of_measurement']`), `entity_id` and `domain` (from the id); a
  * String state coerces for arithmetic with the registered `num()` helper
  * (`int()`/`double()` cover the rare numeric attribute). Only this entity is
  * reachable — lookups are same-entity only — and the slot value is whatever
  * the expression returns. Examples:
  *
  *   - `str(math.round(num(state) * 10.0) / 10.0) + ' V'` — round to one
  *     decimal, append a unit
  *   - `state + ('unit_of_measurement' in attr ? ' ' + attr['unit_of_measurement'] : '')`
  *     — append the entity's own unit only when it has one
  *   - `'brightness' in attr ? str(math.round((double(attr['brightness']) - 1.0) * 100.0 / 254.0)) + ' %' : '0 %'`
  *     — position as a percentage of the slider's baked min..max range
  *   - `state == 'on' ? 'Open' : 'Closed'` — map a state to display text
  *   - `"@post('sse/action/\" + dashboard_slug + \"/\" + 'light/toggle' + \"/\" + entity_id + \"')"`
  *     — an identity-derived action URL (a tap), reading no live state
  *
  * Presence is a REAL boolean in CEL, and the entity's `attr` is a JVM map
  * adapted as a CEL map (not a native one): `'x' in attr` tests a key's
  * presence, while a RAW `attr['x']` on an absent key is an evaluation error —
  * so the shipped strings read attributes guarded (`'x' in attr ? … : …`), the
  * idiom that mirrors JSONata's null-on-missing (measured in the Phase-0
  * sweep). Stringify a heterogeneous value with `str(x)`, which renders numbers
  * the same 10-digit way the engine renders a bare numeric result, so the two
  * can never drift.
  *
  * Compilation happens once at build/validate time; the renderer reuses the
  * compiled program. A failing evaluation is **not** swallowed nor allowed to
  * crash the render — the card shows the CEL error message, contained to that
  * one card, so a genuinely broken expression is visible. (For
  * unavailable/unknown entities the renderer shows the raw state and skips the
  * transform by default — `SlotSource.bypassUnavailable`, ON unless an action /
  * label / slider position opts out — see `EntityState.unavailable`.) A `null`
  * result becomes `""` (the slot's `default` then applies).
  *
  * Takes an [[EntityState]], which carries the entity's identity (`entityId`,
  * and `domain` derived from it) alongside its live `state`/`attributes`, so
  * the `entity_id`/`domain` bindings come straight off the fetched state rather
  * than being recomputed here.
  */
object Transform {

  /** A compiled CEL program (see [[Cel]]). */
  type Compiled = Cel.Program

  /** The closed set of transform shapes evaluated WITHOUT the engine — the fast
    * tier beside it (ADR 0027, plan Phase 2).
    *
    * An engine charges for being an engine: a general evaluator converts the
    * entity into its own value model on every evaluation. On the renderer's
    * warm path one evaluation of the six shipped shapes costs ~0.9 kB of
    * cel-java's planner runtime (`benchmarks/RenderBench.cel`), against ~45 B
    * for a direct read (`benchmarks/RenderBench.direct`) — so every shape the
    * library ships that CAN be read as data, should be. The set is closed over
    * the CEL-canonical strings the Pkl library bakes: the raw `state` read, the
    * guarded attribute read, the fallback-to-id name, the unit suffix, a
    * literal prefix/suffix, the state enum, and the slider's range percent and
    * fill (the spliced min/max literals ride IN the string, so recognition
    * parses them out). The fill colour and more-info's comprehension stay on
    * the engine — they genuinely need the language.
    *
    * The catalog is a RECOGNITION over those strings, never a second
    * implementation of the language: [[runSimple]] returns `None` for any value
    * it cannot model (a non-numeric position, a non-string unit) and
    * [[Transforms.run]] falls back to the engine, whose bytes — error text
    * included — always win. A parity battery in TransformSuite runs each
    * recognized form both ways over the hostile sweep, so the fast path is only
    * ever an optimisation, never a different answer.
    */
  enum Simple {
    case State
    case Attr(name: String)
    case AttrOrId(name: String)
    case UnitSuffix(name: String)
    case Prefix(literal: String)
    case Suffix(literal: String)
    case Enum(equalTo: String, thenValue: String, otherwise: String)
    case Percent(name: String, min: Double, max: Double)
    case Fill(name: String, min: Double, max: Double)
  }

  // The canonical strings, one anchored shape each. The attribute name is
  // matched twice (in the presence test and the read) and must agree; the
  // range literals are the Pkl-spliced float forms (`1.0`, `255.0`).
  private val AttrShape =
    """^'([^']+)' in attr \? attr\['([^']+)'\] : null$""".r
  private val AttrOrIdShape =
    """^\('([^']+)' in attr \? attr\['([^']+)'\] : entity_id\)$""".r
  private val UnitSuffixShape =
    """^state \+ \('([^']+)' in attr \? ' ' \+ attr\['([^']+)'\] : ''\)$""".r
  private val PrefixShape = """^'([^']*)' \+ state$""".r
  private val SuffixShape = """^state \+ '([^']*)'$""".r
  private val EnumShape = """^state == '([^']*)' \? '([^']*)' : '([^']*)'$""".r
  private val PercentShape =
    """^cel\.bind\(v, '([^']+)' in attr \? attr\['([^']+)'\] : null, v != null \? str\(math\.round\(\(double\(v\) - ([0-9]+\.[0-9]+)\) \* 100\.0 / \(([0-9]+\.[0-9]+) - ([0-9]+\.[0-9]+)\)\)\) \+ ' %' : '0 %'\)$""".r
  private val FillShape =
    """^str\(cel\.bind\(v, '([^']+)' in attr \? attr\['([^']+)'\] : null, v != null \? 100\.0 - \(\(double\(v\) - ([0-9]+\.[0-9]+)\) \* 100\.0 / \(([0-9]+\.[0-9]+) - ([0-9]+\.[0-9]+)\)\) : 100\.0\)\) \+ '%'$""".r

  /** Recognise a [[Simple]] shape. Deliberately strict: the trimmed string must
    * be one of the canonical forms byte-for-byte (a double-quoted literal, a
    * bare attr read, a second operator — anything else at all — is engine work,
    * and this must never grow into a second implementation of the language).
    * The range literals must parse as the floats Pkl splices, with a
    * non-degenerate range.
    */
  def simple(src: String): Option[Simple] = src.trim match {
    case "state"                                 => Some(Simple.State)
    case AttrShape(guard, read) if guard == read =>
      Some(Simple.Attr(guard))
    case AttrOrIdShape(guard, read) if guard == read =>
      Some(Simple.AttrOrId(guard))
    case UnitSuffixShape(guard, read) if guard == read =>
      Some(Simple.UnitSuffix(guard))
    case PrefixShape(lit)                => Some(Simple.Prefix(lit))
    case SuffixShape(lit)                => Some(Simple.Suffix(lit))
    case EnumShape(eq, thenV, otherwise) =>
      Some(Simple.Enum(eq, thenV, otherwise))
    case PercentShape(guard, read, min, max, min2)
        if guard == read && min == min2 =>
      range(min, max).map(Simple.Percent(guard, _, _))
    case FillShape(guard, read, min, max, min2)
        if guard == read && min == min2 =>
      range(min, max).map(Simple.Fill(guard, _, _))
    case _ => None
  }

  private def range(min: String, max: String): Option[(Double, Double)] = {
    val (dmin, dmax) = (min.toDouble, max.toDouble)
    // A degenerate range divides by zero; the engine would render the IEEE
    // result ("Infinity"/"NaN") and so could the fast path, but no shipped
    // shape can produce one — treat it as not-a-shape rather than model it.
    if (!dmin.isNaN && !dmax.isNaN && dmax != dmin) Some((dmin, dmax)) else None
  }

  /** Evaluate a [[Simple]] shape without the engine, stringified exactly as
    * [[run]] would — the SAME rendering, so the two cannot drift in how they
    * render a state, a number, a list or an absent value. `None` for a value
    * the shape cannot model (a non-numeric position, a non-string unit): the
    * caller falls back to the engine, whose bytes — error text included — win.
    */
  def runSimple(s: Simple, entity: EntityState): Option[String] =
    s match {
      case Simple.State      => Some(entity.state)
      case Simple.Attr(name) =>
        Some(asString(entity.javaAttributes.get(name)))
      case Simple.AttrOrId(name) =>
        val v = entity.javaAttributes.get(name)
        Some(if v == null then entity.entityId else asString(v))
      case Simple.UnitSuffix(name) =>
        entity.javaAttributes.get(name) match {
          case u: String => Some(entity.state + " " + u)
          case null      => Some(entity.state)
          case _ => None // the engine errors on `' ' + nonString`; let it
        }
      case Simple.Prefix(lit)                => Some(lit + entity.state)
      case Simple.Suffix(lit)                => Some(entity.state + lit)
      case Simple.Enum(eq, thenV, otherwise) =>
        Some(if entity.state == eq then thenV else otherwise)
      case Simple.Percent(name, min, max) =>
        entity.javaAttributes.get(name) match {
          case null                => Some("0 %")
          case n: java.lang.Number =>
            Some(
              numToString(
                roundAway((n.doubleValue - min) * 100.0 / (max - min))
              ) +
                " %"
            )
          case _ => None // `double(nonNumeric)` errors in the engine; let it
        }
      case Simple.Fill(name, min, max) =>
        entity.javaAttributes.get(name) match {
          case null                => Some("100%")
          case n: java.lang.Number =>
            Some(
              numToString(
                100.0 - (n.doubleValue - min) * 100.0 / (max - min)
              ) + "%"
            )
          case _ => None
        }
    }

  /** [[math.round]]'s away-from-zero, as a Double — the rounding the engine's
    * `math.round` applies before `str` renders it.
    */
  private def roundAway(d: Double): Double =
    BigDecimal(d).setScale(0, BigDecimal.RoundingMode.HALF_UP).toDouble

  /** Compile a CEL expression (build/validate time). */
  def parse(src: String): Either[String, Compiled] = Cel.parse(src)

  /** Evaluate a compiled program against one entity, stringified for the
    * template. Binds the entity's full context — `state`/`attr` (its live
    * value) and `entity_id`/`domain` (its identity, from the id) — so the same
    * mechanism serves value slots and identity-derived slots (e.g. a tap
    * action) — plus `dashboard_slug`, the only binding that is not about the
    * entity. On evaluation failure, returns the CEL error message so the card
    * shows it (contained — never throws into the render). See [[Cel]].
    */
  def run(expr: Compiled, entity: EntityState, dashboardSlug: String): String =
    Cel.run(expr, entity, dashboardSlug)

  // (The attribute JSON -> Java conversion lives on EntityState.javaAttributes,
  // cached per state version, so it runs once per entity rather than per eval.)

  // Direct-result rendering, kept byte-identical to `Cel.stringify` — the fast
  // path is only sound while it renders exactly what the engine would. Numbers
  // render via the same 10-digit numToString, so a bare `state`-adjacent value
  // never shows more precision than the engine.
  private def asString(result: Any): String =
    result match
      case null                 => ""
      case s: String            => s
      case b: java.lang.Boolean => b.toString
      case n: java.lang.Long    => n.toString
      case n: java.lang.Integer => n.toString
      case n: java.lang.Number  => numToString(n.doubleValue)
      case other                => String.valueOf(other)

  private def numToString(d: Double): String =
    if (d.isNaN || d.isInfinite) d.toString
    else if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong.toString
    else
      BigDecimal(d)
        .setScale(10, BigDecimal.RoundingMode.HALF_UP)
        .bigDecimal
        .stripTrailingZeros
        .toPlainString
}
