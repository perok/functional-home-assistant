package fh.view.runtime

import fh.view.testkit.Slots

import fh.view.testkit.TestCard
import fh.view.history.Windows
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fh.view.model.{
  SlotType,
  CardDef,
  Dashboard,
  LayoutNode,
  FetchTemplate,
  Choice,
  SlotRead,
  Step,
  Value,
  ValueExpr,
  TemplateSlot
}
import fh.view.fetch.{FetchRequest, FetchSnapshot, Staged}
import fh.view.testkit.TestIds.given
import fh.view.testkit.DashboardBuilders.declared

/** A pull asks for the reads of what it renders, and nothing else — most pulls
  * move a plain card and ask none.
  */
class PullReadsSuite extends munit.FunSuite {

  private def plain(id: String) = LayoutNode.Component(
    "dot",
    Slots.values(
      Map(
        "entity_id" -> TemplateSlot(ValueExpr.literal("light.a")),
        "state" -> TemplateSlot(ValueExpr(Value.Entity(None)))
      )
    ),
    id = Some(id)
  )

  private def chart(entity: String, id: Option[String]) =
    LayoutNode.Component(
      "chart",
      Slots.values(
        Map(
          "chart" -> TemplateSlot(
            ValueExpr(
              Value.Fetch(FetchTemplate(entity, Choice.Fixed(Windows.day))),
              List(Step.Chart())
            ),
            `type` = SlotType.TextRaw
          )
        )
      ),
      id = id
    )

  private def host(id: String) =
    LayoutNode.Component(
      "tabs",
      Map("panel" -> Slots.tabs("tab", chart("sensor.tab", None))),
      id = Some(id),
      vars = declared("tab" -> "0")
    )

  private val renderer = Renderer.create(
    Dashboard(
      cards = Map(
        "dot" -> TestCard("<span>{{state}}</span>"),
        "chart" -> TestCard("<div>{{{chart}}}</div>"),
        "col" -> TestCard(
          "<div>{{{children}}}</div>",
          regions = Set("children")
        ),
        "tabs" -> TestCard(
          """{{{panel}}}""",
          regions = Set("panel")
        )
      ),
      card = LayoutNode.Component(
        "col",
        slots = Slots.kids(
          plain("plain"),
          chart("sensor.main", Some("chart")),
          host("charted")
        )
      )
    )
  )

  /** The entities of every read the pull asked for. */
  private def asked(log: FragmentLog, open: Set[String] = Set.empty) = {
    val snapshot = FetchSnapshot.of(
      renderer
        .fetchesForPage(Set("charted_t0"), Shared.of(Map.empty), Map.empty)
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
        Shared.of(Map("light.a" -> EntityState("light.a", "on", Map.empty))),
        noDecisionFetches,
        reads => seen.update(_ ++ reads).as(snapshot),
        Map.empty,
        0L,
        open
      )
      reads <- seen.get
    } yield reads.map { case SlotRead(FetchRequest.History(e, _), _) =>
      e
    }.toSet).unsafeRunSync()
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
      asked(FragmentLog("test").touched("plain", 1L), open = Set("charted_t0")),
      Set("sensor.tab")
    )
  }
}
