package fh.view.history

import cats.effect.std.Mutex
import cats.effect.{IO, Resource}
import fh.view.runtime.JsIsolate
import io.circe.Json
import org.graalvm.polyglot.{Context, Engine, HostAccess, Source}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

/** Series in, SVG out.
  *
  * Server-rendered SVG rather than a canvas chart in the browser, and that is
  * forced rather than preferred: Datastar morphs the DOM, which severs a
  * canvas's element association silently — no error, a dead chart — and
  * `data-ignore-morph` cannot rescue it, because its guard is one-sided and
  * unconditional, so marking a chart host also kills every live update beneath
  * it. As SVG a chart is an ordinary leaf card: it patches, caches, digests,
  * resumes, and survives a morph.
  *
  * ONE context for the process, serialised by a mutex. Two reasons, and the
  * obvious one is the weaker: a Graal context is not safe for concurrent use,
  * which a pool would also solve. The real one is that evaluating ECharts costs
  * ~300 ms in the isolate and a context does not share that with its siblings,
  * so a pool pays it per member. Render rate is bounded by [[SeriesStore]] to
  * roughly one per window per bucket, which one context absorbs; a pool becomes
  * worth its memory only if that stops being true.
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

  /** What the add-on runs: the polyglot isolate. */
  def resource: Resource[IO, ChartRenderer] =
    JsIsolate.engine.flatMap(fromEngine)

  /** For an engine somebody else built — the in-heap one tests use, since the
    * isolate exists only inside the add-on image.
    */
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

  /** zrender starts an animation loop at init, so these must EXIST. With
    * `animation: false` they are never actually fired, which is why no-ops are
    * enough and no timer machinery is needed.
    */
  private val shim: Source =
    js(
      "fh-shim.js",
      """globalThis.setTimeout = function () { return 0; };
        |globalThis.clearTimeout = function () {};
        |globalThis.setInterval = function () { return 0; };
        |globalThis.clearInterval = function () {};
        |""".stripMargin
    )

  /** The option object arrives as a JSON STRING rather than as a host object.
    *
    * Measured at 2 000 points: a JSON string costs 0.48 ms across the isolate
    * boundary against 0.02 ms for a primitive `double[]`, so the faster shape
    * is known. It is not used yet because splitting the series out of the
    * option object means handing over host arrays, whose access rules interact
    * with `HostAccess.SCOPED` — and that combination cannot be verified outside
    * the add-on image, where these tests do not run. Sub-millisecond once per
    * cache bucket is not what to spend an unverifiable change on.
    */
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

  /** Read once per process, from the classpath, where the build put it
    * (`node_modules/echarts/dist` -> managed resources).
    */
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

  /** `cached(true)` is the default and is what makes one parse serve every
    * context built later; naming the source is what puts a readable file in a
    * guest stack trace instead of `<eval>`.
    */
  private def js(name: String, code: String): Source =
    Source.newBuilder("js", code, name).buildLiteral()
}
