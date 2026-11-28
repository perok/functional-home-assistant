# Plan: the after-regions bench regressions

A full bench comparison of `after-regions` (9baea638) against main (574f062c), confirmed at
`-f 3` and then profiled (async-profiler, alloc and cpu), found five regressions. Each one below
names its cause, measured; the fixes keep behaviour byte-identical.

**State:** 1–6 merged (#739, #741); Later on #742 (`perf-later`): 7, 10, 11, 8, 12 and 9 built;
13 (the reconnect): A building on `reconnect-ack`, then B; 14 (circe or jsoniter-scala) to investigate, 15 (`collapse` without the regex) and 17 (send pacing) to try, 16 (the recorder conflates) to measure.

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
   written as it is walked (`Datastar.patchWritten`): its `data: … elements ` prefix, the walk
   through the page's writer chain behind `Sink.Folding` (`collapse` as it is written, pinned
   against it by `FoldingSinkPropertySuite`), then its end. The frame is the encoder's 8 KB
   flushes as one `fs2.Chunk` (`SseFrame` holds a chunk), so the page is never joined or grown.
   Eagerly, inside the step: the next step diffs against the `holds` the repaint leaves, and a
   tap or a swap runs on the owner without waiting on `drained`. Gate: 10's `repaint` B/op near
   `pageWalk`'s. Built: `repaint` 9.82 MB → 1.29 MB/op (pageWalk 508 KB), 7191 → 2533 µs; the
   gate is NOT met, 2.5× `pageWalk`.

   Left above `pageWalk`, profiled: the frame's own bytes (~250 KB, the eager shape's floor, which
   only the extension below removes) and the repaint's signals frame (~350 KB: `signalsJson`'s
   `Json`, its `noSpaces`, `"signals " +`, `renderString`, `getBytes`, and `SignalId.segments`
   re-split per call), which 12 writes the same way.

   **Extension, if the Pi says the rest matters:** the same writer into the stream instead of a
   chunk, which saves the frame's bytes (≈ the page). It needs a frame kind the
   stream walks as it writes, the repaint step holding the owner until the stream hands back the
   `holds` (a tap pressed meanwhile waits on the client's read, where it waits on the walk
   today), an eager walk when no outlet is attached (a swap during a reconnect), empty `holds`
   when the write breaks, and `Opened.bytes` taken after the write. It buys no latency: the
   morph applies only once the whole event has arrived.
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

12. **One frame writer.** An SSE frame has one format and two writers: every node patch, removal
    and signals frame goes `Datastar.sse` → http4s's `ServerSentEvent` → `renderString` →
    `getBytes`, with the regex `collapse` on an `elements` line, and 8's repaint alone goes
    `patchWritten` + `Sink.Folding` into a chunk (`FoldingSinkPropertySuite` pins the one against
    the other: a copy kept equal by a test). Where a bench reaches the bytes it shows:
    `wireCommon` is 3.2 µs per signals frame, all of a client's cost on that path. It was read as
    a sharing question (one encode per slug), which is a separate one: this is what one encode
    costs, shared or not.

    **Shape.** One writer (`Datastar.FrameWriter`), `data:` lines straight into the frame's
    chunk, the event line and the blank line last; `patch`, `remove`, `patchSignals` and the
    repaint are calls of it, and `sse` and `ServerSentEvent` leave the frame path. A run of
    morphs is one fragment. A fragment in hand is `collapse`d and encoded whole, its bytes a
    piece of the chunk, uncopied; only the repaint's walk goes through `Folding`, which
    `FoldingSinkPropertySuite` still pins against `collapse` — the plan was the fold for every
    `elements` line, and that doubled the per-node benches' time (`docs/experiments.md`, "A
    per-node fragment folded and encoded as it is written"). A signals frame prints its nested
    JSON from the sorted paths, each key and leaf through circe's printer, instead of building
    a `Json` to print it. Byte-identical frames: the goldens, and the suite's property that
    every kind is the event http4s renders.

    **Benches encode** (60b0dc2a). `tabFlip`, `selectChange`, `chartWindow` and
    `resumeFlip(One)` stopped at `List[Addressed]`, so no write or pull bench priced its frames;
    each ends as `Server` sends. Gate: those benches, `wire`, `wireCommon` and
    `HouseBench.repaint` B/op down, times within error.

    Built, `-f 3` against 60b0dc2a:

    | bench | B/op | µs |
    |---|---|---|
    | `tabFlip` | 253,532 → 141,538 | 126.2 ± 10.4 → 112.4 ± 12.0 |
    | `selectChange` | 257,658 → 142,908 | 121.1 ± 9.3 → 112.4 ± 10.7 |
    | `chartWindow` | 242,838 → 111,121 | 65.9 ± 5.2 → 51.7 ± 1.8 |
    | `resumeFlip` | 2,737,026 → 1,580,138 | 1224 ± 181 → 1087 ± 109 |
    | `resumeFlipOne` | 275,359 → 160,175 | 165.9 ± 41.3 → 134.3 ± 20.8 |
    | `wire` | 200,876 → 99,724 | 43.7 ± 3.4 → 31.7 ± 1.9 |
    | `wireCommon` | 56,505 → 28,399 | 18.0 ± 1.8 → 10.7 ± 0.8 |
    | `HouseBench.repaint` | 1,337,832 → 1,099,386 | 3024 ± 918 → 1855 ± 134 |

    The gate is met. `repaint` is still 2.2× `pageWalk`'s bytes: the frame's own (8's
    extension) and the signals frame's leaves, each a `Json` made from a `SlotValue` to be
    printed once.

