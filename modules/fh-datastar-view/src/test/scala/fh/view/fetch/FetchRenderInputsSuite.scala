package fh.view.fetch

import fh.view.fetch.{FetchRequest, FetchSnapshot}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  BoundSlot,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Reads,
  SignalBind,
  Choice,
  FetchTemplate,
  SlotAsk,
  SlotRead,
  Region,
  SlotShape,
  SlotSource,
  Surface,
  Transform
}
import api.homeassistant.ws.domain.{HistoryPoint, StatisticsPeriod}
import fh.view.history.{ChartStyle, History, SeriesSource, Window, Windows}
import fh.view.runtime.{EntityState, RenderInputs, Renderer, Selections}
import io.circe.Json
import io.github.iltotore.iron.*
import fh.view.testkit.TestIds.given
import fh.view.FHError
import fh.view.testkit.DashboardBuilders.declared

import java.time.Instant

/** A node reading a fetch contributes nothing to candidacy and everything to
  * the render key.
  */
class FetchRenderInputsSuite extends munit.CatsEffectSuite {

  /** A window written down resolves to itself in any environment, so the
    * assertions stay about the fetch slot, not variables. Named by preset.
    */
  private def chart(window: String = "24h") =
    FetchTemplate("sensor.t", Choice.Fixed(Windows.presets(window)))

  /** What the caches and the render key see. */
  private def resolved(window: String = "24h") =
    FetchRequest.History("sensor.t", Windows.presets(window))

  private def ask(window: String = "24h", width: Int = 600) =
    SlotAsk(chart(window), drawn(width))

  /** The size lives on the stage, so two sizes of one window are one fetch and
    * two drawings.
    */
  private def drawn(width: Int = 600) =
    Transform.Stage.Chart(ChartStyle(width = width.refineUnsafe))

  private def read(window: String = "24h", width: Int = 600) =
    SlotRead(resolved(window), drawn(width))

  private def chartSource(window: String = "24h", width: Int = 600) =
    SlotSource(
      fetch = Some(chart(window)),
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

  test("a fetch slot does not make its node a live candidate") {
    // A state tick does not re-render a chart, so a sensor moving every second
    // does not re-fetch its history every second. Both entity lists come from
    // state slots, and a fetch slot is not one, even at `reads`'s `live`
    // default.
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

  test("a fetch slot is not a signal slot, whatever it says") {
    // A `SlotShape.Fetch` has no signal to read, so the signal path cannot
    // reach it; no rule is needed.
    val src = chartSource().copy(
      signal = Some(SignalBind.Text),
      reads = Reads.Live
    )
    assertEquals(BoundSlot(src, Map.empty).shape, SlotShape.Fetch(ask()))
    assertEquals(
      LayoutNode
        .Component(card = "c", slots = Map("chart" -> src))
        .liveEntitiesAsBytes,
      Nil
    )
  }

  test("a node's fetches are its fetch slots, deduplicated") {
    assertEquals(chartNode().fetches, List(ask()))
    val two = LayoutNode.Component(
      card = "twoCharts",
      slots = Map(
        "a" -> chartSource("1h"),
        "b" -> chartSource("1h"),
        "c" -> chartSource("30d")
      )
    )
    assertEquals(two.fetches.toSet, Set(ask("1h"), ask("30d")))
  }

  test("the answers a render holds are total, and a miss is loud") {
    // A render has every answer or never starts (architecture §0), so an
    // unresolved fetch is loud, not an empty hole.
    val q = read()
    val f = FetchSnapshot.of(Map(q -> Staged(100L, "<svg/>")))
    assertEquals(f.versions("c_0", List(ask())), Map(q -> 100L))
    assertEquals(f.value("c_0", ask()), "<svg/>")

    val miss = intercept[FHError](FetchSnapshot.empty.value("c_0", ask()))
    assertEquals(miss.status, 500)
    assert(miss.getMessage.contains("not resolved for this render"))
  }

  private def source(
      rows: (String, Instant) => IO[List[HistoryPoint]]
  ): SeriesSource = new SeriesSource {
    def raw(start: Instant, end: Instant, entityId: String) =
      rows(entityId, end)
    def statistics(
        start: Instant,
        end: Instant,
        entityId: String,
        period: StatisticsPeriod
    ) = IO.pure(Nil)
  }

  /** Counting fetch and draw, with no JavaScript engine or HA, which is what
    * makes the two-level dedupe testable.
    */
  private def resolver(
      fetches: Ref[IO, Int],
      draws: Ref[IO, Int],
      failWidth: Option[Int] = None
  ): IO[FetchResolver] =
    for {
      history <- History.create(
        source((_, _) => fetches.update(_ + 1).as(Nil)),
        now = IO.pure(Instant.EPOCH)
      )
      r <- FetchResolver.create(
        history,
        IO.pure((_, style) =>
          draws.update(_ + 1) *>
            (if (failWidth.contains(style.width))
               IO.raiseError(RuntimeException("no engine"))
             else IO.pure(s"<svg>${style.width}</svg>"))
        )
      )
    } yield r

  test("two sizes of one window are ONE fetch and TWO drawings") {
    val wide = read(width = 600)
    val narrow = read(width = 320)
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      r <- resolver(fetches, draws)
      f <- FetchSnapshot.resolve(
        r,
        List(wide, narrow),
        Map.empty,
        FetchIdentity.Instance
      )
      counts <- (fetches.get, draws.get).tupled
    } yield {
      assertEquals(counts, (1, 2))
      assertEquals(f.value("n", ask(width = 600)), "<svg>600</svg>")
      assertEquals(f.value("n", ask(width = 320)), "<svg>320</svg>")
    }
  }

  test("two sensors over one window each get their own drawing") {
    // Same window, same bucket, version and style: only the question tells the
    // drawings apart.
    def sensor(e: String) =
      SlotAsk(
        FetchTemplate(e, Choice.Fixed(Windows.day)),
        drawn()
      )
    val a = sensor("sensor.a")
    val b = sensor("sensor.b")
    val reads = List(a, b).map(_.resolve(Map.empty))
    for {
      history <- History.create(
        source { (entityId, end) =>
          val v = if (entityId == "sensor.a") "1.0" else "2.0"
          IO.pure(List(HistoryPoint(v, end)))
        },
        now = IO.pure(Instant.EPOCH)
      )
      r <- FetchResolver.create(
        history,
        IO.pure((s, _) => IO.pure(s"<svg>${s.points.head.value}</svg>"))
      )
      f <- FetchSnapshot.resolve(
        r,
        reads,
        Map.empty,
        FetchIdentity.Instance
      )
    } yield {
      assertEquals(f.value("n", a), "<svg>1.0</svg>")
      assertEquals(f.value("n", b), "<svg>2.0</svg>")
    }
  }

  test("passthrough puts the provider's DATA in the hole, undrawn") {
    // The third-party contract: the JSON a client library reads, nothing drawn,
    // so cheaper than a chart.
    val raw = SlotRead(resolved(), Transform.Stage.Passthrough)
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      r <- resolver(fetches, draws)
      f <- FetchSnapshot.resolve(
        r,
        List(raw),
        Map.empty,
        FetchIdentity.Instance
      )
      d <- draws.get
    } yield {
      assertEquals(d, 0)
      val rawAsk = SlotAsk(chart(), Transform.Stage.Passthrough)
      assert(
        f.value("n", rawAsk).contains("\"points\""),
        clue = f.value("n", rawAsk)
      )
    }
  }

