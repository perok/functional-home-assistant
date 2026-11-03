package fh.view.model

import cats.syntax.all.*
import fh.view.history.ChartStyle
import io.circe.{ACursor, Decoder, DecodingFailure}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

/** What a slot shows: a value read from its [[source]], then each step run on
  * it in order (`docs/plan-slot-values.md`). On the wire a bare JSON primitive
  * is a literal, and every other shape an object whose `kind` names its case.
  *
  * @param default
  *   what an empty result shows instead, typed as the slot is.
  */
final case class ValueExpr(
    source: Value,
    steps: List[Step] = Nil,
    default: Option[Lit] = None
) derives CanEqual:

  /** A literal with nothing run on it but the theme's swap, which the renderer
    * applies as it paints: a value that never moves. A number is its text.
    */
  def constant: Option[SlotValue] = (source, steps) match
    case (Value.Literal(l), Nil | List(Step.ThemeClasses)) =>
      Some(l match
        case Lit.Bool(b) => b
        case other       => Transform.text(other.raw))
    case _ => None

  /** A [[constant]] that is text. */
  def literal: Option[String] = constant.collect { case s: String => s }

  /** The subject resolved: a source [[Value.Entity]] that names none reads
    * `subject`, and a step's operand that names none its source's entity, else
    * `subject`. `None` leaves one unnamed, which `validate` refuses.
    */
  def about(subject: Option[String]): ValueExpr =
    val src = Value.about(subject)(source)
    val own = src match
      case Value.Entity(of, _, _, _) => of
      case _                         => subject
    if (subject.isEmpty && own.isEmpty) this
    else ValueExpr(src, steps.map(Step.about(own)), default)

  /** Every entity a step's operand names: what wakes the slot besides its
    * source.
    */
  def operandEntities: List[String] =
    steps.flatMap(Step.operands).flatMap(Value.entityOf)

  /** Whether any read names no entity after [[about]]: on a card with none. */
  def readsMissingSubject: Boolean =
    (source :: steps.flatMap(Step.operands)).exists {
      case Value.Entity(None, _, _, _) => true
      case Value.List(items)           => items.exists(_.readsMissingSubject)
      case _                           => false
    }

  /** Injective, for signal names and the once-cache. The source's own entity is
    * not in it: the signal path names that entity.
    */
  lazy val key: String = keyBy(Value.sourceKey)

  /** As a named input, whose entity no signal path names. */
  lazy val inputKey: String = keyBy(Value.key)

  private def keyBy(src: Value => String): String =
    src(source) +
      steps.map(s => "|" + Step.key(s)).mkString +
      default.fold("")(d => s"|default=${Lit.key(d)}")

  /** Every CEL's named inputs, its source's and its steps'. */
  def celInputs: Map[String, ValueExpr] =
    (source match
      case Value.Cel(_, in) => in
      case _                => Map.empty
    ) ++ steps.collect { case c: Step.Cel => c.inputs }.flatten

  /** Every CEL source, in order. */
  def celSources: List[String] =
    (source match
      case Value.Cel(src, _) => List(src)
      case _                 => Nil
    ) ++ steps.collect { case c: Step.Cel => c.src }

  /** The type it is declared as when a named input: a literal, a count, the ids
    * present, a condition, a fetch, or an entity it names. `None` for any other
    * shape, which `validate` refuses.
    */
  def inputType: Option[ValueType] = (source, steps) match
    case _ if isCondition =>
      typed(Nil).toOption.map(_.out).filter(_ == ValueType.Bool)
    case (_: Value.Present, List(Step.Count)) => Some(ValueType.Int)
    case (_: Value.Cel | _: Value.List, _) | (Value.Entity(None, _, _, _), _) |
        (_, _ :: _) =>
      None
    case _ => typed(Nil).toOption.map(_.out)

  /** Decided by `Conditions`, without the engine: no CEL, and a step only a
    * condition runs. It reads an attribute as its JSON text, which the
    * renderer's own reads do not.
    */
  lazy val isCondition: Boolean =
    celSources.isEmpty && steps.exists {
      case Step.AllOf | Step.AnyOf | Step.Not | _: Step.Compare |
          _: Step.First =>
        true
      case _ => false
    }

  /** Why it cannot decide where only a condition may: a clause, a state
    * surface, a guard, an order term.
    */
  def conditionError: Option[String] =
    typed(Nil) match
      case Left(m)                     => Some(m.message)
      case Right(t) if t.keys.nonEmpty =>
        Some("a condition is read without CEL")
      case Right(t) if t.out != ValueType.Bool =>
        Some(s"a condition is a bool, not ${t.out.name}")
      case Right(_) => None

  /** Its type checked in order: each CEL compiled with `value` declared as the
    * type before it, beside the named inputs `env` types, and what the last
    * step produces.
    */
  def typed(
      env: List[(String, ValueType)]
  ): Either[ValueExpr.Mistyped, ValueExpr.Typed] =
    def cel(
        src: String,
        value: Option[ValueType],
        keys: List[Transform.CelKey]
    ) =
      val k = Transform.CelKey(src, env, value)
      Transform
        .resultType(k)
        .left
        .map(ValueExpr.Mistyped(_, Some(src)))
        .map(ValueExpr.Typed(keys :+ k, _))
    def plain(t: ValueType) = Right(ValueExpr.Typed(Nil, t))
    val start = source match
      case Value.Cel(src, _)                   => cel(src, None, Nil)
      case Value.List(items)                   => ValueExpr.listOf(items, env)
      case Value.Literal(l)                    => plain(ValueType.of(l))
      case Value.Entity(_, Part.State, _, _)   => plain(ValueType.Text)
      case Value.Entity(_, _: Part.Attr, _, _) => plain(ValueType.Dyn)
      case Value.Entity(_, Part.Whole, _, _)   => plain(ValueType.Entity)
      case Value.Fetch(_)                      => plain(ValueType.Series)
      case Value.Present(_, when)              =>
        when.toList
          .sortBy(_._1)
          .collectFirstSome((id, g) =>
            g.conditionError.map(e => s"the guard on '$id': $e")
          ) match
          case Some(e) => Left(ValueExpr.Mistyped(e))
          case None    => plain(ValueType.List(ValueType.Text))
    val firstErrors = steps.zipWithIndex.collectFirstSome {
      case (Step.First(_), i) if i > 0 || !source.isInstanceOf[Value.Present] =>
        Some("a first step comes straight after a set's present candidates")
      case (Step.First(orderBy), _) =>
        orderBy.collectFirstSome(_.readError)
      case _ => None
    }
    val checked = firstErrors.fold(start)(e => Left(ValueExpr.Mistyped(e)))
    steps.foldLeft(checked)((acc, step) =>
      acc.flatMap(t =>
        step match
          case Step.Cel(src, _) => cel(src, Some(t.out), t.keys)
          case s                =>
            ValueType
              .after(s, t.out)
              .left
              .map(ValueExpr.Mistyped(_))
              .map(o => t.copy(out = o))
      )
    )

  /** Live: what it answers moves, so a once-read cannot hold it. */
  def isLive: Boolean = source match
    case Value.Literal(_)     => false
    case Value.Cel(_, inputs) => inputs.values.exists(_.isLive)
    case Value.List(items)    => items.exists(_.isLive)
    case _                    => true

  /** Every entity that can move it. */
  def entities: List[String] =
    Value.entityOf(source) ++ operandEntities ++
      steps.collect { case Step.First(o) => o.flatMap(_.entities) }.flatten

