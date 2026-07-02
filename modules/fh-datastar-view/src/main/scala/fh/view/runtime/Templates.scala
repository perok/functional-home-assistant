package fh.view.runtime

import com.samskivert.mustache.{Mustache, Template}
import fh.view.model.Dashboard

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
class Templates private (
    val components: Map[String, Template]
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
        .toMap
    )
}
