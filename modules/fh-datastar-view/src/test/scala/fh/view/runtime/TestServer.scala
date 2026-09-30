// In `fh.view.runtime` so it can reach the `private[runtime]` readiness seams
// (`StateStore.changeSubscribers`, `Server.connectedSessions`, `Sessions`).
package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.std.{Queue, Supervisor}
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{host, port}
import fh.view.auth.{AuthSessions, HaOAuth}
import fh.view.build.{PklDump, Site}
import fh.view.model.{Access, Dashboard}
import fh.view.telemetry.{Logging, Meters, Telemetry}
import fh.view.testkit.{
  FakeConfig,
  FakeHomeAssistant,
  FixtureEntity,
  PklWorkspace,
  TestAuth
}
import fs2.concurrent.{Signal, SignallingRef}
import fs2.io.file.{Path, Watcher}
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.websocket.WebSocketBuilder2

import scala.concurrent.duration.*

/** [[ServerApp.assemble]] with only its edges stubbed: the HA socket is a
  * [[FakeHomeAssistant]] handed over as a connection only [[haDown]] closes,
  * HA's token endpoint and the asset CDN are in-process stubs, and the source
  * watcher is a [[TestServer.FakeWatcher]] fed by [[edit]]. Everything else —
  * the feed's reconnect and narrowing, the auth routes, the error boundary — is
  * the production wiring, and requests go through the app production binds.
  */
