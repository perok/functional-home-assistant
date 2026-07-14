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
import java.util.regex.Pattern

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
      entryFile: String,
      system: Option[SystemPkl] = None
  ): Either[String, SourceEval.Result] = {
    val entry = dashboardsDir / os.SubPath(entryFile)
    try {
      val evaluator = buildEvaluator(dashboardsDir, system)
      val module =
        try evaluator.evaluate(ModuleSource.path(entry.toNIO))
        finally evaluator.close()
      val writer = new StringWriter
      ValueRenderers.json(writer, "  ", true).renderDocument(module)
      parser.parse(writer.toString).left.map(_.message).map { json =>
        SourceEval.Result(json, importSet(dashboardsDir, entry, system))
      }
    } catch {
      case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
    }
  }

  /** The module allowlist for the intercepting evaluator. Overriding the
    * preconfigured allowlist means we must re-list every scheme entries use;
    * `http:` is the addition (`preconfigured()` allows `https:` but not plain
    * `http:`, and the `/system/pkl/` endpoint is plain http — decided in the
    * plan). Interception matches by path and never touches the socket, but the
    * allowlist gate is applied to the URI scheme regardless.
    */
  private val AllowedModules: java.util.List[Pattern] =
    List(
      "repl:",
      "file:",
      "modulepath:",
      "https:",
      "http:",
      "pkl:",
      "package:",
      "projectpackage:"
    ).map(Pattern.compile).asJava

  /** The evaluator for one eval.
    *
    *   1. When `dashboardsDir` is a Pkl project (has a `PklProject`), we
    *      `applyFromProject` so entries can import the `lib/` library through
    *      the `@fh-dashboard` alias (ADR 0010, Track B). Plain relative-import
    *      evals — most unit probes, no `PklProject` — skip this and behave
    *      exactly as `Evaluator.preconfigured()`.
    *   2. When a [[SystemPkl]] is supplied, we PREPEND its intercept factory to
    *      the (now possibly project-derived) factory list so it wins over the
    *      built-in `http` factory, and widen the allowlist to admit `http:` —
    *      this keeps any residual `http://…/system/pkl/…` import resolvable and
    *      backs the public route's provider. Order matters: `applyFromProject`
    *      first, then read `getModuleKeyFactories` so the project's factories
    *      are preserved (verified composing on pkl-core 0.31.1).
    */
  private def buildEvaluator(
      dashboardsDir: os.Path,
      system: Option[SystemPkl]
  ): Evaluator = {
    val builder = EvaluatorBuilder.preconfigured()
    resolveProjectDeps(dashboardsDir).foreach(builder.applyFromProject)
    system.foreach { sys =>
      val factories =
        (new SystemPkl.Factory(sys) ::
          builder.getModuleKeyFactories.asScala.toList).asJava
      builder.setModuleKeyFactories(factories).setAllowedModules(AllowedModules)
    }
    builder.build()
  }

  /** If `dashboardsDir` is a Pkl project, load it and ensure its
    * `PklProject.deps.json` lockfile exists, so `applyFromProject` can resolve
    * the `@fh-dashboard` alias. Resolution is IN-PROCESS and network-free for
    * the local `@fh-dashboard -> lib/` mapping (`ProjectDependenciesResolver` +
    * a dummy http client — the local dependency is never fetched); the lockfile
    * is written once and reused (stable for a local dep). Returns the loaded
    * [[Project]], or `None` when there is no `PklProject` (the plain-eval
    * path).
    */
  private def resolveProjectDeps(dashboardsDir: os.Path): Option[Project] = {
    val projectFile = dashboardsDir / "PklProject"
    Option.when(os.exists(projectFile)) {
      val project = Project.loadFromPath(projectFile.toNIO)
      val depsJson = dashboardsDir / "PklProject.deps.json"
      if (!os.exists(depsJson)) {
        val resolver = new ProjectDependenciesResolver(
          project,
          PackageResolver.getInstance(
            SecurityManagers.defaultManager,
            HttpClient.dummyClient(),
            (dashboardsDir / ".pkl-cache").toNIO
          ),
          new PrintWriter(new StringWriter)
        )
        val out = new FileOutputStream(depsJson.toNIO.toFile)
        try resolver.resolve().writeTo(out)
        finally out.close()
      }
      project
    }
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
    * A shipped entry imports its library through the `@fh-dashboard` alias (ADR
    * 0010, Track B), which the file-only analyzer here cannot resolve — so it
    * throws and this collapses to the superset (every `*.pkl` under the dir).
    * That is exactly what we want for the real dashboards: the whole authoring
    * dir is watched, and `ServerApp.watchedSet` adds the `lib/` sources for
    * good measure. Precision still applies to plain FILE-import entries (e.g.
    * the unit probes), where the graph resolves and unrelated siblings drop
    * out. The `SystemPkl` factory + http-admitting security manager below keep
    * any residual `http://…/system/pkl/…` import analyzable rather than
    * throwing.
    */
  private def importSet(
      dashboardsDir: os.Path,
      entry: os.Path,
      system: Option[SystemPkl]
  ): Set[os.Path] = {
    val baseFactories =
      List(ModuleKeyFactories.standardLibrary, ModuleKeyFactories.file)
    val factories = system match {
      case Some(sys) => new SystemPkl.Factory(sys) :: baseFactories
      case None      => baseFactories
    }
    val securityManager = system match {
      case None    => SecurityManagers.defaultManager
      case Some(_) =>
        SecurityManagers.standard(
          AllowedModules,
          SecurityManagers.defaultAllowedResources,
          SecurityManagers.defaultTrustLevels,
          null
        )
    }
    val precise = Try {
      val analyzer = new Analyzer(
        StackFrameTransformers.defaultTransformer,
        false,
        securityManager,
        factories.asJava,
        null,
        null,
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
