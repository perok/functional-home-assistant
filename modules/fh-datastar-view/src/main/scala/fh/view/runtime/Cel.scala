package fh.view.runtime

import fh.view.model.{SlotValue, ValueType}

import dev.cel.common.{CelFunctionDecl, CelOverloadDecl}
import dev.cel.common.ast.CelExpr
import dev.cel.common.navigation.CelNavigableAst
import dev.cel.common.types.{
  CelKind,
  CelType,
  CelTypeProvider,
  ListType,
  MapType,
  OptionalType,
  SimpleType,
  StructType
}
import dev.cel.common.values.{NullValue, StructValue}
import dev.cel.compiler.{CelCompiler, CelCompilerFactory}
import dev.cel.extensions.{CelExtensions, CelOptionalLibrary}
import dev.cel.parser.CelParserFactory
import dev.cel.runtime.{
  CelFunctionBinding,
  CelFunctionOverload,
  CelRuntimeFactory,
  CelVariableResolver
}

import java.util.Optional

/** CEL on the planner runtime, compiled once at validation. Bindings are
  * `value` (the value before the step, typed as it; a CEL source has none),
  * `dashboard_slug` (ADR 0023) and `time_zone` (the process's zone, which
  * Supervisor sets to HA's), plus the named inputs the expression reads, typed.
  * A whole entity is the struct `fh.Entity`.
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

  private val entityType: StructType = StructType.create(
    "fh.Entity",
    com.google.common.collect.ImmutableSet
      .of("state", "attr", "entity_id", "domain"),
    {
      case "state" | "entity_id" | "domain" =>
        Optional.of[CelType](SimpleType.STRING)
      case "attr" =>
        Optional.of[CelType](MapType.create(SimpleType.STRING, SimpleType.DYN))
      case _ => Optional.empty[CelType]()
    }
  )

  private val types = new CelTypeProvider {
    def types(): com.google.common.collect.ImmutableCollection[CelType] =
      com.google.common.collect.ImmutableList.of(entityType)
    def findType(name: String): Optional[CelType] =
      if (name == entityType.name) Optional.of(entityType) else Optional.empty()
  }

  /** An entity as CEL reads it, built once per state
    * ([[EntityState.celValue]]).
    */
  private[runtime] final class EntityValue(e: EntityState)
      extends StructValue[String, EntityValue] {
    def celType(): CelType = entityType
    def select(field: String): Object = field match
      case "state"     => e.state
      case "attr"      => e.javaAttributes
      case "entity_id" => e.entityId
      case "domain"    => e.domain
      case other => throw new IllegalArgumentException(s"no field '$other'")
    def find(field: String): Optional[Object] = Optional.of(select(field))
    def isZeroValue(): Boolean = false
    def value(): EntityValue = this
    override def toString: String = e.entityId
  }

  private val compiler = CelCompilerFactory
    .standardCelCompilerBuilder()
    .setTypeProvider(types)
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
    .addVar("dashboard_slug", SimpleType.STRING)
    .addVar("time_zone", SimpleType.STRING)
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

  /** Read once: a running server does not follow a later change of HA's zone.
    */
  private val timeZone: String = java.time.ZoneId.systemDefault.getId

  /** Every expression may read these, so a named input may not take one. */
  val fixedNames: Set[String] = Set("value", "dashboard_slug", "time_zone")

  /** `env`: the named inputs the source reads, declared beside [[fixedNames]]
    * so a name the node does not declare fails here.
    */
  def parse(
      src: String,
      env: List[(String, ValueType)],
      value: Option[ValueType]
  ): Either[String, Program] =
    compiled(src, env, value).map(ast => runtime.createProgram(ast))

  /** What `src` produces, or why it does not compile. A result no step or slot
    * takes (a map, a timestamp) is refused here.
    */
  def resultType(
      src: String,
      env: List[(String, ValueType)],
      value: Option[ValueType]
  ): Either[String, ValueType] =
    compiled(src, env, value).flatMap(ast =>
      valueTypeOf(ast.getResultType).toRight(
        s"yields a ${ast.getResultType.name}, which a card cannot show — " +
          "make it text, `str(…)` or `string(…)`"
      )
    )

  private def compiled(
      src: String,
      env: List[(String, ValueType)],
      value: Option[ValueType]
  ) = {
    val trimmed = src.trim
    if (trimmed.isEmpty) Left("empty transform expression")
    else {
      val result = compilerWith(env, value).compile(trimmed)
      if (result.hasError) Left(s"invalid CEL: ${result.getErrorString}")
      else Right(result.getAst)
    }
  }

  /** An optional is `dyn`: it renders as what it holds, or `""`. */
  private def valueTypeOf(t: CelType): Option[ValueType] = t match
    case _: OptionalType => Some(ValueType.Dyn)
    case _               =>
      t.kind match
        case CelKind.STRING                  => Some(ValueType.Text)
        case CelKind.BOOL                    => Some(ValueType.Bool)
        case CelKind.INT | CelKind.UINT      => Some(ValueType.Int)
        case CelKind.DOUBLE                  => Some(ValueType.Real)
        case CelKind.DYN | CelKind.NULL_TYPE => Some(ValueType.Dyn)
        case CelKind.LIST                    =>
          valueTypeOf(t.parameters.get(0)).map(ValueType.List(_))
        case CelKind.STRUCT if t.name == entityType.name =>
          Some(ValueType.Entity)
        case _ => None

  private def compilerWith(
      env: List[(String, ValueType)],
      value: Option[ValueType]
  ): CelCompiler =
    if (env.isEmpty && value.isEmpty) compiler
    else
      (value.map("value" -> _).toList ++ env)
        .foldLeft(compiler.toCompilerBuilder()) { case (b, (name, ty)) =>
          b.addVar(name, celType(ty))
        }
        .build()

  private def celType(t: ValueType): CelType = t match
    case ValueType.Text | ValueType.Markup => SimpleType.STRING
    case ValueType.Int                     => SimpleType.INT
    case ValueType.Real                    => SimpleType.DOUBLE
    case ValueType.Bool                    => SimpleType.BOOL
    case ValueType.Dyn                     => SimpleType.DYN
    case ValueType.Entity                  => entityType
    case ValueType.List(of)                => ListType.create(celType(of))

  private val parser = CelParserFactory.standardCelParserBuilder().build()

  /** The names `src` reads, from its parse alone — no declarations needed, and
    * a word inside a string literal is not one. Empty when it does not parse,
    * which [[parse]] reports.
    */
  def identifiers(src: String): Set[String] = {
    val result = parser.parse(src.trim)
    if (result.hasError) Set.empty
    else {
      val b = Set.newBuilder[String]
      CelNavigableAst
        .fromAst(result.getAst)
        .getRoot
        .allNodes()
        .forEach { n =>
          if (n.expr().exprKind().getKind == CelExpr.ExprKind.Kind.IDENT)
            b += n.expr().ident().name()
        }
      b.result()
    }
  }

  private final class Resolver(
      value: Object,
      slug: String,
      values: Map[String, Object]
  ) extends CelVariableResolver {
    def find(name: String): Optional[Object] = name match {
      case "value"          => Optional.of(value)
      case "dashboard_slug" => Optional.of[Object](slug)
      case "time_zone"      => Optional.of[Object](timeZone)
      case other            =>
        values.get(other).fold(Optional.empty[Object]())(Optional.of(_))
    }
  }

  /** A value as CEL reads it: an absent attribute is CEL's `null`, and a
    * present list a Java one.
    */
  def arg(v: Any): Object = v match
    case null        => NullValue.NULL_VALUE
    case xs: List[?] =>
      val out = new java.util.ArrayList[Object](xs.size)
      xs.foreach(x => out.add(arg(x)))
      out
    case o: Object => o
    // Every JVM value is an Object; the compiler cannot see that of `Any`.
    case other => String.valueOf(other)

  /** The result as the engine gives it, for the next step; a failure returns
    * its message, contained to the card.
    */
  def eval(
      program: Program,
      value: Any,
      dashboardSlug: String,
      values: Map[String, Object] = Map.empty
  ): Any =
    try program.eval(new Resolver(arg(value), dashboardSlug, values))
    catch case e: Exception => s"cel error: ${errorText(e)}"

  /** Keeps a `bool` result boolean; a failure's message stays a String. */
  def runValue(
      program: Program,
      value: Any,
      dashboardSlug: String,
      values: Map[String, Object] = Map.empty
  ): SlotValue =
    eval(program, value, dashboardSlug, values) match
      case b: java.lang.Boolean => b.booleanValue
      case other                => stringify(other)

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
