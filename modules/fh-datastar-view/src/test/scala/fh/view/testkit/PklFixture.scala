package fh.view.testkit

import fh.view.build.{DashboardBuild, PklDump, SourceEval}
import fh.view.model.Dashboard
import io.circe.Json

/** Builds a real [[Dashboard]] from an inline Pkl entry through the genuine
  * authoring pipeline — the Tier-A path of `plan-functional-e2e-tests.md`:
  * `.pkl` -> `SourceEval.eval` -> `DashboardBuild.hoistInlineSurfaces` ->
  * decode. It stages a temp dir exactly as `PklBuildSuite` does (the real
  * `lib` modules copied in, a generated `lib/dump.pkl` beside them), so no
  * live HA is touched; the dump is supplied by the caller (typically
  * [[HouseFixture.transformedDump]], so the dashboard and the served state come
  * from one source).
  *
  * This is the seam a functional test uses to serve a Pkl-authored dashboard,
  * and the same builder the Playwright smoke plan will reuse for its richer
  * fixture entry.
  */
object PklFixture {

  /** The real Pkl library modules, as shipped under the resources dir. `os.pwd`
    * in the test JVM is the repo root (mirroring `PklBuildSuite`).
    */
  private val dashboardsDir =
    os.pwd / "modules" / "fh-datastar-view" / "src" / "main" / "resources" / "dashboards"

  /** Every lib module an entry that `amends "lib/entry.pkl"` needs. */
  private val libModules =
    List(
      "hass.pkl",
      "components.pkl",
      "theme.pkl",
      "theme-beer.pkl",
      "tokens.pkl",
      "entry.pkl"
    )

  /** Evaluate `entrySource` (a full entry `.pkl`, e.g. one that
    * `amends "lib/entry.pkl"`) against a `lib/dump.pkl` rendered from `dump`,
    * and return the decoded [[Dashboard]] with its `slug` set. Throws with the
    * pipeline's error text if evaluation, hoisting, or decoding fails — so a
    * broken fixture fails loudly at the call site.
    */
  def buildDashboard(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Dashboard = {
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "lib")
    libModules.foreach(n => os.copy.into(dashboardsDir / "lib" / n, tmp / "lib"))
    os.write(tmp / "lib" / "dump.pkl", PklDump.render(dump))

    val entryFile = s"$slug.pkl"
    os.write(tmp / entryFile, entrySource)

    val result = SourceEval
      .eval(tmp, entryFile)
      .fold(err => sys.error(s"Pkl eval failed for $entryFile: $err"), identity)
    val hoisted = DashboardBuild.hoistInlineSurfaces(result.value)
    hoisted
      .as[Dashboard]
      .fold(
        err => sys.error(s"decoding $entryFile as Dashboard failed: $err"),
        _.copy(slug = slug)
      )
  }
}
