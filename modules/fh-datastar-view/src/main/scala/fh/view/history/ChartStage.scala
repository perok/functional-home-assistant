package fh.view.history

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fh.view.FHError
import fh.view.query.{QueryIdentity, QueryRequest}
import io.circe.Json

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
    entries: Ref[IO, Map[ChartStage.Key, ChartStage.Entry]]
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
    Deferred[IO, Either[Throwable, String]].flatMap { mine =>
      val key = (question, style)
      entries
        .modify { current =>
          current.get(key) match {
            case Some(e) if e.version === version => (current, e.slot.get)
            case _                                =>
              (
                current.updated(key, ChartStage.Entry(version, mine)),
                compute(style, data).attempt.flatTap(r =>
                  // Not cached on failure, so the next asker retries.
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

  def keys: IO[Set[ChartStage.Key]] = entries.get.map(_.keySet)
}

object ChartStage {

  /** Who asked, and what: identity is part of it because what a provider
    * answers may depend on who reads.
    */
  type Question = (QueryIdentity, QueryRequest)

  type Key = (Question, ChartStyle)

  private case class Entry(
      version: Long,
      slot: Deferred[IO, Either[Throwable, String]]
  )

  def create(renderer: IO[ChartDraw]): IO[ChartStage] =
    Ref[IO]
      .of(Map.empty[Key, Entry])
      .map(new ChartStage(renderer, _))
}
