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
  * Presence is a REAL boolean in CEL (`!= null`, `size(...) == 3`), where
  * JSONata used truthiness; on the benchmark fixture (present-or-absent
  * strings, present ints) the two agree for every attribute the shapes read.
  */
object CelShapes {

  final val TransformName =
    """attr["friendly_name"] != null ? attr["friendly_name"] : entity_id"""

  final val TransformUnit =
    """attr["unit_of_measurement"] != null ? state + ' ' + attr["unit_of_measurement"] : state"""

  final val TransformFill =
    """cel.bind(v, attr["brightness"], v != null ? 100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0)) : 100.0)"""

  final val TransformPercent =
    """cel.bind(v, attr["brightness"], v != null ? string(int(math.round((double(v) - 1.0) * 100.0 / 254.0))) + ' %' : '0 %')"""

  final val TransformFillColor =
    """cel.bind(rgb, attr["rgb_color"],
      |  cel.bind(k, attr["color_temp_kelvin"],
      |    size(rgb) == 3
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
