package fh.view.runtime

import fh.view.testkit.TestCard
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Part,
  Region,
  SignalBind,
  Value,
  ValueExpr,
  TemplateSlot,
  Surface
}
import fh.view.testkit.DashboardBuilders.{declared, st}
import fh.view.testkit.TestIds.given

import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** What N viewers cost when their selections differ, so they could hold
  * different byte-slot values for one node id. Before the cache bucketed on
  * selection they evicted each other every frame. The numbers are floors: a
  * rise means the sharing was lost.
  */
class RenderCacheContentionSuite extends ServerHarness {

  override protected def simulateTime: Boolean = true

  /** `c_0` is the tabs host, structure that renders nothing per frame. [[Live]]
    * is the leaf in its `bar` region, whose bytes mention no selection, so
    * every viewer shares them.
    */
  private val Live: NodeId = "c_0_bar_0"

  private def leafDash = Dashboard(
    cards = Map(
      "col" -> TestCard(
        "<div>{{{children}}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> TestCard("<span>{{state}}</span>"),
      "tabs" -> TestCard(
        template =
          """{{{bar}}}<div id="{{hostId}}" class="tabs">{{{panel}}}</div>""",
        regions = Map("bar" -> Region(), "panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "tabs",
          regions = Map(
            "bar" -> List(
              LayoutNode.Component(
                "card",
                templateSlots = Map(
                  "state" -> TemplateSlot(
                    ValueExpr(Value.Entity(Some("sensor.shared")))
                  )
                )
              )
            )
          ),
          vars = declared("tab" -> "0")
        )
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "card",
          templateSlots = Map(
            "state" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.a")))
            )
          )
        ),
        bakeInto = Some("c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.Var("tab")
      ),
      "t1" -> Surface(
        LayoutNode.Component(
          "card",
          templateSlots = Map(
            "state" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.b")))
            )
          )
        ),
        bakeInto = Some("c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1),
        activation = Activation.Var("tab")
      )
    )
  )

