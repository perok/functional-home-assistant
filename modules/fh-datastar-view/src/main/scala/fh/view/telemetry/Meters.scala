package fh.view.telemetry

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.{Counter, Histogram, MeterProvider}

/** What a duration cannot say: how big the slow page was, how fast the house
  * moved.
  *
  * Also span attributes, not duplication: spans are sampled, and span-metrics
  * would turn `fh.nodes=137` into an unbounded label. Each reads a number the
  * code already computed, so the observer measures nothing of its own.
  */
final case class Meters(
    /** Read the walk's duration against it: 200 nodes in 40 ms is not 20. */
    pageNodes: Histogram[IO, Long],
    /** Per applied batch (one HA frame); its rate is how fast the house moves.
      */
    haEntities: Counter[IO, Long]
)

object Meters {

  val noop: Meters =
    Meters(Histogram.noop, Counter.noop)

  /** Observed off the registry, not counted: a re-minted `conn` re-registers,
    * and a second tally would drift.
    */
  def observeSessions(
      provider: MeterProvider[IO],
      live: IO[Long]
  ): Resource[IO, Unit] =
    provider
      .get("fh.view.runtime")
      .toResource
      .flatMap(
        _.observableUpDownCounter[Long]("fh.sessions.live")
          .withDescription("Dashboard sessions currently registered")
          .withUnit("{session}")
          .createWithCallback(cb => live.flatMap(cb.record(_)))
      )
      .void

  def create(provider: MeterProvider[IO]): IO[Meters] =
    provider.get("fh.view.runtime").flatMap { meter =>
      (
        meter
          .histogram[Long]("fh.page.nodes")
          .withDescription("Nodes painted by one page open")
          .withUnit("{node}")
          .create,
        meter
          .counter[Long]("fh.ha.entities")
          .withDescription("Entity states applied from the Home Assistant feed")
          .withUnit("{entity}")
          .create
      ).mapN(Meters.apply)
    }
}
