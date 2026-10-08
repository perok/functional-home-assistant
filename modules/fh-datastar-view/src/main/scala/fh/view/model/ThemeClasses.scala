package fh.view.model

import io.circe.Decoder

import scala.util.matching.Regex

/** What a theme does with one `fh-` class (ADR 0020). `Add` keeps it and puts
  * `classes` beside it; `Replace` puts `classes` in its place, so the base
  * stylesheet's rules for it stop applying — how BeerCSS's spinner keeps the
  * plain ring from drawing under its mask.
  */
case class ClassRule(mode: ClassRule.Mode, classes: List[String])
    derives CanEqual

object ClassRule:
  enum Mode derives CanEqual:
    case Add, Replace

  given Decoder[Mode] = Decoder[String].emap {
    case "add"     => Right(Mode.Add)
    case "replace" => Right(Mode.Replace)
    case other     => Left(s"unknown class rule mode '$other'")
  }

  given Decoder[ClassRule] =
    Decoder.forProduct2("mode", "classes")(ClassRule.apply)

/** A theme's class rules, applied wherever card or cell markup is emitted: the
  * card templates once, at compile ([[rewriteTemplate]]), and the wrapper's
  * classes and live-class bindings at render.
  */
final case class ThemeClasses(rules: Map[String, ClassRule]) derives CanEqual:

  def isEmpty: Boolean = rules.isEmpty

  def expand(cls: String): List[String] = rules.get(cls) match
    case None                                       => List(cls)
    case Some(ClassRule(ClassRule.Mode.Add, extra)) => cls :: extra
    case Some(ClassRule(ClassRule.Mode.Replace, instead)) => instead

  /** A space-separated class list, each token expanded. */
  def expandAll(classes: String): String =
    if isEmpty then classes
    else classes.split(' ').iterator.flatMap(expand).mkString(" ")

  /** Every `fh-` token in a `class="…"` value, and every
    * `data-class:<fh-…>="…"` binding, one binding per class it expands to. The
    * object form `data-class="{…}"` is not read; no `fh-` class uses it.
    */
  def rewriteTemplate(template: String): String =
    if isEmpty then template
    else
      val bound = ThemeClasses.DataClass.replaceAllIn(
        template,
        m =>
          Regex.quoteReplacement(
            expand(m.group(1))
              .map(c => s"""data-class:$c${m.group(2)}="${m.group(3)}"""")
              .mkString(" ")
          )
      )
      ThemeClasses.ClassAttr.replaceAllIn(
        bound,
        m =>
          Regex.quoteReplacement(
            "class=\"" + ThemeClasses.Token.replaceAllIn(
              m.group(1),
              t => Regex.quoteReplacement(expand(t.matched).mkString(" "))
            ) + "\""
          )
      )

  /** A rule that would leave the runtime without a class it selects on. */
  def errors: List[String] =
    rules.toList.sortBy(_._1).flatMap { case (cls, rule) =>
      Option
        .when(!cls.startsWith("fh-"))(
          s"theme.classes: '$cls' is not an fh- class; a rule applies only to those"
        )
        .toList ++
        Option
          .when(
            rule.mode == ClassRule.Mode.Replace &&
              ThemeClasses.RuntimeOwned(cls)
          )(
            s"theme.classes: '$cls' cannot be replaced, the runtime selects on it; add to it instead"
          )
          .toList
    }

object ThemeClasses:
  val empty: ThemeClasses = ThemeClasses(Map.empty)

  /** Selected on by the shell script and the base stylesheet's layout. */
  val RuntimeOwned: Set[String] = Set("fh-cell", "fh-group")

  // `(?<![\w:-])` keeps `data-class="…"` and `data-class:x="…"` out.
  private val ClassAttr: Regex = """(?<![\w:-])class="([^"]*)"""".r
  private val Token: Regex = """(?<![\w-])fh-[\w-]+(?![\w-])""".r
  private val DataClass: Regex =
    """data-class:(fh-[A-Za-z0-9-]+)((?:__[\w.-]+)?)="([^"]*)"""".r

  given Decoder[ThemeClasses] =
    Decoder[Map[String, ClassRule]].map(ThemeClasses(_))
