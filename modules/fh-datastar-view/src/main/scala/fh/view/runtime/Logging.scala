package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.otel4s.logs.{Logger as OtelLogger, Severity}
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute}

/** The log half of #75: one `LoggerFactory` whose lines go to the console AND
  * to the collector, both carrying the span they were written inside.
  *
  * Without the span, traces and logs are two accounts of the same page open
  * with nothing joining them — you can see that a walk took 900 ms, and
  * separately that something warned, and no way to tell whether they were the
  * same request.
  *
  * A FACTORY rather than a logger, for two reasons. It is what http4s takes, so
  * ember's own lines join the same stream; and a logger built per class name is
  * what an OpenTelemetry instrumentation scope is, so the collector can tell
  * `fh.view.runtime.Server` from `org.http4s.ember.server` without being told
  * the mapping.
  *
  * ==Why not the logback appender==
  *
  * `opentelemetry-logback-appender` is the obvious way to ship these records
  * and it does not work here: it reads OpenTelemetry Java's
  * `Context.current()`, which is thread-local, and otel4s keeps the current
  * span in an `IOLocal` that never populates it. Records would arrive with an
  * EMPTY trace context — correlation by regex over a copied attribute rather
  * than by the field a backend already knows. Bridging means a
  * `makeCurrent()`/`close()` pair around every call, which is the same
  * thread-local hazard that rules out SLF4J's MDC: a cats-effect fiber moves
  * between threads freely, and a value put there before an async boundary is on
  * the wrong thread after it — failing silently, and usually with SOMEBODY's
  * trace id rather than none.
  *
  * otel4s' own `LoggerProvider` has no such problem: `currentContext` is read
  * per call, in `IO`, from the same `IOLocal` the tracer writes.
  */
object Logging {

  /** Levels this project logs at, paired with the OpenTelemetry severity and
    * the text a backend shows.
    *
    * An enum rather than passing `Severity` around because `Severity`'s
    * companion offers four gradations per level (`info`, `info2`, …) that
    * nothing here distinguishes, and because matching on it to pick the console
    * method would need an equality this profile does not grant.
    */
  private enum Level(val severity: Severity, val text: String) {
    case Trace extends Level(Severity.trace, "TRACE")
    case Debug extends Level(Severity.debug, "DEBUG")
    case Info extends Level(Severity.info, "INFO")
    case Warn extends Level(Severity.warn, "WARN")
    case Error extends Level(Severity.error, "ERROR")
  }

  /** The factory the whole runtime logs through.
    *
    * ONE tracer for every logger it hands out, which is sound because the
    * tracer is only ever asked which span is current — a property of the fiber,
    * not of the instrumentation scope asking.
    */
  def factory(otel: Telemetry.Otel): IO[LoggerFactory[IO]] =
    otel.tracerProvider.get("fh.view.runtime.Logging").map { tracer =>
      new LoggerFactory[IO] {
        private val console = Slf4jFactory.create[IO]

        def getLoggerFromName(name: String): SelfAwareStructuredLogger[IO] =
          new FanOut(
            name,
            console.getLoggerFromName(name),
            tracer,
            otel
          )

        def fromName(name: String): IO[SelfAwareStructuredLogger[IO]] =
          IO(getLoggerFromName(name))
      }
    }

  /** The console-only factory: what a test and a standalone construction get,
    * and what the add-on itself runs on unless an OTLP endpoint is configured.
    */
  val console: LoggerFactory[IO] = Slf4jFactory.create[IO]

  /** One logger, with its console leg supplied — the seam a test writes
    * against, since the 25 delegating methods below are exactly where a
    * copy-paste slip lands and nothing else would catch it.
    */
  private[runtime] def logger(
      name: String,
      out: SelfAwareStructuredLogger[IO],
      tracer: Tracer[IO],
      otel: Telemetry.Otel
  ): SelfAwareStructuredLogger[IO] = new FanOut(name, out, tracer, otel)

