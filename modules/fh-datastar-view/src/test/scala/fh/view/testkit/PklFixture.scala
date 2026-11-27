package fh.view.testkit

import fh.view.build.{DashboardBuild, PklBuild, PklDump, Site, SourceEval}
import fh.view.model.{CardDef, Choice, Dashboard, Fill, LayoutNode, Slot}
import io.circe.{Json, parser}
import org.pkl.core.{Evaluator, EvaluatorBuilder, ModuleSource, OutputFormat}
import org.pkl.core.project.Project

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

  /** What a [[snippet]] body sees: the facade `c`, `q`, `dump`, the core
    * `nodeMod`/`groupMod`, and `t`, the test leaves (`fixture-leaves.pkl`).
    */
  val SnippetHeader: String =
    s"""amends "@fh-dashboard/entry.pkl"
       |
       |import "@fh-dashboard/components.pkl" as c
       |import "@fh-dashboard/query.pkl" as q
       |import "@fh-dashboard/theme.pkl" as th
       |import "@fh-dashboard/core/node.pkl" as nodeMod
       |import "@fh-dashboard/core/group.pkl" as groupMod
       |import "@fh-dashboard/core/simple.pkl" as simpleMod
       |import "@fh-home/dump.pkl" as dump
       |import "fixture-leaves.pkl" as t
       |
       |theme = $dummyTheme
       |""".stripMargin

  /** A library shape as the build makes it, for a test that would otherwise
    * hand-copy it: `body` follows [[SnippetHeader]] (`card = …`, `surfaces`,
    * locals), over the house plus `extras`. Cached per JVM by source, and every
    * snippet over one dump shares a workspace and an evaluator, so a warm one
    * costs a few ms where a cold [[buildDashboard]] costs about 110.
    */
  def snippet(body: String, extras: FixtureEntity*): Dashboard =
    snippets.getOrElseUpdate(
      (body, extras.toList),
      PklBuild.serialized {
        val ws = workspaces.getOrElseUpdate(extras.toList, Snippets(extras))
        decodeDashboard("fixture", ws.eval(SnippetHeader + body))
      }
    )

  /** A library host whose `t.hole(name)` nodes a test fills with nodes it
    * builds by hand (a chart, a fetch-reading leaf): the host, its cases and
    * its markup come from the build, the leaves from the test.
    */
  final case class Shape(
      cards: Map[String, CardDef],
      card: LayoutNode.Component
  ):
    def fill(holes: (String, LayoutNode)*): LayoutNode =
      Shape.plug(card, holes.toMap)

  object Shape:
    private val HolePrefix = "hole_"

    private def plug(
        n: LayoutNode,
        holes: Map[String, LayoutNode]
    ): LayoutNode =
      n match
        case c: LayoutNode.Component if c.card.startsWith(HolePrefix) =>
          val name = c.card.stripPrefix(HolePrefix)
          holes.getOrElse(name, sys.error(s"no node for the hole '$name'"))
        case c: LayoutNode.Component =>
          c.copy(slots = c.slots.view.mapValues {
            case Slot.Nodes(Fill.Nodes(ch)) =>
              Slot.Nodes(Fill.Nodes(choice(ch)(_.map(plug(_, holes)))))
            case Slot.Nodes(s: Fill.Set) =>
              Slot.Nodes(
                s.copy(members =
                  s.members.view.mapValues(choice(_)(plug(_, holes))).toMap
                )
              )
            case v => v
          }.toMap)

    private def choice[A](ch: Choice[A])(f: A => A): Choice[A] = ch match
      case Choice.Fixed(v)              => Choice.Fixed(f(v))
      case Choice.Chosen(by, cases, or) =>
        Choice.Chosen(by, cases.map(c => c.copy(value = f(c.value))), or.map(f))

    def of(d: Dashboard): Shape = d.card match
      case c: LayoutNode.Component =>
        Shape(d.cards.filterNot(_._1.startsWith(HolePrefix)), c)

  /** [[snippet]]'s `card`, as a [[Shape]]. */
  def shape(body: String, extras: FixtureEntity*): Shape =
    Shape.of(snippet(body, extras*))

  private val snippets =
    scala.collection.concurrent.TrieMap
      .empty[(String, List[FixtureEntity]), Dashboard]
  private val workspaces =
    scala.collection.concurrent.TrieMap.empty[List[FixtureEntity], Snippets]

  /** The first eval goes through [[SourceEval]], which writes the lockfile the
    * shared evaluator then resolves against.
    */
  private final class Snippets(extras: Seq[FixtureEntity]) {
    private val dir = os.temp.dir()
    private val _ =
      PklWorkspace.bootstrap(
        dir,
        PklDump.render(HouseFixture.dumpWith(extras*))
      )
    os.copy.over(
      os.pwd / "modules" / "fh-datastar-view" / "src" / "test" / "resources" /
        "fixture-leaves.pkl",
      dir / "fixture-leaves.pkl"
    )
    private var evaluator: Option[Evaluator] = None
    private var n = 0

    def eval(source: String): Json = {
      n += 1
      val file = s"snippet$n.pkl"
      os.write.over(dir / file, source)
      evaluator match {
        case Some(ev) =>
          parser
            .parse(ev.evaluateOutputText(ModuleSource.path((dir / file).toNIO)))
            .fold(e => sys.error(s"snippet JSON: $e"), identity)
        case None =>
          val json = SourceEval
            .eval(dir, file)
            .fold(
              err => sys.error(s"Pkl eval failed for snippet:\n$err"),
              _.value
            )
          val builder = EvaluatorBuilder.preconfigured()
          builder.applyFromProject(
            Project.loadFromPath((dir / "PklProject").toNIO)
          )
          builder.setModuleCacheDir(PklBuild.workspaceCacheDir(dir).toNIO)
          evaluator = Some(builder.setOutputFormat(OutputFormat.JSON).build())
          json
      }
    }
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
