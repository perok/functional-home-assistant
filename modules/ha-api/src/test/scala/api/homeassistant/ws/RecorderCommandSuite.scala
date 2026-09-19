package api.homeassistant.ws

import api.homeassistant.ws.domain.{
  HistoryPoint,
  StatisticPoint,
  StatisticsPeriod
}
import api.homeassistant.HomeAssistantApi
import api.homeassistant.ws.protocol.client.CommandPhase
import api.homeassistant.ws.testkit.FakeHaSocket
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.implicits.*

import java.time.Instant
import scala.concurrent.duration.*

/** The two recorder commands: what goes on the wire, and what comes back.
  *
  * Every expectation here was taken from a live instance (HA core 2026.9.3)
  * rather than from the docs, because the docs do not mention that
  * `history/history_during_period` is on the WS API at all, and because both of
  * the shapes that actually bite — a rejected null and two different time units
  * in one feature — are invisible in prose.
  */
class RecorderCommandSuite extends munit.FunSuite {

  private val start = Instant.parse("2026-09-18T12:00:00Z")
  private val end = Instant.parse("2026-09-19T12:00:00Z")

  private def wire(c: CommandPhase): String = c.asJson.noSpaces

  test(
    "an open-ended statistics window omits end_time rather than nulling it"
  ) {
    // HA answers `invalid_format: expected str at 'end_time'. Got None` for an
    // explicit null, and succeeds when the field is simply absent — measured
    // both ways. A derived encoder writes the null, so this is the guard on
    // the override that drops it.
    val json = wire(
      CommandPhase.`recorder/statistics_during_period`(
        start,
        None,
        List("sensor.t"),
        StatisticsPeriod.Hour
      )
    )
    assert(!json.contains("end_time"), clue = json)
    assert(json.contains(""""period":"hour""""), clue = json)
  }

  test("a closed statistics window sends end_time") {
    val json = wire(
      CommandPhase.`recorder/statistics_during_period`(
        start,
        Some(end),
        List("sensor.t"),
        StatisticsPeriod.FiveMinute
      )
    )
    assert(json.contains(""""end_time":"2026-09-19T12:00:00Z""""), clue = json)
    assert(json.contains(""""period":"5minute""""), clue = json)
  }

  test("every period HA accepts has a case, spelled HA's way") {
    // The failure is a whole rejected command naming the alternatives, so the
    // list is worth pinning against HA's own error text:
    // "expected '5minute' or 'hour' or 'day' or 'week' or 'month' or 'year'".
    assertEquals(
      StatisticsPeriod.values.map(_.wire).toList,
      List("5minute", "hour", "day", "week", "month", "year")
    )
  }

  test("history asks for the compact row shape by default") {
    // Not a tuning knob: `HistoryPoint` decodes only this shape, and the full
    // one repeats every attribute on every point — 37 KB against 8 KB for the
    // same 223 points.
    val json = wire(
      CommandPhase.`history/history_during_period`(
        start,
        end,
        List("sensor.t")
      )
    )
    assert(json.contains(""""minimal_response":true"""), clue = json)
    assert(json.contains(""""no_attributes":true"""), clue = json)
  }

  test("a history point reads epoch SECONDS, fraction included") {
    // `lu` is seconds-with-a-fraction here and milliseconds in statistics. The
    // sub-second part is not decoration: the recorder writes several rows per
    // second for a chatty sensor, and truncating would collapse them.
    val point = decode[HistoryPoint]("""{"s":"23.1","lu":1789755910.543}""")
    assertEquals(
      point,
      Right(HistoryPoint("23.1", Instant.ofEpochSecond(1789755910, 543000000)))
    )
  }

  test("a non-numeric state is a state, not a gap") {
    // The recorder stores "unavailable"/"unknown" as ordinary rows. Decoding
    // them to None here would lose the difference between a sensor that said
    // nothing and one that was not reporting.
    assertEquals(
      decode[HistoryPoint]("""{"s":"unavailable","lu":1.0}""").map(_.state),
      Right("unavailable")
    )
  }

  test("lc stands in when lu is absent") {
    assertEquals(
      decode[HistoryPoint]("""{"s":"on","lc":10.0}""").map(_.at),
      Right(Instant.ofEpochSecond(10))
    )
  }

  test("a point with no timestamp at all is a decode failure") {
    assert(decode[HistoryPoint]("""{"s":"on"}""").isLeft)
  }

  test("a measurement bucket decodes its band, in MILLISECONDS") {
    val json =
      """{"start":1789758000000,"end":1789761600000,"max":23.4,
         |"mean":22.949893353694446,"min":22.6,"last_reset":null}""".stripMargin
    assertEquals(
      decode[StatisticPoint](json),
      Right(
        StatisticPoint(
          Instant.ofEpochMilli(1789758000000L),
          Instant.ofEpochMilli(1789761600000L),
          Some(StatisticPoint.Mean(22.6, 22.949893353694446, 23.4)),
          None
        )
      )
    )
  }

  test("a total bucket decodes its sum, and carries no band") {
    val json =
      """{"start":1789758000000,"end":1789761600000,"state":6.876,
         |"last_reset":1789754413449,"sum":77770.16,"change":2.865}""".stripMargin
    assertEquals(
      decode[StatisticPoint](json).map(p => (p.mean, p.sum)),
      Right(
        (
          None,
          Some(
            StatisticPoint.Sum(
              6.876,
              77770.16,
              Some(2.865),
              Some(Instant.ofEpochMilli(1789754413449L))
            )
          )
        )
      )
    )
  }

  test("the two groups are independent, so a bucket may carry both") {
    // `has_mean` and `has_sum` are separate flags on `list_statistic_ids`. On
    // a real instance they never overlap (205 mean-only, 134 sum-only, 0 both)
    // because a recorder sensor derives them from its one `state_class` — but
    // that is an observation, not a promise, so a sum type here could silently
    // drop half of an external statistic.
    val json =
      """{"start":0,"end":1,"min":1.0,"mean":2.0,"max":3.0,
         |"state":4.0,"sum":5.0}""".stripMargin
    val point = decode[StatisticPoint](json)
    assertEquals(point.map(_.mean.isDefined), Right(true))
    assertEquals(point.map(_.sum.isDefined), Right(true))
  }

  test("a bucket carrying neither is rejected rather than kept empty") {
    assert(decode[StatisticPoint]("""{"start":0,"end":1}""").isLeft)
  }

  test("a rejected recorder command raises HA's own error") {
    // The two ways to get this wrong — a null `end_time` and an unknown
    // `period` — are both `invalid_format`, and both arrive as a failure frame
    // rather than as an empty result. A caller must be able to tell that apart
    // from the empty map that means "no rows".
    val raised = FakeHaSocket
      .create()
      .flatMap { fake =>
        HAWSApiLowLevel(
          fake.client,
          uri"ws://ha.test/api/websocket",
          FakeHaSocket.Token
        ).use { ll =>
          fake.reject("recorder/statistics_during_period") *>
            HomeAssistantApi
              .fromWs(ll)
              .statisticsDuringPeriod(
                start,
                None,
                List("sensor.t"),
                StatisticsPeriod.Hour
              )
              .attempt
        }
      }
      .timeout(30.seconds)
      .unsafeRunSync()
    assert(raised.isLeft, clue = raised)
  }

  test("the command round-trips through the transport") {
    // The unit checks above pin the two halves; this pins that they are the
    // SAME command — the encoder's field names reach the socket and the
    // decoder reads what comes back on that id.
    val (sent, got) = FakeHaSocket
      .create()
      .flatMap { fake =>
        HAWSApiLowLevel(
          fake.client,
          uri"ws://ha.test/api/websocket",
          FakeHaSocket.Token
        ).use { ll =>
          for {
            _ <- fake.hold("history/history_during_period")
            fiber <- HomeAssistantApi
              .fromWs(ll)
              .historyDuringPeriod(start, end, List("sensor.t"))
              .start
            _ <- IO.sleep(100.millis)
            commands <- fake.sentCommands
            id <- fake.idOf(commands.size)
            _ <- fake.emit(
              Json.obj(
                "id" -> Json.fromInt(id),
                "type" -> Json.fromString("result"),
                "success" -> Json.True,
                "result" -> Json.obj(
                  "sensor.t" -> Json.arr(
                    io.circe.parser
                      .parse("""{"s":"1.5","lu":1789755910.543}""")
                      .getOrElse(Json.Null)
                  )
                )
              )
            )
            result <- fiber.joinWithNever
          } yield (commands.last, result)
        }
      }
      .timeout(30.seconds)
      .unsafeRunSync()

    assertEquals(
      sent.hcursor.get[String]("type"),
      Right("history/history_during_period")
    )
    assertEquals(
      sent.hcursor.get[String]("start_time"),
      Right("2026-09-18T12:00:00Z")
    )
    assertEquals(
      got,
      Map(
        "sensor.t" -> List(
          HistoryPoint("1.5", Instant.ofEpochSecond(1789755910, 543000000))
        )
      )
    )
  }

  test("an incomplete band is not half a band") {
    // Two of three bounds is not a band anyone can draw, and grouping is what
    // makes that unrepresentable rather than a caller's `.get`.
    assertEquals(
      decode[StatisticPoint](
        """{"start":0,"end":1,"min":1.0,"mean":2.0,"sum":9.0,"state":8.0}"""
      )
        .map(_.mean),
      Right(None)
    )
  }
}
