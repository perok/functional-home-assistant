package fh.view.build

import io.circe.parser
import org.pkl.core.evaluatorSettings.TraceMode
import org.pkl.core.http.HttpClient
import org.pkl.core.module.ModuleKeyFactories
import org.pkl.core.packages.PackageResolver
import org.pkl.core.project.{Project, ProjectDependenciesResolver}
import org.pkl.core.{
  Analyzer,
  Evaluator,
  EvaluatorBuilder,
  ModuleSource,
  SecurityManagers,
  StackFrameTransformers,
  ValueRenderers
}

import java.io.{FileOutputStream, PrintWriter, StringWriter}

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** In-process Pkl evaluation for the build phase.
  *
  * The evaluated module is rendered to JSON here (Java-side
  * `ValueRenderers.json` with `omitNullProperties = true`, so absent optional
  * fields decode as `None` rather than JSON nulls) — entry modules need no
  * `output { renderer = ... }` block; an entry just IS its data. This runs once
  * at build/startup/reload — never on the live hot path.
  */
object PklBuild {

  /** Evaluate `entryFile` (relative to `dashboardsDir`). Returns the evaluated
    * JSON + import set, or an error string (Pkl errors carry their own
    * file:line carets, so the message is passed through verbatim).
    *
    * The import set is the entry's precise transitive imports, computed by
    * pkl-core's static import-analysis ([[importSet]]). If that analysis fails
    * for any reason it falls back to the conservative all-`*.pkl`-under-dir
    * superset; the entry itself is always included.
    */
  def eval(
      dashboardsDir: os.Path,
      entryFile: String
  ): Either[String, SourceEval.Result] = {
    val entry = dashboardsDir / os.SubPath(entryFile)
    try {
      val project = resolveProjectDeps(dashboardsDir)
      val evaluator =
        buildEvaluator(project, cacheDir(dashboardsDir, project))
      val module =
        try evaluator.evaluate(ModuleSource.path(entry.toNIO))
        finally evaluator.close()
      val writer = new StringWriter
      ValueRenderers.json(writer, "  ", true).renderDocument(module)
      parser.parse(writer.toString).left.map(_.message).map { json =>
        SourceEval.Result(
          json,
          importSet(dashboardsDir, entry, project)
        )
      }
    } catch {
      case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
    }
  }

  /** The evaluator for one eval.
    *
    * When the dashboards dir is a Pkl project (has a `PklProject`), `project`
    * is its loaded form and we `applyFromProject` so entries can import the
    * `lib/` library through the `@fh-dashboard` alias and the dump through
    * `@fh-home` (ADR 0010) — both resolved offline from the seeded cache
    * packages. Plain relative-import evals — most unit probes, no `PklProject`
    * — pass `None` and behave exactly as `Evaluator.preconfigured()`. No Pkl
    * source imports over http, so the preconfigured allowlist (which already
    * admits `package:`/`projectpackage:`) needs no widening.
    */
  private def buildEvaluator(
      project: Option[Project],
      cache: os.Path
  ): Evaluator = {
    val builder = EvaluatorBuilder.preconfigured()
    project.foreach(builder.applyFromProject)
    // Same cache the resolver used — a REMOTE dep (the add-on's package-form
    // `@fh-dashboard`) must find its pre-seeded zip here rather than in
    // `preconfigured()`'s `~/.pkl/cache`. Set after `applyFromProject` so it
    // wins even when the project declares no `moduleCacheDir` of its own.
    builder.setModuleCacheDir(cache.toNIO)
    builder.build()
  }

