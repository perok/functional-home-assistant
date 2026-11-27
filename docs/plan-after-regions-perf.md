# Plan: the after-regions bench regressions

A full bench comparison of `after-regions` (9baea638) against main (574f062c), confirmed at
`-f 3` and then profiled (async-profiler, alloc and cpu), found five regressions. Each one below
names its cause, measured; the fixes keep behaviour byte-identical.

**State:** 1–6 merged (#739, #741); Later on #742 (`perf-later`): 7, 10 and 11 built; 8 and 9 open,
in the order at the end.

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
   sets and gates). `wireTick` makes a `Shared` per node, so it pays them three times; a slug
   pays once per version. `Shared.of` is 984 B, 448 of it seven tables' empty
   `ConcurrentHashMap`s: every table, not only the three, makes its map on the first fill, at a
   null check on every read.
8. **A repaint streams like a page load.** A frame copies its fragment about five times
   (`collapse`, `"elements " +`, the lines' `mkString`, http4s's `renderString`, `getBytes`); a
   per-node frame is small, but a repaint (`Patches.repaint`) walks the whole body into one
   `String` (`tracedHtml`) and then copies the document five times, the regex over all of it,
   where a page load streams the same walk (`tracedInto`) holding one node. The repaint frame is
   written into the stream instead: its `data: … elements ` prefix, the walk through the page's
   writer chain behind a filter that folds a whitespace run holding a newline into one space,
   then its end; the session's holds commit after the walk, as a page load's do. No hand-written
   UTF-8: the writer's encoder does it in a fixed buffer. Gate: 10's `repaint` B/op near
   `pageWalk`'s.
10. **A bench of a real dashboard** (built: `HouseBench`). Every `RenderBench` page is synthetic
    (`TestCard`), and no bench priced a repaint. The starter site over `HouseShape`, a house with
    the maintainer's shape and no names (1116 entities in its per-domain counts, 3 floors of 16
    areas, its light colour modes, 5 low batteries of 22), evaluated through `DashboardBuild` at
    setup: `pageWalk` (the shipped writer chain), `repaint`, and `pageWalkCold`, under the Pi's
    flags (`-XX:+UseSerialGC -Xms64M -Xmx512M`). B/op is the gate that carries to the Pi; time
    carries in direction only. Baseline (c021a98e + the bench, `-f 3`): a 290 KB page;
    `pageWalk` 527 KB/op, 894 ± 62 µs; `repaint` 9.82 MB/op, 7191 ± 465 µs; `pageWalkCold`
    332 ± 73 ms (its B/op counts the setup's evaluation, so read its time only). A repaint costs
    18.6× a page load's bytes, which is 8's case.
11. **A span on the stream's open** (built: `dashboard.stream.open`, under the request's trace),
    tagged `fh.opening` (resume, repaint with `fh.repaint.reason`, reload), `fh.frames` and
    `fh.bytes`: the walk span (`dashboard.page.walk`) covers a page load only, and a phone
    reopening the app resumes a stream. The opening step answers a `Server.Opened`.

Order: 10, 11, 8, then the reconnect question (a tab switch of a few seconds repaints
wholesale; why, with the browser test setup: it decides how often 8's path runs), then 9.
9. **No version refers to another** (replaces 5's release). 5 releases a version `Decided` lets
   go: a manual dispose that every holder of versions must remember. The forward offer has a
   customer each frame (the recorder advances, then plans against `prev`, so its late "before"
   fills reach `next` through `Table.next`), so it stays, found through what owns the versions:
   a late fill asks `Decided` for the kept version carried from its own, and the keys that may
   arrive move onto the receiving table as what its frame dirtied. Then no version can keep
   another alive, and `next`, `release` and the CAS loop go. Wiring: a `Shared` reaches its
   `Decided`, and a table its counterpart in the successor. Gate: `publishIdle(Set)`'s `gc.time`
   and `-Xlog:gc` flat, B/op equal to 5's.
