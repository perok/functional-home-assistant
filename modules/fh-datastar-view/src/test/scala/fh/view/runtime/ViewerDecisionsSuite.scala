package fh.view.runtime

import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.NodeId
import fh.view.testkit.{FixtureEntries, FixtureEntity, PklFixture}

import scala.concurrent.duration.*

/** What a viewer's own `mode` decides beside state (`FixtureEntries.
  * viewerDecisions`, `viewerOrder`), so each is made per viewer: an If, whose
  * `mode == "a"` shows a title, else the kitchen light while it is on, else
  * "Neither"; a set of two lights whose guard is `mode == "a"` or lit (a keeps
  * both, b the lit one); and a set of three ordered by lit under a, unlit under b.
  */
class ViewerDecisionsSuite extends ServerHarness {

  private val entry = FixtureEntries.viewerDecisions
  private val mode = VarKey(NodeId.derived("c_0"), "mode")
  private val pick = NodeId.derived("c_0_0")
  private val guarded = NodeId.derived("c_0_1")
  private val ordered = NodeId.derived("c_0_0")

  private val dash =
    PklFixture.buildDashboard(entry.slug, entry.source, entry.dump)

  private val orderEntry = FixtureEntries.viewerOrder

  private val orderDash = PklFixture.buildDashboard(
    orderEntry.slug,
    orderEntry.source,
    orderEntry.dump
  )

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
    // A write of `mode` re-plans this pick, which is no plain panel, and the
    // set whose guard reads it, or whose order does.
    assertEquals(r.vars.picksDecidedBy(mode).toSet, Set(pick, guarded))
    assertEquals(
      Renderer.create(orderDash).vars.picksDecidedBy(mode),
      List(ordered)
    )
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

  /** The names in the order the page lists them. */
  private def listed(html: String, names: String*): List[String] =
    names.filter(html.contains).sortBy(html.indexOf(_)).toList

  private val lights = List("Hall", "Porch")
  private val sofas = List("Desk", "Shelf", "Sofa")

  test("a set's guard reading mode and a light: each viewer its own members") {
    served("off") { ts =>
      (ts.page(), ts.page("?v.c_0.mode=b")).tupled.map { (a, b) =>
        assertEquals(listed(a, lights*), List("Hall", "Porch"), clue = a)
        assertEquals(listed(b, lights*), List("Porch"), clue = b)
      }
    }
  }

  test(
    "a light going off: the member leaves for b, a keeps it and is sent it"
  ) {
    served("off") { ts =>
      for {
        onA <- ts.connect()
        onB <- ts.connect(onMode("b"))
        _ <- onA.drain
        _ <- onB.drain
        _ <- ts.change("light.porch", "off")
        a <- onA.drain
        b <- onB.drain
        pageA <- ts.page()
        pageB <- ts.page("?v.c_0.mode=b")
      } yield {
        assert(
          domEvents(b).exists(_._1 == "remove"),
          clue = ("b loses its member", b)
        )
        assert(
          !domEvents(a).exists(_._1 == "remove"),
          clue = ("a keeps it", a)
        )
        assert(a.exists(_.renderString.contains("porch")), clue = ("a", a))
        assertEquals(listed(pageA, lights*), List("Hall", "Porch"))
        assertEquals(listed(pageB, lights*), Nil)
      }
    }
  }

  test("a write of a's value places the member for its writer alone") {
    served("off") { ts =>
      for {
        a <- ts.load().map(_.conn)
        b <- ts.load("?v.c_0.mode=b").map(_.conn)
        sa <- ts.sessions.get(a)
        sb <- ts.sessions.get(b)
        _ <- drain(sa)
        _ <- drain(sb)
        _ <- ts.choose(b, mode, Some("a"))
        sentA <- drain(sa)
        sentB <- drain(sb)
      } yield {
        assert(sentB.contains("Hall"), clue = sentB)
        assertEquals(sentA, "")
      }
    }
  }

  test("an order key reading mode: each viewer ordered its own way") {
    TestServer.resource(orderDash, orderEntry.entities).use { ts =>
      (ts.page(), ts.page("?v.c_0.mode=b")).tupled.map { (a, b) =>
        assertEquals(listed(a, sofas*), List("Shelf", "Desk", "Sofa"), clue = a)
        assertEquals(listed(b, sofas*), List("Desk", "Sofa", "Shelf"), clue = b)
      }
    }
  }

  test("a state change reorders each viewer its own way") {
    TestServer.resource(orderDash, orderEntry.entities).use { ts =>
      for {
        onA <- ts.connect()
        onB <- ts.connect(onMode("b"))
        _ <- onA.drain
        _ <- onB.drain
        _ <- ts.change("light.desk", "on")
        a <- onA.drain
        b <- onB.drain
        pageA <- ts.page()
        pageB <- ts.page("?v.c_0.mode=b")
      } yield {
        assert(domEvents(a).nonEmpty, clue = ("a", a))
        assert(domEvents(b).nonEmpty, clue = ("b", b))
        assertNotEquals(domEvents(a), domEvents(b))
        assertEquals(listed(pageA, sofas*), List("Desk", "Shelf", "Sofa"))
        assertEquals(listed(pageB, sofas*), List("Sofa", "Desk", "Shelf"))
      }
    }
  }
}
