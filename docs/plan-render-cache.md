# Plan: what the render cache is for, and a key made of values

**State:** rounds 2 and 3 built, round 4 answered by them; round 1 open, now with the digest in it.

## Why

The render cache (`RenderCache`, ADR 0012) sits on the patch path only: live ticks, reconnect
resumes and variable writes (`Patches.bytes`); a page load never uses it, and the numbers say it
should not: on the Pi (`pkl-demo`, 55 kB) the page spends 21 ms before its first byte and 22 ms
walking after it, while the browser opens the stream about 400 ms after the document arrives (the
server answers it in about 20 ms). Only the walk is a cache's to take. It does two jobs, each
measured (ADR 0012, the pipeline doc's open questions):

- **Within one tick, across viewers.** Sessions woken by one change render each node once between
  them: the first client costs 262 µs a tick, each further one 70 µs and 117 kB.
- **Across ticks, for one viewer.** On a signal-only tick the resolved byte-slot values are compared
  with the entry's, and equal values reuse its bytes without rendering: one viewer's tick went from
  262 to 102 µs.

It does them with two mechanisms: a key of entity and fetch versions (`RenderInputs`, ordered by
`isAtLeast` so a straggler never displaces fresher bytes) and a pre-check on resolved values
(`Renderer.byteSlotValues`). Separately, each session keeps the digest of what it was sent
(`holds`) so identical bytes are not sent twice. The step V work (#688) adds a third input, the
variable values a node reads, and asked how per-viewer values are bounded; this plan answers that
for the cache as a whole rather than for variables alone.

## Done ahead

A hit on equal inputs reads the entry and nothing else: no byte-slot values, no `Deferred`, no
compare-and-set (#688). Ten clients' tick allocates 16.7% less; one
client's, all misses, 2.0% more.

## Design rounds

1. **Key by values, not versions.** A leaf's patch form is its template applied to its resolved
   slot values: the cell's static part comes from the renderer, and the live parts are signals,
   absent from the patch form. So the resolved byte-slot values (plus the renderer) may be the whole
   key, which would fold the version key, `isAtLeast` and the pre-check into one comparison, and a
   variable's value would be one more value with no bucket rule. To verify: that nothing else
   reaches a leaf's patch bytes (the ids, `dashboardSlug`, a fetch slot's bytes, which a hash or the
   fetch's version would stand for). Cost: resolving the byte slots measured 0.28 µs against a
   5.45 µs render (`RenderBench.byteSlotResolve` vs `tickRender`).
2. **Skip per session, before rendering.** *Built* (ADR 0012, "A session asks its own `holds`
   first"): `Held.values` beside the digest, the values themselves rather than a hash, since a
   home dashboard's byte slots are few and short. Left open: a suppressed morph records nothing,
   so a node whose values moved while its bytes did not renders on every later tick until its
   bytes move; closing it needs a holds-only update, which `Addressed` has no form for.
3. **Is sharing across viewers worth its machinery?** *Built: a memo* (ADR 0012, "A memo, not
   single-flight"), and the cache's own byte-value check deleted, since round 2 does that job in
   the session. Byte tick, escape analysis off, B/op:

   | Bench | single-flight | memo | no cache |
   |---|---|---|---|
   | `resumeMorphs` (1 client) | 246,472 | 216,727 | 205,797 |
   | `resumeMorphsFanout` (10) | 1,120,489 | 1,055,833 | 1,628,427 |

   Time, escape analysis on, two forks, µs: `resumeMorphs` 114.1 -> 86.0, `resumeMorphsFanout`
   420.5 -> 358.7, `resumeMembers` 124.9 -> 92.1. `RenderCacheContentionSuite` holds one render a
   frame across five runs.
4. **Bounding per-viewer values.** *Answered by 2 and 3*: the cache keeps one entry per node
   whatever values are posted, and a session's `Held.values` is one map per node it holds.

## Measure first

**The single viewer pays for the hit path today.** #688 made a hit free and a miss dearer: one
client's tick, all misses, went from 160,117 to 163,317 B/op (+2.0%, `resumeSignals`, escape
analysis off), for the byte-values closure and the `IO.defer` before the old path; ten clients'
went from 1,167,658 to 972,138 (-16.7%). One viewer is the common home case, so the rounds must
win that back: round 2 (a session skipping by its own `holds`) removes the miss on a signal-only
tick altogether, and is the first thing to measure against `resumeSignals`.

**Round 2, measured as spiked and as built** (the same numbers). `Held` keeps the byte-slot values its digest was rendered from (the page
seeds them; a morph records its own), and `Patches.morph` returns nothing when the values a session
holds equal the resolved ones, before any cache lookup. Escape analysis off:

| Bench | Before (B/op) | Spike (B/op) | Change |
|---|---|---|---|
| `resumeSignals` | 163,317 | 127,477 | −21.9% |
| `resumeSignalsFanout` | 972,138 | 866,842 | −10.8% |
| `tickRender` | 98,816 | 98,816 | 0 |

That wins back #688's +2.0% several times over. Round 1's check holds for a static-tree leaf: its
patch bytes are a function of the renderer, the id and the byte-slot values (a member also needs its
clause), compared as `SlotValue`s, not text.

- A lookup-only bench: the cost of `RenderCache.apply` on a hit, and on a miss without the render.
- `RenderBench.resumeSignals`, `resumeSignalsFanout` and `tickRender` as the baselines, plus
  `VarWriteBench` (#688) for per-viewer values. Allocation first, with `-XX:-DoEscapeAnalysis` on
  both sides.
- `RenderCacheContentionSuite`'s renders a frame, as today.

## Steps

1. Round 2: done.
2. Round 3: done.
3. Round 1, undecided: key the memo by the byte-slot values a session already resolved, and let a
   static node's `holds` drop its digest. That would drop `renderInputs` from the live path, the
   per-node hashing from the page walk (and the second patch-form render a two-form node pays
   there), and the snag in round 2. A member needs its clause beside its values.

## Docs

ADR 0012 (the key, the pre-check, the cache's jobs), `docs/architecture-rendering-pipeline.md`
(the render cache box and its open questions), `docs/terminology.md` if `holds` changes meaning;
`docs/experiments.md` for any round the numbers decline.
