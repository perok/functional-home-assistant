package fh.view.runtime

import fh.view.runtime.RendererTestOps.*

import fh.view.model.{
  Activation,
  CardDef,
  Dashboard,
  LayoutNode,
  NodeId,
  Op,
  Predicate,
  Region,
  SignalBind,
  SignalId,
  SlotSource,
  Surface,
  Transform
}
import cats.effect.unsafe.implicits.global
import cats.syntax.traverse.*
import io.circe.Json
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

/** What a page open costs, split into the parts we can act on separately.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 1 -wi 2 -i 2 .*RenderBench.*'  # quick look
  * sbt 'benchmarks/Jmh/run -f 2 -wi 5 -i 5 .*RenderBench.*'  # with error bars
  * sbt 'benchmarks/Jmh/run -f 2 -wi 5 -i 5 -prof gc .*RenderBench.*'  # + bytes
  * }}}
  *
  * Read the Error column before believing a gap. On a shared machine it runs
  * ±20%, which is wide enough to swallow anything under about 1.5x — the
  * signal-slot result below clears it, the composition one does not.
  *
  * '''Run `-prof gc` as well as the timing.''' The server's target is a
  * Raspberry Pi 4, where allocation rate and peak live bytes decide more than
  * microseconds do — and the two do not rank the same work.
  * `gc.alloc.rate.norm` is bytes per op, and the composition result below is
  * significant in bytes while being inside the noise in time.
  *
  * ==Baseline==
  *
  * `-f 2 -wi 5 -i 5 -prof gc`, one machine, so read the RATIOS not the
  * absolutes. Recorded as the starting point for the streaming/sharing work, so
  * a later change has something honest to be measured against.
  *
  * '''These are the STARTING numbers, not the current ones.''' Two rows have
  * since moved and are marked; the paragraphs below carry what they moved to.
  *
  * {{{
  *                        us/op          B/op     what it is
  * -- the document ------------------------------------------------------
  * pageServe            1,521.0     3,782,170     render + digest + UTF-8
  * pageSignals          1,425.3     3,524,973     …render only, SHIPPED shape
  * page                   697.9     1,317,925     same leaves, no signal slots
  * pageFlat             1,458.6     3,356,670     same leaves, one container
  * pageNarrow           1,512.2     3,774,825     same leaves, ~8 levels
  * pageSet              1,338.5     3,769,906     as candidate-set members
  * pageSetPlain           763.8     1,553,136     …signal-less members
  * pageShared           1,339.5     3,474,212     200 leaves, 40 entities
  * -- a live tick, the REAL path ----------------------------------------
  * resumeSignals          261.7       442,296     MOVED -> 102 us / 151 kB
  * resumeSignalsPure      101.4       138,575     …on a card the key protects
  * resumeMorphs           231.5       430,638     1 client, bytes moved
  * resumeSignalsFanout    892.3     1,491,181     MOVED with resumeSignals
  * -- pieces ------------------------------------------------------------
  * simple               1,065.5       960,912     transforms, production dispatch
  * cel                  1,596.1     1,058,235     …all six on the engine
  * celComplex             268.7       328,002     the hostile expression
  * mustache               133.8       392,001     200 executions + contexts
  * holdsSeed               90.1       145,534     200 SHA-256 over leaf html
  * direct                  11.7        27,200     raw reads, no dispatch
  * wireTick                19.3        46,647     a bare 3-node render
  * wire                    53.5       239,874     structural tick, 10 clients
  * wireCommon              33.3       107,412     the encode, 10 clients
  * wireCommonShared         3.2        10,747     …encoded once, 10 reuse
  * }}}
  *
  * ONE run, so the rows are comparable with each other; across runs the same
  * row moves ±20% and only the ratios carry.
  *
  * What to take from it.
  *
  * '''The document is 128 kB and costs 2.2 MB to serve''' — 17x its own size.
  * Most of that is the WALK — transforms, mustache contexts, slot resolution —
  * which the SINK cannot touch.
  *
  * '''Half of what it used to cost was the signal SEED, and profiling is the
  * only reason anyone knows.''' `-prof gc` says how much; it never says where.
  * async-profiler over [[pageSignals]] put '''49%''' of a page open in
  * `Datastar.nestJs` + `SignalId.segments` — a per-level
  * `groupBy.toList.sortBy.collect` run once per LEVEL per NODE, over a
  * single-element list, for a name four segments deep. Sorting the rows once by
  * path and walking them by index took `pageSignals` from 3,399 kB to 2,044 kB
  * ('''−40%'''); precomputing the whole seed per node ([[Datastar.SignalSeed]])
  * took it to '''1,791 kB''', a further −12.6%, and the time from 1,275 µs to
  * 836 µs. [[page]], which has no signal slots, stayed put — the control that
  * says the change reached only what it meant to.
  *
  * '''Then the scratch buffers went.''' The patch form each own-rendering node
  * renders only to be fingerprinted was allocating `char[1024]` plus a
  * `toString` per node; [[Sink.scratched]] lends one per thread to the whole
  * walk. [[pageWalkStream]] '''1,322 kB -> 1,113 kB, −15.8%''', time unchanged.
  *
  * '''Profile [[pageWalkStream]], not [[pageSignals]].''' `pageSignals` buffers
  * the whole document, which production does not — that alone is ~19% of its
  * allocation profile (`RendererTestOps.renderPageTraced`). It is a fine A/B
  * arm; it is a misleading answer to "where does a page open go".
  *
  * '''Read a page row against its own A/B, not against a number written
  * here.''' `gc.alloc.rate.norm` is deterministic WITHIN a run (±200 B) and
  * drifts about '''2.6%''' BETWEEN runs on this box — [[page]] came back
  * anywhere from 1,295 kB to 1,329 kB across one session with no change to its
  * path. So a claim under ~3% needs the two arms measured back to back, which
  * is what the numbers above are; anything smaller is not a result.
  *
  * The lesson is the method, not the number: issue #237's "where the bytes
  * plausibly go" was read off the code and named `resolveTemplate` and
  * `executeResolved`, neither of which registers today. Profile before
  * choosing.
  *
  * '''The sink is the variable; fs2 is not.''' `renderPageInto` is pure over a
  * `Writer`, and http4s hands a buffered body out as a `Stream[IO, Byte]` just
  * as it does a streamed one — so pricing the sinks through `readOutputStream`
  * charged one of them for machinery both pay, and added an error wider than
  * the gap being read (2870 ± 3248 µs on one such arm). [[pageServe]] against
  * [[pageWalkStream]] is the comparison, and neither runs a stream.
  *
  * `-f 1 -wi 5 -i 5 -prof gc`, one run:
  *
  * {{{
  *                          us/op          B/op
  * pageServe            1,167 ±125     3,547,273   buffered walk + encode
  * pageWalkStream       1,305 ± 44     2,874,617   streamed walk
  * pageWalkStreamUnb.   1,327 ±256     3,603,954   …without the writer buffer
  * pageStreamBuffered   2,318 ±904     3,232,255   SHIPPED, end to end
  * }}}
  *
  * '''Streaming buys 672 kB of churn per page open''' — 19% — for 137 µs, or
  * 12%. Read those two rows and not the fs2 one: ±44 against ±904 is the
  * difference between a gap you can read and one you cannot. Streaming also
  * buys PEAK — the ~500 kB a concurrent open otherwise holds live across four
  * materialisations, which multiplies by open tabs — and nothing here measures
  * that (`SinkStreamingSuite` does).
  *
  * '''The writer buffer's win is churn, and only churn''' — 729 kB, against a
  * time difference inside the error.
  *
  * '''The pipe costs ~1,000 µs and ~358 kB''' (`pageStreamBuffered` minus
  * `pageWalkStream`) and is not a choice — ember pulls every body through it.
  * That row is here to map onto issue #237's whole-page-open numbers, not to
  * compare sinks with.
  *
  * '''`Sink.Streaming.digesting` costs nothing''', which was the open question
  * about whether the sealed type earns itself. It is the ONE operation the two
  * sinks implement differently, and it fires only on an own-rendering node with
  * no signal slots — every leaf of [[plain]], no leaf of [[signalled]]. So
  * [[page]] against [[pageWalkStreamPlain]] is that operation, isolated:
  *
  * {{{
  *                            us/op          B/op
  * page                     657 ±119     1,365,741   buffered, digesting fires
  * pageWalkStreamPlain      613 ± 83     1,146,828   streamed, digesting fires
  * }}}
  *
  * Streaming is 219 kB (16%) cheaper there — the same ratio as the 609 kB (17%)
  * it saves on [[signalled]], where `digesting` is never called at all. The
  * per-node scratch buffer is not a price; the saving survives it intact. Note
  * the ±0.6 B error: this pair is fully deterministic, unlike the time.
  *
  * '''An extra client on a tick costs 70 us and 117 kB''' —
  * `resumeSignalsFanout` minus `resumeSignals`, over nine. Against 262 us for
  * the first client, so the [[RenderCache]] is removing about three quarters of
  * each further client's work. It is earning its place.
  *
  * '''Sharing the encoded frame is a much smaller prize than it looks.'''
  * [[wireCommon]] vs [[wireCommonShared]] is 10x — but that measures the encode
  * in ISOLATION. One encode is 3.2 us and ~10.7 kB, against a marginal client
  * cost of 70 us and 117 kB: about '''5% of the time and 9% of the bytes'''.
  * The other 95% is the decision — the changelog read, the visibility
  * narrowing, the per-node cache lookup and digest compare, the signal diff. Do
  * not quote the 10x as if it were a tick-level number.
  *
  * '''A signals tick is the cheap one, and [[resumeSignalsPure]] is what says
  * so.''' `resumeSignals` is 102 us / 151 kB against `resumeSignalsPure`'s 106
  * us — the shipped card, whose name reads `friendly_name`, now costs what a
  * card with a literal name costs. It did not: rendering a morph is how we
  * discover its bytes did not move, and the shipped name re-admitted the entity
  * to the cache key on every tick, so a signals tick cost 262 us / 442 kB —
  * MORE than `resumeMorphs`. `Patches.bytes` now hands the cache the resolved
  * byte-slot values and an entry carrying the same ones is reused (ADR 0012).
  * Keep this pair: it is the only thing that would catch the regression.
  *
  * ==Why it exists==
  *
  * A page open is uncached by construction: [[Renderer]] never consults
  * [[RenderCache]] (that cache is applied per live tick, in [[Patches]]), so
  * opening a dashboard re-walks and re-renders every node. This says where that
  * time goes, because the three places it can go have different fixes:
  *
  *   - '''Transforms''' ([[simple]] / [[cel]]) — one evaluation per live slot:
  *     the fast catalog's dispatch against the engine-only baseline.
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
  private var setPlain: Renderer = null
  private var shared: Renderer = null
  private var st: Map[String, EntityState] = null
  private var transforms: Transforms = null
  private var celProbes: List[Transform.Compiled] = null
  private var celComplex: Transform.Compiled = null
  // The explicit fast tier's probes and the two shipped shapes that stay on
  // the engine (fill colour, attribute comprehension) — the same 4-of-6 mix a
  // shipped page dispatches.
  private var simpleProbes: List[Transform.Simple] = null
  private var engineShapes: List[Transform.Compiled] = null
  private var entityTemplate: com.github.mustachejava.Mustache = null
  private var painted: List[String] = null
  // The wire bench's inputs: three real node bytes (morph payloads) and the
  // signal ids a value tick patches, plus the tick's node ids for the render
  // half.
  private var wireNodeIds: List[NodeId] = null
  private var wireRot = 0
  // The pull benches' fixture: what one client holds after a page open, and
  // the node each of the tick's entities is shown on.
  private var heldAfterPaint: Map[NodeId, Held] = null
  private var tickNodeIds: Vector[NodeId] = null
  private var tickEntities: Vector[String] = null
  // The signal-only variant: same dashboard, name held as a literal, so no
  // slot reads the entity as bytes and the cache key cannot move.
  private var pure: Renderer = null
  private var heldPure: Map[NodeId, Held] = null
  private var pureNodeIds: Vector[NodeId] = null
  // The SLUG's cache, not the op's — see [[pull]].
  private var liveCache: RenderCache = null
  // The FILL fixture: a state group whose branch a flip re-supplies wholesale.
  private var flipped: Renderer = null
  private var flipStates: Map[String, EntityState] = null
  private var flipCache: RenderCache = null
  private var flipRot = 0
  // The PUBLISHER fixture. One store to ingest into, and a rotation counter
  // shared by every bench on this path so no two calls present the same frame.
  private var store: StateStore = null
  private var pubRot = 0
  // Built in setup, not per invocation: an alloc profile said building the
  // frame was 37% of what `publish` reported, i.e. the harness pricing itself.
  // Two is enough to alternate, and alternating is a real change on every
  // entity every call.
  private var pubFrames: Vector[Frame] = null
  private var flipFrames: Vector[Frame] = null
  private var pubIngests: Vector[List[Ingest]] = null
  private var dedupBatch: List[Ingest] = null

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
    setPlain =
      Renderer.create(Dashboard(cards, setTree(Leaves, signals = false)))
    shared = Renderer.create(
      Dashboard(cards, tree(Leaves, 4, signals = true, distinct = Distinct))
    )
    def cparse(src: String): Transform.Compiled =
      Transform
        .parse(src)
        .fold(
          e => sys.error(s"cel won't compile: $src [$e]"),
          identity
        )
    // The production dispatch: Transforms pre-compiles every shape and
    // resolves the fast tier once, so [[simple]] measures exactly what the
    // renderer's warm path does per evaluation.
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
    celProbes = CelShapes.LiveTransforms.map(cparse)
    celComplex = cparse(CelShapes.TransformComplex)
    simpleProbes = List(
      Transform.Simple.AttrOrId("friendly_name"),
      Transform.Simple.UnitSuffix("unit_of_measurement"),
      Transform.Simple.Fill("brightness", 1.0, 255.0),
      Transform.Simple.Percent("brightness", 1.0, 255.0)
    )
    engineShapes =
      List(CelShapes.TransformFillColor, CelShapes.TransformAttrLines).map(
        cparse
      )
    entityTemplate = Templates
      .from(Dashboard(cards, tree(Leaves, 4, signals = true)))
      .components("entity")
    // The trace holds digests now, so the leaf BYTES for [[holdsSeed]] come
    // from the live path that still produces them.
    painted = signalled
      .renderPageTraced(st)
      .own
      .keys
      .flatMap(signalled.renderNodeById(_, st))
      .toList
    // The first leaves' own node ids — one per leaf, in fixture order — for
    // the tick render: `componentsFor` maps the entity to the node that
    // shows it.
    wireNodeIds = List
      .tabulate(3)(i => entityId(i))
      .flatMap(e => signalled.componentsFor(e).toList)
      .take(3)
    // What a client holds the instant its document finished painting — the
    // same seed `Server.renderPage` sets, so a pull below starts from a real
    // client's record rather than an empty one. An empty `holds` reads as
    // "unknown, send it" and would make every pull a morph, which is exactly
    // the case the suppression exists to avoid measuring.
    val paint = signalled.renderPageTraced(st)
    heldAfterPaint = paint.own.map { case (id, p) =>
      id -> Held(Some(p.digest), p.signals)
    }
    tickEntities = Vector.tabulate(TickEntities)(entityId)
    tickNodeIds = tickEntities.flatMap(signalled.componentsFor(_).toList)
    pure = Renderer.create(Dashboard(cards, tree(Leaves, 4, signalOnly = true)))
    val purePaint = pure.renderPageTraced(st)
    heldPure = purePaint.own.map { case (id, p) =>
      id -> Held(Some(p.digest), p.signals)
    }
    pureNodeIds = tickEntities.flatMap(pure.componentsFor(_).toList)
    liveCache = RenderCache.create.unsafeRunSync()
    flipped = Renderer.create(flipDash())
    // Disarmed, so the `else` branch is the one the fill supplies.
    flipStates =
      st + (FlipEntity -> EntityState(FlipEntity, "disarmed", Map.empty))
    flipCache = RenderCache.create.unsafeRunSync()
    store = StateStore.inMemory(flipStates).unsafeRunSync()
    pubFrames = Vector(publishFrame(st, 0), publishFrame(st, 1))
    flipFrames =
      Vector(publishFrame(flipStates, 0), publishFrame(flipStates, 1))
    pubIngests =
      flipFrames.map(_.changes.map(c => Ingest.Replace(c.current): Ingest))
    dedupBatch = dedupIngests
    checkFillShape()
    checkPublishShape()
    checkTickShapes()
  }

  /** The two pull fixtures assert what they are, because the thing that makes
    * them different is a property of the CARD, not of this file: `signalTick`
    * moves only attributes the shipped card carries as signals, and if a slot
    * ever stops being a signal slot that tick starts producing morphs and
    * [[resumeSignals]] silently becomes a second [[resumeMorphs]] — still
    * green, still fast, and measuring the wrong thing.
    *
    * Checked once per trial, before any measurement, so the failure is a dead
    * benchmark rather than a plausible number.
    */
  private def checkTickShapes(): Unit = {
    def kinds(
        r: Renderer,
        held: Map[NodeId, Held],
        ids: Vector[NodeId],
        tick: Int => Map[String, EntityState]
    ): List[Patch] = {
      wireRot += 1
      pull(r, held, ids, tick(wireRot), 1).head.map(_.patch)
    }
    val sig = kinds(signalled, heldAfterPaint, tickNodeIds, signalTick)
    if (sig.exists(_.isInstanceOf[Patch.Morph]))
      sys.error(
        s"signalTick must suppress every morph; got ${sig.map(_.getClass.getSimpleName)}"
      )
    if (!sig.exists(_.isInstanceOf[Patch.Signals]))
      sys.error("signalTick produced no signals frame — nothing is measured")
    val bytes = kinds(signalled, heldAfterPaint, tickNodeIds, byteTick)
    if (!bytes.exists(_.isInstanceOf[Patch.Morph]))
      sys.error(
        "byteTick must produce morphs; the name slot is not in the bytes"
      )
    // The signal-only card must reach the SAME conclusion as the shipped one:
    // the point of it is that it gets there without re-rendering, not that it
    // sends something different. A divergence here would mean the literal name
    // changed what the tick MEANS, and the comparison would be meaningless.
    val purest = kinds(pure, heldPure, pureNodeIds, signalTick)
    if (purest.exists(_.isInstanceOf[Patch.Morph]))
      sys.error("resumeSignalsPure must suppress every morph too")
    if (!purest.exists(_.isInstanceOf[Patch.Signals]))
      sys.error("resumeSignalsPure produced no signals frame")
  }

  /** [[resumeFlip]] is only a fill bench if the pull actually fills. A branch
    * whose condition does not match, or a cursor the mutation is behind, gives
    * an empty batch that still runs and still reports a number.
    */
  private def checkFillShape(): Unit = {
    val one = pullFlip(1).head
    val filled = one.collect { case Addressed(Patch.Insert(html, _, _), _, _) =>
      html
    }
    if (filled.sizeIs != 1)
      sys.error(s"resumeFlip must emit exactly one host fill; got $one")
    if (!filled.head.contains("fh-entity"))
      sys.error("resumeFlip's fill carries no cards — the branch is empty")
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

  /** [[pageSet]] with signal-less members — every member's two forms are
    * byte-identical, so the patch fingerprint is a slice of the document bytes
    * and the second render per member dies. The gap against [[pageSet]] is what
    * the slice saves where it applies; [[pageSet]] is the worst case where
    * every member carries a signal slot and nothing can be shared.
    */
  @Benchmark
  def pageSetPlain(bh: Blackhole): Unit =
    bh.consume(setPlain.renderPageTraced(st))

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

  /** The EXPLICIT fast tier through the production dispatch
    * ([[Transforms.run]]): the four opted-in [[Transform.Simple]] shapes
    * evaluate without the engine (~45 B); the fill colour and the attribute
    * comprehension stay on the engine, exactly the shipped mix (4 of 6). The
    * gap against [[cel]] (all six on the engine) is what the tier saves per
    * page, and against [[direct]] it prices the dispatch on top of the raw
    * read. `Reads.Once` slots excluded — the renderer memoises those.
    */
  @Benchmark
  def simple(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      simpleProbes.foreach(s => bh.consume(transforms.run(s, e)))
      engineShapes.foreach(t => bh.consume(Transform.run(t, e, "dashboard")))
      i += 1
    }
  }

  /** The fallback's worst case, once per entity: a hand-written expression as
    * hostile as the retired dynamic `$lookup($domain)` tier. Shipped nothing
    * uses it, but authors are not limited to the shipped shapes, so its cost is
    * what guarding against "someone writes something worse" has to swallow.
    */
  /** The SHIPPED engine ([[fh.view.model.Transform]]) at the same count and
    * shapes: each compiled once via `Transform.parse` and evaluated per entity
    * through `Transform.run` — the engine-only baseline the fast tier is
    * measured against. The gap against [[simple]] is what the catalog saves per
    * evaluation.
    */
  @Benchmark
  def cel(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      celProbes.foreach(p => bh.consume(Transform.run(p, e, "dashboard")))
      i += 1
    }
  }

  @Benchmark
  def celComplex(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(Transform.run(celComplex, e, "dashboard"))
      i += 1
    }
  }

  /** The hand-rolled fast path for the two TRIVIAL shapes — a bare `state` and
    * a guarded attribute read — which is what the catalog ships INSTEAD of an
    * engine for them ([[Transform.Simple]]). Same count and same three reads as
    * [[simple]], with the dispatch removed, so the gap between the two is what
    * recognizing-and-dispatching costs, and the gap against [[cel]] is the
    * floor the engines are compared against.
    */
  @Benchmark
  def direct(bh: Blackhole): Unit = {
    var i = 0
    while (i < Leaves) {
      val e = st(entityId(i))
      bh.consume(Transform.runSimple(Transform.Simple.State, e))
      bh.consume(
        Transform.runSimple(Transform.Simple.Attr("friendly_name"), e)
      )
      bh.consume(Transform.runSimple(Transform.Simple.Attr("brightness"), e))
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
      val w = new java.io.StringWriter
      entityTemplate.execute(w, m)
      bh.consume(w.toString)
      i += 1
    }
  }

  /** Seeding `holds`: one SHA-256 per painted node, on every page open. */
  @Benchmark
  def holdsSeed(bh: Blackhole): Unit =
    painted.foreach(h => bh.consume(Digest.of(h)))

  /** The WIRE half of a STRUCTURAL tick, fanned to [[WireClients]] open
    * connections, each owed DIFFERENT nodes: three morphs and a signals frame
    * through [[Patches.encode]] (adjacent-morph merge, the collapse at the
    * Datastar edge) and the per-connection encode — `renderString` + UTF-8,
    * which is what http4s's SSE encoder does per event per socket.
    *
    * The gap against [[wireTick]] says how much of a busy house's tick is wire
    * bookkeeping vs the render itself.
    *
    * '''It does not say whether SHARING a frame across connections would buy
    * anything''', though it was once read that way. Every client here is owed a
    * different slice, so there is nothing to share by construction, and both
    * the shared and unshared worlds cost exactly this. That question is
    * [[wireCommon]] vs [[wireCommonShared]] — and note this fixture is also the
    * rarer tick: on a signal-slot dashboard the digest stands still and the
    * common tick sends ONE signals frame with no morph at all (ADR 0017).
    */
  @Benchmark
  def wire(bh: Blackhole): Unit = {
    var c = 0
    while (c < WireClients) {
      val rot = wireRot + c
      val h1 = painted(rot % painted.size)
      val h2 = painted((rot + 7) % painted.size)
      val h3 = painted((rot + 13) % painted.size)
      val eid = s"light.tile_${rot % Leaves}"
      val events = Patches.encode(
        List(
          Addressed(Patch.Morph(h1)),
          Addressed(Patch.Morph(h2)),
          Addressed(Patch.Morph(h3)),
          Addressed(
            Patch.Signals(
              Map(
                SignalId.derived(s"_e.$eid.state") -> Json.fromString("on"),
                SignalId.derived(s"_e.$eid.style.background") ->
                  Json.fromString("#ffb46b")
              )
            )
          )
        )
      )
      events.foreach(e => bh.consume(e.bytes))
      c += 1
    }
    wireRot += 1
  }

  /** '''The BUFFERED serve — the path that no longer ships.'''
    *
    * Kept as the comparison point for [[pageWalkStream]], which is what
    * `Server.renderPage` actually does now. `renderPageTraced` builds the page
    * as one `String`; serving it that way then encodes that String to UTF-8 for
    * the response body, which is a full copy of the document.
    *
    * Read the two together in ONE run or not at all — across runs the same row
    * moves ±20%, which is wider than the gap between them.
    */
  @Benchmark
  def pageServe(bh: Blackhole): Unit = {
    val t = signalled.renderPageTraced(st)
    // No digest pass, and its absence IS the earlier change: the walk
    // fingerprints each node as it writes it, so `holds` is seeded straight
    // from the trace instead of re-hashing every leaf's html.
    bh.consume(t.own.size)
    bh.consume(t.html.getBytes(UTF_8))
  }

  /** '''The streamed walk, with NO fs2''' — the honest A/B against
    * [[pageServe]], because the only thing that differs between them is the
    * sink.
    *
    * The walk is pure over a `Writer`: `renderPageInto` takes the sink and
    * returns the trace, with no `IO` and no stream anywhere in it. Pricing it
    * through `readOutputStream` therefore charged the sink for a pipe and a
    * second fiber that http4s makes us pay whatever produces the body — ember
    * hands a buffered page out as a `Stream[IO, Byte]` too. Comparing the two
    * SINKS is the question; the transport is not a choice we have.
    *
    * Dropping fs2 also drops the noise it brought: the fs2-wrapped arms ran
    * ±3248 µs on 2870, an error wider than the score, which is why the time
    * half of the streaming trade went unresolved for so long.
    *
    * The writer chain is the shipped one — `BufferedWriter` over
    * `OutputStreamWriter` — so the incremental UTF-8 encode is still priced.
    * Only the destination is a discard.
    */
  @Benchmark
  def pageWalkStream(bh: Blackhole): Unit = {
    val sink = new RenderBench.CountingOutputStream
    val w = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(sink, UTF_8),
      Server.PageChunkBytes
    )
    val own = signalled.renderPageInto(Sink.streaming(w), st)
    w.flush()
    bh.consume(own.size)
    bh.consume(sink.count)
  }

  /** '''The one arm that reaches `Sink.Streaming.digesting`''' — the only
    * operation the two sinks implement differently.
    *
    * It fires on an own-rendering node with NO signal slots (`!twoForms`),
    * which is every leaf of [[plain]] and no leaf of [[signalled]] — so every
    * other streamed arm walks past it and [[page]] reaches only the BUFFER's
    * version. A change to `digesting` measured against a `signalled` fixture
    * therefore measures nothing at all.
    *
    * Read against [[page]], which is the same tree through `Sink.Buffer`. The
    * gap is `digesting` and nothing else: a buffer bounds the run by two
    * offsets and cuts a String out of it, a stream catches the run in a scratch
    * `StringBuilder` on the way past. Both copy it once.
    *
    * Read against [[pageWalkStream]] for the other half — the same sink on a
    * tree where `digesting` is never called — so the two comparisons together
    * separate the sink's cost from this operation's.
    */
  @Benchmark
  def pageWalkStreamPlain(bh: Blackhole): Unit = {
    val sink = new RenderBench.CountingOutputStream
    val w = new java.io.BufferedWriter(
      new java.io.OutputStreamWriter(sink, UTF_8),
      Server.PageChunkBytes
    )
    val own = plain.renderPageInto(Sink.streaming(w), st)
    w.flush()
    bh.consume(own.size)
    bh.consume(sink.count)
  }

  /** [[pageWalkStream]] without the `BufferedWriter` — what the buffer is
    * worth, asked without fs2 in the way.
    *
    * The walk writes in THOUSANDS of small pieces — a tag, an id, a slot value
    * — and every one is a `synchronized` call into `OutputStreamWriter`'s
    * `StreamEncoder`, which buffers the ENCODE at 8 kB but not the CALL. The
    * buffer collapses those calls, and what that is worth is 729 kB of churn
    * (3.60 MB against 2.87 MB) and NO time: 1327 ± 256 µs against 1305 ± 44.
    *
    * Measured through fs2 the same buffer appeared to buy 369 µs as well. It
    * does not; that was the pipe's noise, and this arm exists because the arm
    * that produced the wrong reading could not have told us so.
    */
  @Benchmark
  def pageWalkStreamUnbuffered(bh: Blackhole): Unit = {
    val sink = new RenderBench.CountingOutputStream
    val w = new java.io.OutputStreamWriter(sink, UTF_8)
    val own = signalled.renderPageInto(Sink.streaming(w), st)
    w.flush()
    bh.consume(own.size)
    bh.consume(sink.count)
  }

  /** '''The SHIPPED serve end to end''' — [[pageWalkStream]] plus the fs2 pipe
    * `Server.renderPage` actually hands to ember.
    *
    * Kept as the one row that maps onto issue #237's numbers, which are whole
    * page opens. It is NOT the row to read when comparing sinks: the pipe it
    * adds is paid by a buffered body too.
    *
    * Against [[pageWalkStream]] this is what the pipe costs: ~1,000 µs and ~358
    * kB. Read it for the absolute a page open pays, never for a comparison —
    * its error (±904 µs) is wide enough to swallow every gap the walk arms
    * resolve, which is how the writer buffer got credited with a time win it
    * does not have.
    */
  @Benchmark
  def pageStreamBuffered(bh: Blackhole): Unit = {
    val n = fs2.io
      .readOutputStream[cats.effect.IO](Server.PageChunkBytes) { os =>
        cats.effect.IO.blocking {
          val w = new java.io.BufferedWriter(
            new java.io.OutputStreamWriter(os, UTF_8),
            Server.PageChunkBytes
          )
          val own = signalled.renderPageInto(Sink.streaming(w), st)
          w.flush()
          own.size
        }.void
      }
      .compile
      .count
      .unsafeRunSync()
    bh.consume(n)
  }

  /** '''One client's real pull''' — [[Patches.resume]], not `renderNodeById`.
    *
    * [[wireTick]] measures a bare render of nodes someone already decided to
    * send. The pull is what DECIDES: read the changelog from this client's
    * cursor, narrow to what it can see, render each candidate through the
    * slug's [[RenderCache]], and compare against what this client already
    * holds. That comparison is the whole point of the design and none of it was
    * benchmarked.
    *
    * This is the SIGNALS tick, which is the frequent one (ADR 0017): the moved
    * attribute reaches only signal slots, so every node's patch-form bytes are
    * unchanged, every morph is suppressed, and the batch is one
    * `datastar-patch-signals` frame. The work is therefore all decision and no
    * output — the case the whole suppression machinery exists for.
    *
    * Reading an allocation profile of this: `signalTick` is ~9% of it and is
    * THIS METHOD'S fixture, not production — it mints the moved states each op,
    * which a real tick receives. Subtract it before taking any percentage here
    * as a share of a real tick.
    */
  @Benchmark
  def resumeSignals(bh: Blackhole): Unit = {
    wireRot += 1
    bh.consume(
      pull(signalled, heldAfterPaint, tickNodeIds, signalTick(wireRot), 1)
    )
  }

  /** '''The same tick on a card the cache key can actually protect''' — every
    * slot that reads the entity is a signal slot, so the entity is not in
    * `renderInputs` at all (ADR 0012) and a signal-only change cannot move the
    * key. The entry from the previous tick stands and nothing re-renders.
    *
    * The gap against [[resumeSignals]] is the cost of ONE non-signal slot. The
    * shipped `entityCard` has one — the name, which reads `friendly_name` as
    * bytes — and that is enough to put the entity back in the key, so a
    * brightness change re-renders the whole node to discover its bytes are
    * identical. `contentVersion` is per ENTITY and moves when anything about it
    * moves; nothing in the key can say that what moved reaches only a signal.
    *
    * So this is not an exotic fixture, it is the same dashboard with the name
    * held as a literal — and the difference between the two is what a
    * finer-grained key would be worth. It is the number to read before anything
    * else on this path.
    */
  @Benchmark
  def resumeSignalsPure(bh: Blackhole): Unit = {
    wireRot += 1
    bh.consume(pull(pure, heldPure, pureNodeIds, signalTick(wireRot), 1))
  }

  /** [[resumeSignals]] fanned to [[WireClients]] connections off ONE ring of
    * the doorbell, through ONE [[RenderCache]] — a house with ten tabs open.
    *
    * The gap against [[resumeSignals]] is what a tick actually costs per extra
    * client, and it is the number the cache exists to hold down: the render is
    * paid once and every further client should be paying only for its own
    * decision and its own frame. Ten times [[resumeSignals]] would mean the
    * cache is buying nothing.
    */
  @Benchmark
  def resumeSignalsFanout(bh: Blackhole): Unit = {
    wireRot += 1
    bh.consume(
      pull(
        signalled,
        heldAfterPaint,
        tickNodeIds,
        signalTick(wireRot),
        WireClients
      )
    )
  }

  /** '''A flip, pulled by every open connection.''' A state group's branch
    * changed, so each session re-supplies that host wholesale for its own
    * selection — the one path that renders a SUBTREE per connection rather than
    * a node, and the one that goes through no `RenderCache`
    * ([[https://github.com/perok/functional-home-assistant/issues/224 issue
    * #224]]).
    *
    * [[WireClients]] of them, because the waste this prices is per connection:
    * one client's flip is a fair cost, ten clients rendering identical bytes is
    * the thing worth removing. Against [[resumeFlipOne]] the gap IS the
    * duplication.
    */
  @Benchmark
  def resumeFlip(bh: Blackhole): Unit = {
    flipRot += 1
    bh.consume(pullFlip(WireClients))
  }

  /** The same flip for ONE connection — the floor the fanout is measured
    * against, and what a single-viewer house actually pays.
    */
  @Benchmark
  def resumeFlipOne(bh: Blackhole): Unit = {
    flipRot += 1
    bh.consume(pullFlip(1))
  }

  /** One flip, replayed from a cursor before it, for `clients` connections.
    *
    * The log carries the mutation alone: a flip is recorded structurally
    * (`Mutation.Placed` on the group, keyed by the arriving SURFACE), and that
    * is the whole of what a resuming client is owed for it. `holds` is empty
    * because a client that just missed a flip knows nothing about what is
    * inside the host it is about to be handed.
    */
  private def pullFlip(clients: Int): List[List[Addressed]] = {
    val at = flipRot.toLong
    val log = FragmentLog("bench").placed(
      FlipContainer,
      MemberKey.Surface("else"),
      flipped.surfaceContentId("else"),
      at
    )
    (1 to clients).toList
      .traverse(_ =>
        Patches.resume(flipped, flipCache, log, Map.empty, flipStates, at)
      )
      .unsafeRunSync()
  }

  /** '''The publisher half of a frame''' — `Patches.plan` + `Patches.record`,
    * the pass `Server.recordFrame` runs ONCE PER SLUG however many browsers are
    * connected.
    *
    * Everything else on this class prices the SESSION half, which scales with
    * tabs. This one scales with the HA EVENT RATE, so it is what a busy house
    * pays on a Pi whether anybody is looking or not, and until now nothing
    * measured it.
    *
    * No rendering happens here and none should: the pass decides WHICH nodes a
    * frame touched (the reverse index), what a candidate set's membership
    * became, and which state groups flipped. A number that moves with the leaf
    * count is the reverse index; one that moves with the CANDIDATE count is the
    * membership rebuild.
    */
  @Benchmark
  def publish(bh: Blackhole): Unit = {
    pubRot += 1
    bh.consume(publishPass(signalled, pubFrames(pubRot % 2)))
  }

  /** The same frame against a dashboard that is one candidate set, so
    * `MemberGraph.syncMembers` rebuilds a touched container's whole member list
    * — O(candidates), which the tree fixture never pays.
    */
  @Benchmark
  def publishSet(bh: Blackhole): Unit = {
    pubRot += 1
    bh.consume(publishPass(set, pubFrames(pubRot % 2)))
  }

  /** The same frame plus the entity that CHOOSES a branch, so the pass also
    * walks the state groups (`affectedStateGroups`, `activeStateSurfaces`).
    *
    * Alternating armed/disarmed means every call really flips, which is the
    * worst case rather than the common one — a condition that holds still
    * leaves after the first comparison.
    */
  @Benchmark
  def publishFlip(bh: Blackhole): Unit = {
    pubRot += 1
    val frame = flipFrames(pubRot % 2)
    val was = frame.states(FlipEntity)
    val now = was.copy(state = if (pubRot % 2 == 0) "armed" else "disarmed")
    bh.consume(
      publishPass(
        flipped,
        Frame(
          StateChange(FlipEntity, Some(was), now) :: frame.changes,
          frame.states.updated(FlipEntity, now)
        )
      )
    )
  }

  /** '''The ingest''' — `StateStore.update`, once per `subscribe_entities`
    * frame, before any of the above runs. Also per event rather than per tab.
    */
  @Benchmark
  def storeIngest(bh: Blackhole): Unit = {
    pubRot += 1
    // Re-stamped per invocation and NOT hoisted into setup: `stale` drops a
    // Replace that is not strictly newer, so a fixed pair of frames would be
    // applied once and refused forever after. The 20 copies are counted.
    val at = BaseInstant.plusSeconds(pubRot.toLong)
    bh.consume(
      store
        .update(pubIngests(pubRot % 2).map {
          case Ingest.Replace(s) =>
            Ingest.Replace(s.copy(lastUpdated = Some(at)))
          case other => other
        })
        .unsafeRunSync()
    )
  }

  /** The same batch when nothing in it actually moved.
    *
    * '''This is the reconnect path, not a corner.''' A new subscription
    * re-sends every entity as a `Replace`, and `StateStore`'s dedup is what
    * makes that cheap — the doc says so and nothing measured it. The store is
    * seeded with exactly these values, so every invocation takes that path
    * rather than only the ones after the first.
    *
    * The values carry the timestamps they were seeded with, and that is the
    * point: a steady HA re-sends the same `last_updated`, so
    * `EntityState.stale` refuses each Replace ABOVE the dedup and the batch
    * costs about what [[storeIngestEmpty]] does. Strip the timestamps off the
    * fixture and it takes a path production never takes — which is what an
    * earlier reading of this bench measured and blamed on the store.
    *
    * Even so it prices only the smaller half of the claim. Dedup's real payoff
    * is the frame that then does not happen ([[publish]] and everything
    * downstream of it), which no bench here reaches.
    */
  @Benchmark
  def storeIngestDedup(bh: Blackhole): Unit =
    bh.consume(store.update(dedupBatch).unsafeRunSync())

  /** The CONTROL for the two above: an empty batch, so everything they report
    * beyond this number is the batch and everything at or below it is the
    * `Ref.modify` and the `unsafeRunSync` round trip.
    *
    * Without it the pair reads as "an ingest costs 10 µs", when most of that is
    * the harness entering the runtime once per invocation — which production
    * does not do per frame.
    */
  @Benchmark
  def storeIngestEmpty(bh: Blackhole): Unit =
    bh.consume(store.update(Nil).unsafeRunSync())

  /** [[TickEntities]] entities whose content really moved, carrying the values
    * they moved FROM — `beforeSnapshot` rewinds through `previous`, so a change
    * that lied about it would hand the membership diff an instant that never
    * existed.
    *
    * Setup-only: see [[pubFrames]].
    */
  private def publishFrame(
      base: Map[String, EntityState],
      rot: Int
  ): Frame = {
    val moved = tickEntities.toList.map { id =>
      val prev = base(id)
      val next = prev.copy(
        state = if (rot % 2 == 0) "on" else "off",
        attributes = prev.attributes
          .updated("brightness", Json.fromInt(1 + (rot * 13) % 254))
      )
      StateChange(id, Some(prev), next)
    }
    Frame(
      moved,
      moved.foldLeft(base)((m, c) => m.updated(c.entityId, c.current))
    )
  }

  /** `Server.recordFrame` with the `Ref`s and the nobody-is-watching gate taken
    * off: the four pure steps a frame costs, in their production order.
    *
    * A fresh log per call, because `record` is a fold over one and a log that
    * grew across iterations would price its own history rather than the frame.
    */
  private def publishPass(renderer: Renderer, frame: Frame): FragmentLog = {
    val Frame(changes, states) = frame
    val before = Patches.beforeSnapshot(states, changes)
    val membership = renderer.members.syncMembers(changes, before, states)
    val req = Patches.plan(
      renderer,
      states,
      before,
      membership,
      pubRot.toLong,
      changes,
      Set.empty
    )
    Patches.record(renderer, FragmentLog("bench"), req)
  }

  /** The batch [[storeIngestDedup]] replays — the store's own seeded values, so
    * nothing in it can be a change.
    */
  private def dedupIngests: List[Ingest] =
    tickEntities.toList.map(id => Ingest.Replace(flipStates(id)))

  /** [[publish]] is only a selection bench if the selection selects. A frame
    * whose entities reach no node records an empty log, which still runs and
    * still reports a number.
    */
  private def checkPublishShape(): Unit = {
    val frame = pubFrames(1)
    val plain = publishPass(signalled, frame)
    if (plain.fragments.isEmpty)
      sys.error("publish selected no nodes — the reverse index found nothing")
    val members = publishPass(set, frame)
    if (members.fragments.isEmpty && members.mutations.isEmpty)
      sys.error("publishSet recorded neither a fragment nor a membership move")
  }

  /** The same pull when the movement is in the BYTES — a `friendly_name`
    * change, which no slot carries as a signal — so every candidate really is a
    * morph and nothing is suppressed.
    *
    * Against [[resumeSignals]] this is what ADR 0017 buys on the live path,
    * measured where its argument actually lives. ([[pageSignals]] against
    * [[page]] prices signal slots on a FIRST PAINT, which is the case ADR 0017
    * is not about and where it is a straight cost.)
    */
  @Benchmark
  def resumeMorphs(bh: Blackhole): Unit = {
    wireRot += 1
    bh.consume(
      pull(signalled, heldAfterPaint, tickNodeIds, byteTick(wireRot), 1)
    )
  }

  /** One doorbell ring, `clients` sessions — the real shape of a tick. Each
    * client resumes from the version before this one against its own `holds`,
    * which is what makes the suppression decision per client.
    *
    * '''The cache is the slug's and OUTLIVES the op''', because in production
    * it does: `LiveSlug.cache` is created once and every tick reads it. A
    * `RenderCache.create` per op — which this did at first — makes every node
    * of every tick a cold miss, so it prices a render the running server would
    * often not do, and it hides the one thing worth knowing here (whether a
    * signals tick moves the cache key). It also made `resumeSignals` and
    * `resumeMorphs` come out the same, which is what sent us looking.
    *
    * `contentVersion` moves per tick for the same reason: `StateStore.update`
    * stamps it whenever `sameContent` says an entity moved, so a fixture that
    * left it at 0 would hit the cache forever and price nothing.
    */
  private def pull(
      renderer: Renderer,
      held: Map[NodeId, Held],
      nodeIds: Vector[NodeId],
      moved: Map[String, EntityState],
      clients: Int
  ): List[List[Addressed]] = {
    val at = wireRot.toLong
    val log = nodeIds.foldLeft(FragmentLog("bench"))(_.touched(_, at))
    (1 to clients).toList
      .traverse { _ =>
        Patches.resume(
          renderer,
          liveCache,
          log,
          held,
          moved,
          at,
          Set.empty,
          Map.empty
        )
      }
      .unsafeRunSync()
  }

  /** A tick that moves ONLY what signal slots read (`brightness`, and the state
    * word), so the patch form is byte-identical and the morph is suppressed.
    *
    * `contentVersion = rot` because `StateStore.update` stamps it whenever an
    * entity's content moved at all — a brightness change included. That is
    * exactly why this tick still MISSES the cache on the shipped card: the
    * node's key holds the entity (its name slot reads it as bytes), the version
    * under it moved, and nothing in the key can tell that the attribute which
    * moved reaches only signal slots. See [[resumeSignalsPure]].
    */
  private def signalTick(rot: Int): Map[String, EntityState] =
    tickEntities.zipWithIndex.foldLeft(st) { case (acc, (e, i)) =>
      acc.updated(
        e,
        EntityState(
          e,
          if ((rot + i) % 2 == 0) "on" else "off",
          Map(
            // UNCHANGED: this is the only attribute the bytes carry.
            "friendly_name" -> Json.fromString(s"Tile number $i"),
            "brightness" -> Json.fromInt(1 + (rot * 7 + i * 13) % 254),
            "unit_of_measurement" -> Json.fromString("lx")
          ),
          contentVersion = rot.toLong
        )
      )
    }

  /** A tick that moves the NAME, which no slot carries as a signal — so every
    * candidate's bytes really move and every morph is sent.
    */
  private def byteTick(rot: Int): Map[String, EntityState] =
    tickEntities.zipWithIndex.foldLeft(st) { case (acc, (e, i)) =>
      acc.updated(
        e,
        EntityState(
          e,
          "on",
          Map(
            "friendly_name" -> Json.fromString(s"Tile $rot number $i"),
            "brightness" -> Json.fromInt(128),
            "unit_of_measurement" -> Json.fromString("lx")
          ),
          contentVersion = rot.toLong
        )
      )
    }

  /** '''What thread C's pre-check would COST''', for the tick's twenty nodes.
    *
    * The check resolves only the slots that travel as BYTES and compares them
    * to what the cache entry was built from. On the shipped `entityCard` that
    * is exactly one slot — the name, an `AttrOrId` through the production
    * dispatch ([[Transforms.run]], ADR 0028's fast tier, no engine). The signal
    * slots are NOT in it: they are resolved on a signals tick regardless, to
    * fill the frame.
    *
    * Against [[tickRender]] — the render this replaces, same twenty nodes —
    * this is the whole trade, and it is the number that says whether thread C
    * is worth building. It deliberately does NOT go through the (private)
    * `Resolved` seam: a pre-check that built the full `Resolved` would resolve
    * every slot and give most of the saving back, which is exactly the risk
    * worth pricing before writing any of it.
    */
  @Benchmark
  def byteSlotResolve(bh: Blackhole): Unit = {
    val moved = signalTick(wireRot)
    var i = 0
    while (i < tickEntities.size) {
      val e = moved(tickEntities(i))
      bh.consume(transforms.run(NameSlot, e))
      i += 1
    }
  }

  /** The render [[byteSlotResolve]] would replace: a cache-missing
    * `renderNodeById` for the same twenty nodes, which is what a signals tick
    * pays today per client for every node it then suppresses.
    *
    * [[wireTick]] is the same call at three nodes; this one is sized to the
    * tick so the two halves of the trade are directly comparable.
    */
  @Benchmark
  def tickRender(bh: Blackhole): Unit = {
    val moved = signalTick(wireRot)
    tickNodeIds.foreach(id =>
      bh.consume(signalled.renderNodeById(id, moved, Map.empty))
    )
  }

  /** '''The tick that decides it, unshared.''' The COMMON value tick on a
    * signal-slot dashboard: one entity moved, its digest stood still, so no
    * morph goes out — just a `datastar-patch-signals` frame carrying the two
    * signal entries, with the cursor merged into it by [[Patches.encode]]
    * (adjacent signal frames join, which is every batch nothing separates).
    *
    * Every client is owed the SAME values, which is the case sharing is about
    * and the case [[wire]] deliberately is not: ten tabs on one dashboard, the
    * same tab selected. The cursor is the same for all of them too — it names
    * the version the doorbell rang at, not anything per-client. So all
    * [[WireClients]] frames here are byte-identical and the whole encode is
    * duplicated work.
    *
    * Against [[wireCommonShared]] this is the ceiling on what minting the frame
    * once per slug could buy. Measured, `-f 2 -wi 5 -i 5`, ten clients:
    *
    * {{{
    * wireCommon        32.459 ± 3.372 us/op   each client encodes
    * wireCommonShared   3.140 ± 0.459 us/op   encoded once, ten reuse
    * wireTick          18.368 ± 2.022 us/op   the slug-shared render
    * }}}
    *
    * 10.3x, and linear in the client count — 3.2 us per encode, ten of them —
    * which says the per-client cost on this path is ENTIRELY the encode.
    * Nothing else is in it. The 29 us of duplicated work is more than the whole
    * slug-shared render of the same tick, and it grows with every open tab
    * while the render does not.
    */
  @Benchmark
  def wireCommon(bh: Blackhole): Unit = {
    val eid = entityId(wireRot % Leaves)
    var c = 0
    while (c < WireClients) {
      Patches.encode(commonTick(eid)).foreach(e => bh.consume(e.bytes))
      c += 1
    }
    wireRot += 1
  }

  /** '''The same tick, shared.''' One encode for the slug; every client is
    * handed the frame already in hand.
    *
    * The gap against [[wireCommon]] is what a per-slug frame memo could save,
    * MINUS the lookup such a memo would cost per client — which this does not
    * model, so the real saving is smaller than the gap. Read it as an upper
    * bound: if the bound is not worth having, the memo certainly is not.
    */
  @Benchmark
  def wireCommonShared(bh: Blackhole): Unit = {
    val eid = entityId(wireRot % Leaves)
    val shared = Patches.encode(commonTick(eid))
    var c = 0
    while (c < WireClients) {
      shared.foreach(e => bh.consume(e.bytes))
      c += 1
    }
    wireRot += 1
  }

  /** One client's patches for the common tick — built fresh per call, because
    * `Patches.resume` builds them fresh per session and the point of the pair
    * above is which half of that survives sharing.
    */
  private def commonTick(eid: String): List[Addressed] =
    List(
      Addressed(
        Patch.Signals(
          Map(
            SignalId.derived(s"_e.$eid.state") -> Json.fromString("on"),
            SignalId.derived(s"_e.$eid.style.background") ->
              Json.fromString("#ffb46b")
          )
        )
      ),
      Addressed(
        Patch.Signals(
          Map(SignalId.derived("_v.store") -> Json.fromLong(wireRot.toLong))
        )
      )
    )

  /** The RENDER half of the same tick: one node's patch bytes against a state
    * snapshot the tick just moved — a cache-missing `renderNodeById`, which is
    * what a live tick renders per slug when the entity actually changed.
    *
    * Against [[wire]] this prices the whole per-tick budget: the render is the
    * slug-shared half, the wire the per-connection half.
    */
  @Benchmark
  def wireTick(bh: Blackhole): Unit = {
    val rot = wireRot % Leaves
    val eid = entityId(rot)
    val moved = st.updated(
      eid,
      EntityState(
        eid,
        if (rot % 2 == 0) "on" else "off",
        Map(
          "friendly_name" -> Json.fromString(s"Tile number $rot"),
          "brightness" -> Json.fromInt(rot * 13 % 254),
          "unit_of_measurement" -> Json.fromString("lx")
        )
      )
    )
    wireNodeIds.foreach { id =>
      bh.consume(signalled.renderNodeById(id, moved, Map.empty))
    }
    wireRot += 1
  }
}

