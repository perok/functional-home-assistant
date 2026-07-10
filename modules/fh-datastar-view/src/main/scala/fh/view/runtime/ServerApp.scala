package fh.view.runtime

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Env
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{Host, Port, host, port}
import fh.api.FHApi
import fh.view.build.DashboardBuild
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

  // Relative to the module directory (the forked `run` working dir).
  private val defaultDashboardsDir = "src/main/resources/dashboards"

  def run(args: List[String]): IO[ExitCode] =
    for {
      dashboardsDir <- pathFromEnv("DASHBOARDS_DIR", defaultDashboardsDir)
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
        // Every top-level `*.pkl` in the dir is a dashboard;
        // slug = filename sans ext (Pkl library modules live in `lib/`).
        entries <- discoverEntries(dashboardsDir).toResource
        _ <- IO
          .raiseWhen(entries.isEmpty)(
            new RuntimeException(s"no *.pkl dashboards in $dashboardsDir")
          )
          .toResource

        // Write the live dump once (so `import "lib/dump.pkl"` resolves) via
        // the build phase, then re-evaluate every entry against the on-disk
        // dump. The runtime calls through `DashboardBuild`, never
        // `DataDump`/`PklDump` directly — build owns fetching + writing the
        // dump.
        _ <- DashboardBuild.prepareDumps(api, dashboardsDir).toResource
        // Per-entry: a broken dashboard (e.g. a bad user edit before a
        // restart) is logged and skipped, not a crash loop; only zero
        // buildable dashboards is fatal.
        built <- entries
          .traverse { case (slug, entry) =>
            buildEntry(dashboardsDir, slug, entry).attempt.flatMap {
              case Right(r) => IO.pure(Some((slug, r)))
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

        // Self-healing live feed: supervises its OWN Home Assistant connection
        // (re-`.use`s `FHApi.fromEnvWithClose` on drop, reacting to the
        // connection's `awaitClosed`), keeps the store seeded across reconnects,
        // and reports upstream health. Runtime `call_service` calls and live
        // state both go through this, so a dropped HA WebSocket no longer
        // freezes the dashboard until a restart. (The startup `api` above stays
        // scoped to dump/asset prep.)
        feed <- HaFeed.resource(FHApi.fromEnvWithClose)
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
        // Also runs the per-slug shared patch publishers in the background —
        // the render-once fan-out every SSE connection subscribes to.
        server <- Server.resource(
          feed.api,
          feed.store,
          rendererRefs,
          defaultSlug,
          sessions,
          assets,
          feed.healthy
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
          }

      reload.concurrently(reconcile)
    }

  private def fs2Path(p: os.Path): Path = Path.fromNioPath(p.toNIO)

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
          case true => IO.pure(Some(path))
          case false =>
            IO.println(s"pkl-lsp: PKL_LSP_JAR=$p does not exist").as(None)
        }
      case None =>
        val cache = os.pwd / ".pkl-lsp" / s"pkl-lsp-$PklLspVersion.jar"
        IO.blocking(os.exists(cache)).flatMap {
          case true => IO.pure(Some(cache))
          case false =>
            downloadPklLsp(client, cache).attempt.flatMap {
              case Right(_) => IO.pure(Some(cache))
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
