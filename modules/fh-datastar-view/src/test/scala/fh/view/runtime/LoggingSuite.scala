package fh.view.runtime

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

/** The claim [[Logging]] rests on, and the only place anything checks it: a log
  * record written inside a span leaves carrying THAT span's ids.
  *
  * Worth a real SDK rather than a stub, because the failure mode this guards
  * against is precisely a context that looks present and is empty — which is
  * what the logback appender would have produced, and what a stub asserting on
  * our own `withContext` call would have missed.
  */
class LoggingSuite extends munit.CatsEffectSuite {

  /** One SDK writing spans and records to memory, wrapped as the `Telemetry`
    * value the runtime is given. `SimpleLogRecordProcessor` rather than a batch
    * one so a record is exported by the time `emit` returns.
    */
  private def collecting: Resource[IO, (Telemetry.Otel, InMemoryLogRecordExporter)] =
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
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(records))
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
        // The span's own ids, captured inside it, are what the record has to
        // match — reading them from the record alone would pass on any two
        // equal-looking hex strings.
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
        // The scope is the logger's NAME, which is what lets a backend tell
        // this project's lines from ember's without being told the mapping.
        assertEquals(record.getInstrumentationScopeInfo.getName, "fh.test")
      }
    }
  }

  test("a record written outside any span is still exported, with no ids") {
    // The other half: correlation is a bonus, never a precondition. A wrapper
    // that dropped or refused untraced lines would lose exactly the boot and
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
    // Both legs, from one call. The add-on's Log tab is plain text, so the ids
    // have to be IN the line there — the record's own context is no help to a
    // reader looking at stdout.
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
    // The promise `Telemetry` makes, on the leg it did not used to have: a
    // no-op logger provider must not merely drop records, it must report
    // itself disabled so the message is never even built.
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
