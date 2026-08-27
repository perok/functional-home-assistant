package fh.view.runtime

import com.samskivert.mustache.{Mustache, Template}
import fh.view.model.{CardDef, Dashboard}

/** The shared template library, pre-compiled once at startup, never on the hot
  * path. Templates escape their `{{slot}}` values because HA values contain
  * `<`, `&` and quotes; raw author values (action URLs, ids) use `{{{...}}}`.
  * Missing slots render as empty strings rather than throwing.
  *
  * @param components
  *   the whole card, `{{{self}}}` and every region hole included — the document
  *   path.
  * @param selves
  *   the `self` part of a card that declares one — what the patch path renders,
  *   and the predicate deciding which path a node takes.
  */
class Templates private (
    val components: Map[String, Template],
    val selves: Map[String, Template]
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
      selves = part(dashboard, _.self)
    )

  private def part(
      dashboard: Dashboard,
      of: CardDef => Option[String]
  ): Map[String, Template] =
    dashboard.cards.view.flatMap { case (name, cd) =>
      of(cd).map(name -> compiler.compile(_))
    }.toMap
}
