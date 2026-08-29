package fh.view.runtime

/** The six shipped transform shapes + the hostile ceiling, translated to CEL —
  * the extension-enabled, `cel.bind` form that a real port would compile.
  *
  * Each is a translation (not a rewrite): `cel.bind` for JSONata's `:=` and its
  * compound expression, `+` for `&` (both engines string-coerce on that
  * operator; CEL uses `string(...)`/`int(...)`/`double(...)` where JSONata
  * coerce implicitly), `attr.transformMap(k, v, ...)` for `$each`, `.sort()`
  * for `$sort`, `.join('\n')` for `$join`, `math.round` for `$round`, and the
  * registered `str(x)` for `$string` over heterogeneous values (arrays,
  * numbers, nulls) — the one place CEL has no native equivalent.
  *
  * Presence is a REAL boolean in CEL: `'x' in attr` for key presence, `!= null`
  * for value presence, `size(...) == n` for shape. RAW `attr["x"]` THROWS an
  * evaluation error when `x` is absent (a denormalised hand-rolled `attr` map,
  * not a CEL native map), so `attr["x"] != null` is NOT a null check — a
  * dimension the everything-present benchmark fixture never used, measured by
  * the CelSpike sweep. Empty-string values are present to `in` (where JSONata
  * found them falsy): the surviving, deliberate divergences are pinned there.
  */
object CelShapes {

  final val TransformName =
    """'friendly_name' in attr ? attr["friendly_name"] : entity_id"""

  final val TransformUnit =
    """'unit_of_measurement' in attr ? state + ' ' + attr["unit_of_measurement"] : state"""

  final val TransformFill =
    """cel.bind(v, 'brightness' in attr ? attr["brightness"] : null, v != null ? 100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0)) : 100.0)"""

  final val TransformPercent =
    """cel.bind(v, 'brightness' in attr ? attr["brightness"] : null, v != null ? string(int(math.round((double(v) - 1.0) * 100.0 / 254.0))) + ' %' : '0 %')"""

  final val TransformFillColor =
    """cel.bind(rgb, 'rgb_color' in attr ? attr["rgb_color"] : null,
      |  cel.bind(k, 'color_temp_kelvin' in attr ? attr["color_temp_kelvin"] : null,
      |    (rgb != null && size(rgb) == 3)
      |      ? 'rgb(' + string(int(rgb[0])) + ',' + string(int(rgb[1])) + ',' + string(int(rgb[2])) + ')'
      |      : (k != null
      |          ? cel.bind(t, (double(k) - 2000.0) < 0.0 ? 0.0 : ((double(k) - 2000.0) > 4500.0 ? 1.0 : (double(k) - 2000.0) / 4500.0),
      |              'rgb(' + string(math.round(255.0 - 54.0 * t)) + ',' + string(math.round(166.0 + 60.0 * t)) + ',' + string(math.round(87.0 + 168.0 * t)) + ')')
      |          : '')))""".stripMargin

  final val TransformAttrLines =
    """attr.transformList(k, v, k + ': ' + str(v)).sort().join('\n')"""

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
