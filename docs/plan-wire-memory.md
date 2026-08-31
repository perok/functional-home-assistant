# Plan — memory on the wire

**Status: in flight.** Delete this file when the work lands (root `CLAUDE.md`, plan lifecycle).

## Why

The server runs as a Home Assistant add-on on a **Raspberry Pi 4**. Allocation rate and peak live
bytes decide more there than microseconds do, and the pipeline that #237 has been optimising was
never measured in bytes at all. `docs/architecture-rendering-pipeline.md` §6a now describes the
document path; this plan is what to do about it.

Three independent threads, which must not be conflated because they attack different numbers.
**Do C first** — it is the largest by an order of magnitude and was found last, by asking why a
signals tick was not faster:

| thread | target | mechanism | size |
|---|---|---|---|
| C — narrow the cache key | per-client work on every live tick | key on the ATTRIBUTES a node reads as bytes, not the entity | **130 µs + 304 kB** per client per tick |
| A — stream the document | **peak live bytes** per concurrent page open | thread a `Writer` through the walk to the socket | ~380 kB of ~500 kB peak |
| B — share the frame | per-client work on a live tick | memo the encoded frame across sessions | 3.3 µs + 10.7 kB per client per tick |

## Measured starting point

`RenderBench`, `-f 2 -wi 5 -i 5 -prof gc`. The table lives in `RenderBench`'s own scaladoc and is
the thing to re-run, not to copy around. What matters here:

- The document is **128 kB** and costs **3.76 MB** to serve (`pageServe`) — 29x its own size.
- `pageServe` minus `pageSignals` is **281 kB**: the digest pass and the UTF-8 encode, which is the
  churn streaming directly removes. Add `Server.page`'s head concatenation (unmeasured, ~130 kB)
  and it is roughly **10%**.
- `Traced.own` is a further **99 kB** held live: 77% of the document a second time, and on the
  page-open path used only to compute 200 digests.
- The remaining ~3.3 MB is the walk — transforms 0.96 MB, mustache contexts 0.39 MB, slot
  resolution, signal seeds. Streaming does not touch any of it.

**So thread A buys peak, not churn.** ~10% of the allocation, but most of the ~500 kB a concurrent
page open holds live across four materialisations — which is the number that multiplies by open
tabs, and the one that matters on a Pi. Anyone hoping to fix the 3.76 MB is looking at the walk,
which is a different plan (issue #130, and the transform memo recorded as a dead end in
`RenderBench`).

Two findings from benching the real tick path, both of which change what is worth doing:

- **An extra client on a tick costs 68 us and 115 kB** (`resumeSignalsFanout` − `resumeSignals`,
  over nine), against 263 us for the first. The `RenderCache` is removing about three quarters of
  each further client's work — it is earning its place.
- **A signals tick costs more than a bytes tick and should cost half.** That is thread C below,
  and it is the biggest thing here. ADR 0017 and ADR 0012 both now say so.

## Thread C — narrow the cache key

**Do this one first.**

A signals tick ought to be the cheap one: the node's bytes cannot have moved, so ADR 0012 keeps
the entity out of the `RenderCache` key, the previous entry stands, and nothing re-renders. That
is the design and it works — when it applies:

| | us/op | B/op |
|---|---:|---:|
| `resumeSignals` — shipped card | 229.0 | 443,962 |
| `resumeSignalsPure` — same card, name as a literal | 99.2 | 139,507 |
| `resumeMorphs` — bytes really moved | 207.6 | 430,597 |

**It does not apply to the shipped card, and one slot is why.** `renderInputs` keys on a per-entity
`contentVersion`, which `StateStore.update` bumps whenever anything about the entity moves. ADR
0012's exclusion therefore only bites where an entity reaches a node *exclusively* through signal
slots. The shipped `entityCard`'s name reads `friendly_name` as bytes, so the entity is in the key,
so every brightness change moves it, misses, and re-renders the node — to discover the bytes are
identical and suppress the morph.

