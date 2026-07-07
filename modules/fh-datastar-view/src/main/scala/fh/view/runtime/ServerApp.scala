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
      bindHost <- Env[IO]
        .get("HOST")
        .map(_.flatMap(Host.fromString).getOrElse(host"0.0.0.0"))
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
        built <- entries.traverse { case (slug, entry) =>
          buildEntry(dashboardsDir, slug, entry).map((slug, _))
        }.toResource

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

        defaultSlug <- defaultSlugFrom(entries.map(_._1)).toResource
        // Also runs the per-slug shared patch publishers in the background —
        // the render-once fan-out every SSE connection subscribes to.
        server <- Server.resource(
          api,
          store,
          rendererRefs,
          defaultSlug,
          sessions
        )

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
          .withHttpApp(server.routes.orNotFound)
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
    * hot-swap each renderer; the SSE streams repaint their body. A failed
    * re-eval logs and keeps the previous renderers. Mirrors the single-
    * dashboard watcher: a concurrent reconcile tracks `importsRef` so newly-
    * imported files start being watched and removed ones stop.
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
                buildEntry(dashboardsDir, slug, entry).map((slug, _))
              }
              .attempt
              .flatMap {
                case Right(rebuilt) =>
                  rebuilt.traverse_ { case (slug, (renderer, _)) =>
                    rendererRefs.get(slug).traverse_(_.set(renderer))
                  } *>
                    importsRef.set(
                      watchedSet(dashboardsDir, entries, rebuilt.map(_._2._2))
                    ) *>
                    IO.println("Dashboards reloaded")
                case Left(err) =>
                  IO.println(
                    s"Dashboard reload failed (keeping previous): ${err.getMessage}"
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
}
