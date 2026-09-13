package fh.view.runtime

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fh.view.testkit.{FakeHomeAssistant, FixtureEntity}
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json

import scala.concurrent.duration.*

/** The reconnect behaviour of the self-healing feed — the one thing the
  * fixture-backed functional suites can't cover, because their `Connect` never
  * closes. Here a controllable `Connect` lets us force a drop.
  *
  * Two properties, and the split between them IS the design: the STORE is
  * refilled by the supervisor with no help from its reader, while an external
  * subscriber's stream ends with its connection and it re-subscribes off
  * `healthy` (the pattern `ServerApp.watchRegistryEvents` uses). Subscriptions
  * are deliberately not durable — see `HaFeed.routingFacade`.
  */
class HaFeedSuite extends munit.CatsEffectSuite {

  /** A `Connect` whose connection can be closed on demand, reporting how many
    * times it has been established.
    */
  private def controllable(fake: FakeHomeAssistant) =
    for {
      closeRef <- Ref[IO].of(Option.empty[Deferred[IO, Unit]])
      uses <- SignallingRef[IO].of(0)
      connect: HaFeed.Connect = Resource.eval(
        for {
          d <- Deferred[IO, Unit]
          _ <- closeRef.set(Some(d))
          _ <- uses.update(_ + 1)
        } yield (fake, d.get) // awaitClosed completes cleanly when we fire `d`
      )
      // Drop the live connection cleanly: the fake ends this generation's
      // subscriptions (as the real transport does), and `awaitClosed` fires so
      // the supervisor reconnects.
      drop = fake.dropConnection *> closeRef.get
        .flatMap(_.get.complete(()))
        .void
    } yield (connect, uses, drop)

  test(
    "a peer that accepts and closes at once cannot spin the reconnect loop"
  ) {
    // The case that used to bypass the backoff entirely: a CLEAN close. The
    // retry policy only ever saw raises, so an end that merely returned sent
    // the loop straight back round with no delay — connect, auth, close,
    // repeat, at wire speed.
    (for {
      fake <- FakeHomeAssistant.create(
        List(FixtureEntity("light.kitchen", "off"))
      )
      closeRef <- Ref[IO].of(Option.empty[Deferred[IO, Unit]])
      instant <- Ref[IO].of(false)
      uses <- SignallingRef[IO].of(0)
      connect: HaFeed.Connect = Resource.eval(
        for {
          d <- Deferred[IO, Unit]
          _ <- closeRef.set(Some(d))
          _ <- uses.update(_ + 1)
          // The first connection stays up (the feed's acquisition waits for its
          // opening state); every one after it closes the moment it is up.
          _ <- instant.get.flatMap(now => d.complete(()).whenA(now))
        } yield (fake, d.get)
      )
      attempts <- HaFeed.resource(connect).use { _ =>
        for {
          _ <- instant.set(true)
          _ <- fake.dropConnection *> closeRef.get
            .flatMap(_.get.complete(()))
            .void
          _ <- IO.sleep(2.seconds)
          n <- uses.get
        } yield n
      }
      // Two seconds of instant closes buys a couple of attempts at a 1s minimum
      // backoff, doubling. Unthrottled it was thousands, so the bound is loose
      // on purpose — it discriminates by orders of magnitude, not by timing.
    } yield assert(attempts <= 6, clue = attempts)).timeout(30.seconds)
  }

  test("the store keeps being refilled across a reconnect") {
    (for {
      fake <- FakeHomeAssistant.create(
        List(FixtureEntity("light.kitchen", "off"))
      )
      (connect, uses, drop) <- controllable(fake)
      out <- HaFeed.resource(connect).use { feed =>
        for {
          _ <- drop
          _ <- uses.discrete.find(_ >= 2).head.compile.drain
          // The new connection's opening full set is the catch-up, and the pump
          // rides it: the store's reader does nothing to make that happen.
          _ <- feed.healthy.discrete.find(identity).head.compile.drain
          _ <- fake.emit("light.kitchen", "on")
          state <- Stream
            .repeatEval(feed.store.snapshot.map(_.get("light.kitchen")))
            .metered(10.millis)
            .find(_.exists(_.state == "on"))
            .head
            .compile
            .lastOrError
            .timeout(10.seconds)
        } yield state.map(_.state)
      }
    } yield assertEquals(out, Some("on"))).timeout(30.seconds)
  }

