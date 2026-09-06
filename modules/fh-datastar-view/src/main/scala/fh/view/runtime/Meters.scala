package fh.view.runtime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.{Counter, Histogram, MeterProvider}

/** The instruments this project defines, as one value.
  *
  * The http4s middleware already gives the conventional `http.server.*` /
  * `http.client.*`, which answer "was a request slow". These answer the
  * questions a duration cannot: how big the page that took that long was, how
  * many sessions were live while it happened, how fast the house was moving.
  *
  * ==Why these are metrics and not just span attributes==
  *
  * `fh.page.nodes` and `fh.ha.entities` are ALSO attributes on the spans that
  * carry them, and that is not duplication. A span is sampled — the whole point
  * of `OTEL_TRACES_SAMPLER` on a busy feed — so the attribute answers "what did
  * THIS page open do" and the instrument answers "what do page opens do", which
  * sampling would otherwise make a guess.
  *
  * ==Why so few==
  *
  * Each of these reads a number the code had already computed. Nothing here
  * measures anything on its own account, because the thing #75 is trying to
  * find out is what the add-on costs on a Pi — and an instrument that needs its
  * own measurement is measuring the observer.
  */
final case class Meters(
    /** Nodes painted by one page open — the number the walk's duration has to
      * be read against, since 200 nodes in 40 ms and 20 nodes in 40 ms are
      * different findings.
      */
    pageNodes: Histogram[IO, Long],
    /** Entities in one applied batch, which is one Home Assistant frame. The
      * rate of this IS how fast the house is moving.
      */
    haEntities: Counter[IO, Long],
    /** How long it takes to fetch the dump and evaluate every dashboard. Boot
      * pays this, and so does every registry-driven refresh — which is why it
      * is worth a series and not just a boot log line.
      */
    evalDuration: Histogram[IO, Double]
)

object Meters {

  /** What an unconfigured install, a test and a standalone construction get. */
  val noop: Meters =
    Meters(Histogram.noop, Counter.noop, Histogram.noop)

  /** Sessions currently registered, OBSERVED off the registry rather than
    * counted into a separate total.
    *
    * The map is already the truth. A counter incremented in `register` and
    * decremented in `deregisterIf` would be a second copy of it, and the two
    * would disagree the first time a `conn` is re-registered (a re-minted
    * session does exactly that) — an off-by-one that never surfaces except as a
    * metric slowly drifting from reality.
    */
  def observeSessions(
      provider: MeterProvider[IO],
      sessions: Sessions
  ): Resource[IO, Unit] =
    provider
      .get("fh.view.runtime")
      .toResource
      .flatMap(
        _.observableUpDownCounter[Long]("fh.sessions.live")
          .withDescription("Dashboard sessions currently registered")
          .withUnit("{session}")
          .createWithCallback(cb =>
            sessions.all.flatMap(all => cb.record(all.size.toLong))
          )
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
          .create,
        meter
          .histogram[Double]("fh.dashboard.eval.duration")
          .withDescription("Dump fetch plus evaluation of every dashboard")
          .withUnit("s")
          .create
      ).mapN(Meters.apply)
    }
}
