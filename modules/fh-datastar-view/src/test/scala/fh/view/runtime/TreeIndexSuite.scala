package fh.view.runtime

import fh.view.model.{Dashboard, Ix, NodeId}
import fh.view.testkit.{FixtureEntries, PklFixture}

/** The index against what the runtime does with the same dashboard: every id a
  * render writes is the index's, and every container `SurfaceGraph` answers is
  * the one the index records.
  */
class TreeIndexSuite extends munit.FunSuite {

  private val starter = FixtureEntries.Entry(
    "starter",
    fh.view.build.AddonBootstrap.starterSite
  )

  // A set in a member, and an If in a member.
  private val nested = PklFixture.snippet(
    """card = (c.column) { children {
      |  q.from(List(dump.entities.light_kitchen, dump.entities.light_living_room))
      |    .render((e) -> (c.column) { children {
      |      q.from(List(dump.entities.light_kitchen, dump.entities.light_living_room))
      |        .where(q.eq(q.stateProp, "on")).render((f) -> t.dot(f.entity_id)).build()
      |      c.iff(q.entity(dump.entities.light_kitchen).stateIs("on")).then(t.label("on"))
      |    } })
      |    .build()
      |} }
      |""".stripMargin
  )

  private val dashboards
      : List[(String, () => Dashboard, Map[String, EntityState])] =
    ("nested", () => nested, Map.empty[String, EntityState]) ::
      (
        "starter",
        () => PklFixture.buildSiteDashboard("home", starter.source),
        Map.empty
      ) ::
      FixtureEntries.all.map(e =>
        (
          e.slug,
          () => PklFixture.buildDashboard(e.slug, e.source, e.dump),
          e.entities.map(x => x.entityId -> x.toEntityState).toMap
        )
      )

  /** A container as the runtime spells it: `""`, a popup's id, a case's id. */
  private def spelled(d: Dashboard, c: Ix): String =
    if (d.index.parent(c).isEmpty) d.index.tree(c) else d.index.id(c)

  dashboards.foreach { (slug, build, states) =>
    test(s"the index has every id a render writes, in its container: $slug") {
      val d = build()
      val r =
        Renderer(d.validated().fold(errs => fail(errs.mkString), identity))
      val shared = Shared.of(states)
      val v = Viewing(
        RenderGoldenSuite.fresh(r),
        RenderGoldenSuite.answers(r, d, shared, Nil)
      )
      val rendered: Set[NodeId] =
        r.renderBodyTraced(shared, v).own.keySet ++
          d.surfaces.keys
            .flatMap(r.renderSurfaceTraced(_, shared, v))
            .flatMap(_.own.keySet) ++
          d.picks.values
            .flatMap(_.cases)
            .flatMap(r.renderShownTraced(_, shared, v))
            .flatMap(_.own.keySet)
      val byId: Map[NodeId, List[Ix]] = d.index.all.toList.groupBy(d.index.id)
      assertEquals(rendered -- byId.keySet, Set.empty[NodeId])
      val wrong = byId.toList.flatMap { (id, ixs) =>
        r.surfaces
          .rootOf(id)
          .filterNot(c =>
            ixs.exists(ix => spelled(d, d.index.container(ix)) == c)
          )
          .map(id -> _)
      }
      assertEquals(wrong, Nil)
    }
  }

  test("a node's subtree is the range its children recurse to") {
    val i = nested.index
    def below(ix: Ix): Set[Ix] = i.children(ix).toSet.flatMap(c => below(c) + c)
    i.all.foreach { ix =>
      assertEquals(
        i.all.filter(o => o != ix && i.contains(ix, o)).toSet,
        below(ix)
      )
    }
  }
}
