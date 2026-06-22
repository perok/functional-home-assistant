package fh.view.model

import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.Jsonata.jsonata
import io.circe.Json

/** Per-slot value transforms, expressed as [JSONata](https://jsonata.org).
  *
  * Authored inline in the dashboard jsonnet as a string (see
  * [[SlotSource.transform]]) and evaluated by the renderer per live value.
  *
  * A transform reads the entity through bound variables — `$state` (its raw
  * state String) and `$attr` (its full attribute object, navigated as
  * `$attr.unit_of_measurement`); numbers stay numeric, so coerce a String state
  * with `$number($state)` for arithmetic. Only this entity is reachable —
  * lookups are same-entity only — and there is no bare `$`: the slot value is
  * whatever the expression returns. Examples:
  *
  *   - `$round($number($state) * 1000) & " W"` — scale, round, append a unit
  *   - `$state & " " & $attr.unit_of_measurement` — append the entity's own
  *     unit
  *   - `$round($number($state) * 1.8 + 32, 1)` — °C → °F, one decimal
  *   - `$state = "on" ? "Open" : "Closed"` — map a state to display text
  *
  * Compilation happens once at build/validate time; the renderer reuses the
  * compiled expression. Evaluation errors (e.g. `$number` on a non-numeric
  * state) and null results fall back to the raw value, so a transient odd value
  * never blanks a card.
  */
object Transform {

  /** A compiled JSONata expression. */
  type Compiled = Jsonata

  /** What a transform evaluates against: the SAME entity's `state` and
    * `attributes` (bound as `$state`/`$attr`). Carries no other entity, so
    * transforms cannot reach across entities. `value` is the already-resolved
    * slot value, used only as the fallback when evaluation errors — it is *not*
    * exposed to the expression (there is no bare `$`).
    */
  final case class Context(
      value: String,
      state: String,
      attributes: Map[String, Json]
  )

  /** Compile a JSONata expression (build/validate time). */
  def parse(src: String): Either[String, Compiled] = {
    val trimmed = src.trim
    if (trimmed.isEmpty) Left("empty transform expression")
    else
      try Right(jsonata(trimmed))
      catch case e: Exception => Left(s"invalid JSONata: ${e.getMessage}")
  }

  /** Evaluate a compiled expression against one entity's [[Context]],
    * stringified for the template. On evaluation error, returns the raw value
    * unchanged.
    */
  def run(expr: Compiled, ctx: Context): String =
    try
      // dashjoin's Jsonata is documented thread-safe: `createFrame` makes a
      // fresh child of the (shared, read-only) std-library environment, so
      // binding `$state`/`$attr` here is local to this call and the renderer
      // safely shares one compiled instance across fibers without locking.
      val frame = expr.createFrame()
      frame.bind("state", ctx.state)
      frame.bind("attr", attrObject(ctx.attributes))
      // No input context: the expression addresses the entity via $state/$attr,
      // so there is no bare `$`.
      asString(expr.evaluate(null, frame))
    catch case _: Exception => ctx.value

  // JSONata navigates plain Java values, so expose attributes as a Java map of
  // converted JSON (numbers stay numeric for `$attr.brightness` arithmetic,
  // strings stay strings, nested objects/arrays recurse). null fields drop out.
  private def attrObject(
      attrs: Map[String, Json]
  ): java.util.Map[String, Any] = {
    val m = new java.util.LinkedHashMap[String, Any](attrs.size)
    attrs.foreach { case (k, v) => m.put(k, toJava(v)) }
    m
  }

  private def toJava(j: Json): Any =
    j.fold(
      null,
      b => b,
      n => n.toLong.map(l => l: Any).getOrElse(n.toDouble),
      s => s,
      arr => {
        val l = new java.util.ArrayList[Any](arr.size)
        arr.foreach(x => l.add(toJava(x)))
        l
      },
      obj => {
        val m = new java.util.LinkedHashMap[String, Any]()
        obj.toIterable.foreach { case (k, v) => m.put(k, toJava(v)) }
        m
      }
    )

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
