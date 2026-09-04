package fh.view.runtime

import cats.effect.{IO, Resource}
import cats.effect.std.Env
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.TracerProvider

/** Telemetry for the add-on (#75), and the switch that keeps it free when
  * nobody is collecting.
  *
  * The motivating question was "opening a dashboard feels sluggish" on a
  * Raspberry Pi, which the render benchmark could not answer: it prices the
  * render at 2-3 ms, and the phases around it — reading the store, minting the
  * session, the walk that writes the document — were not instrumented at all.
  *
  * **Nothing is produced unless an OTLP endpoint is configured.** With none,
  * [[resource]] answers the no-op providers and the OpenTelemetry SDK is never
  * constructed — so on the overwhelmingly common install the cost is a no-op
  * call per instrument and no classes loaded. That is deliberate on this
  * hardware: the add-on's memory footprint is the thing #75 was opened to
  * understand, and instrumentation that inflated it by default would be
  * measuring the observer.
  *
  * PROVIDERS rather than a `Tracer`/`Meter`, because that is what the http4s
  * middleware takes: it names its own instrumentation scope, which is how its
  * spans and metrics carry the conventional `http.*` names instead of ones this
  * project invented.
  */
object Telemetry {

  /** What the wiring needs, in one value. Both are no-op together or real
    * together — there is one endpoint and one SDK, so splitting them would only
    * invite a half-configured state that cannot occur.
    */
  final case class Otel(
      tracerProvider: TracerProvider[IO],
      meterProvider: MeterProvider[IO]
  )

  /** The endpoint's env var, which is OpenTelemetry's own standard name rather
    * than an `FH_` one — `run.sh` sets it from the `otlp_endpoint` option, and
    * a standalone `docker run` can set it directly, the way every other OTLP
    * producer is configured.
    */
  val EndpointVar = "OTEL_EXPORTER_OTLP_ENDPOINT"

  def resource: Resource[IO, Otel] =
    Resource.eval(Env[IO].get(EndpointVar)).flatMap(resource)

  /** The decision itself, with the environment lifted out.
    *
    * Split so both arms can be pinned by a test: reading the variable inside
    * would make "is telemetry off by default" depend on the machine the suite
    * runs on, and the default arm is exactly the one a developer with a
    * collector configured would stop exercising.
    */
  private[runtime] def resource(endpoint: Option[String]): Resource[IO, Otel] =
    endpoint.map(_.trim).filter(_.nonEmpty) match {
      case Some(_) =>
        // `autoConfigured` reads the standard `OTEL_*` variables, so protocol,
        // headers, sampling and resource attributes are all configurable
        // without this file growing an option for each of them.
        OtelJava
          .autoConfigured[IO]()
          .map(otel => Otel(otel.tracerProvider, otel.meterProvider))
      case None =>
        Resource.pure(Otel(TracerProvider.noop, MeterProvider.noop))
    }
}
