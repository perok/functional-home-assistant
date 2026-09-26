package fh.view.build

import io.circe.Json

/** The authoring-language seam; Pkl is the only language. */
object SourceEval {

  /** `imports` are the entry and its transitive imports, for watching. */
  case class Result(value: Json, imports: Set[os.Path])

  /** Not pure despite the signature: it reads files and runs pkl-core, so
    * callers suspend it in `IO.blocking`.
    */
  def eval(
      dashboardsDir: os.Path,
      entryFile: String
  ): Either[String, Result] =
    if (entryFile.endsWith(".pkl"))
      PklBuild.eval(dashboardsDir, entryFile)
    else Left(s"unsupported dashboard source (expected .pkl): $entryFile")

  /** Evaluation erases positions, but an author's literal survives verbatim, so
    * grep for its first line. `None` for a concatenated one. Cold path only.
    */
  def literalLocator(sources: Set[os.Path]): String => Option[String] = {
    val files = sources.toList
      .filter(_.last.endsWith(".pkl"))
      .filterNot(_.last == "dump.pkl")
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