object RenderBench {

  /** One frame as the feed delivers it: the changes, and the states they leave
    * behind. Paired because a pass needs both and they must agree.
    */
  final case class Frame(
      changes: List[StateChange],
      states: Map[String, EntityState]
  )

  /** A discard that still counts, so the walk's bytes cannot be optimised away.
    *
    * `OutputStream.nullOutputStream()` would discard them, but it gives nothing
    * to hand a `Blackhole` — and a page whose bytes provably go nowhere is a
    * page the JIT may decline to produce.
    */
  final class CountingOutputStream extends java.io.OutputStream {
    var count: Long = 0L
    override def write(b: Int): Unit = count += 1
    override def write(b: Array[Byte], off: Int, len: Int): Unit = count += len
  }

  /** 200 leaves — the "big page" #213 measured, so the numbers are comparable
    * to the ones already recorded on issue #130.
    */
  final val Leaves = 200

  /** Open connections the [[RenderBench.wire]] tick fans to — a house with ten
    * tabs open. The per-client cost multiplies by exactly this.
    */
  final val WireClients = 10

  /** Entities one tick moves. A real `state_changed` burst is a handful, not
    * the whole house: a light group answering one tap, a few sensors on the
    * same poll. Twenty of two hundred leaves.
    */
  final val TickEntities = 20

