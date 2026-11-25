package fh.view.runtime

import cats.effect.IO
import org.http4s.*

import scala.concurrent.duration.*

/** An action that runs in a viewer's session (Back's history post, a choice):
  * its target is this client's own DOM, so what it needs is a live connection
  * to patch.
  */
class SessionActionSuite extends ServerHarness {

  private val warm = Map("sensor.a" -> es("sensor.a", "warm"))

  private def as(conn: String): String = s"""{"${Server.ConnSignal}":"$conn"}"""

  test(
    "a tap on a connection the server has forgotten is refused, not minted"
  ) {
    // An idle page outlives its session while looking alive. A tap carries too
    // little of the page to rebuild the session, so it says so, and the
    // reconnecting stream, which carries the rest, rebuilds it.
    live(
      liveLeafDash,
      warm,
      Server.SessionWindows.default.copy(adoption = 50.millis)
    ) { ts =>
      for {
        conn <- ts.load().map(_.conn)
        // The idle page: nothing registered under the `conn` its DOM carries.
        _ <- (IO.sleep(10.millis) *> ts.sessions.get(conn))
          .iterateWhile(_.isDefined)
        result <- ts.postResult(
          s"sse/history/${ts.slug}",
          body = as(conn)
        )
        revived <- ts.sessions.get(conn)
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains(Server.NotConnectedMessage), clue = body)
        assertEquals(revived, None)
      }
    }
  }

  test("a tap naming another dashboard's connection is refused, not dropped") {
    // A `conn` is per document and a document is one dashboard, so no honest
    // client does this. Re-registering would unroute the live owner, and 204
    // would be silence; the refusal rides the body (`_toast`, the tap's group
    // and node) since the bundle parses a body only on 200.
    live(liveLeafDash, warm) { ts =>
      for {
        elsewhere <- ownerlessSession("other")
        _ <- ts.sessions.register("shared", elsewhere)
        result <- ts.postResult(
          s"sse/history/${ts.slug}",
          body = as("shared")
        )
        untouched <- ts.sessions.get("shared").map(_.exists(_.slug == "other"))
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains(Server.WrongSlugMessage), clue = body)
        assert(untouched)
      }
    }
  }
}
