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

Recorded elsewhere:

- [`datastar-vs-htmx.md`](datastar-vs-htmx.md): stay on Datastar (#149).
- Draft PR #328: one rendering shared by many entities, concluded not to build (#108).
- Draft PR #371: Pkl never runs compiled; native-packager works on sbt 2.
- Draft PR #163: Nickel as the authoring language.

## Fixed-size collections for the renderer's indexes and a session's holds

2026-10-22, main at 0f013206. **Not worth doing.**

**Question.** Would arrays or presized maps, in place of `List` and `Map`, cut memory where a
collection's size is known when it is built? The candidates were the static indexes a renderer
builds once (`MemberGraph`, `NodeAncestry`, `SurfaceGraph`, `allIndexed`) and the per-session
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

**Question.** After a dashboard edit, `reloadRepaints` renders the body once per connected session,
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
