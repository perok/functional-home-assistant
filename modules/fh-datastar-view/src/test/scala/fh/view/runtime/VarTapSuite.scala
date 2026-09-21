package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.syntax.all.*
import fh.view.history.{ChartStage, HistoryProvider, Series, SeriesProvider}
import fh.view.history.{SeriesStore, Window}
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
import fh.view.query.{QueryIdentity, QueryResolver}
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
    * A drawn chart could not do this — `ChartStage` is handed a `Series` and a
    * style, never the window — which is itself the split working: the drawing
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

  /** The span of whatever window was asked for, as the series' one point — so
    * `[[3600000,...]]` is an hour and `[[604800000,...]]` is a week.
    */
  private def resolver: IO[QueryResolver] =
    for {
      store <- SeriesStore.create(
        new SeriesProvider {
          def identify(req: Request[IO]) = IO.pure(QueryIdentity.Instance)
          def series(
              identity: QueryIdentity,
              entityId: String,
              window: Window,
              asOf: Instant
          ) = IO.pure(
            Series(
              Vector(
                Series.Point(
                  Instant.ofEpochMilli(window.span.toMillis),
                  1.0
                )
              ),
              0
            )
          )
        }
      )
      history <- HistoryProvider.create(store)
      stage <- ChartStage.create(IO.pure((_, _) => IO.pure("<svg/>")))
    } yield QueryResolver(history, stage)

  private def served[A](
      f: (HttpApp[IO], Sessions) => IO[A]
  ): IO[A] =
    (for {
      store <- StateStore.inMemory(Map("sensor.a" -> es("sensor.a", "1")))
      ref <- SignallingRef[IO].of(
        Server.RendererState.Ready(
          Renderer.fromValidated(
            dash.validated().fold(e => sys.error(e.mkString("; ")), identity)
          )
        )
      )
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      qr <- resolver
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
        queued <- session.flatTraverse(_.control.tryTake)
      } yield {
        assertEquals(status, Status.NoContent)
        assertEquals(
          chose,
          Some(Map(("panel": fh.view.model.NodeId, "window") -> "7d"))
        )
        // The patch carries the WEEK's series, which is the whole claim: the
        // write moved the query, not just a signal.
        assert(queued.flatMap(_.data).exists(_.contains(Week)), clue = queued)
        assert(!queued.flatMap(_.data).exists(_.contains(Day)), clue = queued)
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
        queued <- session.flatTraverse(_.control.tryTake)
      } yield {
        // ADR 0024: refused is a 200 of signals, not a 4xx.
        assertEquals(res.status, Status.Ok)
        assert(body.contains("4h"), clue = body)
        // The session keeps what it had — a refused write is not a half-write.
        assertEquals(chose, Some(Map.empty))
        assertEquals(queued, None)
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

  test("choosing the window already showing costs no patch") {
    // The suppression `Patches.resume` does per node, asked of one node: the
    // bytes did not move, so this client is owed nothing.
    served { (routes, sessions) =>
      for {
        conn <- connect(routes)
        session <- sessions.get(conn)
        _ <- post(routes, conn, "/sse/var/dashboard/panel/window/24h")
        queued <- session.flatTraverse(_.control.tryTake)
      } yield assertEquals(queued, None)
    }
  }
}
