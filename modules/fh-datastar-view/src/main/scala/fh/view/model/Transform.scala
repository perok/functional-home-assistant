package fh.view.model

import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.Jsonata.jsonata
import fh.view.runtime.EntityState

/** Per-slot value transforms, expressed as [JSONata](https://jsonata.org).
  *
  * Authored inline in the dashboard jsonnet as a string (see
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
  * that one card, so a genuinely broken expression is visible. (A value-display
  * slot can opt out for unavailable/unknown entities via
  * `SlotSource.bypassUnavailable`, where the renderer shows the raw state and
  * skips the transform instead — see `EntityState.unavailable`.) A `null`
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
    * action). On evaluation failure, returns the JSONata error message so the
    * card shows it (contained — never throws into the render).
    */
  def run(expr: Compiled, entity: EntityState): String =
    evalBound(
      expr,
      "state" -> entity.state,
      // Cached on the EntityState (converted once per state version — see
      // EntityState.javaAttributes), so repeated evals on the same entity (a card
      // with several `$attr` slots, or a dynamic group scanning a hot entity) do
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
