package fh.view.runtime

import cats.effect.{Deferred, IO}
import cats.effect.kernel.Ref as CeRef
import cats.syntax.all.*
import api.homeassistant.ws.domain.HistoryPoint
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  QueryTemplate,
  Reads,
  Ref,
  Region,
  SlotSource,
  Transform
}
import fh.view.testkit.{FakeConfig, FixtureEntity}
import org.http4s.*

import java.time.Instant
import scala.concurrent.duration.*

/** Writing a node variable (issue #209): the tap that moves a chart's window,
  * and what happens to a value the chart could not draw. The slice the feature
  * exists for.
  */
class VarTapSuite extends ServerHarness {

  /** Passthrough, so the window can be read back out: the fake recorder puts
    * its span in the one point it answers with. A drawn chart is handed a
    * `Series`, never the window, which is the split working.
    */
  private val chartCard = CardDef(
    """<span>{{chart}}</span>""",
    slots = List("chart")
  )

  private val panelCard = CardDef(
    "<div>{{#children}}{{{html}}}{{/children}}</div>",
    regions = Map("children" -> Region())
  )

  private val chartNode = LayoutNode.Component(
    "chart",
    slots = Map(
      "chart" -> SlotSource(
        query = Some(
          QueryTemplate(
            "history",
            Map(
              "entity" -> Ref.Literal("sensor.a"),
              "window" -> Ref.Var("window")
            )
          )
        ),
        transform = Transform.Stage.Passthrough,
        reads = Reads.OnRender
      )
    )
  )

  private val dash = Dashboard(
    cards = Map("chart" -> chartCard, "panel" -> panelCard),
    card = LayoutNode.Component(
      "panel",
      regions = LayoutNode.kids(chartNode),
      id = Some("panel"),
      vars = Map("window" -> "24h")
    )
  )

  /** The chart's entity is the variable, beside a card naming `sensor.b`: the
    * dashboard ADR 0023's bound is drawn around.
    */
  private val entityDash = Dashboard(
    cards = Map("chart" -> chartCard, "panel" -> panelCard),
    card = LayoutNode.Component(
      "panel",
      regions = LayoutNode.kids(
        chartNode.copy(slots =
          Map(
            "chart" -> SlotSource(
              query = Some(
                QueryTemplate(
                  "history",
                  Map(
                    "entity" -> Ref.Var("e"),
                    "window" -> Ref.Var("window")
                  )
                )
              ),
              transform = Transform.Stage.Passthrough,
              reads = Reads.OnRender
            )
          )
        ),
        LayoutNode.Component(
          "chart",
          slots = Map("chart" -> SlotSource(entityId = Some("sensor.b")))
        )
      ),
      id = Some("panel"),
      vars = Map("e" -> "sensor.a", "window" -> "24h")
    )
  )

  private type Recorder = (Instant, Instant, String) => IO[List[HistoryPoint]]

  /** `[[3600000,...]]` is an hour, `[[604800000,...]]` a week. */
  private val span: Recorder = (start, end, _) =>
    IO.pure(
      List(
        HistoryPoint(
          "1.0",
          Instant.ofEpochMilli(end.toEpochMilli - start.toEpochMilli)
        )
      )
    )

  private def served[A](
      f: TestServer => IO[A],
      recorder: Recorder = span,
      dashboard: Dashboard = dash
  ): IO[A] =
    TestServer
      .resource(
        dashboard,
        List(FixtureEntity("sensor.a", "1"), FixtureEntity("sensor.b", "2")),
        config = FakeConfig(recorder = Some(recorder))
      )
      .use(f)
      .timeout(30.seconds)

  private def post(ts: TestServer, conn: String, path: String) =
    ts.postResult(path, body = s"""{"${Server.ConnSignal}":"$conn"}""")

  private def varPath(ts: TestServer, rest: String) =
    s"sse/var/${ts.slug}/panel/$rest"

  private val Day = 24.hours.toMillis.toString
  private val Week = 7.days.toMillis.toString

  /** Drained, since the count is half the claim: a refused write offers nothing
    * and an accepted one always commits (ADR 0025).
    */
  private def drain(session: Option[Session]): IO[List[SseFrame]] =
    session.fold(IO.pure(List.empty[SseFrame]))(s => s.takeBacklog)