  /** The shipped `entityCard`'s one BYTE slot — the name. Every other slot on
    * it is either a literal or travels as a signal, which is precisely why one
    * slot is what re-admits the entity to the cache key (ADR 0012).
    */
  final val NameSlot: Transform.Simple =
    Transform.Simple.AttrOrId("friendly_name")

  /** Distinct entities behind [[RenderBench.pageShared]]'s leaves. */
  final val Distinct = 40

  // The transform shapes the shipped components use today, in the exact CEL
  // bytes they ship (single-sourced from [[CelShapes]] — the renderer's probe
  // dashboards must compile under the shipped engine, which no longer speaks
  // in the mix. The JSONata counterparts these replaced live on [[Jsonata]] as
  // the reference the divergence gate ([[CelSpike]]) runs.
  final val TransformName = CelShapes.TransformName
  final val TransformUnit = CelShapes.TransformUnit
  final val TransformFill = CelShapes.TransformFill
  final val TransformPercent = CelShapes.TransformPercent
  final val TransformFillColor = CelShapes.TransformFillColor
  final val TransformAttrLines = CelShapes.TransformAttrLines
  final val TransformComplex = CelShapes.TransformComplex

  /** The shapes [[RenderBench.simple]] runs through the production dispatch
    * ([[Transforms.run]]): the six live shapes, each precompiled into the warm
    * map — plus [[TransformComplex]] for [[RenderBench.celComplex]], which the
    * catalog deliberately does not recognise.
    */
  final val LiveTransforms = List(
    TransformName,
    TransformUnit,
    TransformFill,
    TransformPercent,
    TransformFillColor,
    TransformAttrLines
  )
  final val ProbeTransforms = LiveTransforms :+ TransformComplex

