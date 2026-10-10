package fh.view.runtime

import fh.view.query.QuerySnapshot
import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Region,
  SlotSource,
  Surface
}
import fh.view.testkit.{FakeHomeAssistant, FixtureEntity}
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fh.view.build.SystemPkl
import fh.view.telemetry.{Logging, Meters}
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.Json
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.otel4s.trace.Tracer

import scala.annotation.targetName
import scala.concurrent.duration.*

/** What the `Server` suites share. A trait so a harness can call `assertEquals`
  * and return `IO`.
  */
trait ServerHarness extends munit.CatsEffectSuite {

  /** Real time unless a suite about concurrent interleavings opts in: there a
    * seeded TestControl is what makes the interleaving reproducible, and the
    * harness's IO.sleep poll loops resolve the moment every fiber is blocked.
    *
    * Under it, every background fiber a test starts must be supervised: a bare
    * `.start` outlives the test, and the driver never reaches quiescence, which
    * OOMs rather than hangs. Nor can it fetch a document or boot [[live]]: the
    * page route streams through `fs2.io.readOutputStream`, whose writer and
    * reader block each other, and TestControl runs `IO.blocking` inline on its
    * one thread, so the test hangs in `PipedStreamBuffer.read`. Server
    * topology, several live fibers coordinating through refs and streams, is
    * out of its scope anyway (typelevel/cats-effect#4104).
    */
  protected def simulateTime: Boolean = false

  @targetName("testIO")
  protected def test(
      name: String
  )(body: => IO[Unit])(using loc: munit.Location): Unit = if (!simulateTime)
    super.test(name)(body)
  else {
    import cats.effect.kernel.Outcome

    super.test(name)(
      TestControl
        .execute(body, seed = sys.env.get("FH_TEST_SEED"))
        .flatMap { c =>
          def embed: IO[Unit] = c.results.flatMap {
            case Some(Outcome.Succeeded(())) => IO.unit
            case Some(Outcome.Errored(e))    => IO.raiseError(e)
            case Some(Outcome.Canceled())    =>
              IO.raiseError(new java.util.concurrent.CancellationException())
            case None =>
              IO.raiseError(new TestControl.NonTerminationException())
          }
          // Drive to the program's completion, not to timer exhaustion:
          // `tickAll` returns only once no timer is armed, so a recurring one
          // (a stream keepalive) spins it forever after the outcome is decided.
          // The cap turns a livelock into a fast failure, and a failed run
          // prints its seed for FH_TEST_SEED.
          def drive(iterations: Int): IO[Unit] =
            if iterations > 100000 then
              IO.raiseError(
                new RuntimeException(
                  s"simulated runtime did not settle (iterations=$iterations, seed=${c.seed})"
                )
              )
            else
              c.results.flatMap {
                case Some(_) => IO.unit
                case None    =>
                  c.tickOne.flatMap { more =>
                    if more then drive(iterations + 1)
                    else
                      c.nextInterval.flatMap { n =>
                        // Deadlocked; [[embed]] raises NonTermination for it.
                        if n == Duration.Zero then IO.unit
                        else c.advanceAndTick(n) *> drive(iterations + 1)
                      }
                  }
              }
          drive(0)
            .flatMap(_ => embed)
            .onError(_ =>
              IO.println(
                s"[fh-test] '$name' failed; replay with FH_TEST_SEED=${c.seed}"
              )
            )
        }
    )
  }

  @targetName("testSync")
  protected def test(
      name: String
  )(body: => Unit)(using loc: munit.Location): Unit =
    super.test(name)(body)

  def resumeNow(
      renderer: Renderer,
      log: FragmentLog,
      holds: Map[NodeId, Held],
      states: Map[String, EntityState],
      v: Long,
      open: Set[String],
      uiState: Map[String, String]
  ): List[Addressed] =
    RenderCache.create
      .flatMap(
        Patches.resume(
          renderer,
          _,
          log,
          holds,
          states,
          _ => IO.pure(QuerySnapshot.empty),
          Map.empty,
          v,
          open,
          uiState
        )
      )
      .unsafeRunSync()

