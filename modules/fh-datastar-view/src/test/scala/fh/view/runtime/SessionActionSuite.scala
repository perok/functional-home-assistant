package fh.view.runtime

import cats.effect.IO
import fh.view.model.NodeId
import org.http4s.*

import scala.concurrent.duration.*

/** An action that runs in a viewer's session (Back's history post, a choice):
  * its target is this client's own DOM, so what it needs is a live connection
  * to patch.
  */
class SessionActionSuite extends ServerHarness {

  private val states = Map(
    "sensor.a" -> es("sensor.a", "warm"),
    "sensor.b" -> es("sensor.b", "cold")
  )

  private def as(conn: String): String = s"""{"${Server.ConnSignal}":"$conn"}"""

  private val tab = VarKey(NodeId.derived("c"), "tab")

  /** The two session actions under `conn`: a tap, told what failed, and Back,
    * whose URL has already moved, reloaded there.
    */
  private val actions
      : List[(String, (TestServer, String) => IO[(Status, String)], String)] =
    List(
      ("a tap", (ts, conn) => ts.choose(conn, tab, Some("1")), ""),
      (
        "Back",
        (ts, conn) => ts.postResult(s"sse/history/${ts.slug}", body = as(conn)),
        Server.BackReloadedNotice
      )
    )

  for ((name, act, reloadNotice) <- actions) {
    def answered(body: String, refusal: String): Unit =
      if (reloadNotice.isEmpty) {
        assert(body.contains(refusal), clue = body)
        assert(!body.contains(Server.ReloadSignal), clue = body)
      } else {
        assert(body.contains(Server.ReloadSignal), clue = body)
        assert(body.contains(reloadNotice), clue = body)
      }

    test(
      s"$name on a connection the server has forgotten is refused, not minted"
    ) {
      // An idle page outlives its session while looking alive. An action
      // carries too little of the page to rebuild the session, so it says so,
      // and the reconnecting stream, which carries the rest, rebuilds it.
      live(
        tabsDash,
        states,
        Server.SessionWindows.default.copy(adoption = 50.millis)
      ) { ts =>
        for {
          conn <- ts.load().map(_.conn)
          // The idle page: nothing registered under the `conn` its DOM carries.
          _ <- (IO.sleep(10.millis) *> ts.sessions.get(conn))
            .iterateWhile(_.isDefined)
          result <- act(ts, conn)
          revived <- ts.sessions.get(conn)
        } yield {
          val (status, body) = result
          assertEquals(status, Status.Ok)
          answered(body, Server.NotConnectedMessage)
          assertEquals(revived, None)
        }
      }
    }

    test(
      s"$name naming another dashboard's connection is refused, not dropped"
    ) {
      // A `conn` is per document and a document is one dashboard, so no honest
      // client does this. Re-registering would unroute the live owner, and 204
      // would be silence; the refusal rides the body since the bundle parses a
      // body only on 200.
      live(tabsDash, states) { ts =>
        for {
          elsewhere <- ownerlessSession("other")
          _ <- ts.sessions.register("shared", elsewhere)
          result <- act(ts, "shared")
          untouched <- ts.sessions
            .get("shared")
            .map(_.exists(_.slug == "other"))
        } yield {
          val (status, body) = result
          assertEquals(status, Status.Ok)
          answered(body, Server.WrongSlugMessage)
          assert(untouched)
        }
      }
    }
  }
}
