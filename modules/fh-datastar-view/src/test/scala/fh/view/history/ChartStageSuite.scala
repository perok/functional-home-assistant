package fh.view.history

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.FHError
import fh.view.query.{QueryIdentity, QueryRequest}
import io.circe.Json

/** What a DRAWING costs: keyed by question and every field of its style, and
  * shared by everyone reading one version.
  */
class ChartStageSuite extends munit.CatsEffectSuite {

  private def question(entity: String): ChartStage.Question =
    (QueryIdentity.Instance, QueryRequest.History(entity, Window.LastDay))

  private val q = question("sensor.t")

  private val data = Series.toJson(
    Series(Vector(Series.Point(java.time.Instant.EPOCH, 1.0)), 0)
  )

  private def fixture(
      draw: (Series, ChartStyle) => IO[String] = (_, s) =>
        IO.pure(s"<svg>${s.width}</svg>")
  ): IO[(ChartStage, Ref[IO, Int])] =
    for {
      draws <- Ref[IO].of(0)
      stage <- ChartStage.create(
        IO.pure((series, style) => draws.update(_ + 1) *> draw(series, style))
      )
    } yield (stage, draws)

  test("ten viewers of one version cost one drawing") {
    // The reason the JavaScript context is not on the hot path, and the reason
    // the stage caches at all: `QuerySnapshot.resolve` runs BEFORE the render
    // cache, so without this every page open would redraw.
    fixture().flatMap { case (stage, draws) =>
      List
        .fill(10)(stage.draw(q, ChartStyle(), 100L, data))
        .parSequence
        .flatMap { results =>
          draws.get.map { d =>
            assertEquals(results.distinct, List("<svg>600</svg>"))
            assertEquals(d, 1)
          }
        }
    }
  }

  test("two styles of one answer are two drawings") {
    // A tile and a popup showing the same sensor at different sizes. The FETCH
    // they share is not visible from here any more, which is the point of the
    // split — see `QueryRenderInputsSuite` for the property that spans both.
    fixture().flatMap { case (stage, draws) =>
      stage.draw(q, ChartStyle(width = 600), 100L, data) *>
        stage.draw(q, ChartStyle(width = 320), 100L, data) *>
        draws.get.map(assertEquals(_, 2))
    }
  }

  test("a moved version redraws, and needs no expiry rule to do it") {
    // The half of `BucketCache` this does NOT need. A series has a shelf life;
    // a drawing is a deterministic function of an answer, so an entry for a
    // superseded version is dead the moment the version moves and replacing in
    // place is the whole of eviction.
    fixture().flatMap { case (stage, draws) =>
      stage.draw(q, ChartStyle(), 100L, data) *>
        stage.draw(q, ChartStyle(), 100L, data) *>
        draws.get.map(assertEquals(_, 1)) *>
        stage.draw(q, ChartStyle(), 200L, data) *>
        draws.get.map(assertEquals(_, 2)) *>
        stage.keys.map(ks => assertEquals(ks.size, 1))
    }
  }

  test("two questions in one bucket are two drawings of their own data") {
    // Two sensors over one window share a version (the bucket), so the
    // version alone cannot say whose drawing an entry is.
    def dataOf(v: Double) =
      Series.toJson(Series(Vector(Series.Point(java.time.Instant.EPOCH, v)), 0))
    fixture(draw = (s, _) => IO.pure(s"<svg>${s.points.head.value}</svg>"))
      .flatMap { case (stage, draws) =>
        for {
          a <- stage.draw(question("sensor.a"), ChartStyle(), 100L, dataOf(1.0))
          b <- stage.draw(question("sensor.b"), ChartStyle(), 100L, dataOf(2.0))
          d <- draws.get
        } yield {
          assertEquals((a, b), ("<svg>1.0</svg>", "<svg>2.0</svg>"))
          assertEquals(d, 2)
        }
      }
  }

  test("a failed drawing is retried once its window passes") {
    // Same rule the series cache keeps, and for the same reason: otherwise one
    // bad draw blanks a chart until its version moves, which for a 30 d window
    // is an hour.
    Ref[IO].of(0).flatMap { attempts =>
      ChartStage
        .create(
          IO.pure((_, _) =>
            attempts.updateAndGet(_ + 1).flatMap {
              case 1 => IO.raiseError(RuntimeException("no engine"))
              case _ => IO.pure("<svg/>")
            }
          ),
          failureTtl = scala.concurrent.duration.Duration.Zero
        )
        .flatMap { stage =>
          stage.draw(q, ChartStyle(), 100L, data).attempt *>
            stage
              .draw(q, ChartStyle(), 100L, data)
              .map(assertEquals(_, "<svg/>"))
        }
    }
  }

  test("an answer the stage cannot read is an error naming why") {
    // The provider's contract is JSON, so a stage reads it back rather than
    // being handed a typed value. That round trip is a real failure mode, and
    // a blank chart would be the worst way to report it.
    fixture().flatMap { case (stage, _) =>
      stage
        .draw(q, ChartStyle(), 100L, Json.obj("nope" -> Json.True))
        .attempt
        .map { r =>
          r.left.getOrElse(fail("expected a failure")) match {
            case e: FHError =>
              assert(
                e.getMessage.contains("cannot read its provider's answer"),
                clue = e.getMessage
              )
            case other => fail(s"expected an FHError, got $other")
          }
        }
    }
  }
}
