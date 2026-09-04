package fh.view.runtime

import cats.effect.{IO, Resource}
import cats.effect.std.Env
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

/** Tracing for the page-open path (#75), and the switch that keeps it free
  * when nobody is collecting.
  *
  * The motivating question was "opening a dashboard feels sluggish" on a
  * Raspberry Pi, which the render benchmark could not answer: it prices the
  * render at 2-3 ms, and the phases around it — reading the store, minting the
  * session, the walk that writes the document — were not instrumented at all.
  * Spans on those are the first thing worth having.
  *
  * **Nothing is exported unless an OTLP endpoint is configured.** With none,
  * [[tracerFor]] answers `Tracer.Implicits.noop` and the OpenTelemetry SDK is
  * never constructed — so on the overwhelmingly common install the cost is a
  * no-op call per span site and no classes loaded. That is deliberate on this
  * hardware: the add-on's memory footprint is the thing #75 was opened to
  * understand, and instrumentation that inflates it by default would be
  * measuring the observer.
  */
object Telemetry {

  /** The endpoint's env var, which is OpenTelemetry's own standard name rather
    * than an `FH_` one — `run.sh` sets it from the `otlp_endpoint` option, and
    * a standalone `docker run` can set it directly, the way every other OTLP
    * producer is configured.
    */
  val EndpointVar = "OTEL_EXPORTER_OTLP_ENDPOINT"

  /** Named so a collector shows this add-on rather than an anonymous JVM. */
  val ServiceName = "fh-dashboard"

  /** A tracer, and the SDK behind it only when there is somewhere to send to.
    *
    * The endpoint is read here rather than taken as a parameter because the
    * decision is "is anyone collecting", which is an environment fact — and
    * reading it in one place keeps the no-op arm from being reachable by
    * accident from a caller that forgot to check.
    */
  def tracerFor(instrument: String): Resource[IO, Tracer[IO]] =
    Resource.eval(Env[IO].get(EndpointVar)).flatMap(tracerFor(instrument, _))

  /** The decision itself, with the environment lifted out.
    *
    * Split so the two arms can be pinned by a test: reading the variable
    * inside would make "is tracing off by default" depend on the machine the
    * suite runs on, and the arm that matters is exactly the one a developer
    * with a collector configured would stop exercising.
    */
  private[runtime] def tracerFor(
      instrument: String,
      endpoint: Option[String]
  ): Resource[IO, Tracer[IO]] =
    endpoint.map(_.trim).filter(_.nonEmpty) match {
      case Some(_) => configured.evalMap(_.get(instrument))
      case None    => Resource.pure(Tracer.Implicits.noop[IO])
    }

  /** The autoconfigured SDK. `OtelJava.autoConfigured` reads the standard
    * `OTEL_*` variables, so protocol, headers, sampling and resource
    * attributes are all configurable without this file growing an option for
    * each of them.
    */
  private def configured: Resource[IO, TracerProvider[IO]] =
    OtelJava.autoConfigured[IO]().map(_.tracerProvider)
}
