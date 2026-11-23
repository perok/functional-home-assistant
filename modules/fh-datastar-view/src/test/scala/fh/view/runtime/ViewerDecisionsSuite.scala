package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.NodeId
import fh.view.testkit.{FixtureEntries, FixtureEntity, PklFixture}

import scala.concurrent.duration.*

/** An If whose chain reads a variable and a light (`FixtureEntries.
  * viewerDecisions`): `mode == "a"` shows a title, else the kitchen light while
  * it is on, else "Neither". Which case a viewer is shown depends on its own
  * `mode` and on the light, so the decision is made per viewer.
  */
class ViewerDecisionsSuite extends ServerHarness {

  private val entry = FixtureEntries.viewerDecisions
  private val mode = VarKey(NodeId.derived("c_0"), "mode")
  private val pick = NodeId.derived("c_0_0")

  private val dash =
    PklFixture.buildDashboard(entry.slug, entry.source, entry.dump)

  private def withKitchen(state: String): List[FixtureEntity] =
    entry.entities.map(e =>
      if (e.entityId == "light.kitchen") e.copy(state = state) else e
    )

  private def served[A](kitchen: String)(f: TestServer => IO[A]): IO[A] =
    TestServer.resource(dash, withKitchen(kitchen)).use(f).timeout(30.seconds)

  /** A stream carrying this viewer's committed `mode`, as a reconnect does
    * (`Server.SseInclude`).
    */
  private def onMode(value: String): String =
    "?datastar=" + java.net.URLEncoder.encode(
      s"""{"${mode.committedSignal}":"$value"}""",
      "UTF-8"
    )

  private def drain(session: Option[Session]): IO[String] =
    session
      .fold(IO.pure(List.empty[SseFrame]))(_.takeBacklog)
      .map(_.flatMap(_.data).mkString("\n"))

  test("the chain is a viewer pick, decided by neither state nor a lookup") {
    val r = Renderer.create(dash)
    assertEquals(r.surfaces.viewerPicks, Set(pick))
    assertEquals(r.surfaces.statePicks, Set.empty[NodeId])
    assertEquals(r.surfaces.varPicks, Set.empty[NodeId])
    // A write of `mode` re-plans this pick, which is no plain panel.
    assertEquals(r.vars.picksDecidedBy(mode), List(pick))
    assertEquals(r.vars.panelsSelectedBy(mode), Nil)
  }

  test(
    "two viewers on different values are shown different cases at one version"
  ) {
    served("off") { ts =>
      (ts.page(), ts.page("?v.c_0.mode=b")).tupled.map { (a, b) =>
        assert(a.contains("Mode a") && !a.contains("Neither"), clue = a)
        assert(b.contains("Neither") && !b.contains("Mode a"), clue = b)
      }
    }
  }

  test(
    "the light moving changes one viewer's case and sends the other nothing"
  ) {
    served("off") { ts =>
      for {
        onA <- ts.connect()
        onB <- ts.connect(onMode("b"))
        _ <- onA.drain
        _ <- onB.drain
        _ <- ts.change("light.kitchen", "on")
        a <- onA.drain
        b <- onB.drain
        _ = assertEquals(domEvents(a), Nil, clue = ("on mode a", a))
        _ = assert(
          domEvents(b).exists(_._3.exists(_.contains("Kitchen"))),
          clue = ("on mode b: the light's tile arrives", b)
        )
        _ = assert(
          !b.exists(_.renderString.contains("Neither")),
          clue = ("the case it left is not resent", b)
        )
        // And back: the case it left is the one it is sent again.
        _ <- ts.change("light.kitchen", "off")
        a2 <- onA.drain
        b2 <- onB.drain
        _ = assertEquals(domEvents(a2), Nil, clue = ("on mode a", a2))
        _ = assert(
          b2.exists(_.renderString.contains("Neither")),
          clue = ("on mode b", b2)
        )
      } yield ()
    }
  }

  test("a light moving under a case no one's values reach logs nothing") {
    // Mode a is shown "Mode a" whatever the light does: no viewer's case moves.
    served("off") { ts =>
      for {
        onA <- ts.connect()
        _ <- onA.drain
        before <- ts.log
        _ <- ts.change("light.kitchen", "on")
        a <- onA.drain
        after <- ts.log
      } yield {
        assertEquals(domEvents(a), Nil, clue = a)
        assertEquals(after.fragments, before.fragments)
      }
    }
  }

  test("a write re-plans its writer's host and no one else's") {
    served("off") { ts =>
      for {
        a <- ts.load().map(_.conn)
        b <- ts.load("?v.c_0.mode=b").map(_.conn)
        sa <- ts.sessions.get(a)
        sb <- ts.sessions.get(b)
        _ <- drain(sa)
        _ <- drain(sb)
        // A moves to `b`: the chain, now past `mode == a`, reads the light.
        _ <- ts.choose(a, mode, Some("b"))
        sentA <- drain(sa)
        sentB <- drain(sb)
        stateA <- sa.traverse(_.state)
      } yield {
        assert(sentA.contains("Neither"), clue = sentA)
        assert(!sentA.contains("Mode a"), clue = sentA)
        assertEquals(sentB, "")
        assertEquals(stateA.map(_.vars), Some(Map(mode -> "b")))
      }
    }
  }
}
