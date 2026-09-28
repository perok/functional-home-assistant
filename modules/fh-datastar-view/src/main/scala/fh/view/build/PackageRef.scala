package fh.view.build

/** `package://<Host>/<name>@<version>` and every string derived from it (ADR
  * 0010).
  */
case class PackageRef(name: String, version: String) {

  def uri: String = s"package://${PackageRef.Host}/$name@$version"

  /** Also the served artifact's file stem. */
  def base: String = s"$name@$version"

  def zipName: String = s"$base.zip"

  def jsonName: String = s"$base.json"

  def entryDir(cacheDir: os.Path): os.Path =
    PackageRef.entryDir(cacheDir, base)
}

object PackageRef {

  /** RFC 2606 `.invalid`: `.local` is mDNS and real on an HA LAN. A laptop maps
    * it to its instance with one `http.rewrites` line.
    */
  val Host = "fh.invalid"

  private val UriRe =
    """package://[^/]+/([^@/]+)@([^@/]+)$""".r.unanchored

  def parse(uri: String): Option[PackageRef] =
    UriRe.findFirstMatchIn(uri).map(m => PackageRef(m.group(1), m.group(2)))

  def contentVersion(baseVersion: String, hash: String): String =
    s"$baseVersion-g${hash.take(12)}"

  // `package-2` is pkl's cache-format tag.
  def entryDir(cacheDir: os.Path, base: String): os.Path =
    cacheDir / "package-2" / Host / base
}
