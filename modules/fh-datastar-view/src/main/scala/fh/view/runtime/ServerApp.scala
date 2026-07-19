package fh.view.runtime

import api.homeassistant.HomeAssistantApi
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.{Env, Mutex}
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.build.{
  AddonBootstrap,
  DashboardBuild,
  DataDump,
  DumpRefresh,
  LibPackage,
  PklDump,
  SystemPkl
}
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.ember.server.EmberServerBuilder
import fs2.io.file.{Watcher, Path}

/** Runtime phase entry point.
  *
  * Connects to Home Assistant, discovers every `*.pkl` dashboard entry in the
  * dashboards dir (slug = filename), evaluates each **in memory** into the
  * runtime model, seeds live state, and serves them with live Datastar updates.
  * Run via `fh-datastar-view/runMain fh.view.runtime.ServerApp` with
  * `SERVER`/`SECRET` set.
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
  // The `lib/` and starter entries a dev run treats as "bundled" — the same
  // resources the add-on image bakes in, here read straight from the repo. The
  // add-on overrides these three via `run.sh` env.
  private val bundledResourcesDir = "src/main/resources/dashboards"

  // Persistent pkl package cache for a dev run: the cross-platform user data
  // dir (`~/.local/share/fh/…/pkl-cache` on Linux), shared with `BuildApp` and
  // the laptop `fh` via one appdirs helper. The add-on overrides it to its
  // persistent `/data/pkl-cache` via `FH_PKL_CACHE_DIR`.
  private def defaultCacheDir: String =
    AddonBootstrap.defaultCacheDir

  def run(args: List[String]): IO[ExitCode] =
    for {
      // Workspace precedence: optional CLI arg > `DASHBOARDS_DIR` env > default.
      // Both the add-on and a local `dashboardServe` set the env (the dev run
      // via the project's `run / envVars`, an absolute repo-root path); the arg
      // stays as a manual override.
      dashboardsDir <- args.headOption
        .map(p => IO.pure(os.Path(p, os.pwd)))
        .getOrElse(pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir))
      // The bundled `@fh-dashboard` artifacts this boot seeded — passed to the
      // first `prepareDumps` so it can pin the dump's lib dependency before any
      // `pins.json` exists (fresh workspace, first-boot ordering).
      bundledLib <- bootstrap(dashboardsDir)
      // Loopback by default: /sse/action/* drives HA with the server's token
      // and no auth, so LAN exposure is opt-in (HOST=0.0.0.0).
      bindHost <- Env[IO]
        .get("HOST")
        .map(_.flatMap(Host.fromString).getOrElse(host"127.0.0.1"))
      bindPort <- Env[IO]
        .get("PORT")
        .map(
          _.flatMap(_.toIntOption).flatMap(Port.fromInt).getOrElse(port"8080")
        )

      _ <- (for {
        api <- FHApi.fromEnv
        // Every top-level `*.pkl` in the dir is a dashboard; slug = filename
        // sans ext. The library is not in the workspace — it resolves through
        // the `@fh-dashboard` package (seeded into the cache by `bootstrap`).
        entries <- discoverEntries(dashboardsDir).toResource
        _ <- IO
          .raiseWhen(entries.isEmpty)(
            new RuntimeException(s"no *.pkl dashboards in $dashboardsDir")
          )
          .toResource

        // Write the live dump once (so `import "@fh-home/dump.pkl"` resolves)
        // via the build phase, then re-evaluate every entry against the on-disk
        // dump. The runtime calls through `DashboardBuild`, never
        // `DataDump`/`PklDump` directly — build owns fetching + writing the
        // dump.
        _ <- DashboardBuild
          .prepareDumps(api, dashboardsDir, Some(bundledLib))
          .toResource
        // Serves this home's `dump.pkl` and its resolved package artifacts over
        // the public `/system/pkl/*` route for external tooling — the `fh`
        // script, pkl-lsp, remote authors — that fetch for real (ADR 0010). The
        // server's OWN eval never imports over http: entries resolve
        // `@fh-dashboard`/`@fh-home` from the seeded cache packages, so this
        // provider backs ONLY the route, not evaluation. Reads are by-name off
        // the pinned package in the cache, reflecting the latest dump.
        systemPkl = SystemPkl.fromDisk(dashboardsDir)
        // Per-entry: a broken dashboard (e.g. a bad user edit before a
        // restart) is logged and skipped, not a crash loop; only zero
        // buildable dashboards is fatal.
        built <- entries
          .traverse { case (slug, entry) =>
            buildEntry(dashboardsDir, slug, entry).attempt.flatMap {
              case Right(r)  => IO.pure(Some((slug, r)))
              case Left(err) =>
                IO.println(
                  s"Skipping dashboard '$slug' (build failed): ${err.getMessage}"
                ).as(None)
            }
          }
          .map(_.flatten)
          .toResource
        _ <- IO
          .raiseWhen(built.isEmpty)(
            new RuntimeException(
              s"all *.pkl dashboards in $dashboardsDir failed to build"
            )
          )
          .toResource

        // Cache the themes' external assets (CSS/JS/fonts) locally so the
        // dashboard serves them itself — offline-friendly, CDN fallback on a
        // cold-cache fetch failure. Reuses the JDK http client idiom from
        // FHApi; URLs are collected from every built renderer's theme (a
        // live-reload that introduces NEW urls passes through until restart).
        assetsDir <- pathFromEnv("FH_ASSETS_DIR", "assets-cache").toResource
        httpClient <- IO(java.net.http.HttpClient.newHttpClient()).toResource
        assets <- AssetCache
          .build(
            assetsDir,
            Server.DatastarCdn :: built.flatMap { case (_, (renderer, _)) =>
              renderer.stylesheets ++ renderer.scripts
            },
            org.http4s.jdkhttpclient.JdkHttpClient[IO](httpClient)
          )
          .toResource

        store <- StateStore.create(api)
        rendererRefs <- built
          .traverse { case (slug, (renderer, _)) =>
            SignallingRef[IO].of(renderer).map(slug -> _)
          }
          .map(_.toMap)
          .toResource
        importsRef <- SignallingRef[IO]
          .of(watchedSet(dashboardsDir, entries, built.map(_._2._2)))
          .toResource
        sessions <- Sessions.create.toResource

        // Only slugs that actually built (a skipped entry must not become
        // the default and 404 the root).
        defaultSlug <- defaultSlugFrom(built.map(_._1)).toResource

        // Dump refresh (validate-then-swap, DumpRefresh): re-fetch the entity
        // dump and swap it in only if every currently-building dashboard still
        // builds; on success the renderers hot-swap like a source edit. The
        // mutex serializes the endpoint against the registry watcher.
        refreshMutex <- Mutex[IO].toResource
        refreshDump = refreshMutex.lock.surround(
          refreshOnce(
            api,
            dashboardsDir,
            entries,
            rendererRefs,
            importsRef
          )
        )

        // Also runs the per-slug shared patch publishers in the background —
        // the render-once fan-out every SSE connection subscribes to.
        server <- Server.resource(
          api,
          store,
          rendererRefs,
          defaultSlug,
          sessions,
          assets,
          systemPkl,
          dumpRefresh = Some(refreshDump)
        )
        // The editor surface (/edit + /lsp/pkl). The pkl-lsp jar backs the LSP
        // subprocess; None just disables completion/diagnostics (the editor and
        // local highlighting still work).
        pklLspJar <- resolvePklLspJar(httpClient).toResource
        editor = new EditorRoutes(dashboardsDir, pklLspJar, defaultSlug)

        _ <- watchSources(
          dashboardsDir,
          entries,
          rendererRefs,
          importsRef
        ).compile.drain.background

        // Registry-driven dump refresh: HA's `*_registry_updated` events say
        // the HOME changed (device/entity/area/floor added, renamed, removed)
        // — exactly what the dump snapshots. Toggleable via the add-on's
        // `watch_registry` option (FH_WATCH_REGISTRY); on by default.
        watchRegistry <- Env[IO]
          .get("FH_WATCH_REGISTRY")
          .map(v => !v.map(_.trim.toLowerCase).exists(RegistryWatchOff))
          .toResource
        _ <-
          if (watchRegistry)
            watchRegistryEvents(api, refreshDump).compile.drain.background.void
          else Resource.unit[IO]
        _ <- EmberServerBuilder
          .default[IO]
          .withHost(bindHost)
          .withPort(bindPort)
          .withHttpWebSocketApp(wsb =>
            (server.routes <+> editor.routes(wsb)).orNotFound
          )
          .withShutdownTimeout(0.seconds)
          .build
        _ <- IO
          .println(
            s"Dashboards serving on http://$bindHost:$bindPort " +
              s"(default '/$defaultSlug', all: ${entries.map(_._1).mkString(", ")})"
          )
          .toResource
      } yield ()).useForever
    } yield ExitCode.Success

  /** `(slug, entryFilename)` for every top-level `*.pkl` in the dir,
    * slug-sorted. (`os.list` is non-recursive, so `lib/` — the Pkl library
    * modules — is never scanned.) Slugs are unique by construction: with a
    * single extension, slug = filename sans `.pkl`, and filenames are unique
    * within a directory.
    */
  private def discoverEntries(dir: os.Path): IO[List[(String, String)]] =
    IO.blocking {
      os.list(dir)
        .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
        .map(p => p.last.stripSuffix(".pkl") -> p.last)
        .sortBy(_._1)
        .toList
    }

  /** Default dashboard: `DEFAULT_DASHBOARD` if present, else `dashboard`, else
    * the first slug.
    */
  private def defaultSlugFrom(slugs: List[String]): IO[String] =
    Env[IO].get("DEFAULT_DASHBOARD").map { configured =>
      configured
        .filter(slugs.contains)
        .orElse(Option.when(slugs.contains("dashboard"))("dashboard"))
        .getOrElse(slugs.head)
    }

  /** Evaluate one entry against the on-disk dump and create its renderer,
    * forcing its slug to the filename-derived one (routing is by slug).
    */
  private def buildEntry(
      dashboardsDir: os.Path,
      slug: String,
      entry: String
  ): IO[(Renderer, Set[os.Path])] =
    DashboardBuild.reevaluate(dashboardsDir, entry).map {
      case (dash, imports) =>
        Renderer.create(dash.copy(slug = slug)) -> imports
    }

  /** The set of files to watch: every entry's transitive imports plus the entry
    * files themselves (so a brand-new import or a top-level edit is caught).
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
      entries: List[(String, String)],
      imports: List[Set[os.Path]]
  ): Set[Path] =
    (imports.flatten.toSet ++ entries.map { case (_, e) => dashboardsDir / e })
      .map(fs2Path)

  private val watchedEvents = List(
    Watcher.EventType.Created,
    Watcher.EventType.Modified,
    Watcher.EventType.Deleted
  )

  /** Watch every dashboard's source graph and, on change, re-evaluate ALL
    * entries (they share the `lib/` modules, so one edit can touch several) and
    * hot-swap each renderer; the SSE streams repaint their body. Re-eval is
    * per-entry: a failing entry logs and keeps its previous renderer while the
    * others still swap (an entry that failed at STARTUP has no renderer ref —
    * fixing it needs a restart). Mirrors the single-dashboard watcher: a
    * concurrent reconcile tracks `importsRef` so newly-imported files start
    * being watched and removed ones stop.
    */
  private def watchSources(
      dashboardsDir: os.Path,
      entries: List[(String, String)],
      rendererRefs: Map[String, SignallingRef[IO, Renderer]],
      importsRef: SignallingRef[IO, Set[Path]]
  ): Stream[IO, Unit] =
    Stream.resource(Watcher.default[IO]).flatMap { watcher =>
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
                    .traverse(p => watcher.watch(p, watchedEvents).tupleLeft(p))
                  _ <- toCancel.toList
                    .traverse_(p => current.getOrElse(p, IO.unit))
                  _ <- active.set((current ++ added) -- toCancel)
                } yield ()
              }
            }
          }

      val reload =
        watcher
          .events()
          .debounce(200.millis)
          .evalMap { _ =>
            reloadEntries(
              dashboardsDir,
              entries,
              rendererRefs,
              importsRef
            )
          }

      reload.concurrently(reconcile)
    }

  /** Re-evaluate ALL entries against the on-disk sources + dump and hot-swap
    * each renderer (per-entry: a failing entry logs and keeps its previous
    * renderer). The body behind both the source watcher and the post-dump-swap
    * reload ([[refreshOnce]] — the dump is deliberately not watched).
    */
  private def reloadEntries(
      dashboardsDir: os.Path,
      entries: List[(String, String)],
      rendererRefs: Map[String, SignallingRef[IO, Renderer]],
      importsRef: SignallingRef[IO, Set[Path]]
  ): IO[Unit] =
    entries
      .traverse { case (slug, entry) =>
        buildEntry(dashboardsDir, slug, entry).attempt
          .map((slug, _))
      }
      .flatMap { results =>
        val rebuilt = results.collect { case (slug, Right(r)) =>
          (slug, r)
        }
        val failed = results.collect { case (slug, Left(err)) =>
          (slug, err)
        }
        failed.traverse_ { case (slug, err) =>
          IO.println(
            s"Dashboard '$slug' reload failed (keeping previous): ${err.getMessage}"
          )
        } *>
          rebuilt.traverse_ { case (slug, (renderer, _)) =>
            rendererRefs.get(slug).traverse_(_.set(renderer))
          } *>
          IO.whenA(rebuilt.nonEmpty)(
            importsRef.set(
              watchedSet(dashboardsDir, entries, rebuilt.map(_._2._2))
            ) *>
              IO.println(
                s"Dashboards reloaded (${rebuilt.map(_._1).mkString(", ")})"
              )
          )
      }

  /** One full dump refresh: fetch + render the live dump, validate-then-swap
    * ([[DumpRefresh.refresh]]), and on a swap hot-reload every renderer (the
    * source watcher does not watch the dump). A rejection only warns — HA
    * changed, but the dashboards keep building against the current dump until
    * they're fixed and a refresh is retried.
    */
  private def refreshOnce(
      api: HomeAssistantApi[IO],
      dashboardsDir: os.Path,
      entries: List[(String, String)],
      rendererRefs: Map[String, SignallingRef[IO, Renderer]],
      importsRef: SignallingRef[IO, Set[Path]]
  ): IO[DumpRefresh.Result] =
    DataDump
      .fetch(api)
      .map(PklDump.render)
      .flatMap(DumpRefresh.refresh(_, dashboardsDir, entries))
      .flatTap {
        case DumpRefresh.Unchanged =>
          IO.println("dump refresh: home unchanged")
        case DumpRefresh.Swapped(version, seedLog) =>
          seedLog.traverse_(IO.println) *>
            IO.println(s"dump refreshed -> $version") *>
            reloadEntries(
              dashboardsDir,
              entries,
              rendererRefs,
              importsRef
            )
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
    */
  private def watchRegistryEvents(
      api: HomeAssistantApi[IO],
      refresh: IO[DumpRefresh.Result]
  ): Stream[IO, Unit] =
    Stream
      .emits(DumpEvents)
      .map { eventType =>
        Stream
          .resource(api.rawEvents(eventType))
          .flatMap(queue => Stream.repeatEval(queue.take))
      }
      .parJoinUnbounded
      .debounce(RegistryQuiet)
      .evalMap { _ =>
        refresh.attempt.flatMap {
          case Left(err) =>
            IO.println(
              s"registry-driven dump refresh failed: ${err.getMessage}"
            )
          case Right(_) => IO.unit
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
    * The three inputs come from `run.sh` on the add-on; a local `sbt
    * dashboardServe` has none set and falls back to the repo's own resources —
    * the `lib/` and starter entries the image would bake in are read straight
    * from `src/main/resources/dashboards`, and the cache lives beside the local
    * workspace. So a dev run reads `lib/` from the bundled resources exactly as
    * the add-on does; iterating on library Pkl is `fh push` against the running
    * instance, never a mutable workspace `lib/`.
    */
  private def bootstrap(dashboardsDir: os.Path): IO[LibPackage.Artifacts] =
    for {
      bundledLib <- pathFromEnv(
        "FH_BUNDLED_LIB",
        s"$bundledResourcesDir/lib"
      )
      seedDir <- pathFromEnv("FH_SEED_DIR", bundledResourcesDir)
      cacheDir <- pathFromEnv("FH_PKL_CACHE_DIR", defaultCacheDir)
      // This instance's own URL, written into `.fh/machine.json` as the
      // `http.rewrites` target. Loopback + the bind PORT: inert here (packages
      // resolve from the cache), it only matters if the workspace is copied — a
      // laptop's `fh init` overwrites it with the real instance URL.
      port <- Env[IO].get("PORT").map(_.getOrElse("8080"))
      artifacts <- IO
        .blocking(
          AddonBootstrap
            .run(
              dashboardsDir,
              bundledLib,
              seedDir,
              cacheDir,
              loopbackUrl = s"http://127.0.0.1:$port"
            )
        )
        .flatMap { case (artifacts, log) =>
          log.traverse_(IO.println).as(artifacts)
        }
    } yield artifacts

  private def pathFromEnv(name: String, default: String): IO[os.Path] =
    Env[IO]
      .get(name)
      .map(_.getOrElse(default))
      .map(s => os.Path(s, os.pwd))

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
      client: java.net.http.HttpClient
  ): IO[Option[os.Path]] =
    Env[IO].get("PKL_LSP_JAR").flatMap {
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
