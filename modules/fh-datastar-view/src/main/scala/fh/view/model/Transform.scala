package fh.view.model

import fh.view.runtime.{Cel, EntityState}
import io.circe.{Decoder, DecodingFailure}
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
  *   - `state + attr[?'unit_of_measurement'].optMap(u, ' ' + u).orValue('')` —
  *     append the entity's own unit only when it has one
  *   - `attr[?'brightness'].optMap(b, str(math.round((double(b) - 1.0) * 100.0 / 254.0)) + ' %').orValue('0 %')`
  *     — position as a percentage of the slider's baked min..max range
  *   - `state == 'on' ? 'Open' : 'Closed'` — map a state to display text
  *   - `"@post('sse/action/\" + dashboard_slug + \"/\" + 'light/toggle' + \"/\" + entity_id + \"')"`
  *     — an identity-derived action URL (a tap), reading no live state
  *
  * Presence is a REAL boolean in CEL, and the entity's `attr` is a JVM map
  * adapted as a CEL map (not a native one): `'x' in attr` tests a key's
  * presence, while a RAW `attr['x']` on an absent key is an evaluation error —
  * so every read is guarded. CEL's optionals are enabled and are what the
  * shipped strings use: `attr[?'x']` is the guarded read, `.orValue(d)` the
  * inline default, `.optMap(v, …)` when the value is transformed on the way
  * out. An empty optional renders `""`, exactly as a `null` does. The older
  * `'x' in attr ? … : …` ternary means the same thing and still compiles, but
  * nothing here spells it that way any more — the [[Simple]] tier's documented
  * equivalents moved too, and the parity suite proves the two forms agree byte
  * for byte over its hostile sweep. Stringify a heterogeneous value with
  * `str(x)`, which renders numbers the same 10-digit way the engine renders a
  * bare numeric result, so the two can never drift.
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
    * a [[Simple]] value in its `transform` field ([[SlotSource]]) where a CEL
    * string would otherwise sit, and that FORM — not any recognition of
    * expression spelling — is the whole tier selection. There is no recognition
    * machinery: a CEL string is engine work, a Simple value is fast-path work,
    * and nothing infers one from the other.
    *
    * MEMBERSHIP: a shape belongs here when it is a static lookup and TOTAL —
    * decided at build time, and defined over every value a live entity can
    * produce. Totality is the load-bearing half. CEL's `double(state)` errors
    * on `unknown`, and an error renders as its own message on a wall panel, so
    * a shape whose garbage case has an honest rendering earns a place here even
    * when it is nowhere near a hot path. Speed is the other half and is
    * narrower than it looks: a drag paints the slider's fill client-side, so
    * the shapes that actually evaluate at volume are the ones a whole-dashboard
    * render touches — page load, reconnect, repaint — where one evaluation
    * costs ~0.9 kB of cel-java's planner runtime (`benchmarks/RenderBench.cel`)
    * against ~45 B for a direct read (`benchmarks/RenderBench.direct`).
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
    *
    * This enum is the RUNTIME form; [[SimpleWire]] is what the Pkl module emits
    * and circe decodes, and [[SimpleWire.toSimple]] turns one into the other
    * ONCE at decode time. See that type for why the two differ.
    */
  enum Simple {

    /** The entity's raw state string. Idiomatic CEL: `state`. */
    case State

    /** A guarded attribute read, stringified. Idiomatic CEL: `attr[?'name']` —
      * an empty optional renders `""`, so the absent attribute IS the empty
      * string (the slot's `default` then applies). Presence is part of the
      * structure; an author can never write an unguarded read.
      */
    case Attr(name: String)

    /** The state with the entity's own unit appended when it has one. Idiomatic
      * CEL: `state + attr[?'name'].optMap(u, ' ' + u).orValue('')`. A unit that
      * is present but not a String is treated as absent — the documented
      * divergence from the engine, which would error on `' ' + nonString`
      * (pinned in the parity suite's divergence table).
      */
    case UnitSuffix(name: String)

    /** A literal prefix on the state. Idiomatic CEL: `'literal' + state`. */
    case Prefix(literal: String)

    /** A literal suffix on the state. Idiomatic CEL: `state + 'literal'`. */
    case Suffix(literal: String)

    /** The state as a lookup. Idiomatic CEL:
      * `cel.bind(m, {'k': 'v', …}, state in m ? m[state] : 'otherwise')`, with
      * the values written as CEL literals — so a boolean arm spells `true`, not
      * `'true'`.
      *
      * A `Map`, not an ordered list of arms: the match is on equality, so this
      * IS a lookup table and nothing about it is sequential. That makes a
      * duplicate key and a first-match-wins question unrepresentable rather
      * than undefined.
      *
      * The values are [[SlotValue]], so this is also the shape that yields a
      * real BOOLEAN — the only kind of value that can turn a boolean attribute
      * OFF (see [[SlotValue]]). A dedicated membership case was considered and
      * rejected: it would be a second state-to-value mechanism beside this one,
      * and it could not express the inverse (`otherwise = true`, "every state
      * except these") without a third. CEL requires one type across a map's
      * values and both ternary arms, so the arms and `otherwise` must agree —
      * which is a property of the language, not a rule invented here.
      *
      * `otherwise` is required, and deliberately not an `Option` meaning "these
      * cases are exhaustive". Exhaustive over WHAT: the runtime does not know a
      * domain's state vocabulary — only the vendored Pkl module does
      * (`hass/lock.pkl`'s `LockState`) — so the check cannot live here, and HA
      * ADDS states (`open`/`opening` arrived in `lock` after the domain
      * shipped). An unmatched state has to degrade, not blank a wall panel
      * months after the dashboard was written. The authoring layer is where a
      * "cover every variant" helper belongs, because that is where the
      * vocabulary is.
      */
    case Match(cases: Map[String, SlotValue], otherwise: SlotValue)

    /** An attribute as a percentage of a range, rounded half-away-from-zero —
      * the same rounding the engine's `math.round` applies. Idiomatic CEL:
      * `attr[?'name'].optMap(v, str(math.round((double(v) - min) * 100.0 / (max -
      * min))) + ' %').orValue('0 %')`. The `optMap` body runs only when the
      * attribute is there, which is what the `cel.bind` + `!= null` pair this
      * replaced was spelling out. The numeric domain mirrors the engine's
      * `double()` — a number, or a string that parses as one — so a
      * string-numbered attribute renders the same bytes both ways. Absent, or
      * present and unparseable, renders `0 %` — the documented divergence from
      * the engine, which would error on `double(text)` (pinned in the parity
      * suite's divergence table).
      */
    case Percent(name: String, min: Double, max: Double)

    /** An attribute as the slider's remaining fill — the complement of
      * [[Percent]] for a right-anchored track. Idiomatic CEL:
      * `attr[?'name'].optMap(v, str(100.0 - ((double(v) - min) * 100.0 / (max -
      * min)))).orValue('100') + '%'`. Absent, or present and unparseable as a
      * number, renders `100%` (same divergence note as [[Percent]]).
      */
    case Fill(name: String, min: Double, max: Double)

    /** The STATE as a duration a person reads, scaled from the unit the entity
      * reports into seconds first. Idiomatic CEL:
      * {{{
      * cel.bind(t, int(math.round(double(state) * scale)),
      *   t <= 0 ? '0s'
      *   : t >= 3600 ? str(t / 3600) + 'h ' + str(t % 3600 / 60) + 'm'
      *   : t >= 60 ? str(t / 60) + 'm'
      *   : str(t) + 's')
      * }}}
      *
      * `scale` is SECONDS PER UNIT, off `hass.SensorEntity.durationSeconds`.
      * HA's `duration` device class fixes no unit — the same "45 minutes left"
      * arrives as `45` from one appliance and `2700` from another — so a
      * formatter that assumed one would be right by luck per integration.
      *
      * This is the only case that reads the STATE numerically rather than an
      * attribute, which is what a duration sensor is: the reading IS the state.
      *
      * The three-tier resolution is deliberately coarser than the reading. An
      * appliance updates a remaining time on its own slow schedule, so seconds
      * shown against an hours-long cycle would be invented precision that ticks
      * in jumps.
      *
      * Absent-value form is the EMPTY string, and that case is ordinary rather
      * than exotic: measured on the live instance, a dishwasher between
      * programmes reports `unknown` for its remaining time. Empty is the only
      * honest answer there — `0s` would claim it just finished, and passing
      * `unknown` through puts the word where a time goes. The engine ERRORS on
      * `double('unknown')`; same divergence as [[Percent]]/[[Fill]], pinned in
      * the parity suite's divergence table.
      */
    case Duration(scale: Double)
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

    given Decoder[Simple] = Decoder[SimpleWire].emap(_.toSimple)

    /** A stable, injective KEY for one Simple value — the transform's identity
      * wherever the renderer keys by transform (signal names, the once-cache).
      * Structure, not spelling: two structures with equal fields share a key,
      * different structures never collide (the op prefix is disjoint).
      */
    def key(s: Simple): String = s match {
      case Simple.State            => "state"
      case Simple.Attr(n)          => s"attr:$n"
      case Simple.UnitSuffix(n)    => s"unit:$n"
      case Simple.Prefix(lit)      => s"prefix:$lit"
      case Simple.Suffix(lit)      => s"suffix:$lit"
      case m: Simple.Match         => matchKey(m)
      case Simple.Percent(n, a, b) => s"percent:$n:$a:$b"
      case Simple.Fill(n, a, b)    => s"fill:$n:$a:$b"
      case Simple.Duration(scale)  => s"duration:$scale"
    }

    /** Length-prefixed, where its siblings just join on `:`. They can: their
      * arity is fixed, so a separator inside a field cannot make one shape read
      * as another. A [[Simple.Match]]'s arity is not, and `k=v,k=v` would let a
      * key holding the separator forge a different map — a key COLLISION here
      * is two different transforms sharing one signal. Sorted so the key does
      * not depend on `Map` iteration order. Verbosity is free: the segment is
      * hashed into the signal name either way ([[fh.view.runtime.Renderer]]).
      */
    private def matchKey(m: Simple.Match): String = {
      def sized(s: String) = s"${s.length}:$s"
      // A value carries its TYPE into the key: `true` and `"true"` are
      // different transforms — one removes a boolean attribute, the other sets
      // it — so they must not share a signal.
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

  /** The WIRE form of [[Simple]]: what the Pkl module emits and circe decodes,
    * two shapes rather than one per operator.
    *
    *   - [[SimpleWire.Value]] — an operator NAME plus its arguments. Five of
    *     the operators take one String and nothing else, so that argument is a
    *     field and their evaluation never touches a map.
    *   - [[SimpleWire.Match]] — the lookup table, which cannot flatten into an
    *     argument list: its payload is a map with `String | Boolean` arms, and
    *     encoding that inside a field would be a parser.
    *
    * `kind` discriminates the two — the house spelling, same as `LayoutNode`
    * and `Predicate` — and `op` names the operator WITHIN a `Value`. Two fields
    * because they answer two questions, and because circe's discriminator maps
    * a CONSTRUCTOR NAME to one fixed string: an `op` that varies per operator
    * cannot also select the case.
    *
    * Decoding is derived. The only hand-written instances are the two UNION
    * types circe has no generic story for, and [[toSimple]] is the seam that
    * turns a decoded wire value into the runtime [[Simple]] — see its scaladoc
    * for why the renderer never sees this type.
    */
  enum SimpleWire {
    case Value(
        op: String,
        value: Option[String] = None,
        params: Map[String, String | Double] = Map.empty
    )
    case Match(cases: Map[String, SlotValue], otherwise: SlotValue)

    /** Parse a wire value into the runtime shape — TOTAL, and the one place
      * that knows the operator names.
      *
      * The `Left` is what makes the flat wire safe to carry: the Pkl
      * constructors are typed and are the only door, so a missing argument can
      * only arrive from hand-written JSON or a `SimpleValue` built directly,
      * and this reports it at build time naming the dashboard rather than
      * rendering a blank slot forever.
      *
      * Applied in [[Simple.given_Decoder_Simple]] via `emap`, so the flat form
      * never reaches the renderer: the evaluator keeps an exhaustive match over
      * [[Simple]] (which is what stops a new shape quietly skipping the parity
      * suite) and no evaluation pays a map lookup for an argument.
      */
    def toSimple: Either[String, Simple] = this match {
      case SimpleWire.Match(cases, otherwise) =>
        Right(Simple.Match(cases, otherwise))

      case SimpleWire.Value(op, value, params) =>
        def arg: Either[String, String] =
          value.toRight(s"simple `$op` needs a `value`")
        def param(name: String): Either[String, Double] =
          params.get(name) match {
            case Some(d: Double) => Right(d)
            case Some(s: String) =>
              s.toDoubleOption.toRight(
                s"simple `$op` param `$name` is not a number"
              )
            case None => Left(s"simple `$op` needs a `$name` param")
          }
        def range(make: (String, Double, Double) => Simple) =
          for {
            n <- arg
            lo <- param("min")
            hi <- param("max")
          } yield make(n, lo, hi)

        op match {
          case "state"      => Right(Simple.State)
          case "attr"       => arg.map(Simple.Attr.apply)
          case "suffixUnit" => arg.map(Simple.UnitSuffix.apply)
          case "prefix"     => arg.map(Simple.Prefix.apply)
          case "suffix"     => arg.map(Simple.Suffix.apply)
          case "percent"    => range(Simple.Percent.apply)
          case "fill"       => range(Simple.Fill.apply)
          // The one operator whose argument is numeric and whose read is the
          // STATE, so it takes no `value` at all.
          case "duration" => param("scale").map(Simple.Duration.apply)
          case other      => Left(s"unknown simple op `$other`")
        }
    }
  }

  object SimpleWire {

    /** `kind` carries the case, lowercased; `withDefaults` is what lets an
      * absent `value`/`params` fall back to the constructor's own defaults, so
      * the Pkl module can omit both rather than emitting `"params": {}` on
      * every slot of every card — bytes in a wire whose CONTENT HASH is the
      * package version.
      */
    private given Configuration =
      Configuration.default.withDefaults
        .withDiscriminator("kind")
        .withTransformConstructorNames(_.toLowerCase)

    /** A [[Match]] arm off the wire. BOOLEAN FIRST, and the order is the whole
      * decoder: circe's `Decoder[String]` fails on a JSON boolean, but trying
      * String first would still be wrong the day someone widens the union — the
      * narrower type always goes first. Pkl emits a bare `true`, not `"true"`,
      * so the two are distinguishable in the JSON and this is a total decision
      * rather than a guess.
      */
    private given Decoder[SlotValue] =
      Decoder[Boolean]
        .map(b => b: SlotValue)
        .or(Decoder[String].map(s => s: SlotValue))

    /** One `params` entry. Decided on the JSON's OWN shape rather than by
      * trying `Decoder[Double]` then `Decoder[String]`: circe's numeric
      * decoders accept a JSON string that parses as a number, so an ordered
      * `or` would silently turn a genuine string argument into a Double. There
      * is no ordering to get wrong here.
      */
    private given Decoder[String | Double] = Decoder.instance { c =>
      c.value.fold(
        jsonNull = Left(DecodingFailure("param is null", c.history)),
        jsonBoolean =
          _ => Left(DecodingFailure("param is a boolean", c.history)),
        jsonNumber = n => Right(n.toDouble),
        jsonString = s => Right(s),
        jsonArray = _ => Left(DecodingFailure("param is an array", c.history)),
        jsonObject = _ => Left(DecodingFailure("param is an object", c.history))
      )
    }

    given Decoder[SimpleWire] = ConfiguredDecoder.derived
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
    SlotValue.text(runSimpleValue(s, entity))

  /** [[runSimple]] keeping a [[Simple.Match]] arm's type. Every other shape
    * reads or builds a String, so this is a widening at one case and an
    * identity everywhere else.
    */
  def runSimpleValue(s: Simple, entity: EntityState): SlotValue =
    s match {
      case Simple.State      => entity.state
      case Simple.Attr(name) =>
        Cel.stringify(entity.javaAttributes.get(name))
      case Simple.UnitSuffix(name) =>
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

  /** [[run]] keeping a boolean result boolean — see [[Cel.runValue]]. */
  def runValue(
      expr: Compiled,
      entity: EntityState,
      dashboardSlug: String
  ): SlotValue =
    Cel.runValue(expr, entity, dashboardSlug)

  // (The attribute JSON -> Java conversion lives on EntityState.javaAttributes,
  // cached per state version, so it runs once per entity rather than per eval.)

}
