package fh.view.runtime

import fh.view.testkit.Slots

import fh.view.testkit.TestCard

import fh.view.history.Windows
import fh.view.build.DashboardBuild
import fh.view.model.{
  SlotType,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  FetchTemplate,
  Choice,
  SlotRead,
  Step,
  Value,
  ValueExpr,
  TemplateSlot,
  Surface
}
import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}
import io.circe.parser.parse

/** The reads a render is handed are decided BEFORE the walk, by code that is
  * not the walk (`Renderer.fetchesForPage`, `fetchesForSurface`,
  * `readsForPull`). If the two drift, a render misses an answer and raises.
  * This renders each dashboard in every shape a viewer can put it in, against
  * exactly what was decided, so a drift fails here instead.
  */
class FetchDriftSuite extends munit.FunSuite {

  private final case class Case(
      name: String,
      renderer: Renderer,
      dashboard: Dashboard,
      houses: List[Shared]
  )

  private def built(d: Dashboard): Renderer =
    Renderer(
      d.validated().fold(e => fail(e.mkString("; ")), identity)
    )

  private def fixture(name: String): Case = {
    val wire = os.read(
      os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" /
        "snapshots" / s"$name.json"
    )
    val d = DashboardBuild
      .hoistInlineSurfaces(parse(wire).fold(e => fail(s"$name: $e"), identity))
      .as[Dashboard]
      .fold(e => fail(s"$name: $e"), identity)
    // The house as fixtured, and with every on/off flipped, so each state
    // group shows each branch and each set admits and drops members.
    val base = HouseFixture.all.map(e => e.entityId -> e.toEntityState).toMap
    val flip = Map("on" -> "off", "off" -> "on")
    val flipped =
      base.view.mapValues(s => s.copy(state = flip.getOrElse(s.state, s.state)))
    Case(
      name,
      built(d),
      d,
      List(Shared.of(base), Shared.of(flipped.toMap))
    )
  }

  /** A distinct sensor in every place a chart can hide, so no read is answered
    * by accident because another position asks the same thing — which is what
    * makes the fixtures above blind to a dropped surface.
    */
  private val shapes: Case = {
    def chart(e: String) = LayoutNode.Component(
      "chart",
      Slots.values(
        Map(
          "chart" -> TemplateSlot(
            ValueExpr(
              Value.Fetch(FetchTemplate(e, Choice.Fixed(Windows.day))),
              List(Step.Chart())
            ),
            `type` = SlotType.TextRaw
          )
        )
      )
    )
    val library = PklFixture.shape(
      """card = (c.column) {
        |  children {
        |    t.dot("light.a")
        |    t.hole("main")
        |    (c.tabs) { id = "tabs"; tabs { ["A"] { t.hole("tab0") } ["B"] { t.hole("tab1") } } }
        |    (c.iff(q.entity(dump.entities.light_a).stateIs("on"))
        |      .then((c.column) {
        |        children {
        |          t.dot("light.a")
        |          t.hole("on")
        |          (c.tabs) { id = "inner"; tabs { ["A"] { t.hole("inner") } } }
        |        }
        |      })
        |      .`else`(t.hole("off"))) { id = "branch" }
        |    q.from(List(dump.entities.light_a))
        |      .where(q.eq(q.stateProp, "on"))
        |      .render((e) -> t.hole("set"))
        |      .build()
        |  }
        |}
        |""".stripMargin,
      FixtureEntity("light.a", "on")
    )
    val d = Dashboard(
      cards = library.cards + ("chart" -> TestCard("<div>{{{chart}}}</div>")),
      card = library.fill(
        "main" -> chart("sensor.main"),
        "tab0" -> chart("sensor.tab0"),
        "tab1" -> chart("sensor.tab1"),
        "on" -> chart("sensor.on"),
        "inner" -> chart("sensor.inner"),
        "off" -> chart("sensor.off"),
        "set" -> chart("sensor.set")
      ),
      surfaces = Map("pop" -> Surface(chart("sensor.pop")))
    )
    def light(s: String) = Map(
      "light.a" -> EntityState("light.a", s, Map.empty)
    )
    Case(
      "shapes",
      built(d),
      d,
      List(Shared.of(light("on")), Shared.of(light("off")))
    )
  }

