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

  /** A bake owner that ALSO binds an entity — the only shape where two viewers
    * hold different inputs for one node. `c_0` is the tabs host: its key
    * carries `sensor.shared`'s version AND the `bakeIndex` its viewer selected.
    *
    * It is a `self` host deliberately, and that is the shape the authoring
    * layer blesses: `lib/components.pkl` makes a LIVE slot on a card that
    * mounts children with no `self` a build error, because such a card's patch
    * would carry everything its mount holds — "declaring a `self` is the fix,
    * and it lifts the ban". So a live tab host looks exactly like this, and a
    * fixture without the `self` would be measuring a dashboard nobody can
    * author.
    */
  private def contendedDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """{{{self}}}<div id="{{hostId}}" class="tabs">{{{panel}}}</div>""",
        self = Some("""<span id="{{selfId}}">{{state}}</span>"""),
        regions = Map("panel" -> Region(Region.Baked)),
        slots = List("state")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = LayoutNode.kids(
        LayoutNode.Component(
          "tabs",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
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

  /** The shape after `self` goes: the live part is a LEAF sibling of the baked
    * region, not the bake owner's own presentation.
    *
    * Same dashboard, same two viewers on two tabs — the only change is WHERE
    * the live value lives. `c_0` is structure and renders nothing per frame;
    * `c_0_bar_0` is the leaf that does.
    */
  private def leafDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """{{#bar}}{{{html}}}{{/bar}}<div id="{{hostId}}" class="tabs">{{{panel}}}</div>""",
        regions = Map("bar" -> Region(), "panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = LayoutNode.kids(
        LayoutNode.Component(
          "tabs",
          children = Map(
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
    surfaces = contendedDash.surfaces
  )

  /** The same node with no bake group: one key for everyone, at any count. */
  private def plainDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      children = LayoutNode.kids(
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
        "bake owner, 4 viewers on one tab",
        List.fill(4)(""),
        contendedDash,
        1.0,
        frames = 5
      )
  }

  /** The contract that was the open question.
    *
    * Two selections need two DIFFERENT renders a frame — the viewers are owed
    * different bytes — so 2.0 is the floor and not waste. What is NOT
    * inevitable is the third and fourth viewer: before the cache bucketed on
    * the selection, a pull for tab 1 evicted tab 0's entry, so the next tab-0
    * viewer re-rendered what its neighbour had just filled. That measured
    * 2.13–3.80 renders a frame at 3+3, drifting toward one render per VIEWER.
    *
    * So the assertion is not "2.0 is fast" — it is that the cost is the number
    * of distinct SELECTIONS in flight, and does not move when viewers pile up
    * behind each one.
    */
  test("cost follows distinct selections, not viewers") {
    assertCost(
      "1+1 on two tabs",
      List("", "?ui.c_0=1"),
      contendedDash,
      2.0
    ) *>
      assertCost(
        "2+2 on two tabs",
        List("", "", "?ui.c_0=1", "?ui.c_0=1"),
        contendedDash,
        2.0
      ) *>
      assertCost(
        "3+3 on two tabs",
        List.fill(3)("") ++ List.fill(3)("?ui.c_0=1"),
        contendedDash,
        2.0,
        frames = 5
      )
  }

  /** THE question the `self` deletion turns on, measured rather than argued.
    *
    * The old shape needs two renders a frame for two tabs and that is a FLOOR:
    * the bake owner's own bytes carry the viewer's selection, so the two
    * viewers are owed different bytes. The new shape needs ONE — the live part
    * is a leaf whose bytes mention no selection, so every viewer is owed the
    * same bytes and one render serves them all.
    *
    * If this measured 2.0 the contention would merely have moved and the
    * per-selection bucketing would still be earning its keep.
    */
  test("as a leaf, two tabs cost ONE render — the contention is gone") {
    assertCost(
      "old shape, 1+1 on two tabs",
      List("", "?ui.c_0=1"),
      contendedDash,
      2.0
    ) *>
      assertCost(
        "new shape, 1+1 on two tabs",
        List("", "?ui.c_0=1"),
        leafDash,
        1.0,
        node = "c_0_bar_0"
      ) *>
      // ...and the owner itself is not rendered per frame at all.
      assertCost(
        "new shape, the structural owner",
        List("", "?ui.c_0=1"),
        leafDash,
        0.0
      )
  }

  /** The other half of bucketing: it must not become a leak.
    *
    * A node keeps ONE generation per selection, so churning the entity behind a
    * node over many frames leaves the count where it started — it is the entity
    * VERSIONS that replace in place. If this ever grows with frames, the split
    * has been keyed on the whole [[RenderInputs]] again and the map retains
    * dead HTML forever.
    */
  test("generations are bounded by selections, not by frames") {
    val renderer = Renderer.create(contendedDash)
    val v = (n: Long) =>
      RenderInputs(Map("sensor.shared" -> n), Map("bakeIndex" -> "0"))

    for {
      cache <- RenderCache.create
      _ <- (1L to 50L).toList.traverse_(n =>
        cache("c_0", renderer, v(n))(IO.pure(s"<b>$n</b>"))
      )
      afterChurn <- cache.generations
      // ...and a SECOND selection is a second generation, not a replacement.
      _ <- cache(
        "c_0",
        renderer,
        RenderInputs(Map("sensor.shared" -> 50L), Map("bakeIndex" -> "1"))
      )(IO.pure("<b>other tab</b>"))
      afterSecondTab <- cache.generations
      nodes <- cache.size
    } yield {
      assertEquals(afterChurn, 1, "50 frames of one selection")
      assertEquals(afterSecondTab, 2, "one generation per selection")
      assertEquals(nodes, 1)
    }
  }

  private val tab0 = Map("bakeIndex" -> "0")
  private def at(v: Long) = RenderInputs(Map("sensor.shared" -> v), tab0)

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
      renderer = Renderer.create(contendedDash)
      newest <- cache("c_0", renderer, at(2))(render("<b>v2</b>"))
      // The laggard is SERVED, and served its own version's bytes...
      late <- cache("c_0", renderer, at(1))(render("<b>v1</b>"))
      // ...and the third session finds v2 still there.
      third <- cache("c_0", renderer, at(2))(render("<b>v2 again</b>"))
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
      renderer = Renderer.create(contendedDash)
      _ <- cache("c_0", renderer, at(1))(render("<b>v1</b>"))
      _ <- cache("c_0", renderer, at(2))(render("<b>v2</b>"))
      hit <- cache("c_0", renderer, at(2))(render("<b>never</b>"))
      gens <- cache.generations
    } yield {
      assertEquals(hit.html, "<b>v2</b>")
      assertEquals(runs.get(), 2)
      assertEquals(gens, 1)
    }
  }

  /** Freshness is per ENTITY, so a mixed key is not ordered either way. */
  test("a partly-newer generation is not treated as a straggler") {
    val two = (a: Long, b: Long) =>
      RenderInputs(Map("sensor.a" -> a, "sensor.b" -> b), tab0)
    val runs = new AtomicInteger(0)
    def render(html: String) = IO(runs.incrementAndGet()).as(html)

    for {
      cache <- RenderCache.create
      renderer = Renderer.create(contendedDash)
      _ <- cache("c_0", renderer, two(2, 1))(render("<b>a2 b1</b>"))
      // Behind on a, ahead on b: neither dominates, so it installs.
      mixed <- cache("c_0", renderer, two(1, 2))(render("<b>a1 b2</b>"))
      hit <- cache("c_0", renderer, two(1, 2))(render("<b>never</b>"))
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
      renderer = Renderer.create(contendedDash)
      _ <- cache("c_0", renderer, at(5))(render("<b>one</b>"))
      grew <- cache(
        "c_0",
        renderer,
        RenderInputs(Map("sensor.shared" -> 4L, "sensor.new" -> 1L), tab0)
      )(render("<b>two</b>"))
      gens <- cache.generations
    } yield {
      assertEquals(grew.html, "<b>two</b>")
      assertEquals(runs.get(), 2)
      assertEquals(gens, 1, "it installed rather than being refused")
    }
  }

  /** A renderer swap drops EVERY selection, not just the one asked for. */
  test("a renderer swap clears a node's other selections too") {
    val before = Renderer.create(contendedDash)
    val after = Renderer.create(contendedDash)
    val at = (i: String) =>
      RenderInputs(Map("sensor.shared" -> 1L), Map("bakeIndex" -> i))

    for {
      cache <- RenderCache.create
      _ <- cache("c_0", before, at("0"))(IO.pure("<b>old tab0</b>"))
      _ <- cache("c_0", before, at("1"))(IO.pure("<b>old tab1</b>"))
      two <- cache.generations
      _ <- cache("c_0", after, at("0"))(IO.pure("<b>new tab0</b>"))
      one <- cache.generations
      // The stale tab-1 generation is gone rather than served.
      tab1 <- cache("c_0", after, at("1"))(IO.pure("<b>new tab1</b>"))
    } yield {
      assertEquals(two, 2)
      assertEquals(one, 1, "the swap took the other selection with it")
      assertEquals(tab1.html, "<b>new tab1</b>")
    }
  }
}
