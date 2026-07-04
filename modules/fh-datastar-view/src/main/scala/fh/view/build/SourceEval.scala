package fh.view.build

import io.circe.Json

/** The authoring-language seam: evaluate a dashboard entry file into JSON,
  * dispatching on the file extension (`.jsonnet` → sjsonnet, `.pkl` →
  * pkl-core). Everything downstream (normalize/hoist/decode/validate) is
  * source-agnostic and consumes the [[Result]].
  */
object SourceEval {

  /** Evaluation result: the JSON plus every file that was read to produce it
    * (the entry and its transitive imports), so callers can watch them.
    */
  case class Result(value: Json, imports: Set[os.Path])

  /** Evaluate `entryFile` (relative to `dashboardsDir`) with the evaluator its
    * extension selects. Returns the evaluated JSON + import set, or an error
    * string.
    *
    * The pure-`Either` signature belies real work: this reads files and runs
    * sjsonnet/pkl-core eagerly when called. Callers must therefore suspend it —
    * `DashboardBuild` invokes it inside `IO.blocking` so the evaluation happens
    * when the IO runs, on the blocking pool.
    */
  def eval(
      dashboardsDir: os.Path,
      entryFile: String
  ): Either[String, Result] =
    if (entryFile.endsWith(".jsonnet"))
      JsonnetBuild.eval(dashboardsDir, entryFile)
    else if (entryFile.endsWith(".pkl")) PklBuild.eval(dashboardsDir, entryFile)
    else
      Left(
        s"unsupported dashboard source (expected .jsonnet or .pkl): $entryFile"
      )

  /** Best-effort locator from a (post-evaluation) string literal back to where
    * it appears in the authoring sources.
    *
    * Evaluation erases source positions, but an author-written literal (e.g. a
    * transform expression) survives verbatim into the value, so we can grep the
    * sources for it. Returns `file:line` for the first match (or, for a value
    * spanning lines, a match on its first line). `None` when not found as a
    * literal — e.g. it was assembled via string concatenation. Reads each
    * source once; intended for the cold validation path, not the hot path.
    */
  def literalLocator(sources: Set[os.Path]): String => Option[String] = {
    // Skip the generated dumps (large, never a transform source) and files in
    // neither authoring language.
    val files = sources.toList
      .filter { p =>
        p.last.endsWith(".jsonnet") || p.last.endsWith(".libsonnet") ||
        p.last.endsWith(".pkl")
      }
      .filterNot(p => p.last == "dump.libsonnet" || p.last == "dump.pkl")
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
