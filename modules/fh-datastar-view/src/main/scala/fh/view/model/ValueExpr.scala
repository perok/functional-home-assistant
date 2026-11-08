package fh.view.model

import cats.syntax.all.*
import fh.view.history.ChartStyle
import fh.view.runtime.{Cel, Steps}
import io.circe.{ACursor, Decoder, DecodingFailure}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

/** What a slot shows: a value read from its [[source]], then each step run on
  * it in order (ADR 0035, the value language). On the wire a bare JSON
  * primitive is a literal, and every other shape an object whose `kind` names
  * its case.
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
        case other       => Steps.text(other.raw))
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

  /** What it holds besides its source's own read, its source's first. Every
    * walk below goes through it, so a new value or step kind is a compile error
    * in [[Value.parts]] or [[Step.parts]], not a walk that skips it.
    */
  def parts: List[ValueExpr.Inner] =
    Value.parts(source) ++ steps.flatMap(Step.parts)

  /** Every entity what it holds names ([[parts]]), its CELs' own inputs
    * included: what wakes it besides its source's own read.
    */
  def partEntities: List[String] = parts.flatMap {
    case ValueExpr.Inner.Operand(v)   => Value.Operand.entity(v)
    case ValueExpr.Inner.Item(e)      => e.entities
    case ValueExpr.Inner.PerMember(e) => e.entities
    case ValueExpr.Inner.Input(e)     => e.entities
  }

  /** Whether any read names no entity after [[about]]: on a card with none. */
  def readsMissingSubject: Boolean =
    (source match
      case Value.Entity(of, _, _, _) => of.isEmpty
      case _                         => false
    ) || parts.exists {
      case ValueExpr.Inner.Operand(v) => Value.Operand.unnamed(v)
      case ValueExpr.Inner.Item(e)    => e.readsMissingSubject
      case ValueExpr.Inner.PerMember(_) | ValueExpr.Inner.Input(_) => false
    }

  /** Why `what`, a bool as `typed` answers it, cannot be decided: it compares
    * an entity it does not name, where nothing names one for it.
    */
  def unnamedComparison(
      what: String,
      typed: Either[ValueExpr.Mistyped, ValueExpr.Typed]
  ): Option[String] =
    Option.when(readsMissingSubject && typed.exists(_.out == ValueType.Bool))(
      s"$what compares an entity it does not name — name it with q.entity(e)"
    )

  private def bypassesSource: Boolean = source match
    case e: Value.Entity => e.bypassUnavailable
    case _               => false

  /** Why it asks for the bypass where it may not: only a text slot's own read
    * of a state or a whole entity takes it (`ownMay`), and no other read.
    */
  def bypassError(ownMay: Boolean): Option[String] =
    Option.when(bypassesInside || (bypassesSource && !ownMay))(
      ValueExpr.MisplacedBypass
    )

  private def bypassesInside: Boolean = parts.exists {
    case ValueExpr.Inner.Operand(e: Value.Entity)  => e.bypassUnavailable
    case ValueExpr.Inner.Operand(_: Value.Literal) => false
    case ValueExpr.Inner.Item(e)                   => e.bypassesAnywhere
    case ValueExpr.Inner.PerMember(e)              => e.bypassesAnywhere
    case ValueExpr.Inner.Input(e)                  => e.bypassesAnywhere
  }

  private def bypassesAnywhere: Boolean = bypassesSource || bypassesInside

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

  /** Every CEL, its source's and its steps', in order, each with its own named
    * inputs.
    */
  def cels: List[(String, Map[String, ValueExpr])] =
    (source match
      case Value.Cel(src, in) => List(src -> in)
      case _                  => Nil
    ) ++ steps.collect { case Step.Cel(src, in) => src -> in }

  /** It and every expression inside it ([[parts]]) at any depth, each with
    * whether its CELs see the node's values: a guard and an order term, read
    * per member, see none, and nor does anything inside them.
    */
  def scoped: List[(ValueExpr, Boolean)] = within(nodeScope = true)

  private def within(nodeScope: Boolean): List[(ValueExpr, Boolean)] =
    (this, nodeScope) :: parts.flatMap {
      case ValueExpr.Inner.Item(e)      => e.within(nodeScope)
      case ValueExpr.Inner.Input(e)     => e.within(nodeScope)
      case ValueExpr.Inner.PerMember(e) => e.within(nodeScope = false)
      case ValueExpr.Inner.Operand(_)   => Nil
    }

  /** It and every expression inside it, at any depth. */
  def all: List[ValueExpr] = scoped.map(_._1)

  /** The node's values its CELs read, at any depth: by name, where no input of
    * that CEL's own has it.
    */
  def readsNamed(values: Map[String, ValueExpr]): Map[String, ValueExpr] =
    if (values.isEmpty) values
    else
      val read = scoped
        .collect { case (e, true) => e }
        .flatMap(_.cels)
        .flatMap((src, own) => Cel.identifiers(src) -- own.keySet)
      values.filter((n, _) => read.contains(n))

  /** Why it cannot decide where a bool is required, read with no named values:
    * a clause, a guard, a state surface.
    */
  def boolError: Option[String] =
    typed(Nil) match
      case Left(m)                             => Some(m.message)
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
    // Each CEL declares the names it reads: its own inputs, each typed with
    // `env` in scope, and `env`'s others.
    def cel(
        src: String,
        own: Map[String, ValueExpr],
        value: Option[ValueType],
        keys: List[Cel.Key]
    ) =
      own.toList
        .sortBy(_._1)
        .traverse((n, v) =>
          v.typed(env)
            .map(t => n -> t.out)
            .left
            .map(m =>
              ValueExpr.Mistyped(s"the input '$n': ${m.message}", m.cel)
            )
        )
        .flatMap { mine =>
          val read = Cel.identifiers(src)
          val names = (env.toMap ++ mine).filter((n, _) => read.contains(n))
          val k = Cel.Key(src, names.toList.sortBy(_._1), value)
          Cel
            .resultType(k)
            .left
            .map(ValueExpr.Mistyped(_, Some(src)))
            .map(ValueExpr.Typed(keys :+ k, _))
        }
    def plain(t: ValueType) = Right(ValueExpr.Typed(Nil, t))
    val start = source match
      case Value.Cel(src, own)               => cel(src, own, None, Nil)
      case Value.List(t, items)              => ValueExpr.listOf(t, items, env)
      case Value.Literal(l)                  => plain(ValueType.of(l))
      case Value.Entity(_, Part.State, _, _) => plain(ValueType.Text)
      case Value.Entity(_, _: Part.Attr, _, _) => plain(ValueType.Dyn)
      case Value.Entity(_, Part.Whole, _, _)   => plain(ValueType.Entity)
      case Value.Fetch(_)                      => plain(ValueType.Series)
      case Value.Present(_, when)              =>
        when.toList
          .sortBy(_._1)
          .collectFirstSome((id, g) =>
            g.boolError.map(e => s"the guard on '$id': $e")
          ) match
          case Some(e) => Left(ValueExpr.Mistyped(e))
          case None    => plain(ValueType.List(ValueType.Text))
    val orderErrors = steps.collectFirstSome {
      case Step.First(orderBy) =>
        orderBy.collectFirstSome(_.by.typed(Nil).left.toOption.map(_.message))
      case _ => None
    }
    val checked = orderErrors.fold(start)(e => Left(ValueExpr.Mistyped(e)))
    steps.foldLeft(checked)((acc, step) =>
      acc.flatMap(t =>
        step match
          case Step.Cel(src, own) => cel(src, own, Some(t.out), t.keys)
          case s                  =>
            ValueType
              .after(s, t.out)
              .left
              .map(ValueExpr.Mistyped(_))
              .map(o => t.copy(out = o))
      )
    )

  /** Live: what it answers moves, so a once-read cannot hold it. */
  lazy val isLive: Boolean =
    (source match
      case Value.Literal(_) | Value.List(_, _) | Value.Cel(_, _) => false
      case Value.Entity(_, _, _, _) | Value.Fetch(_) | Value.Present(_, _) =>
        true
    ) || parts.exists {
      case ValueExpr.Inner.Operand(v)   => Value.Operand.entity(v).nonEmpty
      case ValueExpr.Inner.Item(e)      => e.isLive
      case ValueExpr.Inner.PerMember(e) => e.isLive
      case ValueExpr.Inner.Input(e)     => e.isLive
    }

  /** Every entity that can move it. */
  def entities: List[String] =
    (source match
      case Value.Entity(of, _, _, _) => of.toList
      case Value.Present(cs, _)      => cs
      case Value.Literal(_) | Value.Fetch(_) | Value.List(_, _) |
          Value.Cel(_, _) =>
        Nil
    ) ++ partEntities

