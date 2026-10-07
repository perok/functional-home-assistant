package fh.view.build

import io.circe.{Json, parser}

import java.net.URI
import java.nio.file.Paths
import java.security.MessageDigest

import scala.util.Try

/** An entry's rendered JSON on disk, so a boot with nothing changed skips an
  * evaluation that takes seconds on a Pi (issue #406). Keyed on the content of
  * every module the static import graph reaches, with workspace files named
  * relative to the workspace, so a dump refresh's staged copy and the reload
  * after the swap share one entry.
  *
  * Blind to what a module `read()`s rather than imports (an env var, a resource
  * file): the lib reads none, and a workspace that does is served the value
  * from the evaluation that wrote the entry.
  */
object SiteEvalCache {

  /** Bump when [[PklBuild.eval]] renders differently. */
  private val Format = "1"

  private val Kept = 4

  def dir(moduleCacheDir: os.Path): os.Path = moduleCacheDir / "fh-site-eval"

  def key(dashboardsDir: os.Path, modules: Set[URI]): String = {
    def fileLine(name: String, p: os.Path) =
      s"$name ${if (os.isFile(p)) sha256(os.read.bytes(p)) else "-"}"
    val moduleLines = modules.toList.map { u =>
      if (u.getScheme == "file") {
        val p = os.Path(Paths.get(u))
        fileLine(
          if (p.startsWith(dashboardsDir)) p.relativeTo(dashboardsDir).toString
          else p.toString,
          p
        )
      } else u.toString
    }
    // The lockfile decides what an `@alias` import resolves to.
    val projectLines = List("PklProject", "PklProject.deps.json")
      .map(f => fileLine(f, dashboardsDir / f))
    val header = List(
      s"format $Format",
      s"pkl ${org.pkl.core.Release.current().version()}"
    )
    sha256(
      (header ++ projectLines ++ moduleLines.sorted)
        .mkString("\n")
        .getBytes("UTF-8")
    )
  }

  def read(dir: os.Path, key: String): Option[Json] =
    Try(os.read(dir / s"$key.json")).toOption
      .flatMap(parser.parse(_).toOption)

  /** Best effort: a cache that cannot be written costs the next boot an
    * evaluation, never this one its result.
    */
  def write(dir: os.Path, key: String, json: Json): Unit = {
    val _ = Try {
      os.makeDir.all(dir)
      val tmp = os.temp(json.noSpaces, dir = dir, deleteOnExit = false)
      os.move(
        tmp,
        dir / s"$key.json",
        replaceExisting = true,
        atomicMove = true
      )
      os.list(dir)
        .filter(_.ext == "json")
        .sortBy(p => -os.mtime(p))
        .drop(Kept)
        .foreach(os.remove)
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"$b%02x")
      .mkString
}
