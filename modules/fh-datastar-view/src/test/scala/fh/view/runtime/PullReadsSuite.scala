package fh.view.runtime

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  QueryTemplate,
  Reads,
  Ref as SlotRef,
  Region,
  SlotRead,
  SlotSource,
  Surface,
  Transform
}
import fh.view.query.{QuerySnapshot, Staged}
import fh.view.testkit.TestIds.given

/** A pull asks for the reads of what it renders, and nothing else — most pulls
  * move a plain card and ask none.
  */
class PullReadsSuite extends munit.FunSuite {

  private def plain(id: String) = LayoutNode.Component(
    "dot",
    Map(
      "entity_id" -> SlotSource(literal = Some("light.a")),
      "state" -> SlotSource()
    ),
    id = Some(id)
  )

  private def chart(entity: String, id: Option[String]) =
    LayoutNode.Component(
      "chart",
      Map(
        "chart" -> SlotSource(
          query = Some(
            QueryTemplate(
              "history",
              Map(
                "entity" -> SlotRef.Literal(entity),
                "window" -> SlotRef.Literal("24h")
              )
            )
          ),
          transform = Transform.Stage.Chart(),
          reads = Reads.OnRender
        )
      ),
      id = id
    )

  private def host(id: String) = LayoutNode.Component("col", id = Some(id))

  private val renderer = Renderer.create(
    Dashboard(
      cards = Map(
        "dot" -> CardDef("<span>{{state}}</span>", slots = List("state")),
        "chart" -> CardDef("<div>{{{chart}}}</div>", slots = List("chart")),
        "col" -> CardDef(
          "<div>{{#children}}{{{html}}}{{/children}}</div>",
          regions = Map("children" -> Region())
        )
      ),
      card = LayoutNode.Component(
        "col",
        regions = LayoutNode.kids(
          plain("plain"),
          chart("sensor.main", Some("chart")),
          host("charted")
        )
      ),
      surfaces = Map(
        "withChart" -> Surface(
          chart("sensor.tab", None),
          bakeInto = Some(NodeId.derived("charted")),
          bakeAs = Some("panel"),
          bakeIndex = Some(0)
        )
      )
    )
  )

  /** The entities of every read the pull asked for. */
  private def asked(log: FragmentLog, open: Set[String] = Set.empty) = {
    val snapshot = QuerySnapshot.of(
      renderer
        .queriesForPage(Set("withChart"), Map.empty, Map.empty)
        .map(_ -> Staged(1L, "<svg/>"))
        .toMap
    )
    (for {
      seen <- Ref[IO].of(List.empty[SlotRead])
      cache <- RenderCache.create
      _ <- Patches.resume(
        renderer,
        cache,
        log,
        Map.empty,
        Map("light.a" -> EntityState("light.a", "on", Map.empty)),
        reads => seen.update(_ ++ reads).as(snapshot),
        Map.empty,
        0L,
        open
      )
      reads <- seen.get
    } yield reads.map(_.query.params("entity")).toSet).unsafeRunSync()
  }

  test("a pull that moves only a plain card asks for nothing") {
    assertEquals(asked(FragmentLog("test").touched("plain", 1L)), Set.empty)
  }

  test("a pull that renders a chart asks for that chart") {
    assertEquals(
      asked(FragmentLog("test").touched("chart", 1L)),
      Set("sensor.main")
    )
  }

  test("an open tab's chart is asked on every pull, and only that one") {
    // A pull re-checks every node of an open surface, and a chart's key holds
    // its read's version — so the tab's chart is asked even when only a light
    // moved, and the body's is not.
    assertEquals(
      asked(FragmentLog("test").touched("plain", 1L), open = Set("withChart")),
      Set("sensor.tab")
    )
  }
}