  def tabsRenderer: Renderer = Renderer.create(tabsDash)

  def tabsDash: Dashboard = {
    val cards = Map(
      "btn" ->
        CardDef("<button>{{label}}</button>", slots = List("label")),
      "card" ->
        CardDef("<span>{{state}}</span>", slots = List("state")),
      // Shaped like the shipped `Tabs`: the bar is structure, never a patch
      // target.
      "tabs" -> CardDef(
        template = """<div class="tabs">""" +
          """{{#children}}{{{html}}}{{/children}}</div>""" +
          """<div id="{{hostId}}" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("children" -> Region(), "panel" -> Region(Region.Baked))
      )
    )
    def panel(name: String): LayoutNode.Component =
      LayoutNode.Component(
        "card",
        slots = Map("state" -> SlotSource(Some(s"sensor.$name")))
      )
    Dashboard(
      cards,
      LayoutNode.Component(
        "tabs",
        regions = LayoutNode.kids(
          LayoutNode.Component(
            "btn",
            Map("label" -> SlotSource(literal = Some("A")))
          ),
          LayoutNode
            .Component("btn", Map("label" -> SlotSource(literal = Some("B"))))
        ),
        vars = TabDeclared
      ),
      surfaces = Map(
        "c_t0" -> tabMember(panel("a"), "c", 0),
        "c_t1" -> tabMember(panel("b"), "c", 1)
      )
    )
  }

  /** What a tab bar declares: the open member's index (ADR 0033). */
  val TabDeclared: Map[String, String] = Map("tab" -> "0")

  /** A member of a tab bar's panel, selected by the bar's `tab`. */
  def tabMember(
      content: LayoutNode,
      host: String,
      index: Int,
      bakeAs: String = "panel"
  ): Surface =
    Surface(
      content,
      bakeInto = Some(host),
      bakeAs = Some(bakeAs),
      bakeIndex = Some(index),
      activation = Activation.Var("tab")
    )

  /** A stream carrying this viewer's committed tabs, as a reconnect does
    * (`Server.SseInclude`): with no document, nothing else names them.
    */
  def onTabs(tabs: (String, Int)*): String =
    "?datastar=" + java.net.URLEncoder.encode(
      tabs
        .map((host, i) => s""""_var_${host}__tab":"$i"""")
        .mkString("{", ",", "}"),
      "UTF-8"
    )

  /** A page GET carrying ui state in the URL, as a refresh does. */
  def get(params: (String, String)*): Request[IO] =
    Request[IO](Method.GET, uri"/".withQueryParams(params.toMap))

  // No bake groups, so its live patches belong entirely to the shared pass.
  def liveLeafDash = Dashboard(
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
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        )
      )
    )
  )

  def on(id: String): EntityState = st(id, "on")

  def off(id: String): EntityState = st(id, "off")

  /** Each clause is `(extra guard, card, slots)`, additionally guarded on
    * `state == on`, so a candidate is present exactly while it is on.
    */
  def onSet(
      candidates: List[String],
      clauses: List[(Option[Predicate], String, Map[String, SlotSource])]
  ): LayoutNode.SetNode =
    LayoutNode.SetNode(
      candidates = candidates,
      members = candidates.map { id =>
        id -> LayoutNode.SetMember(clauses.map { case (extra, card, slots) =>
          LayoutNode.SetClause(
            when = Some(
              extra.fold[Predicate](isOn)(e => Predicate.And(List(isOn, e)))
            ),
            node = LayoutNode.Component(
              card,
              slots.updated("entity_id", SlotSource(literal = Some(id)))
            )
          )
        })
      }.toMap
    )

  val isOn: Predicate = Predicate.Cmp("state", Op.Eq, Json.fromString("on"))

  def dynDash = Dashboard(
    cards =
      Map("dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))),
    // Sorted, which the placement assertions were written against; a set places
    // by authored order.
    card = onSet(
      List("light.a", "light.b", "light.c", "light.d", "light.z"),
      List((None, "dot", Map("state" -> SlotSource())))
    )
  )

  def logged(log: FragmentLog): Map[NodeId, Long] = log.fragments

  def elementPatches(batch: List[SseFrame]): List[String] =
    batch.map(_.render).filterNot(_.contains("datastar-patch-signals"))

  // An empty conjunction is vacuously true and reads no entity, so an `else`
  // needs no subject.
  val always: Predicate = Predicate.And(Nil)

  def entityIs(id: String, state: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  val armedCond = entityIs("alarm.h", "armed")

  val ifCards = Map(
    "col" -> CardDef(
      "<div>{{#children}}{{{html}}}{{/children}}</div>",
      regions = Map("children" -> Region())
    ),
    // Pure structure, like `lib/components/base/surface.pkl`'s `If`.
    "ifhost" -> CardDef(
      template =
        """<div id="{{hostId}}">{{#branch}}{{{html}}}{{/branch}}</div>""",
      regions = Map("branch" -> Region(Region.Baked))
    ),
    "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
    "dot" -> CardDef("<b>{{state}}</b>", slots = List("state"))
  )

  def branchCard(entity: String): LayoutNode.Component =
    LayoutNode.Component(
      "card",
      slots = Map("state" -> SlotSource(Some(entity)))
    )

  def stateMember(
      content: LayoutNode,
      host: String,
      index: Int,
      condition: Predicate
  ): Surface =
    Surface(
      content,
      bakeInto = Some(host),
      bakeAs = Some("branch"),
      bakeIndex = Some(index),
      activation = Activation.State(condition)
    )

  /** `ifhost` at "c_0". `then` is active while alarm.h == armed, and the
    * always-true `else` otherwise; by default they show sensor.a and sensor.b.
    */
  def ifDash(
      thenContent: LayoutNode = branchCard("sensor.a"),
      elseContent: LayoutNode = branchCard("sensor.b")
  ): Dashboard =
    Dashboard(
      cards = ifCards,
      card = LayoutNode
        .Component(
          "col",
          regions = LayoutNode.kids(LayoutNode.Component("ifhost"))
        ),
      surfaces = Map(
        "then" -> stateMember(thenContent, "c_0", 0, armedCond),
        "else" -> stateMember(elseContent, "c_0", 1, always)
      )
    )

  def es(id: String, state: String): EntityState = st(id, state)

  /** One viewer over an evolving store: each [[step]] applies one update,
    * deriving the StateChange as the WS ingest does, records the frame, and
    * returns what this viewer's pull emits. `holds` and `position` accumulate
    * across steps.
    */
  val BodyRepaint = "selector #dashboard"

  def mixedTabsDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """<div id="{{hostId}}" class="tabs">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("tabs", vars = TabDeclared)
      )
    ),
    surfaces = Map(
      "t0" -> tabMember(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        "c_1",
        0
      )
    )
  )

  /** A server-built event carries `data: ` on its continuation lines; a decoded
    * one, and an [[SseFrame]] read through its parser, do not. Tolerating both
    * lets the accessors read `sharedPatches` and the stream alike.
    */
  private def dataLine(data: Option[String], key: String): Option[String] =
    data.toList
      .flatMap(_.linesIterator)
      .map(l => if (l.startsWith("data: ")) l.drop("data: ".length) else l)
      .collectFirst {
        case l if l.startsWith(s"$key ") => l.drop(key.length + 1)
      }

  extension (e: ServerSentEvent) {

    def mode: String = dataLine(e.data, "mode").getOrElse("outer")
    def selector: Option[String] = dataLine(e.data, "selector")
    def elements: Option[String] = dataLine(e.data, "elements")
    def signals: Option[String] = dataLine(e.data, "signals")
    def name: String = e.eventType.getOrElse("")
  }

  extension (e: SseFrame) {
    def mode: String = dataLine(e.data, "mode").getOrElse("outer")
    def selector: Option[String] = dataLine(e.data, "selector")
    def elements: Option[String] = dataLine(e.data, "elements")
    def signals: Option[String] = dataLine(e.data, "signals")
    def name: String = e.eventType.getOrElse("")
  }

  def events(out: List[Addressed]): List[SseFrame] =
    out.map(_.patch.toSse)

  val PopupSig = Server.UiSignalPrefix + Dashboard.PopupHostId

  val Elements = "datastar-patch-elements"

  val Signals = "datastar-patch-signals"

  def sseFrom(
      resp: Response[IO]
  )(done: ServerSentEvent => Boolean): IO[List[ServerSentEvent]] =
    resp.body
      .through(ServerSentEvent.decoder[IO])
      .takeThrough(e => !done(e))
      .compile
      .toList

  def isCursor(e: ServerSentEvent): Boolean =
    e.signals.exists(_.contains(Server.StoreVersionSignal))

  /** Not part of the opening block; see [[LiveWorld.connect]] for why it is
    * still waited for.
    */
  def isLiveness(e: ServerSentEvent): Boolean =
    e.signals.exists(_.contains(Server.HaDownSignal))

  def isCursor(e: SseFrame): Boolean =
    e.signals.exists(_.contains(Server.StoreVersionSignal))

  /** Signals are left out: `haDown` rides its own merged stream, so its
    * position among the others is a scheduling detail.
    */
  def domEvents(
      events: List[ServerSentEvent]
  ): List[(String, Option[String], Option[String])] =
    events
      .filter(_.name == Elements)
      .map(e => (e.mode, e.selector, e.elements))

  /** A booted server and several clients, in-process through `routes.run`, over
    * a caller's [[Renderer]]. Kept for `RenderCacheContentionSuite` alone,
    * which counts renders through a subclass that [[TestServer]] cannot take;
    * everything else goes through [[TestServer]].
    */
  class LiveWorld(
      routes: org.http4s.HttpApp[IO],
      store: StateStore,
      sessions: Sessions,
      clients: Ref[IO, List[TestServer.LiveClient]],
      supervisor: Supervisor[IO]
  ) {

    /** `query` carries what a document would hand back, such as `?ui.<id>=<n>`
      * (see [[Server.Restore]]).
      */
    def connect(query: String = ""): IO[TestServer.LiveClient] =
      for {
        seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
        resp <- routes.run(
          Request[IO](
            Method.GET,
            Uri.unsafeFromString(s"/sse/dashboard/dashboard/patch$query")
          )
        )
        // Supervised: the body carries the connection's `keepAlive` stream, and
        // with no socket to close it a bare fiber outlives the test.
        _ <- supervisor.supervise(
          resp.body
            .through(ServerSentEvent.decoder[IO])
            .evalMap(e => seen.update(_ :+ e))
            .compile
            .drain
        )
        client = new TestServer.LiveClient(seen)
        // `_haDown` rides a branch merged into the connection stream, so it can
        // land either side of the cursor; waiting for the cursor alone left it
        // for the next drain. It always comes here: with no document, the
        // session starts at `None` and the first health value always patches.
        _ <- fs2.Stream
          .repeatEval(seen.get <* IO.sleep(10.millis))
          .find(es => es.exists(isCursor) && es.exists(isLiveness))
          .compile
          .drain
          .timeout(15.seconds)
        _ <- clients.update(_ :+ client)
      } yield client

    /** Two gates: [[served]] proves every session pulled this version, then
      * [[TestServer.LiveClient.arrived]] that the bytes landed. A client owed
      * nothing receives nothing, so "the frame is done" has to be asked of the
      * server.
      */
    def change(next: EntityState): IO[Unit] =
      recording *> store.update(next) *> settle

    /** One HA frame carrying several entities, as one store update. */
    def frame(nexts: List[EntityState]): IO[Unit] =
      recording *> store.update(nexts.map(Ingest.Replace(_))) *> settle

    /** A topic delivers only to current subscribers, and the recorder starts
      * asynchronously, so a `store.update` that beats it is never recorded and
      * every later gate times out. Connecting a client does not imply it.
      */
    private def recording: IO[Unit] =
      store.changeSubscribers
        .filter(_ >= 1)
        .head
        .compile
        .drain
        .timeout(15.seconds)

    private def settle: IO[Unit] =
      served *> clients.get.flatMap(_.traverse_(_.arrived))

    /** Not `Sessions.floor`, which includes `Lingering` and `Fresh` sessions:
      * one with no stream never pulls, so waiting on it times out, and the test
      * that left it fails elsewhere, intermittently.
      */
    private def served: IO[Unit] =
      fs2.Stream
        .repeatEval(
          (store.current, sessions.forSlug("dashboard")).flatMapN {
            (now, all) =>
              all
                .traverse(s => (s.tenure.get, s.position.get).tupled)
                .map(_.collect { case (_: Tenure.Held, at) => at })
                .map(live => live.nonEmpty && live.forall(_ >= now.version))
          } <* IO.sleep(5.millis)
        )
        .find(identity)
        .compile
        .drain
        .timeout(15.seconds)
  }

  /** [[TestServer]] over `dash`, seeded with `initial`; real time only, see
    * [[simulateTime]].
    */
  def live[A](
      dash: Dashboard,
      initial: Map[String, EntityState],
      windows: Server.SessionWindows = Server.SessionWindows.default
  )(use: TestServer => IO[A]): IO[A] =
    TestServer
      .resource(
        dash,
        initial.values.toList
          .map(e => FixtureEntity(e.entityId, e.state, e.attributes)),
        windows = windows
      )
      .use(use)
      .timeout(30.seconds)

  extension (v: TestServer.Viewer)
    /** [[TestServer.Viewer.step]] from the states these suites build. */
    def change(states: EntityState*): IO[List[SseFrame]] =
      v.step(states.map(s => FixtureEntity(s.entityId, s.state, s.attributes))*)

  def liveOne(dash: Dashboard, initial: Map[String, EntityState])(
      use: (TestServer, TestServer.LiveClient) => IO[Unit]
  ): IO[Unit] =
    live(dash, initial)(w => w.connect().flatMap(use(w, _)))

  def liveWorldOf(
      renderer: Renderer,
      initial: Map[String, EntityState]
  )(use: LiveWorld => IO[Unit]): IO[Unit] =
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(renderer))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      clients <- Ref[IO].of(List.empty[TestServer.LiveClient])
      site <- Server.LiveSite.of(
        Map("dashboard" -> ref),
        Map.empty,
        "dashboard"
      )
      _ <- Server
        .withSite(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          site,
          sessions,
          TestAuth.openGate,
          AssetCache.empty,
          Signal.constant(true),
          SystemPkl.empty,
          dumpRefresh = None,
          Server.SessionWindows.default,
          Tracer.noop,
          Logging.console,
          Meters.noop,
          queries = None
        )
        .use(server =>
          // Scoped to `use`: its connect fibers must be gone before the
          // server releases.
          Supervisor[IO].use(supervisor =>
            use(
              new LiveWorld(
                server.routes.orNotFound,
                store,
                sessions,
                clients,
                supervisor
              )
            )
          )
        )
    } yield ()).timeout(30.seconds)

  def twoTabsDash = Dashboard(
    cards = Map(
      "col" -> CardDef(
        "<div>{{#children}}{{{html}}}{{/children}}</div>",
        regions = Map("children" -> Region())
      ),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template =
          """<div id="{{hostId}}" class="tabs">{{#panel}}{{{html}}}{{/panel}}</div>""",
        regions = Map("panel" -> Region(Region.Baked))
      )
    ),
    card = LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.shared")))
        ),
        LayoutNode.Component("tabs", vars = TabDeclared)
      )
    ),
    surfaces = Map(
      "t0" -> tabMember(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        "c_1",
        0
      ),
      "t1" -> tabMember(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        ),
        "c_1",
        1
      )
    )
  )

}