  private def plainDash = Dashboard(
    cards = Map(
      "col" -> TestCard(
        "<div>{{{children}}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> TestCard("<span>{{state}}</span>")
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          templateSlots = Map(
            "state" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.shared")))
            )
          )
        )
      )
    )
  )

  /** `c_0` reads the shared sensor AND the viewer's `window`, declared on the
    * root `c`, so its bytes are each value's.
    */
  private def varDash = Dashboard(
    cards = Map(
      "col" -> TestCard(
        "<div>{{{children}}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> TestCard("<span>{{state}} {{window}}</span>")
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          templateSlots = Map(
            "state" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.shared")))
            ),
            "window" -> TemplateSlot(ValueExpr(Value.Var("window")))
          )
        )
      ),
      vars = declared("window" -> "24h")
    )
  )

  /** What a reconnect carries: the committed value, adopted by the session. */
  private def onWindow(value: String): String =
    "?datastar=" + java.net.URLEncoder.encode(
      s"""{"_var_c__window":"$value"}""",
      "UTF-8"
    )

  private val initial = Map(
    "sensor.shared" -> st("sensor.shared", "0"),
    "sensor.a" -> st("sensor.a", "a"),
    "sensor.b" -> st("sensor.b", "b")
  )

  /** Per node: a total cannot tell a second viewer's miss from a member render
    * that happens anyway.
    */
  private class PerNode(v: Dashboard.Validated) extends Renderer(v) {
    private val counts =
      new java.util.concurrent.ConcurrentHashMap[NodeId, AtomicInteger]()

    override def renderPatch(
        id: NodeId,
        shared: Shared,
        values: ByteValues
    ): Option[String] = {
      val _ = counts
        .computeIfAbsent(id, _ => new AtomicInteger(0))
        .incrementAndGet()
      super.renderPatch(id, shared, values)
    }

    def reset: IO[Unit] = IO(counts.clear())
    def tally: IO[Map[NodeId, Int]] =
      IO(counts.asScala.map((k, v) => k -> v.get()).toMap)
  }

  /** Reset after everyone connects, so only steady-state live pulls are
    * measured.
    *
    * On [[LiveWorld]], not [[TestServer]]: the counting renderer has no way
    * into [[ServerApp.assemble]], and a renderer factory on its `Prepared`
    * would be a production seam for this suite alone. What that skips is the
    * feed, narrowing and the auth routes, none of which renders. Not lower
    * either: driving `Server.pull` per viewer passes too, but only because the
    * recorder, the doorbell loop and the route's selection render nothing —
    * which is part of what this measures.
    */
  private def rendersPerFrame(
      dash: Dashboard,
      queries: List[String],
      frames: Int,
      node: NodeId
  ): IO[Double] = {
    val renderer =
      new PerNode(dash.validated().fold(e => fail(e.mkString("\n")), identity))
    liveWorldOf(renderer, initial) { world =>
      for {
        _ <- queries.traverse_(world.connect(_))
        _ <- renderer.reset
        _ <- (1 to frames).toList.traverse_(i =>
          world.change(st("sensor.shared", i.toString))
        )
      } yield ()
    } *> renderer.tally.map(_.getOrElse(node, 0).toDouble / frames)
  }

  private val Frames = 8

  private def assertCost(
      label: String,
      qs: List[String],
      dash: Dashboard,
      expected: Double,
      frames: Int = Frames,
      node: NodeId = "c_0"
  ): IO[Unit] =
    rendersPerFrame(dash, qs, frames, node).flatMap(got =>
      IO(assertEquals(got, expected, s"$label (${qs.size} viewers)"))
    )

  test("one selection is one render a frame, however many viewers hold it") {
    assertCost("no bake group, 3 viewers", List.fill(3)(""), plainDash, 1.0) *>
      assertCost(
        "beside a bake owner, 4 viewers on one tab",
        List.fill(4)(""),
        leafDash,
        1.0,
        frames = 5,
        node = Live
      )
  }

  /** A bake owner is structure, never cached or rendered per frame; the leaf
    * beside it is owed the same bytes on any tab. So the floor is 1.0 at any
    * mix; 2.0 would mean cost follows selection and a cache keyed on entities
    * alone evicts one tab's bytes for the other's.
    */
  test("cost does not follow selections — one render serves both tabs") {
    assertCost(
      "1+1 on two tabs",
      List("", onTabs("c_0" -> 1)),
      leafDash,
      1.0,
      node = Live
    ) *>
      assertCost(
        "2+2 on two tabs",
        List("", "", onTabs("c_0" -> 1), onTabs("c_0" -> 1)),
        leafDash,
        1.0,
        node = Live
      ) *>
      assertCost(
        "3+3 on two tabs",
        List.fill(3)("") ++ List.fill(3)(onTabs("c_0" -> 1)),
        leafDash,
        1.0,
        frames = 5,
        node = Live
      ) *>
      assertCost(
        "the structural owner",
        List("", onTabs("c_0" -> 1)),
        leafDash,
        0.0
      )
  }

  /** The shipped card's shape: the state travels as a signal, and one byte slot
    * reads the same entity, which puts it in the render key. A state change
    * moves the key but not the bytes, so no session should render.
    */
  private def signalDash = Dashboard(
    cards = Map(
      "col" -> TestCard(
        "<div>{{{children}}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> TestCard("<b {{{state.bind}}}>{{state}}</b><i>{{name}}</i>")
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          templateSlots = Map(
            "state" -> TemplateSlot(
              ValueExpr(Value.Entity(Some("sensor.shared"))),
              signal = Some(SignalBind.Text)
            ),
            "name" -> TemplateSlot(
              ValueExpr(
                Value.Entity(Some("sensor.shared"), Part.Attr("friendly_name"))
              )
            )
          )
        )
      )
    )
  )

  test("a signal-only tick renders nothing, however many viewers") {
    assertCost("state only", List.fill(3)(""), signalDash, 0.0)
  }

  /** The values a node reads are in its key: viewers on one value share its
    * render, and viewers on two each render, replacing each other's entry. A
    * node reading no variable is the 1.0 above.
    */
  test("a node reading a variable: one render a frame per value viewers hold") {
    assertCost(
      "3 viewers on one value",
      List.fill(3)(onWindow("7d")),
      varDash,
      1.0
    ) *>
      assertCost(
        "1+1 on two values",
        List(onWindow("7d"), onWindow("30d")),
        varDash,
        2.0
      )
  }

  test("one entry per node, however many values are posted") {
    val renderer = Renderer.create(varDash)
    val on = (v: String) => ByteValues(Map("window" -> v), None)
    for {
      cache <- RenderCache.create
      _ <- (1 to 50).toList.traverse_(i =>
        IO(cache("c_0", renderer, on(s"v$i"))(s"<b>$i</b>"))
      )
      gens <- cache.size
      again <- IO(cache("c_0", renderer, on("v50"))("<b>never</b>"))
    } yield {
      assertEquals(gens, 1, "50 values, one entry")
      assertEquals(again, "<b>50</b>", "the last value's install stands")
    }
  }

  /** A node keeps one entry, so churn leaves the count where it started; growth
    * would mean retained dead HTML.
    */
  test("entries are bounded, however many frames go by") {
    val renderer = Renderer.create(leafDash)
    val v = (n: Long) => at(n)

    for {
      cache <- RenderCache.create
      _ <- (1L to 50L).toList.traverse_(n =>
        IO(cache("c_0_bar_0", renderer, v(n))(s"<b>$n</b>"))
      )
      afterChurn <- cache.size
    } yield assertEquals(afterChurn, 1, "50 frames, one entry")
  }

  private def at(v: Long): ByteValues = ByteValues(Map("state" -> s"v$v"), None)

  /** Sessions pull on their own fibers, so they do not render from one
    * snapshot. The straggler's install evicts the newer entry: the next session
    * on the newer values renders again, and is never served the straggler's
    * bytes.
    */
  test("a straggler costs a render, never wrong bytes") {
    val runs = new AtomicInteger(0)
    def render(html: String) = { runs.incrementAndGet(); html }

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      newest <- IO(cache(Live, renderer, at(2))(render("<b>v2</b>")))
      late <- IO(cache(Live, renderer, at(1))(render("<b>v1</b>")))
      third <- IO(cache(Live, renderer, at(2))(render("<b>v2 again</b>")))
      gens <- cache.size
    } yield {
      assertEquals(newest, "<b>v2</b>")
      assertEquals(late, "<b>v1</b>", "the straggler gets ITS bytes")
      assertEquals(third, "<b>v2 again</b>", "rendered for its own values")
      assertEquals(runs.get(), 3)
      assertEquals(gens, 1)
    }
  }

  /** A refusal here would freeze a node at its first render. */
  test("new values replace the entry") {
    val runs = new AtomicInteger(0)
    def render(html: String) = { runs.incrementAndGet(); html }

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      _ <- IO(cache(Live, renderer, at(1))(render("<b>v1</b>")))
      _ <- IO(cache(Live, renderer, at(2))(render("<b>v2</b>")))
      hit <- IO(cache(Live, renderer, at(2))(render("<b>never</b>")))
      gens <- cache.size
    } yield {
      assertEquals(hit, "<b>v2</b>")
      assertEquals(runs.get(), 2)
      assertEquals(gens, 1)
    }
  }

  /** By identity: nothing the previous dashboard rendered is worth keeping. */
  test("a renderer swap replaces what a node holds") {
    val before = Renderer.create(leafDash)
    val after = Renderer.create(leafDash)

    for {
      cache <- RenderCache.create
      old <- IO(cache(Live, before, at(1L))("<b>old</b>"))
      first <- cache.size
      fresh <- IO(cache(Live, after, at(1L))("<b>new</b>"))
      afterSwap <- cache.size
    } yield {
      assertEquals(old, "<b>old</b>")
      assertEquals(fresh, "<b>new</b>", "the swap was not served a hit")
      assertEquals(first, 1)
      assertEquals(afterSwap, 1, "replaced, not accumulated")
    }
  }
}
