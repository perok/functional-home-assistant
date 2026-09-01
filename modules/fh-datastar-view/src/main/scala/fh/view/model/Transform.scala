package fh.view.model

import fh.view.runtime.{Cel, EntityState}
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredDecoder}

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
    * tier beside it (ADR 0027, ADR 0028). Opted into EXPLICITLY: a slot carries
    * a [[Simple]] value in its `simple` field ([[SlotSource]]), and that field
    * — not any recognition of expression spelling — is the whole tier
    * selection. There is no recognition machinery: a CEL string is engine work,
    * a Simple value is fast-path work, and nothing infers one from the other.
    *
    * An engine charges for being an engine: a general evaluator converts the
    * entity into its own value model on every evaluation. On the renderer's
    * warm path one evaluation of these shapes costs ~0.9 kB of cel-java's
    * planner runtime (`benchmarks/RenderBench.cel`), against ~45 B for a direct
    * read (`benchmarks/RenderBench.direct`) — so every shape the library ships
    * that CAN be read as data, should be.
    *
    * Each case is DEFINED as its idiomatic CEL spelling, documented below; the
    * parity suite in TransformSuite evaluates that spelling through the engine
    * and asserts byte-equality with [[runSimple]] over the hostile sweep, so
    * the fast path is a faithful implementation of the documented expression,
    * never a second answer. The fill colour and more-info's comprehension stay
    * on the engine — they genuinely need the language.
    *
    * The set is CLOSED: atomic forms over one read. Anything beyond it — a
    * second operator, rounding, cross-entity reads — is CEL, explicitly, which
    * is what keeps this from growing back into a micro-language.
    */
  enum Simple {

    /** The entity's raw state string. Idiomatic CEL: `state`. */
    case State

    /** A guarded attribute read, stringified. Idiomatic CEL:
      * `'name' in attr ? attr['name'] : null` — a null result renders `""`, so
      * the absent attribute IS the empty string (the slot's `default` then
      * applies). Presence is part of the structure; an author can never write
      * an unguarded read.
      */
    case Attr(name: String)

    /** A guarded attribute read falling back to the entity id — the "name"
      * shape. Idiomatic CEL: `('name' in attr ? attr['name'] : entity_id)`.
      */
    case AttrOrId(name: String)

    /** The state with the entity's own unit appended when it has one. Idiomatic
      * CEL: `'name' in attr ? state + ' ' + attr['name'] : state`. A unit that
      * is present but not a String is treated as absent — the documented
      * divergence from the engine, which would error on `' ' + nonString`
      * (pinned in the parity suite's divergence table).
      */
    case UnitSuffix(name: String)

    /** A literal prefix on the state. Idiomatic CEL: `'literal' + state`. */
    case Prefix(literal: String)

    /** A literal suffix on the state. Idiomatic CEL: `state + 'literal'`. */
    case Suffix(literal: String)

    /** The two-armed state enum. Idiomatic CEL:
      * `state == 'eq' ? 'thenValue' : 'otherwise'`.
      */
    case Enum(equalTo: String, thenValue: String, otherwise: String)

    /** An attribute as a percentage of a range, rounded half-away-from-zero —
      * the same rounding the engine's `math.round` applies. Idiomatic CEL:
      * `cel.bind(v, 'name' in attr ? attr['name'] : null, v != null ?
      * str(math.round((double(v) - min) * 100.0 / (max - min))) + ' %' : '0
      * %')`. The numeric domain mirrors the engine's `double()` — a number, or
      * a string that parses as one — so a string-numbered attribute renders the
      * same bytes both ways. Absent, or present and unparseable, renders `0 %`
      * — the documented divergence from the engine, which would error on
      * `double(text)` (pinned in the parity suite's divergence table).
      */
    case Percent(name: String, min: Double, max: Double)

    /** An attribute as the slider's remaining fill — the complement of
      * [[Percent]] for a right-anchored track. Idiomatic CEL: `str(cel.bind(v,
      * 'name' in attr ? attr['name'] : null, v != null ? 100.0 - ((double(v) -
      * min) * 100.0 / (max - min)) : 100.0)) + '%'`. Absent, or present and
      * unparseable as a number, renders `100%` (same divergence note as
      * [[Percent]]).
      */
    case Fill(name: String, min: Double, max: Double)
  }

  object Simple {

    /** The numeric domain of [[Simple.Percent]]/[[Simple.Fill]], mirroring the
      * engine's `double()` overloads: a JVM number, or a string that parses as
      * one. Anything else (a boolean, a list, an unparseable string) is not a
      * number — the case renders its absent-value form, the documented
      * divergence from the engine's error.
      */
    private[Transform] def num(v: Any | Null): Option[Double] = v match {
      case n: java.lang.Number => Some(n.doubleValue)
      case s: String           => scala.util.Try(s.toDouble).toOption
      case _                   => None
    }

    /** The wire `kind` names. `AttrOrId`/`UnitSuffix` spell deliberately (camel
      * / the shorter "unit"); the rest lowercase themselves.
      */
    given Configuration =
      Configuration.default.withDefaults
        .withDiscriminator("kind")
        .withTransformConstructorNames {
          case "AttrOrId"   => "attrOrId"
          case "UnitSuffix" => "unit"
          case other        => other.toLowerCase
        }

    given Decoder[Simple] = ConfiguredDecoder.derived

    /** A stable, injective KEY for one Simple value — the transform's identity
      * wherever the renderer keys by transform (signal names, the once-cache).
      * Structure, not spelling: two structures with equal fields share a key,
      * different structures never collide (the kind prefix is disjoint).
      */
    def key(s: Simple): String = s match {
      case Simple.State            => "state"
      case Simple.Attr(n)          => s"attr:$n"
      case Simple.AttrOrId(n)      => s"attrOrId:$n"
      case Simple.UnitSuffix(n)    => s"unit:$n"
      case Simple.Prefix(lit)      => s"prefix:$lit"
      case Simple.Suffix(lit)      => s"suffix:$lit"
      case Simple.Enum(eq, t, o)   => s"enum:$eq:$t:$o"
      case Simple.Percent(n, a, b) => s"percent:$n:$a:$b"
      case Simple.Fill(n, a, b)    => s"fill:$n:$a:$b"
    }
  }

  /** Evaluate a [[Simple]] shape without the engine, stringified exactly as
    * [[run]] renders the idiomatic CEL — the SAME rendering, so the parity
    * suite can hold the two to byte-equality over the sweep. TOTAL: a value the
    * shape cannot model a number or string out of (a non-numeric position, a
    * non-string unit) renders the absent-value form, the divergence the case's
    * scaladoc documents and the suite pins — there is no engine fallback; the
    * opted-in tier owns its values.
    */
  def runSimple(s: Simple, entity: EntityState): String =
    s match {
      case Simple.State      => entity.state
      case Simple.Attr(name) =>
        asString(entity.javaAttributes.get(name))
      case Simple.AttrOrId(name) =>
        val v = entity.javaAttributes.get(name)
        if v == null then entity.entityId else asString(v)
      case Simple.UnitSuffix(name) =>
        entity.javaAttributes.get(name) match {
          case u: String => entity.state + " " + u
          case _         => entity.state
        }
      case Simple.Prefix(lit)                => lit + entity.state
      case Simple.Suffix(lit)                => entity.state + lit
      case Simple.Enum(eq, thenV, otherwise) =>
        if entity.state == eq then thenV else otherwise
      case Simple.Percent(name, min, max) =>
        Simple.num(entity.javaAttributes.get(name)) match {
          case Some(v) =>
            numToString(roundAway((v - min) * 100.0 / (max - min))) + " %"
          case None => "0 %"
        }
      case Simple.Fill(name, min, max) =>
        Simple.num(entity.javaAttributes.get(name)) match {
          case Some(v) =>
            numToString(100.0 - (v - min) * 100.0 / (max - min)) + "%"
          case None => "100%"
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
      // `java.math.BigDecimal.valueOf`, not `scala.math.BigDecimal(d)`: the
      // Scala one wraps the same `Double.toString` parse in a `BigDecimal`
      // object carrying a `MathContext`, and every fractional slot value on the
      // page pays for both. Byte-identical — `DECIMAL128` keeps 34 significant
      // digits and a `Double` has at most 17, so the context never rounds
      // anything a `setScale(10)` would not; checked over 500k doubles and
      // pinned by the cases in `TransformSuite`.
      java.math.BigDecimal
        .valueOf(d)
        .setScale(10, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros
        .toPlainString
}
