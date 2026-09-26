package fh.view.history

import cats.effect.std.Mutex
import cats.effect.{IO, Resource}
import fh.view.runtime.JsIsolate
import fh.view.telemetry.Logging
import io.circe.Json
import org.graalvm.polyglot.{Context, Engine, HostAccess, Source}
import org.typelevel.log4cats.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

/** Series in, SVG out — server-side because a morph silently kills a canvas
  * chart (ADR 0032).
  *
  * ONE context behind a mutex rather than a pool: each context pays ~300 ms to
  * evaluate ECharts, and the resolver's stage cache bounds drawings to one per
  * chart per version. What that costs a cold page is architecture §8's.
  */
final class ChartRenderer private (context: Context, lock: Mutex[IO]) {
  import ChartRenderer.InterruptWait

  def render(series: Series, style: ChartStyle): IO[String] =
    renderOption(ChartOption(series, style), style)

  def renderOption(option: Json, style: ChartStyle): IO[String] =
    lock.lock.surround {
      IO.blocking {
        context
          .getBindings("js")
          .getMember("fhRenderChart")
          .execute(option.noSpaces, style.width, style.height)
          .asString()
      }.cancelable(
        // A timed-out drawing would otherwise run on, holding the lock every
        // other chart waits behind. Interrupting leaves the context usable.
        IO.blocking(context.interrupt(InterruptWait))
          .void
          .handleError(_ => ())
      )
    }
}

object ChartRenderer {

  private val InterruptWait = java.time.Duration.ofSeconds(1)

  def resource(
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): Resource[IO, ChartRenderer] =
    JsIsolate
      .engineOrInHeap(e =>
        loggerFactory
          .getLoggerFromName("fh.view.history.ChartRenderer")
          .warn(
            "no GraalJS isolate, so charts are drawn in-heap where the classpath " +
              "has JavaScript (slower) and fail where it does not, as in the " +
              s"add-on image: ${e.getMessage}"
          )
      )
      .flatMap(fromEngine)

  /** On an engine the caller chose. */
  def fromEngine(engine: Engine): Resource[IO, ChartRenderer] =
    for {
      context <- Resource.fromAutoCloseable(
        IO.blocking(
          Context
            .newBuilder("js")
            .engine(engine)
            .allowHostAccess(HostAccess.SCOPED)
            .build()
        )
      )
      _ <- Resource.eval(IO.blocking {
        context.eval(shim)
        context.eval(echarts)
        context.eval(entry)
      }.void)
      lock <- Resource.eval(Mutex[IO])
    } yield new ChartRenderer(context, lock)

  // zrender's animation loop needs these to exist; with `animation: false`
  // they never fire.
  private val shim: Source =
    js(
      "fh-shim.js",
      """globalThis.setTimeout = function () { return 0; };
        |globalThis.clearTimeout = function () {};
        |globalThis.setInterval = function () { return 0; };
        |globalThis.clearInterval = function () {};
        |""".stripMargin
    )

  // A JSON string crosses the isolate in 0.48 ms at 2 000 points, a
  // `double[]` in 0.02 ms (measured). Not worth reshaping the option object
  // for, once per cache bucket.
  private val entry: Source =
    js(
      "fh-chart.js",
      """globalThis.fhRenderChart = function (optionJson, width, height) {
        |  var chart = echarts.init(null, null, {
        |    renderer: 'svg', ssr: true, width: width, height: height
        |  });
        |  try {
        |    chart.setOption(JSON.parse(optionJson));
        |    return chart.renderToSVGString();
        |  } finally {
        |    chart.dispose();
        |  }
        |};
        |""".stripMargin
    )

  private lazy val echarts: Source = {
    val bytes = Using.resource(
      Option(getClass.getResourceAsStream("/chart/echarts.min.js"))
        .getOrElse(
          throw new IllegalStateException(
            "chart/echarts.min.js is not on the classpath — the build stages it from node_modules"
          )
        )
    )(_.readAllBytes())
    js("echarts.min.js", String(bytes, UTF_8))
  }

  // Named so a guest stack trace shows a file rather than `<eval>`.
  private def js(name: String, code: String): Source =
    Source.newBuilder("js", code, name).buildLiteral()
}