  test("a failing stage is that chart's error, not the render's") {
    // One failing recorder or drawing must not take the page, or the live
    // stream, down with it.
    val ok = read(width = 600)
    val bad = read(width = 1)
    for {
      fetches <- Ref[IO].of(0)
      draws <- Ref[IO].of(0)
      r <- resolver(fetches, draws, failWidth = Some(1))
      f <- FetchSnapshot.resolve(
        r,
        List(ok, bad),
        Map.empty,
        FetchIdentity.Instance
      )
    } yield {
      assertEquals(f.value("n", ask(width = 600)), "<svg>600</svg>")
      assertEquals(f.value("n", ask(width = 1)), Staged.failed(drawn()).value)
      // Below any real version, so the next good answer moves the key.
      assertEquals(
        f.versions("n", List(ask(width = 1))),
        Map(bad -> Staged.FailedVersion)
      )
    }
  }

  private def key(fetches: (SlotRead, Long)*) =
    RenderInputs(Map("sensor.t" -> 1L), fetches.toMap)

  test("a later version is ahead of an earlier one") {
    assert(key(read() -> 200L).isAtLeast(key(read() -> 100L)))
    assert(!key(read() -> 100L).isAtLeast(key(read() -> 200L)))
    assert(key(read() -> 100L).isAtLeast(key(read() -> 100L)))
  }

  test("different fetches are not ordered, so neither overwrites the other") {
    // Unordered means separate generations; otherwise one viewer is served a
    // chart of the wrong span, silently.
    assert(!key(read("24h") -> 100L).isAtLeast(key(read("1h") -> 100L)))
    assert(!key(read("1h") -> 100L).isAtLeast(key(read("24h") -> 100L)))
  }

  test("gaining a fetch is not being ahead") {
    assert(!key(read() -> 100L).isAtLeast(key()))
    assert(!key().isAtLeast(key(read() -> 100L)))
  }

  test("a moved fetch alone moves the key, with state standing still") {
    assertNotEquals(key(read() -> 100L), key(read() -> 200L))
  }

