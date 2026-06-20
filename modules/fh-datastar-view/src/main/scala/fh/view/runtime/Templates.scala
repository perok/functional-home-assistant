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
    val components: Map[String, Template],
    val inputs: Map[String, List[String]]
)

object Templates {

  private val compiler: Mustache.Compiler =
    Mustache.compiler().escapeHTML(true).defaultValue("")

  def from(dashboard: Dashboard): Templates =
    new Templates(
      components = dashboard.templates.view
        .mapValues(td => compiler.compile(td.template))
        .toMap,
      inputs = dashboard.templates.view.mapValues(_.inputs).toMap
    )
}
