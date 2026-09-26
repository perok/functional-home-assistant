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
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import fh.view.testkit.TestAuth
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.http4s.*
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.targetName
import scala.concurrent.duration.*

/** What the `Server` suites share. A trait so a harness can call `assertEquals`
  * and return `IO`.
  */
trait ServerHarness extends munit.CatsEffectSuite {

  // Shadows `test` for `IO[Unit]` bodies so they run under TestControl, where
  // the harness's IO.sleep poll loops (`quiet`, `served`) resolve the moment
  // every fiber is blocked. Every background fiber the harness starts must be
  // supervised: a bare `.start` outlives the test under simulated time, and the
  // driver never reaches quiescence, which OOMs rather than hangs. The live
  // path has no IO.blocking, realTime/monotonic, Dispatcher or evalOn, so
  // nothing can misreport a real wait as TestControl's NonTerminationException
  // (issue #109).
  //
  // Server topology, several live fibers coordinating through refs and streams,
  // is out of TestControl's scope (typelevel/cats-effect#4104): its
  // single-threaded scheduler can park on a completion production delivers in
  // microseconds. [[testReal]] runs those on the real runtime, so keep them
  // sub-second.
  @targetName("testRealIO")
  protected def testReal(
      name: String
  )(body: => IO[Unit])(using loc: munit.Location): Unit =
    super.test(name)(body)

  /** A suite that fetches a document must set this `false`. The page route
    * streams through `fs2.io.readOutputStream`, whose writer and reader block
    * each other, and TestControl runs `IO.blocking` inline on its one thread,
    * so one side parks it and the test hangs, readers parked in
    * `PipedStreamBuffer.read`. Which tests hang depends on tick order, hence
    * per suite.
    *
    * Measured before choosing this: the sleeps are 5-300 ms against a 50 ms
    * `adoptionWindow`, so simulated time only accelerated poll loops. It does
    * give up determinism, so re-run an opted-out suite a few times before
    * trusting it.
    */
  protected def simulateTime: Boolean = true

  @targetName("testIO")
  protected def test(
      name: String
  )(body: => IO[Unit])(using loc: munit.Location): Unit = if (!simulateTime)
    testReal(name)(body)
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

  /** One frame recorded for the slug, then pulled by one viewer: the path a
    * live change takes. `holds` is what that viewer's DOM already has, and
    * suppresses redundant patches; `from = 0` asks for everything the log
    * knows.
    */
  def recordAndPull(
      server: Server,
      sessions: Sessions,
      store: StateStore,
      renderer: Renderer,
      log: Ref[IO, FragmentLog],
      changes: List[StateChange],
      open: Set[String] = Set.empty,
      ui: Map[String, String] = Map.empty,
      holds: Map[NodeId, Held] = Map.empty,
      from: Long = 0L
  ): IO[List[Addressed]] =
    // A slug nobody is watching records nothing, so the viewer must exist
    // before the frame does.
    Session
      .create("dashboard")
      .flatTap(_.open.set(open))
      .flatMap(sessions.register("recordAndPull", _)) *>
      server.recordFrame("dashboard", renderer, log, changes) *>
      (log.get, store.current, RenderCache.create).flatMapN((l, now, rc) =>
        Patches.resume(
          renderer,
          rc,
          l,
          holds,
          now.entities,
          _ => IO.pure(QuerySnapshot.empty),
          Map.empty,
          from,
          open,
          ui
        )
      )

