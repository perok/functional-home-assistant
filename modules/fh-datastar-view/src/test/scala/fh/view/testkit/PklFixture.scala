package fh.view.testkit

import fh.view.build.{DashboardBuild, PklDump, SourceEval}
import fh.view.model.Dashboard
import io.circe.Json

/** Builds a real [[Dashboard]] from an inline Pkl entry through the genuine
  * authoring pipeline — the Tier-A path (ADR 0009): `.pkl` -> `SourceEval.eval`
  * -> `DashboardBuild.hoistInlineSurfaces` -> decode. It stages a temp dir
  * exactly as `PklBuildSuite` does (the real `lib` modules copied in, a
  * generated `home/dump.pkl` in the @fh-home package), so no live HA is
  * touched; the dump is supplied by the caller (typically
  * [[HouseFixture.transformedDump]], so the dashboard and the served state come
  * from one source).
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

  /** Bootstrap a package-form workspace (the ONE resolution mode, ADR 0010)
    * with the `dump` seeded as the `@fh-home` cache package, write
    * `entrySource` as `<slug>.pkl`, and evaluate it. The entry resolves
    * `@fh-dashboard`/`@fh-home` from the cache exactly as the live server does.
    * Throws with the pipeline's error text if evaluation fails — so a broken
    * fixture fails loudly at the call site.
    */
  def eval(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Built = {
    val tmp = os.temp.dir()
    PklWorkspace.bootstrap(tmp, PklDump.render(dump))

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
