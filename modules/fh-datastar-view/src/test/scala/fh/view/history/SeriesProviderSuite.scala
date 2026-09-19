package fh.view.history

import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import cats.effect.{IO, Ref}
import cats.syntax.all.*

import java.time.Instant
import scala.concurrent.duration.*

/** Source selection, what it learns, and what the store shares.
  *
  * The stub is two methods because [[SeriesSource]] is two methods; a provider
  * that took the whole `HomeAssistantApi` would need eighteen more here to say
  * anything about a downsampler.
  */
class SeriesProviderSuite extends munit.CatsEffectSuite {

  private val now = Instant.parse("2026-09-19T12:00:00Z")
  private val entity = "sensor.t"

  private final class StubSource(
      rawRows: Instant => List[HistoryPoint],
      statRows: List[StatisticPoint] = Nil,
      val calls: Ref[IO, Vector[String]]
  ) extends SeriesSource {
    def raw(
        start: Instant,
        end: Instant,
        entityId: String
    ): IO[List[HistoryPoint]] =
      calls.update(_ :+ "raw").as(rawRows(start))

    def statistics(
        start: Instant,
        end: Instant,
        entityId: String,
        period: StatisticsPeriod
    ): IO[List[StatisticPoint]] =
      calls.update(_ :+ s"statistics:${period.wire}").as(statRows)
  }

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

  // --- Retention -----------------------------------------------------------

  test("retention is the greatest age seen, not the latest one") {
    for {
      r <- Retention.create
      _ <- r.record(Some(now.minusSeconds(10.days.toSeconds)), now)
      _ <- r.record(Some(now.minusSeconds(1.hour.toSeconds)), now)
      observed <- r.observed
      // A new sensor's short answer must not retract what an old row proved —
      // the two are indistinguishable per entity, which is the whole reason
      // this is a maximum.
      _ <- IO(assertEquals(observed.toDays, 10L))
      coversWeek <- r.covers(Window.LastWeek)
      coversMonth <- r.covers(Window.LastMonth)
      _ <- IO(assert(coversWeek))
      _ <- IO(assert(!coversMonth))
    } yield ()
  }

  test("an empty answer proves nothing") {
    for {
      r <- Retention.create
      _ <- r.record(None, now)
      observed <- r.observed
    } yield assertEquals(observed.toSeconds, 0L)
  }

  // --- Source selection ----------------------------------------------------

  test("a window retention already covers costs one call") {
    for {
      calls <- Ref[IO].of(Vector.empty[String])
      r <- Retention.create
      _ <- r.record(Some(now.minusSeconds(30.days.toSeconds)), now)
      p = SeriesProvider.asInstance(
        new StubSource(
          rowsFrom(now.minusSeconds(2.hours.toSeconds)),
          Nil,
          calls
        ),
        r
      )
      _ <- p.series(SeriesIdentity.Instance, entity, Window.LastHour, now)
      made <- calls.get
    } yield assertEquals(made, Vector("raw"))
  }

  test("an unproven window asks both, and the history half teaches retention") {
    val oldest = now.minusSeconds(10.days.toSeconds)
    for {
      calls <- Ref[IO].of(Vector.empty[String])
      r <- Retention.create
      p = SeriesProvider.asInstance(
        new StubSource(rowsFrom(oldest), hourlyStats(oldest), calls),
        r
      )
      _ <- p.series(SeriesIdentity.Instance, entity, Window.LastMonth, now)
      made <- calls.get
      learned <- r.observed
      _ <- IO(assertEquals(made.sorted, Vector("raw", "statistics:hour")))
      // Ten days of rows came back for a thirty-day question: that is what the
      // recorder holds, and it is now known without anyone configuring it.
      _ <- IO(assertEquals(learned.toDays, 10L))
      // ...so the same window still needs both (30d > 10d), but a week does not.
      coversWeek <- r.covers(Window.LastWeek)
    } yield assert(coversWeek)
  }

  test("the wider answer wins, which is how a purged window still draws") {
    // Raw history stops ten days back; statistics reaches the whole month.
    val oldRaw = now.minusSeconds(10.days.toSeconds)
    val fullStats = now.minusSeconds(30.days.toSeconds)
    for {
      calls <- Ref[IO].of(Vector.empty[String])
      r <- Retention.create
      p = SeriesProvider.asInstance(
        new StubSource(rowsFrom(oldRaw), hourlyStats(fullStats), calls),
        r
      )
      s <- p.series(SeriesIdentity.Instance, entity, Window.LastMonth, now)
    } yield assert(
      s.span.exists(_.toDays >= 29),
      clue = s.span.map(_.toDays)
    )
  }

