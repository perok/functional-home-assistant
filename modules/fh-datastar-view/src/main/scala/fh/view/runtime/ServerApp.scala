package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.{Env, Mutex}
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.FHError
import fh.view.telemetry.{Logging, Meters, Telemetry}
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
  HaAccess,
  HaOAuth,
  RefreshOutcome,
  SessionStore
}
import api.homeassistant.ws.domain.HaUser
import fh.view.model.Dashboard
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import cats.effect.MonadCancelThrow
import org.http4s.client.Client
import org.http4s.client.middleware.Metrics as ClientMetrics
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.client.{
  ClientMiddleware,
  ClientSpanDataProvider,
  UriRedactor
}
import org.http4s.{Query, Status}
import org.http4s.otel4s.middleware.trace.redact.{PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{
  ServerMiddleware,
  ServerSpanDataProvider
}
import org.http4s.server.middleware.Metrics as ServerMetrics
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import fs2.io.file.{Watcher, Path}

/** The runtime phase: evaluates the workspace's `site.pkl` in memory into a
  * slug -> dashboard map (ADR 0021) and serves them live.
  */
object ServerApp extends IOApp {

  private val LoggerName = "fh.view.runtime.ServerApp"

  // For helpers nobody handed a logger, which is tests only.
  private val consoleLog: SelfAwareStructuredLogger[IO] =
    Logging.console.getLoggerFromName(LoggerName)

  private case class Config(
      dashboardsDir: os.Path,
      cacheDir: os.Path,
      assetsDir: os.Path,
      // Loopback by default, so LAN exposure is opt-in.
      bindHost: Host,
      bindPort: Port,
      watchRegistry: Boolean,
      pklLspJar: Option[String]
  )

  private object Config {
    def load(args: List[String]): IO[Config] =
      for {
        dirEnv <- Env[IO].get("DASHBOARDS_DIR")
        dir <- IO.fromEither(
          workspaceDir(args, dirEnv).leftMap(Exception(_))
        )
        dashboardsDir = os.Path(dir, os.pwd)
        // A dev run shares `~/.pkl/cache` with the `pkl` CLI and pkl-lsp.
        cacheDir <- pathFromEnv(
          "FH_PKL_CACHE_DIR",
          AddonBootstrap.defaultCacheDir
        )
        assetsDir <- pathFromEnv("FH_ASSETS_DIR", "assets-cache")
        bindHost <- Env[IO]
          .get("HOST")
          .flatMap(
            _.fold(IO.pure(host"127.0.0.1"))(raw =>
              Host
                .fromString(raw)
                .liftTo[IO](Exception(s"HOST='$raw' is not a host"))
            )
          )
        portString <- envOr("PORT", "8080")
        bindPort = portString.toIntOption
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
        watchRegistry,
        pklLspJar
      )
  }

  def run(args: List[String]): IO[ExitCode] =
    (for {
      // First, so the boot path — where a first start goes wrong — has a
      // logger that reaches the collector.
      otel <- Telemetry.resource
      loggerFactory <- Logging.factory(otel).toResource
      log = loggerFactory.getLoggerFromName(LoggerName)
      config <- Config.load(args).toResource
      // Pins the first dump's lib dependency before any `pins.json` exists.
      bundledLib <- bootstrap(config, log).toResource
      meters <- Meters.create(otel.meterProvider).toResource
      // Every outbound client is wrapped: an unwrapped one is a hole in the
      // trace of a slow page open.
      traceClient <- ClientMiddleware
        .builder[IO](
          // Paths and queries kept: which call was slow is the question, and
          // credentials ride in headers.
          ClientSpanDataProvider.openTelemetry(
            new UriRedactor.OnlyRedactUserInfo {}
          )
        )(using MonadCancelThrow[IO], otel.tracerProvider)
        .build
        .toResource
      metricsClient <- OtelMetrics
        .clientMetricsOps[IO]()(using cats.Monad[IO], otel.meterProvider)
        .toResource
      instrument = (client: Client[IO]) =>
        traceClient.wrapClient(ClientMetrics[IO](metricsClient)(client))
      // Eagerly, so a missing credential fails boot instead of looking like
      // an unreachable HA inside the reconnect loop.
      haEnv <- FHApi.resolveEnv.toResource
      // `None` (unfiltered) until dashboards exist; the unfiltered
      // subscription is also what fills the store the feed waits on.
      // `narrowFeed` narrows it.
      wanted <- SignallingRef[IO]
        .of(Option.empty[Set[String]])
        .toResource
      feedTracer <- otel.tracerProvider
        .get("fh.view.runtime.HaFeed")
        .toResource
      // The one HA connection: everything uses `feed.api`, so no second,
      // unsupervised socket dies silently on a drop. Blocks until seeded.
      feed <- HaFeed.resource(
        FHApi.lowLevelConnectWithClose(haEnv),
        wanted,
        feedTracer,
        loggerFactory,
        meters
      )
      dashboardsDir = config.dashboardsDir
      buildTracer <- otel.tracerProvider.get("fh.view.build").toResource
      prepared <- prepareRenderers(
        feed,
        dashboardsDir,
        Some(bundledLib),
        buildTracer,
        loggerFactory
      ).toResource
      built = prepared.built
      // Backs only the `/system/pkl/*` route; the server's own eval resolves
      // from the seeded cache (ADR 0010).
      systemPkl = SystemPkl.fromDisk(dashboardsDir)

      // Collected at boot: a reload that introduces new URLs passes them
      // through until restart.
      httpClient <- IO(java.net.http.HttpClient.newHttpClient()).toResource
      assets <- AssetCache
        .build(
          config.assetsDir,
          Server.DatastarCdn :: built.flatMap { case (_, renderer) =>
            renderer.stylesheets ++ renderer.deferredStylesheets ++
              renderer.scripts
          },
          instrument(org.http4s.jdkhttpclient.JdkHttpClient[IO](httpClient))
        )
        .toResource

      rendererRefs <- prepared.states.toList
        .traverse { case (slug, state) =>
          SignallingRef[IO].of(state).map(slug -> _)
        }
        .map(_.toMap)
        .toResource
      importsRef <- SignallingRef[IO]
        .of(watchedSet(dashboardsDir, prepared.imports))
        .toResource

      site <- Server.LiveSite
        .of(
          rendererRefs,
          prepared.content,
          defaultSlugFrom(prepared.default, prepared.states.keys.toList)
        )
        .toResource
      // Not part of the feed: the registry does not exist when it is
      // acquired.
      _ <- narrowFeed(site, wanted).background
      reload = reloadSite(dashboardsDir, site, importsRef, log)

      // Serialises the endpoint against the registry watcher.
      refreshMutex <- Mutex[IO].toResource
      refreshDump = refreshMutex.lock.surround(
        refreshOnce(feed.api, dashboardsDir, reload, log)
      )

      // Login (issue #89): HA is the OAuth identity provider. Under the
      // add-on this server dials `http://supervisor/core`, which no browser
      // can reach, so ask HA where it lives ([[HaOAuth.browserBase]] ranks
      // the sources). A failed answer is just one absent source.
      haInternalUrl <- feed.api.getConfigWS.attempt
        .map(_.toOption.flatMap(HaOAuth.internalUrlOf))
        .toResource
      haPublicUrl <- Env[IO]
        .get("FH_HA_PUBLIC_URL")
        .map(_.flatMap(org.http4s.Uri.fromString(_).toOption))
        .map(HaOAuth.browserBase(_, haInternalUrl, haEnv.server))
        .toResource
      // Not `SERVER` either: the supervisor proxy serves no `/auth/…` and
      // authenticates add-ons, not people ([[HaOAuth.coreBase]]).
      haCoreUrl <- Env[IO]
        .get("FH_HA_TOKEN_URL")
        .map(_.flatMap(org.http4s.Uri.fromString(_).toOption))
        .map(HaOAuth.coreBase(_, haInternalUrl, haEnv.server))
        .toResource
      _ <- log
        .info(
          s"Home Assistant login redirects go to $haPublicUrl; " +
            s"logins dial $haCoreUrl"
        )
        .toResource
      authSessions <- AuthSessions
        .create(SessionStore.inWorkspace(dashboardsDir, loggerFactory))
        .toResource
      oauth = new HaOAuth(
        haPublicUrl,
        haCoreUrl,
        instrument(org.http4s.jdkhttpclient.JdkHttpClient[IO](httpClient))
      )
      // A short-lived connection as a user, never the machine-token feed nor
      // its address ([[HaOAuth.coreWs]]). Shared by `identify` and
      // `ServiceCalls.asUser` so the address ranking is written once.
      connectAs = (token: String) =>
        FHApi.from(
          haCoreUrl,
          token,
          HaOAuth.coreWs(haCoreUrl, haEnv.server, haEnv.serverWs)
        )
      identify = (token: String) =>
        connectAs(token)
          .use(_.currentUser)
          .handleErrorWith(e =>
            FHError
              .unavailable(
                s"could not ask Home Assistant who this login belongs to: ${e.getMessage}"
              )
              .raiseError[IO, HaUser]
          )
      // Ingress headers are trusted by source address ([[Ingress]]). An empty
      // `FH_TRUSTED_PROXY` turns that trust off.
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
      server <- liveServer(
        feed,
        site,
        gate,
        assets,
        systemPkl,
        dumpRefresh = Some(refreshDump),
        // A tap is the user's (issue #198); with no login session it falls
        // back to the feed's identity.
        actions = ServiceCalls.asUser(_, connectAs, authSessions, oauth),
        tracerProvider = otel.tracerProvider,
        loggerFactory = loggerFactory,
        meterProvider = otel.meterProvider,
        meters = meters
      )
      pklLspJar <- resolvePklLspJar(config.pklLspJar, log).toResource
      editor = new EditorRoutes(
        dashboardsDir,
        gate,
        pklLspJar,
        site.defaultSlug,
        site.names
      )

      _ <- watchSources(reload, importsRef).compile.drain.background

      _ <-
        if (config.watchRegistry)
          watchRegistryEvents(
            feed.api,
            feed.healthy,
            refreshDump,
            log
          ).compile.drain.background.void
        else Resource.unit[IO]
      // What makes a revocation in HA reach a dashboard nobody is touching.
      _ <- revalidateSessions(
        authSessions,
        oauth,
        identify,
        log = log
      ).compile.drain.background
      // The query is dropped: `/auth/callback?code=…` would put an
      // authorization code in a span that leaves the machine. The path (a
      // slug or file name) carries no secret.
      traceServer <- ServerMiddleware
        .builder[IO](
          ServerSpanDataProvider.openTelemetry(
            new PathRedactor.NeverRedact with QueryRedactor {
              def redactQuery(query: Query): Query = Query.empty
            }
          )
        )(using MonadCancelThrow[IO], otel.tracerProvider)
        .build
        .toResource
      metricsServer <- OtelMetrics
        .serverMetricsOps[IO]()(using cats.Monad[IO], otel.meterProvider)
        .toResource
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(config.bindHost)
        .withPort(config.bindPort)
        .withLogger(
          loggerFactory.getLoggerFromName("org.http4s.ember.server")
        )
        .withHttpWebSocketApp(wsb =>
          // Metrics sits inside the error boundary (it takes routes), so
          // `errorResponseHandler` maps a raised `FHError` to the status the
          // client actually got, not a 500.
          traceServer.wrapHttpApp(
            FHError.handle(
              ServerMetrics[IO](
                metricsServer,
                errorResponseHandler = {
                  case e: FHError =>
                    Status.fromInt(e.status).toOption
                  case _ => Some(Status.InternalServerError)
                }
              )(
                authRoutes.routes <+> server.routes <+> editor.routes(wsb)
              ).orNotFound
            )
          )
        )
        .withShutdownTimeout(0.seconds)
        .build
      defaultSlug <- site.defaultSlug.toResource
      _ <- log
        .info(
          s"Dashboards serving on http://${config.bindHost}:${config.bindPort} " +
            s"(default '/$defaultSlug', all: ${prepared.states.keys.toList.sorted.mkString(", ")})"
        )
        .toResource
    } yield ()).useForever.as(ExitCode.Success)

  /** A dashboard that failed to build is registered as failed, not skipped, so
    * no slug the entrypoint names is silently absent.
    */
  private[runtime] case class Prepared(
      dashboards: Map[String, Either[String, Dashboard.Validated]],
      default: Option[String],
      imports: Set[os.Path]
  ) {

    lazy val states: Map[String, Server.RendererState] =
      dashboards.map { case (slug, result) => slug -> Server.stateOf(result) }

    /** The model, not the renderer, for [[reloadSite]] to compare
      * ([[Server.Origin]]).
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

  /** Seed the dump and evaluate `site.pkl`; shared with `TestServer`. Nothing
    * is fatal: an entrypoint that will not evaluate registers one failed
    * dashboard under [[Server.DefaultSlug]], so `/` still shows the error.
    */
  private[runtime] def prepareRenderers(
      feed: HaFeed,
      dashboardsDir: os.Path,
      bundledLib: Option[LibPackage.Artifacts],
      tracer: Tracer[IO] = Tracer.noop,
      loggerFactory: LoggerFactory[IO] = Logging.console
  ): IO[Prepared] = {
    val log = loggerFactory.getLoggerFromName(LoggerName)
    // No HTTP span covers this, and on a Pi it is seconds.
    tracer.span("dashboard.prepare").surround {
      tracer
        .span("dashboard.prepare.dump")
        .surround(
          DashboardBuild.prepareDumps(
            feed.api,
            dashboardsDir,
            bundledLib,
            loggerFactory
          )
        ) *>
        tracer
          .span("dashboard.prepare.eval")
          .surround(DashboardBuild.evalSite(dashboardsDir))
          .attempt
          .flatMap {
            case Right((site, imports)) =>
              IO.pure(
                Prepared(site.dashboards.toMap, site.default, imports)
              )
            case Left(err) =>
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
              renderer.warnings.traverse_(w => log.warn(s"'$slug': $w"))
            } *>
              prepared.failed.traverse_ { case (slug, message) =>
                log.error(s"Dashboard '$slug' failed to build: $message")
              }
          }
    }
  }

  /** HA's own access-token life, so a revocation shows within one generation.
    */
  private val RevalidateAfter: FiniteDuration = 30.minutes

  /** What makes HA authoritative, not merely the thing that once issued a
    * login. `Dead` evicts; a raised error does not, or every network hiccup
    * would log the household out.
    *
    * The first pass runs immediately: `awakeEvery` sleeps first, and a session
    * revoked during downtime would otherwise be served for five minutes.
    */
  private[runtime] def revalidateSessions(
      sessions: AuthSessions,
      oauth: HaOAuth,
      identify: String => IO[HaUser],
      every: FiniteDuration = 5.minutes,
      after: FiniteDuration = RevalidateAfter,
      log: SelfAwareStructuredLogger[IO] = consoleLog
  ): Stream[IO, Unit] = {
    val pass = revalidateOnce(sessions, oauth, identify, after, log)
    Stream.eval(pass) ++ Stream.awakeEvery[IO](every).evalMap(_ => pass)
  }

  private[runtime] def revalidateOnce(
      sessions: AuthSessions,
      oauth: HaOAuth,
      identify: String => IO[HaUser],
      after: FiniteDuration = RevalidateAfter,
      log: SelfAwareStructuredLogger[IO] = consoleLog
  ): IO[Unit] =
    IO.realTimeInstant
      .map(_.minusSeconds(after.toSeconds))
      .flatMap(sessions.stale)
      .flatMap(_.traverse_ { case (id, session) =>
        // HA accepts back only the client_id it stored at login.
        oauth
          .refresh(
            session.refresh,
            org.http4s.Uri.unsafeFromString(session.clientId)
          )
          .flatMap {
            case RefreshOutcome.Dead =>
              log.info(
                s"[auth] session for ${session.user.name} is no longer valid at HA; signing out"
              ) *> sessions.remove(id)
            case RefreshOutcome.Renewed(tokens) =>
              // Re-read the user too: a role change is what this is for.
              (identify(tokens.accessToken), IO.realTimeInstant).flatMapN {
                (user, at) =>
                  sessions.renew(
                    id,
                    user,
                    tokens.refreshToken.getOrElse(session.refresh),
                    // Kept, so a tap soon after does not mint a second.
                    Some(
                      HaAccess(
                        tokens.accessToken,
                        at.plusSeconds(tokens.expiresIn)
                      )
                    )
                  )
              }
          }
          .handleErrorWith(e =>
            log.warn(
              s"[auth] could not re-check a session (leaving it alone): ${e.getMessage}"
            )
          )
      })

  /** The wiring [[run]] and `TestServer` share. The renderer source and the
    * serving shell stay with the caller, as does the feed: production needs
    * `feed.api` to prepare the dump before any renderer exists.
    */
  private[runtime] def liveServer(
      feed: HaFeed,
      site: Server.LiveSite,
      gate: AuthGate,
      assets: AssetCache = AssetCache.empty,
      systemPkl: SystemPkl = SystemPkl.empty,
      dumpRefresh: Option[IO[DumpRefresh.Result]] = None,
      actions: HomeAssistantApi[IO] => ServiceCalls = ServiceCalls.asInstance,
      tracerProvider: TracerProvider[IO] = TracerProvider.noop,
      loggerFactory: LoggerFactory[IO] = Logging.console,
      meterProvider: MeterProvider[IO] = MeterProvider.noop,
      meters: Meters = Meters.noop
  ): Resource[IO, Server] =
    for {
      sessions <- Sessions.create.toResource
      _ <- Meters.observeSessions(
        meterProvider,
        sessions.all.map(_.size.toLong)
      )
      tracer <- tracerProvider.get("fh.view.runtime.Server").toResource
      server <- Server.fromFeed(
        feed,
        site,
        sessions,
        gate,
        assets,
        systemPkl,
        dumpRefresh,
        actions,
        tracer,
        loggerFactory,
        meters
      )
    } yield server

  private[runtime] def defaultSlugFrom(
      preferred: Option[String],
      all: List[String]
  ): String =
    Server.defaultSlugFor(preferred, all).getOrElse(Server.DefaultSlug)

  /** `file:` imports only: the lib and the dump are immutable packages (ADR
    * 0010), and a dump change goes through `DumpRefresh`.
    */
  private def watchedSet(
      dashboardsDir: os.Path,
      imports: Set[os.Path]
  ): Set[Path] =
    (imports +
      (dashboardsDir / Site.EntryFile) +
      // So a new package dependency re-resolves the lockfile
      // (`PklBuild.staleLockfile`).
      (dashboardsDir / EditorRoutes.Manifest) +
      // The directory, so a new file is noticed: a glob import exists so that
      // dropping one in adds a dashboard.
      dashboardsDir)
      .map(fs2Path)

  /** The directory watch also sees `PklProject.deps.json`, which the reload
    * itself rewrites, and `.fh/`. Overflow passes: events were lost.
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

  /** Decoupled from the OS watcher for tests; the `WatchService` itself is only
    * exercised manually.
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

  /** `Some` from the first emission, even empty: the feed then declines to
    * subscribe, since an empty `entity_ids` means the whole house to HA. `None`
    * is only the boot value.
    */
  private[runtime] def narrowFeed(
      site: Server.LiveSite,
      wanted: SignallingRef[IO, Option[Set[String]]]
  ): IO[Unit] =
    site.watchedEntities
      .evalMap(ids => wanted.set(Some(ids)))
      .compile
      .drain

  /** Re-evaluate the entrypoint and bring the live site in line; behind both
    * the source watcher and a dump swap ([[refreshOnce]]). What changed is
    * decided by [[Server.LiveSite.applySite]] against each slug's origin.
    */
  private[runtime] def reloadSite(
      dashboardsDir: os.Path,
      site: Server.LiveSite,
      importsRef: SignallingRef[IO, Set[Path]],
      log: SelfAwareStructuredLogger[IO] = consoleLog
  ): IO[Unit] =
    DashboardBuild.evalSite(dashboardsDir).attempt.flatMap {
      case Left(err) =>
        site.failSite(Site.messageOf(err)).flatMap(report(_, log))
      case Right((decoded, imports)) =>
        for {
          changes <- site.applySite(decoded.dashboards, decoded.default)
          _ <- report(changes, log)
          _ <- importsRef.set(watchedSet(dashboardsDir, imports))
          reloaded = changes.collect {
            case c @ (_: Server.Change.Added | _: Server.Change.Broke |
                _: Server.Change.Recovered | _: Server.Change.Rebuilt) =>
              c.describe._1
          }
          _ <- IO.whenA(changes.nonEmpty)(
            log.info(s"Dashboards reloaded (${reloaded.mkString(", ")})")
          )
        } yield ()
    }

  private def report(
      changes: List[Server.Change],
      log: SelfAwareStructuredLogger[IO]
  ): IO[Unit] =
    changes.traverse_(_.describe._2.traverse_(log.info(_)))

  /** A rejection only warns: the dashboards keep the current dump until fixed.
    */
  private def refreshOnce(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      reload: IO[Unit],
      log: SelfAwareStructuredLogger[IO]
  ): IO[DumpRefresh.Result] =
    RegistryDump
      .fetch(api)
      .flatTap(
        PklDump.warnings(_).traverse_(w => log.warn(s"dump warning: $w"))
      )
      .map(PklDump.render)
      .flatMap(DumpRefresh.refresh(_, dashboardsDir))
      .flatTap {
        case DumpRefresh.Unchanged =>
          // The answer to most registry events.
          log.debug("dump refresh: home unchanged")
        case DumpRefresh.Swapped(version, seedLog) =>
          seedLog.traverse_(log.info(_)) *>
            log.info(s"dump refreshed -> $version") *> reload
        case DumpRefresh.Rejected(errors) =>
          log.warn(
            "dump refresh rejected — the new dump breaks dashboards " +
              "that build today; keeping the current dump:"
          ) *>
            errors.traverse_ { case (slug, err) =>
              log.warn(s"  '$slug': $err")
            }
      }

  /** Devices too, since a rename can fire only the device event; and
    * `component_loaded`, the only event for YAML entities with no registry
    * entry.
    */
  private val DumpEvents = List(
    "entity_registry_updated",
    "device_registry_updated",
    "area_registry_updated",
    "floor_registry_updated",
    "component_loaded"
  )

  private val RegistryWatchOff = Set("false", "0", "off", "no")

  /** Debounced: one integration fires dozens of events. Re-subscribes per
    * connection and refreshes on every reconnect, so an event lost in the gap
    * is re-derived rather than replayed. A full re-evaluation, not a patch: it
    * happens a few times a year.
    */
  private def watchRegistryEvents(
      api: HomeAssistantApi[IO],
      healthy: Signal[IO, Boolean],
      refresh: IO[DumpRefresh.Result],
      log: SelfAwareStructuredLogger[IO]
  ): Stream[IO, Unit] = {
    val runRefresh = refresh.attempt.flatMap {
      case Left(err) =>
        log.error(err)("registry-driven dump refresh failed")
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
        Stream.exec(runRefresh) ++ events
      }
  }

  private val RegistryQuiet = 5.seconds

  private def fs2Path(p: os.Path): Path = Path.fromNioPath(p.toNIO)

  /** On every start, add-on or dev, so the two never diverge (ADR 0010). The
    * lib and starter come from the jar's own resources.
    */
  private def bootstrap(
      config: Config,
      log: SelfAwareStructuredLogger[IO]
  ): IO[LibPackage.Artifacts] =
    for {
      bundled <- IO.blocking(BundledLib.artifacts())
      _ <- IO
        .blocking(
          AddonBootstrap
            .run(
              config.dashboardsDir,
              bundled,
              config.cacheDir
            )
        )
        .flatMap(_.traverse_(log.info(_)))
    } yield bundled

  private def envOr(name: String, default: String): IO[String] =
    Env[IO].get(name).map(_.getOrElse(default))

  // Relative to the forked run's cwd, the repo root.
  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    envOr(name, default).map(s => os.Path(s, os.pwd))

  /** No default, and a second argument is refused: either way a mistyped path
    * booted green on a fresh workspace nobody named, beside the real one.
    */
  private[runtime] def workspaceDir(
      args: List[String],
      dashboardsDirEnv: Option[String]
  ): Either[String, String] =
    args match {
      case dir :: Nil => Right(dir)
      case Nil        =>
        dashboardsDirEnv
          .filter(_.nonEmpty)
          .toRight(
            "no workspace to serve: name the directory — `sbt 'dashboardServe <dir>'` — or set DASHBOARDS_DIR"
          )
      case more =>
        Left(
          s"dashboardServe takes one workspace directory, got ${more.size}: " +
            more.mkString(", ") +
            " — quote a path that contains spaces, and set everything else through the environment"
        )
    }

  /** `None` degrades the editor (no completion or diagnostics) rather than
    * failing boot.
    */
  private def resolvePklLspJar(
      jarOverride: Option[String],
      log: SelfAwareStructuredLogger[IO]
  ): IO[Option[os.Path]] =
    jarOverride.filter(_.nonEmpty) match {
      case None =>
        log.warn("pkl-lsp: PKL_LSP_JAR unset; LSP features disabled").as(None)
      case Some(p) =>
        val path = os.Path(p, os.pwd)
        IO.blocking(os.exists(path)).flatMap {
          case true  => IO.pure(Some(path))
          case false =>
            log
              .warn(s"pkl-lsp: PKL_LSP_JAR=$p does not exist; LSP disabled")
              .as(None)
        }
    }
}
