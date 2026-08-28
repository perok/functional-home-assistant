package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.{Env, Mutex}
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.FHError
import fh.view.build.{
  AddonBootstrap,
  BundledLib,
  DashboardBuild,
  DumpRefresh,
  LibPackage,
  PklDump,
  RegistryDump,
  Site,
  SystemPkl
}
import com.comcast.ip4s.Ipv4Address
import fh.view.auth.{
  AuthGate,
  Ingress,
  IngressUsers,
  AuthRoutes,
  AuthSessions,
  HaOAuth,
  RefreshOutcome,
  SessionStore
}
import api.homeassistant.ws.domain.HaUser
import fh.view.model.Dashboard
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import org.http4s.ember.server.EmberServerBuilder
import fs2.io.file.{Watcher, Path}

/** Runtime phase entry point.
  *
  * Connects to Home Assistant, evaluates the workspace's ONE entrypoint
  * (`site.pkl`) **in memory** into a slug -> dashboard map (ADR 0021), seeds
  * live state, and serves them all with live Datastar updates. Run via
  * `fh-datastar-view/runMain fh.view.runtime.ServerApp` with `SERVER`/`SECRET`
  * set.
  */
object ServerApp extends IOApp {

  // All relative to the module directory (the forked `run` working dir).
  //
  // Last-resort fallback when `DASHBOARDS_DIR` is unset (build.sbt sets it for
  // `dashboardServe` — an absolute repo-root path; `run.sh` sets it on the
  // add-on). A local scratch dir, NOT the resources dir, so a dev run bootstraps
  // a real package-form workspace (its `.fh/` pins, seeded entries, dated
  // backups) without ever writing into the checked-in
  // `src/main/resources/dashboards`.
  private val defaultDashboardsDir = "dashboard-local-dev"

  // Persistent pkl package cache for a dev run: the cross-platform user data
  // dir (`~/.local/share/fh/…/pkl-cache` on Linux), shared with `BuildApp` and
  // the laptop `fh` via one appdirs helper. The add-on overrides it to its
  // persistent `/data/pkl-cache` via `FH_PKL_CACHE_DIR`.
  private def defaultCacheDir: String =
    AddonBootstrap.defaultCacheDir

  /** Everything the runtime reads from the environment, parsed once by
    * [[Config.load]] at the boundary so the rest of `run` is a pure wiring
    * table — no scattered `Env[IO].get`, no defaults re-stated per site.
    */
  private case class Config(
      // Workspace precedence: optional CLI arg > `DASHBOARDS_DIR` > default.
      dashboardsDir: os.Path,
      // Persistent pkl package cache (`FH_PKL_CACHE_DIR`).
      cacheDir: os.Path,
      // Local theme-asset cache (`FH_ASSETS_DIR`).
      assetsDir: os.Path,
      // Bind address: loopback by default so LAN exposure is opt-in (`HOST`).
      bindHost: Host,
      bindPort: Port,
      // The raw `PORT` string for the `.fh/machine.json` loopback URL — kept
      // verbatim so a non-numeric PORT reproduces the prior behavior exactly
      // (bindPort falls back to 8080, the URL echoes the raw value).
      loopbackPort: String,
      // `FH_WATCH_REGISTRY`: registry-driven dump refresh, on by default.
      watchRegistry: Boolean,
      // `PKL_LSP_JAR`: explicit pkl-lsp jar override (else cached/downloaded).
      pklLspJar: Option[String]
  )

