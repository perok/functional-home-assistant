package fh.view.runtime

/** The six shipped transform shapes + the hostile ceiling, as the SHIPPED CEL
  * bytes — what the Pkl library bakes today, not a parallel translation. The
  * shipped runtime ([[fh.view.model.Transform]]) compiles exactly these via
  * `str`/`num` (its two registered helpers) plus cel-java's bundled
  * string/list/math/bindings/comprehensions extensions.
  *
  * The load-bearing CEL facts the forms encode (each one a measured Phase-0
  * finding, pinned by TransformSuite):
  *
  *   - '''`double(v)` is not decoration.''' An attribute position arrives as a
  *     Long, and CEL's double overloads have no (Long, double) operand — a bare
  *     `v - 1.0` COMPILES and throws at evaluation.
  *   - '''Float literals throughout the range arithmetic.''' CEL's `/` on two
  *     ints is integer division.
  *   - '''`str(...)`, never native `string(...)`.''' `math.round` returns a
  *     double, which `string(...)` renders as `247.0`; the registered `str`
  *     strips the `.0` and renders lists the way JSONata's `$string` did.
  *   - '''Ternary arms must share a type.''' CEL has no `? x : null`, so the
  *     kelvin-absent arm of the fill colour is `''`.
  *   - '''Presence is `'k' in attr`.''' Raw `attr['k']` on an absent key is an
  *     evaluation error (JSONata read it as null), so every attribute read is
  *     gated — and an empty-string value is PRESENT to `in`, where JSONata
  *     found it falsy (the surviving, deliberate divergences CelSpike pins).
  *   - '''`cel.bind` for JSONata's `:=`''', `attr.transformList(k, v, ...)` for
  *     `$each`, `.sort()`/`.join('\n')` for `$sort`/`$join`.
  */
object CelShapes {

  final val TransformName =
    """('friendly_name' in attr ? attr['friendly_name'] : entity_id)"""

  final val TransformUnit =
    """state + ('unit_of_measurement' in attr ? ' ' + attr['unit_of_measurement'] : '')"""

  // The slider's fill, as slider.pkl bakes it for a light (min 1, max 255) —
  // unrounded on purpose (beer.min.js recomputes the same percentage on load),
  // wrapped as a "%"-suffixed string.
  final val TransformFill =
    """str(cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, v != null ? 100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0)) : 100.0)) + '%'"""

  // The percent readout, same baked config, whole numbers + " %".
  final val TransformPercent =
    """cel.bind(v, 'brightness' in attr ? attr['brightness'] : null, v != null ? str(math.round((double(v) - 1.0) * 100.0 / (255.0 - 1.0))) + ' %' : '0 %')"""

  // The fill COLOUR (slider.pkl): rgb_color wins, else the kelvin ramp —
  // `double(k)` for the Long kelvin, `''` where JSONata had null.
  final val TransformFillColor =
    """cel.bind(rgb, 'rgb_color' in attr ? attr['rgb_color'] : null, cel.bind(k, 'color_temp_kelvin' in attr ? attr['color_temp_kelvin'] : null, (rgb != null && size(rgb) == 3) ? 'rgb(' + str(rgb[0]) + ',' + str(rgb[1]) + ',' + str(rgb[2]) + ')' : (k != null ? cel.bind(t, (double(k) - 2000.0) < 0.0 ? 0.0 : ((double(k) - 2000.0) > 4500.0 ? 1.0 : (double(k) - 2000.0) / 4500.0), 'rgb(' + str(math.round(255.0 - 54.0 * t)) + ',' + str(math.round(166.0 + 60.0 * t)) + ',' + str(math.round(87.0 + 168.0 * t)) + ')') : '')))"""

  // More-info's attribute block: every attribute as a sorted `name: value`
  // line (moreinfo.pkl).
  final val TransformAttrLines =
    """attr.transformList(k, v, k + ': ' + str(v)).sort().join('\n')"""

  // The CEILING: the retired dynamic `$lookup($domain)` tier, hand-written in
  // CEL. Shipped nothing uses it; the fallback's worst case stays priced.
  final val TransformComplex =
    """cel.bind(v, attr[{'light':'brightness','cover':'current_position'}[domain]],
      |  v != null
      |    ? math.round(100.0 - ((double(v) - double({'light':1,'cover':0}[domain])) * 100.0 /
      |        (double({'light':255,'cover':100}[domain]) - double({'light':1,'cover':0}[domain]))))
      |    : 100.0)""".stripMargin

  final val LiveTransforms = List(
    TransformName,
    TransformUnit,
    TransformFill,
    TransformPercent,
    TransformFillColor,
    TransformAttrLines
  )

  final val all = LiveTransforms :+ TransformComplex
}
