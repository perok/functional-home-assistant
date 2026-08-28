package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Reads,
  Region,
  SignalBind,
  SlotSource
}
import io.circe.Json
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** What a page open costs, split into the parts we can act on separately.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 2 -i 2 .*RenderBench.*'  # quick look
  * sbt 'benchmarks/Jmh/run -f 2 -wi 5 -i 5 .*RenderBench.*'  # with error bars
  * }}}
  *
  * Read the Error column before believing a gap. On a shared machine it runs
  * ±20%, which is wide enough to swallow anything under about 1.5x — the
  * signal-slot result below clears it, the composition one does not.
  *
  * ==Why it exists==
  *
  * A page open is uncached by construction: [[Renderer]] never consults
  * [[RenderCache]] (that cache is applied per live tick, in [[Patches]]), so
  * opening a dashboard re-walks and re-renders every node. This says where that
  * time goes, because the three places it can go have different fixes:
  *
  *   - '''JSONata''' ([[jsonata]]) — one `Transform.run` per live slot.
  *   - '''Mustache''' ([[mustache]]) — one `Template.execute` per node, context
  *     map included. The fix would be partials: one execution for the page.
  *   - '''Composition''' — a child renders to a `String` and each ancestor
  *     copies it into its own output. Isolated by [[pageNarrow]] vs
  *     [[pageFlat]]: same leaves and same real work, eight levels of copying
  *     against one.
  *
  * [[pageSignals]] against [[page]] prices ADR 0017's signal slots, which turn
  * out to dominate all three.
  *
  * ==Dead ends, recorded so they are not re-tried==
  *
  *   - '''A hand-rolled `nanoTime` loop.''' It ran seven measurements in one
  *     JVM, so each inherited the JIT state of the ones before it, and the
  *     per-bucket numbers moved 2x between runs of the same file. Raising
  *     warmup hid that rather than fixing it. Hence JMH, which forks.
  *   - '''Benchmarks under the module's own `src/test`.''' sbt-jmh's README
  *     documents that layout, but it needs `Jmh / compile := (Jmh /
  *     compile).dependsOn(Test / compile).value`, and sbt 2's task cache
  *     refuses a redefined `compile` — there is no `JsonFormat` for its
  *     `CompileAnalysis`. Hand-wiring the bytecode generator around that got as
  *     far as writing runner sources that `Jmh / compile` then would not pick
  *     up. A separate project on the plugin's DEFAULT layout needs none of it,
  *     and keeps jmh-core out of the add-on jar besides.
  *   - '''A byte-ratio for the composition cost''' — bytes written over bytes
  *     the page holds. It is structurally 1.0 whatever the tree, because the
  *     `own` map it sums holds ONLY leaves (a container "has no rendering of
  *     its own"), so the nodes whose copying was in question are precisely the
  *     ones missing from it. It measured nothing and looked like a result.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class RenderBench {
  import RenderBench.*

  private var plain: Renderer = null
  private var signalled: Renderer = null
  private var narrow: Renderer = null
  private var flat: Renderer = null
  private var set: Renderer = null
  private var st: Map[String, EntityState] = null
  private var transforms: Transforms = null
  private var entityTemplate: com.samskivert.mustache.Template = null
  private var painted: List[String] = null

  @Setup(Level.Trial)
  def setup(): Unit = {
    st = states(Leaves)
    plain = Renderer.create(Dashboard(cards, tree(Leaves, 4, signals = false)))
    signalled =
      Renderer.create(Dashboard(cards, tree(Leaves, 4, signals = true)))
    narrow = Renderer.create(Dashboard(cards, tree(Leaves, 2, signals = true)))
    flat =
      Renderer.create(Dashboard(cards, tree(Leaves, Leaves, signals = true)))
    set = Renderer.create(Dashboard(cards, setTree(Leaves)))
    transforms = Transforms.from(
      Dashboard(cards, tree(Leaves, 4, signals = true))
    )
    entityTemplate = Templates
      .from(Dashboard(cards, tree(Leaves, 4, signals = true)))
      .components("entity")
    painted = signalled.renderPageTraced(st).own.values.map(_.html).toList
  }

  /** The baseline: a whole page, no signal slots. */
  @Benchmark
  def page(bh: Blackhole): Unit = bh.consume(plain.renderPageTraced(st))

  /** The same page with two signal slots per card, which is what the shipped
    * cards look like. The gap against [[page]] is what ADR 0017 costs on a
    * FIRST PAINT — the case its own argument is not about, since its subject is
    * the live tick it makes cheaper.
    */
  @Benchmark
  def pageSignals(bh: Blackhole): Unit =
    bh.consume(signalled.renderPageTraced(st))

  /** Same leaves, one container: composition copies the page's bytes once. */
  @Benchmark
  def pageFlat(bh: Blackhole): Unit = bh.consume(flat.renderPageTraced(st))

  /** Same leaves, binary tree: ~8 levels, so the bytes are recopied ~8 times.
    * The gap against [[pageFlat]] is the composition cost, and it is the only
    * number that speaks to whether Mustache partials would buy anything.
    */
  @Benchmark
  def pageNarrow(bh: Blackhole): Unit = bh.consume(narrow.renderPageTraced(st))

  /** The same leaves as members of a CANDIDATE SET rather than static nodes.
    *
    * A separate benchmark because the member path is a separate renderer: a
    * member is one patch unit covering a whole subtree, so it resolves, renders
    * and seeds differently from a static node — and it is the hotter of the
    * two, since a set re-renders every matched member on every event.
    */
  @Benchmark
  def pageSet(bh: Blackhole): Unit = bh.consume(set.renderPageTraced(st))

  /** JSONata at the count one page performs, `Reads.Once` slots excluded (the
    * renderer memoises those, which is the state a warm server is in).
    */
  @Benchmark
  def jsonata(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(transforms.run(TransformIcon, e, "dashboard"))
      bh.consume(transforms.run(TransformName, e, "dashboard"))
      bh.consume(transforms.run(TransformUnit, e, "dashboard"))
      i += 1
    }
  }

  /** Mustache at the count one page performs, context construction included —
    * it is per-node work the walk cannot avoid either.
    */
  @Benchmark
  def mustache(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val m = new java.util.HashMap[String, AnyRef](8)
      val _ = m.put("cls", "fh-tile")
      val _ = m.put("icon", "lightbulb")
      val _ = m.put("name", "Tile number 7")
      val _ = m.put("state", "on lx")
      val _ = m.put("action", "@post('x')")
      bh.consume(entityTemplate.execute(m))
      i += 1
    }
  }

  /** Seeding `holds`: one SHA-256 per painted node, on every page open. */
  @Benchmark
  def holdsSeed(bh: Blackhole): Unit =
    painted.foreach(h => bh.consume(Digest.of(h)))
}

