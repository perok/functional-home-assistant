package fh.view.runtime

import dev.cel.common.{CelFunctionDecl, CelOverloadDecl}
import dev.cel.common.types.{MapType, SimpleType}
import dev.cel.compiler.CelCompilerFactory
import dev.cel.extensions.CelExtensions
import dev.cel.runtime.{
  CelFunctionBinding,
  CelFunctionOverload,
  CelRuntimeFactory
}

import java.util

/** A compile-once/eval-many CEL engine over the benchmark's entity context,
  * mirroring `Transforms`/`Transform.run` so the CEL-vs-JSONata comparison is
  * apples-to-apples (same shapes, same fixture states, same per-eval binding).
  */
object CelTransforms {

  /** JSONata's `$string` behaviour for the `str(...)` helper the more-info
    * shape's comprehension uses: scalars stringify directly (a Long/Integer as
    * decimal, a Double as its toString — the exact rendering the fixture
    * needs), a `List` as its elements joined with ", " (JSONata string()), and
    * null/empty as "".
    */
  private def stringLike(v: Any): String = v match
    case null                  => ""
    case s: String             => s
    case b: Boolean            => b.toString
    case l: Long               => l.toString
    case i: Integer            => i.toString
    case n: java.lang.Number   => n.toString
    case xs: java.util.List[?] =>
      val it = xs.iterator()
      val sb = new java.lang.StringBuilder("[")
      while (it.hasNext) {
        if (sb.length() > 1) {
          sb.append(",")
          ()
        }
        sb.append(stringLike(it.next()))
        ()
      }
      sb.append("]").toString
    case other => other.toString

  // A dyn-typed helper so the more-info shape can stringify heterogeneous
  // attribute values the way JSONata's $string does. Compiled as `str(x)`.
  private val STR_OVERLOAD = "str_dyn"
  private val strOverload = CelOverloadDecl
    .newBuilder()
    .setOverloadId(STR_OVERLOAD)
    .setIsInstanceFunction(false)
    .addParameterTypes(SimpleType.DYN)
    .setResultType(SimpleType.STRING)
    .build()
  private val strDecl = CelFunctionDecl
    .newFunctionDeclaration("str", strOverload)

  private val compiler = CelCompilerFactory
    .standardCelCompilerBuilder()
    .addLibraries(
      CelExtensions.bindings(),
      CelExtensions.strings(),
      CelExtensions.lists(),
      CelExtensions.math(),
      CelExtensions.comprehensions()
    )
    .addVar("state", SimpleType.STRING)
    .addVar("attr", MapType.create(SimpleType.STRING, SimpleType.DYN))
    .addVar("entity_id", SimpleType.STRING)
    .addVar("domain", SimpleType.STRING)
    .addVar("dashboard_slug", SimpleType.STRING)
    .addFunctionDeclarations(strDecl)
    .build()

  private val runtime = CelRuntimeFactory
    .plannerRuntimeBuilder()
    .addLibraries(
      CelExtensions.strings(),
      CelExtensions.lists(),
      CelExtensions.math(),
      CelExtensions.comprehensions()
    )
    .addFunctionBindings(
      CelFunctionBinding.from(
        STR_OVERLOAD,
        util.List.of(classOf[Object]),
        new CelFunctionOverload {
          def apply(args: Array[Object]): Object =
            if (args.isEmpty) ""
            else stringLike(args(0))
        }
      )
    )
    .build()

  /** A compiled CEL program over the entity context. */
  final class Program private[CelTransforms] (
      private val program: dev.cel.runtime.CelRuntime.Program
  ) {
    def run(entity: EntityState, dashboardSlug: String): String = {
      val activation = new util.HashMap[String, Object]()
      activation.put("state", entity.state)
      activation.put("attr", entity.javaAttributes)
      activation.put("entity_id", entity.entityId)
      activation.put("domain", entity.domain)
      activation.put("dashboard_slug", dashboardSlug)
      try stringify(program.eval(activation))
      catch case e: Exception => s"cel error: ${e.getMessage}"
    }
  }

  def parse(src: String): Program = {
    val result = compiler.compile(src)
    if (result.hasError)
      throw new IllegalStateException(
        s"cel failed to compile: $src\n${result.getErrorString}"
      )
    new Program(runtime.createProgram(result.getAst))
  }

  /** Stringify a CEL result exactly as `Transform.asString` would a JSONata
    * result, so the two engines land on identical slot values.
    */
  private def stringify(v: Any): String = v match
    case null                 => ""
    case s: String            => s
    case b: java.lang.Boolean => b.toString
    case l: java.lang.Long    => l.toString
    case i: java.lang.Integer => i.toString
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