13. **The reconnect** (the question answered; the fix undecided). A tab switch drops the stream
    and the page reconnects with its cursor, within the linger. `ReconnectSuite` drives it
    through `FakeBrowser`: with nothing lost, the opening resumes (nothing moved: its signals
    alone; a tick while down: only what it moved). It repaints when a frame was lost in
    flight: hidden, Datastar aborts the fetch, the server writes on until it sees the close,
    and a tick in between moves `told` past the page's cursor, so the opening reads
    `BehindTold` and repaints the body (8's path). On a house ticking every second that is
    most tab switches, and the likely large update on a reconnect.

    The log covers the window; `holds` does not. It records what was SENT, so a resume from
    the cursor would skip what was lost, which is why `BehindTold` repaints.

    And a bug: a choice's batch ends with no cursor, so `told` does not move for it. A choice
    lost in flight resumes over a DOM that never got the panel: the tab bar says the new tab,
    the panel is the old one, and `holds` says otherwise, so nothing corrects it
    (`ReconnectSuite`, "a choice lost in flight is resent", an expected failure until fixed).

    The echoed store version alone cannot fix either: a choice moves no version, so the echo
    cannot tell a choice applied from one lost; and knowing V says what MOVED since V (the
    log), not what the DOM holds, which `holds` (what was sent) cannot rewind to. Hence A,
    then B, which needs A's numbers to key its snapshots.

    - **A** (branch `reconnect-ack`, on #742). The cursor gains `batch`, and `told` becomes the
      pair last put on the wire, `(version, batch)`; the opening reads `c.batch < told.batch` as
      `BehindTold`. Batch numbers come from one counter for the process, not per session: a
      session minted under the same `conn` after a reap would restart a per-session count, so
      an old cursor would read as ahead of a lost opening and resume over it (today's version
      check catches that). Only a batch that moves what the session believes the DOM holds
      takes a number: an opening, a repaint, a resume that patched, a choice. The keepalive's
      cursor-only frame keeps it, since losing it loses nothing held. Each number rides in its
      batch's own trailing signal frame (~12 B), not a frame of its own (~45 B and an event per
      tick). A lost choice then repaints; the `.fail` case in `ReconnectSuite` passes.
    - **B.** Each batch's `holds` and `position` are kept by its number, in the session's own
      state (the owner's alone, so no lock): a cursor behind `told` resumes from its own batch's
      `holds`, exact because the number rides last, instead of repainting. Kept for the linger
      (`SessionWindows.linger`, so a suite can shorten it), since a wifi drop can leave the
      server writing into the socket buffer for minutes; capped at 256 as the backstop for a
      fast-ticking house, past which the oldest go and that cursor repaints. Pruned from the
      front on every step; the maps share structure, so each costs what its batch changed.
      Gate: the lost-in-flight case resumes; the opening's bytes; per-session memory under a
      fast-ticking bench.

    Not covered by either: the HA-down banner frame is sent outside the session, so one lost in
    flight is not resent on reconnect.

14. **circe or jsoniter-scala** (to investigate). Every JSON the runtime reads or writes is
    circe: HA's WS frames and REST answers, the signals frame's leaves (12 prints them through
    circe's printer), the dump. [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala)
    codecs write and read bytes directly, with no `Json` tree between. The question: what a
    switch would buy on the paths that pay per frame (the WS decode of `state_changed`, the
    signals frame), measured by a bench of each against circe, and what it would cost
    (derivation, two libraries side by side, `Json` used as a value type in the model).

15. **`collapse` without the regex** (to try). Every per-node fragment goes through
    `Datastar.collapse`, a regex over the whole string, then `getBytes`. 12 found a per-character
    fold loses to both (`docs/experiments.md`, "A per-node fragment folded and encoded as it is
    written"); untried is a scan that stays in intrinsics: `indexOf('\n')` from newline to
    newline, trim the whitespace each side, and append the spans between in bulk
    (`StringBuilder.append(s, start, end)`). Gate: 12's (`wire`, `wireCommon`, `tabFlip`,
    `chartWindow`), B/op and time not worse, the output pinned by `FoldingSinkPropertySuite`. If
    it loses, an entry in `docs/experiments.md`.

16. **The recorder conflates** (to measure). Per client, a spamming entity already collapses:
    the log keeps each place's latest and a pull reads from its position, so a node spammed
    between pulls is one patch. The recorder does not: it records every store frame (one per
    HA arrival that changed something), per slug, even when the next frame overwrites it.
    Conflating the frames waiting for it (the union of what changed, at the latest version)
    would bound that. First check nothing reads a frame in between (transitions,
    `recoverTransitions`), and measure the recorder's cost under a spamming entity before
    building it.

17. **Send pacing** (to try). A slow client already gets coalesced batches: the doorbell is
    `discrete`, so it yields only the latest version, and a pull waits on `outlet.drained`. A
    fast client on a busy house gets one batch per frame. A minimum gap per session after each
    pull (`pull *> outlet.drained *> IO.sleep(gap)`) folds whatever lands in the gap into the
    next pull, which also bounds B's snapshots. Configurable per session; where the value
    comes from (a `site.pkl` setting, a stream parameter, a server default) is to decide. A
    choice is not paced (it runs directly), but a tap's result arriving as a state change
    waits up to the gap: 10 ms is invisible, 100 ms shows on a slider.

Order: 10, 11, 8, 12, 13 (A building; then B), then 9, then 14, 15, 16 and 17.
9. **No version refers to another** (replaces 5's release). 5 releases a version `Decided` lets
   go: a manual dispose that every holder of versions must remember. The forward offer has a
   customer each frame (the recorder advances, then plans against `prev`, so its late "before"
   fills reach `next` through `Table.next`), so it stays, found through what owns the versions:
   a late fill asks `Decided` for the kept version carried from its own, and the keys that may
   arrive move onto the receiving table as what its frame dirtied. Then no version can keep
   another alive, and `next`, `release` and the CAS loop go. Wiring: a `Shared` reaches its
   `Decided`, and a table its counterpart in the successor. Gate: `publishIdle(Set)`'s `gc.time`
   and `-Xlog:gc` flat, B/op equal to 5's.

   Built. A table holds `refused` (what its own frame dirtied or left unmarked; everything
   until it is carried into) and its counterpart's selector; a version `Decided` made carries a
   `Kept` (its version, `Decided`, and the version it was carried from), which a late fill
   asks `Decided` for its successor with. `next`, `release` and the release's winner-only CAS
   go; `make` keeps a CAS loop, since `updateAndGet`'s function would be one more object per
   version. A fill on a version `Decided` dropped now still reaches the kept successor, which
   5's release cut off. Two costs found on the way: the seven selectors written as lambdas in
   the class body captured `this`, seven objects per version (343 B/op on `wireTick`), so they
   live in an object; and a `Decided`-only field on `Tables` cost every per-node `Shared`.

   `-f 3` against 65120c75, from classpath snapshots back to back:

   | bench | B/op | µs | gc.time ms |
   |---|---|---|---|
   | `publishIdle` | 4,728 ± 170 → 5,027 ± 693 | 2.23 → 2.47 ± 0.41 | 220 → 234 |
   | `publishIdleSet` | 4,312 ± 63 → 4,336 ± 22 | 2.77 → 2.92 ± 0.24 | 200 → 178 |
   | `publish` | 14,581 → 14,456 | 7.76 ± 6.05 → 5.58 ± 0.28 | 229 → 245 |
   | `publishSet` | 30,608 → 30,949 (± 1,100) | 15.9 → 16.3 | 221 → 232 |
   | `publishFlip` | 5,893 → 6,395 ± 787 | 3.69 → 4.04 ± 0.54 | 194 → 220 |
   | `resumeSignals` | 73,454 → 73,568 | 66.8 → 63.2 | 132 → 136 |
   | `wireTick` | 5,008 ± 55 → 5,088 ± 111 | 2.41 → 2.38 | 227 → 193 |

   `publishIdle` and `publishFlip`'s errors are one fork each (5,912 and 7,400, the other two
   at base's), the same split base shows on `publishSet` (32,016); a single fork of
   `publishIdle`, profiled, was 4,672 against base's 4,624. `-Xlog:gc` on `publishIdleSet`: the heap after a young collection
   holds at 78 MB from 5 s to 19 s.