That is why a signals tick currently costs *more* than a bytes tick rather than half of one.

### The fix

Key on the **attributes** the node's byte slots read, not on the entities. This is ADR 0012's own
argument one level down: it already refuses to key on an entity whose movement cannot change the
bytes; the same is true of an attribute.

- `Transform.Simple` names its attribute outright (ADR 0028) — `AttrOrId("friendly_name")`,
  `Fill("brightness", …)`. Trivial to read off.
- A **CEL** transform does not. Its attribute references would have to be extracted at parse
  time, which is the part that has not been designed and is where the risk is. `Transform.parse`
  is the place to look.
- Fallback where extraction cannot answer: keep the entity version, i.e. today's behaviour. The
  change is then strictly an improvement and never a correctness risk — a key that is too wide
  wastes a render, which ADR 0012 already records as the loud, safe direction to fail in.

`RenderInputs.isAtLeast` is a partial order over the key's shape and will need to keep working
across the change; ADR 0012 explains why different key sets are deliberately unordered.

### Watch out for

The reverse index must stay WIDE (ADR 0012): a signal has to make its node a candidate or no frame
is ever computed for it. Only the cache key narrows. Getting these the wrong way round fails
asymmetrically and the dangerous direction is silent.

## Thread A — stream the document

### What is already right

The walk is **already push-based**. `Renderer.executeInto` takes a `java.io.Writer`; a region is a
`Writer => Unit` in `regionWalk`; mustache.java's `execute(Writer, ctx)` writes straight through.
Nothing about the recursion needs to change.

The primitive on the http4s side is `fs2.io.readOutputStream(chunkSize)(os => F[Unit])`, which hands
a function an `OutputStream` and yields a `Stream[F, Byte]` whose memory is bounded by the chunk
rather than by the page. `Ok(stream)` then chunk-encodes it. (Not `readInputStream` — that adapts a
*blocking source*; there is no InputStream here.)

### The three things in the way

1. **The chrome template takes the body as a mustache VALUE.** `renderPageTraced` puts `body.html`
   into a `HashMap` and lets the chrome template splice it. That forces the body to exist as a
   `String`. Fix: make the body a region-walk hole like every other region, or split the chrome
   template into prefix/suffix at compile time in `Templates` and write around the walk.

2. **`Traced.own` is sliced out of the shared buffer.** A stream cannot re-read bytes it has
   flushed. This is load-bearing, not incidental: the slice is what lets a signal-less member's
   patch fingerprint be a slice of the document rather than a second render (§5, and
   `pageSetPlain` prices it). Fix: feed a `MessageDigest` incrementally as a node's bytes pass, so
   `own` carries the `Digest` and never the html. The page-open path only ever digests
   (`Server.renderPage`, `Patches.arrivingFill`), so this is a pure win there — **but**
   `Renderer.render` reads `own.get(id).html` for real, to pull one node's patch-form bytes out of
   a traced walk. That caller needs the html, so `Traced` either grows a variant or the digest is
   computed alongside rather than instead.

3. **`session.holds` is committed BEFORE the response today.** A stream that aborts mid-body would
   leave `holds` claiming bytes the DOM never received — the exact permanent staleness ADR 0011
   guards against, and the reason `told` exists. Fix: commit holds in the stream's finalizer, on
   successful completion only. Worth checking against ADR 0011 before writing code.

A fourth, smaller: streaming means **no `Content-Length`** and no way to turn a mid-render failure
into a 500, since headers are already gone. The render is pure and runs on a `Dashboard.Validated`,
so a throw should be impossible — but "should be" is not a guarantee, and a truncated document is a
worse failure than a 500. Decide deliberately.

### Staging

1. Digest incrementally; `own` stops carrying html on the page path. **Standalone win** — removes
   99 kB of retention with no streaming at all, and is a prerequisite for (3).
2. Chrome as a region-walk hole (or prefix/suffix split).
3. Thread the `Writer` from `readOutputStream` through `renderPageTraced`; `holds` commits on
   completion.

