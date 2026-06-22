package fh.view.build

import io.circe.Json
import io.circe.parser
import sjsonnet.{DefaultParseCache, Interpreter, OsPath, Settings, SjsonnetMain}

/** In-process jsonnet evaluation for the build phase.
  *
  * Wraps sjsonnet so the dashboard `.jsonnet` (which imports
  * `components.libsonnet` and the generated `dump.libsonnet`) is evaluated to
  * JSON. This runs ONCE at build time — never on the runtime hot path.
  */
object JsonnetBuild {

  /** Evaluation result: the JSON plus every file that was read to produce it
    * (the entry and its transitive imports), so callers can watch them.
    */
  case class Result(value: Json, imports: Set[os.Path])

  /** Evaluate `entryFile` (relative to `dashboardsDir`), resolving imports from
    * that directory. Returns the evaluated JSON + import set, or an error
    * string.
    */
  def eval(
      dashboardsDir: os.Path,
      entryFile: String
  ): Either[String, Result] = {
    val entry = dashboardsDir / entryFile
    val importer = SjsonnetMain.resolveImport(Seq(OsPath(dashboardsDir)), None)
    // The parse cache is keyed by every parsed file, so after interpretation its
    // keys are the entry + all transitive imports.
    val cache = new DefaultParseCache
    val interpreter = new Interpreter(
      Map.empty[String, String], // ext vars: none — jsonnet is pure composition
      Map.empty[String, String], // top-level args: none
      OsPath(dashboardsDir),
      importer,
      cache,
      Settings.default
    )

    interpreter
      .interpret(os.read(entry), OsPath(entry))
      .flatMap { ujsonValue =>
        parser.parse(ujsonValue.render()).left.map(_.message)
      }
      .map { json =>
        val imports =
          cache.keySet.iterator.collect { case (OsPath(p), _) =>
            p
          }.toSet + entry
        Result(json, imports)
      }
  }
}
