package fh.view.runtime

import fh.view.model.SlotValue

import dev.cel.common.{CelFunctionDecl, CelOverloadDecl}
import dev.cel.common.types.{MapType, SimpleType}
import dev.cel.compiler.CelCompilerFactory
import dev.cel.extensions.{CelExtensions, CelOptionalLibrary}
import dev.cel.runtime.{
  CelFunctionBinding,
  CelFunctionOverload,
  CelRuntimeFactory,
  CelVariableResolver
}

import java.util.Optional

/** CEL on the planner runtime, compiled once at validation. Bindings are
  * `state`, `attr`, `entity_id`, `domain` and `dashboard_slug` (ADR 0023).
  *
  * Two helpers: `str(x)` renders numbers through [[numToString]], so the engine
  * and the `Simple` tier cannot drift, and lists as `[a,b]`; `num(x)` parses a
  * numeric string and errors on anything else, so the card shows the failure
  * rather than a blank.
  *
  * Compiler and runtime are shared process-wide: compilation is idempotent and
  * each evaluation takes its own resolver.
  */
object Cel {

  // By class name, to avoid coupling to a values class for one sentinel.
  private def isNullValue(v: Any): Boolean =
    v == null || v.getClass.getName == "dev.cel.common.values.NullValue"

  type Program = dev.cel.runtime.CelRuntime.Program

  /** Both sides of `TransformSuite`'s byte-equality, so one definition. */
  private[view] def numToString(d: Double): String =
    if (d.isNaN || d.isInfinite) d.toString
    else if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong.toString
    else
      // The Java `valueOf`, not Scala's `BigDecimal`, which allocates a
      // `MathContext` per value; byte-identical (checked over 500k doubles).
      java.math.BigDecimal
        .valueOf(d)
        .setScale(10, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros
        .toPlainString

  private def stringLike(v: Any): String = v match
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
    // Deliberately differs from a bare list result, which renders `[a, b]`.
    case scalar => stringify(scalar)

  private def parseNum(v: Any): java.lang.Double = v match
    case n: java.lang.Number =>
      n.doubleValue
    case s: String =>
      try java.lang.Double.parseDouble(s)
      catch
        case _: NumberFormatException =>
          throw new IllegalArgumentException(s"cannot parse number: \"$s\"")
    case other =>
      throw new IllegalArgumentException(s"cannot parse number: $other")

  private val STR_OVERLOAD = "str_dyn"
  private val NUM_OVERLOAD = "num_dyn"

  private def dynToStringOverload(id: String): CelOverloadDecl =
    CelOverloadDecl
      .newBuilder()
      .setOverloadId(id)
      .setIsInstanceFunction(false)
      .addParameterTypes(SimpleType.DYN)
      .setResultType(SimpleType.STRING)
      .build()

  private def dynToDoubleOverload(id: String): CelOverloadDecl =
    CelOverloadDecl
      .newBuilder()
      .setOverloadId(id)
      .setIsInstanceFunction(false)
      .addParameterTypes(SimpleType.DYN)
      .setResultType(SimpleType.DOUBLE)
      .build()

  private val strDecl = CelFunctionDecl.newFunctionDeclaration(
    "str",
    dynToStringOverload(STR_OVERLOAD)
  )
  private val numDecl = CelFunctionDecl.newFunctionDeclaration(
    "num",
    dynToDoubleOverload(NUM_OVERLOAD)
  )

  private val compiler = CelCompilerFactory
    .standardCelCompilerBuilder()
    .addLibraries(
      CelExtensions.bindings(),
      CelExtensions.strings(),
      CelExtensions.lists(),
      CelExtensions.math(),
      CelExtensions.comprehensions(),
      // On both builders, or the runtime cannot evaluate what compiles.
      CelOptionalLibrary.INSTANCE
    )
    // No macros: `has(attr.x)` is an undeclared reference here (cel 0.14.0).
    // `setStandardMacros(CelStandardMacro.HAS)` would enable just that one.
    .addVar("state", SimpleType.STRING)
    .addVar("attr", MapType.create(SimpleType.STRING, SimpleType.DYN))
    .addVar("entity_id", SimpleType.STRING)
    .addVar("domain", SimpleType.STRING)
    .addVar("dashboard_slug", SimpleType.STRING)
    .addFunctionDeclarations(strDecl, numDecl)
    .build()

  private val runtime = CelRuntimeFactory
    .plannerRuntimeBuilder()
    .addLibraries(
      CelExtensions.strings(),
      CelExtensions.lists(),
      CelExtensions.math(),
      CelExtensions.comprehensions(),
      CelOptionalLibrary.INSTANCE
    )
    .addFunctionBindings(
      CelFunctionBinding.from(
        STR_OVERLOAD,
        java.util.List.of(classOf[Object]),
        new CelFunctionOverload {
          def apply(args: Array[Object]): Object =
            if (args.isEmpty) ""
            else stringLike(args(0))
        }
      ),
      CelFunctionBinding.from(
        NUM_OVERLOAD,
        java.util.List.of(classOf[Object]),
        new CelFunctionOverload {
          def apply(args: Array[Object]): Object =
            parseNum(args(0))
        }
      )
    )
    .build()

  // The compile-time optimizers were measured and declined: no CPU gain and
  // ~1-2% more allocation per eval, since no shipped shape repeats a subtree.

  def parse(src: String): Either[String, Program] = {
    val trimmed = src.trim
    if (trimmed.isEmpty) Left("empty transform expression")
    else {
      val result = compiler.compile(trimmed)
      if (result.hasError) Left(s"invalid CEL: ${result.getErrorString}")
      else Right(runtime.createProgram(result.getAst))
    }
  }

  /** On demand, so an expression reading no attribute never forces
    * [[EntityState.javaAttributes]].
    */
  private final class EntityResolver(entity: EntityState, slug: String)
      extends CelVariableResolver {
    def find(name: String): Optional[Object] = name match {
      case "state"     => Optional.ofNullable[Object](entity.state)
      case "attr"      => Optional.ofNullable[Object](entity.javaAttributes)
      case "entity_id" => Optional.ofNullable[Object](entity.entityId)
      case "domain"    => Optional.ofNullable[Object](entity.domain)
      case "dashboard_slug" => Optional.ofNullable[Object](slug)
      case _                => Optional.empty()
    }
  }

  /** A failure returns its message, contained to the card. */
  def run(
      program: Program,
      entity: EntityState,
      dashboardSlug: String
  ): String =
    SlotValue.text(runValue(program, entity, dashboardSlug))

  /** Keeps a `bool` result boolean; a failure's message stays a String. */
  def runValue(
      program: Program,
      entity: EntityState,
      dashboardSlug: String
  ): SlotValue =
    try
      program.eval(new EntityResolver(entity, dashboardSlug)) match
        case b: java.lang.Boolean => b.booleanValue
        case other                => stringify(other)
    catch case e: Exception => s"cel error: ${errorText(e)}"

  /** Also the `Simple` tier's renderer, so the two cannot drift. Null and an
    * empty `Optional` both render `""`; without the latter case the DOM shows
    * `Optional.empty`.
    */
  private[view] def stringify(result: Any): String = result match
    case n if isNullValue(n)  => ""
    case o: Optional[?]       => if (o.isPresent) stringify(o.get) else ""
    case s: String            => s
    case b: java.lang.Boolean => b.toString
    case l: java.lang.Long    => l.toString
    case i: java.lang.Integer => i.toString
    case n: java.lang.Number  => numToString(n.doubleValue)
    case other                => String.valueOf(other)

  private def errorText(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
}
