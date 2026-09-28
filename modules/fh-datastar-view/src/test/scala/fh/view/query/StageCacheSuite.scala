package fh.view.query

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.FHError
import fh.view.history.{ChartStyle, History, Series, SeriesSource, Window}
import fh.view.model.Transform
import io.circe.Json

/** What a STAGE costs: keyed by question, stage (every field of its style) and
  * version, and shared by everyone reading one version — for any stage, since
  * the resolver caches them alike.
  */
class StageCacheSuite extends munit.CatsEffectSuite {

  private def question(entity: String): QueryRequest =
    QueryRequest.History(entity, Window.LastDay)

  private val q = question("sensor.t")

  private val data = Series.toJson(
    Series(Vector(Series.Point(java.time.Instant.EPOCH, 1.0)), 0)
  )

  private def chart(style: ChartStyle = ChartStyle()) =
    Transform.Stage.Chart(style)

  // The resolver is handed its answers, so its provider is never asked.
  private val unused: SeriesSource = new SeriesSource {
    def raw(
        start: java.time.Instant,
        end: java.time.Instant,
        entityId: String
    ) =
      IO.raiseError(RuntimeException("not asked"))
    def statistics(
        start: java.time.Instant,
        end: java.time.Instant,
        entityId: String,
        period: api.homeassistant.ws.domain.StatisticsPeriod
    ) = IO.raiseError(RuntimeException("not asked"))
  }

  private def resolver(
      draw: (Series, ChartStyle) => IO[String],
      failureTtl: scala.concurrent.duration.FiniteDuration =
        SharedCache.FailureTtl
  ): IO[QueryResolver] =
    History
      .create(unused)
      .flatMap(QueryResolver.create(_, IO.pure(draw), failureTtl))

  private def fixture(
      draw: (Series, ChartStyle) => IO[String] = (_, s) =>
        IO.pure(s"<svg>${s.width}</svg>")
  ): IO[(QueryResolver, Ref[IO, Int])] =
    for {
      draws <- Ref[IO].of(0)
      r <- resolver((series, style) =>
        draws.update(_ + 1) *> draw(series, style)
      )
    } yield (r, draws)

  private def stage(
      r: QueryResolver,
      request: QueryRequest,
      st: Transform.Stage,
      version: Long,
      answer: Json = data
  ): IO[String] =
    r.stage(QueryIdentity.Instance, request, st, Answer(version, answer))
      .map(_.value)

  test("ten viewers of one version cost one drawing") {
    // `QuerySnapshot.resolve` runs before the render cache, so without this
    // every page open would redraw.
    fixture().flatMap { case (r, draws) =>
      List
        .fill(10)(stage(r, q, chart(), 100L))
        .parSequence
        .flatMap { results =>
          draws.get.map { d =>
            assertEquals(results.distinct, List("<svg>400</svg>"))
            assertEquals(d, 1)
          }
        }
    }
  }

  test("two styles of one answer are two drawings") {
    // A tile and a popup at different sizes; their shared fetch is
    // `QueryRenderInputsSuite`'s.
    fixture().flatMap { case (r, draws) =>
      stage(r, q, chart(ChartStyle(width = 600)), 100L) *>
        stage(r, q, chart(ChartStyle(width = 320)), 100L) *>
        draws.get.map(assertEquals(_, 2))
    }
  }

  test("a moved version restages, and needs no expiry rule to do it") {
    // A staged value is a function of an answer, so replacing in place is the
    // whole of eviction.
    fixture().flatMap { case (r, draws) =>
      stage(r, q, chart(), 100L) *>
        stage(r, q, chart(), 100L) *>
        draws.get.map(assertEquals(_, 1)) *>
        stage(r, q, chart(), 200L) *>
        draws.get.map(assertEquals(_, 2)) *>
        r.keys.map(ks => assertEquals(ks.size, 1))
    }
  }

  test("every stage is cached, not only a drawing") {
    // One entry per (question, stage), even for a cheap stage.
    fixture().flatMap { case (r, _) =>
      for {
        raw <- stage(r, q, Transform.Stage.Passthrough, 100L)
        _ <- stage(r, q, chart(), 100L)
        _ <- stage(r, q, Transform.Stage.Passthrough, 200L)
        ks <- r.keys
      } yield {
        assertEquals(raw, data.noSpaces)
        assertEquals(
          ks.map(k => (k.stage, k.version)),
          Set[(Transform.Stage, Long)](
            (chart(), 100L),
            (Transform.Stage.Passthrough, 200L)
          )
        )
      }
    }
  }

  test("two questions in one bucket are two drawings of their own data") {
    // Two sensors over one window share a version (the bucket), so the
    // version alone cannot say whose drawing an entry is.
    def dataOf(v: Double) =
      Series.toJson(Series(Vector(Series.Point(java.time.Instant.EPOCH, v)), 0))
    fixture(draw = (s, _) => IO.pure(s"<svg>${s.points.head.value}</svg>"))
      .flatMap { case (r, draws) =>
        for {
          a <- stage(r, question("sensor.a"), chart(), 100L, dataOf(1.0))
          b <- stage(r, question("sensor.b"), chart(), 100L, dataOf(2.0))
          d <- draws.get
        } yield {
          assertEquals((a, b), ("<svg>1.0</svg>", "<svg>2.0</svg>"))
          assertEquals(d, 2)
        }
      }
  }

  test("a failed drawing is retried once its window passes") {
    // Otherwise one bad draw blanks a chart until its version moves: an hour
    // for a 30 d window.
    Ref[IO].of(0).flatMap { attempts =>
      resolver(
        (_, _) =>
          attempts.updateAndGet(_ + 1).flatMap {
            case 1 => IO.raiseError(RuntimeException("no engine"))
            case _ => IO.pure("<svg/>")
          },
        failureTtl = scala.concurrent.duration.Duration.Zero
      ).flatMap { r =>
        stage(r, q, chart(), 100L).attempt *>
          stage(r, q, chart(), 100L).map(assertEquals(_, "<svg/>"))
      }
    }
  }

  test("an answer the stage cannot read is an error naming why") {
    // A stage reads the provider's JSON back; that round trip can fail, and a
    // blank chart would be the worst report.
    fixture().flatMap { case (r, _) =>
      stage(r, q, chart(), 100L, Json.obj("nope" -> Json.True)).attempt
        .map { res =>
          res.left.getOrElse(fail("expected a failure")) match {
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
