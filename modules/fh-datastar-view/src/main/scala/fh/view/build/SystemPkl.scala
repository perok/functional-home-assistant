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
  * offline build/test paths keep working. See
  * `docs/plan-pkl-live-endpoint-and-deps.md`.
  */
trait SystemPkl {

  /** Source text for a served module by file name (e.g. `"hass.pkl"`), or
    * `None` when the name is not one this home serves.
    */
  def module(name: String): Option[String]
}

object SystemPkl {

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
  def apply(hass: => Option[String], dump: => Option[String]): SystemPkl =
    (name: String) =>
      name match {
        case "hass.pkl" => hass
        case "dump.pkl" => dump
        case _          => None
      }

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
      moduleName(uri).flatMap(system.module) match {
        case Some(text) => Optional.of(new Key(uri, text))
        case None       => Optional.empty()
      }
  }
}
