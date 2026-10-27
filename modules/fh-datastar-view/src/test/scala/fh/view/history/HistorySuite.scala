package fh.view.history

import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.fetch.FetchIdentity
import io.circe.syntax.*
import io.github.iltotore.iron.*

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
      StatisticPoint(s, s.plusSeconds(3600), mean = Some(1.0))
    }.toList
  }

  private def ago(d: FiniteDuration) = now.minusSeconds(d.toSeconds)

  /** The cache tests read raw, so what they count is the fetch itself, never a
    * statistics read falling back.
    */
  private val rawDay: Window = Window.Raw(86400L)

  extension (h: History)
    private def ask(
        w: Window,
        asOf: Instant = now,
        e: String = entity,
        identity: FetchIdentity = FetchIdentity.Instance
    ) = h.series(identity, e, w, asOf)

  test("a window reads what it says, and only that") {
    def made(w: Window) =
      for {
        src <- source(statRows = hourlyStats(ago(30.days)))
        h <- History.create(src)
        _ <- h.answerAt(FetchIdentity.Instance, entity, w, now)
        calls <- src.calls.get
      } yield calls
    for {
      hour <- made(Windows.hour)
      day <- made(Windows.day)
      week <- made(Windows.week)
    } yield {
      assertEquals(hour, Vector("raw"))
      assertEquals(day, Vector("statistics:5minute"))
      assertEquals(week, Vector("statistics:hour"))
    }
  }

  test("the newest bucket is carried to now, as HA's own chart does") {
    // A bucket is written after its period ends, so the newest lags by minutes.
    for {
      src <- source(statRows = hourlyStats(ago(7.days), ago(1.hour)))
      h <- History.create(src)
      s <- h.ask(Windows.week)
      calls <- src.calls.get
    } yield {
      assertEquals(calls, Vector("statistics:hour"))
      assertEquals(s.points.lastOption.map(_.at), Some(now))
      assertEquals(
        s.points.map(_.at).filter(_.isAfter(ago(1.hour))),
        Vector(now)
      )
    }
  }

  test("no statistics draw nothing, unless the window falls back to raw") {
    // Only an entity with a `state_class` has statistics.
    val strict = Window.Statistics(604800L, StatisticsPeriod.Hour)
    for {
      starts <- Ref[IO].of(Vector.empty[Instant])
      src <- source((_, s) => starts.update(_ :+ s).as(rowsFrom(ago(1.day))(s)))
      h <- History.create(src)
      none <- h.ask(strict)
      fellBack <- h.ask(Windows.week)
      asked <- starts.get
    } yield {
      assertEquals(none, Series.empty)
      assert(fellBack.points.nonEmpty, clue = fellBack)
      assertEquals(asked, Vector(ago(7.days)))
    }
  }

  test("a month past the recorder's purge still draws, from its buckets") {
    for {
      src <- source(
        (_, s) => IO.pure(rowsFrom(ago(10.days))(s)),
        hourlyStats(ago(30.days))
      )
      h <- History.create(src)
      s <- h.ask(Windows.month)
    } yield assert(
      s.points.headOption.exists(_.at.isBefore(ago(29.days))),
      clue = s.points.headOption
    )
  }

  test("the answer is every bucket of a statistics read, none thinned") {
    for {
      src <- source(statRows = hourlyStats(ago(30.days)))
      h <- History.create(src)
      s <- h.ask(Windows.month)
    } yield assertEquals(s.points.length, 30 * 24)
  }

  test(
    "the bucket is the period, or a 720th of a raw span, at least a minute"
  ) {
    assertEquals(Windows.hour.bucket, 1.minute)
    assertEquals(Windows.day.bucket, 5.minutes)
    assertEquals(Windows.week.bucket, 1.hour)
    assertEquals(Windows.month.bucket, 1.hour)
    assertEquals(Window.Raw(604800L).bucket, 14.minutes)
  }

  test("the chart stage thins what it draws; the answer keeps every point") {
    // How many points a drawing needs is the drawing's, so a chart and a
    // computation over one window share one fetch.
    val every = Series(
      Vector.tabulate(5000)(i => Series.Point(now.plusSeconds(i.toLong), 1.0))
    )
    val counted: ChartDraw = (s, _) => IO.pure(s.points.length.toString)
    ChartStage
      .draw(IO.pure(counted), ChartStyle(), every.asJson)
      .map(drawn => assertEquals(drawn, Downsample.DefaultTarget.toString))
  }

  test("ten viewers in one bucket cost one fetch and share a version") {
    for {
      src <- source((_, _) => IO.sleep(50.millis).as(Nil))
      h <- History.create(src)
      answers <- List
        .fill(10)(
          h.answerAt(FetchIdentity.Instance, entity, rawDay, now)
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
    val later = now.plusSeconds(rawDay.bucket.toSeconds)
    def version(h: History, at: Instant) =
      h.answerAt(FetchIdentity.Instance, entity, rawDay, at)
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
    val windows: List[Window] = List(
      Window.Raw(2592000L),
      Window.Raw(604800L),
      Window.Raw(86400L),
      Window.Raw(3600L)
    )
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
      _ <- h.ask(rawDay)
      _ <- h.ask(rawDay, identity = FetchIdentity.user("alice"))
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
      first <- h.ask(rawDay).attempt
      second <- h.ask(rawDay)
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
      first <- h.ask(rawDay).attempt
      second <- h.ask(rawDay).attempt
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

  test("a window that does not decode names what would") {
    def err(json: String) =
      io.circe.parser
        .decode[Window](json)
        .fold(_.getMessage, w => fail(s"decoded $w"))
    assert(err("""{"kind":"Weekly","span":60}""").contains("Weekly"))
    val period = err("""{"kind":"Statistics","span":60,"period":"minute"}""")
    assert(period.contains("5minute"), clue = period)
    assert(err("""{"kind":"Raw","span":0}""").contains("positive"))
  }

  test("statistics at a period no shorter than the span answer nothing") {
    // Measured: `hour` over one hour is empty.
    assert(Window.Statistics(3600L, StatisticsPeriod.Hour).problem.isDefined)
    assertEquals(Windows.presets.values.flatMap(_.problem).toList, Nil)
  }

  test("a raw answer keeps every row, which a computation wants") {
    // A countdown's start is one row: thinned to ~300, a day of minute rows
    // kept 100 of a job that started at 101.
    for {
      src <- source((_, s) => IO.pure(rowsFrom(ago(1.day))(s)))
      h <- History.create(src)
      every <- h.ask(Window.Raw(86400L))
    } yield assertEquals(every.points.length, 24 * 60 + 1)
  }
}