  private lazy val cases =
    List("cards", "readings", "charts").map(fixture) :+ shapes

  /** No selection, each member of each tab group, and each popup open. */
  private def uiStates(c: Case): List[Selections] = {
    val r = c.renderer
    val tabs: List[Selections] =
      r.surfaces.varPicks.toList.sorted.flatMap(gid =>
        r.surfaces
          .cases(gid)
          .indices
          .map(i => Selections(None, Map(gid -> i)))
      )
    val popups: List[Selections] =
      c.dashboard.surfaces.keys.toList.sorted.map(sid =>
        Selections(Some(sid), Map.empty)
      )
    Selections.none :: tabs ++ popups
  }

  /** At the declared values, as `Server` hands every render its viewer's. */
  private def defaults(r: Renderer): VarEnv = r.vars.env(Map.empty)

  private def answered(r: Renderer, reads: List[SlotRead]) =
    FetchSnapshot.of(reads.map(_ -> Staged(1L, "<svg/>")).toMap, defaults(r))

  test("a page render reads only what the page resolved") {
    for (c <- cases; states <- c.houses; ui <- uiStates(c)) {
      val r = c.renderer
      val snapshot = answered(
        r,
        r.fetchesForPage(r.surfaces.selectedSurfaces(ui), states, defaults(r))
      )
      val _ = r.renderPageInto(
        Sink.buffer(r.pageBytesHint),
        states,
        Viewing(ui, snapshot)
      )
    }
  }

  test("a surface fill reads only what the surface resolved") {
    for (
      c <- cases; states <- c.houses; ui <- uiStates(c);
      sid <- c.dashboard.surfaces.keys.toList.sorted
    ) {
      val r = c.renderer
      val _ = r.renderSurfaceTraced(
        sid,
        states,
        Viewing(
          ui,
          answered(
            r,
            r.fetchesForSurface(
              r.surfaces.popupSpelling(sid).get,
              states,
              ui,
              defaults(r)
            )
          )
        )
      )
    }
  }

  test("a pull renders each target and fills each host with what it asked") {
    for (c <- cases) {
      val r = c.renderer
      val painted: List[NodeId] =
        (r.surfaces.statePicks.toList ++
          c.dashboard.surfaces.keys.toList
            .flatMap(r.surfaces.popupSpelling)
            .flatMap(r.surfaceNodeIds) ++
          c.houses.flatMap(states =>
            r.renderPageInto(
              Sink.buffer(r.pageBytesHint),
              states,
              Viewing(
                Selections.none,
                answered(
                  r,
                  r.fetchesForPage(
                    r.surfaces.selectedSurfaces(),
                    states,
                    defaults(r)
                  )
                )
              )
            ).keys
          )).distinct
      val nodes =
        (painted ++ painted.flatMap(r.ancestry.ancestorsOf)).distinct.sorted
      val hosts = nodes.filter(id =>
        r.surfaces.statePicks(id) || r.members.setContainer(id).isDefined
      )
      for (states <- c.houses; ui <- uiStates(c)) {
        def asked(targets: List[NodeId], hs: List[NodeId]) =
          answered(r, r.readsForPull(targets, hs, states, ui, defaults(r)))
        nodes.foreach { id =>
          val f = asked(List(id), Nil)
          val _ = (
            r.byteSlotValues(id, states, f),
            r.renderNodeById(id, states, Viewing(ui, f))
          )
        }
        hosts.foreach(h =>
          r.renderHost(h, states, Viewing(ui, asked(Nil, List(h))))
        )
      }
      // Precise, not everything: a plain node asks nothing, a chart asks.
      val (quiet, loud) = nodes.partition(id =>
        r.readsForPull(
          List(id),
          Nil,
          c.houses.head,
          Selections.none,
          defaults(r)
        ).isEmpty
      )
      assert(quiet.nonEmpty && loud.nonEmpty, clue = c.name)
    }
  }
}
