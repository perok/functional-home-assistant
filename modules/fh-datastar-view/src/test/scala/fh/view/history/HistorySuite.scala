package fh.view.history

import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.fetch.{FetchIdentity, FetchRequest}
import io.circe.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

class HistorySuite extends munit.CatsEffectSuite {

  private val now = Instant.parse("2026-09-19T12:00:00Z")
  private val entity = "sensor.t"

  /** `raw` is one per fetch, since a fetch always asks it. */
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

  private def rowsFrom(oldest: Instant)(start: Instant): List[HistoryPoint] = {
    val from = if (start.isBefore(oldest)) oldest else start
    val minutes = java.time.Duration.between(from, now).toMinutes
    (0L to minutes)
      .map(m => HistoryPoint("1.0", from.plusSeconds(m * 60)))
      .toList
  }

  private def hourlyStats(
      from: Instant,
      to: Instant = now
  ): List[StatisticPoint] = {
    val hours = java.time.Duration.between(from, to).toHours
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
        identity: FetchIdentity = FetchIdentity.Instance
    ) = h.series(identity, e, w, asOf)

  test(
    "a day or longer reads statistics, then raw; the hour and every point raw"
  ) {
    def made(w: Window, everyPoint: Boolean = false) =
      for {
        src <- source(statRows = hourlyStats(ago(30.days)))
        h <- History.create(src)
        _ <- h.answerAt(FetchIdentity.Instance, entity, w, now, everyPoint)
        calls <- src.calls.get
      } yield calls
    for {
      hour <- made(Window.LastHour)
      day <- made(Window.LastDay)
      week <- made(Window.LastWeek)
      month <- made(Window.LastMonth)
      every <- made(Window.LastDay, everyPoint = true)
    } yield {
      assertEquals(hour, Vector("raw"))
      assertEquals(day, Vector("statistics:5minute", "raw"))
      assertEquals(week, Vector("statistics:hour", "raw"))
      assertEquals(month, Vector("statistics:hour", "raw"))
      assertEquals(every, Vector("raw"))
    }
  }

  test("raw rows after the newest bucket carry the line to now") {
    // A bucket is written after its period ends, so the newest lags by minutes.
    val lastEnd = ago(1.hour)
    for {
      starts <- Ref[IO].of(Vector.empty[Instant])
      src <- source(
        (_, s) => starts.update(_ :+ s).as(rowsFrom(s)(s)),
        hourlyStats(ago(7.days), lastEnd)
      )
      h <- History.create(src)
      s <- h.ask(Window.LastWeek)
      asked <- starts.get
    } yield {
      assertEquals(asked, Vector(lastEnd))
      val times = s.points.map(_.at)
      assertEquals(times, times.sorted)
      assertEquals(times.lastOption, Some(now))
      assert(times.count(_ == lastEnd) == 1, clue = times)
    }
  }

  test("an entity with no statistics reads its whole window raw") {
    for {
      starts <- Ref[IO].of(Vector.empty[Instant])
      src <- source((_, s) => starts.update(_ :+ s).as(Nil))
      h <- History.create(src)
      _ <- h.ask(Window.LastWeek)
      asked <- starts.get
    } yield assertEquals(asked, Vector(ago(7.days)))
  }

  test("a month past the recorder's purge still draws, from its buckets") {
    for {
      src <- source(
        (_, s) => IO.pure(rowsFrom(ago(10.days))(s)),
        hourlyStats(ago(30.days))
      )
      h <- History.create(src)
      s <- h.ask(Window.LastMonth)
    } yield assert(
      s.points.headOption.exists(_.at.isBefore(ago(29.days))),
      clue = s.points.headOption
    )
  }

  test("the result is downsampled before anyone sees it") {
    for {
      src <- source((_, s) => IO.pure(rowsFrom(ago(30.days))(s)))
      h <- History.create(src, target = 100)
      s <- h.ask(Window.LastMonth)
    } yield assertEquals(s.points.length, 100)
  }