  /** If `dashboardsDir` is a Pkl project, load it and ensure its
    * `PklProject.deps.json` lockfile exists, so `applyFromProject` can resolve
    * the `@fh-dashboard` alias. Resolution is IN-PROCESS, and touches the
    * network only for a REMOTE dependency that is not already in the package
    * cache: local deps read files, and a cached remote version satisfies the
    * resolver without a request (so add-on boots stay offline-safe — the client
    * below is lazy and is never even built then). An uncached remote dep — a
    * published third-party card package, or a bumped `@fh-dashboard` pin the
    * image didn't bundle — is fetched for real, honoring the manifest's own
    * `evaluatorSettings.http.rewrites` (the documented air-gap mechanism; how a
    * workspace maps `fh.invalid` to a real host). If that fetch fails (offline,
    * dead registry), the error propagates into the entry's build error verbatim
    * — pkl names the package URI — and the resolve-before-write order below
    * keeps the previous lockfile intact. Returns the loaded [[Project]], or
    * `None` when there is no `PklProject` (the plain-eval path).
    */
  private def resolveProjectDeps(dashboardsDir: os.Path): Option[Project] = {
    val projectFile = dashboardsDir / "PklProject"
    Option.when(os.exists(projectFile)) {
      val project = Project.loadFromPath(projectFile.toNIO)
      val depsJson = dashboardsDir / "PklProject.deps.json"
      if (staleLockfile(dashboardsDir, depsJson)) {
        val resolver = new ProjectDependenciesResolver(
          project,
          PackageResolver.getInstance(
            SecurityManagers.defaultManager,
            settingsHttpClient(project),
            cacheDir(dashboardsDir, Some(project)).toNIO
          ),
          new PrintWriter(new StringWriter)
        )
        // Resolve fully BEFORE opening the lockfile: `FileOutputStream`
        // truncates on open, so the old order destroyed the previous lockfile
        // whenever resolution threw.
        val resolved = resolver.resolve()
        val out = new FileOutputStream(depsJson.toNIO.toFile)
        try resolved.writeTo(out)
        finally out.close()
      }
      project
    }
  }

  /** The resolver's http client, from the manifest's own
    * `evaluatorSettings.http` (spike-verified on 0.31.1: `setRewrites` with the
    * settings' map reproduces what the pkl CLI does with them; a manifest
    * without the block yields a plain client). Lazy — a fully-cached resolve
    * never builds it — with bounded timeouts so a dead registry fails a boot in
    * seconds, not minutes. The evaluator side needs no counterpart:
    * `applyFromProject` already applies the same settings (spike-verified).
    */
  private def settingsHttpClient(project: Project): HttpClient = {
    val builder = HttpClient
      .builder()
      .setConnectTimeout(java.time.Duration.ofSeconds(10))
      .setRequestTimeout(java.time.Duration.ofSeconds(60))
    Option(project.getEvaluatorSettings)
      .flatMap(s => Option(s.http()))
      .flatMap(h => Option(h.rewrites()))
      .foreach(builder.setRewrites)
    builder.buildLazily()
  }

  /** Re-resolve when the lockfile is absent OR any `PklProject` under the dir
    * outdates it — so editing a manifest (adding a dependency, bumping the
    * `@fh-dashboard` pin) takes effect on the next eval instead of silently
    * serving the stale pin forever (the frozen-lockfile bug, ADR 0010).
    */
  private def staleLockfile(
      dashboardsDir: os.Path,
      depsJson: os.Path
  ): Boolean =
    !os.exists(depsJson) || {
      val lockTime = os.mtime(depsJson)
      // `.fh/base.pkl` is the machine-owned half of the manifest amends chain,
      // and `.fh/pins.json` holds the `@fh-dashboard`/`@fh-home` pins the static
      // base.pkl reads — a tool rewriting either (`DumpPackage.seedFromText`
      // moves the home pin on every dump change) must take effect exactly like a
      // manifest edit.
      os.walk(dashboardsDir, maxDepth = 2)
        .exists(p =>
          (p.last == "PklProject" ||
            ((p.last == "base.pkl" || p.last == "pins.json") &&
              (p / os.up).last == ".fh")) &&
            os.mtime(p) > lockTime
        )
    }

  /** The package cache for this workspace, taken from the loaded project's
    * `evaluatorSettings.moduleCacheDir` — which the static `.fh/base.pkl`
    * always declares (reading it from `.fh/machine.json`; the add-on points it
    * at persistent storage and pkl-lsp honors the same setting). Used
    * identically by the resolver, the evaluator and the analyzer — a remote dep
    * resolves offline as long as its version is already IN this cache
    * (pre-seeded by `LibPackage`).
    *
    * A loaded `PklProject` that declares NO `moduleCacheDir` is a HARD ERROR:
    * in this design every workspace's `base.pkl` supplies it, so its absence
    * means an un-bootstrapped / corrupt workspace — better a loud failure than
    * a silent stray `.pkl-cache`. Only the projectless plain-eval path (no
    * `PklProject` at all, hence no package deps) falls back to a
    * workspace-local `.pkl-cache`.
    */
  private[build] def workspaceCacheDir(dashboardsDir: os.Path): os.Path = {
    val projectFile = dashboardsDir / "PklProject"
    val project = Try(
      Option.when(os.exists(projectFile))(
        Project.loadFromPath(projectFile.toNIO)
      )
    ).toOption.flatten
    cacheDir(dashboardsDir, project)
  }

