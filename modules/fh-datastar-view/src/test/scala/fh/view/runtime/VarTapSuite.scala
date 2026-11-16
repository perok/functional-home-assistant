package fh.view.runtime

import fh.view.testkit.Sets

import fh.view.testkit.Slots

import fh.view.testkit.DashboardBuilders

import fh.view.testkit.TestCard
import cats.effect.{Deferred, IO}
import cats.effect.kernel.Ref as CeRef
import cats.syntax.all.*
import api.homeassistant.ws.domain.HistoryPoint
import fh.view.model.{
  Step,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  FetchTemplate,
  Region,
  Value,
  ValueExpr,
  TemplateSlot
}
import fh.view.history.Windows
import fh.view.testkit.{FakeConfig, FixtureEntity}
import fh.view.testkit.DashboardBuilders.declared
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
  private val chartCard = TestCard(
    """<span>{{chart}}</span>"""
  )

  private val panelCard = TestCard(
    "<div>{{{children}}}</div>",
    regions = Map("children" -> Region())
  )

  private val chartNode = LayoutNode.Component(
    "chart",
    slots = Slots.values(
      Map(
        "chart" -> TemplateSlot(
          ValueExpr(
            Value.Fetch(
              FetchTemplate(
                "sensor.a",
                DashboardBuilders.chosenBy("window", Windows.presets)
              )
            ),
            List(Step.Json, Step.JsonText)
          )
        )
      )
    )
  )

  private val dash = Dashboard(
    cards = Sets.cards ++ Map("chart" -> chartCard, "panel" -> panelCard),
    card = LayoutNode.Component(
      "panel",
      slots = Slots.kids(chartNode),
      id = Some("panel"),
      vars = declared("window" -> "24h")
    )
  )

  /** Two variables, each read by its own chart. */
  private val twoVarDash = Dashboard(
    cards = Sets.cards ++ Map("chart" -> chartCard, "panel" -> panelCard),
    card = LayoutNode.Component(
      "panel",
      slots = Slots.kids(
        chartNode.copy(slots =
          Slots.values(
            Map(
              "chart" -> TemplateSlot(
                ValueExpr(
                  Value.Fetch(
                    FetchTemplate(
                      "sensor.b",
                      DashboardBuilders.chosenBy("w", Windows.presets)
                    )
                  ),
                  List(Step.Json, Step.JsonText)
                )
              )
            )
          )
        ),
        chartNode
      ),
      id = Some("panel"),
      vars = declared("w" -> "24h", "window" -> "24h")
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
        withChoices(dashboard),
        List(FixtureEntity("sensor.a", "1"), FixtureEntity("sensor.b", "2")),
        config = FakeConfig(recorder = Some(recorder))
      )
      .use(f)
      .timeout(30.seconds)

  /** The panel's own choices of each variable it declares, the ones the tests
    * press: `4h` is a window no chart's table has.
    */
  private def withChoices(d: Dashboard): Dashboard = d.card match
    case root: LayoutNode.Component =>
      val picks = root.vars.map(_.name).flatMap {
        case "window" =>
          DashboardBuilders.choices("window", "1h", "24h", "7d", "4h") :+
            DashboardBuilders.choosing("window", None)
        case name => DashboardBuilders.choices(name, "1h")
      }
      d.copy(
        cards =
          d.cards + (DashboardBuilders.ChoiceCard -> DashboardBuilders.choiceCard),
        card = root.copy(slots =
          Slots.nodes(
            root.regions.updatedWith(LayoutNode.DefaultRegion)(
              _.map(_ ++ picks)
            )
          )
        )
      )

  private def choose(
      ts: TestServer,
      conn: String,
      name: String,
      value: String
  ) =
    ts.choose(conn, VarKey(NodeId.derived("panel"), name), Some(value))

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
    // The head reads no fetch, so a cold fetch overlaps the stylesheet fetches
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
        result <- choose(ts, conn, "window", "7d")
        chose <- session.traverse(_.state.map(_.vars))
        queued <- drain(session)
      } yield {
        assertEquals(result._1, Status.NoContent)
        assertEquals(
          chose,
          Some(Map(VarKey(NodeId.derived("panel"), "window") -> "7d"))
        )
        // The week's series: the write moved the fetch, not just a signal.
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
              first <- choose(ts, conn, "window", "7d").start
              _ <- entered.get
              second <- choose(ts, conn, "window", "1h").start
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
    def chartOf(entity: String) = Sets.Member(
      List(
        Sets.Clause(node =
          chartNode.copy(slots =
            Slots.values(
              Map(
                "chart" -> TemplateSlot(
                  ValueExpr(
                    Value.Fetch(
                      FetchTemplate(
                        entity,
                        DashboardBuilders.chosenBy("window", Windows.presets)
                      )
                    ),
                    List(Step.Json, Step.JsonText)
                  )
                )
              )
            )
          )
        )
      )
    )
    dash.copy(card =
      LayoutNode.Component(
        "panel",
        slots = Slots.kids(
          Sets.node(
            candidates = List("sensor.a", "sensor.b"),
            members = Map(
              "sensor.a" -> chartOf("sensor.a"),
              "sensor.b" -> chartOf("sensor.b")
            )
          )
        ),
        id = Some("panel"),
        vars = declared("window" -> "24h")
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
          result <- choose(ts, conn, "window", "7d")
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

  test("a value no reader takes is refused, and nothing moves") {
    // The chart's table is the authority on what a window may be, so a list
    // beside the declaration could only copy it.
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        result <- choose(ts, conn, "window", "4h")
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
        // (`Server.actionSignals`) ends the pending value,
        // which falls back to a committed value that never moved.
        assertEquals(queued, Nil)
      }
    }
  }

  // A value that is not a plain token is refused at decode (CallDeclPropertySuite).
  test("a choice of an undeclared variable fails the build") {
    val stray = dash.card match
      case root: LayoutNode.Component =>
        dash.copy(
          cards =
            dash.cards + (DashboardBuilders.ChoiceCard -> DashboardBuilders.choiceCard),
          card = root.copy(slots =
            Slots.nodes(
              root.regions.updatedWith(LayoutNode.DefaultRegion)(
                _.map(_ ++ DashboardBuilders.choices("nosuch", "7d"))
              )
            )
          )
        )
    val errors = stray.validate()
    assert(
      errors.exists(_.contains("chooses the variable 'nosuch', which no node")),
      clue = errors
    )
  }

  test("a press with no conn is refused, and its ask cleared") {
    served { ts =>
      for {
        press <- ts.choice(
          VarKey(NodeId.derived("panel"), "window"),
          Some("7d")
        )
        result <- ts.postResult(press, body = "{}")
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains("\"_var_panel__window__pending\":\"\""), body)
      }
    }
  }

  test(
    "a clear drops the choice: the chart and the commit at the declared value"
  ) {
    // The commit at the declared value is also what takes the URL parameter
    // away (`VarKey.urlMirrorAttr`).
    val key = VarKey(NodeId.derived("panel"), "window")
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        session <- ts.sessions.get(conn)
        _ <- choose(ts, conn, "window", "7d")
        _ <- drain(session)
        cleared <- ts.choose(conn, key, None)
        chose <- session.traverse(_.state.map(_.vars))
        queued <- drain(session)
      } yield {
        assertEquals(cleared._1, Status.NoContent, clue = cleared)
        assertEquals(chose, Some(Map.empty))
        val painted = queued.flatMap(_.data).mkString
        assert(painted.contains(Day) && !painted.contains(Week), painted)
        assertEquals(queued.lastOption.flatMap(_.data), Some(committed("24h")))
      }
    }
  }

  test("a clear writes no pending ask, a choice writes its own") {
    served(
      _.page().map { html =>
        assert(
          html.contains("$_var_panel__window__pending = '7d'; "),
          clue = html
        )
        // Only the four choices ask; `pending || committed` cannot hold
        // "nothing".
        assertEquals(
          // Non-empty: the shell's clear assigns `''`.
          "_var_panel__window__pending = '[^']+'; ".r.findAllMatchIn(html).size,
          4,
          clue = html
        )
      }
    )
  }

  test("a press under a conn this process forgot is refused, its ask cleared") {
    // A press cannot carry the viewer's other choices or open panels, so a
    // session minted from one would render against a guess.
    served { ts =>
      for {
        result <- choose(ts, "forgotten", "window", "7d")
        minted <- ts.sessions.get("forgotten")
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains(Server.NotConnectedMessage), clue = body)
        assert(body.contains("\"_var_panel__window__pending\":\"\""), body)
        assertEquals(minted, None)
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
        _ <- choose(ts, conn, "window", "24h")
        queued <- drain(session)
      } yield assertEquals(queued.flatMap(_.data), List(committed("24h")))
    }
  }

  test("a refusal ends the control's pending ask by name") {
    // The ask is the one the press wrote, from the declaration it chose for,
    // so a refusal ends it and no other.
    served { ts =>
      for {
        conn <- ts.load().map(_.conn)
        result <- choose(ts, conn, "window", "4h")
      } yield {
        val (status, body) = result
        assertEquals(status, Status.Ok)
        assert(body.contains("\"_var_panel__window__pending\":\"\""), body)
      }
    }
  }

  test(
    "a link carrying a value no reader can parse opens on the declared value"
  ) {
    // The URL once skipped the write's check: the page answered 200 and died
    // mid-walk. A stale link is dropped, not a 400: it outlives its layout.
    served(
      _.pageResponse("?v.panel.window=bogus")
        .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
        .map { (status, body) =>
          assertEquals(status, Status.Ok)
          assert(body.contains("_var_panel__window: '24h'"), clue = body)
          assert(!body.contains("bogus"), clue = body)
          assert(body.contains(Day), clue = body)
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
            choose(ts, conn, "w", "1h"),
            choose(ts, conn, "window", "7d")
          ).parTupled
          chose <- ts.sessions
            .get(conn)
            .flatMap(_.traverse(_.state.map(_.vars)))
        } yield assertEquals(
          chose.map(_.keySet.map(_.name)),
          Some(Set("w", "window"))
        ),
      dashboard = twoVarDash
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
    // A value one reader refuses must not cost the value carried beside it.
    served(
      ts =>
        for {
          client <- ts.connect(
            "?datastar=" + java.net.URLEncoder.encode(
              s"""{"${Server.ConnSignal}":"forgotten","_var_panel__window":"7d","_var_panel__w":"4h"}""",
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
      dashboard = twoVarDash
    )
  }

  test("a live session's own choice wins over what its reconnect carries") {
    // The stream that would have delivered a commit can die with it, so the
    // client may still hold the value before.
    served { ts =>
      for {
        doc <- ts.load()
        _ <- choose(ts, doc.conn, "window", "7d")
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
    cards = dash.cards + ("both" -> TestCard(
      """<span>s={{state}} w={{chart}}</span>"""
    )),
    card = LayoutNode.Component(
      "panel",
      slots = Slots.kids(
        LayoutNode.Component(
          "both",
          slots = Slots.values(
            chartNode.valueSlots +
              ("state" -> TemplateSlot(
                ValueExpr(Value.Entity(Some("sensor.a")))
              ))
          )
        )
      ),
      id = Some("panel"),
      vars = declared("window" -> "24h")
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
          _ <- choose(ts, doc.conn, "window", "7d")
          _ <- until(seen.get)(_.exists(_.data.exists(_.contains(Week))))
          _ <- choose(ts, doc.conn, "window", "1h")
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
    val renderer = Renderer(
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
