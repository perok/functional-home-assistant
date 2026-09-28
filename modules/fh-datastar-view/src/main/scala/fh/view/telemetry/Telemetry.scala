package fh.view.telemetry

import cats.effect.{IO, Resource}
import cats.effect.std.Env
import org.typelevel.otel4s.logs.LoggerProvider
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.TracerProvider

/** Telemetry (#75). Without an OTLP endpoint the SDK is never constructed: the
  * add-on's memory on a Pi is what #75 set out to measure, so the default must
  * not inflate it.
  *
  * Providers, not a `Tracer`/`Meter`, because the http4s middleware names its
  * own scope and so emits the conventional `http.*` names.
  */
object Telemetry {

  final case class Otel(
      tracerProvider: TracerProvider[IO],
      meterProvider: MeterProvider[IO],
      loggerProvider: LoggerProvider[IO, Context]
  )

  object Otel {

    val noop: Otel =
      Otel(TracerProvider.noop, MeterProvider.noop, LoggerProvider.noop)
  }

  /** OpenTelemetry's own name; `run.sh` sets it from `otlp_endpoint`. */
  val EndpointVar = "OTEL_EXPORTER_OTLP_ENDPOINT"

  def resource: Resource[IO, Otel] =
    Resource.eval(Env[IO].get(EndpointVar)).flatMap(resource)

  // Environment lifted out, so the off-by-default arm is testable anywhere.
  private[telemetry] def resource(
      endpoint: Option[String]
  ): Resource[IO, Otel] =
    endpoint.map(_.trim).filter(_.nonEmpty) match {
      case Some(_) =>
        // Reads the standard `OTEL_*` variables; a bad one fails boot, which is
        // wanted once an endpoint was asked for.
        OtelJava
          .autoConfigured[IO]()
          .map(otel =>
            Otel(otel.tracerProvider, otel.meterProvider, otel.loggerProvider)
          )
      case None => Resource.pure(Otel.noop)
    }
}
