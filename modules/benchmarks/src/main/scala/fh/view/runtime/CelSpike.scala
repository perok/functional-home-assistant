package fh.view.runtime

import fh.view.model.Transform

/** Parity gate for the CEL translations: every CEL shape must render the SAME
  * string the JSONata engine does, on the benchmark's own fixture states. If it
  * does not, the comparison would price two different computations.
  */
object CelSpike {
  def main(args: Array[String]): Unit = {
    val st = RenderBench.states(12)
    // Only the shapes that reach an ENGINE: the trivials resolve via
    // Transform.Direct (never JSONata), so there is no CEL counterpart.
    val jsonShapes = RenderBench.LiveTransforms :+ RenderBench.TransformComplex
    val celShapes = CelShapes.all
    var failures = 0
    jsonShapes.zip(celShapes).foreach { case (jsonExpr, celExpr) =>
      val compiled = Transform
        .parse(jsonExpr)
        .fold(
          e => sys.error(s"jsonata won't compile: $jsonExpr ($e)"),
          identity
        )
      val program = CelTransforms.parse(celExpr)
      st.values.foreach { e =>
        val jr = Transform.run(compiled, e, "dashboard")
        val cr = program.run(e, "dashboard")
        if (jr != cr) {
          failures += 1
          println(s"MISMATCH for [$jsonExpr]")
          println(s"  jsonata: [$jr]")
          println(s"  cel:     [$cr]")
        }
      }
      println(s"parity checked: $jsonExpr")
    }
    if (failures == 0) println("ALL PARITY OK")
    else sys.error(s"$failures mismatches")
  }
}
