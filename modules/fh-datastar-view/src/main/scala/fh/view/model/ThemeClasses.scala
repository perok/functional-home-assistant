package fh.view.model

import io.circe.Decoder

import scala.util.matching.Regex

/** A theme's class rules (ADR 0020): each `fh-` class it names, mapped to the
  * classes the markup carries in its place. A content that keeps the class adds
  * to it; one that leaves it out replaces it, so the base stylesheet's rules
  * for it stop applying, which is how BeerCSS's spinner keeps the plain ring
  * from drawing under its mask. Applied wherever card or cell markup is
  * emitted: the card templates once, at compile ([[rewriteTemplate]]), and the
  * wrapper's classes and live-class bindings at render.
  */
final case class ThemeClasses(contents: Map[String, List[String]])
    derives CanEqual:

  def isEmpty: Boolean = contents.isEmpty

  def expand(cls: String): List[String] = contents.getOrElse(cls, List(cls))

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
    contents.toList.sortBy(_._1).flatMap { case (cls, content) =>
      Option
        .when(!cls.startsWith("fh-"))(
          s"theme.classes: '$cls' is not an fh- class; a rule applies only to those"
        )
        .toList ++
        Option
          .when(ThemeClasses.RuntimeOwned(cls) && !content.contains(cls))(
            s"theme.classes: '$cls' must stay in its own content, the runtime selects on it"
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

  /** Space-separated on the wire, as a theme writes it. */
  given Decoder[ThemeClasses] =
    Decoder[Map[String, String]].map(m =>
      ThemeClasses(
        m.view.mapValues(_.split(' ').toList.filter(_.nonEmpty)).toMap
      )
    )
