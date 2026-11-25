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
import fh.view.testkit.PklFixture
import fh.view.runtime.RendererTestOps.ixOf

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

  private val tabs = PklFixture.shape(
    """card = (c.tabs) { id = "charted"; tabs { ["A"] { t.hole("p") } } }"""
  )

  private val renderer = Renderer.create(
    Dashboard(
      cards = tabs.cards ++ Map(
        "dot" -> TestCard("<span>{{state}}</span>"),
        "chart" -> TestCard("<div>{{{chart}}}</div>"),
        "col" -> TestCard(
          "<div>{{{children}}}</div>",
          regions = Set("children")
        )
      ),
      card = LayoutNode.Component(
        "col",
        slots = Slots.kids(
          plain("plain"),
          chart("sensor.main", Some("chart")),
          tabs.fill("p" -> chart("sensor.tab", None))
        )
      )
    )
  )

  /** The viewer who has the tab bar's only tab chosen. */
  private val onTab =
    choosing(renderer, "charted", "tab", "0")

  /** The entities of every read the pull asked for, for a viewer whose values
    * are `env`: none chosen unless given. A live pull's unless `coverage` says
    * a reconnect's.
    */
  private def asked(
      log: FragmentLog,
      env: VarEnv = Map.empty,
      coverage: Patches.Coverage = Patches.Coverage.Logged
  ) = {
    val snapshot = FetchSnapshot.of(
      renderer
        .fetchesForPage(
          Shared.of(Map.empty).viewing(onTab),
          onTab
        )
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
        env,
        0L,
        coverage
      )
      reads <- seen.get
    } yield reads.map { case SlotRead(FetchRequest.History(e, _), _) =>
      e
    }.toSet).unsafeRunSync()
  }

  test("a pull that moves only a plain card asks for nothing") {
    assertEquals(
      asked(FragmentLog("test").touched(renderer.ixOf("plain"), 1L)),
      Set.empty
    )
  }

  test("a pull that renders a chart asks for that chart") {
    assertEquals(
      asked(FragmentLog("test").touched(renderer.ixOf("chart"), 1L)),
      Set("sensor.main")
    )
  }

  test(
    "a reconnect asks for every chart on its screen, the body's and the tab's"
  ) {
    // A reconnect re-checks every node it shows, and a chart's key holds its
    // read's version, so both are asked though only a light moved.
    assertEquals(
      asked(
        FragmentLog("test").touched(renderer.ixOf("plain"), 1L),
        env = onTab,
        coverage = Patches.Coverage.Swept
      ),
      Set("sensor.main", "sensor.tab")
    )
  }
}
