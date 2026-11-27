package fh.view.runtime

import fh.view.testkit.TestCard
import api.homeassistant.ws.domain.{HistoryPoint, StatisticsPeriod}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.history.{History, SeriesSource}
import fh.view.model.{
  SlotType,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  FetchTemplate,
  Choice,
  SlotRead,
  Step,
  Value,
  ValueExpr,
  TemplateSlot
}
import fh.view.fetch.{FetchIdentity, FetchResolver, FetchSnapshot}
import fh.view.testkit.DashboardBuilders.declared
import fh.view.history.Windows
import fh.view.testkit.Slots
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Instant
import java.util.concurrent.TimeUnit

/** What charts cost the render paths, WARM — every fetch and drawing already
  * cached, which is the steady state a live page sits in. [[RenderBench]]'s
  * dashboard plus four charts in the body and four in a selected tab.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 5 -i 5 .*FetchBench.*'
  * }}}
  *
  *   - `pullPlain` — a live pull that moves one card, nothing open: asks
  *     nothing.
  *   - `reconnectTabOpen` — that card's resume on a reconnect, the chart tab
  *     open and nothing held: the sweep re-checks every shown node, so the
  *     tab's four charts are asked.
  *   - `pullChart` — a pull that renders one body chart.
  *   - `pagePrepass` — resolving a page's eight reads.
  *   - `pageWithCharts` — that, plus the page walk.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class FetchBench {
  import FetchBench.*

  private var renderer: Renderer = null
  private var resolver: FetchResolver = null
  private var st: Shared = null
  // `st` as a viewer on the declared tab sees it: its panel is open.
  private var tabOpen: (Shared, VarEnv) = null
  private var cache: RenderCache = null
  private val asOf = Instant.parse("2026-09-24T12:00:00Z")
  private var rot = 0L

  @Setup(Level.Trial)
  def setup(): Unit = {
    st = Shared.of(RenderBench.states(RenderBench.Leaves))
    renderer = Renderer.create(dashboard)
    val tabEnv = renderer.vars.env(Map.empty)
    tabOpen = (st.viewing(tabEnv), tabEnv)
    resolver = (for {
      history <- History.create(
        new SeriesSource {
          def raw(start: Instant, end: Instant, entityId: String) =
            IO.pure(
              List.tabulate(200)(i =>
                HistoryPoint("1.0", start.plusSeconds(i * 60L))
              )
            )
          def statistics(
              start: Instant,
              end: Instant,
              entityId: String,
              period: StatisticsPeriod
          ) = IO.pure(Nil)
        },
        // Fixed, so a bucket never rolls mid-run and the warm stays warm.
        now = IO.pure(asOf)
      )
      r <- FetchResolver.create(history, IO.pure((_, _) => IO.pure(Svg)))
    } yield r).unsafeRunSync()
    cache = RenderCache.create.unsafeRunSync()
    // Warm: every read drawn once.
    val _ = answer(renderer.fetchesForPage(tabOpen._1, tabOpen._2))
      .unsafeRunSync()
  }

  // Server.answer's shape: an empty set asks nothing.
  private def answer(reads: List[SlotRead]): IO[FetchSnapshot] =
    if (reads.isEmpty) IO.pure(FetchSnapshot.of(Map.empty))
    else
      FetchSnapshot.resolve(
        resolver,
        reads,
        Map.empty,
        FetchIdentity.Instance
      )

  private def pull(
      node: NodeId,
      at: (Shared, VarEnv),
      coverage: Patches.Coverage = Patches.Coverage.Logged
  ): List[Addressed] = {
    rot += 1
    Patches
      .resume(
        renderer,
        cache,
        renderer.index
          .ixsOf(node)
          .foldLeft(FragmentLog("bench"))(_.touched(_, rot)),
        Map.empty,
        at._1,
        reads => answer(reads.toList),
        answer,
        at._2,
        rot,
        coverage
      )
      .unsafeRunSync()
  }

  @Benchmark
  def pullPlain(bh: Blackhole): Unit =
    bh.consume(pull(PlainId, (st, Map.empty)))

  @Benchmark
  def reconnectTabOpen(bh: Blackhole): Unit =
    bh.consume(pull(PlainId, tabOpen, Patches.Coverage.Swept))

  @Benchmark
  def pullChart(bh: Blackhole): Unit =
    bh.consume(pull(ChartId, (st, Map.empty)))

  @Benchmark
  def pagePrepass(bh: Blackhole): Unit =
    bh.consume(
      answer(renderer.fetchesForPage(tabOpen._1, tabOpen._2))
        .unsafeRunSync()
    )

  @Benchmark
  def pageWithCharts(bh: Blackhole): Unit = {
    val f =
      answer(renderer.fetchesForPage(tabOpen._1, tabOpen._2))
        .unsafeRunSync()
    bh.consume(
      renderer.renderPageInto(
        Sink.buffer(renderer.pageBytesHint),
        st,
        Viewing(f)
      )
    )
  }
}

object FetchBench {

  // About the size of a real ECharts drawing.
  val Svg: String = "<svg>" + ("<path d=\"M0 0L1 1\"/>" * 250) + "</svg>"

  val PlainId: NodeId = NodeId.derived("plain")
  val ChartId: NodeId = NodeId.derived("chart0")

  private def chart(entity: String, id: Option[String] = None) =
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

  def dashboard: Dashboard = Dashboard(
    cards = RenderBench.cards ++ Map(
      "chart" -> TestCard("<div>{{{chart}}}</div>"),
      "host" -> TestCard(
        """{{{panel}}}""",
        regions = Set("panel")
      )
    ),
    card = LayoutNode.Component(
      "col",
      slots = Slots.kids(
        (RenderBench.leaf(signals = true)(0).copy(id = Some("plain")) ::
          RenderBench.tree(RenderBench.Leaves, 4) ::
          List.tabulate(4)(i =>
            chart(s"sensor.main_$i", Option.when(i == 0)("chart0"))
          ) ++
          List(
            LayoutNode.Component(
              "host",
              Map(
                "panel" -> Slots.tabs(
                  "tab",
                  LayoutNode.Component(
                    "col",
                    slots = Slots.kids(
                      List.tabulate(4)(i => chart(s"sensor.tab_$i"))*
                    )
                  )
                )
              ),
              id = Some("tabs"),
              vars = declared("tab" -> "0")
            )
          ))*
      )
    )
  )
}
