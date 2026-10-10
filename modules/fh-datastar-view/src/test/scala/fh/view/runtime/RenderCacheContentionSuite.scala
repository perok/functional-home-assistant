package fh.view.runtime

import fh.view.query.QuerySnapshot
import cats.effect.IO
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Region,
  SlotSource,
  Surface
}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given

import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** What N viewers cost when their selections differ, so they hold different
  * [[RenderInputs]] for one node id. Before the cache bucketed on selection
  * they evicted each other every frame. The numbers are floors: a rise means
  * the sharing was lost.
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
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """{{#bar}}{{{html}}}{{/bar}}<div id="{{hostId}}" class="tabs">{{#panel}}{{{html}}}{{/panel}}</div>""",
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
                slots = Map("state" -> SlotSource(Some("sensor.shared")))
              )
            )
          ),
          vars = Map("tab" -> "0")
        )
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        bakeInto = Some("c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.Var("tab")
      ),
      "t1" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
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
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        )
      )
    )
  )

  private val initial = Map(
    "sensor.shared" -> st("sensor.shared", "0"),
    "sensor.a" -> st("sensor.a", "a"),
    "sensor.b" -> st("sensor.b", "b")
  )

  /** Per node: a total cannot tell a second viewer's miss from a member render
    * that happens anyway.
    */
  private class PerNode(dash: Dashboard)
      extends Renderer(dash, Templates.from(dash), Transforms.from(dash)) {
    private val counts =
      new java.util.concurrent.ConcurrentHashMap[NodeId, AtomicInteger]()

    override def renderNodeById(
        id: NodeId,
        states: Map[String, EntityState],
        selections: Selections,
        form: SlotForm,
        fragments: QuerySnapshot
    ): Option[String] = {
      val _ = counts
        .computeIfAbsent(id, _ => new AtomicInteger(0))
        .incrementAndGet()
      super.renderNodeById(id, states, selections, form, fragments)
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
    val renderer = new PerNode(dash)
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

  /** A node keeps one generation, so churn leaves the count where it started;
    * growth would mean retained dead HTML.
    */
  test("generations are bounded, however many frames go by") {
    val renderer = Renderer.create(leafDash)
    val v = (n: Long) => RenderInputs(Map("sensor.shared" -> n))

    for {
      cache <- RenderCache.create
      _ <- (1L to 50L).toList.traverse_(n =>
        cache("c_0_bar_0", renderer, v(n))(IO.pure(s"<b>$n</b>"))
      )
      afterChurn <- cache.generations
      nodes <- cache.size
    } yield {
      assertEquals(afterChurn, 1, "50 frames, one generation")
      assertEquals(nodes, 1)
    }
  }

  private def at(v: Long) = RenderInputs(Map("sensor.shared" -> v))

  /** Sessions pull on their own fibers, so they do not render from one
    * snapshot. The straggler's render serves its client, but installing it
    * would evict the generation the third session is about to hit.
    */
  test("a straggler does not evict the generation that overtook it") {
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      newest <- cache(Live, renderer, at(2))(render("<b>v2</b>"))
      late <- cache(Live, renderer, at(1))(render("<b>v1</b>"))
      third <- cache(Live, renderer, at(2))(render("<b>v2 again</b>"))
      gens <- cache.generations
    } yield {
      assertEquals(newest.html, "<b>v2</b>")
      assertEquals(late.html, "<b>v1</b>", "the straggler gets ITS bytes")
      assertEquals(third.html, "<b>v2</b>", "served from the surviving entry")
      assertEquals(runs.get(), 2, "the third session did not re-render")
      assertEquals(gens, 1, "and the straggler cached nothing")
    }
  }

  /** A refusal here would freeze a node at its first render. */
  test("a newer generation does replace an older one") {
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      _ <- cache(Live, renderer, at(1))(render("<b>v1</b>"))
      _ <- cache(Live, renderer, at(2))(render("<b>v2</b>"))
      hit <- cache(Live, renderer, at(2))(render("<b>never</b>"))
      gens <- cache.generations
    } yield {
      assertEquals(hit.html, "<b>v2</b>")
      assertEquals(runs.get(), 2)
      assertEquals(gens, 1)
    }
  }

  test("a partly-newer generation is not treated as a straggler") {
    val two =
      (a: Long, b: Long) => RenderInputs(Map("sensor.a" -> a, "sensor.b" -> b))
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      _ <- cache(Live, renderer, two(2, 1))(render("<b>a2 b1</b>"))
      mixed <- cache(Live, renderer, two(1, 2))(render("<b>a1 b2</b>"))
      hit <- cache(Live, renderer, two(1, 2))(render("<b>never</b>"))
    } yield {
      assertEquals(mixed.html, "<b>a1 b2</b>")
      assertEquals(hit.html, "<b>a1 b2</b>", "it took the entry")
      assertEquals(runs.get(), 2)
    }
  }

  test("a different entity set is not ordered against the entry") {
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      _ <- cache(Live, renderer, at(5))(render("<b>one</b>"))
      grew <- cache(
        Live,
        renderer,
        RenderInputs(Map("sensor.shared" -> 4L, "sensor.new" -> 1L))
      )(render("<b>two</b>"))
      gens <- cache.generations
    } yield {
      assertEquals(grew.html, "<b>two</b>")
      assertEquals(runs.get(), 2)
      assertEquals(gens, 1, "it installed rather than being refused")
    }
  }

  /** By identity: nothing the previous dashboard rendered is worth keeping. */
  test("a renderer swap replaces what a node holds") {
    val before = Renderer.create(leafDash)
    val after = Renderer.create(leafDash)
    val at = (v: Long) => RenderInputs(Map("sensor.shared" -> v))

    for {
      cache <- RenderCache.create
      old <- cache(Live, before, at(1L))(IO.pure("<b>old</b>"))
      first <- cache.generations
      fresh <- cache(Live, after, at(1L))(IO.pure("<b>new</b>"))
      afterSwap <- cache.generations
    } yield {
      assertEquals(old.html, "<b>old</b>")
      assertEquals(fresh.html, "<b>new</b>", "the swap was not served a hit")
      assertEquals(first, 1)
      assertEquals(afterSwap, 1, "replaced, not accumulated")
    }
  }
}
