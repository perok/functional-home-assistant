package fh.view.build

import org.pkl.core.SecurityManager as PklSecurityManager
import org.pkl.core.module.{
  ModuleKey,
  ModuleKeyFactory,
  ResolvedModuleKey,
  ResolvedModuleKeys
}

import java.net.URI
import java.util.Optional

/** Serves the two fh-owned Pkl artifacts — the static domain schema
  * (`hass.pkl`) and the live per-home dump (`dump.pkl`) — as source text keyed
  * by their served module name.
  *
  * ONE source of truth for two consumers: the `/system/pkl/` HTTP route (for
  * pkl-lsp / the `/edit` editor / remote authors, which fetch for real) and the
  * in-server import interception ([[SystemPkl.Factory]], for the server's own
  * eval). Because the server resolves its own `http://…/system/pkl/…` imports
  * from memory, it never HTTP-fetches from itself — no bootstrap cycle, and the
  * offline build/test paths keep working. See ADR 0010.
  */
trait SystemPkl {

  /** Source text for a served module by file name (e.g. `"hass.pkl"`), or a
    * `Left` reason (surfaced in the route's error body) when the name is not
    * one this home serves.
    */
  def module(name: String): Either[SystemPkl.ErrorString, String]

  /** A resolved-package artifact by cache file name — the metadata JSON for
    * `"<name>@<version>"`, the module zip for `"<name>@<version>.zip"` — or a
    * `Left` reason when this home's package cache has no such artifact. Backs
    * the `/system/pkl/packages/` route (ADR 0010): a laptop workspace without a
    * repo checkout points `package://fh.invalid/…` at it with one
    * `http.rewrites` line and resolves the SAME artifacts the instance
    * evaluates (same sha256 pin). Serves whatever versions the cache holds, so
    * a laptop pinned to an older lib than the instance still resolves.
    */
  def packageArtifact(
      file: String
  ): Either[SystemPkl.ErrorString, Array[Byte]] =
    Left("this home serves no packages")

  /** The discovery document for `GET /system/pkl/packages` (no file name): the
    * current version + metadata sha256 of the packages this home serves — what
    * `fh pull` reads before rewriting the laptop's pins. `Left` when this home
    * cannot package (path-form workspace, or no dump yet).
    */
  def packagesIndex: Either[SystemPkl.ErrorString, String] =
    Left("this home has no packages to serve (no dump yet)")
}

object SystemPkl {

  /** A human-readable reason a lookup could not be served, returned as the
    * `Left` of every [[SystemPkl]] result so the HTTP route can put it in the
    * error response body (rather than a bare 404).
    */
  type ErrorString = String

  /** The URL path prefix under which the live home serves its Pkl artifacts. */
  val Prefix: String = "/system/pkl/"

  /** The served module file name for a `…/system/pkl/<name>` http(s) URI, if it
    * is one (e.g. `http://home/system/pkl/dump.pkl` → `"dump.pkl"`). `None` for
    * any other URI, so [[Factory]] falls through to the normal factories.
    */
  def moduleName(uri: URI): Option[String] = {
    val scheme = uri.getScheme
    val path = uri.getPath
    if (
      (scheme == "http" || scheme == "https") && path != null && path
        .startsWith(Prefix)
    )
      Some(path.substring(Prefix.length)).filter(_.nonEmpty)
    else None
  }

  /** Build a provider from the static schema text and a (dynamic) dump text.
    * Both are by-name so the dump can be re-read from a live `Ref` per call.
    */
  def apply(hass: => Option[String], dump: => Option[String]): SystemPkl = {
    case "hass.pkl" => hass.toRight("hass.pkl is not available")
    case "dump.pkl" => dump.toRight("dump.pkl is not available")
    case name       => Left(s"no module named '$name'")
  }

  /** Serves nothing (every lookup is a `Left` → the route errors, the factory
    * falls through). The default for callers that don't wire a live home.
    */
  val empty: SystemPkl = apply(None, None)

