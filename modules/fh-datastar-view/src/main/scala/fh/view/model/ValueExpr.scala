package fh.view.model

import cats.syntax.all.*
import fh.view.history.ChartStyle
import io.circe.{ACursor, Decoder, DecodingFailure}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

/** What a slot shows: a value read from its [[source]], then each step run on
  * it in order (`docs/plan-slot-values.md`). On the wire a bare JSON primitive
  * is a literal, and every other shape an object whose `kind` names its case.
  *
  * @param source
  *   `None` reads no entity: a CEL over its inputs and the renderer's bindings
  *   alone.
  * @param default
  *   what an empty result shows instead.
  */
final case class ValueExpr(
    source: Option[Value],
    steps: List[Step] = Nil,
    default: Option[String] = None
) derives CanEqual:

  /** A text literal with nothing run on it: what the renderer writes as is. */
  def literal: Option[String] = (source, steps) match
    case (Some(Value.Literal(Lit.Text(s))), Nil) => Some(s)
    case _                                       => None

  /** The subject resolved: a source [[Value.Entity]] that names none reads
    * `subject`, and a step's operand that names none its source's entity, else
    * `subject`. `None` leaves one unnamed, which `validate` refuses.
    */
  def about(subject: Option[String]): ValueExpr =
    val src = source.map(Value.about(subject))
    val own = src match
      case Some(Value.Entity(of, _, _, _)) => of
      case _                               => subject
    if (subject.isEmpty && own.isEmpty) this
    else ValueExpr(src, steps.map(Step.about(own)), default)

  /** Every entity a step's operand names: what wakes the slot besides its
    * source.
    */
  def operandEntities: List[String] =
    steps.flatMap(Step.operands).flatMap(Value.entityOf)

  /** Whether any read names no entity after [[about]]: on a card with none. */
  def readsMissingSubject: Boolean =
    (source.toList ++ steps.flatMap(Step.operands)).exists {
      case Value.Entity(None, _, _, _) => true
      case _                           => false
    }

  /** Injective, for signal names and the once-cache. The source's own entity is
    * not in it: the signal path names that entity.
    */
  lazy val key: String = keyBy(Value.sourceKey)

  /** As a named input, whose entity no signal path names. */
  lazy val inputKey: String = keyBy(Value.key)

  private def keyBy(src: Value => String): String =
    source.fold("none")(src) +
      steps.map(s => "|" + Step.key(s)).mkString +
      default.fold("")(d => s"|default=${d.length}:$d")

  /** The CEL type it is declared as when a named input: a literal, a count, a
    * condition or a fetch. `None` for any other shape, which `validate`
    * refuses.
    */
  def inputKind: Option[ValueExpr.Kind] = (source, steps) match
    case (Some(Value.Literal(l)), Nil) =>
      Some(l match
        case Lit.Text(_)  => ValueExpr.Kind.Str
        case Lit.Bool(_)  => ValueExpr.Kind.Bool
        case Lit.Whole(_) => ValueExpr.Kind.Int
        case Lit.Real(_)  => ValueExpr.Kind.Dbl)
    case (Some(Value.Present(_)), List(Step.Count)) => Some(ValueExpr.Kind.Int)
    case (Some(Value.Condition(_)), Nil)            => Some(ValueExpr.Kind.Bool)
    case (Some(Value.Fetch(_)), Nil) => Some(ValueExpr.Kind.Series)
    case _                           => None

  /** Live: what it answers moves, so a once-read cannot hold it. */
  def isLive: Boolean = source.exists {
    case Value.Literal(_) => false
    case _                => true
  }

  /** Every entity that can move it. */
  def entities: List[String] =
    source.toList.flatMap(Value.entityOf) ++ operandEntities

object ValueExpr:

  /** The CEL type a named input is declared as. `Series` is a history answer's
    * points, `list(list(double))` of `[epochMillis, value]`.
    */
  enum Kind derives CanEqual:
    case Str, Int, Dbl, Bool, Series

  def env(inputs: Map[String, ValueExpr]): List[(String, Kind)] =
    inputs.toList.sortBy(_._1).flatMap((n, v) => v.inputKind.map(n -> _))

  def literal(s: String): ValueExpr = ValueExpr(
    Some(Value.Literal(Lit.Text(s)))
  )

  val fieldNames: Set[String] = Set("source", "steps", "default")

  /** A bare primitive, or `{source, steps, default}`. */
  given Decoder[ValueExpr] = Decoder.instance(c =>
    if (!c.value.isObject) c.as[Lit].map(l => ValueExpr(Some(Value.Literal(l))))
    else Strict.only(c, fieldNames) *> fields(c)
  )

  /** The fields of an object that may hold others (a template slot's). */
  def fields(c: ACursor): Decoder.Result[ValueExpr] =
    (
      c.getOrElse[Option[Value]]("source")(None),
      c.getOrElse[List[Step]]("steps")(Nil),
      c.getOrElse[Option[String]]("default")(None)
    ).mapN(ValueExpr.apply)

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

  /** The candidates present now, which a [[Step.Count]] counts. */
  case Present(tally: Predicate.Tally)

  /** Decides itself, over the entities it names; a comparison that names none
    * reads the subject.
    */
  case Condition(predicate: Predicate)

