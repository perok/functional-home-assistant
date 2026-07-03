package fh.view.build

import io.circe.parser
import org.pkl.core.{Evaluator, ModuleSource}

import scala.util.control.NonFatal

/** In-process Pkl evaluation for the build phase (the `.pkl` counterpart of
  * [[JsonnetBuild]]).
  *
  * The entry module must render itself as JSON:
  * {{{
  * output { renderer = new JsonRenderer { omitNullProperties = true } }
  * }}}
  * (`omitNullProperties` makes absent optional fields decode as `None` rather
  * than JSON nulls.) Like jsonnet, this runs once at build/startup/reload —
  * never on the live hot path.
  */
object PklBuild {

  /** Evaluate `entryFile` (relative to `dashboardsDir`). Returns the evaluated
    * JSON + import set, or an error string (Pkl errors carry their own
    * file:line carets, so the message is passed through verbatim).
    *
    * The import set is a conservative superset: every `*.pkl` under
    * `dashboardsDir` (recursively — library modules live in `lib/`), not the
    * entry's precise transitive imports. The watcher re-evaluates all entries
    * on any change anyway, so over-watching is behaviorally identical; the
    * precise pkl 0.27+ import-analysis API (`pkl:analyze` / importGraph) is a
    * noted follow-up.
    */
  def eval(
      dashboardsDir: os.Path,
      entryFile: String
  ): Either[String, SourceEval.Result] = {
    val entry = dashboardsDir / os.SubPath(entryFile)
    try {
      val evaluator = Evaluator.preconfigured()
      val text =
        try evaluator.evaluateOutputText(ModuleSource.path(entry.toNIO))
        finally evaluator.close()
      parser.parse(text).left.map(_.message).map { json =>
        val imports = os
          .walk(dashboardsDir)
          .filter(p => os.isFile(p) && p.ext == "pkl")
          .toSet + entry
        SourceEval.Result(json, imports)
      }
    } catch {
      case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
    }
  }
}
