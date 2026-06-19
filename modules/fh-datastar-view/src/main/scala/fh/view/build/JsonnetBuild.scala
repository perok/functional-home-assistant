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

  /** Evaluate `entryFile` (relative to `dashboardsDir`), resolving imports from
    * that directory. Returns the evaluated JSON or a jsonnet error string.
    */
  def eval(dashboardsDir: os.Path, entryFile: String): Either[String, Json] = {
    val entry = dashboardsDir / entryFile
    val importer = SjsonnetMain.resolveImport(Seq(OsPath(dashboardsDir)), None)
    val interpreter = new Interpreter(
      Map.empty[String, String], // ext vars: none — jsonnet is pure composition
      Map.empty[String, String], // top-level args: none
      OsPath(dashboardsDir),
      importer,
      new DefaultParseCache,
      Settings.default
    )

    interpreter
      .interpret(os.read(entry), OsPath(entry))
      .flatMap { ujsonValue =>
        parser.parse(ujsonValue.render()).left.map(_.message)
      }
  }
}
