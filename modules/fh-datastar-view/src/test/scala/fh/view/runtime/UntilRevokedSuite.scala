package fh.view.runtime

import cats.effect.IO
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.ServerSentEvent

import scala.concurrent.duration.*

/** How a live stream ends (issue #89, ADR 0023).
  *
  * `Server.untilRevoked` merges the dashboard's own events with a side that
  * stays silent until the access rule breaks, then emits `_reload` and ends. It
  * therefore sits between the stream and EVERY other reason a stream stops —
  * the client hanging up, a session being displaced, the server shutting down —
  * and getting it wrong does not corrupt anything, it just fails to terminate.
  *
  * That failure mode is why this suite exists as a unit test rather than being
  * left to `SessionLifecycleSuite`: there it shows up as a 31-second timeout
  * under load, which reads like a flake. The first version (`mergeHaltR`) was
  * exactly this bug — it waited for a side that never ends.
  */
class UntilRevokedSuite extends munit.CatsEffectSuite {

  private val always: Stream[IO, Boolean] = Stream(true) ++ Stream.never[IO]

  private def event(id: String) = SseFrame.of(ServerSentEvent(data = Some(id)))

  /** The case `SessionLifecycleSuite` exercises through two HTTP requests: the
    * events end on their own (a displaced session interrupts them) while the
    * rule still holds, so the revocation side never fires. The merged stream
    * must still end.
    */
  test("the stream ends when its EVENTS end, even though the rule holds") {
    Server
      .untilRevoked(always)(Stream.emit(event("a")))
      .compile
      .toList
      .timeout(5.seconds)
      .map(seen => assertEquals(seen.flatMap(_.data), List("a")))
  }

  test("an interrupted stream ends too — the displacement path") {
    for {
      stop <- SignallingRef[IO].of(false)
      events = (Stream.emit(event("a")) ++ Stream.never[IO]).interruptWhen(stop)
      seen <- Server
        .untilRevoked(always)(events)
        .concurrently(Stream.eval(IO.sleep(50.millis) *> stop.set(true)))
        .compile
        .toList
        .timeout(5.seconds)
    } yield assertEquals(seen.flatMap(_.data), List("a"))
  }

  /** The other direction, and the reason the merge is there at all. */
  test("a revoked rule ends the stream and says _reload last") {
    for {
      allowed <- SignallingRef[IO].of(true)
      events = Stream.emit(event("a")) ++ Stream.never[IO]
      seen <- Server
        .untilRevoked(allowed.discrete)(events)
        .concurrently(
          Stream.eval(IO.sleep(50.millis) *> allowed.set(false))
        )
        .compile
        .toList
        .timeout(5.seconds)
    } yield {
      assertEquals(seen.head.data, Some("a"))
      assertEquals(seen.last, Server.reloadPatch)
      assertEquals(seen.size, 2)
    }
  }

  test("a rule that never breaks never adds anything") {
    Server
      .untilRevoked(always)(Stream.emits(List(event("a"), event("b"))))
      .compile
      .toList
      .timeout(5.seconds)
      .map(seen => assertEquals(seen.map(_.data.get), List("a", "b")))
  }
}
