package fh.view.runtime

import com.samskivert.mustache.{Mustache, Template}
import fh.view.model.{CardDef, Dashboard}

/** The shared template library, pre-compiled once at startup, never on the hot
  * path. Templates escape their `{{slot}}` values because HA values contain
  * `<`, `&` and quotes; raw author values (action URLs, ids) use `{{{...}}}`.
  * Missing slots render as empty strings rather than throwing.
  *
  * @param components
  *   the whole card, every region hole included. There is one template per card
  *   now: a leaf's IS its patch fragment, and structure is never patched.
  */
class Templates private (val components: Map[String, Template])

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
      dashboard.cards.view.mapValues(cd => compiler.compile(cd.template)).toMap
    )
}
