package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Dashboard, LayoutNode, Surface}
import org.http4s.*

import scala.concurrent.duration.*

/** The tap that opens or closes a surface: its target is this client's own DOM,
  * so what it needs is a live connection to patch.
  */
class SurfaceTapSuite extends ServerHarness {

  private def popupDash: Dashboard =
    liveLeafDash.copy(surfaces =
      Map("det" -> Surface(LayoutNode.Component("col")))
    )

  private val warm = Map("sensor.a" -> es("sensor.a", "warm"))

  private def as(conn: String): String = s"""{"${Server.ConnSignal}":"$conn"}"""

  test(
    "a tap on a connection the server has forgotten is refused, not minted"
  ) {
    // An idle page outlives its session while looking alive. A tap carries too
    // little of the page to rebuild the session, so it says so, and the
    // reconnecting stream, which carries the rest, rebuilds it.
    live(
      popupDash,
      warm,
      Server.SessionWindows.default.copy(adoption = 50.millis)
    ) { ts =>
      for {
        conn <- ts.load().map(_.conn)
        // The idle page: nothing registered under the `conn` its DOM carries.
        _ <- (IO.sleep(10.millis) *> ts.sessions.get(conn))
          .iterateWhile(_.isDefined)
        result <- ts.postResult(
          s"sse/surface/${ts.slug}/open/det",
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

  test("a swap COMMITS the selection, and only a swap does") {
    // The client does not set `ui_popups` itself (ADR 0025), so without this
    // frame the URL mirror and reconnect restore go blind. The body carries no
    // ui-state: the selection can only come from the swap.
    live(popupDash, warm) { ts =>
      for {
        conn <- ts.load().map(_.conn)
        opened <- ts.post(s"sse/surface/${ts.slug}/open/det", body = as(conn))
        session <- ts.sessions.get(conn)
        afterOpen <- session.traverse(drain)
        closed <- ts.post(s"sse/popup/${ts.slug}/close", body = as(conn))
        afterClose <- session.traverse(drain)
      } yield {
        assertEquals(opened, Status.NoContent)
        assertEquals(closed, Status.NoContent)
        val sig = Server.UiSignalPrefix + Dashboard.PopupHostId
        assert(
          afterOpen.exists(_.contains(s""""$sig":"det"""")),
          clue = afterOpen
        )
        // A close is a commit too, or the URL keeps claiming a dialog.
        assert(
          afterClose.exists(_.contains(s""""$sig":""""")),
          clue = afterClose
        )
        // The close empties the host the chrome owns; it never writes the
        // host element, so attributes a theme put on it survive.
        val host = Dashboard.PopupHostId
        assert(afterClose.exists(_.contains("mode remove")), clue = afterClose)
        assert(
          afterClose.exists(_.contains(s"selector #$host > *")),
          clue = afterClose
        )
        assert(
          !afterClose.exists(_.contains(s"""id="$host"""")),
          clue = afterClose
        )
      }
    }
  }

  private def drain(session: Session): IO[String] =
    session.takeBacklog.map(_.flatMap(_.data).mkString("\n"))

  test("a tap on a surface this build no longer has says so") {
    // Ids are location-derived, so an edit renames surfaces below it and a page
    // open across the rebuild taps an unknown name; it must say so.
    live(popupDash, warm) { ts =>
      ts.load()
        .flatMap(doc =>
          ts.postResult(
            s"sse/surface/${ts.slug}/open/gone",
            body = as(doc.conn)
          )
        )
        .map { (status, body) =>
          // 200 carrying signals (ADR 0024).
          assertEquals(status, Status.Ok)
          assert(body.contains("gone"), clue = body)
          assert(body.contains(Server.ToastSignal), clue = body)
        }
    }
  }

  test("a tap naming another dashboard's connection is refused, not dropped") {
    // A `conn` is per document and a document is one dashboard, so no honest
    // client does this. Re-registering would unroute the live owner, and 204
    // would be silence; the refusal rides the body (`_toast`, the tap's group
    // and node) since the bundle parses a body only on 200.
    live(popupDash, warm) { ts =>
      for {
        elsewhere <- ownerlessSession("other")
        _ <- ts.sessions.register("shared", elsewhere)
        result <- ts.postResult(
          s"sse/surface/${ts.slug}/open/det",
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
