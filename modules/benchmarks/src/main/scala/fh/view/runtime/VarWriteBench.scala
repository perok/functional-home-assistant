package fh.view.runtime

import fh.view.testkit.TestCard
import api.homeassistant.ws.domain.{HistoryPoint, StatisticsPeriod}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fh.view.history.{History, SeriesSource}
import fh.view.model.{
  Activation,
  Choice,
  Dashboard,
  FetchTemplate,
  LayoutNode,
  NodeId,
  Region,
  SlotRead,
  SlotType,
  Step,
  Surface,
  TemplateSlot,
  Value,
  ValueExpr,
  VarAt
}
import fh.view.fetch.{FetchIdentity, FetchResolver, FetchSnapshot}
import fh.view.testkit.DashboardBuilders.declared
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Instant
import java.util.concurrent.TimeUnit

/** What one variable write costs the server, `Server.setVar`'s pure core: the
  * check, the viewer's environment, then either the panel it selects rendered
  * into its host or its readers re-rendered through the slug's cache.
  *
  *   - `tabFlip` — a tab bar's `tab` alternating between two panels of
  *     [[PanelLeaves]] leaves.
  *   - `selectChange` — a `c.select`'s value alternating between two members
  *     selected at a value, not an index.
  *   - `chartWindow` — two viewers alternating a `window` over four charts
  *     reading it, warm: every fetch and drawing cached, so what is left is the
  *     render key and the cache.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 5 -i 5 -prof gc -jvmArgsAppend -XX:-DoEscapeAnalysis .*VarWriteBench.*'
  * }}}
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class VarWriteBench {
  import VarWriteBench.*

  private var renderer: Renderer = null
  private var resolver: FetchResolver = null
  private var st: Shared = null
  private var cache: RenderCache = null
  private val asOf = Instant.parse("2026-09-24T12:00:00Z")
  private var rot = 0

  @Setup(Level.Trial)
  def setup(): Unit = {
    st = Shared.of(RenderBench.states(RenderBench.Leaves))
    renderer = Renderer.create(dashboard)
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
        now = IO.pure(asOf)
      )
      r <- FetchResolver.create(
        history,
        IO.pure((_, _) => IO.pure(FetchBench.Svg))
      )
    } yield r).unsafeRunSync()
    cache = RenderCache.create.unsafeRunSync()
    // Warm: both windows drawn once.
    WindowValues.foreach { w =>
      val _ = chartWrite(w)
    }
  }

  private def answer(
      reads: List[SlotRead],
      env: VarEnv
  ): IO[FetchSnapshot] =
    if (reads.isEmpty) IO.pure(FetchSnapshot.of(Map.empty, env))
    else FetchSnapshot.resolve(resolver, reads, env, FetchIdentity.Instance)

  /** The swap `Server.setVar` makes for a panel group. */
  private def panelWrite(key: VarKey, value: String): Option[String] = {
    val proposed = Map(key -> value)
    require(renderer.refusals(proposed).isEmpty)
    val env = renderer.vars.env(proposed)
    val after = renderer.surfaces.selections(None, env)
    renderer.vars
      .panelsSelectedBy(key)
      .flatMap { gid =>
        renderer.surfaces
          .memberAt(gid, value)
          .map(renderer.surfaces.bakeGroup(gid))
          .flatMap(sid =>
            renderer
              .surface(sid)
              .flatMap(surf =>
                Patches.hostFill(
                  renderer,
                  surf.hostId,
                  Some(sid),
                  st,
                  Viewing(after, FetchSnapshot.of(Map.empty, env))
                )
              )
              .map(_._2)
          )
      }
      .headOption
  }

  /** The readers `Server.setVar` re-renders, through the slug's cache. */
  private def chartWrite(value: String): List[Addressed] = {
    val proposed = Map(WindowKey -> value)
    require(renderer.refusals(proposed).isEmpty)
    val env = renderer.vars.env(proposed)
    val after = renderer.surfaces.selections(None, env)
    val readers = renderer.vars.readersOf(WindowKey)
    (for {
      snapshot <- answer(
        renderer.readsForPull(readers, Nil, st, after, env),
        env
      )
      patches <- readers.traverseFilter(
        Patches.morph(
          renderer,
          cache,
          Map.empty,
          st,
          Viewing(after, snapshot),
          _
        )
      )
    } yield patches).unsafeRunSync()
  }

  private def next(values: Vector[String]): String = {
    rot += 1
    values(rot % values.size)
  }

  @Benchmark
  def tabFlip(bh: Blackhole): Unit =
    bh.consume(panelWrite(TabKey, next(Tabs)))

  @Benchmark
  def selectChange(bh: Blackhole): Unit =
    bh.consume(panelWrite(ViewKey, next(Views)))

  @Benchmark
  def chartWindow(bh: Blackhole): Unit =
    bh.consume(chartWrite(next(WindowValues)))
}

object VarWriteBench {

  /** Leaves in each panel: a section of a dashboard, as `FlipLeaves`. */
  final val PanelLeaves = 20

  val TabKey: VarKey = VarKey(NodeId.derived("tabs"), "tab")
  val ViewKey: VarKey = VarKey(NodeId.derived("view"), "view")
  val WindowKey: VarKey = VarKey(NodeId.derived("charts"), "window")

  val Tabs: Vector[String] = Vector("0", "1")
  val Views: Vector[String] = Vector("a", "b")
  val WindowValues: Vector[String] = Vector("24h", "7d")

  private val ViewValues = List("a", "b", "c")

  private def chart(entity: String) =
    LayoutNode.Component(
      "chart",
      Map(
        "chart" -> TemplateSlot(
          ValueExpr(
            Value.Fetch(
              FetchTemplate(
                entity,
                Choice.Chosen("window", fh.view.history.Windows.presets)
              )
            ),
            List(Step.Chart())
          ),
          `type` = SlotType.TextRaw
        )
      )
    )

  private def panel(from: Int) =
    LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        List.tabulate(PanelLeaves)(i =>
          RenderBench.leaf(signals = true)(from + i)
        )*
      )
    )

  private def host(id: String, name: String, default: String) =
    LayoutNode.Component(
      "host",
      id = Some(id),
      vars = declared(name -> default)
    )

  def dashboard: Dashboard = Dashboard(
    cards = RenderBench.cards ++ Map(
      "chart" -> TestCard("<div>{{{chart}}}</div>"),
      "host" -> TestCard(
        """<div id="{{hostId}}">{{{panel}}}</div>""",
        regions = Map("panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        host("tabs", "tab", "0"),
        host("view", "view", "a"),
        LayoutNode.Component(
          "col",
          regions = LayoutNode.kids(
            List.tabulate(4)(i => chart(s"sensor.main_$i"))*
          ),
          id = Some("charts"),
          vars = declared("window" -> "24h")
        )
      )
    ),
    surfaces = (List.tabulate(2)(i =>
      s"t$i" -> Surface(
        panel(i * PanelLeaves),
        bakeInto = Some(NodeId.derived("tabs")),
        bakeAs = Some("panel"),
        bakeIndex = Some(i),
        activation = Activation.Var("tab")
      )
    ) ++ ViewValues.zipWithIndex.map((v, i) =>
      s"v_$v" -> Surface(
        panel(100 + i * PanelLeaves),
        bakeInto = Some(NodeId.derived("view")),
        bakeAs = Some("panel"),
        bakeIndex = Some(i),
        activation = Activation.Var("view", VarAt.Value(v))
      )
    )).toMap
  )
}
