package fh.view.query

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** What both the series cache and the staged-value cache rely on: one
  * computation per key that no single asker can strand.
  */
class SharedCacheSuite extends munit.CatsEffectSuite {

  private def cache = SharedCache.create[String, Int]((_, _) => true)

  test("an asker that gives up does not strand the one behind it") {
    // A page abandoned mid-fetch cancels its asker. If the computation died
    // with it, the slot would never fill and every later asker of the key
    // would wait on it for as long as the key lives.
    for {
      c <- cache
      gate <- Deferred[IO, Unit]
      first <- c.get("k")(gate.get.as(1)).start
      _ <- c.keys.iterateUntil(_.nonEmpty)
      _ <- first.cancel
      _ <- gate.complete(())
      second <- c.get("k")(IO.pure(2)).timeout(5.seconds)
    } yield assertEquals(second, 1)
  }

  test("a computation that hangs is its askers' failure, then remembered") {
    TestControl.executeEmbed(for {
      c <- cache
      runs <- Ref[IO].of(0)
      first <- c.get("k")(runs.update(_ + 1) *> IO.never).attempt
      again <- c.get("k")(runs.update(_ + 1) *> IO.never).attempt
      _ <- IO.sleep(SharedCache.FailureTtl)
      retried <- c.get("k")(runs.update(_ + 1).as(3))
      n <- runs.get
    } yield {
      assert(
        first.left.exists(_.isInstanceOf[TimeoutException]),
        clue = first
      )
      assert(again.isLeft, clue = again)
      assertEquals((retried, n), (3, 2))
    })
  }
}
