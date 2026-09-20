package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fh.view.FHError
import io.circe.Json

/** How a series becomes bytes.
  *
  * Narrow on purpose, and the reason outlived its first home: taking the whole
  * [[ChartRenderer]] would put a JavaScript engine in every test that only
  * wants to know what the cache does — the same reason [[SeriesProvider]] takes
  * a [[SeriesSource]] rather than the HA client.
  */
type ChartDraw = (Series, ChartStyle) => IO[String]

/** The built-in drawing stage: a provider's series in, SVG out.
  *
  * It sits where the FETCH already happens — before the walk — because drawing
  * is `IO` (a Graal context) and a render is a synchronous string build. What
  * moved is only whose job it is: the provider answers data, and what that data
  * becomes is the slot's `transform`.
  *
  * '''One drawing per (stage, version), and the cache needs no expiry.''' That
  * is the half of [[BucketCache]] this does NOT need, and the reason is worth
  * stating because the two caches now look similar and are not. A SERIES has a
  * shelf life — it stops being current when its bucket rolls, which is a fact
  * about recorder data and the thing that decides when a version moves. A
  * DRAWING has none: it is a deterministic function of an answer, so an entry
  * for a superseded version is dead the moment the version moves, and
  * replacing in place is the whole of eviction.
  */
final class ChartStage private (
    renderer: IO[ChartDraw],
    entries: Ref[IO, Map[String, ChartStage.Entry]]
) {

  /** Draw `data` at `style`, or serve the drawing already made for this
    * version.
    *
    * The `Deferred` is the same trick [[BucketCache]] uses and for the same
    * reason: concurrent page opens are the case that matters, so the second
    * caller of a cold key waits for the first rather than starting a second
    * drawing — which here would also mean a second turn through the renderer's
    * process-wide mutex.
    */
  def draw(style: ChartStyle, version: Long, data: Json): IO[String] =
    Deferred[IO, Either[Throwable, String]].flatMap { mine =>
      val key = ChartStage.keyOf(style)
      entries
        .modify { current =>
          current.get(key) match {
            case Some(e) if e.version === version => (current, e.slot.get)
            case _                                =>
              (
                current.updated(key, ChartStage.Entry(version, mine)),
                compute(style, data).attempt.flatTap(r =>
                  // A failure is not cached: it is removed on completion so the
                  // next asker retries, the same rule the series cache keeps.
                  // Otherwise one bad draw would blank a chart until its bucket
                  // rolled.
                  mine.complete(r) *>
                    entries.update(_ - key).whenA(r.isLeft)
                )
              )
          }
        }
        .flatten
        .rethrow
    }

  private def compute(style: ChartStyle, data: Json): IO[String] =
    data
      .as[Series]
      .leftMap(e =>
        FHError.internal(
          s"the chart stage cannot read its provider's answer: ${e.getMessage}"
        )
      )
      .liftTo[IO]
      .flatMap(s => renderer.flatMap(_(s, style)))

  /** What is currently drawn, for tests and diagnostics. */
  def keys: IO[Set[String]] = entries.get.map(_.keySet)
}

object ChartStage {

  private case class Entry(
      version: Long,
      slot: Deferred[IO, Either[Throwable, String]]
  )

  /** A style's identity. Every field, because every one of them changes the
    * picture — serving a 600px drawing for a 300px ask is a squashed axis
    * rather than a visible error.
    */
  private def keyOf(s: ChartStyle): String =
    s"${s.width}x${s.height}|${s.line}|${s.fill.getOrElse("")}|${s.unit.getOrElse("")}"

  def create(renderer: IO[ChartDraw]): IO[ChartStage] =
    Ref[IO].of(Map.empty[String, Entry]).map(new ChartStage(renderer, _))
}
