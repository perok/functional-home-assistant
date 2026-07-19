package fh.view.build

/** A resolved Pkl package coordinate — `package://<Host>/<name>@<version>` —
  * and the single source of truth for every string derived from it: the package
  * uri, the `name@version` base, the cache file names, the on-disk cache-entry
  * dir, and the content-version scheme. It replaces the handful of verbatim
  * re-interpolations and re-derivations that had accumulated across the build
  * package (`LibPackage`, `DumpPackage`, `Pins`, `SystemPkl`) — one place now
  * owns the wire shape, so it cannot drift between the seeder, the served
  * artifacts, and the pin reader (ADR 0010).
  */
case class PackageRef(name: String, version: String) {

  /** The `package://…` uri a `dependencies` block / pin declares. */
  def uri: String = s"package://${PackageRef.Host}/$name@$version"

  /** `name@version` — the single path segment pkl's cache addresses a package
    * by (also the served artifact file's stem).
    */
  def base: String = s"$name@$version"

  def zipName: String = s"$base.zip"

  def jsonName: String = s"$base.json"

  /** Where pkl caches this package's artifacts. */
  def entryDir(cacheDir: os.Path): os.Path =
    PackageRef.entryDir(cacheDir, base)
}

object PackageRef {

  /** The placeholder package host. RFC 2606 reserves `.invalid`; `.local` would
    * be mDNS and real on an HA LAN. Never contacted by the add-on (the cache is
    * pre-seeded); a laptop maps it to its own instance with one
    * `evaluatorSettings.http.rewrites` line.
    */
  val Host = "fh.invalid"

  private val UriRe =
    """package://[^/]+/([^@/]+)@([^@/]+)$""".r.unanchored

  /** Parse a `package://<host>/<name>@<version>` uri into its coordinate. The
    * one regex, replacing the verbatim per-name duplicates that used to live in
    * `LibPackage.effectivePin` and `Pins.versionOf`.
    */
  def parse(uri: String): Option[PackageRef] =
    UriRe.findFirstMatchIn(uri).map(m => PackageRef(m.group(1), m.group(2)))

  /** The content-derived version scheme — `<base>-g<hash-prefix>` — owned in
    * one place ([[LibPackage.build]] and [[DumpPackage.build]] both mint
    * through here). Same content bytes ⇒ same version everywhere.
    */
  def contentVersion(baseVersion: String, hash: String): String =
    s"$baseVersion-g${hash.take(12)}"

  /** The cache dir holding a package's artifacts, addressed by its
    * `name@version` base string (`package-2` is pkl's cache-format tag,
    * observed on 0.31.1). The one definition of the `package-2/<host>/<base>`
    * layout — absorbed both `LibPackage.entryDir` and the re-inlined copy in
    * `SystemPkl.packageArtifact`.
    */
  def entryDir(cacheDir: os.Path, base: String): os.Path =
    cacheDir / "package-2" / Host / base
}
