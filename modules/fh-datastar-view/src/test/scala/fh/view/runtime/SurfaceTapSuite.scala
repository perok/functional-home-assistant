package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{Dashboard, LayoutNode, Surface}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import scala.concurrent.duration.*

/** The tap that opens or closes a SURFACE — the one action route whose target
  * is this client's own DOM rather than an entity, so what it needs from a
  * request is a live connection to patch.
  *
  * Its neighbour [[SessionLifecycleSuite]] owns how a session is born and
  * reaped; this owns what a tap does when it finds one, or does not.
  */
class SurfaceTapSuite extends ServerHarness {

  // This suite opens DOCUMENTS, and the page route streams its body through a
  // blocking pipe that simulated time cannot host — see [[ServerHarness.simulateTime]].
  override protected def simulateTime: Boolean = false

  /** [[liveLeafDash]] with a popup surface to open. */
  private def popupDash: Dashboard =
    liveLeafDash.copy(surfaces =
      Map("det" -> Surface(LayoutNode.Component("col")))
    )

  test("a tap on a connection the server has forgotten still opens the popup") {
    // The bug this is here for: a page left idle outlives its session (its
    // stream drops, the linger expires, the reaper takes it) while looking
    // perfectly alive. Every tap on it used to answer 204 — no patch, no
    // error — and the client, which sets its own `ui_*` signal, then showed a
    // URL claiming a popup the DOM did not have. It took a SECOND tap, after
    // Datastar had quietly reconnected, or a reload.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate,
          // Long enough that the assertions are not racing it. The tap MINTS a
          // `Fresh` session and schedules its reap this far out, so every read
          // below happens inside the same window the test uses to let the
          // FIRST session be reaped — and on a loaded machine 50 ms was not
          // enough, leaving `sessions.get(conn)` empty and the test blaming the
          // tap for a session the reaper had taken.
          adoptionWindow = 2.seconds
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
            // Let the reaper have it — this is the idle page, with nothing
            // registered under the `conn` its DOM still carries.
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
            // The tap is what re-establishes the connection, so the patch has
            // somewhere to wait: the reconnecting stream adopts this session
            // and drains it.
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
    // The client no longer sets `ui_popups` itself
    // (`docs/adr/0025-a-value-in-flight.md`), so if this frame is missing the URL
    // mirror and every reconnect restore go blind — a dialog on screen that a
    // refresh does not bring back. The request body deliberately carries no
    // ui-state at all: the selection here can only have come from the swap.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
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
        // A close is a commit too — the emptied value is what stops the URL
        // claiming a dialog this DOM no longer holds.
        assert(
          afterClose.exists(_.contains(s""""$sig":""""")),
          clue = afterClose
        )
    }
  }

  /** Everything queued for a session's own stream, as one string. */
  private def drain(session: Session): IO[String] =
    fs2.Stream
      .repeatEval(session.control.tryTake)
      .unNoneTerminate
      .compile
      .toList
      .map(_.flatMap(_.data).mkString("\n"))

  test("a tap on a surface this build no longer has says so") {
    // Ids are location-derived, so editing a dashboard renames the surfaces
    // below the edit. A page open across that rebuild taps a name the server
    // has never heard of — the one remaining way a tap could do nothing and
    // say nothing, which is what a status (and the shell's toast) is for.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
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
      assertEquals(status, Status.NotFound)
      assert(body.contains("gone"), clue = body)
    }
  }

  test("a tap naming another dashboard's connection is refused, not dropped") {
    // A `conn` is minted per document and a document is one dashboard, so no
    // honest client produces this. Re-registering would unroute whatever live
    // page owns it, and answering 204 would be the silence this whole route
    // was fixed for — hence a status the shell can toast.
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "warm")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(Renderer.create(popupDash))
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      out <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
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
                  uri"/sse/surface/dashboard/open/det"
                ).withEntity(s"""{"${Server.ConnSignal}":"shared"}""")
              )
              .map(_.status)
            // ...and the page that does own it is left alone.
            untouched <- sessions.get("shared").map(_.exists(_.slug == "other"))
          } yield (status, untouched)
        }
    } yield out).timeout(30.seconds).map { case (status, untouched) =>
      assertEquals(status, Status.Conflict)
      assert(untouched)
    }
  }
}