  def tabsRenderer: Renderer = {
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
    Renderer.create(
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
          )
        ),
        surfaces = Map(
          "c_t0" -> Surface(
            panel("a"),
            bakeInto = Some("c"),
            bakeAs = Some("panel"),
            bakeIndex = Some(0),
            activation = Activation.User(defaultOpen = true)
          ),
          "c_t1" -> Surface(
            panel("b"),
            bakeInto = Some("c"),
            bakeAs = Some("panel"),
            bakeIndex = Some(1)
          )
        )
      )
    )
  }

  /** A page GET carrying ui state in the URL, as a refresh does. */
  def get(params: (String, String)*): Request[IO] =
    Request[IO](Method.GET, uri"/".withQueryParams(params.toMap))

  class CountingRenderer(dash: Dashboard, count: AtomicInteger)
      extends Renderer(dash, Templates.from(dash), Transforms.from(dash)) {
    override def renderNodeById(
        id: NodeId,
        states: Map[String, EntityState],
        uiState: Map[String, String],
        form: SlotForm,
        fragments: QuerySnapshot
    ): Option[String] = {
      count.incrementAndGet()
      super.renderNodeById(id, states, uiState, form, fragments)
    }
  }

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

  /** A viewer already current on these nodes, and a log that has recorded them.
    * The value is each id's live rendering, since suppression compares a digest
    * of what the client holds; seeded through `set`, so the digest derivation
    * is not duplicated here.
    */
  def seeded(
      renderer: Renderer,
      states: Map[String, EntityState],
      ids: Iterable[String]
  ): (FragmentLog, Map[NodeId, Held]) =
    ids.foldLeft((FragmentLog("test"), Map.empty[NodeId, Held])) {
      case ((log, holds), raw) =>
        val id = NodeId.derived(raw)
        (
          log.touched(id, 0L),
          renderer
            .renderNodeById(id, states, fragments = QuerySnapshot.empty)
            .fold(holds)(html => holds + (id -> Held.of(html)))
        )
    }

  // The log holds a version, not HTML (ADR 0012); what patches carry is
  // asserted on the patches.
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
    // Pure structure, like lib/components.pkl's `If`, at the pre-region
    // spelling on purpose: these suites pin that the string-splice fallback
    // renders the same bytes.
    "ifhost" -> CardDef(
      template = """<div id="{{hostId}}">{{{branch}}}</div>""",
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

  class SharedHarness(
      store: StateStore,
      val server: Server,
      renderer: Renderer,
      cache: Ref[IO, FragmentLog],
      sessions: Sessions
  ) {

    /** What this slug records next is recorded with nobody watching: a gap. */
    def closeViewer: IO[Unit] =
      sessions
        .get("harness")
        .flatMap(_.traverse_(sessions.deregisterIf("harness", _)))
    private val holds = Ref.unsafe[IO, Map[NodeId, Held]](Map.empty)
    private val position = Ref.unsafe[IO, Long](0L)

    private def record(next: EntityState): IO[Unit] =
      for {
        prev <- store.snapshot.map(_.get(next.entityId))
        _ <- store.update(next)
        _ <- server.recordFrame(
          "dashboard",
          renderer,
          cache,
          List(StateChange(next.entityId, prev, next))
        )
      } yield ()

    private def drain: IO[List[SseFrame]] =
      (cache.get, store.current, holds.get, position.get, RenderCache.create)
        .flatMapN { (log, now, held, from, rc) =>
          Patches
            .resume(
              renderer,
              rc,
              log,
              held,
              now.entities,
              _ => IO.pure(QuerySnapshot.empty),
              Map.empty,
              from + 1
            )
            .flatMap { patches =>
              holds.set(
                patches.foldLeft(held)(
                  Patches.applied(renderer.ancestry, _, _)
                )
              ) *>
                position
                  .set(now.version)
                  .as(events(patches) :+ Server.versionSignal(now.version))
            }
        }

    private def sharedBatch(next: EntityState): IO[List[SseFrame]] =
      (record(next) *> drain).timeout(30.seconds)

    def step(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(elementPatches)

    /** One slow client catching up in a single pass. */
    def queued(nexts: List[EntityState]): IO[List[String]] =
      (nexts.traverse_(record) *> drain)
        .map(elementPatches)
        .timeout(30.seconds)

    /** Cursor signal included. */
    def stepRaw(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(_.map(_.render))

    def cacheNow: IO[Map[NodeId, Long]] =
      cache.get.map(logged).timeout(30.seconds)

    def mutationsNow: IO[Map[NodeId, Mutation]] =
      cache.get.map(_.mutations).timeout(30.seconds)

    def logId: IO[String] = cache.get.map(_.id)

    def headHash: String = renderer.headHash

    def styleHash: String = renderer.styleHash

    /** Connect with `cursor` in the `datastar` param, as a reconnecting browser
      * does, and read the opening block: up to the cursor signal, or the reload
      * signal that replaces it.
      */
    def opening(
        cursor: Option[Server.Cursor],
        popup: Option[String] = None
    ): IO[String] =
      val signals = cursor.toList.map(c =>
        s""""${Server.CursorSignal}":{""" +
          s""""${Server.HeadHashSignal}":"${c.headHash}",""" +
          s""""${Server.StyleHashSignal}":"${c.styleHash}",""" +
          s""""${Server.LogIdSignal}":"${c.logId}",""" +
          s""""${Server.StoreVersionSignal}":${c.version}}"""
      ) ++ popup.map(p => s""""$PopupSig":"$p"""")
      val uri =
        if (signals.isEmpty) uri"/sse/dashboard/dashboard/patch"
        else
          uri"/sse/dashboard/dashboard/patch"
            .withQueryParam("datastar", signals.mkString("{", ",", "}"))
      server.routes.orNotFound
        .run(Request[IO](Method.GET, uri))
        .flatMap(
          _.body
            .through(fs2.text.utf8.decode)
            .scan("")(_ + _)
            .takeThrough(seen =>
              !seen.contains(Server.StoreVersionSignal) &&
                !seen.contains(Server.ReloadSignal)
            )
            .compile
            .lastOrError
        )
        .timeout(30.seconds)
  }

  object SharedHarness {

    /** These tests hold their `Server` in `IO` and never start a publisher, so
      * nothing is supervised through it; hence no release.
      */
    private lazy val suiteSupervisor: Supervisor[IO] =
      Supervisor[IO].allocated.unsafeRunSync()._1

    def create(
        dash: Dashboard,
        initial: Map[String, EntityState]
    ): IO[SharedHarness] =
      (for {
        store <- StateStore.inMemory(initial)
        ref <- SignallingRef[IO].of(
          Server.RendererState.Ready(Renderer.create(dash))
        )
        sessions <- Sessions.create
        // Registered because a slug nobody watches records nothing; its empty
        // open set matches what `drain` resumes with.
        _ <- Session
          .create("dashboard")
          .flatMap(sessions.register("harness", _))
        // The patch path never calls HA; an unexpected registry call still
        // raises.
        fake <- FakeHomeAssistant.create(Nil)
        site <- Server.LiveSite.of(
          Map("dashboard" -> ref),
          Map.empty,
          "dashboard"
        )
        live <- site.liveFor("dashboard").map(_.get)
        server = new Server(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          site,
          sessions,
          TestAuth.openGate,
          suiteSupervisor
        )
        renderer <- ref.get.map(_.rendererOf.get)
        // The recorder writes the slug's log, the one a reconnect resumes from,
        // so a cursor from `step` is valid at `opening`.
      } yield new SharedHarness(store, server, renderer, live.log, sessions))
        .timeout(30.seconds)
  }

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
        LayoutNode.Component("tabs")
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
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

  class LiveClient(seen: Ref[IO, Vector[ServerSentEvent]]) {

    def drain: IO[List[ServerSentEvent]] =
      seen.getAndSet(Vector.empty).map(_.toList)

    /** Quiet alone cannot tell "nothing was produced" from "nothing has arrived
      * yet", so it follows [[LiveWorld.change]]'s server-side proof that every
      * session pulled the frame. A pull that owes a client nothing sends
      * nothing, not even a cursor, so waiting for one would hang on exactly the
      * clients this checks are left alone.
      */
    def arrived: IO[Unit] = quiet

    private def quiet: IO[Unit] =
      fs2.Stream
        .repeatEval(seen.get.map(_.size) <* IO.sleep(25.millis))
        .drop(6)
        // Four equal readings, not two: a batch's events can arrive more than a
        // sample apart under load, and two samples returned mid-batch in CI.
        .sliding(4)
        .find(w => w.toList.distinct.sizeIs == 1)
        .compile
        .drain
  }

  /** A booted server and several clients, in-process through `routes.run`, so
    * deterministic. Over [[SharedHarness]] it adds the publisher fibers, the
    * topic and the per-connection merge, where the running app's bugs had been
    * hiding.
    */
  class LiveWorld(
      routes: org.http4s.HttpApp[IO],
      store: StateStore,
      sessions: Sessions,
      clients: Ref[IO, List[LiveClient]],
      supervisor: Supervisor[IO]
  ) {

    /** `query` carries what a document would hand back, such as `?ui.<id>=<n>`
      * (see [[Server.Restore]]).
      */
    def connect(query: String = ""): IO[LiveClient] =
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
        client = new LiveClient(seen)
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
      * [[LiveClient.arrived]] that the bytes landed. A client owed nothing
      * receives nothing, so "the frame is done" has to be asked of the server.
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

  def liveWorld(
      dash: Dashboard,
      initial: Map[String, EntityState]
  )(use: LiveWorld => IO[Unit]): IO[Unit] =
    liveWorldOf(Renderer.create(dash), initial)(use)

  def liveWorldOf(
      renderer: Renderer,
      initial: Map[String, EntityState]
  )(use: LiveWorld => IO[Unit]): IO[Unit] =
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(Server.RendererState.Ready(renderer))
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      clients <- Ref[IO].of(List.empty[LiveClient])
      _ <- Server
        .resource(
          ServiceCalls.asInstance(HomeAssistantApi.fromWs(fake)),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions,
          TestAuth.openGate
        )
        .use(server =>
          // Scoped to `use`: its connect fibers must be gone before
          // `Server.resource` releases.
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

  val SseUrlMarker: String = """data-init="@get\('"""

  def connOfPage(routes: org.http4s.HttpApp[IO]): IO[String] =
    routes
      .run(Request[IO](Method.GET, uri"/d/dashboard"))
      .flatMap(_.bodyText.compile.string)
      .map { page =>
        Uri
          .unsafeFromString(
            "/" + page
              .split("""data-init="@get\('""")(1)
              .split("'")(0)
              .replace("&amp;", "&")
          )
          .query
          .params(Server.ConnSignal)
      }

  def liveClient(
      dash: Dashboard,
      initial: Map[String, EntityState]
  )(use: (LiveWorld, LiveClient) => IO[Unit]): IO[Unit] =
    liveWorld(dash, initial)(w => w.connect().flatMap(use(w, _)))

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
        LayoutNode.Component("tabs")
      )
    ),
    surfaces = Map(
      "t0" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(0),
        activation = Activation.User(defaultOpen = true)
      ),
      "t1" -> Surface(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.b")))
        ),
        bakeInto = Some("c_1"),
        bakeAs = Some("panel"),
        bakeIndex = Some(1)
      )
    )
  )

}
