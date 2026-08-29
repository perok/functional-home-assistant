package fh.view.model

import fh.view.runtime.{Cel, EntityState}

/** Per-slot value transforms, expressed as [CEL](https://cel.dev) (the
  * "Common Expression Language").
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
  * so the shipped strings read attributes guarded (`'x' in attr ? … : …`),
  * the idiom that mirrors JSONata's null-on-missing (measured in the Phase-0
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

  /** The transform shapes that read a value and apply nothing to it.
    *
    * They are worth separating because an ENGINE charges for being an engine: a
    * general evaluator converts the entity into its own value model on every
    * evaluation. On the renderer's warm path one evaluation of the six shipped
    * shapes costs ~0.9 kB of cel-java's planner runtime (`benchmarks/
    * RenderBench.cel`), against ~45 B for a direct read (`benchmarks/
    * RenderBench.direct`) — so on the shape that applies nothing, the fast path
    * saves the whole engine cost, not a fraction (issue #237). `Transforms.run`
    * resolves them at startup and never sends them to an engine.
    *
    * Phase 1 recognises only the bare `state` read: it is the guaranteed-present
    * one (the fast path survives for every plain state slot). An `attr` read is
    * ship-shaped GUARDED (`'x' in attr ? attr['x'] : …`), and guards need the
    * engine — correctness first, the `Direct.Attr` fast path re-targets in
    * Phase 2 (the benchmark keeps pricing the read mechanism).
    *
    * `None` for everything else, which is the honest answer: anything with an
    * operator, a function or a conditional goes to the engine, and this must
    * never grow into a second implementation of the language.
    */
  enum Direct {
    case State
    case Attr(name: String)
  }

  /** Recognise a [[Direct]] shape. Deliberately strict — a leading/trailing
    * space is already handled by the trim, but anything else at all (`state `
    * with an operator after it, `state == 'on' ? … : …`) is not the one shape
    * and must go to the engine.
    */
  def direct(src: String): Option[Direct] = src.trim match {
    case "state" => Some(Direct.State)
    case _       => None
  }

  /** Evaluate a [[Direct]] shape without the engine, stringified exactly as
    * [[run]] would — the SAME rendering, so the two cannot drift in how they
    * render a state, a number or an absent value.
    */
  def runDirect(d: Direct, entity: EntityState): String = d match {
    case Direct.State      => entity.state
    case Direct.Attr(name) =>
      asString(entity.javaAttributes.get(name))
  }

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