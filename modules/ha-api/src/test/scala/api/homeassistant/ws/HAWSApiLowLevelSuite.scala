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
  * ack-then-events ordering, coalesced framing, teardown, and the liveness
  * paths (`awaitClosed` via socket close, missed pong, or a frame that will not
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
      val call = ll.sendCommand(CommandPhase.get_config())
      for {
        _ <- fake.hold("get_config")
        // Started one at a time: two identical commands racing to the drain
        // fiber would make WHICH id belongs to which caller a coin flip, and
        // the test would be asserting the coin rather than the routing.
        // (supported_features is command 1.)
        first <- call.start
        _ <- waitFor(fake.sentCommands.map(_.size >= 2))
        second <- call.start
        _ <- waitFor(fake.sentCommands.map(_.size >= 3))
        idA <- fake.idOf(2)
        idB <- fake.idOf(3)
        // Answered BACKWARDS, in one coalesced frame. Only id routing gets this
        // right; anything positional hands each caller the other's reply.
        _ <- fake.emit(
          result(idB, Json.fromString("B")),
          result(idA, Json.fromString("A"))
        )
        a <- first.joinWithNever
        b <- second.joinWithNever
      } yield assertEquals((a, b), (Json.fromString("A"), Json.fromString("B")))
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

  test("a coalesced burst reaches the consumer as ONE chunk") {
    withConn { (fake, ll) =>
      // The point of coalesce_messages: a tick's worth of events stays a single
      // batch end to end, so `HaFeed.pump` folds it into one store update. This
      // is a guarantee, not an optimization that usually fires — routing groups
      // a frame's payloads by id and offers each group as one chunk. Asserting
      // on chunks is the only way to see that from outside; a consumer that
      // re-batched whatever happened to be queued would pass an
      // order-and-contents test while failing this.
      ll.subscribeStream(CommandPhase.subscribe_events(Some("x"))).use {
        events =>
          for {
            fiber <- events.chunks.head.compile.lastOrError.start
            id <- fake.idOf(2)
            _ <- fake.emit((1 to 5).map(i => fake.event(id, payload(s"e$i")))*)
            chunk <- fiber.joinWithNever
          } yield {
            assertEquals(chunk.size, 5)
            assertEquals(
              chunk.toList,
              (1 to 5).map(i => payload(s"e$i")).toList
            )
          }
      }
    }
  }

  test("events sharing the ack's frame stay batched with the ones after it") {
    withConn { (fake, ll) =>
      // The boundary case: acquire consumes the ack out of a frame that also
      // carried events. The remainder must reach the stream AS A CHUNK, not be
      // replayed one at a time.
      val sub = ll.subscribeStream(CommandPhase.subscribe_events(Some("x")))
      for {
        _ <- fake.hold("subscribe_events")
        fiber <- sub.use(_.chunks.head.compile.lastOrError).start
        _ <- waitFor(fake.sentCommands.map(_.size >= 2))
        id <- fake.idOf(2)
        _ <- fake.emit(
          fake.ack(id) +: (1 to 3).map(i => fake.event(id, payload(s"e$i")))*
        )
        chunk <- fiber.joinWithNever
      } yield assertEquals(chunk.size, 3)
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
        assertEquals(
          unsub.head.hcursor.get[Int]("subscription").toOption,
          Some(id)
        )
      }
    }
  }

  test("a closed socket TERMINATES the subscription stream") {
    withConn { (fake, ll) =>
      // Without this the stream would simply block on a queue nothing can feed
      // again — a silent stall, indistinguishable from a quiet house. The
      // per-route `None` sentinel ends it, so a consumer sees the connection
      // end and can re-subscribe.
      ll.subscribeStream(CommandPhase.subscribe_events(Some("x"))).use {
        events =>
          for {
            fiber <- events.compile.toList.start
            id <- fake.idOf(2)
            _ <- fake.emit(fake.event(id, payload("last")))
            _ <- fake.close
            got <- fiber.joinWithNever.timeout(5.seconds)
          } yield assertEquals(got, List(payload("last")))
      }
    }
  }

  test("a socket dying mid-command fails the caller instead of hanging it") {
    withConn { (fake, ll) =>
      // A `call_service` racing a disconnect used to block forever: the reply
      // could never arrive and nothing closed the route.
      for {
        _ <- fake.hold("get_config")
        fiber <- ll.sendCommand(CommandPhase.get_config()).attempt.start
        _ <- waitFor(fake.sentCommands.map(_.size >= 2))
        _ <- fake.close
        got <- fiber.joinWithNever.timeout(5.seconds)
      } yield assert(got.isLeft, got)
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
          assert(err.getMessage.contains("ping went unanswered"), err.getMessage)
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
