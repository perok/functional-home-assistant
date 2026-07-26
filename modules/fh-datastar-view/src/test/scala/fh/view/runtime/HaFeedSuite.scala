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
        // Acquire means SUBSCRIBED, so an event pushed after it is attributable
        // to the connection that was live at the time.
        feed.api.rawEvents("test_registry").use { events =>
          // The queue-backed stream can be consumed in stages; each run takes
          // from where the last left off.
          val next = events.head.compile.lastOrError.timeout(5.seconds)
          for {
            _ <- fake.pushRawEvent("test_registry", Json.fromString("one"))
            // Read it BEFORE dropping. The reconnect seam is lossy by design
            // (an event taken from the dying connection but not yet handed to
            // the durable queue goes with it), and this test is about surviving
            // the reconnect — not about that gap.
            one <- next
            // Drop the live connection cleanly -> supervisor reconnects (a
            // second `connect` use), and the durable subscription re-arms.
            _ <- closeRef.get.flatMap(_.get.complete(()))
            _ <- uses.discrete.find(_ >= 2).head.compile.drain
            // Reconnected is not yet re-subscribed, and the gap between them is
            // the lossy seam — wait for the durable side to actually re-arm.
            _ <- fake.awaitEventSubscribes(2)
            // Pushed AFTER the drop: only a durable (re-subscribing) stream
            // still delivers it.
            _ <- fake.pushRawEvent("test_registry", Json.fromString("two"))
            two <- next
          } yield List(one, two)
        }
      }
    } yield assertEquals(
      out,
      List(Json.fromString("one"), Json.fromString("two"))
    )).timeout(30.seconds)
  }
}