  private def cacheDir(
      dashboardsDir: os.Path,
      project: Option[Project]
  ): os.Path =
    project match {
      case Some(p) =>
        Option(p.getEvaluatorSettings.moduleCacheDir())
          .map { path =>
            // pkl resolves a relative moduleCacheDir against the project dir.
            if (path.isAbsolute) os.Path(path)
            else dashboardsDir / os.RelPath(path.toString)
          }
          .getOrElse(
            sys.error(
              s"${dashboardsDir / ".fh" / "base.pkl"} declares no moduleCacheDir " +
                "— the workspace is not bootstrapped; run `fh init` or restart " +
                "the add-on"
            )
          )
      // No PklProject at all: the plain-eval path has no package deps, so a
      // workspace-local cache location is enough.
      case None => dashboardsDir / ".pkl-cache"
    }

  /** The entry's transitive imports as `file:` paths under `dashboardsDir`.
    *
    * Uses pkl-core's static analyzer (`Analyzer.importGraph`): the graph's
    * module set (the `imports` map keys, plus resolved targets) is every module
    * the entry pulls in. We keep only `file:` modules under the dashboards dir
    * (dropping `pkl:`/`package:`/`http(s):` stdlib and remote imports, which
    * are not local files to watch). On any failure — or an empty result — we
    * fall back to the conservative superset (every `*.pkl` under the dir); the
    * entry is always included regardless.
    *
    * **The `@fh-dashboard` alias resolves here too**, which is why this is
    * precise rather than a superset for the real dashboards. Two of the
    * `Analyzer` constructor's slots do the work: the `moduleCacheDir` and the
    * `DeclaredDependencies` (`project.getDependencies`, the same
    * [[resolveProjectDeps]] output the evaluator gets). With those supplied and
    * the `projectpackage`/`pkg` factories registered, an
    * `import "@fh-dashboard/components.pkl"` analyzes as
    * `projectpackage://fh.invalid/fh-dashboard@1.0.0#/components.pkl`, and —
    * because `@fh-dashboard` is a LOCAL dependency — `graph.resolvedImports`
    * maps it straight back to the real `file:…/lib/components.pkl`. So the
    * `file:` filter below picks up exactly the library modules the entry
    * actually imports, and nothing else (verified on pkl-core 0.31.1).
    *
    * That precision is why `ServerApp.watchedSet` does NOT need to bulk-add
    * `lib/`: an entry that imports a card class watches that card class, and a
    * library module nobody imports is correctly not watched. Lib/dump arrive as
    * cache-backed `package:` imports and are filtered out of the `file:` set —
    * they are immutable per version, not hot-reloaded.
    */
  private def importSet(
      dashboardsDir: os.Path,
      entry: os.Path,
      project: Option[Project]
  ): Set[os.Path] = {
    val factories =
      List(
        ModuleKeyFactories.standardLibrary,
        ModuleKeyFactories.file,
        ModuleKeyFactories.projectpackage,
        ModuleKeyFactories.pkg
      )
    val precise = Try {
      val analyzer = new Analyzer(
        StackFrameTransformers.defaultTransformer,
        false,
        SecurityManagers.defaultManager,
        factories.asJava,
        cacheDir(dashboardsDir, project).toNIO,
        project.map(_.getDependencies).orNull,
        HttpClient.dummyClient(),
        TraceMode.COMPACT
      )
      val graph = analyzer.importGraph(entry.toNIO.toUri)
      val uris =
        graph.imports.keySet.asScala.toSet ++ graph.resolvedImports.values.asScala.toSet
      uris.iterator
        .filter(u => u.getScheme == "file")
        .map(u => os.Path(java.nio.file.Paths.get(u)))
        .filter(_.startsWith(dashboardsDir))
        .toSet
    }.toOption.filter(_.nonEmpty)

    precise.getOrElse(superset(dashboardsDir)) + entry
  }

  /** Conservative fallback: every `*.pkl` under `dashboardsDir` (recursively —
    * library modules live in `lib/`). Over-watching is behaviorally identical,
    * since the watcher re-evaluates all entries on any change.
    */
  private def superset(dashboardsDir: os.Path): Set[os.Path] =
    os.walk(dashboardsDir)
      .filter(p => os.isFile(p) && p.ext == "pkl")
      .toSet
}
