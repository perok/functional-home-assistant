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
| C — narrow the cache key | the render on every live tick | compare the resolved byte-slot VALUES before rendering | **DONE: 160 µs + 291 kB per tick per slug** |
| A — stream the document | **peak live bytes** per concurrent page open | thread a `Writer` through the walk to the socket | ~380 kB of ~500 kB peak |
| B — share the frame | per-client work on a live tick | memo the encoded frame across sessions | 3.2 µs + 10.7 kB per client per tick |

## Measured starting point

`RenderBench`, `-f 2 -wi 5 -i 5 -prof gc`. The table lives in `RenderBench`'s own scaladoc and is
the thing to re-run, not to copy around. What matters here:

- The document is **128 kB** and costs **3.78 MB** to serve (`pageServe`) — 29x its own size.
- `pageServe` minus `pageSignals` is **257 kB**: the digest pass and the UTF-8 encode, which is the
  churn streaming directly removes. Add `Server.page`'s head concatenation (unmeasured, ~130 kB)
  and it is roughly **10%**.
- `Traced.own` is a further **99 kB** held live: 77% of the document a second time, and on the
  page-open path used only to compute 200 digests.
- The remaining ~3.3 MB is the walk — transforms 0.96 MB, mustache contexts 0.39 MB, slot
  resolution, signal seeds. Streaming does not touch any of it.

**So thread A buys peak, not churn.** ~10% of the allocation, but most of the ~500 kB a concurrent
page open holds live across four materialisations — which is the number that multiplies by open
tabs, and the one that matters on a Pi. Anyone hoping to fix the 3.78 MB is looking at the walk,
which is a different plan (issue #130, and the transform memo recorded as a dead end in
`RenderBench`).

Two findings from benching the real tick path, both of which change what is worth doing:

- **An extra client on a tick costs 70 us and 117 kB** (`resumeSignalsFanout` − `resumeSignals`,
  over nine), against 262 us for the first. The `RenderCache` is removing about three quarters of
  each further client's work — it is earning its place.
- **A signals tick costs more than a bytes tick and should cost half.** That is thread C below,
  and it is the biggest thing here. ADR 0017 and ADR 0012 both now say so.

## Thread C — narrow the cache key — **LANDED**

Measured after the change, against the same benchmarks:

| | before | after | |
|---|---:|---:|---|
| `resumeSignals` | 261.7 µs / 442 kB | **101.7 µs / 151 kB** | 2.6x time, 2.9x bytes |
| `resumeSignalsPure` | 101.4 µs / 139 kB | 105.9 µs / 142 kB | unchanged, as it must be |
| `resumeMorphs` | 231.5 µs / 431 kB | 255.0 µs / 439 kB | unchanged (within noise) |
| `resumeSignalsFanout` | 892.3 µs / 1,491 kB | 751.3 µs / 1,246 kB | |

**`resumeSignals` now equals `resumeSignalsPure`**, which is the result that says the mechanism is
the intended one rather than an accident: the shipped card costs what the card the key already
protected costs. `resumeMorphs` unchanged says the bytes-moved path did not regress.

**The saving is PER TICK PER SLUG, not per client** — my earlier framing of it as per-client was
wrong. The fanout improved by 141 µs, about the same absolute amount as the single client's 160 µs,
because clients 2..10 were already hitting the cache: they shared the first client's render. What
thread C removes is that FIRST render, once per tick. The marginal client is still ~72 µs and is
untouched by this, and remains the thing to attack next if the tick path matters more.

### The original diagnosis, kept for the argument

A signals tick ought to be the cheap one: the node's bytes cannot have moved, so ADR 0012 keeps
the entity out of the `RenderCache` key, the previous entry stands, and nothing re-renders. That
is the design and it works — when it applies:

| | us/op | B/op |
|---|---:|---:|
| `resumeSignals` — shipped card | 261.7 | 442,296 |
| `resumeSignalsPure` — same card, name as a literal | 101.4 | 138,575 |
| `resumeMorphs` — bytes really moved | 231.5 | 430,638 |

**It does not apply to the shipped card, and one slot is why.** `renderInputs` keys on a per-entity
`contentVersion`, which `StateStore.update` bumps whenever anything about the entity moves. ADR
0012's exclusion therefore only bites where an entity reaches a node *exclusively* through signal
slots. The shipped `entityCard`'s name reads `friendly_name` as bytes, so the entity is in the key,
so every brightness change moves it, misses, and re-renders the node — to discover the bytes are
identical and suppress the morph.

That is why a signals tick currently costs *more* than a bytes tick rather than half of one.

### The fix — compare the resolved VALUES, do not predict the inputs

Two shapes were considered. **Take the first.**

