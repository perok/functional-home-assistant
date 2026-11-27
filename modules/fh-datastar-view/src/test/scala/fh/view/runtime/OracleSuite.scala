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

  /** A window picked by `a + '_' + b`, `a` opened at `x` though declared `y`:
    * `b` at 1 is taken only over the start, at 3 only over the declaration
    * (ed39c541).
    */
  private val leaning = FixtureEntries.Entry(
    "oracle-leaning",
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/core/simple.pkl" as simpleMod
       |import "@fh-dashboard/providers/history.pkl" as h
       |import "@fh-dashboard/theme.pkl" as th
       |import "@fh-home/dump.pkl" as dump
       |
       |theme = ${PklFixture.dummyTheme}
       |
       |local a = c.variable("a")
       |local b = c.variable("b")
       |local both = (a.value) {
       |  steps { new simpleMod.Cel { src = "value + '_' + b"; inputs = new Mapping { ["b"] = b.value } } }
       |}
       |local window = new h.Chosen {
       |  by = both
       |  cases {
       |    new h.Case { `is` = "y_2"; value = h.presets["1h"] }
       |    new h.Case { `is` = "x_2"; value = h.presets["24h"] }
       |    new h.Case { `is` = "x_1"; value = h.presets["7d"] }
       |    new h.Case { `is` = "y_3"; value = h.presets["30d"] }
       |  }
       |}
       |
       |card = (c.column) {
       |  vars {
       |    a.defaultingTo("y").withInitialTransform(dump.entities.light_kitchen, "value.state == 'on' ? 'x' : 'z'")
       |    b.defaultingTo("2")
       |  }
       |  children {
       |    c.select(a).options(List("x", "y", "z"))
       |    c.select(b).options(List("1", "2", "3"))
       |    c.history.chart(dump.entities.sensor_outside_temp).over(window)
       |    c.entityCard(dump.entities.sensor_outside_temp)
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
      nested,
      leaning
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

  private def build(e: FixtureEntries.Entry): Dashboard =
    PklFixture.buildDashboard(e.slug, e.source, e.dump)

  /** `e`'s card as the first tab of a tab bar, with and without a second tab
    * holding a card: tabs are chosen by index, so installing the second over
    * the first drops the tab a viewer may be on.
    */
  private def variants(e: FixtureEntries.Entry): (Dashboard, Dashboard) = {
    val body = "(?m)^card = ".r.replaceFirstIn(e.source, "local body = ")
    def under(extra: String) =
      PklFixture.buildDashboard(
        e.slug,
        s"""$body
           |card = (c.tabs) {
           |  tabs {
           |    ["main"] { body }
           |$extra
           |  }
           |}
           |""".stripMargin,
        e.dump
      )
    (under("""    ["extra"] { c.title("Extra") }"""), under(""))
  }

  /** For a run that never reloads: `e` as written, whose press indices the
    * run's steps name.
    */
  private def unswapped(e: FixtureEntries.Entry): (Dashboard, Dashboard) = {
    val d = build(e)
    (d, d)
  }

  private enum Step {
    case Change(entity: Int, state: Int)
    case Leave(entity: Int)
    case Press(viewer: Int, pick: Int)
    case Drop(viewer: Int)
    case Connect(viewer: Int)
    case Forget
    // The other variant installed into the slug, as a hot reload does.
    case Reload
    case Back(viewer: Int)
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
      1 -> Gen.constant(Step.Forget),
      1 -> Gen.constant(Step.Reload),
      2 -> genViewer.map(Step.Back(_))
    )
  }

  private val CallPress = """sse/call/[^/]+/([^?/]+)""".r.unanchored

  /** What a viewer may press that the oracle drives: a variable's choice, a
    * popup's open among them.
    */
  private def drivable(b: FakeBrowser, r: Renderer): List[String] =
    b.page.presses.filter {
      case CallPress(id) =>
        r.callOf(CallId.parse(id)).exists(_.decl.isInstanceOf[CallDecl.SetVar])
      case _ => false
    }

  private def run(
      variants: (Dashboard, Dashboard),
      entry: FixtureEntries.Entry,
      steps: List[Step]
  ): Result =
    served(variants._1, entry, 2)
      .use { (ts, viewers) =>
        val byId = entry.entities.map(e => e.entityId -> e).toMap
        val renderer = ts.server
          .liveSlug(ts.slug)
          .flatMap(_.renderer.get)
          .map(_.rendererOf.get)
        for {
          first <- renderer
          // A refresh, the reference, opens a start again, which the live
          // page never follows: an entity only a start reads stays still.
          startOnly = first.vars.initialTransforms.values
            .map(_.entityId)
            .filterNot(first.references)
            .toSet
          ids = byId.keys
            .filter(id => first.watchedEntities(id) && !startOnly(id))
            .toVector
            .sorted
          onFirst <- IO.ref(true)
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
                renderer.flatMap { r =>
                  val options = drivable(viewers(v), r)
                  IO.whenA(options.nonEmpty)(
                    viewers(v).post(options(p % options.size))
                  )
                }
              case Step.Drop(v)    => viewers(v).drop
              case Step.Connect(v) =>
                IO.unlessA(viewers(v).up)(viewers(v).connect)
              case Step.Forget =>
                FakeBrowser.forgetAll(ts, viewers.toList)
              case Step.Reload =>
                onFirst.getAndUpdate(!_).flatMap { was =>
                  val next = if (was) variants._2 else variants._1
                  ts.server
                    .liveSlug(ts.slug)
                    .flatMap(
                      _.install(
                        Server.RendererState.Ready(Renderer.create(next))
                      )
                    )
                }
              case Step.Back(v) => viewers(v).back
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
    * reconnect must keep it, or the dialog stays on screen with the variable
    * empty.
    */
  test(
    "taps: a popup opened while the stream was down survives the reconnect"
  ) {
    val e = FixtureEntries.taps
    run(unswapped(e), e, List(Step.Drop(1), Step.Press(1, 1))) match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** A press on a page a reload swapped, while down, queues the swap's repaint;
    * the porch changing after it reaches the page only through the opening that
    * follows that repaint on the reconnect.
    */
  test(
    "viewer-decisions: a reconnect after a swap sends what changed since"
  ) {
    val e = FixtureEntries.viewerDecisions
    run(
      variants(e),
      e,
      List(Step.Drop(0), Step.Reload, Step.Press(0, 0), Step.Change(4, 1))
    ) match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** Back on a page whose session is forgotten: the browser has moved the URL
    * off the popup, so the page reloads there; refused, it kept the popup, and
    * the reconnect adopted it from the page's committed choices.
    */
  test("taps: Back on a forgotten page reloads at its URL") {
    val e = FixtureEntries.taps
    run(
      unswapped(e),
      e,
      List(Step.Press(0, 1), Step.Forget, Step.Back(0))
    ) match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** A tab pressed while the stream is down commits into a session that is then
    * forgotten, so the reconnect carries the old tab: an ask nothing would ever
    * answer stayed lit over the panel the page showed.
    */
  test("branches: a connect ends an ask its forgotten session never answered") {
    val e = FixtureEntries.branches
    run(
      unswapped(e),
      e,
      List(Step.Drop(1), Step.Press(1, 1), Step.Forget)
    ) match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** A page forgotten while the other viewer is on `b`, where the hall is not
    * shown: its going unavailable is logged for nobody, so the reconnect's
    * sweep is all that can send it. The sweep took only the chosen cases and
    * missed the page's own container, the set's host in it.
    */
  test("viewer-decisions: a reconnect sweeps the page's own container") {
    val e = FixtureEntries.viewerDecisions
    val steps =
      List(Step.Forget, Step.Connect(0), Step.Press(0, 1), Step.Change(2, 2))
    run(unswapped(e), e, steps) match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** The swap a random run reaches rarely: a viewer on the tab the new
    * dashboard lacks, and the edit installed under it.
    */
  test("a reload drops the tab a viewer is on") {
    val e = FixtureEntries.sets
    val (a, b) = variants(e)
    val r = served(a, e, 1)
      .use { (ts, viewers) =>
        val v = viewers(0)
        for {
          live <- ts.server.liveSlug(ts.slug)
          tab <- live.renderer.get.map(
            _.rendererOf.get.vars.defaults.keys.find(_.name == "tab").get
          )
          _ <- v.choose(tab, Some("1")) *> v.deliver
          _ <- live.install(Server.RendererState.Ready(Renderer.create(b)))
          _ <- v.deliver
          r <- v.check
        } yield r
      }
      .timeout(1.minute)
      .unsafeRunSync()
    r match
      case Result.Success => ()
      case failure        => throw AssertionError(failure.toString)
  }

  /** `b` at 1 through each way a choice arrives, `a` opened at `x`: each is
    * checked over the start, so each keeps it. A refresh refuses a choice the
    * press refused too, so the properties above cannot see one refused wrongly.
    */
  private val b = VarKey(NodeId.derived("c"), "b")
  private val popups = VarKey(NodeId.derived("root"), "popups")

  private def keepsB(arrive: (TestServer, FakeBrowser) => IO[Unit]): Unit = {
    val held = served(build(leaning), leaning, 1)
      .use { (ts, viewers) =>
        arrive(ts, viewers(0)) *> ts.sessions
          .get(viewers(0).conn)
          .flatMap(_.traverse(_.state))
          .map(_.flatMap(_.vars.get(b)))
      }
      .timeout(1.minute)
      .unsafeRunSync()
    if (!held.contains("1")) throw AssertionError(s"b held $held, not 1")
  }

  test("leaning: a press taken only over its start is kept") {
    keepsB((_, v) => v.choose(b, Some("1")) *> v.deliver)
  }

  test("leaning: a link taken only over its start is kept") {
    val held = served(build(leaning), leaning, 0)
      .use((ts, _) =>
        ts.viewer("?v.c.b=1").flatMap(v => v.session.state <* v.leave)
      )
      .timeout(1.minute)
      .unsafeRunSync()
      .vars
      .get(b)
    if (!held.contains("1")) throw AssertionError(s"b held $held, not 1")
  }

  test("leaning: a reload keeps a choice taken only over its start") {
    keepsB((ts, v) =>
      v.choose(b, Some("1")) *> v.deliver *>
        ts.server
          .liveSlug(ts.slug)
          .flatMap(
            _.install(
              Server.RendererState.Ready(Renderer.create(build(leaning)))
            )
          ) *> v.deliver
    )
  }

  test("leaning: Back to a choice taken only over its start keeps it") {
    keepsB((_, v) =>
      v.choose(b, Some("1")) *> v.deliver *>
        v.choose(popups, Some("moreinfo_sensor_outside_temp")) *> v.deliver *>
        v.back *> v.deliver
    )
  }

  for (e <- entries) {
    lazy val both = variants(e)
    lazy val watched =
      Renderer
        .create(both._1)
        .watchedEntities
        .intersect(e.entities.map(_.entityId).toSet)
    property(
      s"${e.slug}: what a viewer was sent leaves its page equal to a fresh one",
      _.copy(testLimit = SuccessCount(20))
    ) {
      genStep(watched.size.max(1))
        .list(Range.linear(1, 15))
        .forAll
        .map(run(both, e, _))
    }
  }
}
