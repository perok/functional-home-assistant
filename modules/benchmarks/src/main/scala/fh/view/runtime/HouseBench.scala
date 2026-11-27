package fh.view.runtime

import cats.effect.unsafe.implicits.global
import fh.view.build.{AddonBootstrap, DashboardBuild, PklDump, Site}
import fh.view.fetch.FetchSnapshot
import fh.view.testkit.PklWorkspace
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

/** The starter site's `home` over [[HouseShape]], built through Pkl as a boot
  * builds it, under the Pi's heap and collector (`home-addon/run.sh`). Every
  * other page bench is synthetic; this is the one whose B/op carries to the Pi.
  * Its time carries in direction only.
  *
  * `pageWalk` is a page load's body (the shipped writer chain, as
  * `RenderBench.pageWalkStream`); `repaint` is what a stream's opening sends
  * when it cannot resume; `pageWalkCold` is the first walk in a fresh JVM, and
  * its B/op counts the setup's Pkl evaluation (the gc profiler charges a
  * trial's setup to its first iteration), so read its time only. The minifier
  * is off, so the head's CSS is the unminified text.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(
  value = 3,
  jvmArgsAppend = Array("-XX:+UseSerialGC", "-Xms64M", "-Xmx512M")
)
class HouseBench {

  private var renderer: Renderer = null
  private var shared: Shared = null
  private var viewing: Viewing = null

  @Setup(Level.Trial)
  def setup(): Unit = {
    val ws = os.temp.dir()
    val _ =
      PklWorkspace.bootstrap(ws, PklDump.render(HouseShape.transformedDump))
    os.write.over(ws / Site.EntryFile, AddonBootstrap.starterSite)
    val (site, _) = DashboardBuild.evalSite(ws).unsafeRunSync()
    val home = site.dashboards
      .collectFirst { case ("home", d) => d }
      .getOrElse(sys.error("the starter site has no `home`"))
      .fold(sys.error, identity)
    renderer = Renderer(home)
    // Nothing renders here, or `pageWalkCold` would not be cold.
    // A fresh session, as a page load opens one: the sliders read `control`.
    val env = renderer.vars.env(renderer.initialValuesFor(HouseShape.states))
    shared = Shared.of(HouseShape.states).viewing(env)
    viewing = Viewing(FetchSnapshot.of(Map.empty, env))
  }

  private def walk(): (Long, Int) = {
    val sink = new RenderBench.CountingOutputStream
    val w = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(sink, UTF_8),
      Server.PageChunkBytes
    )
    val own = renderer.renderPageInto(
      Sink.streaming(w),
      shared,
      viewing
    )
    w.flush()
    (sink.count, own.size)
  }

  private def repaintFrames() =
    Patches.repaint(renderer, shared, viewing)

  @Benchmark
  def pageWalk(bh: Blackhole): Unit = bh.consume(walk())

  @Benchmark
  def repaint(bh: Blackhole): Unit = {
    val (frames, held) = repaintFrames()
    bh.consume(frames.foldLeft(0)(_ + _.bytes.length))
    bh.consume(held.size)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SingleShotTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 0)
  @Measurement(iterations = 1)
  @Fork(
    value = 10,
    jvmArgsAppend = Array("-XX:+UseSerialGC", "-Xms64M", "-Xmx512M")
  )
  def pageWalkCold(bh: Blackhole): Unit = bh.consume(walk())
}
