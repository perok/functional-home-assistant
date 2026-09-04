package fh.view.runtime

import cats.effect.IO
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.otel4s.trace.Tracer

/** The link between the two halves of #75: every log line written inside a
  * span carries that span's ids.
  *
  * Without it, traces and logs are two accounts of the same page open with
  * nothing joining them — you can see that a walk took 900 ms, and separately
  * that something warned, and no way to tell whether they were the same
  * request. With it, the trace id is on the console line the add-on's Log tab
  * shows, so the slow trace and the line explaining it find each other.
  *
  * `trace_id` / `span_id` are the conventional key names, which is what lets a
  * log backend correlate without being told the mapping.
  *
  * Why a wrapper rather than log4cats' own `withContext`: that takes a FIXED
  * map, and `withModifiedContext` a pure function of one — but the span is
  * neither. It has to be read from the tracer per call, in `IO`, because which
  * span is current is a property of the fiber doing the logging.
  *
  * Why not SLF4J's MDC, which logback would pick up with no wrapper at all:
  * MDC is thread-local, and a cats-effect fiber moves between threads freely.
  * A value put there before an async boundary is on the wrong thread after it,
  * which fails in the direction that is worst — silently, and usually with
  * SOMEBODY's trace id rather than none.
  */
final class TracedLogger(
    underlying: SelfAwareStructuredLogger[IO],
    tracer: Tracer[IO]
) extends SelfAwareStructuredLogger[IO] {

  /** The span's ids, merged UNDER the caller's own context — an explicit key
    * wins, so this can never quietly overwrite something a call site meant.
    */
  private def traced(ctx: Map[String, String]): IO[Map[String, String]] =
    tracer.currentSpanContext.map {
      case Some(span) if span.isValid =>
        Map("trace_id" -> span.traceIdHex, "span_id" -> span.spanIdHex) ++ ctx
      case _ => ctx
    }

  // Every arity funnels through the structured form, so there is one place
  // where the span is read and one definition of precedence.
  def trace(message: => String): IO[Unit] = trace(Map.empty[String, String])(message)
  def debug(message: => String): IO[Unit] = debug(Map.empty[String, String])(message)
  def info(message: => String): IO[Unit] = info(Map.empty[String, String])(message)
  def warn(message: => String): IO[Unit] = warn(Map.empty[String, String])(message)
  def error(message: => String): IO[Unit] = error(Map.empty[String, String])(message)

  def trace(t: Throwable)(message: => String): IO[Unit] =
    trace(Map.empty[String, String], t)(message)
  def debug(t: Throwable)(message: => String): IO[Unit] =
    debug(Map.empty[String, String], t)(message)
  def info(t: Throwable)(message: => String): IO[Unit] =
    info(Map.empty[String, String], t)(message)
  def warn(t: Throwable)(message: => String): IO[Unit] =
    warn(Map.empty[String, String], t)(message)
  def error(t: Throwable)(message: => String): IO[Unit] =
    error(Map.empty[String, String], t)(message)

  def trace(ctx: Map[String, String])(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.trace(c)(message))
  def debug(ctx: Map[String, String])(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.debug(c)(message))
  def info(ctx: Map[String, String])(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.info(c)(message))
  def warn(ctx: Map[String, String])(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.warn(c)(message))
  def error(ctx: Map[String, String])(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.error(c)(message))

  def trace(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.trace(c, t)(message))
  def debug(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.debug(c, t)(message))
  def info(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.info(c, t)(message))
  def warn(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.warn(c, t)(message))
  def error(ctx: Map[String, String], t: Throwable)(message: => String): IO[Unit] =
    traced(ctx).flatMap(c => underlying.error(c, t)(message))

  // Delegated unchanged: whether a level is on is the backend's answer, and
  // has nothing to do with which span is current.
  def isTraceEnabled: IO[Boolean] = underlying.isTraceEnabled
  def isDebugEnabled: IO[Boolean] = underlying.isDebugEnabled
  def isInfoEnabled: IO[Boolean] = underlying.isInfoEnabled
  def isWarnEnabled: IO[Boolean] = underlying.isWarnEnabled
  def isErrorEnabled: IO[Boolean] = underlying.isErrorEnabled
}