object ValueExpr:

  /** [[ValueExpr.typed]]'s answer: the CEL it compiles, in order, and its type.
    */
  final case class Typed(keys: List[Transform.CelKey], out: ValueType)

  /** Why it does not type; `cel` is the CEL source at fault, if one is. */
  final case class Mistyped(message: String, cel: Option[String] = None)

  /** Its items decide without the engine, as a condition does, so a CEL item is
    * refused rather than compiled.
    */
  private def listOf(
      items: List[ValueExpr],
      env: List[(String, ValueType)]
  ): Either[Mistyped, Typed] =
    items.traverse(_.typed(env)).flatMap { ts =>
      ts.map(_.out).distinct match
        case _ if ts.exists(_.keys.nonEmpty) =>
          Left(Mistyped("a list's items are read without CEL"))
        case List(t) => Right(Typed(Nil, ValueType.List(t)))
        case Nil     => Left(Mistyped("a list has no items"))
        case outs    =>
          Left(
            Mistyped(
              "a list's items are one type, not " +
                outs.map(_.name).mkString(" and ")
            )
          )
    }

  def env(inputs: Map[String, ValueExpr]): List[(String, ValueType)] =
    inputs.toList.sortBy(_._1).flatMap((n, v) => v.inputType.map(n -> _))

  def literal(s: String): ValueExpr = ValueExpr(Value.Literal(Lit.Text(s)))

  val fieldNames: Set[String] = Set("source", "steps", "default")

  /** A bare primitive, or `{source, steps, default}`. */
  given Decoder[ValueExpr] = Decoder.instance(c =>
    if (!c.value.isObject) c.as[Lit].map(l => ValueExpr(Value.Literal(l)))
    else Strict.only(c, fieldNames) *> fields(c)
  )

  /** The fields of an object that may hold others (a template slot's). */
  def fields(c: ACursor): Decoder.Result[ValueExpr] =
    (
      c.get[Value]("source"),
      c.getOrElse[List[Step]]("steps")(Nil),
      c.getOrElse[Option[Lit]]("default")(None)
    ).mapN(ValueExpr.apply)

