package fh.view.testkit

import fh.view.model.{CardDef, Region}

/** A wire card whose template is `content` inside the root `core/node.pkl`
  * writes for a card that sets no `tag`, so a fixture renders what a Pkl card
  * does. `UseCaseSuite` holds `root` to the library's lowering.
  */
object TestCard:
  def root(content: String): String =
    s"""<div class="fh-cell{{{cellClasses}}}"{{{cell}}}>$content</div>"""

  def apply(
      template: String,
      regions: Map[String, Region] = Map.empty,
      css: String = "",
      script: Option[String] = None
  ): CardDef =
    CardDef(root(template), regions = regions, css = css, script = script)
