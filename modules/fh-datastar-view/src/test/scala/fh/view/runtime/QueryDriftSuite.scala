package fh.view.runtime

import fh.view.build.DashboardBuild
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  QueryTemplate,
  Reads,
  Ref,
  Region,
  SlotRead,
  SlotSource,
  Surface,
  Transform
}
import fh.view.query.{QuerySnapshot, Staged}
import fh.view.testkit.HouseFixture
import io.circe.Json
import io.circe.parser.parse

/** The reads a render is handed are decided BEFORE the walk, by code that is
  * not the walk (`Renderer.queriesForPage`, `queriesForSurface`,
  * `readsForPull`). If the two drift, a render misses an answer and raises.
  * This renders each dashboard in every shape a viewer can put it in, against
  * exactly what was decided, so a drift fails here instead.
  */
class QueryDriftSuite extends munit.FunSuite {

  private final case class Case(
      name: String,
      renderer: Renderer,
      dashboard: Dashboard,
      houses: List[Map[String, EntityState]]
  )

  private def built(d: Dashboard): Renderer =
    Renderer.fromValidated(
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
    Case(name, built(d), d, List(base, flipped.toMap))
  }

  /** A distinct sensor in every place a chart can hide, so no read is answered
    * by accident because another position asks the same thing — which is what
    * makes the fixtures above blind to a dropped surface.
    */
  private val shapes: Case = {
    def chart(e: String) = LayoutNode.Component(
      "chart",
      Map(
        "chart" -> SlotSource(
          query = Some(
            QueryTemplate(
              "history",
              Map("entity" -> Ref.Literal(e), "window" -> Ref.Literal("24h"))
            )
          ),
          transform = Transform.Stage.Chart(),
          reads = Reads.OnRender
        )
      )
    )
    def host(id: String) = LayoutNode.Component("host", id = Some(id))
    def col(kids: LayoutNode*) =
      LayoutNode.Component("col", regions = LayoutNode.kids(kids*))
    def baked(into: String, idx: Int, content: LayoutNode, a: Activation) =
      Surface(
        content,
        bakeInto = Some(NodeId.derived(into)),
        bakeAs = Some("panel"),
        bakeIndex = Some(idx),
        activation = a
      )
    val on =
      Predicate.Cmp("state", Op.Eq, Json.fromString("on"), Some("light.a"))
    val plain = LayoutNode.Component(
      "dot",
      Map(
        "entity_id" -> SlotSource(literal = Some("light.a")),
        "state" -> SlotSource()
      )
    )
    val d = Dashboard(
      cards = Map(
        "chart" -> CardDef("<div>{{{chart}}}</div>", slots = List("chart")),
        "dot" -> CardDef("<span>{{state}}</span>", slots = List("state")),
        "col" -> CardDef(
          "<div>{{#children}}{{{html}}}{{/children}}</div>",
          regions = Map("children" -> Region())
        ),
        "host" -> CardDef(
          """<div id="{{hostId}}">{{#panel}}{{{html}}}{{/panel}}</div>""",
          regions = Map("panel" -> Region(Region.Baked))
        )
      ),
      card = col(
        plain,
        chart("sensor.main"),
        host("tabs"),
        host("branch"),
        LayoutNode.SetNode(
          candidates = List("light.a"),
          members = Map(
            "light.a" -> LayoutNode.SetMember(
              List(
                LayoutNode.SetClause(
                  Some(Predicate.Cmp("state", Op.Eq, Json.fromString("on"))),
                  chart("sensor.set")
                )
              )
            )
          )
        )
      ),
      surfaces = Map(
        "t0" -> baked("tabs", 0, chart("sensor.tab0"), Activation.User(true)),
        "t1" -> baked("tabs", 1, chart("sensor.tab1"), Activation.User()),
        "on" -> baked(
          "branch",
          0,
          col(plain, chart("sensor.on"), host("inner")),
          Activation.State(on)
        ),
        "off" -> baked(
          "branch",
          1,
          chart("sensor.off"),
          Activation.State(Predicate.And(Nil))
        ),
        "i0" -> baked(
          Renderer.surfacePrefix("on") + "inner",
          0,
          chart("sensor.inner"),
          Activation.User(true)
        ),
        "pop" -> Surface(chart("sensor.pop"))
      )
    )
    def light(s: String) = Map(
      "light.a" -> EntityState("light.a", s, Map.empty)
    )
    Case("shapes", built(d), d, List(light("on"), light("off")))
  }

