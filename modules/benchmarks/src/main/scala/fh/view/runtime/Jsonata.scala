package fh.view.runtime

import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.Jsonata.jsonata

/** The RETIRED JSONata transform engine, kept here — bench-only — as the
  * reference the divergence gate ([[CelSpike]]) and RenderBench's jsonata cells
  * measure against. This is the pre-swap `fh.view.model.Transform`,
  * byte-for-byte in behaviour — same bindings, same evaluation, same
  * stringification — so the engine comparison stays apples-to-apples now that
  * the shipped runtime compiles CEL only and this module is the only thing that
  * still reaches dashjoin.
  */
object Jsonata {

  /** A compiled JSONata expression. */
  type Compiled = com.dashjoin.jsonata.Jsonata

  /** Compile a JSONata expression (bench setup). */
  def parse(src: String): Either[String, Compiled] = {
    val trimmed = src.trim
    if (trimmed.isEmpty) Left("empty transform expression")
    else
      try Right(jsonata(trimmed))
      catch case e: Exception => Left(s"invalid JSONata: ${e.getMessage}")
  }

  /** Evaluate a compiled expression against one entity, stringified exactly as
    * the shipped [[fh.view.model.Transform.run]] stringifies — the SAME
    * `asString`, so the two engines land on identical slot values and a
    * divergence is the engines', not the wrappers'.
    */
  def run(
      expr: Compiled,
      entity: EntityState,
      dashboardSlug: String
  ): String = {
    val frame = expr.createFrame()
    frame.bind("dashboardSlug", dashboardSlug)
    frame.bind("state", entity.state)
    frame.bind("attr", entity.javaAttributes)
    frame.bind("entity_id", entity.entityId)
    frame.bind("domain", entity.domain)
    try asString(expr.evaluate(null, frame))
    catch case e: Exception => s"JSONata error: ${errorText(e)}"
  }

  // dashjoin's Jsonata is documented thread-safe: `createFrame` makes a fresh
  // child of the (shared, read-only) std-library environment, so binding in
  // [[run]] is local to that call and one compiled instance is safely shared
  // across fibers without locking. No input context: the expression addresses
  // the entity via $state/$attr/$domain/$entity_id, so there is no bare `$`.

  private def errorText(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

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

  /** The six shipped shapes, as the JSONata the shipped components stuck on hot
    * slots before the CEL swap — the reference side of [[CelSpike]]'s pairing
    * and of RenderBench's jsonata cells. (The icon is a literal now, so it is
    * not in the mix; benchmarking `$state` alone would flatter JSONata, so the
    * trivial shapes sit beside [[TrivialTransforms]].)
    */
  final val TransformName =
    """$attr.friendly_name ? $attr.friendly_name : $entity_id"""
  final val TransformUnit =
    """$state & ($attr.unit_of_measurement ? " " & $attr.unit_of_measurement : "")"""
  // The slider's fill, as Pkl baked it for a light (min 1, max 255), WITH the
  // `$string(...) & "%"` wrapper and unrounded on purpose — beer.min.js
  // recomputes the same percentage on load.
  final val TransformFill =
    """$string(($v := $attr.brightness; $v != null ? 100 - (($v - 1) * 100 / (255 - 1)) : 100)) & "%""""
  // The percent readout, same baked config, as a "%"-suffixed string.
  final val TransformPercent =
    """($v := $attr.brightness; $v != null ? $string($round(($v - 1) * 100 / (255 - 1))) & " %" : "0 %")"""
  // The fill COLOUR: rgb_color verbatim, else the kelvin ramp (slider.pkl).
  final val TransformFillColor =
    "($rgb := $attr.rgb_color; $k := $attr.color_temp_kelvin; $count($rgb) = 3 " +
      """? "rgb(" & $string($rgb[0]) & "," & $string($rgb[1]) & "," & $string($rgb[2]) & ")" """ +
      " : $k != null ? ($t := $k < 2000 ? 0 : ($k > 6500 ? 1 : ($k - 2000) / 4500); " +
      "\"rgb(\" & $string($round(255 - 54 * $t)) & \",\" & $string($round(166 + 60 * $t)) " +
      "& \",\" & $string($round(87 + 168 * $t)) & \")\") : null)"
  // More-info's attribute block: every attribute as a sorted `name: value`
  // line (moreinfo.pkl).
  final val TransformAttrLines =
    """$join($sort($each($attr, function($v, $k) { $k & ": " & $string($v) })), "\n")"""

  final val LiveTransforms = List(
    TransformName,
    TransformUnit,
    TransformFill,
    TransformPercent,
    TransformFillColor,
    TransformAttrLines
  )

  /** The two trivial reads, in the JSONata spelling that once reached the
    * engine — in production they are [[fh.view.model.Transform.Simple]] and
    * never see an engine at all.
    */
  final val TrivialTransforms =
    List("$state", "$attr.friendly_name", "$attr.brightness")

  /** The CEILING: a hand-written expression as hostile as the retired dynamic
    * `$lookup($domain)` tier. Shipped nothing uses it; the fallback's worst
    * case stays priced ([[RenderBench.jsonataComplex]]).
    */
  final val TransformComplex =
    "($v := $lookup($attr, $lookup({\"light\":\"brightness\",\"cover\":\"current_position\"}, $domain)); " +
      "$v != null ? $round(100 - (($v - $lookup({\"light\":1,\"cover\":0}, $domain)) * 100 / " +
      "($lookup({\"light\":255,\"cover\":100}, $domain) - $lookup({\"light\":1,\"cover\":0}, $domain)))) : 100)"
}
