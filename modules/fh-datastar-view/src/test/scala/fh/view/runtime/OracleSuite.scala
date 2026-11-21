package fh.view.runtime

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fh.view.history.ChartRenderer
import fh.view.model.{CallDecl, CallId, Dashboard}
import fh.view.telemetry.Logging
import fh.view.testkit.{FixtureEntity, FixtureEntries, PklFixture}
import hedgehog.*
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite

import scala.concurrent.duration.*

/** The model-based oracle: whatever the steps, everything a viewer was sent,
  * applied to its page in order, is the page a fresh render gives now. Two
  * viewers share one real server per run; a failing run names its seed, and
  * `HEDGEHOG_SEED=<seed>` replays it.
  */
class OracleSuite extends HedgehogSuite {

  override val munitTimeout: Duration = 5.minutes

  /** A set in a set's member and an If in a member: not in
    * `FixtureEntries.all`, which would need snapshots and goldens for a shape
    * only this suite reads.
    */
  private val nested = FixtureEntries.Entry(
    "oracle-nested",
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-dashboard/theme.pkl" as th
       |import "@fh-home/dump.pkl" as dump
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |card = (c.column) {
       |  children {
       |    q.from(dump.lights.all)
       |      .where(q.eq(q.stateProp, "on"))
       |      .render((l) -> (c.column) {
       |        children {
       |          c.entityCard(l)
       |          c.iff(q.entity(dump.entities.binary_sensor_front_door).stateIs("on"))
       |            .then(c.title("Open"))
       |            .`else`(c.title("Shut"))
       |          q.from(List(dump.entities.light_kitchen, dump.entities.light_living_room))
       |            .where(q.eq(q.stateProp, "on"))
       |            .render((i) -> c.toggle(i))
       |            .build()
       |        }
       |      })
       |      .build()
       |  }
       |}
       |""".stripMargin
  )

  // Each has something a viewer's choice or an entity's change can move:
  // sets: a filtered, ordered and cut candidate set, and its leader as a condition.
  // branches: tabs, a state If, and a branch a node variable picks.
  // taps: a popup, an inline popup and calls beside them.
  // sliderGroups, lightControls: a slider's main controls, a match over its
  // `control` variable, per member in a group.
  // charts: a window chooser's variable picking a fetch.
  private val entries: List[FixtureEntries.Entry] =
    List(
      FixtureEntries.sets,
      FixtureEntries.branches,
      FixtureEntries.taps,
      FixtureEntries.sliderGroups,
      FixtureEntries.lightControls,
      FixtureEntries.charts,
      nested
    )

  // One engine for the suite, loaded by its first chart: per server, ECharts
  // would load in every charts run.
  private val (charts, releaseCharts) =
    ChartRenderer.lazily(Logging.console).allocated.unsafeRunSync()

  override def afterAll(): Unit = releaseCharts.unsafeRunSync()

  private val chrome =
    """<main class="container" id="dashboard">{{{body}}}</main><div id="popups">{{{popups}}}</div>"""

  private def build(e: FixtureEntries.Entry): Dashboard = {
    val d = PklFixture.buildDashboard(e.slug, e.source, e.dump)
    d.copy(theme = d.theme.copy(chrome = chrome))
  }

  private enum Step {
    case Change(entity: Int, state: Int)
    case Leave(entity: Int)
    case Press(viewer: Int, pick: Int)
    case Drop(viewer: Int)
    case Connect(viewer: Int)
    case Forget
  }

  private def statesOf(entityId: String): Vector[String] =
    entityId.takeWhile(_ != '.') match
      case "sensor"       => Vector("0", "12.4", "21.5")
      case "climate"      => Vector("heat", "off", "cool")
      case "media_player" => Vector("paused", "playing", "off")
      case "lock"         => Vector("locked", "unlocked")
      case "light" | "switch" | "binary_sensor" =>
        Vector("on", "off", "unavailable")
      case _ => Vector("on", "off")

  private val genViewer = Gen.int(Range.linear(0, 1))

  private def genStep(entities: Int): Gen[Step] = {
    val entity = Gen.int(Range.linear(0, entities - 1))
    Gen.frequency1(
      6 -> (for {
        e <- entity
        s <- Gen.int(Range.linear(0, 2))
      } yield Step.Change(e, s)),
      1 -> entity.map(Step.Leave(_)),
      5 -> (for {
        v <- genViewer
        p <- Gen.int(Range.linear(0, 20))
      } yield Step.Press(v, p)),
      2 -> genViewer.map(Step.Drop(_)),
      2 -> genViewer.map(Step.Connect(_)),
      1 -> Gen.constant(Step.Forget)
    )
  }

  private val CallPress = """sse/call/[^/]+/([^?/]+)""".r

  /** What a viewer may press that the oracle drives: a variable's choice and
    * the popup routes.
    */
  private def drivable(b: FakeBrowser, r: Renderer): List[String] =
    b.page.presses.filter {
      case CallPress(id) =>
        r.callOf(CallId.parse(id)).exists(_.decl.isInstanceOf[CallDecl.SetVar])
      case p => p.contains("/open/") || p.contains("sse/popup/")
    }

  private def run(
      d: Dashboard,
      entry: FixtureEntries.Entry,
      steps: List[Step]
  ): Result =
    TestServer
      .resource(d, entry.entities, chart = Resource.pure(charts))
      .use { ts =>
        val byId = entry.entities.map(e => e.entityId -> e).toMap
        for {
          renderer <- ts.server
            .liveSlug(ts.slug)
            .flatMap(_.renderer.get)
            .map(_.rendererOf.get)
          ids = byId.keys.filter(renderer.watchedEntities).toVector.sorted
          current <- IO.ref(
            byId.view.mapValues(e => Option(e.state)).toMap
          )
          a <- FakeBrowser.open(ts)
          b <- FakeBrowser.open(ts)
          viewers = Vector(a, b)
          settled <- steps.traverse { step =>
            val action: IO[Unit] = step match
              case Step.Change(i, s) =>
                val id = ids(i % ids.size)
                val options = statesOf(id)
                val state = options(s % options.size)
                current.get.flatMap(m =>
                  IO.whenA(!m(id).contains(state))(
                    ts.record(byId(id).copy(state = state)) *>
                      current.update(_.updated(id, Some(state)))
                  )
                )
              case Step.Leave(i) =>
                val id = ids(i % ids.size)
                current.get.flatMap(m =>
                  IO.whenA(m(id).isDefined)(
                    ts.recordRemoval(id) *> current.update(_.updated(id, None))
                  )
                )
              case Step.Press(v, p) =>
                val options = drivable(viewers(v), renderer)
                IO.whenA(options.nonEmpty)(
                  viewers(v).post(options(p % options.size))
                )
              case Step.Drop(v)    => viewers(v).drop
              case Step.Connect(v) =>
                IO.unlessA(viewers(v).up)(viewers(v).connect)
              case Step.Forget =>
                FakeBrowser.forgetAll(ts, viewers.toList)
            action *> viewers.traverse_(_.deliver) *>
              viewers.filter(_.up).traverse(_.listsInPage)
          }
          _ <- viewers.traverse_(v => IO.unlessA(v.up)(v.connect))
          _ <- viewers.traverse_(_.deliver)
          ra <- a.check
          rb <- b.check
        } yield Result.all(settled.flatten ++ List(ra, rb))
      }
      .timeout(2.minutes)
      .unsafeRunSync()

  /** The run that found it, kept because the properties reach it only in some
    * seeds: a repaint's seed whose text the element already carries is not
    * applied again (the bundle's morph sets only an attribute that differs), so
    * a page that missed the frames kept the value they left.
    */
  test("a forgotten page gets back a value that returned to its seed") {
    val e = FixtureEntries.taps
    val kitchen = e.entities.find(_.entityId == "light.kitchen").get
    val r = TestServer
      .resource(build(e), e.entities, chart = Resource.pure(charts))
      .use { ts =>
        for {
          a <- FakeBrowser.open(ts)
          _ <- ts.record(kitchen.copy(state = "off")) *> a.deliver
          _ <- FakeBrowser.forgetAll(ts, List(a))
          _ <- ts.record(kitchen.copy(state = "on"))
          _ <- a.connect *> a.deliver
          r <- a.check
        } yield r
      }
      .timeout(1.minute)
      .unsafeRunSync()
    // Thrown: this suite's `assert` is hedgehog's, which returns a Result.
    r match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  for (e <- entries) {
    lazy val d = build(e)
    lazy val watched =
      Renderer
        .create(d)
        .watchedEntities
        .intersect(e.entities.map(_.entityId).toSet)
    property(
      s"${e.slug}: what a viewer was sent leaves its page equal to a fresh one",
      _.copy(testLimit = SuccessCount(50))
    ) {
      genStep(watched.size.max(1))
        .list(Range.linear(1, 15))
        .forAll
        .map(run(d, e, _))
    }
  }
}
