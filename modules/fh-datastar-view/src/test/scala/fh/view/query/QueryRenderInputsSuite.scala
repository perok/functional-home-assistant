package fh.view.query

import fh.view.query.Fragments
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Reads,
  SignalBind,
  SlotQuery,
  SlotRead,
  Region,
  SlotShape,
  SlotSource,
  Surface,
  Transform
}
import fh.view.history.{
  ChartStage,
  HistoryProvider,
  Series,
  SeriesProvider,
  SeriesStore,
  Window
}
import fh.view.runtime.{RenderInputs, Renderer}
import fh.view.FHError

import java.time.Instant

/** What a node that reads a query contributes to the pipeline: nothing to
  * candidacy, everything to the render key.
  */
class QueryRenderInputsSuite extends munit.CatsEffectSuite {

  private def chart(window: String = "24h") =
    SlotQuery("history", Map("entity" -> "sensor.t", "window" -> window))

  /** A chart STAGE. The size lives here now rather than in the query, which is
    * what makes two sizes of one window one fetch and two drawings.
    */
  private def drawn(width: Int = 600) =
    Transform.Stage.Chart(Map("width" -> width.toString))

  private def read(window: String = "24h", width: Int = 600) =
    SlotRead(chart(window), drawn(width))

  private def chartSource(window: String = "24h", width: Int = 600) =
    SlotSource(
      query = Some(chart(window)),
      transform = drawn(width),
      reads = Reads.OnRender
    )

  private def chartNode(window: String = "24h") =
    LayoutNode.Component(
      card = "historyChart",
      slots = Map(
        "entity_id" -> SlotSource(literal = Some("sensor.t")),
        "chart" -> chartSource(window),
        "name" -> SlotSource(transform = "state")
      )
    )

  // --- Candidacy ------------------------------------------------------------

  test("a query slot does not make its node a live candidate") {
    // The claim the whole design rests on: a chart is not re-rendered by a
    // state tick, so a sensor moving every second does not re-fetch its own
    // history every second. Nothing arranges it — both entity lists are built
    // from STATE slots, and a query slot is not one, so it has no entity to
    // contribute even though it leaves `reads` at its `live` default.
    assertEquals(chartNode().liveEntities, List("sensor.t")) // from `name`
    assertEquals(
      LayoutNode
        .Component(
          card = "historyChart",
          slots = Map(
            "entity_id" -> SlotSource(literal = Some("sensor.t")),
            "chart" -> chartSource()
          )
        )
        .liveEntities,
      Nil
    )
  }

  test("a query slot is not a signal slot, whatever it says") {
    // Not a rule that rejects the combination — a `SlotShape.Query` has no
    // signal to read, so the signal path cannot reach it.
    val src = chartSource().copy(
      signal = Some(SignalBind.Text),
      reads = Reads.Live
    )
    assertEquals(src.shape, SlotShape.Query(read()))
    assertEquals(
      LayoutNode
        .Component(card = "c", slots = Map("chart" -> src))
        .liveEntitiesAsBytes,
      Nil
    )
  }

  test("a node's queries are its query slots, deduplicated") {
    assertEquals(chartNode().queries, List(read()))
    val two = LayoutNode.Component(
      card = "twoCharts",
      slots = Map(
        "a" -> chartSource("1h"),
        "b" -> chartSource("1h"),
        "c" -> chartSource("30d")
      )
    )
    assertEquals(two.queries.toSet, Set(read("1h"), read("30d")))
  }

  // --- The snapshot ---------------------------------------------------------

  test("the answers a render holds are total, and a miss is loud") {
    // This replaced "a missing query is absent from the key, not zero", which
    // was the right rule for a snapshot that could be PARTIAL. It cannot be
    // now: a render either has every answer or never starts (architecture §0),
    // so the distinction that rule protected has nothing left to describe.
    // What is worth asserting instead is that the case it guarded against —
    // rendering a query nobody resolved — is loud rather than an empty hole,
    // because that is the shape the defect took.
    val q = read()
    val f = Fragments.of(Map(q -> Fragment(100L, "<svg/>")))
    assertEquals(f.forQueries(List(q)), Map(q -> 100L))
    assertEquals(f.html(q), "<svg/>")

    val miss = intercept[FHError](Fragments.empty.html(q))
    assertEquals(miss.status, 500)
    assert(miss.getMessage.contains("not resolved for this render"))
  }