/** What a source or step produces, checked in order through a [[ValueExpr]]'s
  * steps. A list is not [[Dyn]]: a series typed as one would turn a build error
  * into error text on a wall panel.
  */
enum ValueType derives CanEqual:
  case Text, Bool, Int, Real, Dyn, Entity

  /** A chart's SVG, which only a raw hole takes. */
  case Markup
  case List(of: ValueType)

  def name: String = this match
    case List(of) => s"list of ${of.name}"
    case Entity   => "whole entity"
    case Markup   => "markup"
    case other    => other.toString.toLowerCase

object ValueType:

  /** A fetch's answer: `[epochMillis, value]` pairs. */
  val Series: ValueType = List(List(Real))

  private val numberLike = Set[ValueType](Text, Int, Real, Dyn)

  def of(l: Lit): ValueType = l match
    case Lit.Text(_)  => Text
    case Lit.Bool(_)  => Bool
    case Lit.Whole(_) => Int
    case Lit.Real(_)  => Real

  /** What a step other than CEL makes of `in`. */
  def after(step: Step, in: ValueType): Either[String, ValueType] =
    def takes(ok: Boolean, what: String, out: ValueType) =
      Either.cond(
        ok,
        out,
        s"has a ${Step.label(step)} step, which takes $what, after a ${in.name}"
      )
    step match
      case _: Step.Prefix | _: Step.Suffix | _: Step.WithUnit |
          _: Step.Percent | _: Step.Fill | _: Step.Duration =>
        takes(numberLike(in), "a number or text", Text)
      case m: Step.Match =>
        Right(if (Step.allBoolean(m)) Bool else Text)
      case Step.Count =>
        takes(in match { case List(_) => true; case _ => false }, "a list", Int)
      case Step.AllOf | Step.AnyOf =>
        takes(in == List(Bool), "a list of bools", Bool)
      case _: Step.Compare   => takes(numberLike(in), "a number or text", Bool)
      case Step.Not          => takes(in == Bool, "a bool", Bool)
      case _: Step.First     => takes(in == List(Text), "a set's ids", Text)
      case _: Step.Chart     => takes(in == Series, "a fetch's series", Markup)
      case Step.ThemeClasses => takes(in == Text, "text", Text)
      case _: Step.Cel       => Right(Dyn)

  /** What a slot of `slotType` may produce: a text slot shows a number as its
    * text, and `dyn` passes either, since an attribute or CEL's `dyn` may be
    * anything.
    */
  def fits(out: ValueType, slotType: SlotType): Boolean = slotType match
    case SlotType.Text => numberLike(out) || out == Markup
    case SlotType.Bool => out == Bool || out == Dyn

/** A literal as Pkl wrote it. A number is typed by its spelling: a Pkl `Float`
  * `2.0` and an `Int` `2` are one value, and CEL's compiler types them apart.
  */
enum Lit derives CanEqual:
  case Text(v: String)
  case Bool(v: Boolean)
  case Whole(v: Long)
  case Real(v: Double)

  def raw: Any = this match
    case Text(s)  => s
    case Bool(b)  => b
    case Whole(n) => n
    case Real(d)  => d

