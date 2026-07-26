package api.homeassistant.ws

import api.homeassistant.ws.protocol.client.CommandPhase
import api.homeassistant.ws.testkit.FakeHaSocket
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Uri
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** The WS transport itself, against [[FakeHaSocket]] — the real
  * [[HAWSApiLowLevel]] running over a stubbed socket, so everything between a
  * frame and a decoded value is exercised: the auth handshake, id routing,
  * ack-then-events ordering, coalesced framing, teardown, and the liveness paths
  * (`awaitClosed` via socket close, missed pong, or a frame that will not
  * decode).
  *
  * The dashboard suites stub `HAWSApiLowLevel` itself, so none of this is
  * reachable from them; a regression here would surface as a mysteriously dead
  * dashboard rather than a failing test.
  */
class HAWSApiLowLevelSuite extends munit.CatsEffectSuite {

  private val uri: Uri = uri"ws://ha.test/api/websocket"

  /** The real low level over the fake socket. Ping cadence is tightened so the
    * keepalive test does not take 40 seconds.
    */
  private def connect(
      fake: FakeHaSocket,
      pingInterval: FiniteDuration = 1.hour,
      pingTimeout: FiniteDuration = 200.millis
  ): Resource[IO, HAWSApiLowLevel[IO]] =
    HAWSApiLowLevel(
      fake.client,
      uri,
      FakeHaSocket.Token,
      pingInterval = pingInterval,
      pingTimeout = pingTimeout
    )

  private def withConn[A](
      f: (FakeHaSocket, HAWSApiLowLevel[IO]) => IO[A]
  ): IO[A] =
    FakeHaSocket
      .create()
      .flatMap(fake => connect(fake).use(ll => f(fake, ll)))
      .timeout(30.seconds)

  test("authenticates, then enables coalescing as its first command") {
    withConn { (fake, _) =>
      fake.sentCommands.map { sent =>
        // Auth is not a command (no id), so the first thing recorded is the
        // feature-enablement phase — everything after it is array-framed.
        assertEquals(
          sent.headOption.flatMap(_.hcursor.get[String]("type").toOption),
          Some("supported_features")
        )
        assertEquals(
          sent.head.hcursor
            .downField("features")
            .get[Int]("coalesce_messages")
            .toOption,
          Some(1)
        )
      }
    }
  }

  test("a bad token fails the connection instead of hanging") {
    FakeHaSocket
      .create(token = "the-real-one")
      .flatMap { fake =>
        HAWSApiLowLevel(fake.client, uri, "wrong").use_.attempt
      }
      .timeout(30.seconds)
      .map(r => assert(r.isLeft, r))
  }

  test("responses route by id, whatever order they arrive in") {
    withConn { (fake, ll) =>
      // Two in-flight commands answered BACKWARDS. Only id routing gets this
      // right; anything positional hands each caller the other's reply.
      val callOne = ll.sendCommand(CommandPhase.get_config())
      val callTwo = ll.sendCommand(CommandPhase.get_config())
      for {
        _ <- fake.hold("get_config")
        fiber <- (callOne, callTwo).parTupled.start
        // Wait for both to be on the wire (supported_features is command 1).
        _ <- waitFor(fake.sentCommands.map(_.size >= 3))
        idA <- fake.idOf(2)
        idB <- fake.idOf(3)
        _ <- fake.emit(
          result(idB, Json.fromString("B")),
          result(idA, Json.fromString("A"))
        )
        out <- fiber.joinWithNever
      } yield assertEquals(out, (Json.fromString("A"), Json.fromString("B")))
    }
  }

  test("subscribe acquire waits for the ack; a rejection raises there") {
    FakeHaSocket
      .create()
      .flatMap { fake =>
        connect(fake).use { ll =>
          fake.reject("subscribe_events") *>
            ll.subscribeStream(CommandPhase.subscribe_events(Some("x")))
              .use_
              .attempt
        }
      }
      .timeout(30.seconds)
      .map {
        case Left(err) =>
          // HA's own error, surfaced at ACQUIRE — not swallowed into a stream
          // that simply never emits.
          assert(err.getMessage.contains("not allowed"), err.getMessage)
        case Right(_) => fail("a rejected subscription must raise")
      }
  }

