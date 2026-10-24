# Experiments

Measurements and spikes whose answer was "don't build it", kept so they are not rerun from
scratch. Each entry has the question, how it was measured, the numbers and the verdict, and what
would make it worth asking again. An experiment that led to a change belongs in that change's PR
or ADR, not here.

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

## A digest as two `Long`s instead of a hex `String`

2026-10-22, the same probe. **Not worth doing.**

`Digest` (`FragmentLog.scala`) is a 32-character hex `String`: 72 bytes with its `byte[]`, plus a
16-byte `Some` in `Held`. There were two ways to shrink it:

- **A `final case class Digest(hi: Long, lo: Long)`**: about 40 B less per node (a 32-byte object
  in place of 72), and the hex encoder goes. The change stays in
  one file, apart from `FragmentLogSuite`'s check of the hex encoding, which goes with it.
- **The two `Long`s inline in `Held`**, with a flag in place of the `Option`: about 72 B less per
  node. This changes `Held`'s shape, `merge` and the 20 places that construct one.

On a 200-node page that is 8 to 14 KiB per open browser. Speed doesn't change: comparing two
32-byte strings is a JIT intrinsic, against roughly 450 ns of SHA-256 to produce each digest
(`RenderBench.holdsSeed`, 90 µs for 200).

**Worth asking again if** the per-session figure above starts to matter, for the same reasons as
the previous entry. The `case class` variant is the one to try first.

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
lookup costs about what one hash lookup does; the `String.equals` time is spent elsewhere.

**Worth asking again if** a profile attributes the time to `fhGet` itself, not to the template
around it.
