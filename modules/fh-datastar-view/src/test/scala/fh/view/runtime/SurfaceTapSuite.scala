package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Dashboard, LayoutNode, Surface}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** The tap that opens or closes a surface: its target is this client's own DOM,
  * so what it needs is a live connection to patch.
  */
class SurfaceTapSuite extends ServerHarness {

  // Opens documents; see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  private def popupDash: Dashboard =
    liveLeafDash.copy(surfaces =
      Map("det" -> Surface(LayoutNode.Component("col")))
    )

  test("a tap on a connection the server has forgotten still opens the popup") {
    // An idle page outlives its session while looking alive. Every tap on it
    // answered 204, and the client showed a URL claiming a popup the DOM did
    // not have, until a second tap or a reload.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate,
          // The tap mints a `Fresh` session with its reap this far out, inside
          // the window the test lets the first session be reaped in; 50 ms was
          // not enough on a loaded machine.
          windows = Server.SessionWindows.default.copy(adoption = 2.seconds)
        )
        .use { server =>
          val routes = server.routes.orNotFound
          for {
            page <- routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
              .flatMap(_.bodyText.compile.string)
            conn = Uri
              .unsafeFromString(
                "/" + page
                  .split("""data-init="@get\('""")(1)
                  .split("'")(0)
                  .replace("&amp;", "&")
              )
              .query
              .params(Server.ConnSignal)
            // The idle page: nothing registered under the `conn` its DOM
            // carries.
            _ <- (IO.sleep(10.millis) *> sessions.get(conn))
              .iterateWhile(_.isDefined)
            status <- routes
              .run(
                Request[IO](
                  Method.POST,
                  uri"/sse/surface/dashboard/open/det"
                ).withEntity(s"""{"${Server.ConnSignal}":"$conn"}""")
              )
              .map(_.status)
            // The tap re-establishes the session, so the patch waits for the
            // reconnecting stream to adopt and drain it.
            revived <- sessions.get(conn)
            open <- revived.traverse(_.open.get)
            queued <- revived.flatTraverse(_.control.tryTake)
          } yield (status, revived.map(_.slug), open, queued.flatMap(_.data))
        }
    } yield out).timeout(30.seconds).map { case (status, slug, open, patch) =>
      assertEquals(status, Status.NoContent)
      assertEquals(slug, Some("dashboard"))
      assertEquals(open, Some(Set("det")))
      assert(
        patch.exists(_.contains(Dashboard.PopupHostId)),
        clue = patch
      )
    }
  }

  test("a swap COMMITS the selection, and only a swap does") {
    // The client does not set `ui_popups` itself (ADR 0025), so without this
    // frame the URL mirror and reconnect restore go blind. The body carries no
    // ui-state: the selection can only come from the swap.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          val routes = server.routes.orNotFound
          def tap(path: Uri) =
            routes
              .run(
                Request[IO](Method.POST, path)
                  .withEntity(s"""{"${Server.ConnSignal}":"c"}""")
              )
              .map(_.status)
          for {
            opened <- tap(uri"/sse/surface/dashboard/open/det")
            session <- sessions.get("c")
            afterOpen <- session.traverse(drain)
            closed <- tap(uri"/sse/popup/dashboard/close")
            afterClose <- session.traverse(drain)
          } yield (opened, closed, afterOpen, afterClose)
        }
    } yield out).timeout(30.seconds).map {
      case (opened, closed, afterOpen, afterClose) =>
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

  private def drain(session: Session): IO[String] =
    fs2.Stream
      .repeatEval(session.control.tryTake)
      .unNoneTerminate
      .compile
      .toList
      .map(_.flatMap(_.data).mkString("\n"))

  test("a tap on a surface this build no longer has says so") {
    // Ids are location-derived, so an edit renames surfaces below it and a page
    // open across the rebuild taps an unknown name; it must say so.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          server.routes.orNotFound
            .run(
              Request[IO](
                Method.POST,
                uri"/sse/surface/dashboard/open/gone"
              ).withEntity(s"""{"${Server.ConnSignal}":"c"}""")
            )
            .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
        }
    } yield out).timeout(30.seconds).map { case (status, body) =>
      // 200 carrying signals (ADR 0024).
      assertEquals(status, Status.Ok)
      assert(body.contains("gone"), clue = body)
      assert(body.contains(Server.ToastSignal), clue = body)
    }
  }

  test("a tap naming another dashboard's connection is refused, not dropped") {
    // A `conn` is per document and a document is one dashboard, so no honest
    // client does this. Re-registering would unroute the live owner, and 204
    // would be silence; the refusal rides the body (`_toast`, the tap's group
    // and node) since the bundle parses a body only on 200.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use { server =>
          for {
            elsewhere <- Session.create("other")
            _ <- sessions.register("shared", elsewhere)
            status <- server.routes.orNotFound
              .run(
                Request[IO](
                  Method.POST,
                  uri"/sse/surface/dashboard/open/det?group=tabs"
                ).withEntity(s"""{"${Server.ConnSignal}":"shared"}""")
              )
              .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
            untouched <- sessions.get("shared").map(_.exists(_.slug == "other"))
          } yield (status, untouched)
        }
    } yield out).timeout(30.seconds).map { case ((status, body), untouched) =>
      assertEquals(status, Status.Ok)
      assert(body.contains(Server.WrongSlugMessage), clue = body)
      // With no status to infer it from, the server clears the tab's pending
      // selection in the same frame.
      assert(body.contains("_tabs__pending"), clue = body)
      assert(untouched)
    }
  }
}