  test("ten viewers in one bucket cost one fetch and share a version") {
    for {
      src <- source((_, _) => IO.sleep(50.millis).as(Nil))
      h <- History.create(src)
      answers <- List
        .fill(10)(
          h.answerAt(FetchIdentity.Instance, entity, Window.LastDay, now)
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
      h.answerAt(FetchIdentity.Instance, entity, Window.LastDay, at)
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
    // Windows bucket at different sizes, so mid-hour the 7d floor is older than
    // the 1h while both are current. Longest first, so each shorter miss sweeps
    // over a longer window's live entry.
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
    // A key omitting identity would be a permission leak, not a performance
    // bug.
    for {
      src <- source()
      h <- History.create(src)
      _ <- h.ask(Window.LastDay)
      _ <- h.ask(Window.LastDay, identity = FetchIdentity.user("alice"))
      count <- src.fetches
    } yield assertEquals(count, 2)
  }

  test("a failure is retried once its window passes") {
    // A series failed by an HA blink comes back when it stops, not next bucket.
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
    // Every live pull showing the chart asks; with the recorder down each would
    // wait out a fresh fetch.
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
    // A round trip rather than bytes: what passthrough writes, the chart stage
    // reads.
    val s = Series(
      Vector(
        Series.Point(Instant.ofEpochMilli(1000L), 1.5),
        Series.Point(Instant.ofEpochMilli(2000L), 2.5)
      )
    )
    val json = s.asJson
    assert(json.noSpaces.contains("[[1000,1.5],[2000,2.5]]"), clue = json)
    assertEquals(json.as[Series], Right(s))
  }

  test("an unknown window is a build error naming the ones that exist") {
    val e = HistoryFetch
      .parse(Map("entity" -> "sensor.t", "window" -> "last-week"))
      .swap
      .getOrElse(fail("expected a parse error"))
    assert(e.contains("last-week"), clue = e)
    assert(e.contains("24h"), clue = e)
  }

  test("a fetch with no entity or no window is a build error") {
    assert(HistoryFetch.parse(Map("window" -> "24h")).isLeft)
    assert(HistoryFetch.parse(Map("entity" -> "sensor.t")).isLeft)
  }

  test("points 'all' asks for every reading; anything else is a build error") {
    assertEquals(
      HistoryFetch.parse(
        Map("entity" -> "sensor.t", "window" -> "24h", "points" -> "all")
      ),
      Right(FetchRequest.History("sensor.t", Window.LastDay, everyPoint = true))
    )
    assert(
      HistoryFetch
        .parse(Map("entity" -> "sensor.t", "window" -> "24h", "points" -> "9"))
        .isLeft
    )
  }

  test("every point is its own cache entry, and nothing is thinned out") {
    // A countdown's start is one row: thinned to ~300, a day of minute rows
    // kept 100 of a job that started at 101.
    for {
      src <- source((_, s) => IO.pure(rowsFrom(ago(1.day))(s)))
      h <- History.create(src, target = 100)
      thinned <- h.ask(Window.LastDay)
      every <- h
        .answerAt(FetchIdentity.Instance, entity, Window.LastDay, now, true)
        .flatMap(_.data.as[Series].liftTo[IO])
      count <- src.fetches
    } yield {
      assertEquals(thinned.points.length, 100)
      assert(every.points.length > 1000, clue = every.points.length)
      assertEquals(count, 2)
    }
  }

  test("a size is not a fetch parameter, and is ignored here") {
    // The question is entity and window; size is how the answer is drawn
    // (`ChartStyleSuite`).
    assertEquals(
      HistoryFetch.parse(
        Map("entity" -> "sensor.t", "window" -> "24h", "width" -> "wide")
      ),
      Right(FetchRequest.History("sensor.t", Window.LastDay))
    )
  }
}
