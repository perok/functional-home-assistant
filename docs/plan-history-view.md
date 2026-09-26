# Plan — history in the more-info view

**Status:** proposal, nothing built. Issue: more-info should show where a reading has *been*, not
only where it is.

`components/moreinfo.pkl` says so itself today: *"HA's own more-info also carries history and
settings; neither is here."* This is the design for the first half.

Two questions were asked. The charting one is settled by a spike (§2): **ECharts runs on the
server, under GraalJS, and emits SVG** — so a chart is bytes, like every other card. The retrieval
one is the real design work, because it adds **a second kind of data** to a pipeline that currently
has exactly one.

---

## 1. What makes history different

Every slot value today is a CEL transform over one entity's **current** state, held in the one
global `StateStore`, and rendering is driven by the state stream: a change lands, the recorder
writes the changelog, sessions pull and render.

A series breaks four of those assumptions and reinforces a fifth:

| | State (today) | Series (new) |
|---|---|---|
| Where it lives | the `StateStore`, always resident | HA's recorder DB, fetched on request |
| Shape | one value | a few hundred to a few thousand points |
| Parameters | none — the entity IS the key | entity **+ window + resolution** |
| Who decides | the server, for everyone | **the viewer**, per tab |
| Cost | free to read | a DB query, tens to hundreds of ms |
| Changes | constantly | **never, below the newest point** |

That last row is the lever, and the rest of this plan is built on it. The past is immutable. Only
the tail grows. A pipeline that already separates "mostly static" from "moves every tick" is
exactly what we have.

### The vocabulary this needs

Three words for `docs/terminology.md`, chosen to not collide with what is there:

- **Series** — a time-ordered set of readings for one entity over one window. Not state: fetched,
  parameterised, and never in the `StateStore`.
- **Window** — the span and resolution a series covers (`last 24h at 5 min`). A viewer
  **selection**, in the sense the codebase already uses: it belongs with bake index and open set,
  not with entity state.
- **Provider** — the named thing that answers a series request. `history` is the one we would
  ship; a third party registers another under its own name.

Note what *isn't* new: a window is a selection, so it rides the machinery selections already have.

---

## 2. Charting: ECharts, rendered to SVG on the server

**Run Apache ECharts in a GraalJS context inside our own JVM and emit its SVG string into the
card.** No chart JavaScript reaches the browser.

This is the third answer this section has had, and the reason it beats the other two is that it
takes the good half of each: we do not write a chart library, and the browser gets bytes.

### Why not hand-rolled SVG

"Just emit SVG ourselves" sounds cheap and is not. It obliges us to write:

- value and time scaling, and nice-number tick selection on the value axis;
- tick selection on a **time** axis across 1h → 30d, where each magnitude wants a different
  sensible interval;
- **step-after** path generation, because HA state is a step function and a straight polyline is a
  lie about it;
- gaps and `unavailable` runs as breaks rather than interpolation;
- LTTB downsampling;
- a responsive `viewBox` that does not scale the label text with it.

That is a small chart library — several hundred lines with real tests, mediocre-looking for a
while, and a permanent maintenance surface. Plus one thing that does not show up until it bites:
**text cannot be measured without a text engine**, so axis margins get guessed, and the guess is
wrong at a different font, a longer unit, or wider numerals.

### Why not a chart library in the browser

Datastar morphs the DOM, and a client chart keeps its state in a JS object tied to an element, so a
morph that replaces that element severs it silently. That is solvable — a custom element with a
shadow root is invisible to the morph — but it costs a vendored ~350 KB–1 MB asset on every phone,
a custom element with its own lifecycle, a new failure class, and ADR 0024's "usable without JS".
The full design is kept in the appendix, because it is the right answer *if* we ever want true
interactivity.

### The spike: it works, and the numbers are fine

ECharts has had a zero-dependency SSR mode since 5.3: `echarts.init(null, null, { renderer: 'svg',
ssr: true, width, height })` then `chart.renderToSVGString()`. It needs no DOM and no canvas — only
a JavaScript engine, and **we already ship one**: `fh.view.runtime.JsIsolate`.