  /** Serve the artifacts straight off disk: the schema from the `@fh-dashboard`
    * library (`lib/hass.pkl` — present in the repo layout; a package-form
    * add-on workspace has no `lib/`, so that name 404s there and the packages
    * route is the replacement), the dump from the `@fh-home` package
    * ([[DashboardBuild.DumpPath]]), and package artifacts from this workspace's
    * package cache (`PklBuild.workspaceCacheDir` — the same cache the server
    * evaluates from, pre-seeded by `LibPackage` on the add-on). Reads are
    * by-name (per lookup), so `dump.pkl` reflects the latest
    * `DashboardBuild.prepareDumps` write without any cache to invalidate.
    */
  def fromDisk(dashboardsDir: os.Path): SystemPkl = {
    def read(p: os.Path): Option[String] = Option.when(os.exists(p))(os.read(p))
    // Computed once: the manifest's `moduleCacheDir` does not move at runtime.
    val cache = PklBuild.workspaceCacheDir(dashboardsDir)
    new SystemPkl {
      def module(name: String): Either[ErrorString, String] =
        name match {
          case "hass.pkl" =>
            read(dashboardsDir / "lib" / "hass.pkl").toRight(
              "hass.pkl is not served here (no lib/ in this workspace) — " +
                "resolve the schema via the packages route"
            )
          case "dump.pkl" =>
            read(DashboardBuild.dumpPath(dashboardsDir))
              .toRight("no dump.pkl yet — this home has not been built")
          case _ => Left(s"no module named '$name'")
        }
      override def packagesIndex: Either[ErrorString, String] =
        DumpPackage
          .index(dashboardsDir)
          .toRight("this home has no packages to serve (no dump yet)")
      override def packageArtifact(
          file: String
      ): Either[ErrorString, Array[Byte]] = {
        val (base, suffix) =
          if (file.endsWith(".zip")) (file.dropRight(4), ".zip")
          else (file, ".json")
        // `base` is one path segment (the router splits on `/`), but reject
        // anything outside the artifact-name shape regardless — this indexes
        // into the filesystem.
        if (!ArtifactBase.matches(base)) Left(s"invalid artifact name: $file")
        else {
          val path =
            cache / "package-2" / LibPackage.Host / base / s"$base$suffix"
          Either.cond(
            os.exists(path),
            os.read.bytes(path),
            s"no such artifact: $file"
          )
        }
      }
    }
  }

  /** `<name>@<version>` as it appears in the cache layout — conservative
    * charset, no separators, so it can never escape the cache dir.
    */
  private val ArtifactBase =
    """[A-Za-z0-9][A-Za-z0-9._+-]*@[A-Za-z0-9][A-Za-z0-9._+-]*""".r

  /** An in-memory [[ModuleKey]] backing an intercepted `/system/pkl/…` URI.
    * `hasHierarchicalUris = true` lets a relative `import "hass.pkl"` inside
    * the served `dump.pkl` resolve to the sibling `…/system/pkl/hass.pkl`,
    * which [[Factory]] intercepts too.
    */
  private final class Key(uri: URI, text: String) extends ModuleKey {
    def getUri: URI = uri
    def resolve(sm: PklSecurityManager): ResolvedModuleKey =
      ResolvedModuleKeys.virtual(this, uri, text, true)
    def hasHierarchicalUris: Boolean = true
    def isGlobbable: Boolean = false
    override def isLocal: Boolean = false
    override def isCached: Boolean = true
  }

  /** A [[ModuleKeyFactory]] that intercepts `/system/pkl/` http(s) imports,
    * serving them from `system` in memory. Register it AHEAD of the built-in
    * `http` factory. An unrecognized name under the prefix falls through
    * (empty) so the real http factory can still fetch/404 it.
    */
  final class Factory(system: SystemPkl) extends ModuleKeyFactory {
    def create(uri: URI): Optional[ModuleKey] =
      moduleName(uri).flatMap(system.module(_).toOption) match {
        case Some(text) => Optional.of(new Key(uri, text))
        case None       => Optional.empty()
      }
  }
}
