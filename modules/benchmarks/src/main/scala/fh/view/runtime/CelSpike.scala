package fh.view.runtime

import fh.view.model.Transform
import io.circe.Json

/** Divergence gate for the CEL engine swap (ADR 0027).
  *
  * Pairing the shipped-intended JSONata shapes against the SHIPPED CEL bytes,
  * this sweeps BOTH the benchmark's real-world fixture and a curated margin set
  * (empty-string attrs, fractional values, odd types, absent attrs) and records
  * — not assumes — where the two engines part. The rendered bytes of the
  * affected slots are the contract, and the divergence table this prints is
  * that contract: the JSONata side runs through the bench-local [[Jsonata]]
  * reference (the retired engine, byte-for-byte), the CEL side through the
  * production [[fh.view.model.Transform]].
  *
  * Run with `sbt 'benchmarks/Compile/runMain fh.view.runtime.CelSpike'`. The
  * exit code is non-zero iff the observed divergences stop matching the golden
  * table below — so the swap's output delta stays pinned and a NEW divergence
  * (a drift in either engine — a cel-java upgrade, a reference edit — or a
  * wrong translation) fails loudly.
  */
object CelSpike {

  // The slider fill exactly as slider.pkl baked it for a light (min 1, max
  // 255), WITH the `$string(...) & "%"` wrapper — the CORRECTED bytes. (The
  // pre-swap string in slider.pkl carried a stray paren and did not compile;
  // that defect was itself a Phase-0 finding, and the CEL re-authoring drops
  // it by construction — as the swap itself re-proved when the gate caught a
  // reintroduced one.)
  // The CEL side is the shipped `CelShapes.TransformFill` (a `str(...)` +
  // '%' wrapper around the same arithmetic).

  private final case class Shape(json: String, cel: String)

  private val Shapes: List[(String, Shape)] = List(
    "name" -> Shape(Jsonata.TransformName, CelShapes.TransformName),
    "unit" -> Shape(Jsonata.TransformUnit, CelShapes.TransformUnit),
    "fill" -> Shape(Jsonata.TransformFill, CelShapes.TransformFill),
    "percent" -> Shape(
      Jsonata.TransformPercent,
      CelShapes.TransformPercent
    ),
    "fillColor" -> Shape(
      Jsonata.TransformFillColor,
      CelShapes.TransformFillColor
    ),
    "attrLines" -> Shape(
      Jsonata.TransformAttrLines,
      CelShapes.TransformAttrLines
    ),
    "complex" -> Shape(Jsonata.TransformComplex, CelShapes.TransformComplex)
  )

  private final case class Probe(label: String, entity: EntityState)

  private def es(state: String, attrs: (String, Json)*): EntityState =
    EntityState("light.probe", state, attrs.toMap)

  private final case class Compiled(
      jc: Jsonata.Compiled,
      cp: Transform.Compiled
  )

  private lazy val compiled: Map[String, Compiled] = Shapes.map {
    case (name, s) =>
      name -> Compiled(
        Jsonata
          .parse(s.json)
          .fold(
            e => sys.error(s"jsonata won't compile: $name [$e]"),
            identity
          ),
        Transform
          .parse(s.cel)
          .fold(
            e => sys.error(s"cel won't compile: $name [$e]"),
            identity
          )
      )
  }.toMap

  private final case class Result(diverge: Boolean, json: String, cel: String)

  private def evaluate(name: String, p: Probe): Result = {
    val c = compiled(name)
    val jr = Jsonata.run(c.jc, p.entity, "dashboard")
    val cr = Transform.run(c.cp, p.entity, "dashboard")
    Result(jr != cr, jr, cr)
  }

  private def truncate(s: String, n: Int = 80): String =
    if (s.length <= n) s else s.take(n) + "…"

  /** The pinned divergence contract: (shape, probeLabel) pairs that MUST
    * diverge. Every other (shape, probe) must agree byte-for-byte. Measured and
    * occupied from the run of the SHIPPED CEL bytes — never extended silently.
    * Fifteen rows, four classes:
    *
    *   - Empty-string presence (2): `friendly_name=""`, `unit=""`. JSONata
    *     reads "" falsy and falls back (`light.probe`, no trailing space);
    *     CEL's `'k' in attr` sees it as present (`""` vs `entity_id`, `"on "`
    *     trailing space). The known, deliberate cost of the presence idiom.
    *   - Number stringification (11): the fill fixture rows whose fill is a
    *     non-terminating fraction. JSONata's `$string` renders ~15 significant
    *     digits (`97.244094488189%`), the shared numToString rounds at 10
    *     decimals HALF_UP (`97.2440944882%`). The twelfth fixture — the one
    *     whose fill is exactly 100% — agrees, as do all integer fills.
    *   - Error text (1): `brightness="on"` — each engine's wording for the same
    *     type failure, inside `Transform.error` / the bench reference.
    *   - Half-away vs HALF_EVEN (1): `cover 63.5 (true half)` — an
    *     exactly-representable .5 from the 100-scaled cover range rounds 36.5
    *     to 37 (HALF_UP) vs 36 (HALF_EVEN). Unreachable on integer attributes,
    *     so only this probe can see it.
    *
    * The inverse is the result that makes the swap safe: every `percent` and
    * `attrLines` probe agrees byte-for-byte, and the whole `fillColor` set —
    * whose Phase-0 translation diverged on every kelvin probe — now agrees too:
    * the shipped `str(...)` strips the `math.round` `.0` the native
    * `string(...)` left, and `double(k)` removes the Long/float mix that
    * produced it. Fixture row order is Scala-Map hash order — deterministic for
    * a run, and any reorder only moves which row is the integral one.
    */
  private val Golden_Diverge: Set[(String, String)] = Set(
    "name" -> "friendly_name=\"\"",
    "unit" -> "unit=\"\"",
    "fill" -> "brightness=\"on\"",
    "complex" -> "cover 63.5 (true half)"
  ) ++ Set(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11).map(i => "fill" -> s"fixture[$i]")

  def main(args: Array[String]): Unit = {
    val fixture =
      RenderBench.states(12).toList.zipWithIndex.map { case ((_, e), i) =>
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
