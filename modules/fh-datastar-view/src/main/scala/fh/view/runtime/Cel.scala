package fh.view.runtime

import dev.cel.common.{CelFunctionDecl, CelOverloadDecl}
import dev.cel.common.types.{MapType, SimpleType}
import dev.cel.compiler.CelCompilerFactory
import dev.cel.extensions.CelExtensions
import dev.cel.runtime.{
  CelFunctionBinding,
  CelFunctionOverload,
  CelRuntimeFactory,
  CelVariableResolver
}

import java.util.Optional

/** The per-slot value-transform engine: CEL, compiled once at build/validate
  * time and evaluated per live value on the planner runtime (the engine the
  * plan's Phase-0 sweep validated against — a swap inside this object is a
  * measured, golden-gated decision).
  *
  * The entity rides in as five activations: `state` (its raw state String),
  * `attr` (its full attribute map), `entity_id`, `domain` (from the id) and
  * `dashboard_slug` (the dashboard being rendered — the one binding that is not
  * about the entity, see ADR 0023). Only this entity is reachable — lookups are
  * same-entity only — and there is no bare `$`: the slot value is whatever the
  * expression returns.
  *
  * Two helpers bridge the places CEL has no native equivalent:
  *
  *   - `str(x)` — JSONata's `$string` over heterogeneous attribute values, with
  *     numbers rendered by [[numToString]] so the engine and the direct fast
  *     path cannot drift (a Long/Integer as its decimal, a Double via the
  *     catalog's 10-digit rounding). Lists render as `[a,b]` (the more-info
  *     block's `rgb_color`), null/empty as `""`.
  *   - `num(x)` — `$number`'s replacement: a numeric String → Double (a Number
  *     passes through). A String that is not a number errors like `$number`
  *     did, so the card shows the failure rather than a blank.
  *
  * The compiler and planner runtime are process singletons: compilation is
  * idempotent over a source string and evaluation takes its own activation map
  * per call, so one engine is safely shared across every fiber rendering.
  */
object Cel {

  /** The sentinel a CEL `null` result arrives as when the planner path adapts
    * it (`dev.cel.common.values.NullValue` — named by class, not imported, so
    * the engine does not couple to a values-class for one sentinel).
    */
  private def isNullValue(v: Any): Boolean =
    v == null || v.getClass.getName == "dev.cel.common.values.NullValue"

  /** A compiled CEL program over the entity context. */
  type Program = dev.cel.runtime.CelRuntime.Program

  // ---- the two registered helpers ----

  private def numToString(d: Double): String =
    if (d.isNaN || d.isInfinite) d.toString
    else if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong.toString
    else
      BigDecimal(d)
        .setScale(10, BigDecimal.RoundingMode.HALF_UP)
        .bigDecimal
        .stripTrailingZeros
        .toPlainString

  /** `str(x)` over heterogeneous attribute values — the same rendering the
    * engine applies to a bare result, so `str(v)` and a bare `v` never part.
    */
  private def stringLike(v: Any): String = v match
    case n if isNullValue(n)   => ""
    case s: String             => s
    case b: java.lang.Boolean  => b.toString
    case l: java.lang.Long     => l.toString
    case i: java.lang.Integer  => i.toString
    case n: java.lang.Number   => numToString(n.doubleValue)
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

  /** `num(x)` — `$number`'s replacement. A numeric String parses to Double and
    * a Number passes through; anything else is an evaluation error (a card
    * shows "cel error: …", contained, exactly as `$number` used to).
    */
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
      CelExtensions.comprehensions()
    )
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
      CelExtensions.comprehensions()
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

  // The cel-java compile-time optimizers (ConstantFoldingOptimizer +
  // SubexpressionOptimizer, the codelab's Exercise 8) were MEASURED and
  // declined: on the shipped shapes they bought no CPU (all differences inside
  // the bench's error bars) and cost a consistent ~1-2% MORE allocation per
  // eval — the planner materializes a folded constant node where inline
  // arithmetic was free, and our expressions carry no repeated subtree for CSE
  // to extract (each attribute read appears once). Revisit only if a shape
  // with genuinely repeated subtrees ships; the gate + suites pin the values
  // either way.

  /** Compile a CEL expression (build/validate time and the gate's CEL side).
    */
  def parse(src: String): Either[String, Program] = {
    val trimmed = src.trim
    if (trimmed.isEmpty) Left("empty transform expression")
    else {
      val result = compiler.compile(trimmed)
      if (result.hasError) Left(s"invalid CEL: ${result.getErrorString}")
      else Right(runtime.createProgram(result.getAst))
    }
  }

  /** The five bindings, resolved ON DEMAND: the planner asks only for what the
    * expression reads, so nothing is materialized per evaluation — no HashMap
    * to build, and an expression that reads no attribute (a bare `state`
    * concat) never forces [[EntityState.javaAttributes]] either. The whole
    * per-eval cost is this one small resolver object.
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

  /** Evaluate a compiled program against one entity, stringified for the
    * template. The dashboard's slug binds `dashboard_slug` (ADR 0023); on
    * evaluation failure the CEL error message is returned so the card shows it
    * — contained, never thrown into the render. A `null` result becomes `""` so
    * the slot's `default` can take over.
    */
  def run(
      program: Program,
      entity: EntityState,
      dashboardSlug: String
  ): String =
    try
      stringify(
        program.eval(new EntityResolver(entity, dashboardSlug))
      )
    catch case e: Exception => s"cel error: ${errorText(e)}"

  /** Stringify a CEL result the way a string-coercing operator would, so a bare
    * number and a `str(...)` number land identically on the slot. Null becomes
    * "" so the slot's `default` can take over.
    */
  private def stringify(result: Any): String = result match
    case n if isNullValue(n)  => ""
    case s: String            => s
    case b: java.lang.Boolean => b.toString
    case l: java.lang.Long    => l.toString
    case i: java.lang.Integer => i.toString
    case n: java.lang.Number  => numToString(n.doubleValue)
    case other                => String.valueOf(other)

  private def errorText(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
}