  /** A tap's action as the builder bakes it for one entity (ADR 0016): a
    * build-time literal per entity, so the card's action hole stays filled but
    * no engine runs for it.
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

  /** @param signalOnly
    *   hold the NAME as a literal, so no slot reads the entity as bytes and the
    *   entity drops out of `renderInputs` entirely (ADR 0012). Not a shipped
    *   shape — the shipped card's name reads `friendly_name` — but the
    *   difference between the two is exactly what one byte-reading slot costs
    *   the cache key, which is what [[RenderBench.resumeSignalsPure]] prices.
    */
  def leaf(
      signals: Boolean,
      distinct: Int = Int.MaxValue,
      signalOnly: Boolean = false
  )(
      i: Int
  ): LayoutNode.Component =
    LayoutNode.Component(
      "entity",
      Map(
        "entity_id" -> SlotSource(literal = Some(entityId(i % distinct))),
        "cls" -> SlotSource(literal = Some("fh-tile")),
        // Literal now (entity.pkl): the entity's icon is a registry fact.
        "icon" -> SlotSource(literal = Some("mdi:lightbulb")),
        "name" ->
          (if (signalOnly) SlotSource(literal = Some(s"Tile number $i"))
           else
             SlotSource(
               // Opted in (ADR 0028): the shipped entity card's name shape.
               transform = Transform.Simple.AttrOrId("friendly_name")
             )),
        "state" -> SlotSource(
          transform = Transform.Simple.UnitSuffix("unit_of_measurement"),
          signal = Option.when(signals)(SignalBind.Text)
        ),
        "fill" -> SlotSource(
          transform = Transform.Simple.Fill("brightness", 1.0, 255.0),
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
      signals: Boolean = true,
      distinct: Int = Int.MaxValue,
      signalOnly: Boolean = false
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
    stack(List.tabulate(leaves)(leaf(signals, distinct, signalOnly)))
  }

  /** Leaves in EACH branch of the flip fixture. Smaller than [[Leaves]] on
    * purpose: a state group holds a section of a dashboard, not the whole page,
    * and the number that matters for a fill is what one host contains.
    */
  final val FlipLeaves = 20

  /** The entity whose state chooses the branch — an alarm, the shipped shape
    * for a state-activated surface.
    */
  final val FlipEntity = "alarm.house"

  /** A state group under the root column: an `ifhost` whose one baked region
    * holds whichever branch the state picks, each branch a real subtree of
    * [[FlipLeaves]] entity cards.
    *
    * This is the fixture the FILL benches need and nothing else here had — the
    * page benches are one tree with no surfaces in it, so a wholesale host
    * re-supply was unmeasured (issue #224).
    */
  def flipDash(signals: Boolean = true): Dashboard =
    Dashboard(
      cards = cards + ("ifhost" -> CardDef(
        template = """<div id="{{hostId}}">{{{branch}}}</div>""",
        regions = Map("branch" -> Region(Region.Baked))
      )),
      card = LayoutNode.Component(
        "col",
        regions = LayoutNode.kids(LayoutNode.Component("ifhost"))
      ),
      surfaces = Map(
        "then" -> branchSurface(
          tree(FlipLeaves, 4, signals),
          0,
          Predicate.Cmp(
            "state",
            Op.Eq,
            Json.fromString("armed"),
            entity = Some(FlipEntity)
          )
        ),
        // An empty conjunction is vacuously true and reads no entity, which is
        // what an `else` branch is.
        "else" -> branchSurface(
          tree(FlipLeaves, 4, signals),
          1,
          Predicate.And(Nil)
        )
      )
    )

  /** The host the two branches bake into is the root column's only child, so
    * its id is `c_0` by the same derivation every other node here uses.
    */
  final val FlipContainer: NodeId = NodeId.derived("c_0")

  private def branchSurface(
      content: LayoutNode,
      index: Int,
      condition: Predicate
  ): Surface =
    Surface(
      content,
      bakeInto = Some(FlipContainer),
      bakeAs = Some("branch"),
      bakeIndex = Some(index),
      activation = Activation.State(condition)
    )

  /** The same leaves, as one candidate set: every entity a candidate, each with
    * a single unguarded clause rendering the same card. Membership is therefore
    * total, so this measures the RENDER path rather than predicate evaluation.
    *
    * `signals` toggles the member leaves' signal slots: `true` is the worst
    * case (every member carries a signal slot, so its patch fingerprint needs
    * its own render), `false` the signal-less case where the two forms are
    * byte-identical and the fingerprint is sliced from the document bytes.
    */
  def setTree(leaves: Int, signals: Boolean = true): LayoutNode =
    LayoutNode.Component(
      "col",
      regions = LayoutNode.kids(
        LayoutNode.SetNode(
          candidates = List.tabulate(leaves)(entityId),
          members = List
            .tabulate(leaves)(i =>
              entityId(i) -> LayoutNode.SetMember(
                List(LayoutNode.SetClause(node = leaf(signals)(i)))
              )
            )
            .toMap
        )
      )
    )

  /** Every fixture state carries one, because a real feed always does (measured
    * against the live instance: 0 of 1070 entities without a `last_updated`). A
    * store fixture without them takes paths production never takes —
    * `EntityState.stale` short-circuits on the timestamp, so a `Replace` that
    * has none reaches a dedup it would otherwise never see.
    */
  final val BaseInstant: java.time.Instant =
    java.time.Instant.parse("2026-09-04T06:00:00Z")

  def states(leaves: Int): Map[String, EntityState] =
    List
      .tabulate(leaves) { i =>
        entityId(i) -> EntityState(
          entityId(i),
          if (i % 3 == 0) "off" else "on",
          lastUpdated = Some(BaseInstant),
          attributes = Map(
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
