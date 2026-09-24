package fh.view.runtime

import api.homeassistant.ws.domain.{HistoryPoint, StatisticsPeriod}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.history.{ChartStage, History, SeriesSource}
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  QueryTemplate,
  Reads,
  Ref,
  Region,
  SlotRead,
  SlotSource,
  Surface,
  Transform
}
import fh.view.query.{QueryIdentity, QueryResolver, QuerySnapshot}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Instant
import java.util.concurrent.TimeUnit

/** What charts cost the render paths, WARM — every fetch and drawing already
  * cached, which is the steady state a live page sits in. [[RenderBench]]'s
  * dashboard plus four charts in the body and four in a selected tab.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 5 -i 5 .*QueryBench.*'
  * }}}
  *
  *   - `pullPlain` — a pull that moves one card, nothing open: asks nothing.
  *   - `pullPlainTabOpen` — the same with the chart tab open: the tab's four
  *     charts are asked, since a resume re-checks every open-surface node.
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
class QueryBench {
  import QueryBench.*

  private var renderer: Renderer = null
  private var resolver: QueryResolver = null
  private var st: Map[String, EntityState] = null
  private var cache: RenderCache = null
  private val asOf = Instant.parse("2026-09-24T12:00:00Z")
  private var rot = 0L

  @Setup(Level.Trial)
  def setup(): Unit = {
    st = RenderBench.states(RenderBench.Leaves)
    renderer = Renderer.create(dashboard)
    resolver = (for {
      history <- History.create(new SeriesSource {
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
      })
      stage <- ChartStage.create(IO.pure((_, _) => IO.pure(Svg)))
    } yield QueryResolver(history, stage)).unsafeRunSync()
    cache = RenderCache.create.unsafeRunSync()
    // Warm: every read drawn once.
    val _ = answer(renderer.queriesForPage(Set("t0"), st, Map.empty))
      .unsafeRunSync()
  }

  // Server.answer's shape: an empty set asks nothing.
  private def answer(reads: List[SlotRead]): IO[QuerySnapshot] =
    if (reads.isEmpty) IO.pure(QuerySnapshot.of(Map.empty))
    else
      QuerySnapshot.resolve(
        resolver,
        renderer.queryRequests,
        reads,
        Map.empty,
        QueryIdentity.Instance,
        asOf
      )

  private def pull(node: NodeId, open: Set[String]): List[Addressed] = {
    rot += 1
    Patches
      .resume(
        renderer,
        cache,
        FragmentLog("bench").touched(node, rot),
        Map.empty,
        st,
        answer,
        Map.empty,
        rot,
        open,
        renderer.surfaces.uiStateFrom(open)
      )
      .unsafeRunSync()
  }

  @Benchmark
  def pullPlain(bh: Blackhole): Unit =
    bh.consume(pull(PlainId, Set.empty))

  @Benchmark
  def pullPlainTabOpen(bh: Blackhole): Unit =
    bh.consume(pull(PlainId, Set("t0")))

  @Benchmark
  def pullChart(bh: Blackhole): Unit =
    bh.consume(pull(ChartId, Set.empty))

  @Benchmark
  def pagePrepass(bh: Blackhole): Unit =
    bh.consume(
      answer(renderer.queriesForPage(Set("t0"), st, Map.empty)).unsafeRunSync()
    )

  @Benchmark
  def pageWithCharts(bh: Blackhole): Unit = {
    val f =
      answer(renderer.queriesForPage(Set("t0"), st, Map.empty)).unsafeRunSync()
    bh.consume(
      renderer.renderPageInto(
        Sink.buffer(renderer.pageBytesHint),
        st,
        Map.empty,
        None,
        f
      )
    )
  }
}

object QueryBench {

  // About the size of a real ECharts drawing.
  val Svg: String = "<svg>" + ("<path d=\"M0 0L1 1\"/>" * 250) + "</svg>"

  val PlainId: NodeId = NodeId.derived("plain")
  val ChartId: NodeId = NodeId.derived("chart0")

  private def chart(entity: String, id: Option[String] = None) =
    LayoutNode.Component(
      "chart",
      Map(
        "chart" -> SlotSource(
          query = Some(
            QueryTemplate(
              "history",
              Map(
                "entity" -> Ref.Literal(entity),
                "window" -> Ref.Literal("24h")
              )
            )
          ),
          transform = Transform.Stage.Chart(),
          reads = Reads.OnRender
        )
      ),
      id = id
    )

  def dashboard: Dashboard = Dashboard(
    cards = RenderBench.cards ++ Map(
      "chart" -> CardDef("<div>{{{chart}}}</div>", slots = List("chart")),
      "host" -> CardDef(
        """<div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        (RenderBench.leaf(signals = true)(0).copy(id = Some("plain")) ::
          RenderBench.tree(RenderBench.Leaves, 4) ::
          List.tabulate(4)(i =>
            chart(s"sensor.main_$i", Option.when(i == 0)("chart0"))
          ) ++
          List(LayoutNode.Component("host", id = Some("tabs"))))*
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "col",
          regions =
            LayoutNode.kids(List.tabulate(4)(i => chart(s"sensor.tab_$i"))*)
        ),
        bakeInto = Some(NodeId.derived("tabs")),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(true)
      )
    )
  )
}
