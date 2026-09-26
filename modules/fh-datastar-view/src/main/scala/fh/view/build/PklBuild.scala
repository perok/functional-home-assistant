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

/** In-process Pkl evaluation, rendered to JSON with nulls omitted so absent
  * optionals decode as `None`; an entry needs no `output` block.
  */
object PklBuild {

  private object Truffle

  /** The process's one claim on pkl-core. Its stdlib Truffle ASTs are shared
    * and specialize as they warm, so two concurrent evaluations can catch a
    * node mid-rewrite: an NPE deep in `pkl.semver` (#226, pkl-core 0.32.1).
    * Coverage is by call site: anything building an evaluator or loading a
    * `PklProject` must come through here.
    */
  def serialized[A](thunk: => A): A = Truffle.synchronized(thunk)

  /** Pkl errors carry their own carets and pass through verbatim. */
  def eval(
      dashboardsDir: os.Path,
      entryFile: String
  ): Either[String, SourceEval.Result] = serialized {
    val entry = dashboardsDir / os.SubPath(entryFile)
    try {
      val project = loadProject(dashboardsDir)
      // One builder, so pkl derives the manifest's settings once and the
      // resolve reuses them.
      val builder = EvaluatorBuilder.preconfigured()
      project.foreach(builder.applyFromProject)
      project.foreach(ensureLockfile(dashboardsDir, _, builder))
      // After `applyFromProject`, which leaves its own default when the
      // project declares none; the seeded packages live here.
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

  /** For `PackageResolver`, the one consumer that takes its manager as an
    * argument. Needed since pkl 0.32 checks the allowlist against the
    * post-rewrite URL, which is the instance's plain-http LAN address;
    * `.fh/base.pkl` declares the widening.
    */
  def securityManagerFor(project: Project): org.pkl.core.SecurityManager =
    serialized {
      val builder = EvaluatorBuilder.preconfigured()
      builder.applyFromProject(project)
      securityManagerFrom(builder)
    }

  /** The builder holds lists, not a manager (`getSecurityManager` is null until
    * `build()`); they already include pkl's defaults.
    */
  private def securityManagerFrom(
      builder: EvaluatorBuilder
  ): org.pkl.core.SecurityManager =
    SecurityManagers
      .standardBuilder()
      // Starts empty: defaults are not implied.
      .addAllowedModules(builder.getAllowedModules)
      .addAllowedResources(builder.getAllowedResources)
      .build()

  private def loadProject(dashboardsDir: os.Path): Option[Project] = {
    val projectFile = dashboardsDir / "PklProject"
    Option.when(os.exists(projectFile))(Project.loadFromPath(projectFile.toNIO))
  }

  /** Before `evaluate`, and as a file: the evaluator cannot be handed resolved
    * deps in memory, and pkl-lsp, the CLI and `fh` read it anyway. Selection,
    * not network isolation: a locked but uncached dep is fetched during eval.
    *
    * The resolver's `PackageResolver` never sees the project, so its manager
    * and client come from `builder` — the wiring the CLI omits in
    * `project resolve <dir>` (docs/issue-report-1-…). A failed fetch keeps the
    * previous lockfile (resolved before writing).
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
      // Before opening: `FileOutputStream` truncates.
      val resolved = resolver.resolve()
      val out = new FileOutputStream(depsJson.toNIO.toFile)
      try resolved.writeTo(out)
      finally out.close()
    }
  }

  /** Otherwise a pin bump silently serves the old pin forever (ADR 0010). */
  private def staleLockfile(
      dashboardsDir: os.Path,
      depsJson: os.Path
  ): Boolean =
    !os.exists(depsJson) || {
      val lockTime = os.mtime(depsJson)
      // Including `.fh/base.pkl` and `.fh/pins.json`, which a dump rewrites.
      os.walk(dashboardsDir, maxDepth = 2)
        .exists(p =>
          (p.last == "PklProject" ||
            ((p.last == "base.pkl" || p.last == "pins.json") &&
              (p / os.up).last == ".fh")) &&
            os.mtime(p) > lockTime
        )
    }

  /** Shared by resolver, evaluator and analyzer. None declared is the normal
    * case off the add-on; the fallback is pkl's default, as the seed uses.
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
          .getOrElse(os.Path(AddonBootstrap.defaultCacheDir))
      // No project, no package deps.
      case None => dashboardsDir / ".pkl-cache"
    }

  /** Static analysis only, cheap enough per request; glob imports included.
    * Never throws and errs toward "read": a false "unread" would send an author
    * hunting a bug in their own file.
    */
  def fileImports(dashboardsDir: os.Path, entryFile: String): Set[os.Path] =
    serialized {
      importSet(
        dashboardsDir,
        dashboardsDir / os.SubPath(entryFile),
        Try(loadProject(dashboardsDir)).toOption.flatten
      )
    }

  /** The entry's transitive `file:` imports under the workspace, by static
    * analysis; package imports (the lib, the dump) are immutable and dropped.
    * The cache dir and declared dependencies must reach the `Analyzer`, or the
    * `@` aliases do not resolve. A failed or empty analysis falls back to every
    * `*.pkl` under the dir.
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
        // Defaults suffice: it fetches nothing and reads the cache through
        // allowed schemes (verified by making the fallback fatal).
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

  // Over-watching is harmless: any change re-evaluates everything.
  private def superset(dashboardsDir: os.Path): Set[os.Path] =
    os.walk(dashboardsDir)
      .filter(p => os.isFile(p) && p.ext == "pkl")
      .toSet
}
