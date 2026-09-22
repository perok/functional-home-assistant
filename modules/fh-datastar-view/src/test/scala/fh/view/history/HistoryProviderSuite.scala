package fh.view.history

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.query.{Answer, QueryIdentity, QueryRequest}
import org.http4s.Request

import java.time.Instant

/** What a FETCH costs when many viewers want it, and what the provider refuses
  * to accept before anyone does.
  *
  * Drawing is `ChartStageSuite`'s: the provider answers data only.
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

  private def fixture: IO[(HistoryProvider, Ref[IO, Int])] =
    for {
      fetches <- Ref[IO].of(0)
      store <- SeriesStore.create(CountingProvider(fetches))
      history <- HistoryProvider.create(store)
    } yield (history, fetches)

  /** Parse is pure and instance-free; resolving is what needs the provider. */
  private def request(window: String): QueryRequest =
    HistoryQuery
      .parse(Map("entity" -> "sensor.t", "window" -> window))
      .fold(e => fail(e), identity)

  extension (p: HistoryProvider)
    private def ask(
        r: QueryRequest,
        asOf: Instant,
        identity: QueryIdentity = QueryIdentity.Instance
    ): IO[Answer] = r match {
      case QueryRequest.History(e, w) => p.answer(identity, e, w, asOf)
    }

  // --- Sharing --------------------------------------------------------------

  test("ten viewers in one bucket cost one fetch") {
    // Ten OPEN TABS are already deduped by the per-slug render cache; ten
    // browsers reaching a cold add-on inside one bucket are not, and that is
    // the case this covers.
    fixture.flatMap { case (p, fetches) =>
      val q = request("24h")
      List.fill(10)(p.ask(q, t0)).parSequence.flatMap { results =>
        fetches.get.map { f =>
          assertEquals(results.map(_.version).distinct.size, 1)
          assertEquals(f, 1)
        }
      }
    }
  }

  test("a rolled bucket is a new fetch, an unrolled one is not") {
    fixture.flatMap { case (p, fetches) =>
      val q = request("24h")
      val later = t0.plusSeconds(Window.LastDay.bucket.toSeconds)
      p.ask(q, t0) *>
        p.ask(q, t0.plusSeconds(1)) *>
        fetches.get.map(assertEquals(_, 1)) *>
        p.ask(q, later) *>
        fetches.get.map(assertEquals(_, 2))
    }
  }

  test("the version is the bucket, so it moves only when the bucket does") {
    fixture.flatMap { case (p, _) =>
      val q = request("24h")
      val later = t0.plusSeconds(Window.LastDay.bucket.toSeconds)
      (
        p.ask(q, t0),
        p.ask(q, t0.plusSeconds(1)),
        p.ask(q, later)
      ).tupled.map { case (a, b, c) =>
        assertEquals(a.version, b.version)
        assert(c.version > a.version, clue = (a.version, c.version))
      }
    }
  }

  test("two identities never share a fetch") {
    // A cache key that omitted identity would be a permission leak rather than
    // a performance bug, which is why it is in the key before any per-user
    // provider exists.
    fixture.flatMap { case (p, fetches) =>
      val q = request("24h")
      p.ask(q, t0) *>
        p.ask(q, t0, QueryIdentity.user("alice")) *>
        fetches.get.map(assertEquals(_, 2))
    }
  }

  test("a 1h bucket does not evict a live 30d entry") {
    // A 1 h read buckets by the minute and a 30 d read by the hour; sweeping
    // by the newest key's bucket would evict the live 30 d entry.
    fixture.flatMap { case (p, fetches) =>
      val month = request("30d")
      val hour = request("1h")
      val t1 = t0.plusSeconds(70)
      p.ask(month, t0) *>
        p.ask(hour, t1) *>
        p.ask(month, t1) *>
        fetches.get.map(assertEquals(_, 2))
    }
  }

  test("the answer is the series as DATA, which is the whole contract") {
    // What a passthrough transform puts in the hole, and what the chart stage
    // reads back. Asserted as a round trip rather than on the bytes, because
    // the bytes are what a third party writes against and the round trip is
    // what says the two ends agree.
    val s = Series(
      Vector(
        Series.Point(Instant.ofEpochMilli(1000L), 1.5),
        Series.Point(Instant.ofEpochMilli(2000L), 2.5)
      ),
      unavailable = 3
    )
    val json = Series.toJson(s)
    assert(json.noSpaces.contains("[[1000,1.5],[2000,2.5]]"), clue = json)
    assertEquals(json.as[Series], Right(s))
  }

  // --- Parsing --------------------------------------------------------------

  // Parsing needs no fixture at all, which is the point of it being pure: a
  // dashboard is checked wherever it is built, not only where a provider was
  // wired in.

  test("an unknown window is a build error naming the ones that exist") {
    val e = HistoryQuery
      .parse(Map("entity" -> "sensor.t", "window" -> "last-week"))
      .swap
      .getOrElse(fail("expected a parse error"))
    assert(e.contains("last-week"), clue = e)
    assert(e.contains("24h"), clue = e)
  }

  test("a query with no entity or no window is a build error") {
    assert(HistoryQuery.parse(Map("window" -> "24h")).isLeft)
    assert(HistoryQuery.parse(Map("entity" -> "sensor.t")).isLeft)
  }

  test("a size is not a query parameter, and is ignored here") {
    // It USED to be one, and moving it is the split: the question is the
    // entity and the window, the size is how the answer is drawn. A leftover
    // `width` in a hand-written query is simply not part of the question any
    // more — `ChartStyleSuite` is where a bad one is rejected now.
    assertEquals(
      HistoryQuery.parse(
        Map("entity" -> "sensor.t", "window" -> "24h", "width" -> "wide")
      ),
      Right(QueryRequest.History("sensor.t", Window.LastDay))
    )
  }
}
