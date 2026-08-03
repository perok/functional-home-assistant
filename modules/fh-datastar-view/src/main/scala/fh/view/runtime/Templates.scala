package fh.view.runtime

import com.samskivert.mustache.{Mustache, Template}
import fh.view.model.{CardDef, Dashboard}

/** The shared template library, pre-compiled once at startup (never on the hot
  * path).
  *
  *   - templates escape their `{{slot}}` values (HTML-safe) — HA values contain
  *     `<`, `&`, quotes; raw author values (action URLs, ids) use `{{{...}}}`.
  *   - the layout is a tree walked in Scala (`Renderer`), not a mustache
  *     string.
  *
  * Missing slots render as empty strings rather than throwing.
  */
/** @param components
  *   the whole card, `{{{self}}}`/`{{{mount}}}` holes included — the document
  *   path.
  * @param selves
  *   the `self` part of a container that declares one — what the patch path
  *   renders, and the predicate that decides which path a node takes.
  * @param mounts
  *   the `mount` part, rendered only by the document path and by a fill.
  */
class Templates private (
    val components: Map[String, Template],
    val selves: Map[String, Template],
    val mounts: Map[String, Template],
    // Cards whose `self` renders the SELECTED member's index, so their nodes
    // have one rendering per member (ADR 0012). Decided here, where the
    // templates are, rather than by searching the source at every ask: this
    // matches a mustache TAG, so `bakeIndex` in a comment, a class name or an
    // attribute value does not silently make a card per-viewer forever.
    val selvesReadingSelection: Set[String]
)

object Templates {

  // `emptyStringIsFalse` makes `{{#x}}…{{/x}}` sections vanish when `x` resolves
  // to "" — so optional pieces (secondary, tap) render only when present.
  // Not private: `Renderer` reuses this exact config to compile `theme.chrome`
  // (the one other Mustache template the module compiles), so there is a
  // single jmustache configuration story.
  val compiler: Mustache.Compiler =
    Mustache
      .compiler()
      .escapeHTML(true)
      .defaultValue("")
      .emptyStringIsFalse(true)

  def from(dashboard: Dashboard): Templates =
    new Templates(
      components = dashboard.cards.view
        .mapValues(cd => compiler.compile(cd.template))
        .toMap,
      // Compiled alongside `template`, so the patch path is a lookup rather
      // than a re-parse on the hot path.
      selves = part(dashboard, _.self),
      mounts = part(dashboard, _.mount),
      selvesReadingSelection = dashboard.cards.collect {
        case (name, cd)
            if cd.self.exists(SelectionTag.findFirstIn(_).isDefined) =>
          name
      }.toSet
    )

  /** `{{bakeIndex}}` as a tag — the renderer-injected selected-member index.
    * Triple-stache too, since a numeric index is escape-identical and an author
    * may well write it that way.
    */
  private val SelectionTag: scala.util.matching.Regex =
    """\{\{\{?\s*bakeIndex\s*\}?\}\}""".r

  private def part(
      dashboard: Dashboard,
      of: CardDef => Option[String]
  ): Map[String, Template] =
    dashboard.cards.view.flatMap { case (name, cd) =>
      of(cd).map(name -> compiler.compile(_))
    }.toMap
}