  /** A resolver over a counting fetch and a counting draw. Neither needs a
    * JavaScript engine or an HA connection, which is what makes the two-level
    * dedupe testable at all.
    */
  private def resolver(
      fetches: Ref[IO, Int],
      draws: Ref[IO, Int],
      failWidth: Option[Int] = None
  ): IO[QueryResolver] =
    for {
      store <- SeriesStore.create(
        new SeriesProvider {
          def identify(req: org.http4s.Request[IO]) =
            IO.pure(QueryIdentity.Instance)
          def series(
              identity: QueryIdentity,
              entityId: String,
              window: Window,
              asOf: Instant
          ) = fetches.update(_ + 1).as(Series(Vector.empty, 0))
        }
      )
      history <- HistoryProvider.create(store)
      stage <- ChartStage.create(
        IO.pure((_, style) =>
          draws.update(_ + 1) *>
            (if (failWidth.contains(style.width))
               IO.raiseError(RuntimeException("no engine"))
             else IO.pure(s"<svg>${style.width}</svg>"))
        )
      )
    } yield QueryResolver(history, stage)

  private def plan(reads: SlotRead*) =
    reads.map(r => r -> Queries.parseRead(r).fold(e => fail(e), identity)).toMap

  test("two sizes of one window are ONE fetch and TWO drawings") {
    // The property the split exists for, and the one no single component can
    // assert any more: the provider deduplicates by QUERY, the stage by
    // (query, stage). It used to be arranged inside `HistoryProvider` by two
    // private caches, with a test pinning down an implementation choice; it is
    // what the keys say now.
    val wide = read(width = 600)
    val narrow = read(width = 320)
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      r <- resolver(fetches, draws)
      f <- Fragments.resolve(
        r,
        plan(wide, narrow),
        List(wide, narrow),
        QueryIdentity.Instance,
        Instant.EPOCH
      )
      counts <- (fetches.get, draws.get).tupled
    } yield {
      assertEquals(counts, (1, 2))
      assertEquals(f.html(wide), "<svg>600</svg>")
      assertEquals(f.html(narrow), "<svg>320</svg>")
    }
  }

  test("two sensors over one window each get their own drawing") {
    // Same window means same bucket, so same version and same style: only the
    // question tells the two drawings apart.
    def sensor(e: String) =
      SlotRead(
        SlotQuery("history", Map("entity" -> e, "window" -> "24h")),
        drawn()
      )
    val a = sensor("sensor.a")
    val b = sensor("sensor.b")
    for {
      store <- SeriesStore.create(
        new SeriesProvider {
          def identify(req: org.http4s.Request[IO]) =
            IO.pure(QueryIdentity.Instance)
          def series(
              identity: QueryIdentity,
              entityId: String,
              window: Window,
              asOf: Instant
          ) = IO.pure(
            Series(
              Vector(Series.Point(asOf, if (entityId == "sensor.a") 1 else 2)),
              0
            )
          )
        }
      )
      history <- HistoryProvider.create(store)
      stage <- ChartStage.create(
        IO.pure((s, _) => IO.pure(s"<svg>${s.points.head.value}</svg>"))
      )
      f <- Fragments.resolve(
        QueryResolver(history, stage),
        plan(a, b),
        List(a, b),
        QueryIdentity.Instance,
        Instant.EPOCH
      )
    } yield {
      assertEquals(f.html(a), "<svg>1.0</svg>")
      assertEquals(f.html(b), "<svg>2.0</svg>")
    }
  }

  test("passthrough puts the provider's DATA in the hole, undrawn") {
    // The third-party contract: no transform, no drawing, and the JSON a
    // client library would read. Nothing is drawn at all, which is what makes
    // this cheaper than a chart rather than a chart nobody looks at.
    val raw = SlotRead(chart(), Transform.Stage.Passthrough)
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      r <- resolver(fetches, draws)
      f <- Fragments.resolve(
        r,
        plan(raw),
        List(raw),
        QueryIdentity.Instance,
        Instant.EPOCH
      )
      d <- draws.get
    } yield {
      assertEquals(d, 0)
      assert(f.html(raw).contains("\"points\""), clue = f.html(raw))
    }
  }

  test("a failing stage fails the render rather than leaving a hole") {
    // A page carrying one good chart and one blank one is exactly the
    // incomplete first paint the rule forbids, so the whole render goes.
    val ok = read(width = 600)
    val bad = read(width = 1)
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      r <- resolver(fetches, draws, failWidth = Some(1))
      raised <- Fragments
        .resolve(
          r,
          plan(ok, bad),
          List(ok, bad),
          QueryIdentity.Instance,
          Instant.EPOCH
        )
        .attempt
    } yield raised.left
      .getOrElse(fail("a failing drawing must fail resolve")) match {
      case e: FHError =>
        // 503 and not 500: the dashboard is fine, the recorder or the engine
        // is not, so this is "come back" rather than "this build is broken".
        assertEquals(e.status, 503)
        // Naming the query is what makes a blank chart diagnosable at all,
        // which is what the old log line did before failure became terminal.
        assert(e.getMessage.contains("history"), clue = e.getMessage)
        assert(e.getMessage.contains("no engine"), clue = e.getMessage)
      case other => fail(s"expected an FHError, got $other")
    }
  }

  // --- The partial order ----------------------------------------------------

  private def key(queries: (SlotRead, Long)*) =
    RenderInputs(Map("sensor.t" -> 1L), queries.toMap)

  test("a later version is ahead of an earlier one") {
    assert(key(read() -> 200L).isAtLeast(key(read() -> 100L)))
    assert(!key(read() -> 100L).isAtLeast(key(read() -> 200L)))
    assert(key(read() -> 100L).isAtLeast(key(read() -> 100L)))
  }

  test("different queries are not ordered, so neither overwrites the other") {
    // Two viewers on different windows read different queries. Unordered
    // means separate generations — the alternative is one of them being served
    // a chart of the wrong span, which is the silent failure `RenderInputs`
    // warns about in its own doc.
    assert(!key(read("24h") -> 100L).isAtLeast(key(read("1h") -> 100L)))
    assert(!key(read("1h") -> 100L).isAtLeast(key(read("24h") -> 100L)))
  }

  test("gaining a query is not being ahead") {
    assert(!key(read() -> 100L).isAtLeast(key()))
    assert(!key().isAtLeast(key(read() -> 100L)))
  }

  test("a moved query alone moves the key, with state standing still") {
    assertNotEquals(key(read() -> 100L), key(read() -> 200L))
  }

  // --- What a surface owes before it renders --------------------------------

  test("a surface's queries are found before it is rendered") {
    // The popup path: more-info is a triggered surface, so what it reads has
    // to be answerable from the STATIC tree — a render is a synchronous string
    // build, and a provider is `IO`.
    val d = Dashboard(
      cards = Map(
        "chart" -> CardDef("""<div>{{{chart}}}</div>"""),
        "col" -> CardDef(
          """<div>{{#children}}{{{html}}}{{/children}}</div>""",
          regions = Map("children" -> Region())
        )
      ),
      card = LayoutNode.Component(card = "col"),
      surfaces = Map(
        "popup" -> Surface(
          content = LayoutNode.Component(
            card = "col",
            regions = Map(
              "children" -> List(
                LayoutNode.Component(
                  card = "chart",
                  slots = Map(
                    "chart" -> SlotSource(
                      query = Some(chart()),
                      transform = drawn(),
                      reads = Reads.OnRender
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
    val r = Renderer.create(d)
    assertEquals(r.queriesForSurface("popup"), List(read()))
    // A surface nobody declared owes nothing, rather than raising.
    assertEquals(r.queriesForSurface("nope"), Nil)
  }

  // --- Validation -----------------------------------------------------------

  private def dashboard(src: SlotSource) =
    Dashboard(
      cards = Map("chart" -> CardDef("""<div id="{{id}}">{{{chart}}}</div>""")),
      card = LayoutNode.Component(card = "chart", slots = Map("chart" -> src))
    )

  test("an unknown provider is a build error naming the ones that exist") {
    // Silent otherwise: no provider, no fragment, so the slot renders empty
    // forever and never enters the render key — a blank chart with nothing
    // anywhere saying why.
    val errs = dashboard(
      SlotSource(
        query = Some(SlotQuery("forecast", Map.empty)),
        reads = Reads.OnRender
      )
    ).validate()
    assert(errs.exists(_.contains("unknown query provider")), clue = errs)
    assert(errs.exists(_.contains("history")), clue = errs)
  }

  test("an ESCAPED hole for a query slot is a build error") {
    // Silent otherwise, and visibly wrong only to whoever opens the page:
    // `{{chart}}` renders `&lt;svg …` as text.
    def errsFor(hole: String) =
      Dashboard(
        cards = Map("chart" -> CardDef(s"""<div id="{{id}}">$hole</div>""")),
        card = LayoutNode.Component(
          card = "chart",
          slots = Map(
            "chart" -> chartSource()
          )
        )
      ).validate()

    assert(
      errsFor("{{chart}}").exists(_.contains("ESCAPED hole")),
      clue = errsFor("{{chart}}")
    )
    assertEquals(errsFor("{{{chart}}}"), Nil)
    // Mustache allows the whitespace, so the check must too.
    assertEquals(errsFor("{{{ chart }}}"), Nil)
  }

  test("a query slot whose wire says 'live' is rejected as untruthful") {
    // The field is INERT — a `SlotShape.Query` never reads it — so this is not
    // about behaviour. It is about the wire not lying to whoever reads it: the
    // Pkl default derives `onRender` from the query, and a hand-written one
    // that says otherwise is a build error rather than a misleading document.
    val errs = dashboard(
      chartSource().copy(reads = Reads.Live)
    ).validate()
    assert(errs.exists(_.contains("never pushed")), clue = errs)
  }

  test("the provider's own parse error is the build error") {
    // No wiring: parsing is pure, so a dashboard is checked wherever it is
    // built rather than only where a provider happened to be passed in.
    val errs = dashboard(
      SlotSource(
        query = Some(SlotQuery("history", Map())),
        reads = Reads.OnRender
      )
    )
      .validate()
    assert(errs.exists(_.contains("'entity' parameter")), clue = errs)
  }
}
