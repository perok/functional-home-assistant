package fh.view.testkit

import fh.view.build.{AddonBootstrap, DumpPackage, LibPackage}

/** A package-form workspace (ADR 0010) through the production
  * [[AddonBootstrap]]: `lib/` seeded as the `@fh-dashboard` package,
  * `.fh/base.pkl` and the consumer `PklProject` binding the aliases, and a dump
  * seeded so `@fh-home` always resolves.
  */
object PklWorkspace {

  private val resourcesDashboards =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

  /** What a probe copies for a relative `import "lib/<name>"`, and what
    * bootstrap packages into the cache.
    */
  val resourcesLib: os.Path = resourcesDashboards / "lib"

  /** Plus the `.fh/machine.json` a reader writes for itself: the instance takes
    * its two values from `FH_PKL_CACHE_DIR`/`FH_INSTANCE_URL`, and a test
    * cannot set env vars per case. Without it the workspace would resolve
    * through the developer's own `~/.pkl/cache`.
    */
  def bootstrapInto(
      ws: os.Path,
      bundled: LibPackage.Artifacts,
      cache: os.Path
  ): List[String] = {
    val log = AddonBootstrap.run(ws, bundled, cache)
    os.write.over(
      ws / ".fh" / "machine.json",
      AddonBootstrap
        .machineFileJson(Some(cache), Some("http://127.0.0.1:8080")),
      createFolders = true
    )
    log
  }

  /** `dumpText` matters only if a probe uses `@fh-home/dump.pkl`. Returns the
    * isolated cache dir the workspace's `moduleCacheDir` points at.
    */
  def bootstrap(
      tmp: os.Path,
      dumpText: String = "// no entities\n"
  ): os.Path = {
    os.makeDir.all(tmp)
    val cache = os.temp.dir()
    // The server streams the same bytes from the jar via BundledLib.
    val bundled = LibPackage.build(resourcesLib)
    val _ = PklWorkspace.bootstrapInto(tmp, bundled, cache)
    // No pins.json yet, so the bundled lib pins the dump's `@fh-dashboard`.
    val _ = DumpPackage.seedFromText(tmp, dumpText, Some(bundled))
    cache
  }

  /** The test equivalent of a dump refresh. */
  def seedDump(tmp: os.Path, dumpText: String): Unit = {
    val _ = DumpPackage.seedFromText(tmp, dumpText)
  }
}
