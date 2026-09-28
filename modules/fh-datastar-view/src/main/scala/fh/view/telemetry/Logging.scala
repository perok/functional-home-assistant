package fh.view.telemetry

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.otel4s.logs.{Logger as OtelLogger, Severity}
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{AnyValue, Attribute}

/** The log half of #75: console and collector, both carrying the current span.
  * A factory, so http4s' own lines join and each class name is its own
  * instrumentation scope.
  *
  * Not `opentelemetry-logback-appender`: it reads the thread-local
  * `Context.current()`, which otel4s' `IOLocal` never populates, so records
  * arrive with an empty trace context. Bridging it, like SLF4J's MDC, breaks
  * when a fiber changes threads, usually with somebody else's trace id.
  */
object Logging {

  /** Not `Severity` itself: nothing here uses its `info2`-style gradations, and
    * matching on it needs an equality this profile does not grant.
    */
  private enum Level(val severity: Severity, val text: String) {
    case Trace extends Level(Severity.trace, "TRACE")
    case Debug extends Level(Severity.debug, "DEBUG")
    case Info extends Level(Severity.info, "INFO")
    case Warn extends Level(Severity.warn, "WARN")
    case Error extends Level(Severity.error, "ERROR")
  }

  /** One tracer for every logger: it is only asked which span is current, which
    * belongs to the fiber, not the scope.
    */
  def factory(otel: Telemetry.Otel): IO[LoggerFactory[IO]] =
    otel.tracerProvider.get("fh.view.telemetry.Logging").map { tracer =>
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

  val console: LoggerFactory[IO] = Slf4jFactory.create[IO]

  /** For the suite: the 25 delegating methods are where a copy-paste slip
    * lands.
    */
  private[telemetry] def logger(
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

    /** Under the caller's context, so an explicit key wins. The add-on's Log
      * tab is plain text, so these ids are the console's only correlation.
      */
    private def traced(ctx: Map[String, String]): IO[Map[String, String]] =
      tracer.currentSpanContext.map {
        case Some(span) if span.isValid =>
          Map("trace_id" -> span.traceIdHex, "span_id" -> span.spanIdHex) ++ ctx
        case _ => ctx
      }

    /** The message is forced once, behind the enabled check: a by-name handed
      * to two writers would be evaluated twice.
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

    // Per line: `getLoggerFromName` is not in `IO`, and the lookup is a map read.
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

    // The console's answer only: a caller guarding on it skips a line a DEBUG
    // collector would have taken.
    def isTraceEnabled: IO[Boolean] = out.isTraceEnabled
    def isDebugEnabled: IO[Boolean] = out.isDebugEnabled
    def isInfoEnabled: IO[Boolean] = out.isInfoEnabled
    def isWarnEnabled: IO[Boolean] = out.isWarnEnabled
    def isErrorEnabled: IO[Boolean] = out.isErrorEnabled
  }
}
