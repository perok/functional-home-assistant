package fh.view.testkit

import fh.view.build.{DashboardBuild, PklDump, Site, SourceEval}
import fh.view.model.Dashboard
import io.circe.Json

/** A real [[Dashboard]] from an inline Pkl entry through the Tier-A path (ADR
  * 0009): `.pkl` -> `SourceEval.eval` -> `DashboardBuild.hoistInlineSurfaces`
  * -> decode, over a staged package-form workspace with the caller's dump
  * (typically [[HouseFixture.transformedDump]]) seeded as `@fh-home`. Entries
  * usually set [[dummyTheme]], leaving CSS to the browser suites.
  */
object PklFixture {

  /** The wire JSON before hoist/decode, plus the transitive import set
    * `Dashboard.validate`'s literal locator needs.
    */
  case class Built(value: Json, imports: Set[os.Path])

  /** Every [[fh.view.model.Theme]] field empty. Use as `theme = <this>` after
    * `import "@fh-dashboard/theme.pkl" as th`: the alias keeps `th.Theme` the
    * module identity the base expects, where a file import would be a distinct
    * URI.
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

  /** Resolves `@fh-dashboard`/`@fh-home` from the cache as the live server
    * does, and throws with the pipeline's error, so a broken fixture fails at
    * the call site.
    */
  def eval(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Built = {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp, PklDump.render(dump))

    val entryFile = s"$slug.pkl"
    // Bootstrap may have seeded a starter `site.pkl`, overwritten when slug is
    // "dashboard".
    os.write.over(tmp / entryFile, entrySource)

    val result = SourceEval
      .eval(tmp, entryFile)
      .fold(err => sys.error(s"Pkl eval failed for $entryFile: $err"), identity)
    Built(result.value, result.imports)
  }

  /** Hoisting inline surfaces first, as the build phase does. */
  def buildDashboard(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Dashboard = {
    val built = eval(slug, entrySource, dump)
    decodeDashboard(slug, built.value)
  }

  /** For a fixture that is a site (ADR 0021), the shipped starter above all. */
  def buildSiteDashboard(
      slug: String,
      entrySource: String,
      dump: Json = HouseFixture.transformedDump
  ): Dashboard = {
    val built = eval(Site.EntryFile.stripSuffix(".pkl"), entrySource, dump)
    decodeDashboard(
      slug,
      built.value.hcursor
        .downField(Site.DashboardsKey)
        .downField(slug)
        .focus
        .getOrElse(sys.error(s"the site names no dashboard '$slug'"))
    )
  }

  private def decodeDashboard(slug: String, value: Json): Dashboard =
    DashboardBuild
      .hoistInlineSurfaces(value)
      .as[Dashboard]
      .fold(
        err => sys.error(s"decoding $slug as Dashboard failed: $err"),
        _.copy(slug = slug)
      )
}