Measured on x86_64 / OpenJDK 25 before the isolate landed, so with Truffle interpreted — kept as
the floor, since the shipped numbers are the faster column further down:

| | |
|---|---|
| Engine boot + first eval of `echarts.min.js` (1 MB) | ~1.1 s, **once** |
| A further `Context` on the same `Engine` | 21–37 ms |
| First render in a context | ~500 ms |
| Warm render, 2 000 points | 84–190 ms |
| Warm render, 5 000 points | 120–155 ms |
| SVG out | ~19 KB, and **flat** from 2 000 to 5 000 points, because LTTB caps what is drawn |
| New jars | none — the isolate carries its own JS runtime |

Three findings worth keeping, because each was a live risk:

- **Pkl and JS coexist in one JVM.** Evaluate a Pkl module, then ECharts, then Pkl again: fine.
- **Truffle is backwards compatible in the direction we need.** `pkl-core` 0.32.1, built against
  Truffle 25.0.1, runs correctly on `truffle-api` **25.3.4.1**, so we take the newest GraalJS line
  and it pulls Truffle forward for Pkl too. The constraint is one-directional and worth stating:
  the runtime may be newer than the language, never older, so GraalJS and `pkl-core` must move
  together — bump GraalJS when Pkl's Truffle floor rises past it.
- **A four-line shim is required.** zrender starts an animation loop at init, so `setTimeout` and
  `clearTimeout` must exist. With `animation: false` they never need to fire, so no-ops do.

### The runtime shape

One `Engine`, one `Source`, a small pool of `Context`s:

- the **`Engine`** holds the parsed-code cache. It is what makes the 1.1 s parse a one-off: the
  second and third contexts cost 21–37 ms, not a second each.
- the **`Source`** for the bundle is built once and re-evaluated into each context.
- a **`Context` is not safe for concurrent use**, so renders are serialised — one owner draining a
  queue, per the repo's standing preference over a lock, or a pool of two if a measurement ever
  says one is a bottleneck. Everything runs in `IO.blocking`.
- it is built **lazily, on the first chart render**, so an instance whose dashboards have no chart
  pays neither the 48 MB of class loading nor the 1.1 s.

`echarts.setPlatformAPI({ measureText })` is in the bundle, so if ECharts' own SSR estimate places
axis labels badly we can hand it real font metrics from Java. That closes the one hole hand-rolled
SVG could not close at all.

### What we give up

Interaction. ECharts' docs are explicit that *"some operations related to interaction cannot be
supported"* in SSR. Concretely: no tooltip, no hover, no client-side zoom.

Two of those we do not want anyway. **Zoom is a window change**, which is a selection and therefore
a server round trip by design — only the server can re-downsample for the new span, and client-side
zoom into pre-downsampled points cannot. A hover readout can be a `<title>` per segment. If real
interaction is ever wanted, ECharts 5.5+ ships a tiny client that `hydrate()`s SSR output for
legend and hover; that is the appendix's territory and an addition, not a rewrite.

### Deployment: already done, and compiled

