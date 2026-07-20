package fh.view.runtime

import cats.effect.{Deferred, IO, Ref, Resource}
import fh.view.testkit.FakeHomeAssistant
import fs2.concurrent.SignallingRef
import io.circe.Json

import scala.concurrent.duration.*

/** The reconnect behaviour of the self-healing feed — the one thing the
  * fixture-backed functional suites can't cover, because their `Connect` never
  * closes. Here a controllable `Connect` lets us force a drop and assert the
  * DURABLE facade re-subscribes: a long-lived `rawEvents` subscriber (the
  * registry watch in production) keeps receiving across a reconnect without
  * re-subscribing itself.
  */
class HaFeedSuite extends munit.CatsEffectSuite {

  test("a durable rawEvents subscription survives a reconnect") {
    (for {
      fake <- FakeHomeAssistant.create(Nil)
      // The current connection's clean-close signal, replaced on each connect.
      closeRef <- Ref[IO].of(Option.empty[Deferred[IO, Unit]])
      // How many times `connect` has been used — i.e. connection generation.
      uses <- SignallingRef[IO].of(0)
      connect: HaFeed.Connect = Resource.eval(
        for {
          d <- Deferred[IO, Unit]
          _ <- closeRef.set(Some(d))
          _ <- uses.update(_ + 1)
        } yield (fake, d.get) // awaitClosed completes cleanly when we fire `d`
      )
      out <- HaFeed.resource(connect).use { feed =>
        feed.api.rawEvents("test_registry").use { q =>
          for {
            _ <- feed.awaitHealthy
            _ <- fake.pushRawEvent("test_registry", Json.fromString("one"))
            got1 <- q.take.timeout(5.seconds)
            // Drop the live connection cleanly -> supervisor reconnects (a
            // second `connect` use), and the durable subscription re-arms.
            _ <- closeRef.get.flatMap(_.get.complete(()))
            _ <- uses.discrete.find(_ >= 2).head.compile.drain
            // Pushed AFTER the drop: only a durable (re-subscribing) stream
            // still delivers it.
            _ <- fake.pushRawEvent("test_registry", Json.fromString("two"))
            got2 <- q.take.timeout(5.seconds)
          } yield (got1, got2)
        }
      }
    } yield assertEquals(
      out,
      (Json.fromString("one"), Json.fromString("two"))
    )).timeout(30.seconds)
  }
}