  test("a surface's fetches are found before it is rendered") {
    // More-info is a triggered surface, so what it reads must be answerable
    // from the static tree: a render is a synchronous string build, a provider
    // `IO`.
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
                      fetch = Some(chart()),
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
    assertEquals(
      r.fetchesForSurface("popup", Map.empty, Selections.none, Map.empty),
      List(read())
    )
    assertEquals(
      r.fetchesForSurface("nope", Map.empty, Selections.none, Map.empty),
      Nil
    )
  }

  test("a page resolves the tab it shows and the branch state picks, only") {
    // An unselected tab is fetched by its own switch and an inactive branch is
    // off screen; resolving either draws charts nobody sees.
    def panel(w: String) =
      LayoutNode.Component(
        card = "chart",
        slots = Map("chart" -> chartSource(w))
      )
    def host(id: String) = LayoutNode.Component(card = "host", id = Some(id))
    def baked(into: String, idx: Int, w: String, activation: Activation) =
      Surface(
        panel(w),
        bakeInto = Some(NodeId.derived(into)),
        bakeAs = Some("panel"),
        bakeIndex = Some(idx),
        activation = activation
      )
    val lightOn =
      Predicate.Cmp("state", Op.Eq, Json.fromString("on"), Some("light.a"))
    val r = Renderer.create(
      Dashboard(
        cards = Map(
          "chart" -> CardDef(
            """<div>{{{chart}}}</div>""",
            slots = List("chart")
          ),
          "col" -> CardDef(
            """<div>{{#children}}{{{html}}}{{/children}}</div>""",
            regions = Map("children" -> Region())
          ),
          "host" -> CardDef(
            """<div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
            regions = Map("panel" -> Region(Region.Baked))
          )
        ),
        card = LayoutNode.Component(
          card = "col",
          regions = LayoutNode.kids(
            host("tabs").copy(vars = declared("tab" -> "0")),
            host("branch")
          )
        ),
        surfaces = Map(
          "t0" -> baked("tabs", 0, "1h", Activation.Var("tab")),
          "t1" -> baked("tabs", 1, "7d", Activation.Var("tab")),
          "on" -> baked("branch", 0, "24h", Activation.State(lightOn)),
          "off" -> baked(
            "branch",
            1,
            "30d",
            Activation.State(Predicate.And(Nil))
          )
        )
      )
    )
    def light(s: String) =
      Map("light.a" -> EntityState("light.a", s, Map.empty))
    def windows(selected: String, states: Map[String, EntityState]) =
      r.fetchesForPage(Set(selected), states, Map.empty)
        .map(r =>
          Windows.presets
            .collectFirst {
              case (k, w) if r.fetch == FetchRequest.History("sensor.t", w) => k
            }
            .getOrElse(fail(s"not a preset: ${r.fetch}"))
        )
        .toSet
    assertEquals(windows("t0", light("on")), Set("1h", "24h"))
    assertEquals(windows("t1", light("off")), Set("7d", "30d"))
  }

  private def dashboard(src: SlotSource) =
    Dashboard(
      cards = Map("chart" -> CardDef("""<div id="{{id}}">{{{chart}}}</div>""")),
      card = LayoutNode.Component(card = "chart", slots = Map("chart" -> src))
    )

  test("an unknown provider fails the decode, naming the ones that exist") {
    // Otherwise the slot renders empty forever and never enters the key: a
    // blank chart with nothing saying why.
    val err = io.circe.parser
      .decode[FetchTemplate]("""{"provider": "forecast"}""")
      .fold(_.getMessage, t => fail(s"decoded $t"))
    assert(err.contains("unknown fetch provider"), clue = err)
    assert(err.contains("history"), clue = err)
  }

  test("an ESCAPED hole for a fetch slot is a build error") {
    // Otherwise `{{chart}}` renders `&lt;svg …` as text.
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
    assertEquals(errsFor("{{{ chart }}}"), Nil)
  }

  test("a fetch slot whose wire says 'live' is rejected as untruthful") {
    // Inert, since a `SlotShape.Fetch` never reads it: this keeps the wire from
    // lying. The Pkl default derives `onRender` from the fetch.
    val errs = dashboard(
      chartSource().copy(reads = Reads.Live)
    ).validate()
    assert(errs.exists(_.contains("never pushed")), clue = errs)
  }

  test("statistics at a period no shorter than the span is a build error") {
    // HA answers `hour` over one hour with nothing (measured), so the chart
    // would be blank forever.
    val errs = dashboard(
      SlotSource(
        fetch = Some(
          FetchTemplate(
            "sensor.t",
            Choice.Fixed(Window.Statistics(3600L, StatisticsPeriod.Hour))
          )
        ),
        transform = drawn(),
        reads = Reads.OnRender
      )
    ).validate()
    assert(errs.exists(_.contains("shorter than the span")), clue = errs)
  }
}