**1. Memoise the resolved byte-slot values (chosen).** Before rendering a node, resolve only the
slots that travel as BYTES and compare them to what the cache entry was built from. Identical ⇒
the bytes are identical ⇒ reuse them and re-stamp the entry under the new `RenderInputs`, without
mustache, without composing the `.fh-cell` wrapper, without the digest.

Why this one:

- **It needs no static analysis at all**, so CEL and `Transform.Simple` are handled by the same
  code. You do not predict what the transform reads; you run it and look at what came out.
- **The cost is one evaluation per byte slot**, and on the shipped `entityCard` that is exactly
  ONE — the name, an `AttrOrId` (ADR 0028's fast tier, no engine). The signal slots have to be
  evaluated on a signals tick anyway, to know what to put in the frame. So the added cost is
  approximately nothing and the saving is approximately all of the ~8 µs per node.
- It degrades gracefully: a byte slot on a CEL transform pays that evaluation per tick, still far
  below a full node render.

**2. Track the attributes each evaluation actually read (not chosen, but available).** Key on
`(entity, attribute) -> value` for what the byte slots read, so an unchanged read set skips even
the transform evaluation.

Spiked, because the question was whether this could be done through CEL's API rather than by
guessing. **It can, and it is more precise than static analysis.** `Cel.EntityResolver` hands CEL
`entity.javaAttributes` as `attr`, and CEL indexes a `java.util.Map` through `get`/`containsKey` —
so wrapping that map in a recorder captures the exact read set through CEL's own contract.
Measured on the shipped shapes:

```
src        = state == 'on' ? string(attr['brightness']) : string(attr['color_temp_kelvin'])
attributes = [brightness]                    ← only the branch actually taken

src        = string(attr[state == 'on' ? 'brightness' : 'rgb_color'])
attributes = [brightness]                    ← a dynamic key; no static pass can see this

src        = <the shipped fill colour>
attributes = [rgb_color, color_temp_kelvin]  ← both, cel.bind evaluates both bindings
```

`dev.cel.common.navigation.CelNavigableAst` is on the classpath too, but a static pass is strictly
worse here: it must over-approximate a conditional to both branches and cannot see a dynamic key
at all.

Using a recorded read set as a key is sound by the usual dynamic-dependency argument — CEL is
pure, so if every input the previous evaluation read is unchanged, it takes the same path and
reads the same set. Keep this in reserve for the day a byte slot's transform is expensive enough
to matter; it is strictly more machinery for a saving option 1 mostly already gets.

### Where it goes

`RenderCache.apply`'s `modify` already branches three ways on the entry present. The value check is
a **fourth branch, inserted second**, and the order is the design:

```
1. inputs == inputs        -> hit, await the slot                    (today)
2. byte values match       -> NEW
     a. here.isAtLeast(inputs)  -> serve here's slot, install nothing
     b. otherwise               -> install Gen(inputs, here.slot), serve it
3. here.isAtLeast(inputs)  -> straggler: render fresh, install nothing (today)
4. otherwise               -> render and install                      (today)
```

2a is the subtle one and is why the check cannot simply re-stamp. A straggler whose values happen
to match must **not** install: re-stamping under its older `RenderInputs` would downgrade the
generation and hand the next caller a key that looks stale, which is exactly the eviction the
straggler rule exists to prevent. Serving the same slot costs it nothing — it is better off than
branch 3, which renders.

2b reuses the *existing* `Deferred` rather than rendering into a new one, so the bytes are shared
rather than recomputed and the digest is not taken twice.

`Gen` grows the resolved byte values alongside `inputs`; `apply` takes them as a parameter, since
only the caller can resolve them.

### Measured before building it

| | us/op | B/op | |
|---|---:|---:|---|
| `byteSlotResolve` | 5.5 | 21,412 | the pre-check, 20 nodes |
| `tickRender` | 109.0 | 297,510 | the `renderNodeById` it replaces, same 20 nodes |

**19.7x cheaper in time, 13.9x in allocation** — 0.28 µs against 5.45 µs per node, so the check
costs about 5% of the render it avoids. It cross-checks against the pull numbers:
`resumeSignals − resumeSignalsPure` is 141.5 µs, against `tickRender` 109 µs plus the digest
(~9 µs), the remainder being the cache-install machinery. So the fixture is measuring the thing it
claims to.

Projected: `resumeSignals` 236 µs → ~100 µs and 442 kB → ~161 kB. Re-measure after building it;
the projection is arithmetic, not a result.

### The seam that makes it cheap

`Renderer` already splits resolving from executing — `Resolved` is produced once by
`resolveTemplate`, and `executeResolved` runs per FORM (ADR 0012's "the two render forms" row).
The pre-check wants the resolve and not the execute, which is the split that already exists. What
does not exist yet is resolving *only the byte slots*: `Resolved` covers every slot.

**That narrowing is the whole trade, and the benchmark above is written to protect it.** A
pre-check that built a full `Resolved` would resolve every slot — including the shipped card's CEL
`fillColor`, the expensive one — and hand most of the saving straight back. `byteSlotResolve`
deliberately does not go through the `Resolved` seam for exactly that reason, and its scaladoc says
so, because the tempting "simplification" during implementation is to reuse `Resolved` and the
resulting number would still look fine.

The reverse index must stay WIDE (ADR 0012): a signal has to make its node a candidate or no frame
is ever computed for it. Only the render is skipped. Getting these the wrong way round fails
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

1. ~~**The chrome template takes the body as a mustache VALUE.**~~ **DONE.** Neither of the two
   options guessed at here was needed. `Templates` already had `FhRegionCode` for a `{{#region}}`
   SECTION; the symmetric hook for a raw `{{{name}}}` VARIABLE is
   `DefaultMustacheVisitor.value`, so `FhValueCode` + `FhScope.writerHoles` lets the body and the
   restored dialog write themselves into the page buffer. **The authoring contract does not
   change** — a theme keeps `{{{body}}}`/`{{{popups}}}` exactly as written, which the
   region-section option would have broken. Encoded `{{name}}` holes are left alone; escaping is
   their point and nothing escaped is large enough to matter.

   Measured, and about twice the projection, because removing the body `String` removes TWO
   copies (its own `toString` and the splice into the page buffer) rather than one:

   | | before | after | |
   |---|---:|---:|---|
   | `pageSignals` | 3,524,973 B | **3,278,496 B** | −246 kB (7.0%) |
   | `pageServe` | 3,782,170 B | **3,540,607 B** | −242 kB (6.4%) |
   | `pageSet` | 3,769,906 B | 3,519,046 B | −251 kB |

   Time is unchanged within noise (1425 → 1380 ± 132 µs), which is what an allocation change
   should look like.

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

**In context it is a much smaller prize, and that is the number to trust.** One encode is 3.2 us
and ~10.7 kB against a marginal client cost of 70 us and 117 kB — **about 5% of the time and 9% of
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

## An aside worth not re-deriving: `testFull` flakes under parallelism

Three different suites timed out across ~8 `testFull` runs — `PklDashboardBehaviourSuite`,
`AddonBootstrapSuite`, `SessionLifecycleSuite` — each passing in isolation, each a 30 s munit
timeout rather than an assertion failure. All three evaluate Pkl, which made "pkl is not
thread-safe" the obvious suspect. **It is not, and these were checked:**

- The `Evaluator` is built fresh per call and `close()`d in a `finally` — no shared instance.
- The module cache dir is per-test in the suites (`box.cache`), not the shared appdirs one.
- Concurrent evaluation does **not** serialize: 8 evaluations on 8 threads took 7 ms against
  11 ms sequential (22 cores). Cold engine build is 769 ms, warm evaluation 1 ms — pkl is
  EXPENSIVE the first time, not unsafe.
- `DashboardBuild` already runs evaluation inside `IO.blocking`, so it is not stealing compute
  threads.
- `LibPackage.build` is pure and writes nothing shared.

**The cause is still unknown, and the obvious next guess was checked and is also wrong.** Suite
parallelism is not it: `fh-datastar-view / Test / parallelExecution` is already **false** and
`Test / fork` is **false** (`sbt 'print fh-datastar-view/Test/parallelExecution'`). So suites in
this module already run one at a time, and the three "serial" `testFull` runs that came back 3/3
green were run with the setting flipped to the value it already had — they prove nothing at all.

What is known: the failure is always a 30 s munit timeout, never an assertion; every affected
suite evaluates Pkl; and every `--exclude-tags=Slow` run (which drops the six Playwright suites)
has been green. Cross-project test parallelism, browser-driver startup, and something inside the
`testReal` harness are all still open.

Not part of this plan's threads. Whoever picks it up: do not re-run the parallelism experiment,
and do not trust "716 green" from a single run.

## Open

- ~~Thread C's CEL attribute extraction is the undesigned part.~~ *Answered by the spike above:
  a recording `attr` map reads the exact set through CEL's own contract, and it beats static
  analysis on conditionals and dynamic keys. It also turned out not to be needed — comparing
  resolved values (option 1) needs no read set at all.*
- Does thread A's step 1 (incremental digest) pay for itself alone? Measure before doing 2 and 3.
- `session.control` is an **unbounded** `Queue[IO, SseFrame]` holding pre-encoded byte arrays. Not
  part of either thread, but it is a memory risk on the target hardware and nothing bounds it.
