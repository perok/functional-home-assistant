package fh.view.build

import fh.view.testkit.{HouseFixture, PklWorkspace}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** The starter site evaluated over the fixture house, its eval cache cleared
  * first: `cold` is a boot's first evaluation in a fresh JVM, `warm` a dump
  * refresh or an edit in a running server. The library is read from `os.pwd`,
  * so run it from the checkout being measured.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class PklEvalBench {

  private var ws: os.Path = null
  private var evalCache: os.Path = null

  @Setup(Level.Trial)
  def setup(): Unit = {
    ws = os.temp.dir()
    val cache =
      PklWorkspace.bootstrap(ws, PklDump.render(HouseFixture.transformedDump))
    os.write.over(ws / Site.EntryFile, AddonBootstrap.starterSite)
    evalCache = SiteEvalCache.dir(cache)
  }

  @Setup(Level.Invocation)
  def uncached(): Unit = os.remove.all(evalCache)

  private def evaluate(): SourceEval.Result = {
    val r = SourceEval.eval(ws, Site.EntryFile).fold(sys.error, identity)
    require(!r.fromCache, "served from the eval cache")
    r
  }

  @Benchmark
  @Warmup(iterations = 0)
  @Measurement(iterations = 1)
  @Fork(10)
  def cold(bh: Blackhole): Unit = bh.consume(evaluate())

  @Benchmark
  @Warmup(iterations = 15)
  @Measurement(iterations = 15)
  @Fork(2)
  def warm(bh: Blackhole): Unit = bh.consume(evaluate())
}
