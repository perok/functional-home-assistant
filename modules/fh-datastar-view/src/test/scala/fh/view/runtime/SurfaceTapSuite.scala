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

  test("a tap on a connection the server has forgotten still opens the popup") {
    // An idle page outlives its session while looking alive. Every tap on it
    // answered 204, and the client showed a URL claiming a popup the DOM did
    // not have, until a second tap or a reload.
    live(
      popupDash,
      warm,
      // The tap mints a `Fresh` session with its reap this far out, inside the
      // window the test lets the first session be reaped in; 50 ms was not
      // enough on a loaded machine.
      Server.SessionWindows.default.copy(adoption = 2.seconds)
    ) { ts =>
      for {
        conn <- ts.load().map(_.conn)
        // The idle page: nothing registered under the `conn` its DOM carries.
        _ <- (IO.sleep(10.millis) *> ts.sessions.get(conn))
          .iterateWhile(_.isDefined)
        status <- ts.post(s"sse/surface/${ts.slug}/open/det", body = as(conn))
        // The tap re-establishes the session, so the patch waits for the
        // reconnecting stream to adopt and drain it.
        revived <- ts.sessions.get(conn)
        open <- revived.traverse(_.state.map(_.open))
        queued <- revived.flatTraverse(_.takeBacklog.map(_.headOption))
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(revived.map(_.slug), Some(ts.slug))
        assertEquals(open, Some(Set("det")))
        val patch = queued.flatMap(_.data)
        assert(patch.exists(_.contains(Dashboard.PopupHostId)), clue = patch)
      }
    }
  }

  test("a swap COMMITS the selection, and only a swap does") {
    // The client does not set `ui_popups` itself (ADR 0025), so without this
    // frame the URL mirror and reconnect restore go blind. The body carries no
    // ui-state: the selection can only come from the swap.
    live(popupDash, warm) { ts =>
      for {
        opened <- ts.post(s"sse/surface/${ts.slug}/open/det", body = as("c"))
        session <- ts.sessions.get("c")
        afterOpen <- session.traverse(drain)
        closed <- ts.post(s"sse/popup/${ts.slug}/close", body = as("c"))
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
      }
    }
  }

  private def drain(session: Session): IO[String] =
    session.takeBacklog.map(_.flatMap(_.data).mkString("\n"))

  test("a tap on a surface this build no longer has says so") {
    // Ids are location-derived, so an edit renames surfaces below it and a page
    // open across the rebuild taps an unknown name; it must say so.
    live(popupDash, warm) { ts =>
      ts.postResult(s"sse/surface/${ts.slug}/open/gone", body = as("c")).map {
        (status, body) =>
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
        elsewhere <- Session.create("other")
        _ <- ts.sessions.register("shared", elsewhere)
        result <- ts.postResult(
          s"sse/surface/${ts.slug}/open/det?group=tabs",
          body = as("shared")
        )
        untouched <- ts.sessions.get("shared").map(_.exists(_.slug == "other"))
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains(Server.WrongSlugMessage), clue = body)
        // With no status to infer it from, the server clears the tab's pending
        // selection in the same frame.
        assert(body.contains("_tabs__pending"), clue = body)
        assert(untouched)
      }
    }
  }
}