  test("an ack sharing a frame with its first event keeps both, in order") {
    withConn { (fake, ll) =>
      // The exact shape captured from 2026.7.2 and the reason acquire and the
      // stream must read ONE queue: the ack is consumed by the acquire, the
      // event must still reach the stream.
      val sub = ll
        .subscribeStream(CommandPhase.subscribe_events(Some("x")))
        .use(_.head.compile.lastOrError)
      for {
        // Hold the ack so the test, not the fake, decides it shares a frame
        // with the first event.
        _ <- fake.hold("subscribe_events")
        fiber <- sub.start
        _ <- waitFor(fake.sentCommands.map(_.size >= 2))
        id <- fake.idOf(2)
        _ <- fake.emit(fake.ack(id), fake.event(id, payload("first")))
        got <- fiber.joinWithNever
      } yield assertEquals(got, payload("first"))
    }
  }

  test("a coalesced burst delivers every payload, in order") {
    withConn { (fake, ll) =>
      // One ARRAY frame carrying five events must fan out to five stream
      // elements in order — the framing change `coalesce_messages` makes
      // unconditionally, and the thing a naive one-payload-per-frame decoder
      // would silently collapse to one.
      //
      // Note what is NOT asserted: that they arrive as a single fs2 chunk. The
      // frame decodes to one chunk, but id-routing hands the payloads to the
      // per-id queue one at a time, so the consumer re-batches opportunistically
      // (`HaFeed.pump`'s one-update-per-burst is a best-effort optimization,
      // never a guarantee).
      ll.subscribeStream(CommandPhase.subscribe_events(Some("x"))).use {
        events =>
          for {
            fiber <- events.take(5).compile.toList.start
            id <- fake.idOf(2)
            _ <- fake.emit((1 to 5).map(i => fake.event(id, payload(s"e$i")))*)
            got <- fiber.joinWithNever
          } yield assertEquals(got, (1 to 5).map(i => payload(s"e$i")).toList)
      }
    }
  }

  test("release unsubscribes with the subscription's own id") {
    withConn { (fake, ll) =>
      for {
        id <- ll
          .subscribeStream(CommandPhase.subscribe_events(Some("x")))
          .use(_ => fake.idOf(2))
        sent <- fake.sentCommands
        unsub = sent.filter(c =>
          c.hcursor.get[String]("type").contains("unsubscribe_events")
        )
      } yield {
        assertEquals(unsub.size, 1)
        assertEquals(unsub.head.hcursor.get[Int]("subscription").toOption, Some(id))
      }
    }
  }

  test("a closed socket completes awaitClosed rather than hanging") {
    withConn { (fake, ll) =>
      fake.close *> ll.awaitClosed.attempt.timeout(5.seconds).map { r =>
        // A clean end returns unit; the point is that it RETURNS.
        assert(r.isRight || r.isLeft, r)
      }
    }
  }

  test("a missed pong marks the connection dead") {
    FakeHaSocket
      .create()
      .flatMap { fake =>
        connect(fake, pingInterval = 100.millis, pingTimeout = 200.millis).use {
          ll => fake.stopAnsweringPings *> ll.awaitClosed.attempt
        }
      }
      .timeout(30.seconds)
      .map {
        case Left(err) =>
          assert(err.getMessage.contains("ping timed out"), err.getMessage)
        case Right(_) => fail("a missed pong must report the connection dead")
      }
  }

  test("a frame that will not decode ends the connection, it does not wedge") {
    // The receive loop is the only reader of the socket, so a decode failure
    // that killed it silently would strand every caller forever. It must report
    // through awaitClosed so the supervisor above can reconnect.
    withConn { (fake, ll) =>
      fake.emitFrame("{ this is not json") *>
        ll.awaitClosed.attempt.timeout(5.seconds).map(r => assert(r.isLeft, r))
    }
  }

  private def payload(tag: String): Json =
    Json.obj("tag" -> tag.asJson)

  private def result(id: Int, value: Json): Json = Json.obj(
    "id" -> id.asJson,
    "type" -> "result".asJson,
    "success" -> true.asJson,
    "result" -> value
  )

  /** Poll a condition — the client allocates ids on its own fiber, so a test
    * that needs one has to wait for the send to land.
    */
  private def waitFor(cond: IO[Boolean]): IO[Unit] =
    Stream
      .repeatEval(cond)
      .metered(5.millis)
      .find(identity)
      .head
      .compile
      .drain
      .timeout(5.seconds)
}
