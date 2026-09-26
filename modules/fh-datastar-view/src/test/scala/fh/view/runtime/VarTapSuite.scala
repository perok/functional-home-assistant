package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{Deferred, IO}
import cats.effect.kernel.Ref as CeRef
import cats.syntax.all.*
import api.homeassistant.ws.domain.{HistoryPoint, StatisticsPeriod}
import fh.view.history.{History, SeriesSource}
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  QueryTemplate,
  Reads,
  Ref,
  Region,
  SlotSource,
  Transform
}
import fh.view.query.QueryResolver
import fh.view.testkit.{FakeHomeAssistant, TestAuth}
import fh.view.testkit.TestIds.given
import fs2.concurrent.SignallingRef
import org.http4s.*
import org.http4s.implicits.*

import java.time.Instant
import scala.concurrent.duration.*

/** WRITING a node variable (issue #209): the tap that moves a chart's window,
  * and what happens to a value the chart could not draw.
  *
  * The vertical slice this feature exists for. Everything below it — the
  * declaration, the scope chain, the ask/read split — is only worth having if
  * this works, so this is the suite that says whether it does.
  */
class VarTapSuite extends ServerHarness {

  // Opens a DOCUMENT, whose body streams through a blocking pipe simulated
  // time cannot host.
  override protected def simulateTime: Boolean = false

  /** A passthrough chart, so the test can read the WINDOW back out of the
    * rendered value: the fake provider puts the window's span in the one point
    * it answers with, and passthrough puts that JSON in the hole.
    *
    * A drawn chart could not do this — the chart stage is handed a `Series` and
    * a style, never the window — which is itself the split working: the drawing
    * does not know what was asked.
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

  /** A chart whose ENTITY is the variable, beside a card naming `sensor.b` —
    * the dashboard ADR 0023's bound is drawn around.
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

  /** The span of whatever window was asked for, as the series' one point — so
    * `[[3600000,...]]` is an hour and `[[604800000,...]]` is a week.
    */
  private def resolver: IO[QueryResolver] =
    for {
      history <- History.create(new SeriesSource {
        def raw(start: Instant, end: Instant, entityId: String) =
          IO.pure(
            List(
              HistoryPoint(
                "1.0",
                Instant.ofEpochMilli(end.toEpochMilli - start.toEpochMilli)
              )
            )
          )
        def statistics(
            start: Instant,
            end: Instant,
            entityId: String,
            period: StatisticsPeriod
        ) = IO.pure(Nil)
      })
      r <- QueryResolver.create(history, IO.pure((_, _) => IO.pure("<svg/>")))
    } yield r

  private def served[A](
      f: (HttpApp[IO], Sessions) => IO[A],
      queries: IO[QueryResolver] = resolver,
      dashboard: Dashboard = dash
  ): IO[A] =
    (for {
      store <- StateStore.inMemory(
        Map(
          "sensor.a" -> es("sensor.a", "1"),
          "sensor.b" -> es("sensor.b", "2")
        )
      )
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(
          Renderer.fromValidated(
            dashboard
              .validated()
              .fold(e => sys.error(e.mkString("; ")), identity)
          )
        )
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      qr <- queries
      out <- Server
        .withSite(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Server.LiveSite
            .of(Map("dashboard" -> ref), Map.empty, "dashboard")
            .unsafeRunSync(),
          sessions,
          TestAuth.openGate,
          AssetCache.empty,
          fs2.concurrent.Signal.constant(true),
          fh.view.build.SystemPkl.empty,
          None,
          adoptionWindow = 5.seconds,
          queries = Some(qr)
        )
        .use(server => f(server.routes.orNotFound, sessions))
    } yield out).timeout(30.seconds)

  /** Open the page and pull the `conn` its `data-init` carries. */
  private def connect(routes: HttpApp[IO]): IO[String] =
    routes
      .run(Request[IO](Method.GET, uri"/d/dashboard"))
      .flatMap(_.bodyText.compile.string)
      .map(page =>
        Uri
          .unsafeFromString(
            "/" + page
              .split("""data-init="@get\('""")(1)
              .split("'")(0)
              .replace("&amp;", "&")
          )
          .query
          .params(Server.ConnSignal)
      )

  private def post(routes: HttpApp[IO], conn: String, path: String) =
    routes.run(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
        .withEntity(s"""{"${Server.ConnSignal}":"$conn"}""")
    )

  private val Day = 24.hours.toMillis.toString
  private val Week = 7.days.toMillis.toString

  /** Everything the write offered this client, in order. Drained rather than
    * taken one at a time because the COUNT is half of what these assert: ADR
    * 0025 says a refused write offers nothing and an accepted one always
    * commits, so "and nothing else" is the claim.
    */
  private def drain(session: Option[Session]): IO[List[SseFrame]] =
    session.fold(IO.pure(List.empty[SseFrame]))(s => s.control.tryTakeN(None))