The engine and the image are already in place (`docs/plan-graaljs-isolate.md`, #372 and #373), so
this plan adds a caller, not a runtime:

- The engine is an **Oracle polyglot isolate**: `Engine.newBuilder("js").spawnIsolate(true)`,
  `HostAccess.SCOPED`, contexts built against one shared engine. `fh.view.runtime.JsIsolate` is
  the whole surface, and it is already on the classpath — this plan adds a caller, not a
  dependency.
- The image is glibc (`debian-base`) on JDK 25, the isolate jar is staged per architecture by the
  build, and Truffle unpacks its own resources into `/data/graal-cache` on first start.
- Guest JavaScript is **compiled**: 501 M ops/sec measured against 15.9 M in-heap, with no
  fallback-runtime warning for our engine.

Two consequences for the numbers below. They were measured **interpreted**, so they are an upper
bound on what a chart now costs — the isolate was 1.8–2.6× faster on exactly this workload. And
the memory question they raised is answered differently than expected: the isolate's guest heap is
native memory outside the JVM, and Oracle's holds flat as the series grows where the community
build does not.

### What interpreted rendering cost, as the upper bound

Measured x86_64, OpenJDK 25, ECharts 5.6.0, 600×300 — a plain `javac` program over two coursier
classpaths differing only in `spawnIsolate`. The isolate column is what we ship; the interpreted
column is kept because it is the floor a Pi could fall back to.

| | interpreted | isolate |
|---|---:|---:|
| `Engine` build | 40–47 ms | 110–113 ms |
| `Context` build | 21–24 ms | 31–34 ms |
| eval `echarts.min.js` (1 MB) | 1041–1114 ms | **306–327 ms** |
| first render, 2 000 points | 416–453 ms | **188–196 ms** |
| warm render, 2 000 points | 51–54 ms | **29–31 ms** |
| warm render, 5 000 points | 84 ms | **32 ms** |
| cold process, one chart — wall | 2.23 s | **1.47 s** |
| cold process, one chart — CPU | 11.9 s | **3.5 s** |
| RSS | ~400–530 MB | ~530–545 MB |

Four things in that table matter more than the headline 1.8–2.6×:

- **The CPU column, not the wall column, is the Pi's number.** This box hides most of the
  interpreted cost behind cores that a Pi does not have: HotSpot is busy C2-compiling Truffle's
  *interpreter* in the background, 11.9 s of CPU against 2.2 s of wall. The isolate does not need
  that work done at all. Wall time on a 4-core Pi tracks CPU far more closely, so the gap there
  should be wider than anything measured here — which is an inference, not a measurement, and the
  reason a Pi run is still the deciding evidence.
- **The gap widens with the series.** Interpreted grows 51 → 84 ms from 2 000 to 5 000 points; the
  isolate is flat at 29 → 32 ms. Compiled execution is what makes render cost track the SVG (which
  LTTB already caps) rather than the input.
- **The SVG is byte-identical between the two modes.** Checked, not assumed. So the mode is a pure
  performance switch: wire snapshots taken under one pass under the other, and flipping it is not
  a rendering decision.
- **The engine shape is already built.** `JsIsolate.engine` is a process-lifetime `Resource`, so
  the cold-process row is paid at add-on start, not on a user's first chart.

The host↔guest boundary, which is what an isolate is usually criticised for, is not a problem at
our shape and the instinct about it is backwards. Per-call overhead is 376 ns interpreted vs 964 ns
isolated, so a chatty design would indeed suffer. But we are not chatty: one call in, one SVG
string out. And passing 2 000 points as a host `double[]` costs **0.21 ms interpreted vs 0.02 ms
isolated** — the compiled guest loop more than pays for the crossing — while the "send one JSON
string and parse it inside" trick that seems safer is the one that gets *slower* (0.27 → 0.48 ms),
because the string is copied across. If we ever go isolated, hand the series over as a primitive
array, not as JSON.

`echarts.setPlatformAPI({ measureText })` backed by a Java callback also works under `SCOPED` in an
isolate — verified, since that is the guest→host direction and the one feature the plan leans on
that a boundary could have taken away.

The one thing an isolate does **not** buy is Pkl: `pkl-core` embeds Truffle directly rather than
through the polyglot isolate API, so it stays interpreted. That is fine and is not this plan's
problem — Pkl's own hot path bails out of compilation anyway
(`docs/issue-report-3-graalvm-polyglot-isolate.md`), and it runs at startup, never per render.

---

## 3. Retrieval: a provider seam, shaped like `ServiceCalls`

### Why a new seam rather than widening `Slot`

`Slot` is defined over the card's entity in the `StateStore`. Widening it to "or a fetched series"
would put a network call behind a field that every card resolves on every render. The seam belongs
one level out.

`ServiceCalls` is the precedent and the shape to copy. It exists because **identity is the
question**: HA attributes a call to whoever owns the connection, so the one shared socket makes
every tap the add-on's own. History has the same problem — recorder data is permission-scoped per
user — and therefore wants the same answer: the fetch takes the `Request`, because that is what
carries the person.

```scala
trait SeriesProvider {
  def fetch(req: Request[IO], q: SeriesRequest): IO[Series]
}
```

with `SeriesRequest(entityId, window, resolution)` as a named type rather than four positional
arguments, and providers registered by name so a library author adds one without touching the
renderer.

### The HA side

`ha-api`'s WS client is a set of case classes named after HA's command types, so this is one row
each. **Both commands are on the WS API** — measured against the live instance (HA core
2026.9.3), so nothing here needs a REST client:

- `history/history_during_period` — raw recorder rows. With `minimal_response: true` and
  `no_attributes: true` a point is `{"s": "23.1", "lu": 1789755910.543}`: state as a STRING, and
  `lu` (last_updated) as epoch **seconds with a fractional part**. Without those flags every point
  repeats the full attribute map — 8 KB becomes 37 KB for the same 223 points, so the flags are
  not an optimisation, they are the calling convention.
- `recorder/statistics_during_period` — pre-bucketed statistics, `period` one of
  `5minute`/`hour`/`day`/…. Shape depends on the sensor's `state_class`:
  a measurement gives `{start, end, min, mean, max, last_reset}`, a total gives
  `{start, end, state, sum, change, last_reset}` — `start`/`end` in epoch **milliseconds**, not
  seconds. Two units in one feature; the decoders must not share a timestamp type.

**The cutover is about retention, not size, and the plan had this wrong.** Measured on one
temperature sensor:

| window | history | statistics (hour) |
|---|---|---|
| 1 h | 10 pts, 0.4 KB | **0 pts** |
| 24 h | 223 pts, 8 KB | 23 pts, 2.5 KB |
| 7 d | 1 396 pts, 50 KB | 167 pts, 18 KB |
| 30 d | 1 836 pts, 66 KB | 719 pts, 76 KB |

At 30 days statistics is *bigger* than raw history — because raw history is not there any more.
It is clamped by the recorder's purge horizon (~10 days on this instance: 22 extra days of window
bought 440 extra points), while statistics covers the whole range. So the rule is **history for
what the recorder still holds, statistics for what it has purged**, and the 1 h row is the other
end of the same fact: hourly buckets have nothing to say about the last hour. A window shorter
than the bucket must use history, and one longer than the purge horizon must use statistics.