  test("history wins a tie, because its resolution is finer") {
    val from = now.minusSeconds(7.days.toSeconds)
    for {
      calls <- Ref[IO].of(Vector.empty[String])
      r <- Retention.create
      p = SeriesProvider.asInstance(
        new StubSource(rowsFrom(from), hourlyStats(from), calls),
        r
      )
      s <- p.series(SeriesIdentity.Instance, entity, Window.LastWeek, now)
      // The raw series starts exactly at `from`; the statistics series is
      // plotted at bucket ENDS, so it starts an hour later and is narrower.
    } yield assertEquals(s.points.headOption.map(_.at), Some(from))
  }

  test("the result is downsampled before anyone sees it") {
    for {
      calls <- Ref[IO].of(Vector.empty[String])
      r <- Retention.create
      _ <- r.record(Some(now.minusSeconds(30.days.toSeconds)), now)
      p = SeriesProvider.asInstance(
        new StubSource(
          rowsFrom(now.minusSeconds(30.days.toSeconds)),
          Nil,
          calls
        ),
        r,
        target = 100
      )
      s <- p.series(SeriesIdentity.Instance, entity, Window.LastMonth, now)
    } yield assertEquals(s.points.length, 100)
  }

  // --- The store -----------------------------------------------------------

  private def countingProvider(
      fetches: Ref[IO, Int],
      delay: FiniteDuration = Duration.Zero
  ): SeriesProvider = new SeriesProvider {
    def identify(req: org.http4s.Request[IO]): IO[SeriesIdentity] =
      IO.pure(SeriesIdentity.Instance)
    def series(
        identity: SeriesIdentity,
        entityId: String,
        window: Window,
        asOf: Instant
    ): IO[Series] =
      IO.sleep(delay) *>
        fetches.update(_ + 1).as(Series(Vector(Series.Point(asOf, 1.0)), 0))
  }

  test("ten tabs asking at once cost one fetch") {
    for {
      fetches <- Ref[IO].of(0)
      store <- SeriesStore.create(countingProvider(fetches, 50.millis))
      _ <- List
        .fill(10)(
          store.get(SeriesIdentity.Instance, entity, Window.LastDay, now)
        )
        .parSequence
      count <- fetches.get
    } yield assertEquals(count, 1)
  }

  test("a new bucket is a new fetch, and retires the old key") {
    for {
      fetches <- Ref[IO].of(0)
      store <- SeriesStore.create(countingProvider(fetches))
      _ <- store.get(SeriesIdentity.Instance, entity, Window.LastDay, now)
      // Same bucket: still one fetch.
      _ <- store.get(
        SeriesIdentity.Instance,
        entity,
        Window.LastDay,
        now.plusSeconds(60)
      )
      afterSameBucket <- fetches.get
      _ <- IO(assertEquals(afterSameBucket, 1))
      // Next 5-minute bucket.
      _ <- store.get(
        SeriesIdentity.Instance,
        entity,
        Window.LastDay,
        now.plusSeconds(600)
      )
      afterRoll <- fetches.get
      keys <- store.keys
      _ <- IO(assertEquals(afterRoll, 2))
      // The superseded key is gone rather than accumulating: nothing can ask
      // for it again, since a key names its own bucket.
    } yield assertEquals(keys.size, 1)
  }

  test("two identities do not share an entry") {
    // The cache key carries identity so a per-user provider cannot leak by
    // omission — the sharing stops by construction rather than by someone
    // remembering to change the key at the same time.
    for {
      fetches <- Ref[IO].of(0)
      store <- SeriesStore.create(countingProvider(fetches))
      _ <- store.get(SeriesIdentity.Instance, entity, Window.LastDay, now)
      _ <- store.get(SeriesIdentity.user("alice"), entity, Window.LastDay, now)
      count <- fetches.get
    } yield assertEquals(count, 2)
  }

  test("a failure is not cached") {
    for {
      attempts <- Ref[IO].of(0)
      provider = new SeriesProvider {
        def identify(req: org.http4s.Request[IO]): IO[SeriesIdentity] =
          IO.pure(SeriesIdentity.Instance)
        def series(
            identity: SeriesIdentity,
            entityId: String,
            window: Window,
            asOf: Instant
        ): IO[Series] =
          attempts.getAndUpdate(_ + 1).flatMap {
            case 0 => IO.raiseError(new RuntimeException("HA is down"))
            case _ => IO.pure(Series.empty)
          }
      }
      store <- SeriesStore.create(provider)
      first <- store
        .get(SeriesIdentity.Instance, entity, Window.LastDay, now)
        .attempt
      _ <- IO(assert(first.isLeft))
      // The retry is the point: a series that failed because HA blinked should
      // come back when it stops, not at the next bucket.
      second <- store.get(SeriesIdentity.Instance, entity, Window.LastDay, now)
      count <- attempts.get
      _ <- IO(assertEquals(second, Series.empty))
    } yield assertEquals(count, 2)
  }
}
