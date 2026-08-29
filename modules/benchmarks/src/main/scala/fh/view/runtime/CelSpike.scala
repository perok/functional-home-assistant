package fh.view.runtime

import fh.view.model.Transform
import io.circe.Json

/** Divergence gate for the CEL engine swap (plan Phase 0).
  *
  * Pairing the shipped-intended JSONata shapes against the CEL a port would
  * write, this sweeps BOTH the benchmark's real-world fixture and a curated
  * margin set (empty-string attrs, fractional values, odd types, absent attrs)
  * and records — not assumes — where the two engines part. The rendered bytes
  * of the affected slots are the contract, and the divergence table this prints
  * is that contract, ahead of the swap.
  *
  * Run with `sbt 'benchmarks/Compile/runMain fh.view.runtime.CelSpike'`. The
  * exit code is non-zero iff the observed divergences stop matching the golden
  * table below — so the swap's output delta is pinned and a NEW divergence (a
  * drift in either engine, or a wrong translation) fails loudly.
  */
object CelSpike {

  // The slider fill exactly as slider.pkl bakes it for a light (min 1, max
  // 255), WITH the `$string(...) & "%"` wrapper — the CORRECTED bytes. The raw
  // string in slider.pkl carries a stray paren (`…(255 - 1))) : 100))`) and
  // does not compile; that defect is itself a divergence-class finding (the
  // swap's re-authoring drops it by construction).
  private val JsonFill =
    """$string(($v := $attr.brightness; $v != null ? 100 - (($v - 1) * 100 / (255 - 1)) : 100)) & "%""""

  // The CEL a port would emit for the same slot: same arithmetic, `%` suffix,
  // CEL-native `string(...)` for the numeric (no dashjoin $string semantics).
  private val CelFill =
    """cel.bind(v, 'brightness' in attr ? attr["brightness"] : null, v != null ? string(100.0 - ((double(v) - 1.0) * 100.0 / (255.0 - 1.0))) + '%' : '100%')"""

  private final case class Shape(json: String, cel: String)

  private val Shapes: List[(String, Shape)] = List(
    "name" -> Shape(RenderBench.TransformName, CelShapes.TransformName),
    "unit" -> Shape(RenderBench.TransformUnit, CelShapes.TransformUnit),
    "fill" -> Shape(JsonFill, CelFill),
    "percent" -> Shape(
      RenderBench.TransformPercent,
      CelShapes.TransformPercent
    ),
    "fillColor" -> Shape(
      RenderBench.TransformFillColor,
      CelShapes.TransformFillColor
    ),
    "attrLines" -> Shape(
      RenderBench.TransformAttrLines,
      CelShapes.TransformAttrLines
    ),
    "complex" -> Shape(RenderBench.TransformComplex, CelShapes.TransformComplex)
  )

  private final case class Probe(label: String, entity: EntityState)

  private def es(state: String, attrs: (String, Json)*): EntityState =
    EntityState("light.probe", state, attrs.toMap)

  private final case class Compiled(
      jc: Transform.Compiled,
      cp: CelTransforms.Program
  )

  private lazy val compiled: Map[String, Compiled] = Shapes.map {
    case (name, s) =>
      name -> Compiled(
        Transform
          .parse(s.json)
          .fold(
            e => sys.error(s"jsonata won't compile: $name [$e]"),
            identity
          ),
        CelTransforms.parse(s.cel)
      )
  }.toMap

  private final case class Result(diverge: Boolean, json: String, cel: String)

  private def evaluate(name: String, p: Probe): Result = {
    val c = compiled(name)
    val jr = Transform.run(c.jc, p.entity, "dashboard")
    val cr = c.cp.run(p.entity, "dashboard")
    Result(jr != cr, jr, cr)
  }

  private def truncate(s: String, n: Int = 80): String =
    if (s.length <= n) s else s.take(n) + "…"

