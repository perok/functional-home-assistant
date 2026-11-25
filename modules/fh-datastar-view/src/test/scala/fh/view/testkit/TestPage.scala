package fh.view.testkit

import fh.view.model.{CardDef, Dashboard, LayoutNode}

/** The page card `entry.pkl` puts around a dashboard (`core/page.pkl`), for a
  * fixture built in Scala that needs the `popups` host. Same root and ids as
  * the library's: `root`, the body `c` unless it names its own, `popups`.
  */
object TestPage:

  val Chrome: String = "<main>{{{body}}}</main>{{{popups}}}"

  private def bare(content: String): String =
    s"""<div class="{{{cellClasses}}}"{{{cell}}}>$content</div>"""

  def around(d: Dashboard, chrome: String = Chrome): Dashboard = {
    val body = d.card match
      case c: LayoutNode.Component if c.id.isEmpty => c.copy(id = Some("c"))
      case other                                   => other
    val popups = LayoutNode.Component("page_popups", id = Some("popups"))
    d.copy(
      cards = d.cards ++ Map(
        "page" -> CardDef(
          bare(chrome),
          layoutCell = false,
          regions = Set("body", "popups")
        ),
        "page_popups" -> CardDef(
          bare(CardDef("").holeOf(LayoutNode.DefaultRegion)),
          layoutCell = false,
          regions = Set(LayoutNode.DefaultRegion)
        )
      ),
      card = LayoutNode.Component(
        "page",
        Slots.nodes(Map("body" -> List(body), "popups" -> List(popups))),
        id = Some("root")
      )
    )
  }

  /** A built dashboard's popups by id: the cases of the page's `popups` match
    * (`components/popup.pkl`).
    */
  def popups(d: Dashboard): Map[String, LayoutNode.Component] =
    d.index.all
      .map(d.index.node)
      .filter(_.card == "popup")
      .flatMap(n => n.id.map(_ -> n))
      .toMap
