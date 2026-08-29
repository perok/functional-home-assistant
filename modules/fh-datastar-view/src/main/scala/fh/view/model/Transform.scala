package fh.view.model

import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.Jsonata.jsonata
import fh.view.runtime.EntityState

/** Per-slot value transforms, expressed as [JSONata](https://jsonata.org).
  *
  * Authored inline in the dashboard source as a string (see
  * [[SlotSource.transform]]) and evaluated by the renderer per live value.
  *
  * A transform reads the entity through bound variables — `$state` (its raw
  * state String), `$attr` (its full attribute object, navigated as
  * `$attr.unit_of_measurement`), `$domain` (the entity-id prefix) and
  * `$entity_id` (the full id); numbers stay numeric, so coerce a String state
  * with `$number($state)` for arithmetic. Only this entity is reachable —
  * lookups are same-entity only — and there is no bare `$`: the slot value is
  * whatever the expression returns. Examples:
  *
  *   - `$round($number($state) * 1000) & " W"` — scale, round, append a unit
  *   - `$state & " " & $attr.unit_of_measurement` — append the entity's own
  *     unit
  *   - `$round($number($state) * 1.8 + 32, 1)` — °C → °F, one decimal
  *   - `$state = "on" ? "Open" : "Closed"` — map a state to display text
  *   - `$lookup({"scene": "scene/turn_on"}, $domain) ? … : "homeassistant/toggle"`
  *     — an identity-derived value (a tap action), independent of live state
  *
  * Compilation happens once at build/validate time; the renderer reuses the
  * compiled expression. A failing evaluation is **not** swallowed nor allowed
  * to crash the render — the card shows the JSONata error message, contained to
  * that one card, so a genuinely broken expression is visible. (For
  * unavailable/unknown entities the renderer shows the raw state and skips the
  * transform by default — `SlotSource.bypassUnavailable`, ON unless an action /
  * label / slider position opts out — see `EntityState.unavailable`.) A `null`
  * result becomes `""` (the slot's `default` then applies).
  *
  * Takes an [[EntityState]], which carries the entity's identity (`entityId`,
  * and `domain` derived from it) alongside its live `state`/`attributes`, so
  * the `$domain`/`$entity_id` bindings come straight off the fetched state
  * rather than being recomputed here.
  */
object Transform {

  /** A compiled JSONata expression. */
  type Compiled = Jsonata

  /** The two transform shapes that read a value and apply nothing to it.
    *
    * They are worth separating because an ENGINE charges for being an engine: a
    * general evaluator converts the entity into its own value model on every
    * evaluation. On the renderer's warm path one evaluation of the six shipped
    * shapes costs ~5.9 kB (dashjoin jsonata) or ~1.8 kB (cel-java's planner
    * runtime) (`benchmarks/RenderBench.jsonata` / `.cel`), against ~45 B for a
    * direct read (`benchmarks/RenderBench.direct`) — so on the two shapes that
    * apply nothing, the fast path saves the whole engine cost, not a fraction
    * (issue #237). `Transforms.run` resolves them at startup and never sends
    * them to an engine.
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
    * space is already handled by the trim, but anything else at all (`$state `
    * with an operator after it, `$attr.a.b`, `$attr."x"`) is not one of these
    * two shapes and must go to JSONata.
    */
  def direct(src: String): Option[Direct] = src.trim match {
    case "$state"                                   => Some(Direct.State)
    case s"$$attr.$name" if name.forall(isNameChar) => Some(Direct.Attr(name))
    case _                                          => None
  }

  private def isNameChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_'

  /** Evaluate a [[Direct]] shape without JSONata, stringified exactly as
    * [[run]] would — the SAME `asString`, so the two cannot drift in how they
    * render a number, a boolean or an absent value.
    */
  def runDirect(d: Direct, entity: EntityState): String = d match {
    case Direct.State      => entity.state
    case Direct.Attr(name) =>
      asString(entity.javaAttributes.get(name))
  }

  /** Compile a JSONata expression (build/validate time). */
  def parse(src: String): Either[String, Compiled] = {
    val trimmed = src.trim
    if (trimmed.isEmpty) Left("empty transform expression")
    else
      try Right(jsonata(trimmed))
      catch case e: Exception => Left(s"invalid JSONata: ${e.getMessage}")
  }

  /** Evaluate a compiled expression against one entity, stringified for the
    * template. Binds the entity's full context — `$state`/`$attr` (its live
    * value) and `$domain`/`$entity_id` (its identity, from the id) — so the
    * same mechanism serves value slots and identity-derived slots (e.g. a tap
    * action) — plus `$dashboardSlug`, the only binding that is not about the
    * entity. On evaluation failure, returns the JSONata error message so the
    * card shows it (contained — never throws into the render).
    */
  def run(expr: Compiled, entity: EntityState, dashboardSlug: String): String =
    evalBound(
      expr,
      // The dashboard being rendered. A tap builds an action URL the server can
      // bound (ADR 0023) and only the renderer knows the slug, since a module
      // does not know its own. A real BINDING rather than a token substituted
      // into the expression text: `$dashboardSlug` then says what it is, where
      // a `{{…}}` in a transform would read as Mustache and never be one — a
      // transform's output is inserted raw, so Mustache never sees it.
      "dashboardSlug" -> dashboardSlug,
      "state" -> entity.state,
      // Cached on the EntityState (converted once per state version — see
      // EntityState.javaAttributes), so repeated evals on the same entity (a card
      // with several `$attr` slots, or a candidate set scanning a hot entity) do
      // not each rebuild the attribute map.
      "attr" -> entity.javaAttributes,
      "entity_id" -> entity.entityId,
      "domain" -> entity.domain
    )

  // dashjoin's Jsonata is documented thread-safe: `createFrame` makes a fresh
  // child of the (shared, read-only) std-library environment, so binding here is
  // local to this call and the renderer safely shares one compiled instance
  // across fibers without locking. No input context: the expression addresses
  // the entity via $state/$attr/$domain/$entity_id, so there is no bare `$`.
  private def evalBound(expr: Compiled, bindings: (String, Any)*): String =
    val frame = expr.createFrame()
    bindings.foreach { case (name, value) => frame.bind(name, value) }
    try asString(expr.evaluate(null, frame))
    catch case e: Exception => s"JSONata error: ${errorText(e)}"

  private def errorText(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  // (The attribute JSON -> Java conversion lives on EntityState.javaAttributes,
  // cached per state version, so it runs once per entity rather than per eval.)

  // JSONata produces Java values; render them the way the spec's string
  // coercion (`&` / $string) would, so bare-number results match concatenated
  // ones. Null becomes "" so the slot's `default` can take over.
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
