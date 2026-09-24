package fh.view.history

import cats.effect.IO
import cats.syntax.all.*
import fh.view.FHError
import fh.view.query.{QueryIdentity, QueryRequest}
import io.circe.Json

import scala.concurrent.duration.FiniteDuration

/** How a series becomes bytes — a function rather than [[ChartRenderer]], so a
  * cache test needs no JavaScript engine.
  */
type ChartDraw = (Series, ChartStyle) => IO[String]

/** The built-in drawing stage: a provider's series in, SVG out.
  *
  * One drawing per (question, style), replaced in place when the version moves,
  * so it needs no expiry: unlike a series, a drawing is a pure function of its
  * answer. The question is in the key because the version alone does not name
  * an answer — two sensors over one window share a bucket.
  */
final class ChartStage private (
    renderer: IO[ChartDraw],
    cache: SharedCache[(ChartStage.Key, Long), String]
) {

  /** Concurrent callers of a cold key wait on one drawing. Every style field is
    * in the key: a 600px drawing served for a 300px ask is a squashed axis
    * rather than a visible error.
    */
  def draw(
      question: ChartStage.Question,
      style: ChartStyle,
      version: Long,
      data: Json
  ): IO[String] =
    cache.get(((question, style), version))(compute(style, data))

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

  def keys: IO[Set[ChartStage.Key]] = cache.keys.map(_.map(_._1))
}

object ChartStage {

  /** Who asked, and what: identity is part of it because what a provider
    * answers may depend on who reads.
    */
  type Question = (QueryIdentity, QueryRequest)

  type Key = (Question, ChartStyle)

  def create(
      renderer: IO[ChartDraw],
      failureTtl: FiniteDuration = SharedCache.FailureTtl,
      onFailure: SharedCache.OnFailure[(Key, Long)] = SharedCache.ignore
  ): IO[ChartStage] =
    SharedCache
      .create[(Key, Long), String](
        (added, other) => other._1 != added._1,
        failureTtl,
        onFailure
      )
      .map(new ChartStage(renderer, _))
}
