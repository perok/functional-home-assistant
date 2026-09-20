package fh.view.history

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.query.{PreparedQuery, QueryIdentity}
import org.http4s.Request

import java.time.Instant

/** What a chart costs when many viewers want it, and what the provider refuses
  * to accept before anyone does.
  */
class HistoryProviderSuite extends munit.CatsEffectSuite {

  private val t0 = Instant.parse("2026-09-19T12:00:00Z")

  private final class CountingProvider(fetches: Ref[IO, Int])
      extends SeriesProvider {
    def identify(req: Request[IO]): IO[QueryIdentity] =
      IO.pure(QueryIdentity.Instance)
    def series(
        identity: QueryIdentity,
        entityId: String,
        window: Window,
        asOf: Instant
    ): IO[Series] = fetches.update(_ + 1).as(Series(Vector.empty, 0))
  }

  private def fixture
      : IO[(HistoryProvider, Ref[IO, Int], Ref[IO, Int])] =
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      store <- SeriesStore.create(CountingProvider(fetches))
      history <- HistoryProvider.create(
        store,
        (_, style) => draws.update(_ + 1).as(s"<svg>${style.width}</svg>")
      )
    } yield (history, fetches, draws)

  private def prepared(
      p: HistoryProvider,
      window: String,
      extra: (String, String)*
  ): PreparedQuery =
    p.parse(Map("entity" -> "sensor.t", "window" -> window) ++ extra.toMap)
      .fold(e => fail(e), identity)

  // --- Sharing --------------------------------------------------------------

  test("ten viewers in one bucket cost one fetch and one drawing") {
    // The reason the JavaScript context is not on the hot path. Ten OPEN TABS
    // are already deduped by the per-slug render cache; ten browsers reaching
    // a cold add-on inside one bucket are not, and that is the case this
    // covers.
    fixture.flatMap { case (p, fetches, draws) =>
      val q = prepared(p, "24h")
      List
        .fill(10)(q.resolve(QueryIdentity.Instance, t0))
        .parSequence
        .flatMap { results =>
          (fetches.get, draws.get).tupled.map { case (f, d) =>
            assertEquals(results.map(_.html).distinct, List("<svg>600</svg>"))
            assertEquals(results.map(_.version).distinct.size, 1)
            assertEquals(f, 1)
            assertEquals(d, 1)
          }
        }
    }
  }

  test("a rolled bucket is a new drawing, an unrolled one is not") {
    fixture.flatMap { case (p, _, draws) =>
      val q = prepared(p, "24h")
      val later = t0.plusSeconds(Window.LastDay.bucket.toSeconds)
      q.resolve(QueryIdentity.Instance, t0) *>
        q.resolve(QueryIdentity.Instance, t0.plusSeconds(1)) *>
        draws.get.map(assertEquals(_, 1)) *>
        q.resolve(QueryIdentity.Instance, later) *>
        draws.get.map(assertEquals(_, 2))
    }
  }

  test("the version is the bucket, so it moves only when the bucket does") {
    fixture.flatMap { case (p, _, _) =>
      val q = prepared(p, "24h")
      val later = t0.plusSeconds(Window.LastDay.bucket.toSeconds)
      (
        q.resolve(QueryIdentity.Instance, t0),
        q.resolve(QueryIdentity.Instance, t0.plusSeconds(1)),
        q.resolve(QueryIdentity.Instance, later)
      ).tupled.map { case (a, b, c) =>
        assertEquals(a.version, b.version)
        assert(c.version > a.version, clue = (a.version, c.version))
      }
    }
  }

  test("two identities never share a drawing") {
    // A cache key that omitted identity would be a permission leak rather than
    // a performance bug, which is why it is in the key before any per-user
    // provider exists.
    fixture.flatMap { case (p, fetches, _) =>
      val q = prepared(p, "24h")
      q.resolve(QueryIdentity.Instance, t0) *>
        q.resolve(QueryIdentity.user("alice"), t0) *>
        fetches.get.map(assertEquals(_, 2))
    }
  }

  test("two styles of one series share the fetch but not the drawing") {
    // The ONLY thing the series cache does that the chart cache does not, and
    // the whole reason there are two: a tile and a popup showing the same
    // sensor at different sizes are two pictures of one fetch. Measured by
    // deleting the series cache — of 854 tests nothing but its own noticed,
    // until this one.
    fixture.flatMap { case (p, fetches, draws) =>
      prepared(p, "24h", "width" -> "600")
        .resolve(QueryIdentity.Instance, t0) *>
        prepared(p, "24h", "width" -> "320")
          .resolve(QueryIdentity.Instance, t0) *>
        (fetches.get, draws.get).tupled.map { case (f, d) =>
          assertEquals(f, 1)
          assertEquals(d, 2)
        }
    }
  }

  test("a 1h bucket does not evict a live 30d entry") {
    // Regression. The sweep used to keep only entries at or after the NEWLY
    // inserted key's bucket — but a 1 h read buckets by the minute and a 30 d
    // read by the hour, so every 1 h insert threw away a 30 d entry that was
    // good for another 59 minutes. Measured at 3 drawings where 2 is correct,
    // which is the sharing this cache exists for, gone.
    fixture.flatMap { case (p, _, draws) =>
      val month = prepared(p, "30d")
      val hour = prepared(p, "1h")
      val t1 = t0.plusSeconds(70)
      month.resolve(QueryIdentity.Instance, t0) *>
        hour.resolve(QueryIdentity.Instance, t1) *>
        month.resolve(QueryIdentity.Instance, t1) *>
        draws.get.map(assertEquals(_, 2))
    }
  }

  // --- Parsing --------------------------------------------------------------

  test("an unknown window is a build error naming the ones that exist") {
    fixture.map { case (p, _, _) =>
      val e = p
        .parse(Map("entity" -> "sensor.t", "window" -> "last-week"))
        .swap
        .getOrElse(fail("expected a parse error"))
      assert(e.contains("last-week"), clue = e)
      assert(e.contains("24h"), clue = e)
    }
  }

  test("a query with no entity or no window is a build error") {
    fixture.map { case (p, _, _) =>
      assert(p.parse(Map("window" -> "24h")).isLeft)
      assert(p.parse(Map("entity" -> "sensor.t")).isLeft)
    }
  }

  test("a non-numeric size is a build error rather than a silent default") {
    fixture.map { case (p, _, _) =>
      val e = p
        .parse(
          Map("entity" -> "sensor.t", "window" -> "24h", "width" -> "wide")
        )
        .swap
        .getOrElse(fail("expected a parse error"))
      assert(e.contains("width"), clue = e)
    }
  }
}