  private lazy val cases =
    List(fixture("fixture-features"), fixture("fixture-surfaces"), shapes)

  /** No selection, each member of each tab group, and each popup open. */
  private def uiStates(c: Case): List[Map[String, String]] = {
    val r = c.renderer
    val tabs: List[Map[String, String]] =
      r.surfaces.userBakeOwnerIds.toList.sorted.flatMap(gid =>
        r.surfaces.bakeGroup(gid).indices.map(i => Map(s"$gid" -> s"$i"))
      )
    val popups: List[Map[String, String]] =
      c.dashboard.surfaces.toList.collect {
        case (sid, s) if s.hostId == Dashboard.PopupHostId =>
          Map(s"${Dashboard.PopupHostId}" -> sid)
      }
    Map.empty[String, String] :: tabs ++ popups
  }

  private def answered(reads: List[SlotRead]) =
    QuerySnapshot.of(reads.map(_ -> Staged(1L, "<svg/>")).toMap)

  test("a page render reads only what the page resolved") {
    for (c <- cases; states <- c.houses; ui <- uiStates(c)) {
      val r = c.renderer
      val snapshot = answered(
        r.queriesForPage(r.surfaces.selectedSurfaces(ui), states, Map.empty)
      )
      val _ = r.renderPageInto(
        Sink.buffer(r.pageBytesHint),
        states,
        ui,
        r.surfaces.openPopup(ui),
        snapshot
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
        ui,
        answered(r.queriesForSurface(sid, states, ui, Map.empty))
      )
    }
  }

  test("a pull renders each target and fills each host with what it asked") {
    for (c <- cases) {
      val r = c.renderer
      val painted: List[NodeId] =
        (r.surfaces.stateBakeOwnerIds.toList ++
          c.dashboard.surfaces.keys.toList.flatMap(r.surfaceNodeIds) ++
          c.houses.flatMap(states =>
            r.renderPageInto(
              Sink.buffer(r.pageBytesHint),
              states,
              Map.empty,
              None,
              answered(
                r.queriesForPage(
                  r.surfaces.selectedSurfaces(),
                  states,
                  Map.empty
                )
              )
            ).keys
          )).distinct
      val nodes =
        (painted ++ painted.flatMap(r.ancestry.ancestorsOf)).distinct.sorted
      val hosts = nodes.filter(id =>
        r.surfaces.stateBakeOwnerIds(id) || r.members.setContainer(id).isDefined
      )
      for (states <- c.houses; ui <- uiStates(c)) {
        def asked(targets: List[NodeId], hs: List[NodeId]) =
          answered(r.readsForPull(targets, hs, states, ui, Map.empty))
        nodes.foreach { id =>
          val f = asked(List(id), Nil)
          val _ = (
            r.renderInputs(id, states, f),
            r.renderNodeById(id, states, ui, fragments = f)
          )
        }
        hosts.foreach(h => r.renderHost(h, states, ui, asked(Nil, List(h))))
      }
      // Precise, not everything: a plain node asks nothing, a chart asks.
      val (quiet, loud) = nodes.partition(id =>
        r.readsForPull(List(id), Nil, c.houses.head, Map.empty, Map.empty)
          .isEmpty
      )
      assert(quiet.nonEmpty && loud.nonEmpty, clue = c.name)
    }
  }
}