  /** The committed frame ADR 0025 ends the ask with — what the control's
    * highlight falls back to once its pending value clears.
    */
  private def committed(value: String): String =
    s"""signals {"_var_panel__window":"$value"}"""

  test("a chart whose recorder fails costs that chart, not the page") {
    val down = for {
      history <- History.create(new SeriesSource {
        def raw(start: Instant, end: Instant, entityId: String) =
          IO.raiseError(RuntimeException("recorder is down"))
        def statistics(
            start: Instant,
            end: Instant,
            entityId: String,
            period: StatisticsPeriod
        ) = IO.pure(Nil)
      })
      r <- QueryResolver.create(history, IO.pure((_, _) => IO.pure("<svg/>")))
    } yield r
    served(
      (routes, _) =>
        routes
          .run(Request[IO](Method.GET, uri"/d/dashboard"))
          .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
          .map { case (status, page) =>
            assertEquals(status, Status.Ok)
            assert(page.contains("<span></span>"), clue = page)
          },
      down
    )
  }

  test("the head is sent before a slow chart is answered") {
    // The head reads no query, so a cold fetch overlaps the browser fetching
    // stylesheets rather than holding up the first byte.
    (Deferred[IO, Unit], CeRef[IO].of(false), CeRef[IO].of(false)).tupled
      .flatMap { case (gate, fetched, headFirst) =>
        val slow = for {
          history <- History.create(new SeriesSource {
            def raw(start: Instant, end: Instant, entityId: String) =
              gate.get *> fetched.set(true).as(List(HistoryPoint("1.0", end)))
            def statistics(
                start: Instant,
                end: Instant,
                entityId: String,
                period: StatisticsPeriod
            ) = IO.pure(Nil)
          })
          r <- QueryResolver.create(
            history,
            IO.pure((_, _) => IO.pure("<svg/>"))
          )
        } yield r
        served(
          (routes, _) =>
            routes
              .run(Request[IO](Method.GET, uri"/d/dashboard"))
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
          slow
        )
      }
  }

  test("writing the variable re-renders the chart at the new window") {
    served { (routes, sessions) =>
      for {
        conn <- connect(routes)
        // The page was rendered at the DECLARED window, which is what a viewer
        // who has chosen nothing gets.
        session <- sessions.get(conn)
        status <- post(routes, conn, "/sse/var/dashboard/panel/window/7d")
          .map(_.status)
        chose <- session.traverse(_.vars.get)
        queued <- drain(session)
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(
          chose,
          Some(Map(("panel": fh.view.model.NodeId, "window") -> "7d"))
        )
        // The patch carries the WEEK's series, which is the whole claim: the
        // write moved the query, not just a signal.
        val painted = queued.flatMap(_.data).mkString
        assert(painted.contains(Week), clue = painted)
        assert(!painted.contains(Day), clue = painted)
        // And the commit rides LAST, after the bytes it describes — so the
        // highlight stops being a guess only once the chart beneath it agrees.
        assertEquals(queued.lastOption.flatMap(_.data), Some(committed("7d")))
      }
    }
  }

  test("a value no reader can parse is refused, and nothing moves") {
    // The narrowing, and the reason a declaration needs no list of allowed
    // values: `HistoryQuery.parse` is the authority on what a window may be,
    // and a list beside the declaration could only copy it.
    served { (routes, sessions) =>
      for {
        conn <- connect(routes)
        session <- sessions.get(conn)
        res <- post(routes, conn, "/sse/var/dashboard/panel/window/4h")
        body <- res.bodyText.compile.string
        chose <- session.traverse(_.vars.get)
        queued <- drain(session)
      } yield {
        // ADR 0024: refused is a 200 of signals, not a 4xx.
        assertEquals(res.status, Status.Ok)
        assert(body.contains("4h"), clue = body)
        // The session keeps what it had — a refused write is not a half-write.
        assertEquals(chose, Some(Map.empty))
        // Nothing committed either, which is what lets the control show the
        // press optimistically: the pending value is ended by the REFUSAL's own
        // signal frame (`Server.actionSignals`, the `group` query param), so it
        // falls back to a committed value that never moved.
        assertEquals(queued, Nil)
      }
    }
  }

  test("a variable nothing declares is a 404, not a silent no-op") {
    served { (routes, sessions) =>
      for {
        conn <- connect(routes)
        res <- post(routes, conn, "/sse/var/dashboard/panel/nosuch/7d")
        chose <- sessions.get(conn).flatMap(_.traverse(_.vars.get))
      } yield {
        // ADR 0024: a refused ACTION is a 200 carrying signals, never a 4xx —
        // the request was served and the operation failed, which is page state.
        assertEquals(res.status, Status.Ok)
        assertEquals(chose, Some(Map.empty))
      }
    }
  }

