package fh.view.build

import io.circe.Json
import io.circe.parser.parse
import org.pkl.core.project.Project

import scala.jdk.CollectionConverters.*

/** The state a pkl-lsp project sync leaves (`.pkl-lsp/projects.json`), from
  * what pkl-core has already resolved: the declared dependencies, the lockfile,
  * the cache. Its sync would run the `pkl` CLI the add-on does not ship; with
  * this file, `/edit`'s pkl-lsp loads the project on start.
  *
  * The format is pkl-lsp's (`myType`, `package://` where the lockfile says
  * `projectpackage://`): `PklEditorSupportSuite` fails when a pkl-lsp bump
  * moves it.
  */
private[view] object PklLspProject {

  /** After the lockfile, whenever it is newer. Editor state only: a failure
    * here never fails the build it rides on.
    */
  def refresh(
      dashboardsDir: os.Path,
      project: Project,
      cache: os.Path
  ): Unit = {
    val lockfile = dashboardsDir / "PklProject.deps.json"
    val state = dashboardsDir / ".pkl-lsp" / "projects.json"
    if (
      os.exists(lockfile) &&
      (!os.exists(state) || os.mtime(lockfile) > os.mtime(state))
    ) {
      val _ = scala.util.Try {
        write(dashboardsDir, project, cache)
        linkCache(cache)
      }
    }
  }

  /** What to hand pkl-lsp as `XDG_CACHE_HOME`. It finds packages only under
    * `$XDG_CACHE_HOME/pkl`, never through the project's `moduleCacheDir`, and
    * the add-on's cache is `/data/pkl-cache`: this directory's `pkl` links to
    * it. Beside the cache, not in the workspace, since pkl-lsp keeps its own
    * files under it too.
    */
  def cacheHome(cache: os.Path): os.Path = cache / os.up / "pkl-lsp-xdg"

  private def linkCache(cache: os.Path): Unit = {
    val link = cacheHome(cache) / "pkl"
    if (!os.isLink(link) || os.readLink.absolute(link) != cache) {
      os.makeDir.all(link / os.up)
      val _ = os.remove(link)
      os.symlink(link, cache)
    }
  }

  private def write(
      dashboardsDir: os.Path,
      project: Project,
      cache: os.Path
  ): Unit = {
    val lockfile = dashboardsDir / "PklProject.deps.json"
    val resolved = parse(os.read(lockfile)).toOption
      .flatMap(_.hcursor.downField("resolvedDependencies").focus)
      .getOrElse(Json.obj())
      .mapObject(_.mapValues(_.mapObject { dep =>
        // kotlinx reads the discriminator only as the first key.
        val rest = dep.remove("type").toList.map {
          case ("uri", u) =>
            "uri" -> u.asString
              .fold(u)(s => Json.fromString(s.stripPrefix("project")))
          case kv => kv
        }
        io.circe.JsonObject.fromIterable(
          dep("type").map("myType" -> _).toList ++ rest
        )
      }))
    val declared = project.getDependencies.remoteDependencies.asScala.toList
      .sortBy(_._1)
      .map((name, dep) => name -> Json.fromString(dep.getPackageUri.toString))
    val state = Json.obj(
      "schemaVersion" -> Json.fromInt(1),
      "pklProjects" -> Json.arr(
        Json.obj(
          "projectFile" -> Json.fromString("PklProject"),
          "packageName" -> Json.Null,
          "packageUri" -> Json.Null,
          "declaredDependencies" -> Json.obj(declared*),
          "resolvedDependencies" -> resolved,
          "evaluatorSettings" -> Json.obj(
            "moduleCacheDir" -> Json.fromString(cache.toString)
          )
        )
      )
    )
    val dir = dashboardsDir / ".pkl-lsp"
    os.write.over(dir / "projects.json", state.spaces2, createFolders = true)
    // As pkl-lsp's own sync leaves it: the state is per machine.
    os.write.over(dir / ".gitignore", "*\n")
  }
}
