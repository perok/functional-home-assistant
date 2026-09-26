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

/** The two recorder commands, with every expectation taken from a live instance
  * (HA core 2026.9.3): the docs omit that `history/history_during_period` is on
  * the WS API, and the shapes that bite (a rejected null, two time units in one
  * feature) are invisible in prose.
  */
class RecorderCommandSuite extends munit.FunSuite {

  private val start = Instant.parse("2026-09-18T12:00:00Z")
  private val end = Instant.parse("2026-09-19T12:00:00Z")

  private def wire(c: CommandPhase): String = c.asJson.noSpaces

  test(
    "an open-ended statistics window omits end_time rather than nulling it"
  ) {
    // HA answers `invalid_format: expected str at 'end_time'. Got None` for an
    // explicit null and succeeds on an absent field, measured both ways. A
    // derived encoder writes the null; this guards the override that drops it.
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
    // A wrong value rejects the whole command; HA's own text: "expected
    // '5minute' or 'hour' or 'day' or 'week' or 'month' or 'year'".
    assertEquals(
      StatisticsPeriod.values.map(_.wire).toList,
      List("5minute", "hour", "day", "week", "month", "year")
    )
  }

  test("history asks for the compact row shape by default") {
    // `HistoryPoint` decodes only this shape; the full one repeats every
    // attribute per point, 37 KB against 8 KB for 223 points.
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
    // Seconds with a fraction here, milliseconds in statistics. A chatty sensor
    // writes several rows per second, so truncating would collapse them.
    val point = decode[HistoryPoint]("""{"s":"23.1","lu":1789755910.543}""")
    assertEquals(
      point,
      Right(HistoryPoint("23.1", Instant.ofEpochSecond(1789755910, 543000000)))
    )
  }

  test("a non-numeric state is a state, not a gap") {
    // As None, a sensor that said nothing and one not reporting would look
    // alike.
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
    // Observed never to overlap (205 mean-only, 134 sum-only, 0 both), but that
    // is not a promise, and a sum type could silently drop half an external
    // statistic.
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
    // Both arrive as a failure frame, which a caller must tell apart from the
    // empty map meaning "no rows".
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
    // That the two halves are one command: the encoder's names reach the socket
    // and the decoder reads the reply on that id.
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
    // Two of three bounds is no drawable band; grouping makes it
    // unrepresentable.
    assertEquals(
      decode[StatisticPoint](
        """{"start":0,"end":1,"min":1.0,"mean":2.0,"sum":9.0,"state":8.0}"""
      )
        .map(_.mean),
      Right(None)
    )
  }
}