  test(
    "an external subscriber re-subscribes on healthy and spans a reconnect"
  ) {
    (for {
      fake <- FakeHomeAssistant.create(Nil)
      (connect, uses, drop) <- controllable(fake)
      received <- Ref[IO].of(Vector.empty[Json])
      out <- HaFeed.resource(connect).use { feed =>
        // Exactly what ServerApp.watchRegistryEvents does: one subscription per
        // connection, restarted when the link comes back.
        val watch = feed.healthy.discrete
          .filter(identity)
          .switchMap(_ => Stream.resource(feed.api.rawEvents("test")).flatten)
          .evalMap(j => received.update(_ :+ j))

        def delivered(j: Json) = Stream
          .repeatEval(received.get)
          .metered(10.millis)
          .find(_.contains(j))
          .head
          .compile
          .drain
          .timeout(10.seconds)

        watch.compile.drain.background.surround {
          for {
            _ <- fake.awaitEventSubscribes(1)
            _ <- fake.pushRawEvent("test", Json.fromString("one"))
            // Observe delivery BEFORE dropping. Interrupting a queue-backed
            // stream can swallow an element it has taken but not yet emitted —
            // the same in-flight loss the real transport has at a dying
            // connection, so the test must not depend on winning that race.
            // In production that loss is harmless: it can only happen at a
            // disconnect, and every reconnect re-derives (full state set /
            // dump refresh). Here there is nothing to re-derive from, so the
            // test observes instead.
            _ <- delivered(Json.fromString("one"))
            _ <- drop
            _ <- uses.discrete.find(_ >= 2).head.compile.drain
            // The re-subscribe is what proves the stream ended and restarted.
            _ <- fake.awaitEventSubscribes(2)
            _ <- fake.pushRawEvent("test", Json.fromString("two"))
            _ <- delivered(Json.fromString("two"))
            got <- received.get
          } yield got.toList
        }
      }
    } yield assertEquals(
      out,
      List(Json.fromString("one"), Json.fromString("two"))
    )).timeout(30.seconds)
  }

  test("the subscription is re-opened, narrowed, when the wanted set changes") {
    (for {
      fake <- FakeHomeAssistant.create(
        List(
          FixtureEntity("light.kitchen", "off"),
          FixtureEntity("sensor.unwatched", "1")
        )
      )
      (connect, _, _) <- controllable(fake)
      // Boot value: nothing is built yet, so nothing knows what matters.
      wanted <- SignallingRef[IO].of(Option.empty[Set[String]])
      asked <- HaFeed.resource(connect, wanted).use { feed =>
        for {
          _ <- fake.awaitEntitySubscribes(1)
          _ <- wanted.set(Some(Set("light.kitchen")))
          _ <- fake.awaitEntitySubscribes(2)
          // The narrowed feed still fills the store — the re-subscribe's own
          // opening frame is the catch-up, exactly as a reconnect's is.
          _ <- fake.emit("light.kitchen", "on")
          _ <- Stream
            .repeatEval(feed.store.snapshot.map(_.get("light.kitchen")))
            .metered(10.millis)
            .find(_.exists(_.state == "on"))
            .head
            .compile
            .drain
          got <- fake.entitySubscriptions
        } yield got.toList
      }
    } yield assertEquals(
      asked,
      List(None, Some(List("light.kitchen")))
    )).timeout(30.seconds)
  }

  test("an empty wanted set opens no subscription at all") {
    // The trap this exists for: HA reads an empty `entity_ids` as NO FILTER, so
    // sending one would subscribe to the whole house at the exact moment we
    // want none of it. Declining to subscribe is the only correct spelling.
    (for {
      fake <- FakeHomeAssistant.create(
        List(FixtureEntity("light.kitchen", "off"))
      )
      (connect, _, _) <- controllable(fake)
      wanted <- SignallingRef[IO].of(Option.empty[Set[String]])
      asked <- HaFeed.resource(connect, wanted).use { _ =>
        for {
          _ <- fake.awaitEntitySubscribes(1)
          _ <- wanted.set(Some(Set.empty))
          // Nothing to await — the assertion is that nothing HAPPENS — so give
          // a wrong implementation time to open the subscription it should not.
          _ <- IO.sleep(200.millis)
          got <- fake.entitySubscriptions
        } yield got.toList
      }
    } yield assertEquals(asked, List(None))).timeout(30.seconds)
  }
}