final class TestServer(
    val fake: FakeHomeAssistant,
    val store: StateStore,
    val server: Server,
    val slug: String,
    val auth: TestAuth,
    val sessions: Sessions,
    val site: Server.LiveSite,
    app: HttpApp[IO],
    healthy: Signal[IO, Boolean],
    haUp: SignallingRef[IO, Boolean],
    watcher: TestServer.FakeWatcher,
    supervisor: Supervisor[IO],
    clients: Ref[IO, List[TestServer.LiveClient]]
) {

  /** HA's socket closes and every reconnect waits, so the feed's own health
    * falls, the one the banner and the page read.
    */
  def haDown: IO[Unit] =
    haUp.set(false) *> healthy.waitUntil(!_)

  /** An author saving `file`: written, then announced as the OS watcher would,
    * once the server watches it or its directory. Returns before the debounced
    * reload runs.
    */
  def edit(file: os.Path, content: String): IO[Unit] =
    watcher.edit(file, content)

  /** '''`n` counts the recorder too.''' A test adding its own subscriber must
    * wait for two: waiting for one is answered by the recorder, and an emit can
    * land before the test's stream attaches, failing as a `take(1)` timeout
    * rather than a wrong value.
    */
  def awaitChangeSubscribers(n: Int): IO[Unit] =
    store.changeSubscribers.filter(_ >= n).head.compile.drain

  /** Await `n` adopted sessions (`Tenure.Held`). Nothing is pushed, so a test
    * waits for a connection that will pull.
    *
    * Necessary but not sufficient for a test expecting a change as a live
    * patch: a session is adopted before its opening block runs, so the change
    * can land in the opening repaint. Such a test uses [[connect]], which
    * returns after the opening block.
    */
  def awaitSharedSubscribers(n: Int = 1): IO[Unit] =
    server.connectedSessions.filter(_ >= n).head.compile.drain

  /** The smoke suites' one gate before `fake.emit`. `subscribers` defaults to
    * 1: the per-slug recorder is the only consumer of `changes`, however many
    * connections are open.
    */
  def awaitLive(subscribers: Int = 1): IO[Unit] =
    awaitChangeSubscribers(subscribers) *> awaitSharedSubscribers(1)

  /** The state of an idle page once its stream has dropped and
    * [[Server.LingerWindow]] has passed. Reaping cuts the browser's stream, so
    * Datastar reconnects for real: the path ADR 0009's known gap hid three
    * times.
    */
  def forgetConnections: IO[Int] = server.forgetSessions

  /** After [[forgetConnections]]: the browser has noticed. */
  def awaitNoConnections: IO[Unit] =
    server.connectedSessions.filter(_ == 0).head.compile.drain

  /** `as` is the session id presented; `None` is an anonymous browser.
    * Defaulted to the harness admin.
    */
  private def run(
      req: Request[IO],
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Response[IO]] =
    app.run(as.fold(req)(id => req.addCookie(AuthSessions.CookieName, id)))

  /** With the harness admin's cookie on every request, for suites driving
    * routes directly; without it every gated route answers 303/401.
    */
  val gatedApp: HttpApp[IO] = HttpApp[IO](req => run(req))

  private def bodyOf(resp: Response[IO]): IO[String] =
    resp.body.through(fs2.text.utf8.decode).compile.string

  /** The bytes a browser gets before any script runs, where first-paint claims
    * have to be checked.
    */
  def page(
      query: String = "",
      as: Option[String] = Some(auth.defaultSession)
  ): IO[String] =
    pageResponse(query, as).flatMap(bodyOf)

  def pageResponse(
      query: String = "",
      as: Option[String] = Some(auth.defaultSession)
  ): IO[Response[IO]] =
    run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/d/$slug$query")),
      as
    )

  /** An action that works answers 204 and nothing else; the effect is the
    * recorded [[ServiceCall]].
    */
  def post(
      path: String,
      as: Option[String] = Some(auth.defaultSession),
      body: String = ""
  ): IO[Status] =
    postResult(path, as, body).map(_._1)

  /** A refused action answers 200 carrying the signals that report it (ADR
    * 0024), so a refusal is read from the body. `body` is the signals a
    * Datastar action sends.
    */
  def postResult(
      path: String,
      as: Option[String] = Some(auth.defaultSession),
      body: String = ""
  ): IO[(Status, String)] =
    run(
      Request[IO](
        Method.POST,
        Uri.unsafeFromString("/" + path.stripPrefix("/"))
      ).withEntity(body),
      as
    ).flatMap(resp => bodyOf(resp).map(resp.status -> _))

  /** A page load: the document and the stream URL its `data-init` would open,
    * with the `conn` it minted.
    */
  def load(query: String = ""): IO[TestServer.Document] =
    page(query).map { html =>
      val stream = Uri.unsafeFromString(
        "/" + html
          .split(TestServer.StreamUrlMarker)(1)
          .split("'")(0)
          // As a browser parses the attribute.
          .replace("&amp;", "&")
      )
      TestServer.Document(html, stream, stream.query.params(Server.ConnSignal))
    }

  def get(uri: Uri): IO[Response[IO]] = run(Request[IO](Method.GET, uri))

  private val patchUri: Uri =
    Uri.unsafeFromString(s"/sse/dashboard/$slug/patch")

  def sse(as: Option[String] = Some(auth.defaultSession)): IO[Response[IO]] =
    run(Request[IO](Method.GET, patchUri), as)

  /** A live connection collecting its events for [[TestServer.LiveClient]],
    * returned once its opening block is in. `query` carries what a document
    * would hand back, such as `?ui.<id>=<n>` (see [[Server.Restore]]).
    */
  def connect(query: String = ""): IO[TestServer.LiveClient] =
    for {
      seen <- Ref[IO].of(Vector.empty[ServerSentEvent])
      resp <- run(
        Request[IO](
          Method.GET,
          Uri.unsafeFromString(s"/sse/dashboard/$slug/patch$query")
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
      // for the next drain.
      _ <- fs2.Stream
        .repeatEval(seen.get <* IO.sleep(10.millis))
        .find(es =>
          es.exists(carries(Server.StoreVersionSignal)) &&
            es.exists(carries(Server.HaDownSignal))
        )
        .compile
        .drain
        .timeout(15.seconds)
      _ <- clients.update(_ :+ client)
    } yield client

  private def carries(signal: String)(e: ServerSentEvent): Boolean =
    e.eventType.contains("datastar-patch-signals") &&
      e.data.exists(_.contains(signal))

  /** One HA frame through the fake, returned once every [[connect]]ed client
    * has what it is owed. Two gates: [[served]] proves every session pulled
    * this version, then [[TestServer.LiveClient.arrived]] that the bytes
    * landed. A client owed nothing receives nothing, so "the frame is done" has
    * to be asked of the server.
    */
  def change(entityId: String, state: String): IO[Unit] =
    frame(FixtureEntity(entityId, state))

  def frame(entities: FixtureEntity*): IO[Unit] =
    record(entities*) *> served *> clients.get.flatMap(_.traverse_(_.arrived))

  /** Not `Sessions.floor`, which includes `Lingering` and `Fresh` sessions: one
    * with no stream never pulls, so waiting on it times out, and the test that
    * left it fails elsewhere, intermittently.
    */
  private def served: IO[Unit] =
    fs2.Stream
      .repeatEval(
        (store.version, sessions.forSlug(slug)).flatMapN { (now, all) =>
          all
            .traverse(s => (s.tenure.get, s.position.get).tupled)
            .map(_.collect { case (_: Tenure.Held, at) => at })
            .map(live => live.nonEmpty && live.forall(_ >= now))
        } <* IO.sleep(5.millis)
      )
      .find(identity)
      .compile
      .drain
      .timeout(15.seconds)

  /** What one client connected at `query` is sent for `frames`, as the data
    * lines a browser parses. The opening block is left out: a first paint can
    * already carry what the frames are meant to deliver.
    */
  def sentAfter(frames: IO[Unit], query: String = ""): IO[String] =
    for {
      client <- connect(query)
      _ <- client.drain
      _ <- frames
      sent <- client.drain
    } yield sent.flatMap(_.data).mkString("\n")

  /** One HA frame through the fake, returned once the recorder has logged it
    * and rung the doorbell. Nothing is pulled: that is [[TestServer.Viewer]]'s
    * or a connection's.
    */
  def record(entities: FixtureEntity*): IO[Unit] =
    for {
      // A topic delivers only to current subscribers, and the recorder starts
      // asynchronously, so a frame that beats it is never recorded and every
      // later gate times out. Connecting a client does not imply it.
      _ <- awaitChangeSubscribers(1).timeout(15.seconds)
      before <- store.version
      _ <- fake.emitFrame(entities.toList)
      now <- TestServer.poll(store.version)(_ > before)
      live <- server.liveSlug(slug)
      _ <- TestServer.poll(live.doorbell.get)(_ >= now)
    } yield ()

  def log: IO[FragmentLog] = server.liveSlug(slug).flatMap(_.log.get)

  /** A page load whose stream never opens, pulled by hand: what the connection
    * loop sends one client, with the test choosing when it pulls. Several
    * [[record]]s before one [[TestServer.Viewer.pull]] are a slow client
    * catching up.
    */
  def viewer(query: String = ""): IO[TestServer.Viewer] =
    for {
      doc <- load(query)
      session <- sessions
        .get(doc.conn)
        .flatMap(IO.fromOption(_)(IllegalStateException("no document session")))
      live <- server.liveSlug(slug)
      cursor <- IO.fromOption(
        Server.cursorOf(Request[IO](Method.GET, doc.stream))
      )(IllegalStateException(s"no cursor on ${doc.stream}"))
    } yield new TestServer.Viewer(this, doc, session, live, cursor)

  /** A stream from a browser this server holds no session for, so only `cursor`
    * can decide what it is owed; see [[TestServer.reconnect]].
    */
  def reconnect(
      cursor: Option[Server.Cursor],
      popup: Option[String] = None
  ): IO[String] = TestServer.reconnect(gatedApp, slug, cursor, popup)
}

object TestServer {

  final case class Document(html: String, stream: Uri, conn: String)

  /** Delivers an event only for what the server watches, as the OS does, so
    * [[TestServer.edit]] also proves the watch set covers the file.
    */
  final class FakeWatcher(
      queue: Queue[IO, Watcher.Event],
      watched: SignallingRef[IO, Set[Path]]
  ) extends ServerApp.SourceWatcher {

    def watch(path: Path): IO[IO[Unit]] =
      watched.update(_ + path).as(watched.update(_ - path))

    def events: fs2.Stream[IO, Watcher.Event] =
      fs2.Stream.fromQueueUnterminated(queue)

    def edit(file: os.Path, content: String): IO[Unit] = {
      val path = Path.fromNioPath(file.toNIO)
      val covered = (w: Set[Path]) =>
        w.contains(path) || path.parent.exists(w.contains)
      for {
        _ <- watched
          .waitUntil(covered)
          .timeout(15.seconds)
          .adaptError(_ => IllegalStateException(s"$file is not watched"))
        existed <- IO.blocking(os.exists(file))
        _ <- IO.blocking(os.write.over(file, content))
        _ <- queue.offer(
          if existed then Watcher.Event.Modified(path, 1)
          else Watcher.Event.Created(path, 1)
        )
      } yield ()
    }
  }

  private object FakeWatcher {
    def create: IO[FakeWatcher] =
      (
        Queue.unbounded[IO, Watcher.Event],
        SignallingRef[IO].of(Set.empty[Path])
      ).mapN(new FakeWatcher(_, _))
  }

  /** See [[TestServer.viewer]]. */
  final class Viewer(
      ts: TestServer,
      val document: Document,
      val session: Session,
      live: Server.LiveSlug,
      documentCursor: Server.Cursor
  ) {

    /** Encoded as the stream sends it; `Nil` when owed nothing. */
    def pull: IO[List[SseFrame]] =
      live.doorbell.get.flatMap(ts.server.pull(live, session, _))

    def step(entities: FixtureEntity*): IO[List[SseFrame]] =
      ts.record(entities*) *> pull

    /** What this client would echo on a reconnect. */
    def cursor: IO[Server.Cursor] =
      session.position.get.map(v => documentCursor.copy(version = v))

    /** The page is gone: what the slug records next, nobody is watching. */
    def leave: IO[Unit] = ts.sessions.deregisterIf(document.conn, session)
  }

  /** Carrying `cursor` and `popup` as a reconnect's signals do, read up to its
    * cursor or its reload.
    */
  def reconnect(
      app: HttpApp[IO],
      slug: String,
      cursor: Option[Server.Cursor],
      popup: Option[String]
  ): IO[String] = {
    val signals = cursor.toList.map(c =>
      s""""${Server.CursorSignal}":{""" +
        s""""${Server.HeadHashSignal}":"${c.headHash}",""" +
        s""""${Server.StyleHashSignal}":"${c.styleHash}",""" +
        s""""${Server.LogIdSignal}":"${c.logId}",""" +
        s""""${Server.StoreVersionSignal}":${c.version}}"""
    ) ++ popup.map(p =>
      s""""${Server.UiSignalPrefix}${Dashboard.PopupHostId}":"$p""""
    )
    val stream = Uri.unsafeFromString(s"/sse/dashboard/$slug/patch")
    val uri =
      if (signals.isEmpty) stream
      else stream.withQueryParam("datastar", signals.mkString("{", ",", "}"))
    app
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
      .timeout(15.seconds)
  }

  private def poll[A](read: IO[A])(done: A => Boolean): IO[A] =
    fs2.Stream
      .repeatEval(read <* IO.sleep(5.millis))
      .find(done)
      .compile
      .lastOrError
      .timeout(15.seconds)

  private val StreamUrlMarker: String = """data-init="@get\('"""

  final class LiveClient(seen: Ref[IO, Vector[ServerSentEvent]]) {

    def drain: IO[List[ServerSentEvent]] =
      seen.getAndSet(Vector.empty).map(_.toList)

    /** Quiet alone cannot tell "nothing was produced" from "nothing has arrived
      * yet", so it follows [[TestServer.change]]'s server-side proof that every
      * session pulled the frame. A pull that owes a client nothing sends
      * nothing, not even a cursor, so waiting for one would hang on exactly the
      * clients this checks are left alone.
      */
    def arrived: IO[Unit] =
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

  /** A model dashboard as the site's starting content, so no Pkl is evaluated.
    */
  def resource(
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      // What a CLI pull fetches over `/system/pkl/` (ADR 0010); an empty one
      // when not named.
      workspace: Option[os.Path] = None,
      // The site default the gate falls back to when the dashboard sets none.
      access: Access = Access.default,
      windows: Server.SessionWindows = Server.SessionWindows.default,
      config: FakeConfig = FakeConfig()
  ): Resource[IO, TestServer] =
    for {
      validated <- IO
        .fromEither(
          dashboard
            .validated()
            .leftMap(errs =>
              IllegalArgumentException(
                s"'${dashboard.slug}' does not validate: ${errs.mkString("; ")}"
              )
            )
        )
        .toResource
      fake <- FakeHomeAssistant.create(entities, config).toResource
      dir <- workspace.fold(tempDir("fh-workspace"))(Resource.pure)
      booted <- assemble(
        fake,
        dir,
        startingWith(validated.withAccess(access)),
        windows = windows
      )
      ts <- inProcess(fake, dashboard.slug, booted)
    } yield ts

  /** [[resource]], but from a Pkl entry source through the real build path
    * (`ServerApp.prepareRenderers`): the Tier-A seam (ADR 0009) where authoring
    * and runtime meet with only the HA socket stubbed.
    *
    * The entry is its own module, named under `slug` by the one entrypoint (ADR
    * 0021). `prepareDumps` re-fetches the dump from the fake's
    * `render_template`, so the dashboard is authored against, built from and
    * rendered with one source of state; `entities` must cover every
    * `dump.entities.<key>` it references.
    */
  def fromWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, TestServer] =
    for {
      tmp <- stageWorkspace(slug, entrySource, entities)
      fake <- FakeHomeAssistant.create(entities).toResource
      booted <- assemble(fake, tmp, ServerApp.prepareRenderers(_, tmp, None))
      ts <- inProcess(fake, slug, booted)
    } yield ts

  /** [[fromWorkspace]] on a real port with the theme's assets. The entry must
    * set `access = c.access.public`: a Playwright page cannot be handed a
    * cookie first.
    */
  def servedWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, (TestServer, Uri)] =
    for {
      tmp <- stageWorkspace(slug, entrySource, entities)
      fake <- FakeHomeAssistant.create(entities).toResource
      client <- cdnClient
      booted <- assemble(
        fake,
        tmp,
        ServerApp.prepareRenderers(_, tmp, None),
        client
      )
      prepared = booted.assembled.prepared
      _ <- IO
        .fromOption(prepared.states.get(slug).flatMap(_.rendererOf))(
          RuntimeException(s"'$slug' did not build: ${prepared.states}")
        )
        .toResource
      bound <- bind(fake, slug, booted)
    } yield bound

  /** A workspace booted as it stands, whatever its `site.pkl` says, a broken
    * one included; the server's slug is the site's default.
    */
  def ofWorkspace(
      workspace: os.Path,
      entities: List[FixtureEntity] = Nil
  ): Resource[IO, (TestServer, ServerApp.Prepared)] =
    for {
      fake <- FakeHomeAssistant.create(entities).toResource
      booted <- assemble(
        fake,
        workspace,
        ServerApp.prepareRenderers(_, workspace, None)
      )
      slug <- booted.assembled.site.defaultSlug.toResource
      ts <- inProcess(fake, slug, booted)
    } yield (ts, booted.assembled.prepared)

  private def stageWorkspace(
      slug: String,
      entrySource: String,
      entities: List[FixtureEntity]
  ): Resource[IO, os.Path] =
    for {
      tmp <- IO.blocking(os.temp.dir(prefix = "fh-workspace")).toResource
      _ <- IO.blocking {
        // `prepareDumps` re-seeds an identical dump, a no-op pin move.
        val dumpJson = Json.obj(
          "areas" -> Json.obj(),
          "floors" -> Json.obj(),
          "entities" -> Json.fromFields(entities.map(_.toDumpEntry))
        )
        val _ = PklWorkspace.bootstrap(tmp, PklDump.render(dumpJson))
        val module = s"$slug-entry.pkl"
        os.write.over(tmp / module, entrySource)
        os.write.over(
          tmp / Site.EntryFile,
          s"""amends "@fh-dashboard/site.pkl"
             |
             |dashboards {
             |  ["$slug"] = import("$module")
             |}
             |""".stripMargin
        )
      }.toResource
    } yield tmp

  /** [[resource]] with the real CDN behind the asset cache and an ember bind on
    * a loopback port, for the Playwright suites. State is still driven through
    * `fake.emit`.
    */
  def served(
      dashboard: Dashboard,
      entities: List[FixtureEntity],
      config: FakeConfig = FakeConfig()
  ): Resource[IO, (TestServer, Uri)] =
    for {
      validated <- IO
        .fromEither(
          dashboard
            .validated()
            .leftMap(errs => IllegalArgumentException(errs.mkString("; ")))
        )
        .toResource
      fake <- FakeHomeAssistant.create(entities, config).toResource
      dir <- tempDir("fh-workspace")
      client <- cdnClient
      // Public: a Playwright session cannot be handed a cookie before its first
      // navigation, and driving OAuth against a fake HA would test the fake.
      // The gate still runs, the wall-tablet case.
      booted <- assemble(
        fake,
        dir,
        startingWith(validated.withAccess(Access.Public)),
        client
      )
      bound <- bind(fake, dashboard.slug, booted)
    } yield bound

  private final case class Booted(
      assembled: ServerApp.Assembled,
      haUp: SignallingRef[IO, Boolean],
      watcher: FakeWatcher
  )

  private def assemble(
      fake: FakeHomeAssistant,
      workspace: os.Path,
      prepare: HaFeed => IO[ServerApp.Prepared],
      assetsClient: Client[IO] = NoCdn,
      windows: Server.SessionWindows = Server.SessionWindows.default
  ): Resource[IO, Booted] =
    for {
      assetsDir <- tempDir("fh-assets")
      haUp <- SignallingRef[IO].of(true).toResource
      watcher <- FakeWatcher.create.toResource
      assembled <- ServerApp.assemble(
        ServerApp.Edges(
          workspace = workspace,
          haConnect = fakeConnect(fake, haUp),
          login = _ => IO.pure(fakeLogin(fake)),
          assetsClient = assetsClient,
          assetsDir = assetsDir,
          prepare = prepare,
          trustedProxy = None,
          pklLspJar = None,
          watchRegistry = false,
          otel = Telemetry.Otel.noop,
          loggerFactory = Logging.console,
          meters = Meters.noop,
          sourceWatcher = Resource.pure(watcher),
          sessionWindows = windows
        )
      )
    } yield Booted(assembled, haUp, watcher)

  private def inProcess(
      fake: FakeHomeAssistant,
      slug: String,
      booted: Booted
  ): Resource[IO, TestServer] = {
    val assembled = booted.assembled
    for {
      wsb <- WebSocketBuilder2[IO].toResource
      auth <- TestAuth.admitted(assembled.authSessions).toResource
      // Acquired after `assemble`, so the connect fibers are gone before the
      // server releases.
      supervisor <- Supervisor[IO]
      clients <- Ref[IO].of(List.empty[LiveClient]).toResource
    } yield new TestServer(
      fake,
      assembled.feed.store,
      assembled.server,
      slug,
      auth,
      assembled.sessions,
      assembled.site,
      assembled.app(wsb),
      assembled.feed.healthy,
      booted.haUp,
      booted.watcher,
      supervisor,
      clients
    )
  }

  private def bind(
      fake: FakeHomeAssistant,
      slug: String,
      booted: Booted
  ): Resource[IO, (TestServer, Uri)] =
    for {
      ts <- inProcess(fake, slug, booted)
      bound <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpWebSocketApp(booted.assembled.app)
        .withShutdownTimeout(0.seconds)
        .build
    } yield (ts, bound.baseUri)

  private def startingWith(
      validated: Dashboard.Validated
  ): HaFeed => IO[ServerApp.Prepared] =
    _ =>
      IO.pure(
        ServerApp.Prepared(
          Map(validated.dashboard.slug -> Right(validated)),
          Some(validated.dashboard.slug),
          Set.empty
        )
      )

  private def tempDir(prefix: String): Resource[IO, os.Path] =
    IO.blocking(os.temp.dir(prefix = prefix)).toResource

  /** Up until [[TestServer.haDown]]; after it, a reconnect waits forever. */
  private def fakeConnect(
      fake: FakeHomeAssistant,
      up: SignallingRef[IO, Boolean]
  ): HaFeed.Connect =
    Resource
      .eval(up.waitUntil(identity))
      .as((fake, up.waitUntil(!_)))

  /** HA's token endpoint mints for any grant, and a user's connection is the
    * same fake, so a tap made as a user lands where one made as the instance
    * does.
    */
  private def fakeLogin(fake: FakeHomeAssistant): ServerApp.HaLogin =
    ServerApp.HaLogin(
      new HaOAuth(
        uri"http://ha.test",
        uri"http://ha.test",
        Client.fromHttpApp(HttpApp[IO] { req =>
          if req.uri.path.renderString.endsWith("/auth/token") then
            Ok(
              """{"access_token":"minted","refresh_token":"r2","expires_in":1800}"""
            )
          else NotFound()
        })
      ),
      _ => Resource.pure(HomeAssistantApi.fromWs(fake)),
      _ =>
        (domain, service, entityId, data) =>
          HomeAssistantApi
            .fromWs(fake)
            .callService(domain, service, entityId, data)
            .void
    )

  /** Every asset answers empty, so no in-process test reaches the network. */
  private val NoCdn: Client[IO] =
    Client.fromHttpApp(HttpApp.pure(Response[IO](Status.Ok)))

  private def cdnClient: Resource[IO, Client[IO]] =
    IO(java.net.http.HttpClient.newHttpClient())
      .map(JdkHttpClient[IO](_))
      .toResource
}