object RenderBench {

  /** 200 leaves — the "big page" #213 measured, so the numbers are comparable
    * to the ones already recorded on issue #130.
    */
  final val Leaves = 200

  // The transform shapes the real `dashboard.json` uses, in roughly the
  // proportion it uses them: mostly a `$lookup` table or a unit concatenation,
  // rarely a bare `$state`. Benchmarking `$state` alone would flatter JSONata.
  final val TransformUnit =
    """$state & ($attr.unit_of_measurement ? " " & $attr.unit_of_measurement : "")"""
  final val TransformIcon =
    """$lookup({"light":"lightbulb","switch":"toggle_on","sensor":"thermostat"}, $domain) ? """ +
      """$lookup({"light":"lightbulb","switch":"toggle_on","sensor":"thermostat"}, $domain) : "help""""
  final val TransformName =
    """$attr.friendly_name ? $attr.friendly_name : $entity_id"""
  final val TransformFill =
    """($v := $attr.brightness; $v != null ? $round($v * 100 / 255) & "%" : "")"""
  final val TransformAction =
    """"@post('sse/action/light/toggle/" & $entity_id & "')""""

  def entityId(i: Int): String = s"light.tile_$i"

  /** An entity card shaped like the shipped ones: icon, name, state, a fill bar
    * and a tap target. The `__bind` holes render empty for a slot that is not a
    * signal slot, so ONE card set serves both dashboards and `signals` moves
    * only on the slot — which is what keeps [[RenderBench.page]] and
    * [[RenderBench.pageSignals]] a comparison of one variable.
    */
  def cards: Map[String, CardDef] = Map(
    "entity" -> CardDef(
      template = """<div class="fh-entity {{cls}}">""" +
        """<i class="fh-icon">{{icon}}</i>""" +
        """<div class="fh-body"><span class="fh-name">{{name}}</span>""" +
        """<span class="fh-state" {{{state__bind}}}>{{state}}</span>""" +
        """<div class="fh-bar" {{{fill__bind}}}></div></div>""" +
        """<button data-on-click="{{{action}}}" class="fh-tap">go</button>""" +
        """</div>""",
      slots = List("cls", "icon", "name", "state", "fill", "action")
    ),
    "col" -> CardDef(
      template =
        """<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    )
  )

  def leaf(signals: Boolean)(i: Int): LayoutNode.Component =
    LayoutNode.Component(
      "entity",
      Map(
        "entity_id" -> SlotSource(literal = Some(entityId(i))),
        "cls" -> SlotSource(literal = Some("fh-tile")),
        "icon" -> SlotSource(transform = TransformIcon),
        "name" -> SlotSource(transform = TransformName),
        "state" -> SlotSource(
          transform = TransformUnit,
          signal = Option.when(signals)(SignalBind.Text)
        ),
        "fill" -> SlotSource(
          transform = TransformFill,
          signal = Option.when(signals)(SignalBind.Style("--_end"))
        ),
        // Every dashboard has identity-derived action slots, and the renderer
        // memoises them; leaving them out would overstate JSONata's share.
        "action" -> SlotSource(transform = TransformAction, reads = Reads.Once)
      )
    )

  /** `leaves` leaves under columns of `branching` children each, stacked until
    * one root remains.
    *
    * Branching, not depth, is the knob, and that is what makes the sweep
    * honest: a narrow tree is a DEEP one, and its containers — one per
    * `branching` nodes per level — are the nodes whose bytes composition
    * copies. Fixing the leaf count and squeezing `branching` holds the real
    * work still while multiplying the copying, which is the only way to see the
    * copying by itself.
    */
  def tree(leaves: Int, branching: Int, signals: Boolean): LayoutNode = {
    def stack(items: List[LayoutNode]): LayoutNode =
      if (items.sizeIs == 1) items.head
      else
        stack(
          items
            .grouped(math.max(2, branching))
            .map(g =>
              LayoutNode.Component("col", regions = LayoutNode.kids(g*))
            )
            .toList
        )
    stack(List.tabulate(leaves)(leaf(signals)))
  }

  /** The same leaves, as one candidate set: every entity a candidate, each with
    * a single unguarded clause rendering the same card. Membership is therefore
    * total, so this measures the RENDER path rather than predicate evaluation.
    */
  def setTree(leaves: Int): LayoutNode =
    LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.SetNode(
          candidates = List.tabulate(leaves)(entityId),
          members = List
            .tabulate(leaves)(i =>
              entityId(i) -> LayoutNode.SetMember(
                List(LayoutNode.SetClause(node = leaf(signals = true)(i)))
              )
            )
            .toMap
        )
      )
    )

  def states(leaves: Int): Map[String, EntityState] =
    List
      .tabulate(leaves) { i =>
        entityId(i) -> EntityState(
          entityId(i),
          if (i % 3 == 0) "off" else "on",
          Map(
            "friendly_name" -> Json.fromString(s"Tile number $i"),
            "brightness" -> Json.fromInt(1 + (i * 7) % 254),
            "unit_of_measurement" -> Json.fromString("lx")
          )
        )
      }
      .toMap
}
