package fh.view.runtime

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

/** What N viewers of one dashboard COST, when they are not all looking at the
  * same thing.
  *
  * `SharedPassSuite`'s "rendered once between them" holds the easy half: two
  * viewers with identical state share a render. This holds the half that used
  * to be an open question in the architecture doc — viewers whose SELECTIONS
  * differ hold different [[RenderInputs]] for the same node id, and before the
  * cache bucketed on the selection they evicted each other on every frame.
  *
  * The numbers here are a cost contract in the same sense: they are the FLOOR,
  * and a rise means the sharing has been lost rather than that something got
  * slower.
  */
class RenderCacheContentionSuite extends ServerHarness {

  /** A bake owner and, beside it, the live leaf that actually renders.
    *
    * `c_0` is the tabs host: structure, so it renders nothing per frame.
    * [[Live]] is the leaf in its `bar` region, whose bytes are what a frame
    * moves — and which mention no selection, so every viewer shares them.
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
          """{{#bar}}{{{html}}}{{/bar}}<div id="{{hostId}}" class="tabs">{{{panel}}}</div>""",
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
          )
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
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        ),
        bakeInto = Some("c_0"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

  /** The same node with no bake group: one key for everyone, at any count. */
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

  /** Counts renders PER NODE. A total cannot tell a second viewer's miss from
    * the member render that would happen anyway.
    */
  private class PerNode(dash: Dashboard)
      extends Renderer(dash, Templates.from(dash), Transforms.from(dash)) {
    private val counts =
      new java.util.concurrent.ConcurrentHashMap[NodeId, AtomicInteger]()

    override def renderNodeById(
        id: NodeId,
        states: Map[String, EntityState],
        uiState: Map[String, String],
        form: SlotForm
    ): Option[String] = {
      val _ = counts
        .computeIfAbsent(id, _ => new AtomicInteger(0))
        .incrementAndGet()
      super.renderNodeById(id, states, uiState, form)
    }

    def reset: IO[Unit] = IO(counts.clear())
    def tally: IO[Map[NodeId, Int]] =
      IO(counts.asScala.map((k, v) => k -> v.get()).toMap)
  }

  /** Renders of the contended node per frame, with `queries` viewers connected.
    *
    * The connect renders the opening body for each viewer, so the counter is
    * reset AFTER everyone is on: what is measured is steady-state live pulls.
    */
  private def rendersPerFrame(
      dash: Dashboard,
      queries: List[String],
      frames: Int,
      node: NodeId = "c_0"
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

  /** Cost does not follow SELECTIONS: viewers on different tabs share a render.
    *
    * A bake owner holds its content in regions, which makes it structure —
    * never a patch target, never cached, never rendered per frame. What renders
    * is the LEAF beside it, and a leaf's bytes mention no selection, so every
    * viewer is owed the same bytes whatever tab they are on.
    *
    * The floor is therefore 1.0 at any mix. A 2.0 here would mean the cost had
    * started following the selection again, and a cache keyed on the entity
    * half alone would then be evicting one tab's bytes for the other's on every
    * frame.
    */
  test("cost does not follow selections — one render serves both tabs") {
    assertCost(
      "1+1 on two tabs",
      List("", "?ui.c_0=1"),
      leafDash,
      1.0,
      node = Live
    ) *>
      assertCost(
        "2+2 on two tabs",
        List("", "", "?ui.c_0=1", "?ui.c_0=1"),
        leafDash,
        1.0,
        node = Live
      ) *>
      assertCost(
        "3+3 on two tabs",
        List.fill(3)("") ++ List.fill(3)("?ui.c_0=1"),
        leafDash,
        1.0,
        frames = 5,
        node = Live
      ) *>
      // ...and the owner is not rendered per frame at all, which is why.
      assertCost(
        "the structural owner",
        List("", "?ui.c_0=1"),
        leafDash,
        0.0
      )
  }

  /** The bound: it must not become a leak.
    *
    * A node keeps ONE generation, so churning the entity behind it over many
    * frames leaves the count where it started — entity versions replace in
    * place. If this ever grows with frames, the map is retaining dead HTML
    * forever.
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

  /** The parallelism case: sessions pull on their own fibers and read the store
    * when they get there, so they do not all render from one snapshot.
    *
    * Newest, straggler, newest. The straggler's render is work it needed — it
    * is serving a client that asked at that version — but installing it would
    * evict the generation the third session is about to hit, and cost a render
    * to cache bytes already superseded.
    */
  test("a straggler does not evict the generation that overtook it") {
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      newest <- cache(Live, renderer, at(2))(render("<b>v2</b>"))
      // The laggard is SERVED, and served its own version's bytes...
      late <- cache(Live, renderer, at(1))(render("<b>v1</b>"))
      // ...and the third session finds v2 still there.
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

  /** The other direction still installs: moving FORWARD is what the cache is
    * for, and a refusal there would freeze a node at its first render.
    */
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

  /** Freshness is per ENTITY, so a mixed key is not ordered either way. */
  test("a partly-newer generation is not treated as a straggler") {
    val two =
      (a: Long, b: Long) => RenderInputs(Map("sensor.a" -> a, "sensor.b" -> b))
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(leafDash)
      _ <- cache(Live, renderer, two(2, 1))(render("<b>a2 b1</b>"))
      // Behind on a, ahead on b: neither dominates, so it installs.
      mixed <- cache(Live, renderer, two(1, 2))(render("<b>a1 b2</b>"))
      hit <- cache(Live, renderer, two(1, 2))(render("<b>never</b>"))
    } yield {
      assertEquals(mixed.html, "<b>a1 b2</b>")
      assertEquals(hit.html, "<b>a1 b2</b>", "it took the entry")
      assertEquals(runs.get(), 2)
    }
  }

  /** An entity APPEARING changes what the node reads, not how fresh it is. */
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

  /** A renderer swap invalidates by IDENTITY: nothing the previous dashboard
    * rendered is worth keeping, whatever inputs it was rendered from.
    */
  test("a renderer swap replaces what a node holds") {
    val before = Renderer.create(leafDash)
    val after = Renderer.create(leafDash)
    val at = (v: Long) => RenderInputs(Map("sensor.shared" -> v))

    for {
      cache <- RenderCache.create
      old <- cache(Live, before, at(1L))(IO.pure("<b>old</b>"))
      first <- cache.generations
      // Same node, same inputs, DIFFERENT renderer: a hit would serve the old
      // dashboard's bytes, so it must render again.
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
