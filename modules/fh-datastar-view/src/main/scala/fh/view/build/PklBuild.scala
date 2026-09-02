package fh.view.build

import io.circe.parser
import org.pkl.core.evaluatorSettings.TraceMode
import org.pkl.core.http.HttpClient
import org.pkl.core.module.ModuleKeyFactories
import org.pkl.core.packages.PackageResolver
import org.pkl.core.project.{Project, ProjectDependenciesResolver}
import org.pkl.core.{
  Analyzer,
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

  private object Truffle

  /** Run `thunk` with the process's ONE claim on pkl-core.
    *
    * pkl's stdlib modules are Truffle ASTs that specialize as they warm, and
    * they are shared process-wide — a fresh `Evaluator` per call does not give
    * them out fresh. Two threads evaluating at once can catch a node
    * mid-rewrite, which surfaces as an NPE from `pkl.semver`/`pkl.Project` deep
    * inside a `let`, naming a stdlib line rather than anything the caller wrote
    * (#226, pkl-core 0.32.1).
    *
    * Every production path is already serial, so this costs nothing there; it
    * is a guarantee, not a change. Coverage is by call site, not by type:
    * anything that builds an evaluator OR loads a `PklProject` has to come
    * through here — today `LibPackage.effectivePin` and the two test-side
    * evaluators, none of which route through this object otherwise.
    */
  def serialized[A](thunk: => A): A = Truffle.synchronized(thunk)

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
  ): Either[String, SourceEval.Result] = serialized {
    val entry = dashboardsDir / os.SubPath(entryFile)
    try {
      val project = loadProject(dashboardsDir)
      // ONE builder, and pkl derives the settings from the manifest exactly
      // once: `applyFromProject` needs no lockfile (only `evaluate` does), so
      // the resolve below can be handed what it produced instead of a second,
      // hand-rolled derivation of the same three values.
      val builder = EvaluatorBuilder.preconfigured()
      project.foreach(builder.applyFromProject)
      project.foreach(ensureLockfile(dashboardsDir, _, builder))
      // Same cache the resolver used — a REMOTE dep (the add-on's package-form
      // `@fh-dashboard`) must find its pre-seeded zip here rather than in
      // `preconfigured()`'s `~/.pkl/cache`. Set after `applyFromProject` so it
      // wins even when the project declares no `moduleCacheDir` of its own.
      builder.setModuleCacheDir(cacheDir(dashboardsDir, project).toNIO)
      val evaluator = builder.build()
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

  /** The security manager for dependency RESOLUTION, taken from the workspace's
    * own `evaluatorSettings.allowedResources`.
    *
    * pkl's default allowlist admits `https:` but never plain `http:`. Through
    * 0.31 that was invisible to us, because the allowlist was checked against
    * the `package://fh.invalid/…` URI the author wrote — which matches
    * `package:`. Since 0.32 the check runs on the POST-rewrite URL, and ADR
    * 0010 rewrites that authority to the instance's LAN address, which is plain
    * http. So `defaultManager` now refuses every package fetch.
    *
    * `.fh/base.pkl` is where the widening is declared — one source of truth,
    * scoped to that one instance, and the same field the `pkl` CLI and pkl-lsp
    * read. The evaluator gets it for free via `applyFromProject`; only
    * `PackageResolver` takes its manager as an argument, so this lifts the
    * project's own lists into one for it.
    *
    * Nothing else needs it. [[importSet]]'s `Analyzer` fetches nothing (it is
    * built with `HttpClient.dummyClient`) and reads the cache through
    * `package:`/`projectpackage:`, both of which pkl already allows by default.
    */
  def securityManagerFor(project: Project): org.pkl.core.SecurityManager =
    serialized {
      val builder = EvaluatorBuilder.preconfigured()
      builder.applyFromProject(project)
      securityManagerFrom(builder)
    }

  /** The same manager, from a builder that has already had `applyFromProject`
    * run on it — so pkl decides the two lists exactly once.
    *
    * `getSecurityManager` is null here: the builder holds the pattern LISTS and
    * only materializes a manager inside `build()`. Those lists already carry
    * pkl's defaults when the manifest declares none (verified both ways), which
    * is why nothing falls back by hand.
    */
  private def securityManagerFrom(
      builder: EvaluatorBuilder
  ): org.pkl.core.SecurityManager =
    SecurityManagers
      .standardBuilder()
      // standardBuilder() starts EMPTY — the defaults are not implied.
      .addAllowedModules(builder.getAllowedModules)
      .addAllowedResources(builder.getAllowedResources)
      .build()

  /** The workspace's `PklProject`, loaded — or `None` for the plain-eval path
    * (a bare `.pkl` with relative imports and no manifest).
    */
  private def loadProject(dashboardsDir: os.Path): Option[Project] = {
    val projectFile = dashboardsDir / "PklProject"
    Option.when(os.exists(projectFile))(Project.loadFromPath(projectFile.toNIO))
  }

  /** Write `PklProject.deps.json` if it is stale, so `applyFromProject` can
    * resolve the `@fh-dashboard` alias.
    *
    * **This must happen BEFORE `evaluate`, and the lockfile has to be a FILE.**
    * The evaluator will not produce one: it errors with "attempting to load
    * `PklProject.deps.json`" when it is missing, and there is no way to hand it
    * the resolved set in memory — `EvaluatorBuilder` accepts only
    * `DeclaredDependencies`, and `ProjectDeps` (what `resolve()` returns)
    * exposes nothing but `parse(Path)` and `writeTo(OutputStream)`. We want it
    * on disk regardless: pkl-lsp, the `pkl` CLI and `fh` all read it.
    *
    * The split is version SELECTION, not network access — evaluation is not
    * offline-by-construction. A dependency that is locked but missing from the
    * cache IS fetched during eval (verified), which is why the manifest's
    * settings have to reach the evaluator too.
    *
    * `builder` must already have had `applyFromProject` run on it, and supplies
    * the manager and http client. That indirection exists because there is no
    * `applyFromProject` on the resolver side: `ProjectDependenciesResolver`
    * takes the `Project` but does not use it to configure the `PackageResolver`
    * it is handed, whose one factory never sees a project. Harvesting the
    * builder makes pkl derive those settings ONCE, instead of us re-deriving
    * from the manifest — the same wiring the CLI does by hand, and the same it
    * OMITS in `project resolve <dir>` mode
    * (docs/issue-report-1-pkl-cli-http-rewrites-project-resolve.md).
    *
    * Resolution touches the network only for a REMOTE dependency not already in
    * the cache: local deps read files, and a cached remote version satisfies
    * the resolver without a request, so add-on boots stay offline-safe. An
    * uncached remote dep is fetched for real, honoring the manifest\'s own
    * `http.rewrites`. If that fails (offline, dead registry) the error
    * propagates into the entry\'s build error verbatim — pkl names the package
    * URI — and the resolve-before-write order keeps the previous lockfile
    * intact.
    */
  private def ensureLockfile(
      dashboardsDir: os.Path,
      project: Project,
      builder: EvaluatorBuilder
  ): Unit = {
    val depsJson = dashboardsDir / "PklProject.deps.json"
    if (staleLockfile(dashboardsDir, depsJson)) {
      val resolver = new ProjectDependenciesResolver(
        project,
        PackageResolver.getInstance(
          securityManagerFrom(builder),
          builder.getHttpClient,
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
  /** [[importSet]] WITHOUT evaluating: the local `*.pkl` files `entryFile`
    * reads, from static analysis alone. Cheap (no evaluation, no HA), so it can
    * answer a request — the editor asks it after a write to say whether the
    * file it just saved is one the site actually reads.
    *
    * Glob imports are resolved here too (`import*("*.dashboard.pkl")` returns
    * each matched file), so a dashboard named by convention counts as read.
    *
    * **Never throws, and errs toward "read".** Loading the project can fail on
    * a workspace mid-edit, and [[importSet]] already answers a failed analysis
    * with the conservative all-`*.pkl` superset. Both directions matter for the
    * caller: a false "nothing reads this file" is a confident wrong answer that
    * would send an author looking for a bug in their own file, whereas a false
    * "read" only withholds a hint.
    */
  def fileImports(dashboardsDir: os.Path, entryFile: String): Set[os.Path] =
    serialized {
      importSet(
        dashboardsDir,
        dashboardsDir / os.SubPath(entryFile),
        Try(loadProject(dashboardsDir)).toOption.flatten
      )
    }

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
        // Defaults suffice: this analyzer fetches nothing (dummyClient below)
        // and reads the cache through package:/projectpackage:, both allowed by
        // default. Verified by making the fallback below fatal — the precise
        // path still succeeded for every workspace in the suite.
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