object Value:

  def about(subject: Option[String])(v: Value): Value = v match
    case e: Entity if e.of.isEmpty => e.copy(of = subject)
    case other                     => other

  def entityOf(v: Value): List[String] = v match
    case Entity(of, _, _, _) => of.toList
    case Condition(p)        => Predicate.referencedEntities(p)
    case Present(t)          => t.referencedEntities
    case _                   => Nil

  /** A source's key, its entity left out ([[ValueExpr.key]]). A plain state
    * read keys as `state`, which the signal path spells out.
    */
  def sourceKey(v: Value): String = v match
    case Literal(l)                 => s"lit:${Lit.key(l)}"
    case Entity(_, part, _, bypass) =>
      Part.key(part) + (if (bypass) "" else "!b")
    case Fetch(f)     => s"fetch:${f.key}"
    case Present(t)   => s"t:${Predicate.tallyKey(t)}"
    case Condition(p) => s"p:${Predicate.key(p)}"

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
          Strict.only(c, Set("kind", "tally")) *>
            c.get[Predicate.Tally]("tally").map(Present(_))
        case "Condition" =>
          Strict.only(c, Set("kind", "predicate")) *>
            c.get[Predicate]("predicate").map(Condition(_))
        case other =>
          Left(
            DecodingFailure(
              s"unknown value kind '$other' — Entity, Fetch, Present or " +
                "Condition, " +
                "or a bare literal",
              c.history
            )
          )
      }
  }

/** Which part of an entity a read takes. */
enum Part derives CanEqual:
  case State
  case Attr(name: String)

object Part:
  def key(p: Part): String = p match
    case State      => "state"
    case Attr(name) => s"attr:$name"

  given Decoder[Part] = ConfiguredDecoder.derived(using Strict.configuration)

/** One step of a [[ValueExpr]], run on the value before it. */
sealed trait Step derives CanEqual

object Step:

  /** CEL over the source's entity (`state`, `attr`, `entity_id`, `domain`, when
    * there is one), the renderer's bindings and its named `inputs`.
    */
  final case class Cel(src: String, inputs: Map[String, ValueExpr] = Map.empty)
      extends Step

  /** The size of a [[Value.Present]] list. */
  case object Count extends Step

  /** One value in, one text out, over operands that are themselves values. */
  sealed trait Format extends Step

  /** `text` then the value. */
  final case class Prefix(text: Value) extends Format

  /** The value then `text`. */
  final case class Suffix(text: Value) extends Format

  /** The value, a space and `unit` when the unit is text: CEL's
    * `value + attr[?'u'].optMap(u, ' ' + u).orValue('')`, except that an empty
    * value stays empty, so a slot's default applies.
    */
  final case class WithUnit(unit: Value) extends Format

  /** CEL:
    * `str(math.round((double(value) - min) * 100.0 / (max - min))) + ' %'`,
    * rounding half away from zero like `math.round`. Numbers or numeric
    * strings, as `double()` takes; unparseable renders `0 %` (CEL would error).
    */
  final case class Percent(min: Value, max: Value) extends Format

  /** CEL: `str(100.0 - ((double(value) - min) * 100.0 / (max - min))) + '%'`,
    * for a right-anchored track. Unparseable renders `100%`.
    */
  final case class Fill(min: Value, max: Value) extends Format

  /** Seconds per unit of the value, coarsened: `0s`, `45s`, `12m`, `1h 5m`.
    * Unparseable renders `""`: a dishwasher between programmes reports
    * `unknown`, and `0s` would claim it just finished.
    */
  final case class Duration(scale: Double) extends Format

  /** The case the value names, else `otherwise`. A map, so duplicate keys and
    * first-match questions are unrepresentable; `otherwise` is required, since
    * HA adds states to a domain after it ships.
    */
  final case class Match(cases: Map[String, Lit], otherwise: Value)
      extends Format

  /** A fetch's answer drawn as SVG (ADR 0032). */
  final case class Chart(style: ChartStyle = ChartStyle()) extends Step

  def about(subject: Option[String])(s: Step): Step =
    val v = Value.about(subject)
    s match
      case Prefix(t)     => Prefix(v(t))
      case Suffix(t)     => Suffix(v(t))
      case WithUnit(u)   => WithUnit(v(u))
      case Percent(a, b) => Percent(v(a), v(b))
      case Fill(a, b)    => Fill(v(a), v(b))
      case Match(cs, o)  => Match(cs, v(o))
      case other         => other

  def operands(s: Step): List[Value] = s match
    case Prefix(t)     => List(t)
    case Suffix(t)     => List(t)
    case WithUnit(u)   => List(u)
    case Percent(a, b) => List(a, b)
    case Fill(a, b)    => List(a, b)
    case Match(_, o)   => List(o)
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
      case Count        => "count"
      case Chart(style) => s"chart:$style"

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
