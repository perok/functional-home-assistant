package fh.view.runtime

import fh.view.testkit.Slots

import fh.view.testkit.TestCard

import fh.view.history.Windows
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
  TemplateSlot
}
import fh.view.fetch.{FetchSnapshot, Staged}
import fh.view.testkit.{FixtureEntity, HouseFixture, PklFixture}
import io.circe.parser.parse

/** The reads a render is handed are decided BEFORE the walk, by code that is
  * not the walk (`Renderer.fetchesForPage`, `readsForPull`). If the two drift,
  * a render misses an answer and raises. This renders each dashboard in every
  * shape a viewer can put it in, against exactly what was decided, so a drift
  * fails here instead.
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
    val d = parse(wire)
      .fold(e => fail(s"$name: $e"), identity)
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
    * makes the fixtures above blind to a dropped case.
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
      )
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

  /** What a viewer can have put the dashboard in: its values. */
  private final case class Ui(env: VarEnv)

  /** The declared values, and each case of each pick over a variable, a popup
    * open among them (a value naming each case, and one the otherwise takes
    * where there is one).
    */
  private def uiStates(c: Case): List[Ui] = {
    val r = c.renderer
    val declared = defaults(r)
    val tabs: List[Ui] =
      r.surfaces.varPicks.toList.sorted.flatMap { gid =>
        val name = r.surfaces.varSelecting(gid).getOrElse(fail(s"$gid"))
        val values = r.surfaces.memberValues(gid)
        val picking =
          if (r.surfaces.cases(gid).sizeIs > values.size)
            values :+ "no case names this"
          else values
        picking.map(v =>
          Ui(
            declared.updated(
              gid,
              declared.getOrElse(gid, Map.empty) + (name -> v)
            )
          )
        )
      }
    Ui(declared) :: tabs
  }

  /** At the declared values, as `Server` hands every render its viewer's. */
  private def defaults(r: Renderer): VarEnv = r.vars.env(Map.empty)

  private def answered(env: VarEnv, reads: List[SlotRead]) =
    FetchSnapshot.of(reads.map(_ -> Staged(1L, "<svg/>")).toMap, env)

  test("a page render reads only what the page resolved") {
    for (c <- cases; house <- c.houses; ui <- uiStates(c)) {
      val r = c.renderer
      val states = house.viewing(ui.env)
      val snapshot = answered(
        ui.env,
        r.fetchesForPage(Set.empty, states, ui.env)
      )
      val _ = r.renderPageInto(
        Sink.buffer(r.pageBytesHint),
        states,
        Viewing(snapshot)
      )
    }
  }

  private def parents(r: Renderer, id: NodeId): List[NodeId] =
    r.index
      .ixsOf(id)
      .flatMap(ix =>
        List.unfold(r.index.parent(ix))(
          _.map(p => r.index.id(p) -> r.index.parent(p))
        )
      )

  test("a pull renders each target and fills each host with what it asked") {
    for (c <- cases) {
      val r = c.renderer
      val painted: List[NodeId] =
        (r.surfaces.statePicks.toList ++
          r.surfaces.varPicks.toList
            .flatMap(r.surfaces.cases(_))
            .flatMap(r.surfaceNodeIds) ++
          c.houses.flatMap(house =>
            val states = house.viewing(defaults(r))
            r.renderPageInto(
              Sink.buffer(r.pageBytesHint),
              states,
              Viewing(
                answered(
                  defaults(r),
                  r.fetchesForPage(Set.empty, states, defaults(r))
                )
              )
            ).keys
          )).distinct
      val nodes =
        (painted ++ painted.flatMap(parents(r, _))).distinct.sorted
      val hosts = nodes.filter(id =>
        r.surfaces.statePicks(id) || r.members.setContainer(id).isDefined
      )
      for (house <- c.houses; ui <- uiStates(c)) {
        val states = house.viewing(ui.env)
        def asked(targets: List[NodeId], hs: List[NodeId]) =
          answered(
            ui.env,
            r.readsForPull(targets, hs, states, ui.env)
          )
        nodes.foreach { id =>
          val f = asked(List(id), Nil)
          val _ = (
            r.byteSlotValues(id, states, f),
            r.renderNodeById(id, states, Viewing(f))
          )
        }
        hosts.foreach(h =>
          r.renderHost(
            h,
            states,
            Viewing(asked(Nil, List(h)))
          )
        )
      }
      // Precise, not everything: a plain node asks nothing, a chart asks, in
      // whichever case shows it.
      val (quiet, loud) = nodes.partition(id =>
        uiStates(c).forall(ui =>
          r.readsForPull(
            List(id),
            Nil,
            c.houses.head.viewing(ui.env),
            ui.env
          ).isEmpty
        )
      )
      assert(quiet.nonEmpty && loud.nonEmpty, clue = c.name)
    }
  }
}