object Lit:

  def key(l: Lit): String = l match
    case Text(s)  => s"s${s.length}:$s"
    case Bool(b)  => s"b:$b"
    case Whole(n) => s"i:$n"
    case Real(d)  => s"d:$d"

  given Decoder[Lit] = Decoder.instance { c =>
    c.value.fold(
      Left(DecodingFailure("a literal cannot be null", c.history)),
      b => Right(Bool(b)),
      n =>
        Right(
          if (n.toString.exists(ch => ch == '.' || ch == 'e' || ch == 'E'))
            Real(n.toDouble)
          else n.toLong.fold(Real(n.toDouble))(Whole(_))
        ),
      s => Right(Text(s)),
      _ => Left(DecodingFailure("a literal cannot be a list", c.history)),
      _ => Left(DecodingFailure("a literal cannot be an object", c.history))
    )
  }

/** What a value is read from. */
enum Value derives CanEqual:
  case Literal(v: Lit)

  /** `of = None` reads the entity this is about, which [[ValueExpr.about]]
    * names: the card's subject, or for a step's operand its source's entity.
    */
  case Entity(
      of: Option[String] = None,
      part: Part = Part.State,
      reads: String = Reads.Live,
      bypassUnavailable: Boolean = true
  )
  case Fetch(fetch: FetchTemplate)

  /** The ids of `candidates` present now: those with no guard, and those whose
    * guard in `when` holds, an unnamed read in it reading that candidate. A
    * [[List]] of reads filtered by their guards would say the same, at about
    * four times the bytes per candidate; one source keeps a candidate its id.
    */
  case Present(
      candidates: scala.List[String] = Nil,
      when: Map[String, ValueExpr] = Map.empty
  )

  /** Its items in order, one type ([[ValueExpr.typed]]): what an and/or runs
    * [[Step.AllOf]] or [[Step.AnyOf]] over.
    */
  case List(items: scala.List[ValueExpr])

  /** Computed from nothing before it: CEL over its named inputs and the
    * renderer's bindings, with no `value`.
    */
  case Cel(src: String, inputs: Map[String, ValueExpr] = Map.empty)

object Value:

  def about(subject: Option[String])(v: Value): Value = v match
    case e: Entity if e.of.isEmpty => e.copy(of = subject)
    case List(items)               => List(items.map(_.about(subject)))
    case other                     => other

  def entityOf(v: Value): scala.List[String] = v match
    case Entity(of, _, _, _) => of.toList
    case Present(cs, when)   => cs ++ when.values.flatMap(_.entities)
    case List(items)         => items.flatMap(_.entities)
    case _                   => Nil

  /** A source's key, its entity left out ([[ValueExpr.key]]). A plain state
    * read keys as `state`, which the signal path spells out.
    */
  def sourceKey(v: Value): String = v match
    case Literal(l)                 => s"lit:${Lit.key(l)}"
    case Entity(_, part, _, bypass) =>
      Part.key(part) + (if (bypass) "" else "!b")
    case Fetch(f)          => s"fetch:${f.key}"
    case Present(cs, when) =>
      cs.mkString("present(", ",", "/") +
        when.toList
          .sortBy(_._1)
          .map((id, g) => s"$id=${sized(g.inputKey)}")
          .mkString + ")"
    case List(items) =>
      items.map(i => sized(i.inputKey)).mkString("list(", "", ")")
    // Its inputs are keyed beside it, as a step's are (`BoundSlot.valueKey`).
    case Cel(src, _) => s"cel:${src.length}:$src"

  private def sized(k: String) = s"${k.length}:$k"

  /** An operand's key, which does name its entity. */
  def key(v: Value): String = v match
    case Entity(of, part, _, bypass) =>
      s"@${of.getOrElse("")}:" + Part.key(part) + (if (bypass) "" else "!b")
    case other => sourceKey(other)

  private val EntityFields =
    Set("kind", "of", "part", "reads", "bypassUnavailable")

  given Decoder[Value] = Decoder.instance { c =>
    if (!c.value.isObject) c.as[Lit].map(Literal(_))
    else
      c.get[String]("kind").flatMap {
        case "Entity" =>
          Strict.only(c, EntityFields) *> (
            c.getOrElse[Option[String]]("of")(None),
            c.getOrElse[Part]("part")(Part.State),
            c.getOrElse[String]("reads")(Reads.Live),
            c.getOrElse[Boolean]("bypassUnavailable")(true)
          ).mapN(Entity.apply)
        case "Fetch" =>
          Strict.only(c, Set("kind", "fetch")) *>
            c.get[FetchTemplate]("fetch").map(Fetch(_))
        case "Present" =>
          Strict.only(c, Set("kind", "candidates", "when")) *> (
            c.getOrElse[scala.List[String]]("candidates")(Nil),
            c.getOrElse[Map[String, ValueExpr]]("when")(Map.empty)
          ).mapN(Present.apply)
        case "List" =>
          Strict.only(c, Set("kind", "items")) *>
            c.get[scala.List[ValueExpr]]("items").map(List(_))
        case "Cel" =>
          Strict.only(c, Set("kind", "src", "inputs")) *> (
            c.get[String]("src"),
            c.getOrElse[Map[String, ValueExpr]]("inputs")(Map.empty)
          ).mapN(Cel.apply)
        case other =>
          Left(
            DecodingFailure(
              s"unknown value kind '$other' — Entity, Fetch, Present, " +
                "List or Cel, or a bare literal",
              c.history
            )
          )
      }
  }