  private object Config {
    def load(args: List[String]): IO[Config] =
      for {
        dashboardsDir <- args.headOption
          .map(p => IO.pure(os.Path(p, os.pwd)))
          .getOrElse(pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir))
        cacheDir <- pathFromEnv("FH_PKL_CACHE_DIR", defaultCacheDir)
        assetsDir <- pathFromEnv("FH_ASSETS_DIR", "assets-cache")
        bindHost <- Env[IO]
          .get("HOST")
          .map(host =>
            host
              .map(
                Host
                  .fromString(_)
                  .getOrElse(throw Exception(s"Could not parse $host"))
              )
              .getOrElse(host"127.0.0.1")
          )
        loopbackPort <- envOr("PORT", "8080")
        bindPort = loopbackPort.toIntOption
          .flatMap(Port.fromInt)
          .getOrElse(port"8080")
        watchRegistry <- Env[IO]
          .get("FH_WATCH_REGISTRY")
          .map(v => !v.map(_.trim.toLowerCase).exists(RegistryWatchOff))
        pklLspJar <- Env[IO].get("PKL_LSP_JAR")
      } yield Config(
        dashboardsDir,
        cacheDir,
        assetsDir,
        bindHost,
        bindPort,
        loopbackPort,
        watchRegistry,
        pklLspJar
      )
  }

  def run(args: List[String]): IO[ExitCode] =
    for {
      // All environment configuration, parsed ONCE at the boundary; nothing
      // downstream reads `Env[IO]` again (ADR-style: parse, don't validate).
      config <- Config.load(args)
      // The bundled `@fh-dashboard` artifacts this boot seeded — passed to the
      // first `prepareDumps` so it can pin the dump's lib dependency before any
      // `pins.json` exists (fresh workspace, first-boot ordering).
      bundledLib <- bootstrap(config)

      _ <- (for {
        // Resolve SERVER/SECRET ONCE, eagerly, so a missing credential crashes
        // boot immediately — rather than being swallowed by the feed's
        // background reconnect loop and mistaken for an unreachable HA (which
        // would only surface as the feed's seed timeout below).
        haEnv <- FHApi.resolveEnv.toResource
        // ONE Home Assistant connection for the whole runtime: the self-healing
        // feed. Its stable facade (`feed.api`) backs BOTH the live dashboard
        // (`call_service` + state) AND the startup/occasional REST work — dump
        // prep, dump refresh, registry watching — so there is no second,
        // unsupervised socket that silently dies on a drop. Acquiring it blocks
        // until its store has been filled, so it is ready to read here.
        feed <- HaFeed.resource(FHApi.lowLevelConnectWithClose(haEnv))
        dashboardsDir = config.dashboardsDir
        // Seed the dump and evaluate the entrypoint into every dashboard it
        // names — the source-to-renderer path shared with the test harness so
        // it can't diverge. `bundledLib` pins the FIRST dump on a fresh
        // workspace.
        prepared <- prepareRenderers(
          feed,
          dashboardsDir,
          Some(bundledLib)
        ).toResource
        built = prepared.built
        // Serves this home's `dump.pkl` and its resolved package artifacts over
        // the public `/system/pkl/*` route for external tooling — the `fh`
        // script, pkl-lsp, remote authors — that fetch for real (ADR 0010). The
        // server's OWN eval never imports over http: entries resolve
        // `@fh-dashboard`/`@fh-home` from the seeded cache packages, so this
        // provider backs ONLY the route, not evaluation. Reads are by-name off
        // the pinned package in the cache, reflecting the latest dump.
        systemPkl = SystemPkl.fromDisk(dashboardsDir)

        // Cache the themes' external assets (CSS/JS/fonts) locally so the
        // dashboard serves them itself — offline-friendly, CDN fallback on a
        // cold-cache fetch failure. Reuses the JDK http client idiom from
        // FHApi; URLs are collected from every built renderer's theme (a
        // live-reload that introduces NEW urls passes through until restart).
        httpClient <- IO(java.net.http.HttpClient.newHttpClient()).toResource
        assets <- AssetCache
          .build(
            config.assetsDir,
            Server.DatastarCdn :: built.flatMap { case (_, renderer) =>
              renderer.stylesheets ++ renderer.deferredStylesheets ++
                renderer.scripts
            },
            org.http4s.jdkhttpclient.JdkHttpClient[IO](httpClient)
          )
          .toResource

        // Every dashboard the entrypoint named is registered, built or not:
        // `built` become `Ready` renderers, `failed` become `Failed` states
        // serving their error page and rebuilding live on a fix.
        rendererRefs <- prepared.states.toList
          .traverse { case (slug, state) =>
            SignallingRef[IO].of(state).map(slug -> _)
          }
          .map(_.toMap)
          .toResource
        importsRef <- SignallingRef[IO]
          .of(watchedSet(dashboardsDir, prepared.imports))
          .toResource

        // The live site: what is served, what each slug was built from, and
        // which slug answers `/`. Built BEFORE the Server because the reload
        // path writes it and the routes read it.
        site <- Server.LiveSite
          .of(
            rendererRefs,
            prepared.content,
            defaultSlugFrom(prepared.default, prepared.states.keys.toList)
          )
          .toResource
        reload = reloadSite(dashboardsDir, site, importsRef)

        // Dump refresh (validate-then-swap, DumpRefresh): re-fetch the entity
        // dump and swap it in only if every currently-building dashboard still
        // builds; on success the renderers hot-swap like a source edit. The
        // mutex serializes the endpoint against the registry watcher.
        refreshMutex <- Mutex[IO].toResource
        refreshDump = refreshMutex.lock.surround(
          refreshOnce(feed.api, dashboardsDir, reload)
        )

        // Authentication (issue #89). Home Assistant is the identity provider:
        // this server is an ordinary OAuth client, and the machine token above
        // is untouched — every service call still runs as the machine, and
        // nothing here opens a per-user feed.
        //
        // The URL the BROWSER must reach HA at is not always the one this
        // server dials — under the add-on it is `http://supervisor/core`, which
        // resolves for this process and for nothing a browser runs in. So ask
        // HA where it thinks it lives; `HaOAuth.browserBase` ranks that against
        // the override and the dialled address.
        //
        // An unreachable or malformed answer is not fatal: it is one more
        // absent source, and the chain has three others.
        haInternalUrl <- feed.api.getConfigWS.attempt
          .map(_.toOption.flatMap(HaOAuth.internalUrlOf))
          .toResource
        haPublicUrl <- Env[IO]
          .get("FH_HA_PUBLIC_URL")
          .map(_.flatMap(org.http4s.Uri.fromString(_).toOption))
          .map(HaOAuth.browserBase(_, haInternalUrl, haEnv.server))
          .toResource
        // ...and the address the LOGIN dials is a third answer, not `SERVER`:
        // under the add-on that is the supervisor proxy, which serves the feed
        // and nothing a per-USER credential can use — HA's `/auth/…` is not
        // behind it at all, and its websocket authenticates add-ons, not
        // people. `HaOAuth.coreBase` has the ranking.
        haCoreUrl <- Env[IO]
          .get("FH_HA_TOKEN_URL")
          .map(_.flatMap(org.http4s.Uri.fromString(_).toOption))
          .map(HaOAuth.coreBase(_, haInternalUrl, haEnv.server))
          .toResource
        _ <- IO
          .println(
            s"Home Assistant login redirects go to $haPublicUrl; " +
              s"logins dial $haCoreUrl"
          )
          .toResource
        authSessions <- AuthSessions
          .create(SessionStore.inWorkspace(dashboardsDir))
          .toResource
        oauth = new HaOAuth(
          haPublicUrl,
          haCoreUrl,
          org.http4s.jdkhttpclient.JdkHttpClient[IO](httpClient)
        )
        // The ONE use of somebody else's token: a short-lived connection opened
        // with it, asked who it belongs to, then closed. Deliberately NOT the
        // shared feed, which stays on the machine token — and for the same
        // reason not the feed's ADDRESS either: the supervisor proxy takes only
        // the add-on's own token (see `HaOAuth.coreWs`).
        identify = (token: String) =>
          FHApi
            .from(
              haCoreUrl,
              token,
              HaOAuth.coreWs(haCoreUrl, haEnv.server, haEnv.serverWs)
            )
            .use(_.currentUser)
            .handleErrorWith(e =>
              FHError
                .unavailable(
                  s"could not ask Home Assistant who this login belongs to: ${e.getMessage}"
                )
                .raiseError[IO, HaUser]
            )
        // Built from the SITE, not from the server: the server routes with it,
        // so it has to exist first. `LiveSite` owns the registry the rule is
        // read from, which is where `permissionFor` lives.
        // Under the add-on's ingress, HA has already authenticated the user
        // and the Supervisor forwards who they are — so nobody logs in twice.
        // The headers carry no ROLE, so the id is resolved against HA's own
        // account list; `Ingress` explains why the SOURCE ADDRESS is what makes
        // the headers trustworthy, and why the port cannot be.
        //
        // `FH_TRUSTED_PROXY` overrides the Supervisor's fixed address for a
        // deployment that proxies differently; unset is the add-on default, and
        // an explicit empty value turns ingress trust off entirely.
        trustedProxy <- Env[IO]
          .get("FH_TRUSTED_PROXY")
          .map {
            case None      => Some(Ingress.SupervisorIp)
            case Some(raw) => Ipv4Address.fromString(raw.trim)
          }
          .toResource
        ingressUsers <- IngressUsers.cached(feed.api.configAuthList).toResource
        gate = new AuthGate(
          authSessions,
          identify,
          site.permissionFor,
          ingressUsers,
          trustedProxy
        )
        authRoutes <- AuthRoutes
          .create(oauth, authSessions, identify, Server.baseUriOf)
          .toResource
        // The live Server, assembled through the SHARED kernel `liveServer` (the
        // same one the test harness funnels through, so the wiring can't drift).
        // Also runs the per-slug shared patch publishers in the background — the
        // render-once fan-out every SSE connection subscribes to.
        server <- liveServer(
          feed,
          site,
          gate,
          assets,
          systemPkl,
          dumpRefresh = Some(refreshDump)
        )
        // The editor surface (/edit + /lsp/pkl). The pkl-lsp jar backs the LSP
        // subprocess; None just disables completion/diagnostics (the editor and
        // local highlighting still work).
        pklLspJar <- resolvePklLspJar(httpClient, config.pklLspJar).toResource
        editor = new EditorRoutes(
          dashboardsDir,
          gate,
          pklLspJar,
          site.defaultSlug,
          site.names
        )

        _ <- watchSources(reload, importsRef).compile.drain.background

        // Registry-driven dump refresh: HA's `*_registry_updated` events say
        // the HOME changed (device/entity/area/floor added, renamed, removed)
        // — exactly what the dump snapshots. Toggleable via the add-on's
        // `watch_registry` option (FH_WATCH_REGISTRY); on by default.
        _ <-
          if (config.watchRegistry)
            watchRegistryEvents(
              feed.api,
              feed.healthy,
              refreshDump
            ).compile.drain.background.void
          else Resource.unit[IO]
        // One fiber over the whole store rather than one per session: re-check
        // what is stale, evict what HA says is gone. This is what makes a
        // revocation in HA reach a dashboard nobody is touching.
        _ <- revalidateSessions(
          authSessions,
          oauth,
          identify
        ).compile.drain.background
        _ <- EmberServerBuilder
          .default[IO]
          .withHost(config.bindHost)
          .withPort(config.bindPort)
          .withHttpWebSocketApp(wsb =>
            // Any FHError raised while serving becomes its status + message;
            // anything else falls through to Ember's default 500.
            //
            // Each route (or route GROUP — see `AuthGate.require`) declares
            // its own requirement. Inside the error boundary, so a raised
            // FHError still becomes a status.
            FHError.handle(
              (authRoutes.routes <+> server.routes <+> editor.routes(
                wsb
              )).orNotFound
            )
          )
          .withShutdownTimeout(0.seconds)
          .build
        defaultSlug <- site.defaultSlug.toResource
        _ <- IO
          .println(
            s"Dashboards serving on http://${config.bindHost}:${config.bindPort} " +
              s"(default '/$defaultSlug', all: ${prepared.states.keys.toList.sorted.mkString(", ")})"
          )
          .toResource
      } yield ()).useForever
    } yield ExitCode.Success

  /** Everything [[prepareRenderers]] hands back: the state to register per slug
    * (`Ready` or `Failed`), the slug the site wants at `/`, and the files the
    * evaluation read (for watching). A dashboard that failed to build is
    * REGISTERED as a failed one — serving its error page and rebuilding live on
    * a fix — rather than skipped, so no slug the entrypoint names is ever
    * silently absent from the server.
    */
  private[runtime] case class Prepared(
      dashboards: Map[String, Either[String, Dashboard.Validated]],
      default: Option[String],
      imports: Set[os.Path]
  ) {

    /** Lazy because building a renderer per slug is real work, and a caller
      * that only wants the failures (or the membership) should not pay it.
      */
    lazy val states: Map[String, Server.RendererState] =
      dashboards.map { case (slug, result) => slug -> stateOf(result) }

    /** What each slug currently EVALUATES to — the proven dashboard, or the
      * message it failed with. This is what a later reload compares against to
      * decide whether anything actually changed ([[reloadSite]]), so it holds
      * the model rather than the renderer built from it: two evaluations of an
      * unedited file produce equal `Dashboard`s and different `Renderer`s.
      */
    def content: Map[String, Either[String, Dashboard]] =
      dashboards.view.mapValues(_.map(_.dashboard)).toMap

    def built: List[(String, Renderer)] = states.toList.collect {
      case (slug, Server.RendererState.Ready(r)) => slug -> r
    }
    def failed: List[(String, String)] = states.toList.collect {
      case (slug, Server.RendererState.Failed(m)) => slug -> m
    }
  }

  private def stateOf(
      result: Either[String, Dashboard.Validated]
  ): Server.RendererState = Server.stateOf(result)

  /** Dump and build every dashboard the entrypoint names — the source-to-
    * renderer path that precedes serving, extracted so [[run]] (production) and
    * the test harness (`TestServer.fromWorkspace`) share it and cannot diverge.
    *
    * Blocks on the feed's first connect + seed (so a live template call has a
    * connection), seeds the `@fh-home` dump ONCE from the live API
    * ([[DashboardBuild.prepareDumps]]), then evaluates `site.pkl` against it.
    * Nothing here is fatal: a dashboard that fails to decode or validate
    * becomes a registered `Failed` one, and an entrypoint that will not
    * evaluate at all registers a single failed dashboard under
    * [[Server.DefaultSlug]] — so `/` and the editor still serve the error and
    * the fix path, exactly the shape the reload machinery uses after startup.
    */
  private[runtime] def prepareRenderers(
      feed: HaFeed,
      dashboardsDir: os.Path,
      bundledLib: Option[LibPackage.Artifacts]
  ): IO[Prepared] =
    // Write the live dump once (so `import "@fh-home/dump.pkl"` resolves) via
    // the build phase, which owns fetching + packaging the dump.
    DashboardBuild.prepareDumps(feed.api, dashboardsDir, bundledLib) *>
      DashboardBuild
        .evalSite(dashboardsDir)
        .attempt
        .flatMap {
          case Right((site, imports)) =>
            IO.pure(Prepared(site.dashboards.toMap, site.default, imports))
          case Left(err) =>
            // Nothing evaluated, so nothing can be attributed to a slug: serve
            // the error under the one name `/` will look for.
            IO.pure(
              Prepared(
                Map(Server.DefaultSlug -> Left(Site.messageOf(err))),
                None,
                Set(dashboardsDir / Site.EntryFile)
              )
            )
        }
        .flatTap { prepared =>
          prepared.built.traverse_ { case (slug, renderer) =>
            // Built, but maybe not sound: report what still serves and only
            // misbehaves (a popup with nowhere to go).
            renderer.warnings.traverse_(w => IO.println(s"[warn] '$slug': $w"))
          } *>
            prepared.failed.traverse_ { case (slug, message) =>
              IO.println(s"Dashboard '$slug' failed to build: $message")
            }
        }

  /** How long a session may go unchecked before HA is asked about it again.
    * Matches HA's own access-token life, so a revocation is visible within one
    * token generation rather than at some unrelated interval.
    */
  private val RevalidateAfter: FiniteDuration = 30.minutes

  /** Ask HA, periodically, whether each logged-in user is still who and what we
    * recorded — and drop the ones it no longer vouches for.
    *
    * The only thing that makes Home Assistant AUTHORITATIVE rather than merely
    * the thing that once issued a login: without it, revoking this dashboard in
    * HA would leave its sessions alive here until they aged out.
    *
    * A `Dead` answer evicts. A raised error does NOT: an unreachable HA is not
    * a statement about anybody's account, and treating it as one would log the
    * whole household out of a working dashboard every time the network hiccups.
    *
    * The FIRST pass runs immediately rather than one `every` later, which is
    * what covers a restart: `verifiedAt` is persisted and absolute, so every
    * session that survived downtime is already past `after` on boot and needs
    * no special case — but `awakeEvery` sleeps before its first element, so
    * without the leading pass a session revoked during a week of downtime would
    * still be served for the first five minutes.
    */
  private[runtime] def revalidateSessions(
      sessions: AuthSessions,
      oauth: HaOAuth,
      identify: String => IO[HaUser],
      every: FiniteDuration = 5.minutes,
      after: FiniteDuration = RevalidateAfter
  ): Stream[IO, Unit] = {
    val pass = revalidateOnce(sessions, oauth, identify, after)
    Stream.eval(pass) ++ Stream.awakeEvery[IO](every).evalMap(_ => pass)
  }

  /** One sweep: re-check everything currently past `after`, and evict what HA
    * disowns. Separate from the schedule above so a test can run exactly one
    * and assert on the result, with no clock in the assertion.
    */
  private[runtime] def revalidateOnce(
      sessions: AuthSessions,
      oauth: HaOAuth,
      identify: String => IO[HaUser],
      after: FiniteDuration = RevalidateAfter
  ): IO[Unit] =
    IO.realTimeInstant
      .map(_.minusSeconds(after.toSeconds))
      .flatMap(sessions.stale)
      .flatMap(_.traverse_ { case (id, session) =>
        // The client_id HA stored at login is the only one it accepts back,
        // so the session carries its own rather than the caller guessing one.
        oauth
          .refresh(
            session.refresh,
            org.http4s.Uri.unsafeFromString(session.clientId)
          )
          .flatMap {
            case RefreshOutcome.Dead =>
              IO.consoleForIO.println(
                s"[auth] session for ${session.user.name} is no longer valid at HA; signing out"
              ) *> sessions.remove(id)
            case RefreshOutcome.Renewed(tokens) =>
              // Re-read the user too, not just the token: a role change is
              // exactly the thing a periodic check is here to notice.
              identify(tokens.accessToken).flatMap(user =>
                sessions.renew(
                  id,
                  user,
                  tokens.refreshToken.getOrElse(session.refresh)
                )
              )
          }
          .handleErrorWith(e =>
            IO.consoleForIO.errorln(
              s"[auth] could not re-check a session (leaving it alone): ${e.getMessage}"
            )
          )
      })

  /** The runtime KERNEL both [[run]] (production) and the test harness
    * (`TestServer`) funnel through, so the live-Server wiring cannot silently
    * diverge: wait for the feed's first connect + seed (so the store is
    * populated before anything serves), then build the Server from it via the
    * single [[Server.fromFeed]] constructor.
    *
    * What stays with the caller is deliberate, not drift: the renderer SOURCE
    * (production evaluates Pkl against the live dump; a test uses a fixed
    * `Dashboard`) and the serving SHELL (asset cache, editor + Ember in
    * production; in-memory routes or a bare Ember bind in tests). The feed
    * itself is built by the caller because production needs `feed.api` to
    * prepare the dump BEFORE any renderer exists — everything that makes a
    * Server a Server lives here.
    */
  private[runtime] def liveServer(
      feed: HaFeed,
      site: Server.LiveSite,
      gate: AuthGate,
      assets: AssetCache = AssetCache.empty,
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None
  ): Resource[IO, Server] =
    for {
      sessions <- Sessions.create.toResource
      server <- Server.fromFeed(
        feed,
        site,
        sessions,
        gate,
        assets,
        systemPkl,
        dumpRefresh
      )
    } yield server

  /** The slug the site is seeded with at boot ([[Server.defaultSlugFor]], with
    * the never-empty fallback a `LiveSite` needs). The site's own `default`
    * wins whenever it names a dashboard the entrypoint declared, even one that
    * failed to build — its error page is the point.
    */
  private[runtime] def defaultSlugFrom(
      preferred: Option[String],
      all: List[String]
  ): String =
    Server.defaultSlugFor(preferred, all).getOrElse(Server.DefaultSlug)

  /** The set of files to watch: the entrypoint's transitive imports plus the
    * entrypoint itself (so a brand-new import or a top-level edit is caught).
    *
    * The `lib/` authoring library and the `@fh-home` dump are cache-backed
    * PACKAGES (ADR 0010), so `PklBuild.importSet` filters their `package:`
    * imports out of the `file:` watch set — they are immutable per version and
    * not hot-reloaded (a lib edit needs a restart / re-seed; a dump change goes
    * through `DumpRefresh`). So the watched files are entries + their loose
    * `file:` imports only.
    */
  private def watchedSet(
      dashboardsDir: os.Path,
      imports: Set[os.Path]
  ): Set[Path] =
    (imports +
      (dashboardsDir / Site.EntryFile) +
      // The workspace manifest, so adding a package dependency takes effect
      // like any other edit: the re-eval re-resolves the lockfile whenever this
      // file's mtime has moved (`PklBuild.staleLockfile`). It is editable in the
      // editor, so this is a real edit path, not a hypothetical one.
      (dashboardsDir / EditorRoutes.Manifest) +
      // The workspace DIRECTORY, so a file that APPEARS is noticed. Watching
      // paths alone cannot see one: a new file is nobody's import yet, and a
      // glob import (`import*("*.dashboard.pkl")`) exists precisely so that
      // dropping a file in adds a dashboard. Events are filtered to `*.pkl`
      // ([[watchSourcesWith]]) — the directory also holds the lockfile the
      // evaluation itself rewrites, and reloading on that would feed itself.
      dashboardsDir)
      .map(fs2Path)

  /** Which watcher events are an author edit. Since the workspace DIRECTORY is
    * watched (so a new file is seen), the noise it carries has to be dropped
    * here: `PklProject.deps.json` is rewritten by the evaluation this reload
    * runs, so reloading on it would feed itself, and `.fh/` churns per boot.
    * Overflow has no path and must pass — it means events were LOST, which is
    * exactly when a reload is owed.
    */
  private[runtime] def isSourceEvent(event: Watcher.Event): Boolean =
    event match {
      case Watcher.Event.Overflow(_)       => true
      case Watcher.Event.NonStandard(_, _) => false
      case _                               =>
        val name = eventPath(event).fold("")(_.fileName.toString)
        name.endsWith(".pkl") || name == EditorRoutes.Manifest
    }

  private def eventPath(event: Watcher.Event): Option[Path] = event match {
    case Watcher.Event.Created(p, _)  => Some(p)
    case Watcher.Event.Modified(p, _) => Some(p)
    case Watcher.Event.Deleted(p, _)  => Some(p)
    case _                            => None
  }

  private val watchedEvents = List(
    Watcher.EventType.Created,
    Watcher.EventType.Modified,
    Watcher.EventType.Deleted
  )

  /** Watch the entrypoint's source graph and, on change, re-evaluate it and
    * hot-swap every dashboard it names; the SSE streams repaint their body.
    * MEMBERSHIP moves too — a key added to `dashboards` starts serving and a
    * key removed stops, without a restart (ADR 0021) — and each surviving
    * slug's state is set either way, so a dashboard broken since startup
    * repairs when it is fixed. A concurrent reconcile tracks `importsRef` so
    * newly-imported files start being watched and removed ones stop.
    */
  private def watchSources(
      reload: IO[Unit],
      importsRef: SignallingRef[IO, Set[Path]]
  ): Stream[IO, Unit] =
    Stream.resource(Watcher.default[IO]).flatMap { watcher =>
      watchSourcesWith(
        watcher.events(),
        path => watcher.watch(path, watchedEvents),
        reload,
        importsRef
      )
    }

  /** The source watcher's pipeline, decoupled from the OS watcher: the caller
    * supplies the event stream and the watch/unwatch side effect, so a test can
    * drive the same `events -> reload` wiring with a controlled stream instead
    * of a live `WatchService`. The `WatchService` itself is only exercised
    * manually (`sbt dashboardServe`).
    */
  private[runtime] def watchSourcesWith(
      events: Stream[IO, Watcher.Event],
      watch: Path => IO[IO[Unit]],
      reload: IO[Unit],
      importsRef: SignallingRef[IO, Set[Path]]
  ): Stream[IO, Unit] = {
    val reconcile =
      Stream
        .eval(cats.effect.kernel.Ref[IO].of(Map.empty[Path, IO[Unit]]))
        .flatMap { active =>
          importsRef.discrete.evalMap { imports =>
            active.get.flatMap { current =>
              val toAdd = imports -- current.keySet
              val toCancel = current.keySet -- imports
              for {
                added <- toAdd.toList
                  .traverse(p => watch(p).tupleLeft(p))
                _ <- toCancel.toList
                  .traverse_(p => current.getOrElse(p, IO.unit))
                _ <- active.set((current ++ added) -- toCancel)
              } yield ()
            }
          }
        }

    val reloadOnChange =
      events
        .filter(isSourceEvent)
        .debounce(200.millis)
        .evalMap(_ => reload)

    reloadOnChange.concurrently(reconcile)
  }

  /** Re-evaluate the entrypoint against the on-disk sources + dump and bring
    * the live site up to what it now says: install the dashboards it names
    * (`Ready`, or `Failed` with the message its own build failed with) and
    * remove the ones it no longer names. The body behind both the source
    * watcher and the post-dump-swap reload ([[refreshOnce]] — the dump is
    * deliberately not watched).
    *
    * An entrypoint that will not EVALUATE is a different thing from a dashboard
    * that will not build: nothing can be attributed to a slug, so every slug
    * the site currently owns shows the error and MEMBERSHIP IS LEFT ALONE — the
    * file no longer says what it is. Fixing the file restores all of them at
    * once.
    *
    * What the site owned last time is not tracked here: the registry records it
    * per slug ([[Server.Origin]]), so nothing this function does can drift from
    * what is actually installed. That record does two jobs. It makes removal
    * safe — a slug installed by `push` is not the entrypoint's, so nothing here
    * can reclaim one (ADR 0010) — and it makes a reload that changes nothing
    * cost nothing.
    *
    * '''An unchanged dashboard is not re-installed.''' The watcher fires on
    * anything in the workspace, and a `Ready` state written to the registry is
    * not free: it emits on the slug's `SignallingRef`, which rotates the
    * fragment log and repaints EVERY open browser on that dashboard. So a save
    * that did not change a dashboard — a touched file, a comment, an edit to a
    * sibling — would still throw every viewer's DOM away.
    */
  private[runtime] def reloadSite(
      dashboardsDir: os.Path,
      site: Server.LiveSite,
      importsRef: SignallingRef[IO, Set[Path]]
  ): IO[Unit] =
    DashboardBuild.evalSite(dashboardsDir).attempt.flatMap {
      case Left(err) =>
        // Membership is NOT touched: the file no longer says what it is.
        site.failSite(Site.messageOf(err)).flatMap(report)
      case Right((decoded, imports)) =>
        for {
          changes <- site.applySite(decoded.dashboards, decoded.default)
          _ <- report(changes)
          _ <- importsRef.set(watchedSet(dashboardsDir, imports))
          reloaded = changes.collect {
            case c @ (_: Server.Change.Added | _: Server.Change.Broke |
                _: Server.Change.Recovered | _: Server.Change.Rebuilt) =>
              c.describe._1
          }
          _ <- IO.whenA(changes.nonEmpty)(
            IO.println(s"Dashboards reloaded (${reloaded.mkString(", ")})")
          )
        } yield ()
    }

  /** Announce the TRANSITIONS worth a log line: a dashboard that started
    * serving, one that broke, one that recovered, one that is gone.
    */
  private def report(changes: List[Server.Change]): IO[Unit] =
    changes.traverse_(_.describe._2.traverse_(IO.println))

  /** One full dump refresh: fetch + render the live dump, validate-then-swap
    * ([[DumpRefresh.refresh]]), and on a swap hot-reload every renderer (the
    * source watcher does not watch the dump). A rejection only warns — HA
    * changed, but the dashboards keep building against the current dump until
    * they're fixed and a refresh is retried.
    */
  private def refreshOnce(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      reload: IO[Unit]
  ): IO[DumpRefresh.Result] =
    RegistryDump
      .fetch(api)
      .flatTap(
        PklDump.warnings(_).traverse_(w => IO.println(s"dump warning: $w"))
      )
      .map(PklDump.render)
      .flatMap(DumpRefresh.refresh(_, dashboardsDir))
      .flatTap {
        case DumpRefresh.Unchanged =>
          IO.println("dump refresh: home unchanged")
        case DumpRefresh.Swapped(version, seedLog) =>
          seedLog.traverse_(IO.println) *>
            IO.println(s"dump refreshed -> $version") *> reload
        case DumpRefresh.Rejected(errors) =>
          IO.println(
            "WARNING: dump refresh rejected — the new dump breaks dashboards " +
              "that build today; keeping the current dump:"
          ) *>
            errors.traverse_ { case (slug, err) =>
              IO.println(s"  '$slug': $err")
            }
      }

  /** The HA event types that signal the dump's inputs changed: the registries
    * behind `{entities, areas, floors}` (devices ride along because a device
    * add/remove always touches the entity registry too, but a rename can fire
    * only the device event), plus `component_loaded` — an integration set up at
    * runtime, which also covers YAML-defined entities that never get a registry
    * entry (no `unique_id`) and so fire no registry event. The startup burst of
    * `component_loaded`s is absorbed by the debounce + the refresh being a
    * no-op on a byte-identical dump.
    */
  private val DumpEvents = List(
    "entity_registry_updated",
    "device_registry_updated",
    "area_registry_updated",
    "floor_registry_updated",
    "component_loaded"
  )

  /** `FH_WATCH_REGISTRY` values that turn the registry watcher off. */
  private val RegistryWatchOff = Set("false", "0", "off", "no")

  /** Registry changes come in bursts (adding one integration fires dozens of
    * `entity_registry_updated` events), so wait for quiet before refreshing. A
    * failed refresh (an HA hiccup mid-fetch) logs and keeps listening.
    *
    * SPANS RECONNECTS by re-subscribing, rather than by holding a subscription
    * that survives one: each connection gets a fresh subscription, whose stream
    * ends with that connection, and `healthy` going true again starts the next.
    *
    * NOTHING IS LOST IN THE GAP, and not by luck. A subscription is only ever
    * interrupted by a disconnect (that is the only thing that moves `healthy`),
    * and every reconnect refreshes unconditionally — so a registry event
    * dropped during the outage, or in flight when the socket died, is
    * re-derived rather than replayed. Re-deriving after the gap is both simpler
    * and stricter than buffering across it: it cannot miss a change we never
    * saw an event for. The state feed closes its own gap the same way, with the
    * new subscription's opening full set.
    *
    * The refresh costs one dump render per reconnect and is otherwise a no-op,
    * since an unchanged home has an unchanged content-version.
    *
    * A registry change means an entity/area/floor appeared, vanished or was
    * renamed, which changes what the dashboards are BUILT from — so the answer
    * is a full re-evaluation of every entry, not an incremental patch. That is
    * deliberate: it happens a few times a year, and making it cheaper would buy
    * nothing for a cost in machinery.
    */
  private def watchRegistryEvents(
      api: HomeAssistantApi[IO],
      healthy: Signal[IO, Boolean],
      refresh: IO[DumpRefresh.Result]
  ): Stream[IO, Unit] = {
    val runRefresh = refresh.attempt.flatMap {
      case Left(err) =>
        IO.println(s"registry-driven dump refresh failed: ${err.getMessage}")
      case Right(_) => IO.unit
    }

    healthy.discrete
      .filter(identity)
      .switchMap { _ =>
        val events = Stream
          .emits(DumpEvents)
          .map(t => Stream.resource(api.rawEvents(t)).flatten)
          .parJoinUnbounded
          .debounce(RegistryQuiet)
          .evalMap(_ => runRefresh)
        // Catch up on whatever the outage hid, then watch this connection.
        Stream.exec(runRefresh) ++ events
      }
  }

  private val RegistryQuiet = 5.seconds

  private def fs2Path(p: os.Path): Path = Path.fromNioPath(p.toNIO)

  /** Bring the workspace to a package-form state the server can evaluate, on
    * EVERY start — add-on or local dev — so the two never diverge (ADR 0010).
    * [[AddonBootstrap]] packages the bundled library into the persistent cache
    * and seeds/migrates the user's workspace (its `.fh/base.pkl` + `pins.json`,
    * starter entries), so `@fh-dashboard` always resolves from the cache as
    * `package://fh.invalid/fh-dashboard@<v>` and the live home always serves
    * `/system/pkl/packages` (what `fh init`/`pull`/`push` read).
    *
    * The two path inputs come from `run.sh` on the add-on; a local `sbt
    * dashboardServe` has neither set and falls back to a local scratch dir —
    * the lib AND the starter entry are both read straight off the running jar's
    * own resources ([[BundledLib]], [[AddonBootstrap.starterSite]]), so a dev
    * run seeds exactly what the add-on does; iterating on library Pkl is
    * `fh push` against the running instance, never a mutable workspace `lib/`.
    */
  private def bootstrap(config: Config): IO[LibPackage.Artifacts] =
    for {
      // The lib is the running jar's own resources — nothing to locate on disk.
      bundled <- IO.blocking(BundledLib.artifacts())
      _ <- IO
        .blocking(
          AddonBootstrap
            .run(
              config.dashboardsDir,
              bundled,
              config.cacheDir,
              // This instance's own URL, written into `.fh/machine.json` as the
              // `http.rewrites` target. Loopback + the bind PORT: inert here
              // (packages resolve from the cache), it only matters if the
              // workspace is copied — a laptop's `fh init` overwrites it with
              // the real instance URL.
              loopbackUrl = s"http://127.0.0.1:${config.loopbackPort}"
            )
        )
        .flatMap(_.traverse_(IO.println))
    } yield bundled

  /** Read `name` from the environment, falling back to `default` when unset.
    * The single place the `Env[IO].get(...).getOrElse(...)` idiom lives.
    */
  private def envOr(name: String, default: String): IO[String] =
    Env[IO].get(name).map(_.getOrElse(default))

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    envOr(name, default).map(s => os.Path(s, os.pwd))

  private val PklLspVersion = "0.8.0"
  private val PklLspUrl =
    s"https://repo1.maven.org/maven2/org/pkl-lang/pkl-lsp/$PklLspVersion/" +
      s"pkl-lsp-$PklLspVersion.jar"

  /** Locate the pkl-lsp jar the LSP subprocess runs: `PKL_LSP_JAR` if set, else
    * a cached copy under `.pkl-lsp/`, else download it from Maven Central once
    * (the shaded CLI jar, run as `java -jar`). Returns `None` — LSP degraded,
    * editor + local highlighting still work — if it can't be obtained.
    */
  private def resolvePklLspJar(
      client: java.net.http.HttpClient,
      jarOverride: Option[String]
  ): IO[Option[os.Path]] =
    jarOverride match {
      case Some(p) =>
        val path = os.Path(p, os.pwd)
        IO.blocking(os.exists(path)).flatMap {
          case true  => IO.pure(Some(path))
          case false =>
            IO.println(s"pkl-lsp: PKL_LSP_JAR=$p does not exist").as(None)
        }
      case None =>
        val cache = os.pwd / ".pkl-lsp" / s"pkl-lsp-$PklLspVersion.jar"
        IO.blocking(os.exists(cache)).flatMap {
          case true  => IO.pure(Some(cache))
          case false =>
            downloadPklLsp(client, cache).attempt.flatMap {
              case Right(_)  => IO.pure(Some(cache))
              case Left(err) =>
                IO.println(
                  s"pkl-lsp: could not obtain jar (${err.getMessage}); " +
                    "LSP features disabled"
                ).as(None)
            }
        }
    }

  /** Download the pkl-lsp jar to `dest` via the JDK http client (write to a
    * `.part` sibling, then move — never leave a truncated jar).
    */
  private def downloadPklLsp(
      client: java.net.http.HttpClient,
      dest: os.Path
  ): IO[Unit] =
    IO.println(s"pkl-lsp: downloading $PklLspUrl") *>
      IO.blocking {
        os.makeDir.all(dest / os.up)
        val tmp = dest / os.up / (dest.last + ".part")
        val req = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(PklLspUrl))
          .build()
        val resp = client.send(
          req,
          java.net.http.HttpResponse.BodyHandlers.ofFile(tmp.toNIO)
        )
        if (resp.statusCode() != 200) {
          os.remove.all(tmp)
          throw new RuntimeException(s"HTTP ${resp.statusCode()}")
        }
        os.move.over(tmp, dest)
      } *> IO.println(s"pkl-lsp: cached at $dest")
}
