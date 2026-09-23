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
  * evaluate ECharts, and [[History]] bounds renders to about one per window per
  * bucket.
  */
final class ChartRenderer private (context: Context, lock: Mutex[IO]) {

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
      }
    }
}

object ChartRenderer {

  def resource(
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): Resource[IO, ChartRenderer] =
    JsIsolate
      .engineOrInHeap(e =>
        loggerFactory
          .getLoggerFromName("fh.view.history.ChartRenderer")
          .warn(
            "no GraalJS isolate on the classpath, drawing charts interpreted " +
              s"instead — slower, and not what the add-on image runs: ${e.getMessage}"
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
