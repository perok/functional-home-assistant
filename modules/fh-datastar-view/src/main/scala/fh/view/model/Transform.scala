package fh.view.model

import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.Jsonata.jsonata

/** Per-slot value transforms, expressed as [JSONata](https://jsonata.org).
  *
  * Authored inline in the dashboard jsonnet as a string (see
  * [[SlotSource.transform]]) and evaluated by the renderer per live value. The
  * live value — the resolved slot value, always a String — is the JSONata input
  * `$`; coerce it with `$number($)` for arithmetic. Examples:
  *
  *   - `$round($number($) * 1000) & " W"` — scale, round, append a unit
  *   - `$round($number($) * 1.8 + 32, 1)` — °C → °F, one decimal
  *   - `$ = "on" ? "Open" : "Closed"` — map a state to display text
  *
  * Compilation happens once at build/validate time; the renderer reuses the
  * compiled expression. Evaluation errors (e.g. `$number` on a non-numeric
  * state) and null results fall back to the raw value, so a transient odd value
  * never blanks a card.
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

  /** Evaluate a compiled expression against a single live value, stringified
    * for the template. On evaluation error, returns the raw value unchanged.
    */
  def run(expr: Compiled, value: String): String =
    try
      // dashjoin's Jsonata is documented thread-safe and threads evaluation
      // state through a per-call frame, so the renderer safely shares one
      // compiled instance across fibers without locking.
      asString(expr.evaluate(value))
    catch case _: Exception => value

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
