package fh.view.runtime

import cats.effect.{IO, Resource}
import fh.view.telemetry.Telemetry
import fh.view.testkit.{FixtureEntries, PklFixture}
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import org.typelevel.otel4s.oteljava.OtelJava

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** `dashboard.stream.open` through a real SDK: the stream's body runs after the
  * route returns, so a span that silently opened its own trace (#75) is the
  * failure to catch, beside the attributes.
  */
class StreamOpenSpanSuite extends munit.CatsEffectSuite {

  private val entry = FixtureEntries.cards
  private val dash =
    PklFixture.buildDashboard(entry.slug, entry.source, entry.dump)

  private def collecting: Resource[IO, (Telemetry.Otel, InMemorySpanExporter)] =
    Resource.eval(IO(InMemorySpanExporter.create())).flatMap { spans =>
      Resource
        .make(
          IO(
            OpenTelemetrySdk
              .builder()
              .setTracerProvider(
                SdkTracerProvider
                  .builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
              )
              .build()
          )
        )(sdk => IO(sdk.close()).void)
        .evalMap(sdk => OtelJava.fromJOpenTelemetry[IO](sdk))
        .map(otel =>
          Telemetry.Otel(
            otel.tracerProvider,
            otel.meterProvider,
            otel.loggerProvider
          ) -> spans
        )
    }

  private def named(spans: InMemorySpanExporter, name: String): List[SpanData] =
    spans.getFinishedSpanItems.asScala.toList.filter(_.getName == name)

  test("a stream with no cursor repaints, under the request's trace") {
    collecting.use { (otel, spans) =>
      TestServer
        .resource(dash, entry.entities, otel = otel)
        .use(_.connect())
        .timeout(30.seconds)
        .map { client =>
          val open = named(spans, "dashboard.stream.open") match
            case List(one) => one
            case other     => fail(s"one open span expected, got $other")
          val attrs = open.getAttributes
          assertEquals(
            attrs.get(AttributeKey.stringKey("fh.opening")),
            "repaint"
          )
          assertEquals(
            attrs.get(AttributeKey.stringKey("fh.repaint.reason")),
            "NoCursor"
          )
          assert(attrs.get(AttributeKey.longKey("fh.frames")) >= 2L)
          assert(attrs.get(AttributeKey.longKey("fh.bytes")) > 1000L)
          assert(open.getParentSpanContext.isValid, "opened its own trace")
          val traces = spans.getFinishedSpanItems.asScala.map(_.getTraceId)
          assert(
            traces.count(_ == open.getTraceId) > 1,
            "no other span shares the open's trace"
          )
          client
        }
    }
  }
}
