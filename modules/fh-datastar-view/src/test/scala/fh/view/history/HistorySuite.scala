package fh.view.history

import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.query.{QueryIdentity, QueryRequest}

import java.time.Instant
import scala.concurrent.duration.*

/** The `history` provider: which source it asks, what it learns, what it
  * shares, and what it refuses before anyone asks.
  */
class HistorySuite extends munit.CatsEffectSuite {

  private val now = Instant.parse("2026-09-19T12:00:00Z")
  private val entity = "sensor.t"

  /** Records every call; `raw` is one per fetch, since a fetch always asks it.
    */
  private final class StubSource(
      rawRows: (String, Instant) => IO[List[HistoryPoint]],
      statRows: List[StatisticPoint],
      val calls: Ref[IO, Vector[String]]
  ) extends SeriesSource {
    def raw(start: Instant, end: Instant, entityId: String) =
      calls.update(_ :+ "raw") *> rawRows(entityId, start)
    def statistics(
        start: Instant,
        end: Instant,
        entityId: String,
        period: StatisticsPeriod
    ) = calls.update(_ :+ s"statistics:${period.wire}").as(statRows)

    def fetches: IO[Int] = calls.get.map(_.count(_ == "raw"))
    def reset: IO[Unit] = calls.set(Vector.empty)
  }

  private def source(
      rawRows: (String, Instant) => IO[List[HistoryPoint]] = (_, _) =>
        IO.pure(Nil),
      statRows: List[StatisticPoint] = Nil
  ): IO[StubSource] =
    Ref[IO].of(Vector.empty[String]).map(StubSource(rawRows, statRows, _))

  /** Raw rows every minute, starting no earlier than `oldest`. */
  private def rowsFrom(oldest: Instant)(start: Instant): List[HistoryPoint] = {
    val from = if (start.isBefore(oldest)) oldest else start
    val minutes = java.time.Duration.between(from, now).toMinutes
    (0L to minutes)
      .map(m => HistoryPoint("1.0", from.plusSeconds(m * 60)))
      .toList
  }

  private def hourlyStats(from: Instant): List[StatisticPoint] = {
    val hours = java.time.Duration.between(from, now).toHours
    (0L until hours).map { h =>
      val s = from.plusSeconds(h * 3600)
      StatisticPoint(
        s,
        s.plusSeconds(3600),
        Some(StatisticPoint.Mean(1.0, 1.0, 1.0)),
        None
      )
    }.toList
  }

  private def ago(d: FiniteDuration) = now.minusSeconds(d.toSeconds)

  extension (h: History)
    private def ask(
        w: Window,
        asOf: Instant = now,
        e: String = entity,
        identity: QueryIdentity = QueryIdentity.Instance
    ) = h.series(identity, e, w, asOf)

  // --- Source selection and retention -------------------------------------

  test("an unproven window asks both; a window retention covers asks raw") {
    // Ten days of rows for a thirty-day question is what the recorder holds,
    // and it is known from then on without anyone configuring it.
    for {
      src <- source((_, s) => IO.pure(rowsFrom(ago(10.days))(s)))
      h <- History.create(src)
      _ <- h.ask(Window.LastMonth)
      first <- src.calls.get
      _ <- src.reset
      _ <- h.ask(Window.LastWeek)
      covered <- src.calls.get
      _ <- src.reset
      _ <- h.ask(Window.LastMonth, e = "sensor.other")
      uncovered <- src.calls.get
    } yield {
      assertEquals(first.sorted, Vector("raw", "statistics:hour"))
      assertEquals(covered, Vector("raw"))
      assertEquals(uncovered.sorted, Vector("raw", "statistics:hour"))
    }
  }

  test("retention is the greatest age seen, not the latest one") {
    // A new sensor's short answer must not retract what an old row proved —
    // the two are indistinguishable per entity.
    for {
      src <- source {
        case ("sensor.new", s) => IO.pure(rowsFrom(ago(1.hour))(s))
        case (_, s)            => IO.pure(rowsFrom(ago(10.days))(s))
      }
      h <- History.create(src)
      _ <- h.ask(Window.LastMonth)
      _ <- h.ask(Window.LastMonth, e = "sensor.new")
      _ <- src.reset
      _ <- h.ask(Window.LastWeek, e = "sensor.third")
      made <- src.calls.get
    } yield assertEquals(made, Vector("raw"))
  }

  test("an empty answer proves nothing") {
    for {
      src <- source()
      h <- History.create(src)
      _ <- h.ask(Window.LastHour)
      _ <- src.reset
      _ <- h.ask(Window.LastHour, e = "sensor.other")
      made <- src.calls.get
    } yield assertEquals(made.sorted, Vector("raw", "statistics:5minute"))
  }

  test("the wider answer wins, which is how a purged window still draws") {
    for {
      src <- source(
        (_, s) => IO.pure(rowsFrom(ago(10.days))(s)),
        hourlyStats(ago(30.days))
      )
      h <- History.create(src)
      s <- h.ask(Window.LastMonth)
    } yield assert(s.span.exists(_.toDays >= 29), clue = s.span.map(_.toDays))
  }