/** Which part of an entity a read takes. */
enum Part derives CanEqual:
  case State
  case Attr(name: String)

  /** The entity itself, CEL's `fh.Entity` struct. */
  case Whole

object Part:
  def key(p: Part): String = p match
    case State      => "state"
    case Attr(name) => s"attr:$name"
    case Whole      => "whole"

  given Decoder[Part] = ConfiguredDecoder.derived(using Strict.configuration)

/** One step of a [[ValueExpr]], run on the value before it. */
sealed trait Step derives CanEqual

object Step:

  /** CEL over `value`, the value before it, beside its named `inputs` and the
    * renderer's bindings.
    */
  final case class Cel(src: String, inputs: Map[String, ValueExpr] = Map.empty)
      extends Step

  /** The simple tier (ADR 0028): total, and run without the engine, over
    * operands that are themselves values.
    */
  sealed trait Simple extends Step

  /** The size of a list. */
  case object Count extends Simple

  /** Whether every bool of a list holds, stopping at the first that does not:
    * an and. An empty list holds.
    */
  case object AllOf extends Simple

  /** Whether any bool of a list holds, stopping at the first that does: an or.
    */
  case object AnyOf extends Simple

  /** The value against `to`: equality as text, ordering numeric when both parse
    * and else false, so `Lt` and `Gt` can both be false.
    */
  final case class Compare(op: Op, to: Value) extends Simple

  case object Not extends Simple

  /** The first of a [[Value.Present]]'s ids under `orderBy`, ties to candidate
    * order, `""` when none is present: one pass, no sorted list. It reads every
    * candidate's state, so it is no [[Simple]] step.
    */
  final case class First(orderBy: List[LayoutNode.SortTerm] = Nil) extends Step

  /** `text` then the value. */
  final case class Prefix(text: Value) extends Simple

  /** The value then `text`. */
  final case class Suffix(text: Value) extends Simple

  /** The value, a space and `unit` when the unit is text: CEL's
    * `value + attr[?'u'].optMap(u, ' ' + u).orValue('')`, except that an empty
    * value stays empty, so a slot's default applies.
    */
  final case class WithUnit(unit: Value) extends Simple

  /** CEL:
    * `str(math.round((double(value) - min) * 100.0 / (max - min))) + ' %'`,
    * rounding half away from zero like `math.round`. Numbers or numeric
    * strings, as `double()` takes; unparseable renders `0 %` (CEL would error).
    */
  final case class Percent(min: Value, max: Value) extends Simple

  /** CEL: `str(100.0 - ((double(value) - min) * 100.0 / (max - min))) + '%'`,
    * for a right-anchored track. Unparseable renders `100%`.
    */
  final case class Fill(min: Value, max: Value) extends Simple

  /** Seconds per unit of the value, coarsened: `0s`, `45s`, `12m`, `1h 5m`.
    * Unparseable renders `""`: a dishwasher between programmes reports
    * `unknown`, and `0s` would claim it just finished.
    */
  final case class Duration(scale: Double) extends Simple

  /** The case the value names, else `otherwise`. A map, so duplicate keys and
    * first-match questions are unrepresentable; `otherwise` is required, since
    * HA adds states to a domain after it ships.
    */
  final case class Match(cases: Map[String, Lit], otherwise: Value)
      extends Simple

  /** The renderer's own steps. */
  sealed trait Stage extends Step

  /** The theme's class rules over the value, which sits in a `class` attribute
    * (ADR 0020). Last, so a default is swapped too.
    */
  case object ThemeClasses extends Stage

  /** A fetch's answer drawn as SVG (ADR 0032). */
  final case class Chart(style: ChartStyle = ChartStyle()) extends Stage

  def about(subject: Option[String])(s: Step): Step =
    val v = Value.about(subject)
    s match
      case Prefix(t)      => Prefix(v(t))
      case Suffix(t)      => Suffix(v(t))
      case WithUnit(u)    => WithUnit(v(u))
      case Percent(a, b)  => Percent(v(a), v(b))
      case Fill(a, b)     => Fill(v(a), v(b))
      case Match(cs, o)   => Match(cs, v(o))
      case Compare(op, t) => Compare(op, v(t))
      case other          => other

  /** Its outcomes are all Booleans. */
  def allBoolean(m: Match): Boolean =
    (m.otherwise match
      case Value.Literal(_: Lit.Bool) => true
      case _                          => false
    ) && m.cases.values.forall(_.isInstanceOf[Lit.Bool])

  /** For a message. */
  def label(s: Step): String = s match
    case _: Cel       => "CEL"
    case _: Prefix    => "prefix"
    case _: Suffix    => "suffix"
    case _: WithUnit  => "unit"
    case _: Percent   => "percent"
    case _: Fill      => "fill"
    case _: Duration  => "duration"
    case _: Match     => "match"
    case Count        => "count"
    case AllOf        => "all-of"
    case AnyOf        => "any-of"
    case _: Compare   => "compare"
    case Not          => "not"
    case _: First     => "first"
    case ThemeClasses => "theme classes"
    case _: Chart     => "chart"

  def operands(s: Step): List[Value] = s match
    case Prefix(t)     => List(t)
    case Suffix(t)     => List(t)
    case WithUnit(u)   => List(u)
    case Percent(a, b) => List(a, b)
    case Fill(a, b)    => List(a, b)
    case Match(_, o)   => List(o)
    case Compare(_, t) => List(t)
    case _             => Nil

  /** Length-prefixed where a field is free text, so no key forges another. */
  def key(s: Step): String =
    def sized(t: String) = s"${t.length}:$t"
    s match
      case Cel(src, _)     => s"cel:${sized(src)}"
      case Prefix(t)       => s"prefix(${Value.key(t)})"
      case Suffix(t)       => s"suffix(${Value.key(t)})"
      case WithUnit(u)     => s"unit(${Value.key(u)})"
      case Percent(a, b)   => s"percent(${Value.key(a)},${Value.key(b)})"
      case Fill(a, b)      => s"fill(${Value.key(a)},${Value.key(b)})"
      case Duration(scale) => s"duration:$scale"
      case Match(cases, o) =>
        val body = cases.toSeq
          .sortBy(_._1)
          .map((k, v) => sized(k) + Lit.key(v))
          .mkString
        s"match:${cases.size}:$body:${Value.key(o)}"
      case Count          => "count"
      case AllOf          => "allOf"
      case AnyOf          => "anyOf"
      case Compare(op, t) => s"compare:$op(${Value.key(t)})"
      case Not            => "not"
      case First(orderBy) => orderBy.map(_.key).mkString("first(", ";", ")")
      case ThemeClasses   => "themeClasses"
      case Chart(style)   => s"chart:$style"

  given Decoder[Step] =
    ConfiguredDecoder.derived(using Strict.configuration)

/** Decoders that refuse a field they do not know, which a typo would otherwise
  * turn into a silent default.
  */
private[model] object Strict:

  val configuration: Configuration =
    Configuration.default.withDefaults
      .withDiscriminator("kind")
      .withStrictDecoding

  def only(c: ACursor, allowed: Set[String]): Decoder.Result[Unit] =
    c.keys.toList.flatten.filterNot(allowed) match
      case Nil     => Right(())
      case unknown =>
        Left(
          DecodingFailure(
            s"unknown field(s) ${unknown.mkString(", ")} — one of " +
              allowed.toList.sorted.mkString(", "),
            c.history
          )
        )
