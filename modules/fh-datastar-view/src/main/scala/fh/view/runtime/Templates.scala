package fh.view.runtime

import com.samskivert.mustache.{Mustache, Template}
import fh.view.model.Dashboard

/** Mustache templates pre-compiled once at startup (never on the hot path).
  *
  *   - component templates escape their `{{slot}}` values (HTML-safe) — HA
  *     values contain `<`, `&`, quotes.
  *   - the layout includes already-rendered component HTML via
  *     `{{{componentId}}}` (unescaped triple-mustache).
  *
  * Missing slots render as empty strings rather than throwing.
  */
class Templates private (
    val components: Map[String, Template],
    val layout: Template
)

object Templates {

  private val compiler: Mustache.Compiler =
    Mustache.compiler().escapeHTML(true).defaultValue("")

  def from(dashboard: Dashboard): Templates =
    new Templates(
      components = dashboard.templates.view.mapValues(compiler.compile).toMap,
      layout = compiler.compile(dashboard.layout)
    )
}
