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

  /** Best-effort locator from a (post-evaluation) string literal back to where
    * it appears in the jsonnet sources.
    *
    * Jsonnet erases source positions, but an author-written literal (e.g. a
    * transform expression) survives verbatim into the value, so we can grep the
    * sources for it. Returns `file:line` for the first match (or, for a value
    * spanning lines, a match on its first line). `None` when not found as a
    * literal — e.g. it was assembled via string concatenation. Reads each
    * source once; intended for the cold validation path, not the hot path.
    */
  def literalLocator(sources: Set[os.Path]): String => Option[String] = {
    // Skip the generated dump (large, never a transform source) and non-jsonnet.
    val files = sources.toList
      .filter(p => p.last.endsWith(".jsonnet") || p.last.endsWith(".libsonnet"))
      .filterNot(_.last == "dump.libsonnet")
      .sortBy(_.last)
      .map(p => p.last -> os.read.lines(p))

    needle => {
      val probe = needle.linesIterator.find(_.trim.nonEmpty).getOrElse(needle)
      files.iterator
        .flatMap { case (name, lines) =>
          lines.iterator.zipWithIndex.collect {
            case (line, i) if line.contains(probe) => s"$name:${i + 1}"
          }
        }
        .nextOption()
    }
  }
}