  /** The pinned divergence contract: (shape, probeLabel) pairs that MUST
    * diverge. Every other (shape, probe) must agree byte-for-byte. Measured and
    * occupied from the run during Phase 0 — never extended silently.
    *
    * The 19 rows sort into four classes, each a deliberate CEL-native margin:
    *
    *   - Empty-string presence (2): `friendly_name=""`, `unit=""`. JSONata
    *     reads "" falsy and falls back; CEL's `'k' in attr` sees it as present
    *     (`""` vs `entity_id`, `"on "` trailing space).
    *   - Number stringification (16): `fill` on every fixture value (MC15
    *     `…3228%` vs CEL `…32283%`, and integer `100%` vs `100.0%`), the
    *     `brightness=129.27` double-dust (`49.5%` vs `49.49999999999999%`), and
    *     the kelvin-ramp `math.round` DOUBLE results (`rgb(231,193,162)` vs
    *     `rgb(231.0,193.0,162.0)`).
    *   - Half-away vs HALF_EVEN (1): `cover 63.5` — an exactly-representable .5
    *     from the 100-scaled cover range — rounds 36.5 to 36 vs 37. Unreachable
    *     on integer attributes, so only this probe can see it.
    *   - Error text (1): `brightness="on"` — each engine's wording for the same
    *     type failure, inside `Transform.error`.
    *
    * The inverse is the result that makes the swap safe: `percent` (every
    * fixture value, a fractional, an absent and an integer margin) and
    * `attrLines` (fixture, no-attrs, float-list) agree byte-for-byte, and with
    * the `'k' in attr` presence idiom in the CelShapes translations, absent-key
    * reads fall through like JSONata's null instead of being the `attr["k"]`
    * evaluation errors the sweep first measured.
    */
  private val Golden_Diverge: Set[(String, String)] = Set(
    "name" -> "friendly_name=\"\"",
    "unit" -> "unit=\"\"",
    "complex" -> "cover 63.5 (true half)",
    "fill" -> "brightness=129.27",
    "fill" -> "brightness=\"on\"",
    "fillColor" -> "kelvin only, int",
    "fillColor" -> "kelvin=4208.3333 (half)"
  ) ++ (0 to 11).map(i => "fill" -> s"fixture[$i]")

  def main(args: Array[String]): Unit = {
    val fixture =
      RenderBench.states(12).toList.zipWithIndex.map { case ((id, e), i) =>
        Probe(s"fixture[$i]", e)
      }
    val probesByShape: Map[String, List[Probe]] = Map(
      "name" -> (fixture ++ List(
        Probe(
          "friendly_name=\"\"",
          es("on", "friendly_name" -> Json.fromString(""))
        ),
        Probe("friendly_name absent", es("on"))
      )),
      "unit" -> (fixture ++ List(
        Probe(
          "unit=\"\"",
          es("on", "unit_of_measurement" -> Json.fromString(""))
        ),
        Probe("unit absent", es("on"))
      )),
      "fill" -> (fixture ++ List(
        Probe(
          "brightness=129.27",
          es("on", "brightness" -> Json.fromDouble(129.27).get)
        ),
        Probe("brightness absent", es("on")),
        Probe(
          "brightness=\"on\"",
          es("on", "brightness" -> Json.fromString("on"))
        )
      )),
      "percent" -> (fixture ++ List(
        Probe(
          "brightness=129.27",
          es("on", "brightness" -> Json.fromDouble(129.27).get)
        ),
        Probe("brightness absent", es("on"))
      )),
      "fillColor" -> (fixture ++ List(
        Probe(
          "kelvin only, int",
          es("on", "color_temp_kelvin" -> Json.fromInt(4000))
        ),
        Probe(
          "kelvin=4208.3333 (half)",
          es("on", "color_temp_kelvin" -> Json.fromDouble(4208.3333).get)
        ),
        Probe("no rgb, no kelvin", es("on")),
        Probe("rgb wrong size", es("on", "rgb_color" -> Json.arr()))
      )),
      "attrLines" -> (fixture ++ List(
        Probe("no attrs", es("on")),
        Probe(
          "float list",
          es(
            "on",
            "hue" -> Json
              .arr(Json.fromDouble(3.5).get, Json.fromDouble(4.0).get)
          )
        )
      )),
      "complex" -> (fixture ++ List(
        Probe(
          "brightness=129.27",
          es("on", "brightness" -> Json.fromDouble(129.27).get)
        ),
        Probe(
          "cover 62.5",
          EntityState(
            "cover.probe",
            "open",
            Map("current_position" -> Json.fromDouble(62.5).get)
          )
        ),
        Probe(
          "cover 63.5 (true half)",
          EntityState(
            "cover.probe",
            "open",
            Map("current_position" -> Json.fromDouble(63.5).get)
          )
        )
      ))
    )

    var unexpected = 0
    Shapes.foreach { case (name, _) =>
      println(s"== $name")
      probesByShape(name).foreach { p =>
        val r = evaluate(name, p)
        val mark = if (r.diverge) "DIFF" else "same"
        val expected = Golden_Diverge.contains(name -> p.label)
        if (r.diverge != expected) unexpected += 1
        println(
          f"  $mark%-4s ${p.label}%-28s ${
              if (r.diverge) "GOLDEN" else ""
            }%-7s json=[${truncate(r.json)}] cel=[${truncate(r.cel)}]"
        )
      }
    }
    println(f"unexpected: $unexpected")
    if (unexpected > 0) sys.exit(1)
  }
}
