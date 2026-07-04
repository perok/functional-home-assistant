package fh.view.build

import io.circe.parser
import org.pkl.core.evaluatorSettings.TraceMode
import org.pkl.core.http.HttpClient
import org.pkl.core.module.ModuleKeyFactories
import org.pkl.core.{
  Analyzer,
  Evaluator,
  ModuleSource,
  SecurityManagers,
  StackFrameTransformers,
  ValueRenderers
}

import java.io.StringWriter

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** In-process Pkl evaluation for the build phase (the `.pkl` counterpart of
  * [[JsonnetBuild]]).
  *
  * The evaluated module is rendered to JSON here (Java-side
  * `ValueRenderers.json` with `omitNullProperties = true`, so absent optional
  * fields decode as `None` rather than JSON nulls) — entry modules need no
  * `output { renderer = ... }` block; like jsonnet, an entry just IS its data.
  * This runs once at build/startup/reload — never on the live hot path.
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
      val evaluator = Evaluator.preconfigured()
      val module =
        try evaluator.evaluate(ModuleSource.path(entry.toNIO))
        finally evaluator.close()
      val writer = new StringWriter
      ValueRenderers.json(writer, "  ", true).renderDocument(module)
      parser.parse(writer.toString).left.map(_.message).map { json =>
        SourceEval.Result(json, importSet(dashboardsDir, entry))
      }
    } catch {
      case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
    }
  }

  /** The entry's transitive imports as `file:` paths under `dashboardsDir`.
    *
    * Uses pkl-core's static analyzer (`Analyzer.importGraph`): the graph's
    * module set (the `imports` map keys, plus resolved targets) is every module
    * the entry pulls in. We keep only `file:` modules under the dashboards dir
    * (dropping `pkl:`/`package:`/`https:` stdlib and remote imports, which are
    * not local files to watch). On any failure — or an empty result — we fall
    * back to the conservative superset (every `*.pkl` under the dir); the entry
    * is always included regardless.
    */
  private def importSet(
      dashboardsDir: os.Path,
      entry: os.Path
  ): Set[os.Path] = {
    val precise = Try {
      val analyzer = new Analyzer(
        StackFrameTransformers.defaultTransformer,
        false,
        SecurityManagers.defaultManager,
        java.util.List
          .of(ModuleKeyFactories.standardLibrary, ModuleKeyFactories.file),
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