  test("choosing the window already showing costs no element patch") {
    // The suppression `Patches.resume` does per node, asked of one node: the
    // bytes did not move, so nothing is re-sent. The COMMIT still is — a
    // control that pressed `24h` while on `24h` has an outstanding pending
    // value, and only a committed value agreeing with it ends that ask.
    served { (routes, sessions) =>
      for {
        conn <- connect(routes)
        session <- sessions.get(conn)
        _ <- post(routes, conn, "/sse/var/dashboard/panel/window/24h")
        queued <- drain(session)
      } yield assertEquals(queued.flatMap(_.data), List(committed("24h")))
    }
  }

  test("a refusal ends the control's pending ask by name") {
    // The URL the chooser actually posts (`components/history.pkl`), whose
    // `group` is what lets a refusal end THIS control's ask and no other —
    // ADR 0024's half of ADR 0025.
    served { (routes, _) =>
      for {
        conn <- connect(routes)
        res <- post(
          routes,
          conn,
          "/sse/var/dashboard/panel/window/4h?group=var_panel__window"
        )
        body <- res.bodyText.compile.string
      } yield {
        assertEquals(res.status, Status.Ok)
        assert(body.contains("\"_var_panel__window__pending\":\"\""), body)
      }
    }
  }

  test("a variable moves a chart only to an entity its dashboard shows") {
    // ADR 0023's bound on the read side, at both places a value arrives: a
    // write and a page URL. `sensor.b` is on the dashboard; the lock is not,
    // and the recorder must never be asked for it.
    CeRef[IO].of(Set.empty[String]).flatMap { asked =>
      val recording = for {
        history <- History.create(new SeriesSource {
          def raw(start: Instant, end: Instant, entityId: String) =
            asked.update(_ + entityId).as(List(HistoryPoint("1.0", end)))
          def statistics(
              start: Instant,
              end: Instant,
              entityId: String,
              period: StatisticsPeriod
          ) = IO.pure(Nil)
        })
        r <- QueryResolver.create(history, IO.pure((_, _) => IO.pure("<svg/>")))
      } yield r
      served(
        (routes, sessions) =>
          for {
            conn <- connect(routes)
            named <- post(routes, conn, "/sse/var/dashboard/panel/e/sensor.b")
            refused <- post(
              routes,
              conn,
              "/sse/var/dashboard/panel/e/lock.front_door"
            )
            chose <- sessions.get(conn).flatMap(_.traverse(_.vars.get))
            linked <- routes.run(
              Request[IO](
                Method.GET,
                uri"/d/dashboard?v.panel.e=lock.front_door"
              )
            )
            entities <- asked.get
          } yield {
            assertEquals(named.status, Status.NoContent)
            assertEquals(refused.status, Status.Ok)
            assertEquals(
              chose,
              Some(Map(("panel": fh.view.model.NodeId, "e") -> "sensor.b"))
            )
            assertEquals(linked.status, Status.BadRequest)
            assertEquals(entities, Set("sensor.a", "sensor.b"))
          },
        recording,
        entityDash
      )
    }
  }

  test(
    "a link carrying a value no reader can parse is a 400, not a torn page"
  ) {
    // The URL is the second way a value arrives, and it once skipped the
    // write's check: the page answered 200 and died mid-walk.
    served { (routes, _) =>
      routes
        .run(Request[IO](Method.GET, uri"/d/dashboard?v.panel.window=bogus"))
        .flatMap(r => r.bodyText.compile.string.map(r.status -> _))
        .map { case (status, body) =>
          assertEquals(status, Status.BadRequest)
          assert(body.contains("bogus"), clue = body)
        }
    }
  }

  test("two choices landing together both stick") {
    // Each write validates against, and commits onto, the other's result.
    served(
      (routes, sessions) =>
        for {
          conn <- connect(routes)
          _ <- (
            post(routes, conn, "/sse/var/dashboard/panel/e/sensor.b"),
            post(routes, conn, "/sse/var/dashboard/panel/window/7d")
          ).parTupled
          chose <- sessions.get(conn).flatMap(_.traverse(_.vars.get))
        } yield assertEquals(
          chose.map(_.keySet.map(_._2)),
          Some(Set("e", "window"))
        ),
      dashboard = entityDash
    )
  }

  test("the opening frame states every declared variable, chosen or not") {
    // TOTAL over the declarations, which is what makes a lost session safe: a
    // control still highlighting last session's window is corrected on connect
    // rather than disagreeing with the chart beside it.
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
        Map((("panel": fh.view.model.NodeId), "window") -> "7d"),
        "log",
        0L
      )
      .data
      .getOrElse("")
    assert(declared.contains("\"_var_panel__window\":\"24h\""), declared)
    assert(chosen.contains("\"_var_panel__window\":\"7d\""), chosen)
  }
}