object ValueExpr:

  val MisplacedBypass: String =
    "bypassUnavailable shows an unavailable entity's word in place of a " +
      "reading, so only a text slot's own read of a state or a whole entity " +
      "takes it; everywhere else a read reads as is"

  /** What a [[ValueExpr]] holds inside, by what an unnamed read there reads. */
  enum Inner derives CanEqual:
    /** A step's operand: this expression's subject. */
    case Operand(v: Value.Operand)

    /** A list's item: this expression's subject. */
    case Item(e: ValueExpr)

    /** A guard or an order term, read per member: that member. */
    case PerMember(e: ValueExpr)

    /** A CEL's own named input, read with no subject. */
    case Input(e: ValueExpr)

  /** [[ValueExpr.typed]]'s answer: the CEL it compiles, in order, and its type.
    */
  final case class Typed(keys: List[Cel.Key], out: ValueType)

  /** Why it does not type; `cel` is the CEL source at fault, if one is. */
  final case class Mistyped(message: String, cel: Option[String] = None)

  /** Each item typed in its expression's scope. Empty, it is still a list of
    * `itemType`.
    */
  private def listOf(
      itemType: ValueType,
      items: List[ValueExpr],
      env: List[(String, ValueType)]
  ): Either[Mistyped, Typed] =
    items
      .traverse(_.typed(env))
      .flatMap(
        _.map(_.out).find(_ != itemType) match
          case Some(t) =>
            Left(
              Mistyped(s"a list's items are ${itemType.name}, not ${t.name}")
            )
          case None => Right(Typed(Nil, ValueType.List(itemType)))
      )

  /** A node's values as names a CEL may read, each its own type; one that does
    * not type is left out, and `validate` says why.
    */
  def env(values: Map[String, ValueExpr]): List[(String, ValueType)] =
    values.toList
      .sortBy(_._1)
      .flatMap((n, v) => v.typed(Nil).toOption.map(t => n -> t.out))

  def literal(s: String): ValueExpr = ValueExpr(Value.Literal(Lit.Text(s)))

  /** A CEL's own inputs in its key; none adds nothing, so a CEL without inputs
    * keys as it always has.
    */
  def inputsKey(inputs: Map[String, ValueExpr]): String =
    if (inputs.isEmpty) ""
    else
      inputs.toList
        .sortBy(_._1)
        .map((n, v) => s"$n=${v.inputKey.length}:${v.inputKey}")
        .mkString("{", ";", "}")

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

  /** A list source's item type, as Pkl names it. */
  given Decoder[ValueType] = Decoder[String].emap {
    case "text"   => Right(Text)
    case "bool"   => Right(Bool)
    case "int"    => Right(Int)
    case "real"   => Right(Real)
    case "dyn"    => Right(Dyn)
    case "entity" => Right(Entity)
    case other    => Left(s"unknown item type: $other")
  }

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
      case _: Step.First     => takes(in == List(Text), "a list of ids", Text)
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
      reads: Reads = Reads.Live,
      bypassUnavailable: Boolean = false
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

  /** Its items in order, each an `itemType` ([[ValueExpr.typed]]): what an
    * and/or runs [[Step.AllOf]] or [[Step.AnyOf]] over. Declared, so an empty
    * list is typed too.
    */
  case List(itemType: ValueType, items: scala.List[ValueExpr])

  /** Computed from nothing before it: CEL over its named inputs and the
    * renderer's bindings, with no `value`.
    */
  case Cel(src: String, inputs: Map[String, ValueExpr] = Map.empty)