Step 1 is worth doing on its own merits and should be measured on its own.

## Thread B — share the frame

`Patches.resume` runs per session, so ten tabs on one dashboard each encode the same
`datastar-patch-signals` bytes. In isolation that duplication is 10x in time and allocation
(`wireCommon` vs `wireCommonShared`).

**In context it is a much smaller prize, and that is the number to trust.** One encode is 3.3 us
and ~10.7 kB against a marginal client cost of 68 us and 115 kB — **about 5% of the time and 9% of
the bytes**. The other 95% is the decision: `log.since`, visibility filtering, the per-node cache
lookup and digest compare, the signal diff against `Held.signals`.

**So thread B is not worth doing on its own.** The 10x is an isolation artefact and should not be
quoted as a tick-level number — my own earlier write-up of PR #267 did exactly that, and this is the
correction. What is worth attacking is the 68 us / 115 kB marginal client itself, which nothing has
profiled yet.

If it is done: it is **not** the `RenderCache`. That is keyed per NODE and holds `NodeBytes`, so a
*morph* frame could hang off it — but the common tick is signals-only and has no node. That half
wants a small memo scoped to one doorbell tick, keyed on the patch. And frames are not
unconditionally identical (`Held.signals` is per-client; different tabs see different surfaces), so
it must be a lookup, never an assumption.

## What the bench had to learn first

The bench could not answer any of this when the work started. Three gaps, all now closed, recorded
so the same holes are not re-dug:

- **The document path stopped at the render.** `pageSignals` measures `renderPageTraced` and
  nothing after it, so the digest pass and the UTF-8 encode — the parts streaming removes — were
  invisible. `pageServe` covers them. `Server.page`'s head concatenation is still outside, because
  it is an instance method on a booted server; `pageServe` is a floor, not the whole serve.
- **The patch path was never benchmarked at all.** `wireTick` renders nodes someone already decided
  to send; the pull is what DECIDES, and `Patches.resume` — the changelog read, the visibility
  narrowing, the cache lookup, the digest compare — had no benchmark. `resumeSignals` /
  `resumeMorphs` / `resumeSignalsFanout` are the real path.
- **The signals case was measured where its argument is not.** `pageSignals` against `page` prices
  signal slots on a FIRST PAINT, which ADR 0017 explicitly says is the case it is not about (there
  they are a straight cost). `resumeSignals` against `resumeMorphs` prices them on the live tick,
  which is the claim.

The pull fixtures **assert their own shape** in `@Setup` (`checkTickShapes`): `signalTick` must
suppress every morph and produce a signals frame, `byteTick` must produce morphs. Whether a slot is
a signal slot is a property of the shipped card, so without the check a card change would quietly
turn `resumeSignals` into a second `resumeMorphs` — still green, still fast, measuring nothing.

## What the bench had to be fixed for, twice

The first version of the pull benches created a **fresh `RenderCache` per op**. In production the
cache is the slug's and outlives every tick (`LiveSlug.cache`), so that made every node of every
tick a cold miss — pricing a render the running server would often not do, and hiding the one
thing worth knowing. It also made `resumeSignals` and `resumeMorphs` come out equal, which is
what prompted "why isn't a signals tick faster?" and led to thread C. The fixture also had to
start moving `contentVersion` per tick, since `StateStore.update` stamps it and a fixture leaving
it at 0 would hit the cache forever and price nothing.

Both were bugs that produced *plausible* numbers. Worth remembering when adding a bench here: the
failure mode is not a crash, it is a result.

## Open

- Thread C's CEL attribute extraction is the undesigned part. Spike it before committing to C.
- Does thread A's step 1 (incremental digest) pay for itself alone? Measure before doing 2 and 3.
- `session.control` is an **unbounded** `Queue[IO, SseFrame]` holding pre-encoded byte arrays. Not
  part of either thread, but it is a memory risk on the target hardware and nothing bounds it.