Two more things the measurement settled:

- **Statistics is empty, not an error, for an entity without a `state_class`** — a
  `binary_sensor` and a `light` both came back `{}`. So the build-time knowledge
  `SensorEntity` carries (since #349) decides which source is even *available*, and the runtime
  never has to interpret an empty result as a failure.
- **Fan-out is the expensive axis, not window length.** Ten chatty measurement sensors over 24 h
  is 910 KB and 2.3 s in one call, against 8 KB and 30 ms for the one temperature sensor. That is
  the argument for the per-`(entity, window, bucket)` cache below being per *entity*: batching ten
  entities into one request shares a round trip but shares no cache entry.

### Caching, and where immutability pays

A `SeriesStore` keyed by `(provider, entityId, window, asOfBucket)`, where `asOfBucket` is *now*
rounded down to the window's resolution.

That key is the whole trick. Every viewer looking at "last 24h" shares one fetch **and one
rendered SVG** for the entire bucket, and the entry expires by the bucket rolling over rather than
by a timer. It is the pull-side analogue of `StateStore`, and the same principle as `RenderCache`:
keyed by what the render *read*. It is also what keeps the GraalJS context off the hot path — one
render per bucket, not one per viewer.

### Downsampling stays server-side

ECharts' `sampling: 'lttb'` decides how many points it *draws*. We downsample before that anyway —
LTTB from `com.ggalmazor:downsampling`, target ~300 points — because the fetch itself should not carry thousands of rows through the
process for every bucket, and because it is the one place the work is done once for every viewer.

---

## 4. How this navigates static vs. live — the part that touches the pipeline

This is the question that was actually asked, and the answer is smaller than expected because the
model already carries the distinction.

`LayoutNode.Component` has two lists, and their doc says why neither derives the other:

- `liveEntities` — filtered to `Reads.Live`, and it feeds the reverse index that decides
  **candidacy**. A node not in it is never woken by a state change.
- `liveEntitiesAsBytes` — the same minus signal-only reads, and it feeds `renderInputs`, the
  **cache key**.

A series slot is `reads = onRender` in the existing vocabulary — *"read every render, and never a
reason to have one."* So:

**A history chart is not a candidate on a state tick, for free.** It reads no entity live, so it is
not in `liveEntities`, so a reading arriving does not wake it. No change to candidate selection is
needed. This is the single most important consequence and it falls out of the existing design.

What *does* need a change is the cache key. `RenderInputs` is currently only per-entity content
versions; a chart's bytes depend on the series key instead. So:

> **`RenderInputs` gains a second component: the series keys the node read.** One field, and it is
> the only pipeline change this design requires.

`docs/architecture-rendering-pipeline.md` §6 (the pull path) is the box that moves.

### Live updates ride SSE, unchanged

Server-rendered SVG is what makes this boring, and boring is the point: the chart is an ordinary
leaf, so a new rendering reaches an open page as a `datastar-patch-elements` fragment like any
other node, through the same per-slug diff, the same fragment log, the same resume. Nothing about
the transport is special-cased, and a reconnect replays it like anything else.

What matters is **when** those bytes move, and the answer is: only when the series key does — a
window change, or the bucket rolling over. A patch is ~19 KB, so a chart that re-rendered on every
reading would be the most expensive node on the page by an order of magnitude. It cannot: nothing
wakes it on a state tick (above), and the live present lives in its own region (below).

### The card shape

A history card is **structural, with two regions** — the same split as the slider's head-and-rows,
for the same reason:

- `chart` — a **leaf** holding the SVG. Cached hard on the series key.
- `now` — the live present, as a **signal slot**. Updates every tick, costing one entry in a
  signals frame, while the chart's bytes stand still.

A tick on the current reading must never repaint the chart. Two regions is what guarantees that
structurally, rather than by anyone remembering to.

### Window selection is a bake group

"Changing the axis" is `1h / 24h / 7d / 30d` — a tab bar. That is a bake group with a
state-independent selection, which is what `components/surface.pkl` already builds, including the
pending/committed handling from ADR 0025 so the press is instant while the fetch is in flight. The
window rides `ui_<group>` like any other selection and survives a reload in the URL.

No new selection machinery. The one genuinely new thing is that baking a window must be able to
*trigger a fetch*, which today's bake cannot — surfaces are rendered from state alone.

### Fetching is bounded by the surface

More-info is a **triggered surface**, rendered only while open. So the fetch is bounded by the
popup being open, which is the laziness we want and already have: nothing is fetched for a chart
nobody is looking at, and closing the popup ends the obligation.

---

## 5. Phases

Each is independently mergeable and independently useful.

1. **`ha-api`**: the two WS commands and their decoders. Testable against `FakeHomeAssistant`, no
   live HA.
2. **`SeriesStore` + `SeriesProvider`**, pure core split from the fetch — the downsampler and the
   bucket key are pure and are where the tests go. Source selection is decided by a learned
   `Retention` rather than a threshold: a window it already covers costs one call, an unproven one
   asks both sources in parallel and keeps whichever covers more time, and the history half is what
   teaches it. Retention is a lower bound taken as the MAXIMUM across entities, because per entity
   a daily purge and a day-old sensor give the same answer.
3. **`RenderInputs` gains the series component.** The pipeline change, on its own, with the
   architecture doc updated in the same commit. The prediction held: a series slot is `onRender`,
   which `liveEntities` already filters out, so a chart is not a candidate on a state tick without
   anything being arranged for it. What the phase did NOT include is the live half — nothing fills
   a bucket on the pull path yet, so a rolled bucket does not wake its node; that is phase 6's.
4. **The chart renderer**: the vendored ECharts bundle as a resource and a host behind a plain
   `IO[String]` — series in, SVG out. The engine is `JsIsolate`, already on the classpath and
   already a process-lifetime `Resource`, so this phase adds the `Source` and the context handling
   and nothing else. Its tests are ordinary: no browser, no HA, just a function.
5. **Pkl `core/series.pkl` + a shipped `c.historyChart(e)`** leaf, with the theme's colours folded
   into the option object.
6. **Window selection** as a bake group over the existing surface machinery.
7. **`moreInfoBody` gains the chart**, and `moreinfo.pkl`'s "neither is here" comment stops being
   true and gets rewritten.
8. **Docs**: terminology (series / window / provider), architecture §6, and an ADR for the provider
   seam — the decision that needs a home readers will find is *why a fetched series is a second
   kind of data and not a widened slot*, with "a chart is bytes, and the JS that makes them runs
   here" as its companion.

---

## 6. Open questions

The HA-API ones are answered and have moved into §3, where the design that depends on them lives.
What is left needs a Pi or a rendered chart, not a decision.

1. **Whose identity should read?** The person's token is the correct answer for a permission-scoped
   read, but it means a second connection per user, exactly as issue #198 describes for taps.
   Whether history is worth that cost is a judgement call, not a technical one.
2. **What do the §2 numbers look like on the Pi?** Everything there is x86_64. The chart is now on
   a compiled isolate, so the question has narrowed to whether the *first* chart in a session is
   acceptable — every later one in the bucket is free — and to what the isolate's native heap
   costs alongside Pkl's Truffle. Shares the Pi run `docs/plan-graaljs-isolate.md` is already
   waiting on.
3. **Is ECharts' SSR text estimate good enough**, or does `setPlatformAPI({ measureText })` need
   real Java font metrics? Cheap to answer once a real dashboard renders one.
4. **Does a window change need a new fetch or a re-slice?** Fetching the widest window once and
   slicing it for narrower ones trades memory for round trips. Probably wrong for 30d, probably
   right for 1h/24h — and the retention finding pushes against it too, since the widest window is
   the one that has to come from a different command.

---

## Appendix — the client-side option, if interaction is ever wanted

Not the plan. Recorded because it was designed, the finding under it was measured, and it is what
we would build the day someone wants a draggable time axis or a real tooltip.

**The obstacle is the morph.** A canvas/WebGL chart keeps its state in a JS object associated with
an element; a morph that replaces that element severs the association silently — no error, a dead
chart.

**`data-ignore-morph` is not the fix.** The `datastar` skill records the measurement: clause E of
the guard is one-sided and unconditional, so every element *inside* a marked subtree is refused as
a patch target forever. Marking a chart host to protect it also kills every live update under it.

**A shadow root is the fix, and it is free.** The pinned bundle
(`assets-cache/46cf17bf647e-datastar.js`, v1.0.2) contains **zero occurrences of `shadow`,
`attachShadow` or `customElement`** — the morph walks light-DOM children only. So a custom element
holding all of its chart DOM in a shadow root presents the morph with one tag carrying attributes
and no children: it reconciles the attributes and stops.

That makes the host **ordinary rather than protected**, which is the counter-intuitive part: it is
patched like any leaf, and `attributeChangedCallback` → `setOption` *is* the update path. The data
rides an **attribute, not a signal** — ADR 0011: every non-`_` signal is serialised into every
action POST and every SSE reconnect for the life of the page, which is the wrong place for a
multi-KB payload.

What it would cost, if chosen: a ~350 KB `echarts/core` custom build as an npm dependency and a
fifth vite entry (served hashed and `immutable` from the jar, so offline still holds); a
`disconnectedCallback` → `dispose` plus a `ResizeObserver`, or a closed popup leaks a chart per
open; theme colours passed through the option, since a shadow root does not inherit the page's CSS;
and a smoke test that patches around a live chart, because "the morph replaced the host" is a
failure class that does not exist anywhere else in this codebase.

The cheaper middle road, if only hover is wanted: ECharts 5.5+ ships a small client that
`hydrate()`s **server-rendered** SSR output for legend toggles and hover. That keeps §2 exactly as
it is and adds a few KB, rather than moving the chart to the browser.
