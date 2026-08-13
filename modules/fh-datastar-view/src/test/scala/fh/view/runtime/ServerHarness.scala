package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  SlotSource,
  Surface,
  Theme
}
import fh.view.testkit.FakeHomeAssistant
import fh.view.testkit.DashboardBuilders.st
import fh.view.testkit.TestIds.given
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.http4s.*
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.implicits.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** Everything the `Server` suites share: the fixtures, the two harnesses, and
  * the small readers that turn an SSE body into something assertable.
  *
  * It exists because these lived in one 4 900-line suite. Splitting that by
  * SUBJECT left this behind — the parts genuinely used by more than one — and
  * keeping it a trait rather than an object is what lets a harness call
  * `assertEquals` and return `IO`.
  */
trait ServerHarness extends munit.CatsEffectSuite {

  def resumeNow(
      renderer: Renderer,
      log: FragmentLog,
      holds: Map[NodeId, Digest],
      states: Map[String, EntityState],
      v: Long,
      open: Set[String],
      uiState: Map[String, String]
  ): List[Addressed] =
    RenderCache.create
      .flatMap(
        Patches.resume(renderer, _, log, holds, states, v, open, uiState)
      )
      .unsafeRunSync()

  def recordAndPull(
      server: Server,
      sessions: Sessions,
      store: StateStore,
      renderer: Renderer,
      log: Ref[IO, FragmentLog],
      changes: List[StateChange],
      open: Set[String] = Set.empty,
      ui: Map[String, String] = Map.empty,
      holds: Map[NodeId, Digest] = Map.empty,
      from: Long = 0L
  ): IO[List[Addressed]] =
    // A slug nobody is watching records nothing, so the viewer this is about
    // to pull for has to exist before the frame does. Tests that care about a
    // viewer's own surfaces register their own too; this one is here to open
    // the gate.
    Session
      .create("dashboard")
      .flatTap(_.open.set(open))
      .flatMap(sessions.register("recordAndPull", _)) *>
      server.recordFrame("dashboard", renderer, log, changes) *>
      (log.get, store.current, RenderCache.create).flatMapN((l, now, rc) =>
        Patches.resume(renderer, rc, l, holds, now.entities, from, open, ui)
      )

