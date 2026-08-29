package fh.view.runtime

import fh.view.model.{
  CardDef,
  Dashboard,
  LayoutNode,
  Region,
  SignalBind,
  SlotSource,
  Transform
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
  *   - '''A transform memo keyed by `(entity, contentVersion, transform)`.'''
  *     Unsound: `contentVersion` stands for an entity's content only for states
  *     stamped by `StateStore.update`, and nothing in the type says a `states`
  *     map came from there — `DashboardBuilders.st` defaults it to 0, so two
  *     different states share a key and the memo returns a stale value. Three
  *     suites caught it. A per-WALK memo keyed by `(entity, transform)` is
  *     sound (one walk sees one snapshot), but wants a walk-scoped type
  *     threaded through ~28 signatures; [[pageShared]] is here to say what that
  *     would be worth before anyone pays for it.
  *   - '''Benchmarking that memo without a per-walk scope.''' JMH re-renders
  *     the same `states` thousands of times, so a version-keyed memo never
  *     misses after the first iteration and reports the cost of a page whose
  *     every transform is precomputed — a state production never reaches.
  *     `pageSignals` "improved" 3816 -> 2042 us on a fixture with nothing to
  *     share, which is the tell.
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
  private var shared: Renderer = null
  private var st: Map[String, EntityState] = null
  private var transforms: Transforms = null
  private var celProbes: List[CelTransforms.Program] = null
  private var celComplex: CelTransforms.Program = null
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
    shared = Renderer.create(
      Dashboard(cards, tree(Leaves, 4, signals = true, distinct = Distinct))
    )
    transforms = Transforms.from(
      Dashboard(
        cards ++ ProbeTransforms.zipWithIndex.map { case (_, i) =>
          s"probe$i" -> CardDef(s"{{v}}", slots = List("v"))
        },
        LayoutNode.Component(
          "col",
          regions = LayoutNode.kids(
            (tree(Leaves, 4, signals = true) :: ProbeTransforms.zipWithIndex
              .map { case (t, i) =>
                LayoutNode.Component(
                  s"probe$i",
                  Map("v" -> SlotSource(transform = t))
                )
              })*
          )
        )
      )
    )
    entityTemplate = Templates
      .from(Dashboard(cards, tree(Leaves, 4, signals = true)))
      .components("entity")
    painted = signalled.renderPageTraced(st).own.values.map(_.html).toList
    celProbes = CelShapes.LiveTransforms.map(CelTransforms.parse)
    celComplex = CelTransforms.parse(CelShapes.TransformComplex)
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

  /** The same 200 leaves over only 40 DISTINCT entities — each shown five
    * times, which is what a real dashboard looks like once a light appears in
    * both its room and a summary.
    *
    * Against [[pageSignals]] (200 leaves, 200 distinct entities, so nothing to
    * share) this is what a per-`(entity, content, transform)` memo can win, and
    * [[pageSignals]] itself is what says whether the memo COSTS anything where
    * there is no duplication to exploit.
    */
  @Benchmark
  def pageShared(bh: Blackhole): Unit = bh.consume(shared.renderPageTraced(st))

  /** JSONata at the count one page performs, `Reads.Once` slots excluded (the
    * renderer memoises those, which is the state a warm server is in). The six
    * shapes are the ones the shipped components actually stick on hot slots.
    */
  @Benchmark
  def jsonata(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      LiveTransforms.foreach(t => bh.consume(transforms.run(t, e, "dashboard")))
      i += 1
    }
  }

  /** The fallback's worst case, once per entity: a hand-written expression as
    * hostile as the retired dynamic `$lookup($domain)` tier. Shipped nothing
    * uses it, but authors are not limited to the shipped shapes, so its cost is
    * what guarding against "someone writes something worse" has to swallow.
    */
  @Benchmark
  def jsonataComplex(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(transforms.run(TransformComplex, e, "dashboard"))
      i += 1
    }
  }

  /** CEL at the same count and shapes as [[jsonata]]: the six shipped shapes,
    * each compiled once into a `CelRuntime.Program` (the port's `Compiled`
    * form) on cel-java's PLANNER runtime and evaluated per entity. The
    * difference against [[jsonata]] is what the engine swap prices at the eval
    * the benchmark measures; both build their per-eval activation the same way.
    * (The deprecated `standardCelRuntimeBuilder()` — the legacy runtime —
    * measured ~4.9x heavier on these shapes; a port uses the planner.)
    */
  @Benchmark
  def cel(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      celProbes.foreach(p => bh.consume(p.run(e, "dashboard")))
      i += 1
    }
  }

  /** The same hostile ceiling as [[jsonataComplex]], as CEL — on the fixture
    * that exercises longest/complex each hit (see [[CelSpike]]).
    */
  @Benchmark
  def celComplex(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(celComplex.run(e, "dashboard"))
      i += 1
    }
  }

  /** The same three TRIVIAL reads — a bare `$state` and two bare `$attr.<name>`
    * — through the renderer's warm path. All three are shapes
    * [[Transform.Direct]] recognises, so [[Transforms.run]] resolves them at
    * startup and never sends them to an engine: this prices the dispatcher
    * lookup plus the direct read, NOT JSONata ([[jsonata]] is the engine over
    * the six real shapes). [[direct]] strips the dispatcher to price the read
    * alone. There is deliberately no engine figure for the trivial shapes — an
    * engine would still cost in the kB-per-eval range whether or not a function
    * is called; that is the entire reason [[Transform.Direct]] exists (issue
    * #237).
    */
  @Benchmark
  def jsonataTrivial(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(transforms.run("$state", e, "dashboard"))
      bh.consume(transforms.run("$attr.friendly_name", e, "dashboard"))
      bh.consume(transforms.run("$attr.brightness", e, "dashboard"))
      i += 1
    }
  }

  /** The hand-rolled fast path for the two TRIVIAL shapes — a bare `$state` and
    * a bare `$attr.<name>` — which is what the renderer ships INSTEAD of
    * JSONata for them ([[Transform.Direct]]). Same count and same three reads
    * as [[jsonataTrivial]], with the engine removed, so the gap between the two
    * is what deciding-to-use-the-engine costs on those shapes, and the gap
    * against [[cel]] is the floor the engines are compared against.
    */
  @Benchmark
  def direct(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(Transform.runDirect(Transform.Direct.State, e))
      bh.consume(Transform.runDirect(Transform.Direct.Attr("friendly_name"), e))
      bh.consume(Transform.runDirect(Transform.Direct.Attr("brightness"), e))
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

  /** Distinct entities behind [[RenderBench.pageShared]]'s leaves. */
  final val Distinct = 40

  // The transform shapes the shipped components use today, in the exact form
  // they ship: concat readouts, presence ternaries, the slider's BAKED (min/max
  // literal) fill and percent, its $count-based fill colour, and more-info's
  // each/sort/join block. The icon is a literal now (entity.pkl), so it is not
  // in the JSONata mix. Benchmarking `$state` alone would flatter JSONata, so
  // these sit beside [[RenderBench.jsonataTrivial]].
  final val TransformName =
    """$attr.friendly_name ? $attr.friendly_name : $entity_id"""
  final val TransformUnit =
    """$state & ($attr.unit_of_measurement ? " " & $attr.unit_of_measurement : "")"""
  // The slider's fill, as Pkl bakes it for a light (min 1, max 255), unrounded
  // on purpose — beer.min.js recomputes the same percentage on load.
  final val TransformFill =
    "($v := $attr.brightness; $v != null ? 100 - (($v - 1) * 100 / (255 - 1)) : 100)"
  // The percent readout, same baked config, as a "%"-suffixed string.
  final val TransformPercent =
    """($v := $attr.brightness; $v != null ? $string($round(($v - 1) * 100 / (255 - 1))) & " %" : "0 %")"""
  // The fill COLOUR: rgb_color verbatim, else the kelvin ramp (slider.pkl).
  final val TransformFillColor =
    "($rgb := $attr.rgb_color; $k := $attr.color_temp_kelvin; $count($rgb) = 3 " +
      """? "rgb(" & $string($rgb[0]) & "," & $string($rgb[1]) & "," & $string($rgb[2]) & ")" """ +
      " : $k != null ? ($t := $k < 2000 ? 0 : ($k > 6500 ? 1 : ($k - 2000) / 4500); " +
      "\"rgb(\" & $string($round(255 - 54 * $t)) & \",\" & $string($round(166 + 60 * $t)) " +
      "& \",\" & $string($round(87 + 168 * $t)) & \")\") : null)"
  // More-info's attribute block: every attribute as a sorted `name: value`
  // line (moreinfo.pkl).
  final val TransformAttrLines =
    """$join($sort($each($attr, function($v, $k) { $k & ": " & $string($v) })), "\n")"""
  // The CEILING: a hand-written expression as hostile as the retired dynamic
  // `$lookup($domain)` tier. Nothing stops an author writing one today, so the
  // fallback's worst case stays priced ([[RenderBench.jsonataComplex]]).
  final val TransformComplex =
    "($v := $lookup($attr, $lookup({\"light\":\"brightness\",\"cover\":\"current_position\"}, $domain)); " +
      "$v != null ? $round(100 - (($v - $lookup({\"light\":1,\"cover\":0}, $domain)) * 100 / " +
      "($lookup({\"light\":255,\"cover\":100}, $domain) - $lookup({\"light\":1,\"cover\":0}, $domain)))) : 100)"

  /** The shapes [[RenderBench.jsonata]] runs warm (precompiled into the
    * `Transforms` map), plus the two trivial ones
    * [[RenderBench.jsonataTrivial]] and the complex one
    * [[RenderBench.jsonataComplex]] separate each need.
    */
  final val TrivialTransforms =
    List("$state", "$attr.friendly_name", "$attr.brightness")
  final val LiveTransforms = List(
    TransformName,
    TransformUnit,
    TransformFill,
    TransformPercent,
    TransformFillColor,
    TransformAttrLines
  )

  /** Everything the transform benchmarks run, precompiled into the warm map. */
  final val ProbeTransforms =
    TrivialTransforms ++ LiveTransforms :+ TransformComplex

  /** A tap's action as the builder bakes it for one entity (ADR 0016): a
    * build-time literal per entity, so the card's action hole stays filled but
    * no JSONata runs for it.
    */
  def actionLiteral(i: Int, distinct: Int): String =
    s"@post('sse/action/light/toggle/${entityId(i % distinct)}')"

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
        """<div class="fh-bar" {{{fill__bind}}}></div>""" +
        """<span class="fh-fillcolor" {{{fillColor__bind}}}></span>""" +
        """<button data-on-click="{{{action}}}" class="fh-tap">go</button>""" +
        """</div>""",
      slots =
        List("cls", "icon", "name", "state", "fill", "fillColor", "action")
    ),
    "col" -> CardDef(
      template =
        """<div class="fh-col">{{#children}}{{{html}}}{{/children}}</div>""",
      regions = Map("children" -> Region())
    )
  )

  def leaf(signals: Boolean, distinct: Int = Int.MaxValue)(
      i: Int
  ): LayoutNode.Component =
    LayoutNode.Component(
      "entity",
      Map(
        "entity_id" -> SlotSource(literal = Some(entityId(i % distinct))),
        "cls" -> SlotSource(literal = Some("fh-tile")),
        // Literal now (entity.pkl): the entity's icon is a registry fact.
        "icon" -> SlotSource(literal = Some("mdi:lightbulb")),
        "name" -> SlotSource(transform = TransformName),
        "state" -> SlotSource(
          transform = TransformUnit,
          signal = Option.when(signals)(SignalBind.Text)
        ),
        "fill" -> SlotSource(
          transform = TransformFill,
          signal = Option.when(signals)(SignalBind.Style("--_end"))
        ),
        "fillColor" -> SlotSource(
          transform = TransformFillColor,
          signal = Option.when(signals)(SignalBind.Style("background"))
        ),
        // Every dashboard has action slots, and ADR 0016 made them build-time
        // literals; leaving the hole out would not match a shipped card.
        "action" -> SlotSource(literal = Some(actionLiteral(i, distinct)))
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
  def tree(
      leaves: Int,
      branching: Int,
      signals: Boolean,
      distinct: Int = Int.MaxValue
  ): LayoutNode = {
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
    stack(List.tabulate(leaves)(leaf(signals, distinct)))
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
            "unit_of_measurement" -> Json.fromString("lx"),
            // Half the cards take the rgb_color branch of the fill colour, the
            // rest the kelvin-ramp/null branch, like a real house's mix.
            "rgb_color" -> Json.arr(
              List(i % 256, (i * 3) % 256, (i * 5) % 256).map(Json.fromInt)*
            ),
            "color_temp_kelvin" -> Json.fromInt(2000 + (i * 97) % 4500)
          )
        )
      }
      .toMap
}
