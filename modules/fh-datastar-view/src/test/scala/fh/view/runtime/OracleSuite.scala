package fh.view.runtime

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fh.view.history.ChartRenderer
import fh.view.model.{CallDecl, CallId, Dashboard, NodeId}
import fh.view.telemetry.Logging
import fh.view.testkit.{FakePage, FixtureEntity, FixtureEntries, PklFixture}
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
  // viewerDecisions: an If, a set's guard and a set's order each reading a
  // variable and a light's state.
  // taps: a popup, an inline popup and calls beside them.
  // sliderGroups, lightControls: a slider's main controls, a match over its
  // `control` variable, per member in a group.
  // charts: a window chooser's variable picking a fetch.
  private val entries: List[FixtureEntries.Entry] =
    List(
      FixtureEntries.sets,
      FixtureEntries.branches,
      FixtureEntries.viewerDecisions,
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

  private val (js, releaseJs) = FakePage.runtime.allocated.unsafeRunSync()

  override def afterAll(): Unit =
    (releaseCharts *> releaseJs).unsafeRunSync()

  // A chart drawn on the page and its fresh twin read "now" apart; across a
  // history bucket their time axes differ.
  private val stopped = IO.pure(java.time.Instant.now())

  /** A server for `d` and `n` browsers on it, opened in order. */
  private def served(
      d: Dashboard,
      entry: FixtureEntries.Entry,
      n: Int
  ): Resource[IO, (TestServer, Vector[FakeBrowser])] =
    TestServer
      .resource(
        d,
        entry.entities,
        chart = Resource.pure(charts),
        historyClock = stopped
      )
      .flatMap(ts =>
        Vector.fill(n)(FakeBrowser.open(ts, js)).sequence.map(ts -> _)
      )

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

  private val genViewer = Gen.int(Range.constant(0, 1))

  private def genStep(entities: Int): Gen[Step] = {
    val entity = Gen.int(Range.constant(0, entities - 1))
    Gen.frequency1(
      6 -> (for {
        e <- entity
        s <- Gen.int(Range.constant(0, 2))
      } yield Step.Change(e, s)),
      1 -> entity.map(Step.Leave(_)),
      5 -> (for {
        v <- genViewer
        p <- Gen.int(Range.constant(0, 20))
      } yield Step.Press(v, p)),
      2 -> genViewer.map(Step.Drop(_)),
      2 -> genViewer.map(Step.Connect(_)),
      1 -> Gen.constant(Step.Forget)
    )
  }

  private val CallPress = """sse/call/[^/]+/([^?/]+)""".r.unanchored

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
    served(d, entry, 2)
      .use { (ts, viewers) =>
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
          // Deferred: traverse builds every step's IO before the first runs,
          // and a step reads the pages and the `up` flags the ones before it left.
          settled <- steps.traverse { step =>
            def action: IO[Unit] = step match
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
            IO.defer(action) *> viewers.traverse_(_.deliver) *> IO.defer(
              viewers
                .filter(_.up)
                .traverse(v => (v.listsInPage, v.listsAsFresh).mapN(_.and(_)))
            )
          }
          _ <- viewers.traverse_(v => IO.defer(IO.unlessA(v.up)(v.connect)))
          _ <- viewers.traverse_(_.deliver)
          checked <- viewers.traverse(_.check)
        } yield Result.all(settled.flatten ++ checked)
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
    val r = served(build(e), e, 1)
      .use { (ts, viewers) =>
        val a = viewers(0)
        for {
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

  /** The shape a random run reaches rarely: one viewer on `b`, another left on
    * `a`, and the light that only `b`'s case reads moving both ways. A frame
    * the recorder missed for `b` leaves its page on the case it left.
    */
  test("viewer-decisions: a viewer's case follows a light the other ignores") {
    val e = FixtureEntries.viewerDecisions
    val kitchen = e.entities.find(_.entityId == "light.kitchen").get
    val mode = VarKey(NodeId.derived("c_0"), "mode")
    val r = served(build(e), e, 2)
      .use { (ts, viewers) =>
        val (a, b) = (viewers(0), viewers(1))
        for {
          _ <- b.choose(mode, Some("b")) *> b.deliver
          _ <- ts.record(kitchen.copy(state = "off")) *> a.deliver *> b.deliver
          off <- (a.check, b.check).mapN(_.and(_))
          _ <- ts.record(kitchen.copy(state = "on")) *> a.deliver *> b.deliver
          on <- (a.check, b.check).mapN(_.and(_))
        } yield off.and(on)
      }
      .timeout(1.minute)
      .unsafeRunSync()
    r match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** A member only `a`'s values keep: its viewer goes to `b` and back while the
    * light inside it changes, and the page it returns to is the current one.
    */
  test("viewer-decisions: a member one viewer's values drop returns current") {
    val e = FixtureEntries.viewerDecisions
    val hall = e.entities.find(_.entityId == "light.hall").get
    val mode = VarKey(NodeId.derived("c_0"), "mode")
    val r = served(build(e), e, 1)
      .use { (ts, viewers) =>
        val v = viewers(0)
        for {
          _ <- v.choose(mode, Some("b")) *> v.deliver
          away <- v.check
          _ <- ts.record(hall.copy(state = "on")) *> v.deliver
          still <- v.check
          _ <- v.choose(mode, Some("a")) *> v.deliver
          back <- v.check
        } yield away.and(still).and(back)
      }
      .timeout(1.minute)
      .unsafeRunSync()
    r match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** An order key reading `mode` and a light: one change reorders `a`'s list
    * and `b`'s each its own way, and each live page is the fresh one.
    */
  test("viewer-decisions: a light reorders each viewer's list its own way") {
    val e = FixtureEntries.viewerDecisions
    val desk = e.entities.find(_.entityId == "light.desk").get
    val mode = VarKey(NodeId.derived("c_0"), "mode")
    val r = served(build(e), e, 2)
      .use { (ts, viewers) =>
        val (a, b) = (viewers(0), viewers(1))
        for {
          _ <- b.choose(mode, Some("b")) *> b.deliver
          _ <- ts.record(desk.copy(state = "on")) *> a.deliver *> b.deliver
          on <- (a.check, b.check).mapN(_.and(_))
          _ <- ts.record(desk.copy(state = "off")) *> a.deliver *> b.deliver
          off <- (a.check, b.check).mapN(_.and(_))
        } yield on.and(off)
      }
      .timeout(1.minute)
      .unsafeRunSync()
    r match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** A popup opened while the stream is down waits in the backlog; the
    * reconnect's request still says none, and taking it over the backlog left
    * the dialog on screen with `ui_popups` empty.
    */
  test(
    "taps: a popup opened while the stream was down survives the reconnect"
  ) {
    val e = FixtureEntries.taps
    run(build(e), e, List(Step.Drop(1), Step.Press(1, 1))) match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** A tab pressed while the stream is down commits into a session that is then
    * forgotten, so the reconnect carries the old tab: an ask nothing would ever
    * answer stayed lit over the panel the page showed.
    */
  test("branches: a connect ends an ask its forgotten session never answered") {
    val e = FixtureEntries.branches
    run(build(e), e, List(Step.Drop(1), Step.Press(1, 1), Step.Forget)) match
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
      _.copy(testLimit = SuccessCount(20))
    ) {
      genStep(watched.size.max(1))
        .list(Range.linear(1, 15))
        .forAll
        .map(run(d, e, _))
    }
  }
}
