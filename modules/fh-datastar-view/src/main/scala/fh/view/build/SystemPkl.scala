package fh.view.build

/** Serves a live home's fh-owned Pkl artifacts over `/system/pkl/…`: the
  * per-home dump (`dump.pkl`) as a module, and the resolved package artifacts
  * (`@fh-dashboard`, `@fh-home`) the packages route hands to laptops. The
  * domain schema (`hass.pkl`) is NOT a standalone module on a live server — it
  * ships inside the `@fh-dashboard` package, so [[fromDisk]] directs that name
  * at the packages route. (The in-memory [[apply]] provider still answers
  * `hass.pkl`/`dump.pkl` by name — it backs unit tests, not a live home.)
  *
  * This is a pure SERVING surface for external consumers: the `fh` script,
  * pkl-lsp, the `/edit` editor, remote authors — that fetch `/system/pkl/…` for
  * real. The server's OWN eval never imports over http (entries + the dump
  * resolve `@fh-dashboard`/`@fh-home` offline from the seeded cache packages),
  * so there is no self-import cycle to break and nothing intercepts its
  * imports. See ADR 0010.
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
    * cannot package (the in-memory provider serves modules only, not packages).
    */
  def packagesIndex: Either[SystemPkl.ErrorString, String] =
    Left("this home serves no packages")
}

object SystemPkl {

  /** A human-readable reason a lookup could not be served, returned as the
    * `Left` of every [[SystemPkl]] result so the HTTP route can put it in the
    * error response body (rather than a bare 404).
    */
  type ErrorString = String

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

  /** Serve the artifacts straight off a live (package-form) workspace: the
    * `@fh-home` dump as the `dump.pkl` module — extracted from the currently
    * PINNED dump package in the cache (there is no loose `home/dump.pkl`; the
    * dump is only ever a package, ADR 0010) — and the resolved package
    * artifacts from this workspace's package cache
    * (`PklBuild.workspaceCacheDir` — the same cache the server evaluates from,
    * seeded by `DumpPackage` on every dump render). Reads are by-name (per
    * lookup), so `dump.pkl` reflects the latest pin without any cache to
    * invalidate.
    *
    * The schema (`hass.pkl`) is NOT a standalone module here: it lives inside
    * the `@fh-dashboard` package, so consumers (the `fh` script, pkl-lsp)
    * resolve it through the packages route, never `/system/pkl/hass.pkl`. That
    * name gets a `Left` pointing there.
    */
  def fromDisk(dashboardsDir: os.Path): SystemPkl = {
    // Computed once: the manifest's `moduleCacheDir` does not move at runtime.
    val cache = PklBuild.workspaceCacheDir(dashboardsDir)
    new SystemPkl {
      def module(name: String): Either[ErrorString, String] =
        name match {
          case "dump.pkl" =>
            Pins.homeVersion(dashboardsDir) match {
              case None =>
                Left("no dump yet — this home has not been built")
              case Some(version) =>
                val ref = PackageRef(DumpPackage.Name, version)
                readZipEntry(ref.entryDir(cache) / ref.zipName, "dump.pkl")
                  .toRight(s"dump package $version is not in the cache")
            }
          case "hass.pkl" =>
            Left(
              "hass.pkl is not a standalone module — it ships inside the " +
                "@fh-dashboard package; resolve it via the packages route"
            )
          case _ => Left(s"no module named '$name'")
        }
      // On a live server this is `Some` by construction — `bootstrap` seeds the
      // lib package and `prepareDumps` writes the dump before any route serves,
      // both inside the startup Resource. A `Left` here is therefore an internal
      // invariant break (a fromDisk provider pointed at an un-bootstrapped
      // workspace), not a state a correctly-started home can reach.
      override def packagesIndex: Either[ErrorString, String] =
        DumpPackage
          .index(dashboardsDir)
          .toRight(
            "internal: package index unavailable — this workspace was not " +
              "bootstrapped (no lib package seeded / no dump written)"
          )
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
          val path = PackageRef.entryDir(cache, base) / s"$base$suffix"
          Either.cond(
            os.exists(path),
            os.read.bytes(path),
            s"no such artifact: $file"
          )
        }
      }
    }
  }

  /** Extract one entry's text from a zip (the `dump.pkl` module out of the
    * pinned `@fh-home` package). `None` when the zip or the entry is absent.
    */
  private def readZipEntry(
      zipPath: os.Path,
      entryName: String
  ): Option[String] =
    Option
      .when(os.exists(zipPath)) {
        val zf = new java.util.zip.ZipFile(zipPath.toIO)
        try
          Option(zf.getEntry(entryName)).map { e =>
            val is = zf.getInputStream(e)
            try new String(is.readAllBytes(), "UTF-8")
            finally is.close()
          }
        finally zf.close()
      }
      .flatten

  /** `<name>@<version>` as it appears in the cache layout — conservative
    * charset, no separators, so it can never escape the cache dir.
    */
  private val ArtifactBase =
    """[A-Za-z0-9][A-Za-z0-9._+-]*@[A-Za-z0-9][A-Za-z0-9._+-]*""".r
}