object Value:

  /** What a step reads besides the value before it. Pkl writes only these
    * (`slotMod.Operand`), and the evaluator reads no other.
    */
  type Operand = Literal | Entity

  object Operand:
    def entity(v: Operand): scala.List[String] = v match
      case e: Entity  => e.of.toList
      case _: Literal => Nil

    def unnamed(v: Operand): Boolean = v match
      case e: Entity  => e.of.isEmpty
      case _: Literal => false

  given Decoder[Operand] = Decoder[Value].emap {
    case l: Literal => Right(l)
    case e: Entity  => Right(e)
    case _          => Left("a step's operand is a literal or an entity read")
  }

  def about(subject: Option[String])(v: Value): Value = v match
    case e: Entity      => named(subject)(e)
    case List(t, items) => List(t, items.map(_.about(subject)))
    case v @ (Literal(_) | Fetch(_) | Present(_, _) | Cel(_, _)) => v

  /** [[ValueExpr.parts]] of a source. */
  def parts(v: Value): scala.List[ValueExpr.Inner] = v match
    case Present(_, when) =>
      when.values.toList.map(ValueExpr.Inner.PerMember(_))
    case List(_, items) => items.map(ValueExpr.Inner.Item(_))
    case Cel(_, inputs) => inputs.values.toList.map(ValueExpr.Inner.Input(_))
    case Literal(_) | Entity(_, _, _, _) | Fetch(_) => Nil

  /** An entity read that names none, reading `subject`. */
  def named(subject: Option[String])(e: Entity): Entity =
    if (e.of.isEmpty) e.copy(of = subject) else e

  /** A source's key, its entity left out ([[ValueExpr.key]]). A plain state
    * read keys as `state`, which the signal path spells out.
    */
  def sourceKey(v: Value): String = v match
    case Literal(l)                 => s"lit:${Lit.key(l)}"
    case Entity(_, part, _, bypass) =>
      Part.key(part) + (if (bypass) "!b" else "")
    case Fetch(f)          => s"fetch:${f.key}"
    case Present(cs, when) =>
      cs.mkString("present(", ",", "/") +
        when.toList
          .sortBy(_._1)
          .map((id, g) => s"$id=${sized(g.inputKey)}")
          .mkString + ")"
    // Not its item type: two lists that type with the same items are one value.
    case List(_, items) =>
      items.map(i => sized(i.inputKey)).mkString("list(", "", ")")
    case Cel(src, inputs) =>
      s"cel:${src.length}:$src" + ValueExpr.inputsKey(inputs)

  private def sized(k: String) = s"${k.length}:$k"

  /** An operand's key, which does name its entity. */
  def key(v: Value): String = v match
    case Entity(of, part, _, bypass) =>
      s"@${of.getOrElse("")}:" + Part.key(part) + (if (bypass) "!b" else "")
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
            c.getOrElse[Reads]("reads")(Reads.Live),
            c.getOrElse[Boolean]("bypassUnavailable")(false)
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
          Strict.only(c, Set("kind", "itemType", "items")) *> (
            c.get[ValueType]("itemType"),
            c.get[scala.List[ValueExpr]]("items")
          ).mapN(List.apply)
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
    * operands that are literals or entity reads ([[Value.Operand]]).
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

  /** The value against `to`, as `to`'s type picks: a number literal on the
    * number line, a text literal as text, a bool literal as equality; against a
    * read, two numbers on the number line, else text, ordering numeric when
    * both parse and else false, so `Lt` and `Gt` can both be false.
    */
  final case class Compare(op: Op, to: Value.Operand) extends Simple

  case object Not extends Simple

  /** The first of a list of ids under `orderBy`, each id's entity read as its
    * subject, ties to list order, `null` for an empty list: one pass, no sorted
    * list. It reads every id's state, so it is no [[Simple]] step.
    */
  final case class First(orderBy: List[SortTerm] = Nil) extends Step

  /** `text` then the value. */
  final case class Prefix(text: Value.Operand) extends Simple

  /** The value then `text`. */
  final case class Suffix(text: Value.Operand) extends Simple

  /** The value, a space and `unit` when the unit is text: CEL's
    * `value + attr[?'u'].optMap(u, ' ' + u).orValue('')`, except that an empty
    * value stays empty, so a slot's default applies.
    */
  final case class WithUnit(unit: Value.Operand) extends Simple

  /** CEL:
    * `str(math.round((double(value) - min) * 100.0 / (max - min))) + ' %'`,
    * rounding half away from zero like `math.round`. Numbers or numeric
    * strings, as `double()` takes; unparseable is no value (CEL would error).
    */
  final case class Percent(min: Value.Operand, max: Value.Operand)
      extends Simple

  /** CEL: `str(100.0 - ((double(value) - min) * 100.0 / (max - min))) + '%'`,
    * for a right-anchored track. Unparseable is no value.
    */
  final case class Fill(min: Value.Operand, max: Value.Operand) extends Simple

  /** Seconds per unit of the value, coarsened: `0s`, `45s`, `12m`, `1h 5m`.
    * Unparseable is no value: a dishwasher between programmes reports
    * `unknown`, and `0s` would claim it just finished.
    */
  final case class Duration(scale: Double) extends Simple

  /** The case the value names, else `otherwise`. A map, so duplicate keys and
    * first-match questions are unrepresentable; `otherwise` is required, since
    * HA adds states to a domain after it ships.
    */
  final case class Match(cases: Map[String, Lit], otherwise: Value.Operand)
      extends Simple

  /** The renderer's own steps. */
  sealed trait Stage extends Step

  /** The theme's class rules over the value, which sits in a `class` attribute
    * (ADR 0020). Last, so a default is swapped too.
    */
  case object ThemeClasses extends Stage

  /** What a fetch's answer becomes in its hole; with none it is the answer as
    * JSON, the contract a third party reads against (ADR 0031). A closed set,
    * so a new one is a case here and in `FetchResolver.run`.
    */
  sealed trait FetchStage extends Stage

  /** A fetch's answer drawn as SVG (ADR 0032). */
  final case class Chart(style: ChartStyle = ChartStyle()) extends FetchStage

  /** Whether it makes markup, which only a raw hole takes, rather than data. */
  def draws(s: FetchStage): Boolean =
    ValueType.after(s, ValueType.Series).contains(ValueType.Markup)

  def about(subject: Option[String])(s: Step): Step =
    val v: Value.Operand => Value.Operand = {
      case e: Value.Entity  => Value.named(subject)(e)
      case l: Value.Literal => l
    }
    s match
      case Prefix(t)      => Prefix(v(t))
      case Suffix(t)      => Suffix(v(t))
      case WithUnit(u)    => WithUnit(v(u))
      case Percent(a, b)  => Percent(v(a), v(b))
      case Fill(a, b)     => Fill(v(a), v(b))
      case Match(cs, o)   => Match(cs, v(o))
      case Compare(op, t) => Compare(op, v(t))
      case s @ (_: Cel | _: First | _: Duration | Count | AllOf | AnyOf | Not |
          ThemeClasses | _: Chart) =>
        s

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

  /** [[ValueExpr.parts]] of a step. */
  def parts(s: Step): List[ValueExpr.Inner] =
    import ValueExpr.Inner.*
    s match
      case Prefix(t)      => List(Operand(t))
      case Suffix(t)      => List(Operand(t))
      case WithUnit(u)    => List(Operand(u))
      case Percent(a, b)  => List(Operand(a), Operand(b))
      case Fill(a, b)     => List(Operand(a), Operand(b))
      case Match(_, o)    => List(Operand(o))
      case Compare(_, t)  => List(Operand(t))
      case Cel(_, inputs) => inputs.values.toList.map(Input(_))
      case First(orderBy) => orderBy.map(o => PerMember(o.by))
      case _: Duration | Count | AllOf | AnyOf | Not | ThemeClasses |
          _: Chart =>
        Nil

  def operands(s: Step): List[Value.Operand] =
    parts(s).collect { case ValueExpr.Inner.Operand(v) => v }

  /** Length-prefixed where a field is free text, so no key forges another. */
  def key(s: Step): String =
    def sized(t: String) = s"${t.length}:$t"
    s match
      case Cel(src, inputs) =>
        s"cel:${sized(src)}" + ValueExpr.inputsKey(inputs)
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

/** One ordering position, as a set's `orderBy` and [[Step.First]] hold it: `by`
  * read per member, an unnamed read reading it. A condition sorts true first
  * under `asc`, any other value as `Evaluator.Keys` says.
  */
case class SortTerm(
    by: ValueExpr,
    dir: SortTerm.Dir = SortTerm.Dir.Asc
) derives ConfiguredDecoder:
  def descending: Boolean = dir == SortTerm.Dir.Desc

  def key: String = s"${dir.wire}:${by.inputKey}"

  /** What it names besides the member it is read for. */
  def entities: List[String] = by.entities

object SortTerm:
  enum Dir(val wire: String) derives CanEqual:
    case Asc extends Dir("asc")
    case Desc extends Dir("desc")

  object Dir:
    given Decoder[Dir] = Decoder[String].emap(s =>
      values.find(_.wire == s).toRight(s"unknown dir '$s' — asc or desc")
    )

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
