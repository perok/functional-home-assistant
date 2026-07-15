package fh.view.testkit

import fh.view.build.{DashboardBuild, PklDump, SourceEval}
import fh.view.model.Dashboard
import io.circe.Json

/** Builds a real [[Dashboard]] from an inline Pkl entry through the genuine
  * authoring pipeline — the Tier-A path (ADR 0009): `.pkl` -> `SourceEval.eval`
  * -> `DashboardBuild.hoistInlineSurfaces` -> decode. It stages a temp dir
  * exactly as `PklBuildSuite` does (the real `lib` modules copied in, a
  * generated `home/dump.pkl` in the @fh-home package), so no live HA is touched; the dump is
  * supplied by the caller (typically [[HouseFixture.transformedDump]], so the
  * dashboard and the served state come from one source).
  *
  * This is the seam a functional test uses to serve a Pkl-authored dashboard,
  * and the same builder `PklBuildSuite` uses to exercise the build pipeline
  * against TEST-OWNED entries (rather than the shipped dashboards, which are
  * free to evolve). Entries here typically set a dummy theme, so the theme's
  * CSS is out of scope — visual/theme coverage is the browser smoke plan's job.
  */
object PklFixture {

  /** The raw evaluated entry: the wire JSON (before hoist/decode) plus the
    * precise transitive import set (what `Dashboard.validate` needs for its
    * literal locator).
    */
  final case class Built(value: Json, imports: Set[os.Path])

  /** A trivial theme an entry can set to keep BeerCSS (and all CSS) out of the
    * build: every [[fh.view.model.Theme]] field is empty. Paste into a fixture
    * entry as `theme = <this>` after `import "@fh-dashboard/theme.pkl" as th`
    * (the alias keeps `th.Theme` the SAME module identity the base module's
    * `theme` property expects — a plain file import would be a distinct URI).
    */
  val dummyTheme: String =
    """new th.Theme {
      |  tokens = new {}
      |  tokensDark = new {}
      |  stylesheets = new {}
      |  scripts = new {}
      |  styles = ""
      |  chrome = ""
      |}""".stripMargin

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

  /** Stage the lib + a `home/dump.pkl` rendered from `dump`, write
    * `entrySource` as `<slug>.pkl`, and evaluate it. Throws with the pipeline's
    * error text if evaluation fails — so a broken fixture fails loudly at the
    * call site.
    */
  def eval(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Built = {
    val tmp = os.temp.dir()
    os.makeDir.all(tmp / "lib")
    libModules.foreach(n =>
      os.copy.into(dashboardsDir / "lib" / n, tmp / "lib")
    )
    // Stage both Pkl packages + the consumer project, so an entry resolves the
    // aliases exactly as the live server does (ADR 0010, Track B): the
    // `@fh-dashboard` manifest in `lib/`, the `@fh-home` manifest in `home/`
    // (with the generated dump inside it, where `prepareDumps` writes it), and
    // the consumer `PklProject` mapping both. `PklBuild` resolves the
    // (network-free, local) lockfile in-process.
    os.makeDir.all(tmp / "home")
    os.write(DashboardBuild.dumpPath(tmp), PklDump.render(dump))
    os.copy.into(dashboardsDir / "lib" / "PklProject", tmp / "lib")
    os.copy.into(dashboardsDir / "home" / "PklProject", tmp / "home")
    os.copy.into(dashboardsDir / "PklProject", tmp)

    val entryFile = s"$slug.pkl"
    os.write(tmp / entryFile, entrySource)

    val result = SourceEval
      .eval(tmp, entryFile)
      .fold(err => sys.error(s"Pkl eval failed for $entryFile: $err"), identity)
    Built(result.value, result.imports)
  }

  /** Evaluate `entrySource` and return the decoded [[Dashboard]] with its
    * `slug` set (hoisting inline surfaces first, as the build phase does).
    * Throws if hoisting or decoding fails.
    */
  def buildDashboard(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Dashboard = {
    val built = eval(slug, entrySource, dump)
    DashboardBuild
      .hoistInlineSurfaces(built.value)
      .as[Dashboard]
      .fold(
        err => sys.error(s"decoding $slug as Dashboard failed: $err"),
        _.copy(slug = slug)
      )
  }
}