  private final class FanOut(
      name: String,
      out: SelfAwareStructuredLogger[IO],
      tracer: Tracer[IO],
      otel: Telemetry.Otel
  ) extends SelfAwareStructuredLogger[IO] {

    /** The span's ids, merged UNDER the caller's own context — an explicit key
      * wins, so this can never quietly overwrite something a call site meant.
      *
      * On the console leg these are the whole correlation story: the add-on's
      * Log tab is plain text, and a trace id in it is what lets a reader take a
      * line to the trace it belongs to. The collector leg does not need them —
      * it gets the context itself — but they cost nothing there.
      */
    private def traced(ctx: Map[String, String]): IO[Map[String, String]] =
      tracer.currentSpanContext.map {
        case Some(span) if span.isValid =>
          Map("trace_id" -> span.traceIdHex, "span_id" -> span.spanIdHex) ++ ctx
        case _ => ctx
      }

    /** Both legs of one line.
      *
      * The message is forced ONCE, behind the enabled check, and handed on as a
      * strict `String`: a by-name passed to two writers is evaluated twice, and
      * interpolations in log statements are exactly where that is expensive.
      */
    private def emit(
        level: Level,
        ctx: Map[String, String],
        t: Option[Throwable]
    )(message: => String): IO[Unit] =
      otelLogger.flatMap { logs =>
        (enabled(level), logs.meta.isEnabled(Some(level.severity), None))
          .flatMapN { (toConsole, toCollector) =>
            if toConsole || toCollector then {
              val msg = message
              traced(ctx).flatMap { c =>
                IO.whenA(toConsole)(write(level, c, t, msg)) *>
                  IO.whenA(toCollector)(record(logs, level, c, t, msg))
              }
            } else IO.unit
          }
      }

    /** Resolved per line rather than held: the provider's own lookup is a map
      * read, and holding one would mean resolving it in `IO` at a point where
      * `getLoggerFromName` has none to offer.
      */
    private def otelLogger: IO[OtelLogger[IO, Context]] =
      otel.loggerProvider.logger(name).get

    private def write(
        level: Level,
        ctx: Map[String, String],
        t: Option[Throwable],
        msg: String
    ): IO[Unit] =
      (level, t) match {
        case (Level.Trace, None)    => out.trace(ctx)(msg)
        case (Level.Debug, None)    => out.debug(ctx)(msg)
        case (Level.Info, None)     => out.info(ctx)(msg)
        case (Level.Warn, None)     => out.warn(ctx)(msg)
        case (Level.Error, None)    => out.error(ctx)(msg)
        case (Level.Trace, Some(e)) => out.trace(ctx, e)(msg)
        case (Level.Debug, Some(e)) => out.debug(ctx, e)(msg)
        case (Level.Info, Some(e))  => out.info(ctx, e)(msg)
        case (Level.Warn, Some(e))  => out.warn(ctx, e)(msg)
        case (Level.Error, Some(e)) => out.error(ctx, e)(msg)
      }

    private def record(
        logs: OtelLogger[IO, Context],
        level: Level,
        ctx: Map[String, String],
        t: Option[Throwable],
        msg: String
    ): IO[Unit] =
      logs.currentContext.flatMap { context =>
        val attrs: Seq[Attribute[?]] =
          ctx.toSeq.map((k, v) => Attribute(k, v))
        val builder = logs.logRecordBuilder
          .withContext(context)
          .withSeverity(level.severity)
          .withSeverityText(level.text)
          .withBody(AnyValue.string(msg))
          .addAttributes(attrs*)
        t.fold(builder)(builder.withException).emit
      }

    private def enabled(level: Level): IO[Boolean] =
      level match {
        case Level.Trace => out.isTraceEnabled
        case Level.Debug => out.isDebugEnabled
        case Level.Info  => out.isInfoEnabled
        case Level.Warn  => out.isWarnEnabled
        case Level.Error => out.isErrorEnabled
      }

    // Every arity funnels through `emit`, so there is one place where the span
    // is read, one definition of precedence, and one enabled check.
    def trace(message: => String): IO[Unit] =
      emit(Level.Trace, Map.empty, None)(message)
    def debug(message: => String): IO[Unit] =
      emit(Level.Debug, Map.empty, None)(message)
    def info(message: => String): IO[Unit] =
      emit(Level.Info, Map.empty, None)(message)
    def warn(message: => String): IO[Unit] =
      emit(Level.Warn, Map.empty, None)(message)
    def error(message: => String): IO[Unit] =
      emit(Level.Error, Map.empty, None)(message)

    def trace(t: Throwable)(message: => String): IO[Unit] =
      emit(Level.Trace, Map.empty, Some(t))(message)
    def debug(t: Throwable)(message: => String): IO[Unit] =
      emit(Level.Debug, Map.empty, Some(t))(message)
    def info(t: Throwable)(message: => String): IO[Unit] =
      emit(Level.Info, Map.empty, Some(t))(message)
    def warn(t: Throwable)(message: => String): IO[Unit] =
      emit(Level.Warn, Map.empty, Some(t))(message)
    def error(t: Throwable)(message: => String): IO[Unit] =
      emit(Level.Error, Map.empty, Some(t))(message)

    def trace(ctx: Map[String, String])(message: => String): IO[Unit] =
      emit(Level.Trace, ctx, None)(message)
    def debug(ctx: Map[String, String])(message: => String): IO[Unit] =
      emit(Level.Debug, ctx, None)(message)
    def info(ctx: Map[String, String])(message: => String): IO[Unit] =
      emit(Level.Info, ctx, None)(message)
    def warn(ctx: Map[String, String])(message: => String): IO[Unit] =
      emit(Level.Warn, ctx, None)(message)
    def error(ctx: Map[String, String])(message: => String): IO[Unit] =
      emit(Level.Error, ctx, None)(message)

    def trace(ctx: Map[String, String], t: Throwable)(
        message: => String
    ): IO[Unit] = emit(Level.Trace, ctx, Some(t))(message)
    def debug(ctx: Map[String, String], t: Throwable)(
        message: => String
    ): IO[Unit] = emit(Level.Debug, ctx, Some(t))(message)
    def info(ctx: Map[String, String], t: Throwable)(
        message: => String
    ): IO[Unit] = emit(Level.Info, ctx, Some(t))(message)
    def warn(ctx: Map[String, String], t: Throwable)(
        message: => String
    ): IO[Unit] = emit(Level.Warn, ctx, Some(t))(message)
    def error(ctx: Map[String, String], t: Throwable)(
        message: => String
    ): IO[Unit] = emit(Level.Error, ctx, Some(t))(message)

    // Delegated unchanged: whether a level is on is the backend's answer, and
    // has nothing to do with which span is current. Note this reports the
    // CONSOLE's answer only — a collector configured for DEBUG still gets a
    // debug line that a caller guarded on this would have skipped, which is
    // the right way round.
    def isTraceEnabled: IO[Boolean] = out.isTraceEnabled
    def isDebugEnabled: IO[Boolean] = out.isDebugEnabled
    def isInfoEnabled: IO[Boolean] = out.isInfoEnabled
    def isWarnEnabled: IO[Boolean] = out.isWarnEnabled
    def isErrorEnabled: IO[Boolean] = out.isErrorEnabled
  }
}
