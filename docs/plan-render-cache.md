# Plan: what the render cache is for, and a key made of values

**State:** plan only, nothing agreed. Measure first; the design rounds below decide what is built.

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
2. **Skip per session, before rendering.** If `holds` kept a hash of those values instead of
   (or beside) the bytes' digest, a session could tell "nothing to send" without rendering or
   looking anything up, which is the common signal-only tick.
3. **Is sharing across viewers worth its machinery?** Each lookup is a `MapRef` compare-and-set, a
   `Deferred` and an uncancelable region. A hit no longer pays them ("Done ahead"); a miss does. With 1 and 2, what is left to share is the render on a byte
   tick, which a home dashboard's handful of viewers may not need: compare keeping single-flight,
   a memo for one tick, and none.
4. **Bounding per-viewer values.** Whatever 1–3 decide, the entries for one node must not grow
   with values a viewer posts (ADR 0033 has no allowed-values list).

## Measure first

**The single viewer pays for the hit path today.** #688 made a hit free and a miss dearer: one
client's tick, all misses, went from 160,117 to 163,317 B/op (+2.0%, `resumeSignals`, escape
analysis off), for the byte-values closure and the `IO.defer` before the old path; ten clients'
went from 1,167,658 to 972,138 (-16.7%). One viewer is the common home case, so the rounds must
win that back: round 2 (a session skipping by its own `holds`) removes the miss on a signal-only
tick altogether, and is the first thing to measure against `resumeSignals`.


- A lookup-only bench: the cost of `RenderCache.apply` on a hit, and on a miss without the render.
- `RenderBench.resumeSignals`, `resumeSignalsFanout` and `tickRender` as the baselines, plus
  `VarWriteBench` (#688) for per-viewer values. Allocation first, with `-XX:-DoEscapeAnalysis` on
  both sides.
- `RenderCacheContentionSuite`'s renders a frame, as today.

## Steps

To be written once the rounds are decided.

## Docs

ADR 0012 (the key, the pre-check, the cache's jobs), `docs/architecture-rendering-pipeline.md`
(the render cache box and its open questions), `docs/terminology.md` if `holds` changes meaning;
`docs/experiments.md` for any round the numbers decline.