  // A minimal tabs dashboard: a `tabs` component (id "c") with two panels baked
  // into it (c_t0 default, c_t1) — the ui-state index selects among them.
  def tabsRenderer: Renderer = {
    val cards = Map(
      "btn" ->
        CardDef("<button>{{label}}</button>", slots = List("label")),
      "card" ->
        CardDef("<span>{{state}}</span>", slots = List("state")),
      // Split like the shipped `Tabs` — minimal markup, real shape.
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        self = Some(
          """<div id="{{selfId}}" class="tabs">""" +
            """{{#children}}{{{html}}}{{/children}}</div>"""
        ),
        mount = Some(
          """<div id="{{mountId}}" data-signals="{ tab_{{id}}: {{bakeIndex}} }">{{{panel}}}</div>"""
        )
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
          children = List(
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
        uiState: Map[String, String]
    ): Option[String] = {
      count.incrementAndGet()
      super.renderNodeById(id, states, uiState)
    }
  }

  // One live leaf bound to sensor.a inside a static container — no bake
  // groups, so its live patches belong entirely to the shared per-slug pass.
  def liveLeafDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state"))
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
        LayoutNode.Component(
          "card",
          slots = Map("state" -> SlotSource(Some("sensor.a")))
        )
      )
    )
  )

  def on(id: String): EntityState = st(id, "on")

  def off(id: String): EntityState = st(id, "off")

  /** A candidate set where every candidate carries the same clause list.
    *
    * `clauses` is `(extra guard, card, slots)` per clause, tried in order, and
    * every clause is additionally guarded on `state == on` — so a candidate is
    * present exactly while it is on, which is what these suites drive. Each
    * member's node names its OWN entity, because the build knows the candidate.
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

  // A set of on-state entities as the layout root (group id "c"); each present
  // member renders `<span>on</span>` in an `fh-cell` wrapper `c_<slug>`.
  def dynDash = Dashboard(
    cards =
      Map("dot" -> CardDef("<span>{{state}}</span>", slots = List("state"))),
    // Candidates in entity-id order, which is what the placement assertions
    // were written against when membership was a sorted query result. A set
    // places by AUTHORED order, so writing them sorted keeps every answer the
    // same while making the order a build-time decision.
    card = onSet(
      List("light.a", "light.b", "light.c", "light.d", "light.z"),
      List((None, "dot", Map("state" -> SlotSource())))
    )
  )

  def seeded(
      renderer: Renderer,
      states: Map[String, EntityState],
      ids: Iterable[String]
  ): (FragmentLog, Map[NodeId, Digest]) =
    ids.foldLeft((FragmentLog("test"), Map.empty[NodeId, Digest])) {
      case ((log, holds), raw) =>
        val id = NodeId.derived(raw)
        (
          log.touched(id, 0L),
          renderer
            .renderLogged(id, states)
            .fold(holds)(html => holds + (id -> Digest.of(html)))
        )
    }

  // WHICH nodes the log knows about, and when each last changed — everything
  // these contracts assert on. The log holds a version, not HTML, so there is no
  // node -> html projection to make (docs/adr/0012-each-session-renders-what-it-is-owed.md);
  // what the patches CARRY is asserted on the patches themselves.
  def logged(log: FragmentLog): Map[NodeId, Long] = log.fragments

  def elementPatches(batch: List[ServerSentEvent]): List[String] =
    batch.map(_.renderString).filterNot(_.contains("datastar-patch-signals"))

  // An empty conjunction is vacuously true, and reads no entity — so an `else`
  // member is an ordinary condition with no subject to supply.
  val always: Predicate = Predicate.And(Nil)

  // "Entity X is in state Y": the condition names its entity, so evaluating it
  // is one lookup rather than a scan.
  def entityIs(id: String, state: String): Predicate =
    Predicate.Cmp("state", Op.Eq, Json.fromString(state), entity = Some(id))

  val armedCond = entityIs("alarm.h", "armed")

  val ifCards = Map(
    "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
    // A pure mount, like lib/components.pkl's `If`.
    "ifhost" -> CardDef(
      template = "{{{self}}}{{{mount}}}",
      mount = Some("""<div id="{{mountId}}">{{{branch}}}</div>""")
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
        .Component("col", children = List(LayoutNode.Component("ifhost"))),
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

    /** The viewer closes its tab, so what this slug records next is what it
      * records with nobody watching — a gap.
      */
    def closeViewer: IO[Unit] =
      sessions
        .get("harness")
        .flatMap(_.traverse_(sessions.deregisterIf("harness", _)))
    private val holds = Ref.unsafe[IO, Map[NodeId, Digest]](Map.empty)
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

    /** What this viewer is owed since its last pull, claimed as it goes. */
    private def drain: IO[List[ServerSentEvent]] =
      (cache.get, store.current, holds.get, position.get, RenderCache.create)
        .flatMapN { (log, now, held, from, rc) =>
          Patches
            .resume(renderer, rc, log, held, now.entities, from + 1)
            .flatMap { patches =>
              holds.set(patches.foldLeft(held)(Patches.applied)) *>
                position
                  .set(now.version)
                  .as(events(patches) :+ Server.versionSignal(now.version))
            }
        }

    private def sharedBatch(next: EntityState): IO[List[ServerSentEvent]] =
      (record(next) *> drain).timeout(30.seconds)

    def step(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(elementPatches)

    /** Several frames recorded before this viewer pulls any of them — one slow
      * client, catching up in a single pass.
      */
    def queued(nexts: List[EntityState]): IO[List[String]] =
      (nexts.traverse_(record) *> drain)
        .map(elementPatches)
        .timeout(30.seconds)

    /** Everything a batch emits, cursor signal included. */
    def stepRaw(next: EntityState): IO[List[String]] =
      sharedBatch(next).map(_.map(_.renderString))

    def cacheNow: IO[Map[NodeId, Long]] =
      cache.get.map(logged).timeout(30.seconds)

    /** Where the changelog says each container's member went. */
    def mutationsNow: IO[Map[NodeId, Mutation]] =
      cache.get.map(_.mutations).timeout(30.seconds)

    def logId: IO[String] = cache.get.map(_.id)

    def headHash: String = renderer.headHash

    def styleHash: String = renderer.styleHash

    /** Connect to the SSE route with `cursor` in the `datastar` signal param —
      * exactly how a reconnecting browser arrives — and read the OPENING block.
      * That ends at the cursor signal, which the connect path emits last, or at
      * the reload signal, which replaces the whole block.
      */
    def opening(
        cursor: Option[Server.Cursor],
        popup: Option[String] = None
    ): IO[String] =
      // Nested under `_cursor`, exactly as the client's store holds it.
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

    /** One supervisor for the whole suite.
      *
      * [[Server.resource]] normally owns this (it scopes the shared publishers'
      * fibers), but these harness tests hold their `Server` in `IO` rather than
      * `Resource` — they drive `sharedPatches` directly and never start a
      * publisher, so nothing is ever supervised through it and it holds no
      * fibers to leak. Allocated without a release for the same reason: there
      * is nothing to cancel.
      */
    private lazy val suiteSupervisor: Supervisor[IO] =
      Supervisor[IO].allocated.unsafeRunSync()._1

    def create(
        dash: Dashboard,
        initial: Map[String, EntityState]
    ): IO[SharedHarness] =
      (for {
        store <- StateStore.inMemory(initial)
        ref <- SignallingRef[IO].of(Renderer.create(dash))
        sessions <- Sessions.create
        // The viewer this harness drains for, registered because a slug nobody
        // is watching records nothing. Its open set is empty, matching what
        // `drain` resumes with.
        _ <- Session
          .create("dashboard")
          .flatMap(sessions.register("harness", _))
        // Stub HA: the SSE/patch path never calls it (an unexpected registry
        // call still raises); the store is driven in-memory, so the empty seed
        // is inert.
        fake <- FakeHomeAssistant.create(Nil)
        live <- Server.LiveSlug.create(ref)
        registry <- Ref[IO].of(Map("dashboard" -> live))
        server = new Server(
          HomeAssistantApi.fromWs(fake),
          store,
          registry,
          "dashboard",
          sessions,
          suiteSupervisor
        )
        renderer <- ref.get
        // The recorder writes the SLUG's log — the same one a reconnecting
        // client resumes from — so a cursor issued by `step` is valid at
        // `opening`, as in production.
      } yield new SharedHarness(store, server, renderer, live.log, sessions))
        .timeout(30.seconds)
  }

  val BodyRepaint = "selector #dashboard"

  def mixedTabsDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      // A bar-less tabs host: pure mount, no `self` — nothing about it can
      // change without its content changing.
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}" class="tabs">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
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

  extension (e: ServerSentEvent) {
    private def line(key: String): Option[String] =
      e.data.toList
        .flatMap(_.linesIterator)
        // A SERVER-BUILT event carries the `data: ` prefix on its continuation
        // lines (the multi-line data field is assembled as wire text); a
        // DECODED one does not. Tolerating both lets these accessors read an
        // event straight off `sharedPatches` as well as one off the stream.
        .map(l => if (l.startsWith("data: ")) l.drop("data: ".length) else l)
        .collectFirst {
          case l if l.startsWith(s"$key ") => l.drop(key.length + 1)
        }

    /** Datastar's default when the event names none. */
    def mode: String = line("mode").getOrElse("outer")
    def selector: Option[String] = line("selector")
    def elements: Option[String] = line("elements")
    def signals: Option[String] = line("signals")
    def name: String = e.eventType.getOrElse("")
  }

  def events(out: List[Addressed]): List[ServerSentEvent] =
    out.map(_.patch.toSse)

  val PopupSig = Server.UiSignalPrefix + Dashboard.PopupHostId

  val Elements = "datastar-patch-elements"

  val Signals = "datastar-patch-signals"

  /** Read a response body as the events it carries. */
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

  def domEvents(
      events: List[ServerSentEvent]
  ): List[(String, Option[String], Option[String])] =
    events
      .filter(_.name == Elements)
      .map(e => (e.mode, e.selector, e.elements))

  class LiveClient(seen: Ref[IO, Vector[ServerSentEvent]]) {

    /** Everything received since the last read. */
    def drain: IO[List[ServerSentEvent]] =
      seen.getAndSet(Vector.empty).map(_.toList)

    /** Wait until whatever this change produced for this client has ARRIVED.
      *
      * Quiet alone cannot tell "nothing was produced" from "nothing has arrived
      * yet", which is why this is never used on its own: [[LiveWorld.change]]
      * first proves SERVER-side that every session pulled the frame, and only
      * then waits here for the bytes to land. The two together are what the
      * cursor handshake used to give for free.
      *
      * That handshake is gone on purpose. A pull that owes a client nothing now
      * sends nothing at all, so a cursor no longer marks the end of every batch
      * for every connection — it marks the end of a batch that had something in
      * it. Waiting for one would hang exactly on the clients this suite exists
      * to check are left alone.
      */
    def arrived: IO[Unit] = quiet

    private def quiet: IO[Unit] =
      fs2.Stream
        .repeatEval(seen.get.map(_.size) <* IO.sleep(25.millis))
        .drop(6)
        // FOUR consecutive equal readings, not two. One stable sample is not
        // quiet, it is a gap: under load a batch's own events can arrive more
        // than a sample apart, and a two-sample test then returns mid-batch and
        // hands `drain` half of it. Seen in CI as an "end to end" assertion
        // missing the second half of a batch it had already been given.
        .sliding(4)
        .find(w => w.toList.distinct.sizeIs == 1)
        .compile
        .drain
  }

  class LiveWorld(
      routes: org.http4s.HttpApp[IO],
      store: StateStore,
      sessions: Sessions,
      clients: Ref[IO, List[LiveClient]]
  ) {

    /** Connect as a browser does. `query` carries what a document would hand
      * back — `?ui.<id>=<n>` for a selected tab (see [[Server.Restore]]).
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
        _ <- resp.body
          .through(ServerSentEvent.decoder[IO])
          .evalMap(e => seen.update(_ :+ e))
          .compile
          .drain
          .start
        client = new LiveClient(seen)
        // The opening block ends at the cursor handshake; wait for it so the
        // first `drain` is exactly what CONNECTING produced.
        _ <- fs2.Stream
          .repeatEval(seen.get <* IO.sleep(10.millis))
          .find(_.exists(isCursor))
          .compile
          .drain
          .timeout(15.seconds)
        _ <- clients.update(_ :+ client)
      } yield client

    /** Apply one change and wait until every client has whatever it was owed —
      * including the clients owed nothing, which is most of the point here.
      *
      * TWO gates, and neither works alone. [[served]] proves the SERVER
      * finished: every session pulled this version, so nothing is still in
      * flight. [[LiveClient.arrived]] then proves the bytes landed. Before the
      * empty-batch cursor was removed the first gate was implicit in the second
      * — every connection got a cursor whether or not it got patches — and a
      * single wait covered both. Now a client owed nothing receives literally
      * nothing, so "the frame is done" has to be asked of the server.
      *
      * One method rather than two, because with the server gate in place there
      * is no longer a question to answer differently: a caller expecting
      * silence and one expecting patches wait for the same thing and then
      * assert on what they drained.
      */
    def change(next: EntityState): IO[Unit] =
      recording *> store.update(next) *> settle

    /** ONE HA frame carrying several entities — a single store update, as the
      * feed delivers it.
      */
    def frame(nexts: List[EntityState]): IO[Unit] =
      recording *> store.update(nexts.map(Ingest.Replace(_))) *> settle

    /** The slug's recorder has SUBSCRIBED to `store.changes`.
      *
      * A topic delivers only to current subscribers, and the recorder fiber
      * starts asynchronously with `Server.resource` — so a `store.update` that
      * beats it is never recorded, the doorbell never rings, and every gate
      * after it waits out its full timeout. Connecting a client does not imply
      * it: the stream and the recorder are different fibers.
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

    /** Every session with a LIVE STREAM has pulled the store's current version.
      *
      * Not `Sessions.floor`, which is the minimum over ALL of a slug's sessions
      * — `Lingering` and `Fresh` ones included. That is right for pruning (a
      * lingering session may come back and resume from its position) and wrong
      * here: a session with no stream never pulls, so its position never moves
      * and this would wait out its whole timeout. Any test that leaves one
      * behind then fails somewhere else entirely, intermittently.
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

  /** [[liveWorld]] with the renderer supplied — for the suites that need a
    * [[CountingRenderer]] behind the live path rather than a plain one.
    */
  def liveWorldOf(
      renderer: Renderer,
      initial: Map[String, EntityState]
  )(use: LiveWorld => IO[Unit]): IO[Unit] =
    (for {
      store <- StateStore.inMemory(initial)
      ref <- SignallingRef[IO].of(renderer)
      sessions <- Sessions.create
      fake <- FakeHomeAssistant.create(Nil)
      clients <- Ref[IO].of(List.empty[LiveClient])
      _ <- Server
        .resource(
          HomeAssistantApi.fromWs(fake),
          store,
          Map("dashboard" -> ref),
          "dashboard",
          sessions
        )
        .use(server =>
          use(new LiveWorld(server.routes.orNotFound, store, sessions, clients))
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

  /** One client, for the tests that only need one. */
  def liveClient(
      dash: Dashboard,
      initial: Map[String, EntityState]
  )(use: (LiveWorld, LiveClient) => IO[Unit]): IO[Unit] =
    liveWorld(dash, initial)(w => w.connect().flatMap(use(w, _)))

  def twoTabsDash = Dashboard(
    cards = Map(
      "col" -> CardDef("<div>{{#children}}{{{html}}}{{/children}}</div>"),
      "card" -> CardDef("<span>{{state}}</span>", slots = List("state")),
      "tabs" -> CardDef(
        template = "{{{self}}}{{{mount}}}",
        mount = Some("""<div id="{{mountId}}" class="tabs">{{{panel}}}</div>""")
      )
    ),
    card = LayoutNode.Component(
      "col",
      children = List(
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
