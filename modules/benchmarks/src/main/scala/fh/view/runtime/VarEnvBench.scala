package fh.view.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  QueryTemplate,
  Reads,
  Ref,
  SlotSource,
  Transform
}
import fh.view.query.QuerySnapshot
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** What a viewer's variables cost per request: `VarGraph.env` runs on each
  * pull, write, connect and page, and a write runs `Renderer.refusals`.
  * Measured against the pull it rides on, on [[RenderBench]]'s 200-leaf tree
  * under a root that declares `window`, the shape a top-level chooser gives:
  * every node is in scope. Building a map per node there cost 30 µs of a 46 µs
  * pull.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 5 -i 5 .*VarEnvBench.*'
  * }}}
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class VarEnvBench {
  import VarEnvBench.*

  private var renderer: Renderer = null
  private var st: Map[String, EntityState] = null
  private var cache: RenderCache = null
  private var rot = 0L
  private val window = VarKey(NodeId.derived("root"), "window")

  @Setup(Level.Trial)
  def setup(): Unit = {
    st = RenderBench.states(RenderBench.Leaves)
    renderer = Renderer.create(dashboard)
    cache = RenderCache.create.unsafeRunSync()
    println(s"\nnodes in scope: ${renderer.vars.env(Map.empty).size}")
  }

  @Benchmark
  def env(bh: Blackhole): Unit =
    bh.consume(renderer.vars.env(Map(window -> "7d")))

  @Benchmark
  def refusals(bh: Blackhole): Unit =
    bh.consume(renderer.refusals(Map(window -> "7d")))

  /** A pull that moves one card and asks nothing: the cheapest thing `env`
    * rides on.
    */
  @Benchmark
  def pullPlain(bh: Blackhole): Unit = {
    rot += 1
    bh.consume(
      Patches
        .resume(
          renderer,
          cache,
          FragmentLog("bench").touched(QueryBench.PlainId, rot),
          Map.empty,
          st,
          _ => IO.pure(QuerySnapshot.of(Map.empty)),
          renderer.vars.env(Map(window -> "7d")),
          rot,
          Set.empty,
          Selections.none
        )
        .unsafeRunSync()
    )
  }
}

object VarEnvBench {

  private def chart(entity: String) =
    LayoutNode.Component(
      "chart",
      Map(
        "chart" -> SlotSource(
          query = Some(
            QueryTemplate(
              "history",
              Map(
                "entity" -> Ref.Literal(entity),
                "window" -> Ref.Var("window")
              )
            )
          ),
          transform = Transform.Stage.Passthrough,
          reads = Reads.OnRender
        )
      )
    )

  def dashboard: Dashboard = Dashboard(
    cards = RenderBench.cards ++ Map(
      "chart" -> CardDef("<div>{{chart}}</div>", slots = List("chart"))
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        (RenderBench.leaf(signals = true)(0).copy(id = Some("plain")) ::
          RenderBench.tree(RenderBench.Leaves, 4) ::
          List.tabulate(4)(i => chart(s"sensor.main_$i")))*
      ),
      id = Some("root"),
      vars = Map("window" -> "24h")
    )
  )
}
