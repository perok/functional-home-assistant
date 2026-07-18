package fh.view.testkit

import fh.view.build.{AddonBootstrap, DumpPackage}

/** Stage a **package-form** workspace for tests — the ONE resolution mode
  * (ADR 0010). Reuses the production [[AddonBootstrap]] so a suite exercises
  * exactly what the server does: the real `lib/` is seeded as a cache package
  * (`@fh-dashboard`), a static `.fh/base.pkl` + consumer `PklProject` bind the
  * aliases, and a dump package (`@fh-home`) is seeded so `@fh-home` always
  * resolves. There is no loose `home/dump.pkl` and no path-form.
  */
object PklWorkspace {

  private val resourcesDashboards =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

  /** The real shipped library modules (what a probe copies in for a plain
    * relative `import "lib/<name>"`, and what bootstrap packages into the cache
    * for the `@fh-dashboard` alias).
    */
  val resourcesLib: os.Path = resourcesDashboards / "lib"

  /** Bootstrap `tmp` to a package-form workspace and seed `dumpText` as the
    * `@fh-home` package. `dumpText` content is irrelevant unless a probe imports
    * and USES `@fh-home/dump.pkl` (then pass the real rendered dump); the default
    * just makes `@fh-home` resolvable. Returns the (isolated, absolute) cache
    * dir the workspace's `moduleCacheDir` points at.
    */
  def bootstrap(
      tmp: os.Path,
      dumpText: String = "// no entities\n"
  ): os.Path = {
    os.makeDir.all(tmp)
    val cache = os.temp.dir()
    // Empty seed dir: never seed the demo entries into a test workspace.
    val _ = AddonBootstrap.run(
      tmp,
      resourcesLib,
      os.temp.dir(),
      cache,
      loopbackUrl = "http://127.0.0.1:8080"
    )
    val _ = DumpPackage.seedFromText(tmp, dumpText)
    cache
  }

  /** Re-seed the `@fh-home` package from `dumpText` (moving the pin) — the test
    * equivalent of a dump refresh.
    */
  def seedDump(tmp: os.Path, dumpText: String): Unit = {
    val _ = DumpPackage.seedFromText(tmp, dumpText)
  }
}