  test("history wins a tie, because its resolution is finer") {
    // Statistics are plotted at bucket ENDS, so they start an hour later.
    val from = ago(7.days)
    for {
      src <- source((_, s) => IO.pure(rowsFrom(from)(s)), hourlyStats(from))
      h <- History.create(src)
      s <- h.ask(Window.LastWeek)
    } yield assertEquals(s.points.headOption.map(_.at), Some(from))
  }

  test("the result is downsampled before anyone sees it") {
    for {
      src <- source((_, s) => IO.pure(rowsFrom(ago(30.days))(s)))
      h <- History.create(src, target = 100)
      s <- h.ask(Window.LastMonth)
    } yield assertEquals(s.points.length, 100)
  }

  // --- Sharing -------------------------------------------------------------

  test("ten viewers in one bucket cost one fetch and share a version") {
    for {
      src <- source((_, _) => IO.sleep(50.millis).as(Nil))
      h <- History.create(src)
      answers <- List
        .fill(10)(
          h.answerAt(QueryIdentity.Instance, entity, Window.LastDay, now)
        )
        .parSequence
      count <- src.fetches
    } yield {
      assertEquals(count, 1)
      assertEquals(answers.map(_.version).distinct.size, 1)
    }
  }

  test(
    "a rolled bucket is a new fetch and a new version, and retires the key"
  ) {
    val later = now.plusSeconds(Window.LastDay.bucket.toSeconds)
    def version(h: History, at: Instant) =
      h.answerAt(QueryIdentity.Instance, entity, Window.LastDay, at)
        .map(_.version)
    for {
      src <- source()
      h <- History.create(src)
      a <- version(h, now)
      b <- version(h, now.plusSeconds(1))
      sameBucket <- src.fetches
      c <- version(h, later)
      rolled <- src.fetches
      keys <- h.keys
    } yield {
      assertEquals((sameBucket, rolled), (1, 2))
      assertEquals(a, b)
      assert(c > a, clue = (a, c))
      assertEquals(keys.size, 1)
    }
  }

  test("every window's current entry survives the others being asked for") {
    // Windows bucket at different sizes, so mid-hour the 7d floor is older
    // than the 1h floor while both are current. Longest first, so each
    // shorter window's miss runs the sweep over a longer one's live entry.
    val midHour = now.plusSeconds(25 * 60)
    val windows = Window.values.toList.sortBy(-_.span.toSeconds)
    for {
      src <- source()
      h <- History.create(src)
      askAll = windows.traverse_(w => h.ask(w, midHour))
      _ <- askAll
      _ <- askAll
      count <- src.fetches
      keys <- h.keys
    } yield {
      assertEquals(keys.map(_.window), windows.toSet)
      assertEquals(count, windows.size)
    }
  }

  test("two identities never share a fetch") {
    // A key that omitted identity would be a permission leak rather than a
    // performance bug.
    for {
      src <- source()
      h <- History.create(src)
      _ <- h.ask(Window.LastDay)
      _ <- h.ask(Window.LastDay, identity = QueryIdentity.user("alice"))
      count <- src.fetches
    } yield assertEquals(count, 2)
  }

  test("a failure is retried once its window passes") {
    // A series that failed because HA blinked comes back when it stops, not at
    // the next bucket.
    for {
      attempts <- Ref[IO].of(0)
      src <- source((_, _) =>
        attempts.getAndUpdate(_ + 1).flatMap {
          case 0 => IO.raiseError(new RuntimeException("HA is down"))
          case _ => IO.pure(Nil)
        }
      )
      h <- History.create(src, failureTtl = Duration.Zero)
      first <- h.ask(Window.LastDay).attempt
      second <- h.ask(Window.LastDay)
      count <- attempts.get
    } yield {
      assert(first.isLeft)
      assertEquals(second, Series.empty)
      assertEquals(count, 2)
    }
  }

  test("inside its window a failure is answered without asking HA again") {
    // Every live pull that shows the chart asks; with a recorder down, each
    // of them would otherwise wait out a fresh fetch.
    for {
      src <- source((_, _) => IO.raiseError(new RuntimeException("HA is down")))
      h <- History.create(src)
      first <- h.ask(Window.LastDay).attempt
      second <- h.ask(Window.LastDay).attempt
      count <- src.fetches
    } yield {
      assert(first.isLeft && second.isLeft, clue = (first, second))
      assertEquals(count, 1)
    }
  }

  test("the answer is the series as DATA, which is the whole contract") {
    // What passthrough puts in the hole and what the chart stage reads back,
    // so a round trip rather than the bytes.
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

  // --- Parsing -------------------------------------------------------------

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
    // The question is the entity and the window; the size is how the answer is
    // drawn (`ChartStyleSuite`).
    assertEquals(
      HistoryQuery.parse(
        Map("entity" -> "sensor.t", "window" -> "24h", "width" -> "wide")
      ),
      Right(QueryRequest.History("sensor.t", Window.LastDay))
    )
  }
}
