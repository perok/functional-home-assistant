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
  SlotSource,
  Surface,
  Transform
}
import fh.view.query.{QuerySnapshot, Staged}
import fh.view.testkit.TestIds.given

/** A pull asks for answers only when a node it renders can reach a query —
  * which most pulls, moving a plain card, cannot.
  */
class QueryGateSuite extends munit.FunSuite {

  private def plain(id: String) = LayoutNode.Component(
    "dot",
    Map(
      "entity_id" -> SlotSource(literal = Some("light.a")),
      "state" -> SlotSource()
    ),
    id = Some(id)
  )

  private val chart = LayoutNode.Component(
    "chart",
    Map(
      "chart" -> SlotSource(
        query = Some(
          QueryTemplate(
            "history",
            Map(
              "entity" -> SlotRef.Literal("sensor.t"),
              "window" -> SlotRef.Literal("24h")
            )
          )
        ),
        transform = Transform.Stage.Chart(),
        reads = Reads.OnRender
      )
    ),
    id = Some("chart")
  )

  private def host(id: String) = LayoutNode.Component("col", id = Some(id))

  private def baked(into: String, content: LayoutNode) =
    Surface(
      content,
      bakeInto = Some(NodeId.derived(into)),
      bakeAs = Some("panel"),
      bakeIndex = Some(0)
    )

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
          chart,
          host("charted"),
          host("quiet")
        )
      ),
      surfaces = Map(
        "withChart" -> baked("charted", chart.copy(id = None)),
        "withoutChart" -> baked("quiet", plain("inner"))
      )
    )
  )

  test("a chart, its ancestors and a host of one reach a query; nothing else") {
    val reach = renderer.mayReadQueries
    assert(reach("chart"))
    assert(reach("c"), clue = "the root renders the chart beneath it")
    assert(reach("charted"), clue = "it hosts a surface holding a chart")
    assert(reach(renderer.surfaceContentId("withChart")))
    assert(!reach("plain"))
    assert(!reach("quiet"))
    assert(!reach("inner"))
  }

  private def asked(log: FragmentLog): Int = {
    val snapshot = QuerySnapshot.of(
      renderer
        .queriesForPage(Set.empty, Map.empty, Map.empty)
        .map(_ -> Staged(1L, "<svg/>"))
        .toMap
    )
    (for {
      count <- Ref[IO].of(0)
      cache <- RenderCache.create
      _ <- Patches.resume(
        renderer,
        cache,
        log,
        Map.empty,
        Map("light.a" -> EntityState("light.a", "on", Map.empty)),
        count.update(_ + 1).as(snapshot),
        0L
      )
      n <- count.get
    } yield n).unsafeRunSync()
  }

  test("a pull that moves only a plain card asks for no answers") {
    assertEquals(asked(FragmentLog("test").touched("plain", 1L)), 0)
  }

  test("a pull that renders a chart asks once") {
    assertEquals(asked(FragmentLog("test").touched("chart", 1L)), 1)
  }
}