  /** What the control's highlight falls back to once its pending value clears
    * (ADR 0025).
    */
  private def committed(value: String): String =
    s"""signals {"_var_panel__window":"$value"}"""

  test("a chart whose recorder fails costs that chart, not the page") {
    served(
      _.pageResponse()
        .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
        .map { (status, page) =>
          assertEquals(status, Status.Ok)
          assert(page.contains("<span></span>"), clue = page)
        },
      recorder =
        (_, _, _) => IO.raiseError(RuntimeException("recorder is down"))
    )
  }

  test("the head is sent before a slow chart is answered") {
    // The head reads no query, so a cold fetch overlaps the stylesheet fetches
    // rather than holding up the first byte.
    (Deferred[IO, Unit], CeRef[IO].of(false), CeRef[IO].of(false)).tupled
      .flatMap { (gate, fetched, headFirst) =>
        served(
          _.pageResponse()
            .flatMap(
              _.bodyText
                .evalScan("") { (acc, chunk) =>
                  val page = acc + chunk
                  IO.whenA(page.contains("</head>"))(
                    fetched.get.flatMap(f => headFirst.set(!f).whenA(!f)) *>
                      gate.complete(()).void
                  ).as(page)
                }
                .compile
                .lastOrError
            )
            .flatMap(page =>
              headFirst.get.map { first =>
                assert(first, clue = "the head waited for the fetch")
                assert(page.contains("</html>"), clue = page)
              }
            ),
          recorder = (_, end, _) =>
            gate.get *> fetched.set(true).as(List(HistoryPoint("1.0", end)))
        )
      }
  }

