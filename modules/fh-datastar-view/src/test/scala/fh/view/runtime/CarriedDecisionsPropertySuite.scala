package fh.view.runtime

import fh.view.model.Dashboard
import fh.view.testkit.{FixtureEntries, PklFixture}
import hedgehog.*
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite

import java.util.concurrent.atomic.AtomicLong

/** A decision carried from the version before equals one made from scratch:
  * over random runs of changes to a test dashboard's house, the recorder's
  * [[Renderer.advance]] chain, read in part or not at all along the way and
  * with a reader at times ahead of it, renders every body, surface and node
  * patch as a fresh [[Shared]] of the same states does.
  *
  * A failing run names its seed; `HEDGEHOG_SEED=<seed>` replays it exactly.
  */
class CarriedDecisionsPropertySuite extends HedgehogSuite {

  private final case class Fixture(
      r: Renderer,
      d: Dashboard,
      base: Map[String, EntityState]
  ) {
    // Only what the dashboard reads: a change to the rest of the house tests
    // nothing.
    val ids: Vector[String] =
      base.keys.filter(r.watchedEntities).toVector.sorted
    val surfaceIds: List[String] = d.surfaces.keys.toList.sorted
  }

  private def fixture(e: FixtureEntries.Entry): Fixture = {
    val d = PklFixture.buildDashboard(e.slug, e.source, e.dump)
    Fixture(
      Renderer.create(d),
      d,
      e.entities.map(x => x.entityId -> x.toEntityState).toMap
    )
  }

  // Rising across runs too: a run below the two kept versions decides
  // unshared, and its reader ahead would share nothing with the recorder.
  private val clock = new AtomicLong(0L)

  private enum Read { case Nothing, Body, Everything }

  /** `state` None removes the entity. */
  private final case class Step(
      entity: Int,
      state: Option[String],
      ahead: Read,
      after: Read
  )

  private val genRead = Gen.element1(Read.Nothing, Read.Body, Read.Everything)

  private def genStep(entities: Int): Gen[Step] = for {
    i <- Gen.int(Range.linear(0, entities - 1))
    s <- Gen.frequency1(
      9 -> Gen
        .element1("on", "off", "unavailable", "home", "0", "21.5", "")
        .map(Some(_)),
      1 -> Gen.constant(None)
    )
    ahead <- genRead
    after <- genRead
  } yield Step(i, s, ahead, after)

  private def view(f: Fixture, shared: Shared, read: Read): String =
    read match
      case Read.Nothing => ""
      case Read.Body    =>
        f.r
          .renderBodyTraced(
            shared,
            Viewing(
              Selections.none,
              RenderGoldenSuite.answers(f.r, f.d, shared, Nil)
            )
          )
          .html
      case Read.Everything =>
        val body = f.r.renderBodyTraced(
          shared,
          Viewing(
            Selections.none,
            RenderGoldenSuite.answers(f.r, f.d, shared, Nil)
          )
        )
        val nodes = (body.own.keys ++ f.surfaceIds.flatMap(
          f.r.surfaceNodeIds
        )).toList.distinct.sorted
        val answers = RenderGoldenSuite.answers(f.r, f.d, shared, nodes)
        (body.html ::
          f.surfaceIds.flatMap(
            f.r
              .renderSurfaceTraced(_, shared, Viewing(Selections.none, answers))
              .map(_.html)
          ) ++
          nodes.flatMap(
            f.r.renderNodeById(
              _,
              shared,
              Viewing(Selections.none, answers),
              SlotForm.Patch
            )
          )).mkString("\n")

  private def run(f: Fixture, steps: List[Step]): Result = {
    var states = f.base
    var prev = f.r.shared(StoreState(states, clock.incrementAndGet()))
    val checks = List.newBuilder[Result]
    def check(): Unit = {
      val fresh = Shared.of(states, prev.version)
      val _ = checks += view(f, prev, Read.Everything) ==== view(
        f,
        fresh,
        Read.Everything
      )
    }
    steps.foreach { st =>
      val id = f.ids(st.entity)
      val version = clock.incrementAndGet()
      val change = (states.get(id), st.state) match
        case (Some(p), None) => Some(StateChange.Removed(id, p))
        case (p, Some(s)) if !p.exists(_.state == s) =>
          Some(
            StateChange.Moved(
              id,
              p,
              f.base(id).copy(state = s, contentVersion = version)
            )
          )
        case _ => None
      change.foreach { c =>
        states = c match
          case StateChange.Moved(_, _, current) => states.updated(id, current)
          case StateChange.Removed(_, _)        => states - id
        val state = StoreState(states, version)
        if (st.ahead != Read.Nothing) {
          val _ = view(f, f.r.shared(state), st.ahead)
        }
        prev = f.r.advance(prev, Frame(List(c), state))
        if (st.after == Read.Everything) check()
        else { val _ = view(f, prev, st.after) }
      }
    }
    check()
    Result.all(checks.result())
  }

  // At 25, emptying the branch, set or gate marks was caught after 12 to 14.
  for (e <- FixtureEntries.wired) {
    lazy val f = fixture(e)
    property(
      s"${e.slug}: a carried decision renders as one made from scratch",
      _.copy(testLimit = SuccessCount(50))
    ) {
      genStep(f.ids.size)
        .list(Range.linear(1, 12))
        .forAll
        .map(run(f, _))
    }
  }
}
