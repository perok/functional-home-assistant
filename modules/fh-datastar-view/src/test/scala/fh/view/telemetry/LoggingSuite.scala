package fh.view.telemetry

import cats.effect.{IO, Resource}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

import scala.jdk.CollectionConverters.*

/** The claim [[Logging]] rests on: a record written inside a span carries that
  * span's ids. A real SDK, because the failure is a context that looks present
  * and is empty, which a stub asserting on our own `withContext` call would
  * miss.
  */
class LoggingSuite extends munit.CatsEffectSuite {

  /** `SimpleLogRecordProcessor`, so a record is exported by the time `emit`
    * returns.
    */
  private def collecting
      : Resource[IO, (Telemetry.Otel, InMemoryLogRecordExporter)] =
    Resource
      .eval(IO(InMemoryLogRecordExporter.create()))
      .flatMap { records =>
        Resource
          .make(
            IO(
              OpenTelemetrySdk
                .builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setLoggerProvider(
                  SdkLoggerProvider
                    .builder()
                    .addLogRecordProcessor(
                      SimpleLogRecordProcessor.create(records)
                    )
                    .build()
                )
                .build()
            )
          )(sdk => IO(sdk.close()).void)
          .evalMap(sdk => OtelJava.fromJOpenTelemetry[IO](sdk))
          .map(otel =>
            (
              Telemetry.Otel(
                otel.tracerProvider,
                otel.meterProvider,
                otel.loggerProvider
              ),
              records
            )
          )
      }

  test("a record written inside a span carries that span's ids") {
    collecting.use { (otel, records) =>
      for {
        factory <- Logging.factory(otel)
        tracer <- otel.tracerProvider.get("test")
        log = factory.getLoggerFromName("fh.test")
        // Captured inside the span; reading them from the record alone would
        // pass on any two equal-looking hex strings.
        ids <- tracer
          .span("under-test")
          .surround(
            log.warn("something to correlate") *>
              tracer.currentSpanContext
          )
        emitted = records.getFinishedLogRecordItems.asScala.toList
      } yield {
        assertEquals(emitted.size, 1)
        val record = emitted.head
        val span = ids.getOrElse(fail("no span was current inside the span"))
        assertEquals(record.getSpanContext.getTraceId, span.traceIdHex)
        assertEquals(record.getSpanContext.getSpanId, span.spanIdHex)
        assertEquals(
          Option(record.getBodyValue).map(_.asString),
          Some("something to correlate")
        )
        assertEquals(record.getSeverityText, "WARN")
        // The logger's name lets a backend tell this project's lines from
        // ember's.
        assertEquals(record.getInstrumentationScopeInfo.getName, "fh.test")
      }
    }
  }

  test("a record written outside any span is still exported, with no ids") {
    // Correlation is a bonus: dropping untraced lines would lose the boot and
    // shutdown lines that explain a failure to start.
    collecting.use { (otel, records) =>
      for {
        factory <- Logging.factory(otel)
        _ <- factory.getLoggerFromName("fh.test").info("no span here")
        emitted = records.getFinishedLogRecordItems.asScala.toList
      } yield {
        assertEquals(emitted.size, 1)
        assert(!emitted.head.getSpanContext.isValid)
      }
    }
  }

  test("the console leg still gets the line, with the ids in its context") {
    // The add-on's Log tab is plain text, so the ids must be in the line.
    collecting.use { (otel, records) =>
      val console = StructuredTestingLogger.impl[IO]()
      for {
        tracer <- otel.tracerProvider.get("test")
        log = Logging.logger("fh.test", console, tracer, otel)
        _ <- tracer.span("under-test").surround(log.info("both legs"))
        logged <- console.logged
        emitted = records.getFinishedLogRecordItems.asScala.toList
      } yield {
        assertEquals(emitted.size, 1)
        assertEquals(logged.size, 1)
        val ctx = logged.head.ctx
        assertEquals(
          ctx.get("trace_id"),
          Some(emitted.head.getSpanContext.getTraceId)
        )
        assertEquals(
          ctx.get("span_id"),
          Some(emitted.head.getSpanContext.getSpanId)
        )
      }
    }
  }

  test("with no endpoint configured, nothing is recorded at all") {
    // A no-op provider must report itself disabled, so the message is never
    // built.
    val log = Logging.logger(
      "fh.test",
      StructuredTestingLogger.impl[IO](),
      Tracer.noop[IO],
      Telemetry.Otel.noop
    )
    for {
      logs <- Telemetry
        .resource(None)
        .use(_.loggerProvider.logger("fh.test").get)
      on <- logs.meta.isEnabled(None, None)
      _ <- log.info("goes to the console and nowhere else")
    } yield assert(!on, "a no-op logger provider reported itself enabled")
  }
}
