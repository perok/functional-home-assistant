# Plan: the after-regions bench regressions

A full bench comparison of `after-regions` (9baea638) against main (574f062c), confirmed at
`-f 3` and then profiled (async-profiler, alloc and cpu), found five regressions. Each one below
names its cause, measured; the fixes keep behaviour byte-identical.

**State:** 1–4 and 6 merged (#739); 5 built 2026-11-27 on branch `perf-idle-gc`, off main;
Later (7, 8) is open.

## Working this plan

- Bench gate: the step's benches before and after on the same machine, `-f 3 -wi 3 -i 5 -w 2s -r
  2s -prof gc`, against the baseline of 9baea638. B/op equal or better, times within error; the
  numbers go in the commit message.
- Tests: `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` once per step; check `Total`
  is not 0. No step changes what a test asserts.
- Allocation is profiled over ALL threads: a pull runs on cats-effect's workers, and the bench
  thread holds about 1% of `resumeFlipOne`'s bytes.

## 1. A pick's fill is copied once more than it is rendered

**Why.** `VarWriteBench.tabFlip`/`selectChange` +31% B/op, `resumeFlip(One)` +30%. Rendering itself
allocates less than on main; the extra ~25 KB per write is `content.parts.map(_._2).mkString` in
`Patches.patches`, which for a pick joins ONE string through a growing `StringBuilder`.
`HostContent.parts`' ids are read nowhere.

**Shape.** `HostContent` carries `html: String`: a pick hands its case's rendering over as it is, a
set joins its members once.

## 2. A fill's seeds are built twice

**Why.** The same benches: the seeds of a fill (every signal its claims hold, ADR 0017) are
collected through a list of claims, a list of pairs and a second list before `toMap`, then merged
into the signal frame's map with `++` (~6 KB of the ~13–19 KB). The map itself is the feature:
`Patch.Signals` holds one.

**Shape.** `signalFrame` takes the seeds and adds them into its own payload builder after the
diffed values (a seed wins, as `++` had it), walking each claim's signals with `foreachEntry`.

## 3. Every send makes a `Shared` it throws away

**Why.** `Decided.at` builds `Shared.of` before looking in `kept`, and `renderer.shared(store)` runs
per resume and per send (`Server`), so each connection's send allocates a version's tables (784 B,
`sharedHeld`) and drops them when the version is held, which is the usual case. No
bench prices it: `wireTick` calls `Shared.of` itself.

**Shape.** `at` returns a held version before making one. New bench `RenderBench.sharedHeld`: a
held version asked again.

## 4. A numeric comparison boxes

**Why.** `RenderBench.conditionTree` 0 → 5320 B/op, all of it `Some(Double)` and its `Double`
from `Steps.number` under `compare`. Source and inputs are the same as main's; main's JIT
scalar-replaced them and this build's does not. `Double | Null` would keep the box (it erases to
`Object` through `boxToDouble`).

**Shape.** `compare`'s numeric-literal branch matches the value as the `java.lang.Number` it
already is; text still parses; `ne` is `onLine`'s `!=`, which a NaN keeps. `number` is unchanged
(`ConditionsSuite` is its oracle).

## 5. A dead version keeps every later one alive

**Why.** `publishIdleSet` +1.7 µs per frame per slug (`publishIdle` the same at `-f 3`), with
equal B/op. Not the per-change lookups: reading the page root's map once and one merged
entity → picks map moved neither bench. The cpu profile put 77% of the samples in G1's young
evacuation (main: 1%): `gc.time` 2,498 ms against 67, and `-Xlog:gc` showed the heap after a
young collection growing to 1.2 GB in 13 s, with a full collection finding the live set flat.
Each `Shared.Table` links to the next version's (`next`, so a late fill is offered on), and once
one dead version sits in the old generation it keeps the next alive through young collections,
which promotes that one, and so on. The link is from #675; what after-regions changed is that a
version now gets promoted at all.

**Shape.** `Decided` releases a version it lets go: its tables forget `next`. Only a late
offer is lost, and the next version decides that itself.

## 6. A write scans every scope for its readers

**Why.** `VarGraph.readersOf` walks `scopes.toList` on every call; `refusals` calls it once per
choice of every write, and `Server.setVar` again. Not a regression (main pays about the same), but
it grows with the nodes under a scope.

**Shape.** One `Map[VarKey, List[NodeId]]` built from the same walk, each key's readers in the
order the walk met them.

## Later

7. **`wireTick` +24% B/op**: each version's `Shared` builds three more tables (the viewer branches,
   sets and gates), each with an eager `ConcurrentHashMap`, ~1.2 KB per version. Making them on
   first use puts a check on every table read.
8. **A patch's frame copies its fragment about five times** (`collapse`, `"elements " +`,
   the lines' `mkString`, http4s's `renderString`, `getBytes`), per connection, on main too. One
   pass from the fragment to the frame's bytes; `RenderBench.wire` prices it.