  test("writing the variable re-renders the chart at the new window") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        // A viewer who has chosen nothing gets the declared window.
        session <- ts.sessions.get(conn)
        result <- post(ts, conn, varPath(ts, "window/7d"))
        chose <- session.traverse(_.state.map(_.vars))
        queued <- drain(session)
      } yield {
        assertEquals(result._1, Status.NoContent)
        assertEquals(
          chose,
          Some(Map(VarKey(NodeId.derived("panel"), "window") -> "7d"))
        )
        // The week's series: the write moved the query, not just a signal.
        val painted = queued.flatMap(_.data).mkString
        assert(painted.contains(Week), clue = painted)
        assert(!painted.contains(Day), clue = painted)
        // The commit rides last, so the highlight stops being a guess only once
        // the chart agrees.
        assertEquals(queued.lastOption.flatMap(_.data), Some(committed("7d")))
      }
    }
  }

  test("two choices landing together end on the later one everywhere") {
    // The first is held in the recorder while the second arrives. Unless one
    // write waits for the other, the second commits first and the first lands
    // over it: the chart and the commit at 7d, the session at 1h.
    (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap {
      (entered, release) =>
        val held: Recorder = (start, end, entity) =>
          IO.whenA(end.toEpochMilli - start.toEpochMilli == 7.days.toMillis)(
            entered.complete(()).void *> release.get
          ) *> span(start, end, entity)
        served(
          ts =>
            for {
              conn <- ts.load().map(_.conn)
              session <- ts.sessions.get(conn)
              first <- post(ts, conn, varPath(ts, "window/7d")).start
              _ <- entered.get
              second <- post(ts, conn, varPath(ts, "window/1h")).start
              // Its chance to overtake.
              _ <- IO.sleep(200.millis)
              _ <- release.complete(())
              _ <- first.joinWithNever
              _ <- second.joinWithNever
              chose <- session.traverse(_.state.map(_.vars))
              queued <- drain(session)
            } yield {
              assertEquals(
                chose,
                Some(Map(VarKey(NodeId.derived("panel"), "window") -> "1h"))
              )
              val charts = queued.flatMap(_.data).filter(_.contains("<span>"))
              assert(
                charts.lastOption.exists(_.contains(1.hour.toMillis.toString)),
                clue = charts
              )
              assertEquals(
                queued.lastOption.flatMap(_.data),
                Some(committed("1h"))
              )
            },
          recorder = held
        )
    }
  }

  /** A candidate set of charts under the panel's window: each member reads it
    * through the set's scope (ADR 0033).
    */
  private val setDash = {
    def chartOf(entity: String) = LayoutNode.SetMember(
      List(
        LayoutNode.SetClause(node =
          chartNode.copy(slots =
            Map(
              "chart" -> SlotSource(
                query = Some(
                  QueryTemplate(
                    "history",
                    Map(
                      "entity" -> Ref.Literal(entity),
                      "window" -> Ref.Var("window")
                    )
                  )
                ),
                transform = Transform.Stage.Passthrough,
                reads = Reads.OnRender
              )
            )
          )
        )
      )
    )
    dash.copy(card =
      LayoutNode.Component(
        "panel",
        regions = LayoutNode.kids(
          LayoutNode.SetNode(
            candidates = List("sensor.a", "sensor.b"),
            members = Map(
              "sensor.a" -> chartOf("sensor.a"),
              "sensor.b" -> chartOf("sensor.b")
            )
          )
        ),
        id = Some("panel"),
        vars = Map("window" -> "24h")
      )
    )
  }

  test("a write redraws every member of a set at the new window") {
    served(
      ts =>
        for {
          page <- ts.page()
          conn <- ts.load().map(_.conn)
          session <- ts.sessions.get(conn)
          result <- post(ts, conn, varPath(ts, "window/7d"))
          queued <- drain(session)
        } yield {
          assertEquals(page.sliding(Day.length).count(_ == Day), 2, page)
          assertEquals(result._1, Status.NoContent)
          val painted = queued.flatMap(_.data).mkString
          // Both members, each patched under its own id, at the week.
          assertEquals(
            painted.sliding(Week.length).count(_ == Week),
            2,
            painted
          )
          assert(!painted.contains(Day), clue = painted)
          assertEquals(queued.lastOption.flatMap(_.data), Some(committed("7d")))
        },
      dashboard = setDash
    )
  }

  test("a value no reader can parse is refused, and nothing moves") {
    // `HistoryQuery.parse` is the authority on what a window may be, so a list
    // beside the declaration could only copy it.
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        result <- post(ts, conn, varPath(ts, "window/4h"))
        chose <- session.traverse(_.state.map(_.vars))
        queued <- drain(session)
      } yield {
        val (status, body) = result
        // ADR 0024: refused is a 200 of signals, not a 4xx.
        assertEquals(status, Status.Ok)
        assert(body.contains("4h"), clue = body)
        // A refused write is not a half-write.
        assertEquals(chose, Some(Map.empty))
        // Nothing committed: the refusal's own signal frame
        // (`Server.actionSignals`, the `group` param) ends the pending value,
        // which falls back to a committed value that never moved.
        assertEquals(queued, Nil)
      }
    }
  }

  test("a variable nothing declares is refused, not a silent no-op") {
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        result <- post(ts, conn, varPath(ts, "nosuch/7d"))
        chose <- ts.sessions.get(conn).flatMap(_.traverse(_.state.map(_.vars)))
      } yield {
        // ADR 0024: a refused action is a 200 carrying signals.
        assertEquals(result._1, Status.Ok)
        assertEquals(chose, Some(Map.empty))
      }
    }
  }

  test("choosing the window already showing costs no element patch") {
    // The bytes did not move, so nothing is re-sent, but the commit still is: a
    // `24h` press while on `24h` has a pending value only a commit ends.
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        _ <- post(ts, conn, varPath(ts, "window/24h"))
        queued <- drain(session)
      } yield assertEquals(queued.flatMap(_.data), List(committed("24h")))
    }
  }

  test("a refusal ends the control's pending ask by name") {
    // The URL the chooser posts (`components/history.pkl`); its `group` lets a
    // refusal end this control's ask and no other.
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        result <- post(
          ts,
          conn,
          varPath(ts, "window/4h?group=var_panel__window")
        )
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains("\"_var_panel__window__pending\":\"\""), body)
      }
    }
  }

  test("a variable moves a chart only to an entity its dashboard shows") {
    // ADR 0023's bound on reads, at a write and at a page URL: the lock is not
    // on the dashboard, so the recorder must never be asked for it.
    CeRef[IO].of(Set.empty[String]).flatMap { asked =>
      served(
        ts =>
          for {
            conn <- ts.load().map(_.conn)
            named <- post(ts, conn, varPath(ts, "e/sensor.b"))
            refused <- post(ts, conn, varPath(ts, "e/lock.front_door"))
            chose <- ts.sessions
              .get(conn)
              .flatMap(_.traverse(_.state.map(_.vars)))
            linked <- ts.pageResponse("?v.panel.e=lock.front_door")
            entities <- asked.get
          } yield {
            assertEquals(named._1, Status.NoContent)
            assertEquals(refused._1, Status.Ok)
            assertEquals(
              chose,
              Some(Map(VarKey(NodeId.derived("panel"), "e") -> "sensor.b"))
            )
            assertEquals(linked.status, Status.BadRequest)
            assertEquals(entities, Set("sensor.a", "sensor.b"))
          },
        recorder = (_, end, entityId) =>
          asked.update(_ + entityId).as(List(HistoryPoint("1.0", end))),
        dashboard = entityDash
      )
    }
  }

  test(
    "a link carrying a value no reader can parse is a 400, not a torn page"
  ) {
    // The URL once skipped the write's check: the page answered 200 and died
    // mid-walk.
    served(
      _.pageResponse("?v.panel.window=bogus")
        .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
        .map { (status, body) =>
          assertEquals(status, Status.BadRequest)
          assert(body.contains("bogus"), clue = body)
        }
    )
  }

  test("the document seeds a linked choice, not the declared value") {
    // Seeded ahead of the body, so the URL mirror never writes the declared
    // window over the linked one while the stream connects.
    served(_.page("?v.panel.window=7d").map { page =>
      val seed = page.indexOf("_var_panel__window: '7d'")
      assert(seed >= 0, clue = page)
      assert(seed < page.indexOf("id=\"panel\""), clue = page)
      assert(!page.contains("_var_panel__window: '24h'"), clue = page)
    })
  }

  test("two choices landing together both stick") {
    served(
      ts =>
        for {
          conn <- ts.load().map(_.conn)
          _ <- (
            post(ts, conn, varPath(ts, "e/sensor.b")),
            post(ts, conn, varPath(ts, "window/7d"))
          ).parTupled
          chose <- ts.sessions
            .get(conn)
            .flatMap(_.traverse(_.state.map(_.vars)))
        } yield assertEquals(
          chose.map(_.keySet.map(_.name)),
          Some(Set("e", "window"))
        ),
      dashboard = entityDash
    )
  }

  /** A stream for a `conn` this process never minted: what a reconnect after a
    * restart, or after its session was reaped, looks like.
    */
  private def forgottenReconnect(ts: TestServer, window: String) =
    ts.connect(
      "?datastar=" + java.net.URLEncoder.encode(
        s"""{"${Server.ConnSignal}":"forgotten","_var_panel__window":"$window"}""",
        "UTF-8"
      )
    )

  test("a session the server forgot keeps the window its reconnect carries") {
    // Otherwise the minted session starts at the declared window and the
    // opening frame resets the bar, while a tab survives the same reconnect on
    // `ui_<id>`.
    served { ts =>
      for {
        client <- forgottenReconnect(ts, "7d")
        events <- client.drain
      } yield {
        val sent = events.flatMap(_.data).mkString
        assert(sent.contains("\"_var_panel__window\":\"7d\""), clue = sent)
        assert(!sent.contains("\"_var_panel__window\":\"24h\""), clue = sent)
        assert(sent.contains(Week), clue = sent)
      }
    }
  }

  test("a reconnect carrying a window no reader takes gets the declared one") {
    // The carried value is the client's claim, so it passes the write's check.
    served { ts =>
      for {
        client <- forgottenReconnect(ts, "4h")
        events <- client.drain
      } yield {
        val sent = events.flatMap(_.data).mkString
        assert(sent.contains("\"_var_panel__window\":\"24h\""), clue = sent)
        assert(sent.contains(Day), clue = sent)
      }
    }
  }

  test("a reconnect keeps each carried value its readers take, not none") {
    // A value naming an entity this dashboard does not show must not cost the
    // window carried beside it.
    served(
      ts =>
        for {
          client <- ts.connect(
            "?datastar=" + java.net.URLEncoder.encode(
              s"""{"${Server.ConnSignal}":"forgotten","_var_panel__window":"7d","_var_panel__e":"lock.front_door"}""",
              "UTF-8"
            )
          )
          _ <- client.drain
          chose <- ts.sessions
            .get("forgotten")
            .flatMap(_.traverse(_.state.map(_.vars)))
        } yield assertEquals(
          chose,
          Some(Map(VarKey(NodeId.derived("panel"), "window") -> "7d"))
        ),
      dashboard = entityDash
    )
  }

  test("a live session's own choice wins over what its reconnect carries") {
    // The stream that would have delivered a commit can die with it, so the
    // client may still hold the value before.
    served { ts =>
      for {
        doc <- ts.load()
        _ <- post(ts, doc.conn, varPath(ts, "window/7d"))
        _ <- ts
          .get(
            doc.stream.withQueryParam(
              "datastar",
              s"""{"${Server.ConnSignal}":"${doc.conn}","_var_panel__window":"24h"}"""
            )
          )
          .flatMap(sseFrom(_)(isCursor))
        chose <- ts.sessions
          .get(doc.conn)
          .flatMap(_.traverse(_.state.map(_.vars)))
      } yield assertEquals(
        chose.flatMap(_.get(VarKey(NodeId.derived("panel"), "window"))),
        Some("7d")
      )
    }
  }

  /** Redrawn by a write (its window) and by a pull (its entity), so the order
    * the two reach the client decides what it shows.
    */
  private val bothDash = dash.copy(
    cards = dash.cards + ("both" -> CardDef(
      """<span>s={{state}} w={{chart}}</span>""",
      slots = List("state", "chart")
    )),
    card = LayoutNode.Component(
      "panel",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "both",
          slots = chartNode.slots +
            ("state" -> SlotSource(entityId = Some("sensor.a")))
        )
      ),
      id = Some("panel"),
      vars = Map("window" -> "24h")
    )
  )

  private def until[A](read: IO[A])(done: A => Boolean): IO[A] =
    fs2.Stream
      .repeatEval(read <* IO.sleep(5.millis))
      .find(done)
      .compile
      .lastOrError
      .timeout(15.seconds)

  test("a write the server made before a pull reaches the client before it") {
    // The client is held mid-frame, as a slow socket holds it, while a second
    // write and then a pull are made. Frames returned to two branches of a
    // merge left it in whichever order the merge took them.
    served(
      ts =>
        for {
          doc <- ts.load()
          flowing <- fs2.concurrent.SignallingRef[IO].of(true)
          seen <- CeRef[IO].of(Vector.empty[ServerSentEvent])
          resp <- ts.get(doc.stream)
          reader <- resp.body
            .through(ServerSentEvent.decoder[IO])
            .evalMap(e => seen.update(_ :+ e) *> flowing.waitUntil(identity))
            .compile
            .drain
            .start
          _ <- until(seen.get)(_.exists(isCursor))
          _ <- flowing.set(false)
          _ <- post(ts, doc.conn, varPath(ts, "window/7d"))
          _ <- until(seen.get)(_.exists(_.data.exists(_.contains(Week))))
          _ <- post(ts, doc.conn, varPath(ts, "window/1h"))
          _ <- ts.change("sensor.a", "2")
          _ <- flowing.set(true)
          sent <- until(seen.get)(es =>
            es.exists(_.data.contains(committed("1h"))) &&
              es.exists(_.data.exists(_.contains("s=2")))
          )
          _ <- reader.cancel
        } yield {
          val drawn = sent.flatMap(_.data).filter(_.contains("s="))
          // The server holds `s=2`: the pull redrew it after the write.
          assert(
            drawn.lastOption.exists(_.contains("s=2")),
            clue = drawn.mkString("\n")
          )
        },
      dashboard = bothDash
    )
  }

  test("the opening frame states every declared variable, chosen or not") {
    // Total over the declarations, so a control still showing last session's
    // window is corrected on connect.
    val renderer = Renderer.fromValidated(
      dash.validated().fold(e => sys.error(e.mkString("; ")), identity)
    )
    val declared = Server
      .openingSignals(renderer, Set.empty, Map.empty, "log", 0L)
      .data
      .getOrElse("")
    val chosen = Server
      .openingSignals(
        renderer,
        Set.empty,
        Map(VarKey(NodeId.derived("panel"), "window") -> "7d"),
        "log",
        0L
      )
      .data
      .getOrElse("")
    assert(declared.contains("\"_var_panel__window\":\"24h\""), declared)
    assert(chosen.contains("\"_var_panel__window\":\"7d\""), chosen)
  }
}
