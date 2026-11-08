package fh.view.runtime

import fh.view.build.DashboardBuild
import fh.view.model.Dashboard
import io.circe.parser.parse
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** `Dashboard.validated` over each checked-in wire snapshot: what every reload
  * and dump refresh pays per dashboard. Read from the branch's own snapshots,
  * so the same file measures a wire change before and after it. Each call
  * validates a freshly decoded dashboard, as a reload does: a node caches its
  * slots' typing, so validating one dashboard twice would time a second pass
  * that no reload makes.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class ValidateBench {

  @Param(
    Array("taps", "readings", "branches", "starter")
  )
  var fixture: String = ""

  private var wire: io.circe.Json = null
  private var dashboard: Dashboard = null

  @Setup
  def setup(): Unit =
    wire = DashboardBuild.hoistInlineSurfaces(
      parse(
        os.read(
          os.pwd / "modules" / "fh-datastar-view" / "src" / "test" /
            "resources" / "snapshots" / s"$fixture.json"
        )
      ).fold(throw _, identity)
    )

  @Setup(Level.Invocation)
  def decode(): Unit = dashboard = wire.as[Dashboard].fold(throw _, identity)

  @Benchmark
  def validated(bh: Blackhole): Unit = bh.consume(dashboard.validated())
}
