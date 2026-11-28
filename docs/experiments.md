# Experiments

Measurements and spikes whose answer was "don't build it", kept so they are not rerun from
scratch. Each entry has the question, how it was measured, the numbers and the verdict, and what
would make it worth asking again. An experiment that led to a change belongs in that change's PR
or ADR, not here. The section below is the exception: how to measure, learned the hard way.

## Before measuring

- An `alloc.rate.norm` change in code the diff never runs is usually the JIT, not an allocation:
  rerun both sides with `-jvmArgsAppend -XX:-DoEscapeAnalysis`. Equal numbers there mean nothing
  new is allocated (#657: `publishIdle` +640 B and `pageSetPlain` +14 KB were this).
- Pass JFR as `-jvmArgsAppend -XX:StartFlightRecording=settings=…,filename=…`, not
  `-prof jfr:a;b`: sbt reads the `;` as a command separator and drops the benchmark filter.
  The same trap takes the async profiler's options; quoted inside the sbt command they survive:
  `sbt 'benchmarks/Jmh/run -prof "async:libPath=…;event=alloc;output=collapsed;dir=…" …'`.
  Its alloc profile also counts a `Level.Invocation` setup (`ValidateBench`'s decode, ~24%).

Recorded elsewhere:

- [`datastar-vs-htmx.md`](datastar-vs-htmx.md): stay on Datastar (#149).
- Draft PR #328: one rendering shared by many entities, concluded not to build (#108).
- Draft PR #371: Pkl never runs compiled; native-packager works on sbt 2.
- Draft PR #163: Nickel as the authoring language.

## Fixed-size collections for the renderer's indexes and a session's holds

2026-10-22, main at 0f013206. **Not worth doing.**

**Question.** Would arrays or presized maps, in place of `List` and `Map`, cut memory where a
collection's size is known when it is built? The candidates were the static indexes a renderer
builds once (`MemberGraph`, `NodeAncestry`, `PickGraph`, `allIndexed`) and the per-session
`holds`.

**How.** A throwaway `object` in `modules/benchmarks` (package `fh.view.runtime`) ran
`new RenderBench().setup()` and read its fixtures by reflection. JOL 0.17 (`jol-core`, already in
the coursier cache) was called by reflection, so it was not a dependency, and gave each object
graph's deep size, by class. What X adds to an owner is `size(owner, X) - size(owner)`.

```
java -Djol.magicFieldOffset=true \
  -cp <benchmarks classes>:<export benchmarks/Runtime/fullClasspath>:<jol-core-0.17.jar> <probe>
```

**Numbers.** Two fixtures: a 200-leaf tree (`signalled`) and a 200-member set (`set`).

| Per dashboard (one renderer) | tree | set |
|---|---|---|
| Renderer, deep | 1,543 KiB | 1,566 KiB |
| … of which the `Dashboard` it was built from | 295 KiB | 326 KiB |
| … the compiled CEL (`transforms`) | 358 KiB | 358 KiB |
| … `mainIndex` + `allIndexed` + `members` + `ancestry` + `surfaces` | 178 KiB | about 250 KiB |

The index rows overlap, so their sum is an upper bound. By class, `String` and its `byte[]` are
about 45% of a renderer. Collection nodes (`::`, CHAMP map nodes and their arrays, `Map3`,
tuples, `Some`) are about 25%, roughly 400 KiB.

| Per session (one browser) | tree | set |
|---|---|---|
| `holds` beyond what the renderer already holds | 67 KiB | 67 KiB |
| … of which the 200 digests (32-char hex `String` + `Some`) | 17 KiB | 17 KiB |

For scale, the add-on process is 410 to 510 MB RSS at boot, with and without draft #471's heap
flags, and a bare JVM is about 116 MB of that (the 0.1.44 memory audit).

**Verdict.** Halving every collection in a renderer would save about 200 KiB per dashboard, which
is about 0.05% of the process. A session's per-node signal maps are already Scala's specialised
`Map1`–`Map4`, and `holds` itself changes with every patch, so it has no fixed size to exploit.
Strings are the larger share, but deduplicating them is also bounded by well under 1 MiB per
dashboard.

**Worth asking again if** a dashboard reaches thousands of nodes or open sessions reach the
hundreds: then rerun the probe on that dashboard before choosing a structure.

## A digest's two `Long`s inline in `Held`

2026-10-22, the same probe. **Not worth doing.**

`Digest` (`FragmentLog.scala`) is a `case class` of two `Long`s, a 32-byte object, held as
`Option[Digest]` in `Held`, which adds a 16-byte `Some`. Inlining the two `Long`s into `Held`, with
a flag in place of the `Option`, would save about 48 B per node more: under 10 KiB per open
browser on a 200-node page. It changes `Held`'s shape, `merge` and the 20 places that construct
one.

When `Digest` was still a 32-character hex `String` (72 bytes with its `byte[]`), this entry
declined the `case class` too, saying speed would not change. A profile showed otherwise:
`HashCode.toString` was about 30% of making a digest, 1.8% of `RenderBench.page`. As two `Long`s,
`holdsSeed` (200 digests) went from 50.6 / 48.1 µs to 34.8 / 34.1 µs and from 179,136 to 158,336
B/op, in two alternating runs each with `-prof gc`. `page` and `pageSignals` moved within noise.

**Worth asking again if** the per-session figure above starts to matter, for the same reasons as
the previous entry.

## Sharing one render across a repaint storm

Measured on #130, recorded here 2026-10-24. **Not worth doing.**

**Question.** After a dashboard edit, the swap's repaint renders the body once per connected session,
and N browsers loading at once each render the whole page. Would one shared render (the cache, or
one memoised body per set of viewer inputs) be worth having?

**How.** A spike on #130's branch timed `renderBody` on real cards.

| dashboard | nodes | HTML | `renderBody` |
|---|---|---|---|
| shipped starter | 15 | 33 kB | 0.19 ms |
| 200 real cards (sliders and entity cards) | 335 | 257 kB | 5.7 ms |

Ten coincident loads of the 200-card page cost 57 ms of CPU between them while shipping 2.5 MB:
the render is about 2% of the work it feeds. A repaint storm follows a manual edit in the editor,
never anything a viewer does. The numbers predate CEL and #620, both of which made a render
cheaper.

**Worth asking again if** a deployment shows page renders on a profile, or sessions per dashboard
reach the dozens. #130 stays open for the effectful walk itself, which is wanted for other
reasons.

## One lookup map per plan for a template's names

2026-10-24, on #620. **Not worth doing.**

**Question.** A template hole is looked up through up to six maps in turn
(`NodeContext.fhGet`: bindings, live bindings, paint, constants, bake, structural), and
async-profiler put `String.equals` at 8% of `RenderBench.tickRender`'s own time. Would merging the
layers a paint cannot change (structural, constants, bindings) into one `java.util.HashMap` on the
`NodePlan`, built once, cut that?

**How.** That change, the suite green, then `-f 2 -wi 4 -i 5 -prof gc` before and after.

| µs/op | before | after |
|---|---|---|
| `page` | 612 ± 59 | 612 ± 44 |
| `pageSignals` | 799 ± 52 | 827 ± 62 |
| `tickRender` | 33.3 ± 2.9 | 33.6 ± 2.3 |
| `mustache` | 110 ± 8 | 117 ± 14 |

Allocation was unchanged (66.5 kB per `tickRender`). The maps are small enough that the layered
lookup costs about what one hash lookup does.

**Where `String.equals` goes.** Grouped by caller (async-profiler, collapsed stacks), 93% of it is
Scala's `==` on map and list keys (`BoxesRunTime.equals2`). Half is in `fhGet`: `List.contains`
over the signal slots for every hole of the patch form (18.5%), and the one comparison that
confirms each hash-map hit (about 30%). A hit compares characters because the template's name and
the map's key are different `String` objects. The rest builds the paint map, looks up `states` and
checks `EntityState.unavailable`. All of it is 8.6% of a tick, about 3 µs.

A `Set` for the signal slots and `unavailable` as a `val` were kept: `tickRender` 32.9 ± 1.5 →
30.1 ± 0.6 µs, and 31.7 ± 1.5 → 29.8 ± 1.3 in a second run.

**Interning, also not worth doing.** On top of those, interning the plan's keys and the template's
names (an override of `SimpleObjectHandler.find`, which mustache.java also calls with `null`) made
each hit a reference comparison and took `String.equals` from 8.6% to 4.7% of a tick, but no time
off it: `tickRender` 29.8, `page` 574, `pageSignals` 720 µs, all within noise. What is left, about
1.4 µs a tick, is `states.get` (a third) and Scala's small `Set`/`Map` classes, which compare every
element on a miss; that bounds what interning entity ids or hash-based small collections could buy.
Integer ids instead of names would buy no more than interning: a `String` caches its hash, so only
the confirming comparison costs.

**Worth asking again if** a profile attributes the time to `fhGet` itself, not to the template
around it.

## The components as a property of `site.pkl` (`site.c`)

2026-10-25, on #629. **Not worth doing.** The workspace `components.pkl` replaced it.

**Question.** Can `site.pkl` hold the components (`hidden c: components`), so that one amendment
(`c { devices { … } }`) configures every card, and a dashboard in its own file takes the same `c`
by importing `site.pkl`?

**Measured** on pkl-core 0.32.1 and pkl-lsp 0.8.0, against a copy of the library and on the
branch. These parts worked:

- late binding reaches the facade's functions;
- the `site.pkl` ↔ `kitchen.pkl` import cycle evaluates;
- `Analyzer.importGraph` lists both files;
- the server repaints a dashboard file on its edit;
- an old `site.pkl` that imports `components.pkl` as `c` still builds, because the import shadows
  the property.

Two problems sank it:

- **Nested code can't see the property.** The starter's `(c.grid) { children { c.title(…) } }`
  fails with "Cannot find property `c`", because an inherited property resolves only at a file's
  top level. pkl-lsp shows no error there, only an empty completion list. Every file would need
  `c {}`, `c = site.c` or `module.c`. Giving each `entry` its own `c` fixed one level only.
- **A class can't be named through a value.** `new c.Column` fails with "Expected `c` to be a
  module import".

Both are deliberate in Pkl. [pkl#909](https://github.com/apple/pkl/issues/909) calls the scoping
"expected behavior and the intentional design". [Discussion
#865](https://github.com/apple/pkl/discussions/865) gives the reason: an inherited name could
silently change what a name in a child file means. [pkl#1150](https://github.com/apple/pkl/issues/1150)
(inheriting imports through `amends`) is open. [pkl#289](https://github.com/apple/pkl/issues/289)
got the same `module.` answer. No pkl-lsp or pkl-intellij issue covers the missing diagnostic.
IntelliJ was not tested.

**Worth asking again if** Pkl resolves inherited properties in nested scopes, or #1150 lands.

## A faster dispatch for the value-language evaluator

2026-11-04, slot-conditions at 0fc5b8cb. **Not worth doing.**

**Question.** Should `Evaluator` (ADR 0035), which compiles a reading into closures once, move
to one of the three usual faster designs: a flat opcode VM with an instruction loop, Truffle
nodes with `@Specialization`, or a class generated per reading (ASM, Byte Buddy, or JDK 25's
`java.lang.classfile`)?

**How.** `RenderBench` under the JMH stack sampler, one fork:
`benchmarks/Jmh/run -i 3 -wi 3 -f 1 -prof stack:top=40 .*RenderBench.(page|tickRender)`, the share
of RUNNABLE samples whose top frame is in `Evaluator` or `Transform`. Per-evaluation cost from
`condition` and `conditionTree` (200 evaluations each).

**Numbers.** A condition costs about 40 ns (8.4 µs for 200), `conditionTree` allocating 0 B.
`Evaluator`'s frames are 2.3% of `page` and 3.6% of `tickRender`, most of it the entity read, a
map lookup any design keeps. The CEL engine's own frames, Mustache and map building each weigh
more.

**Verdict, per design.**

- **Opcode VM:** made for short-lived code with little warm-up; a reading here is compiled once
  and runs on every state change. It swaps a virtual call per step for a switch per instruction,
  values still boxed on an operand stack that needs one per call or per thread: no gain expected.
- **Truffle:** on OpenJDK 25, which the add-on runs (a jlinked Temurin 25), the optimizing Truffle
  runtime is available only through a polyglot isolate (GraalVM's runtime support table);
  in-heap it is the fallback interpreter. Its DSL is a Java annotation processor, so the nodes
  would be Java. An isolate costs hundreds of MB on a Pi (ADR 0032).
- **A class per reading:** fits a standard JVM and a hot path, and removes the one real cost
  closures leave: a closure class per step kind is shared by every reading, so HotSpot cannot
  inline across steps. Bounded by the 2–4% above, about half of it realistically, for a hidden
  class per distinct reading (unloaded on reload), metaspace on a Pi, and generated code to debug.

**Worth asking again if** `Evaluator`'s frames pass about 10% of a `tickRender` profile. A class
per reading, through `java.lang.classfile` (no dependency), would be the one to try first.

## `Percent` and `Fill` parsing a text operand once

2026-11-08, value-model-small at e92be6b0. **Not worth doing.**

**Question.** `Steps` checks a slider's min and max with `isNum`, then reads them with `dbl`, so a
text operand is parsed twice. Is one parse (`dbl` answering `NaN` for no number) cheaper?

**How.** `RenderBench.tickRender`, `-f 1 -wi 5 -i 5 -prof gc -jvmArgsAppend
-XX:-DoEscapeAnalysis`. Three builds: base ee8f7997, the change, and the change with the old
`Steps.scala` put back. The base was run again with `-f 2`.

**Numbers.** The base allocates 100,576 B/op (both runs, ±0). The change allocates 101,536 B/op,
whether the operands are parsed before or after the value. The old `Steps.scala` gives 100,576
again. Time is the same within error, at 35–37 µs.

**Verdict.** It allocates 960 B more per tick and saves no time, so the old code stays. The cause
is not found: by count, both versions build one `Option` per text operand.

**Worth asking again if** a profile shows `Steps.dbl` or `isNum` in a slider tick's top frames.

## Sort keys with unboxed numbers

2026-11-08, value-model-small. **Not worth doing.**

**Question.** `Evaluator.Keys` holds each candidate's parsed number as a boxed `Double` in its
`Array[Any]`. Would a primitive layout pay: an `Array[Double]` beside the values for a leader's
two reused buffers, and for a sort one flat buffer for every candidate, ordered by an insertion
sort over indices? A SIMD compare was ruled out first: the keys come from map lookups and
compiled readings, mix booleans, numbers, text and no value, branch per comparison, and a set
holds 10–20 candidates.

**How.** Estimated from `RenderBench` with escape analysis off (`-prof gc -jvmArgsAppend
-XX:-DoEscapeAnalysis`), after the keys stopped allocating a closure and a second box per
candidate and a leader started reusing two buffers: `leaderRow` (10 pills over 10 candidates)
3,120 B/op, `publishSetOrdered` 575,903 B/op.

**Numbers.** One 16 B box per number: about 1.6 KB of `leaderRow`'s 3,120 B, and under 1% of
`publishSetOrdered`, whose bytes are membership and rendering, not keys.

**Verdict.** A second array per key, or a hand-written sort, for at most 1.6 KB per row of
leaders per tick.

**Worth asking again if** a profile shows `Keys.into` or `Keys.compare` in a tick's top frames,
or sets grow to hundreds of candidates.

## cel-java's Pratt parser

2026-11-14, cel-015. **No effect here.**

**Question.** cel 0.15 adds `CelOptions.enablePrattParser`, announced as parsing 6–13x faster
with 5–7x fewer allocations. Would validation get cheaper?

**How.** `ValidateBench.validated` over its four fixtures, the option off and on through a
system-property switch in one build: B/op with escape analysis off, µs with it on, two forks.

**Numbers.** Allocation equal to within 800 B (taps 3,576,463 → 3,576,450 B/op, branches
1,113,298 → 1,112,498); every time inside the error bars (taps 1,279 ± 85 → 1,434 ± 265 µs,
starter 832 ± 91 → 802 ± 61).

**Verdict.** `Cel` memoizes each source's parse and AST process-wide (`asts`, `names`), so a
source is parsed once per process and validation never shows the parser.

**Worth asking again if** those caches go (per-validation scoping), or a profile of a cold start
shows CEL parsing.

## A set's equal guards, compiled once and run once per decision

2026-11-15, set-guard-sharing (#708). **No effect here.**

**Question.** A `where` lands as one guard per candidate, so a set of N carries N equal copies.
Would compiling equal guards once make the build cheaper? And would running a guard that reads
no member (it names every entity it reads, so it answers every candidate alike) once per
decision, not once per candidate, make a set's decision cheaper?

**How.** Compile: equal guards (by `ValueExpr.inputKey`, not `key`, which leaves out the
source's entity) sharing one compiled `Condition` in `Ready.Compile.set` and `Evaluator.present`;
`ValidateBench.validated` before and after. Run: a per-decision memo of member-free guards'
answers (an `IdentityHashMap` by compiled run) in `MemberGraph`'s decision and
`Evaluator.present`. One round, escape analysis off, before and after:
`RenderBench.publishSetNamedGuard` (200 candidates, one guard naming a ticked entity, so every
frame re-decides all 200), `publishSetGuarded`, `leaderRow`, `tickRender`.

**Numbers.** Compile: `validated` equal within error on all four fixtures. Run:
`publishSetNamedGuard` 197 ± 91 → 173 ± 143 µs, inside the error bars, and 137,817 → 141,345
B/op; `leaderRow` 3,120 → 3,360 B/op for the memo; the rest equal.

**Verdict.** Both dropped. A guard's CEL program was already shared per scope
(`Evaluator.Scope`), so sharing the compile saved only a small wrapper. The guard run is a map
lookup and a compare; next to deciding and placing 200 members it is noise, and the memo cost
more bytes than it saved. What costs is re-deciding every candidate when the named entity
changes, which neither touches.

**Worth asking again if** a member-free guard gets expensive (a CEL guard over a fetch or a
large list), a profile of a set's decision shows guard runs in its top frames, or the guard
moves to the set on the wire, which would make one compile and one decision fall out.

## A set's moved members, without copying its list per patch

When an ordered set's sort order changes, or members arrive or leave, a session is sent one
leave and one place per moved member, and `Patches.applied` copies the host's whole held list
(`Held.shows`) for each one, boxing as it compares. Is folding them worth a rewrite?

**How.** `RenderBench.publishSetOrderedFanout`: 200 members ordered by brightness, a frame
turning 20 lights on or off, so 20 move; ten sessions that fold what they were sent, as `Server`
does. Allocation profile (async-profiler, escape analysis off) after D6's A (a leave removes
its subtree's range, `longestRun` in loops).

**Numbers.** 4.78 MB/op for the ten sessions, ~450 KB each. Of the whole, the list copies in
`applied` (each leave's `filterNot`, each place's `placedIn`) are ~36% and the plan's position
map and run ~29%.

**Verdict.** Not built. The fix is hand-written, boxing-free list helpers, and the cost is
O(moved × members): it matters for a 200-member set reordering 20 at once, not for a room's
set, and a sort order changes far less often than members tick.

**Worth asking again if** a dashboard with a large ordered set shows the pull in a profile, or
a set's reorders become frequent (an order on a fast-moving value, such as power draw).

## A viewer's open cases, decided once a pull

`PickGraph.chosen` (the case roots a viewer's values pick) is derived again by each caller in
one pull: `Patches.owing`, and `Renderer.caseAsks` through `PickGraph.shownWithin`. Is passing
one answer down worth it?

**How.** `RenderBench.viewerPull` (a tab bar and an If over a variable, a 4-light set, ten
sessions), allocation profile (async-profiler, escape analysis off) after D6's D.

**Numbers.** 207,092 B/op; `chosen` is ~9% of it overall, ~4% under `owing` and about as much
from `caseAsks`.

**Verdict.** Not built. Sharing it means a new parameter threaded from the pull through
`Renderer`'s reads, for a few percent of a pull that is mostly per session anyway.

**Worth asking again if** a dashboard with many variable or viewer picks shows `chosen` near the
top of a pull's profile, or a third caller starts deriving it.

## The fragment log, without a map update per touched node

`Patches.record` marks each touched node with `FragmentLog.touched`, one immutable map update
each, so a frame copies a map path per node. Is a cheaper log worth it?

**How.** `RenderBench.publish` and `publishManyPopups` (the page bench's 200 leaves, one frame
through the recorder), allocation profile (async-profiler, escape analysis off) in D6's pass.

**Numbers.** `touched` is 31% of `publish` (18,408 B/op) and 26% of `publishManyPopups`
(21,184), about 6 KB a frame.

**Verdict.** Not built. It is paid once per slug per frame, never per session, and the
alternatives are no plainer: a batched `++` still copies paths, and a versions array by `Ix`
copied per frame costs about what it saves at this size, while the log must stay an immutable
value that sessions read while the recorder writes.

**Worth asking again if** frames touch far more nodes (a dashboard of thousands, or a recorder
that touches every node of a fill), or the log moves behind a single owner that could keep a
mutable table safely.

## A tick's signal frame, built once for every session

`Patches.signalFrame` is built per session on each pull: the signal values its nodes have now,
the ones that moved from what it holds, and two maps, the frame's payload and the session's new
holds. On an ordinary tick every session gets the same payload. Is sharing it worth it?

**How.** `RenderBench.resumeMorphs` and `resumeMorphsFanout` (ten sessions), and
`resumeSignalsFanout`; allocation profiles (async-profiler, escape analysis off) in D6's pass
and again after D6's E.

**Numbers.** `signalFrame` is ~38% of `resumeMorphs` (131,988 B/op) and 41% of
`resumeMorphsFanout` (615,305), ~25 KB a session. Of that, ~88% is building the two maps and
~11% the values themselves (`Renderer.signalsOf`), the only part that is the same for every
session.

**Verdict.** Not built. The maps are per session by nature, since each compares against that
session's `holds`. Sharing the payload only works for a session whose held signals are all
stale, so it means a second path for when a session is behind or ahead, for a saving that is
mostly map building.

**Worth asking again if** many sessions per slug becomes the normal case (a wall of tablets on
one dashboard), or a profile of a real tick shows `signalFrame` above the render itself.

## A compiled card shared across dashboards and reloads

Each `Dashboard.validated` compiles every card it uses (`Templates.card`: mustache.java's compile,
then what the template reads off its tree, `readCodes` and `inExpression`). Nothing is shared
between two dashboards on the same theme, or with the serving dashboard on a reload. Is a cache
of compiled cards worth it?

**How.** `ValidateBench.validated` with allocation profiles (async-profiler, escape analysis
off), D6's H on fc2b3e67; the four checked-in snapshots compared card by card.

**Numbers.** The card compile is 43% of `validated` on the starter (1.71 MB/op, ~1.3 ms), 34%
on `branches`, 17% on `taps`: ~0.5 ms a dashboard against seconds of Pkl evaluation on a Pi.
A card of the same name has the same template in every snapshot (`page` has two), so the four
compile 43 cards where 25 are distinct.

**Verdict.** Not built. The render phase would not move: a compiled template is already run by
every session of a slug at once, and the renderer only looks one up by name. The cost is who
owns the cache. `factory.compile(Reader, name)` caches nothing (only `compile(name)` does), so
today a template is freed with its renderer; a global map would keep every template an edit
ever produced, a weakly held one frees at GC time behind everyone's back, and the plain shape,
`validated` taking the cards it may reuse and a reload seeding them from the serving renderers,
needs that map threaded through `Site.decode`, `DashboardBuild.decode` and `DumpRefresh`. A
reload no longer validates a dashboard that decodes equal to what it serves
(`DashboardBuild.decode`'s `live`), which took the case where sharing would have saved the most.

**Worth asking again if** a site of many dashboards on one theme is the normal case and a reload
or a dump refresh that changes every one of them shows up in a Pi's heap or reload time, or the
retained size of the compiled cards (unmeasured; a JOL count like `HoldsSize`) turns out large.

## The planner's per-client allocation, slimmed

D3.4's planner (`Patches.owing`: `covered`, `hostsAt`, `owe`'s closures) allocates ~2.1 KB per
client per pull that the `Patches.reordered` search it replaced did not. Worth a pass of its own?

**How.** `RenderBench.resumeMorphs`/`resumeMorphsFanout` (B/op, escape analysis off) across D3,
with async-profiler allocation profiles; retained `holds` from `HoldsSize`.

**Numbers.** `resumeMorphs` 130,311 → 133,323 B/op (+2.3%), `resumeMorphsFanout` 609,785 →
632,096 (+3.7%), times within error; what is left after the profile fix is the planner's ~2.1 KB
a client. It is garbage per pull, not held: a session's retained `holds` is ~75 KB for 200
leaves either way.

**Verdict.** Not built (2026-11-26). It scales with open viewers, not with dashboard size, and a
home has few; slimming it means specialising the one planner's closures for a cost no profile of
a real pull has shown.

**Worth asking again if** a Pi's GC shows up in a pull's latency with several tablets open, or
`viewerPull`/`resumeMorphsFanout` move by more than the gate on a later change to `owing`.

## A stable set's membership, rebuilt once a frame

A stable set's delta (`MemberGraph.keysMoved`) folds `applyOne` over the touched entities, and
each one that switches clause builds a fresh `GroupMembers`, so its `Presence` scan, again. A
frame where several members switch together (a scene) pays it once per member. Build it once
after the fold instead?

**How.** `RenderBench.publishClauseSwitches` against `publishClauseSwitch` (9258dac6): the
publisher pass on `clauseSetTree(200)`, 20 members switching clause per frame against one.

**Numbers.** 173 against 50 µs, 495 against 190 KB: ~6.5 µs and 15 KB per extra switch. That
is an upper bound on the rebuild's share, since the 20-switch frame also carries 19 more changes
through the reverse index and the log; no control with 20 changes and no switch was run.

**Verdict.** Not built (2026-11-26). It is paid once per slug per frame, not per viewer, and
only when many members of one set switch at once, which a scene does a few times a day: ~1 ms
on a Pi at worst.

**Worth asking again if** a dashboard's sets hold hundreds of candidates that switch together
often, or a Pi's profile of the recorder shows `GroupMembers.of` under `keysMoved`. Start with
the control (20 changes, no switch) to split the rebuild from the frame's other work.

## The recorder's per-change lookups, trimmed

`publishIdle(Set)` ran ~1.7 µs per frame slower after regions with equal B/op, and the new graphs
add lookups per change: `componentsFor` through a map per container, `PickGraph.dirtied`
concatenating two lists. Read the page root's map once and merge the two?

**How.** Both, built together, `-f 3 -prof gc` against 9baea638; then again on top of the GC fix
(57cf63c9), `-f 5`, the two sides back to back.

**Numbers.** Against 9baea638: `publishIdle` 3.75 → 3.80 µs, `publishIdleSet` 4.52 → 4.57, but
G1 was a quarter of the wall time then, so that run could not have seen a small saving. On the
fix: `publishIdle` 2.35 → 2.87 µs (±18%), `publishIdleSet` 3.92 → 3.37 (±13.5%): opposite ways,
both at the edge of the error; `publish`, `publishSet` within 2%.

**Verdict.** Not built (2026-11-27). The time was G1's: a dead `Shared` version kept every later
one alive through young collections, through its link to the next (since gone: no version refers
to another, and a late fill finds its successor in `Decided`).
With that fixed the benches are back at main's, and the trims buy nothing measurable.

**Worth asking again if** a cpu profile of the recorder, with `gc.time` small, shows these
lookups. Check `gc.time` before reading a time regression with equal B/op as cpu work.

## A per-node fragment folded and encoded as it is written

2026-11-27, `perf-later` at 60b0dc2a. **Not built.**

**Question.** `plan-after-regions-perf.md` 12 wanted one path for every `elements` line: the
fragment through `Sink.Folding` into a frame writer that encodes UTF-8 itself, so the regex
`collapse` and `getBytes` leave the frame path and a fragment is never copied on its way to bytes.

**How.** Built that way (every frame kind, the writer hand-encoding a character at a time, an ASCII
run in one loop), `-f 3 -prof gc` against 60b0dc2a; then a cpu profile of `RenderBench.wire`
(async-profiler, collapsed).

**Numbers.** B/op fell (`wire` 200,876 → 42,107, `tabFlip` 253,532 → 141,134) and time doubled:
`wire` 43.7 → 91.9 µs, `chartWindow` 65.9 → 162.1, `tabFlip` 126.2 → 189.3, `resumeFlip`
1224 → 1753. The profile: 73% of `wire` in `Folding.write` and the writer's loop, `String.charAt`
and its bounds check a third of it. Two Java loops per character lose to the regex and
`getBytes`'s intrinsics. The ASCII loop moved nothing.

**Verdict.** A fragment in hand is collapsed and encoded whole, its bytes a piece of the frame's
chunk, uncopied; only a walk folds as it writes. That keeps two copies of a fragment where the
per-character path had none, and is still under the old writer in both B/op and time.

**Worth asking again if** a JDK gives a public bulk encode into a caller's array (a
`String.getBytes` into an offset, for UTF-8), which would remove `getBytes`'s array too.

