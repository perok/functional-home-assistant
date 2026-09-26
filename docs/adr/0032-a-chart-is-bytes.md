# ADR 0032 — A chart is bytes, and the JavaScript that makes them runs here

Apache ECharts runs in a GraalJS context inside our own JVM and emits an SVG string into the
card. No chart JavaScript reaches the browser.

## Context

A chart is the first card whose value is markup rather than an escaped scalar, and the first that
needs a drawing engine at all. Three answers were possible and the third takes the good half of
each: we do not write a chart library, and the browser gets bytes.

## Why not a chart library in the browser

**The morph is the obstacle, and it is ours rather than a library's fault.** A canvas or WebGL
chart keeps its state in a JS object associated with an element; Datastar morphs the DOM, and a
morph that replaces that element severs the association **silently** — no error, a dead chart.

`data-ignore-morph` is not the fix, and the `datastar` skill records the measurement: clause E of
its guard is one-sided and unconditional, so every element *inside* a marked subtree is refused as
a patch target forever. Marking a chart host to protect it also kills every live update beneath
it. It is a "this DOM is client-owned, server keep out" tool, not a "skip this subtree" one.

That leaves a custom element with a shadow root — which **does** work, and is recorded below
rather than built. What it costs is a vendored ~350 KB asset on every phone, a fifth vite entry, a
lifecycle to get wrong, a failure class that exists nowhere else in this codebase, and ADR 0024's
"the DOM we send should be as usable as possible without JS".

## Why not hand-rolled SVG

"Just emit SVG ourselves" sounds cheap and is not. It obliges us to write value and time scaling,
nice-number tick selection, tick selection on a **time** axis across 1h → 30d where each magnitude
wants a different interval, **step-after** path generation (HA state is a step function and a
straight polyline lies about it), gaps and `unavailable` runs as breaks rather than interpolation,
LTTB downsampling, and a responsive `viewBox` that does not scale label text with it.

That is a small chart library: several hundred lines with real tests, mediocre-looking for a
while, and a permanent maintenance surface.

Plus one thing that does not show up until it bites: **text cannot be measured without a text
engine**, so axis margins get guessed — and the guess is wrong at a different font, a longer unit,
or wider numerals. `echarts.setPlatformAPI({ measureText })` is the escape hatch ECharts offers
and hand-rolled SVG has no equivalent of.

## Decision

ECharts has had a zero-dependency SSR mode since 5.3 — `echarts.init(null, null, { renderer:
'svg', ssr: true, width, height })` then `renderToSVGString()`. It needs no DOM and no canvas, only
a JavaScript engine, and **we already ship one**: `fh.view.runtime.JsIsolate`.

Measured, x86_64 / OpenJDK 25 (read the ratios, not the absolutes):

| | interpreted | isolate (shipped) |
|---|---:|---:|
| `Engine` build | 40–47 ms | 110–113 ms |
| `Context` build | 21–24 ms | 31–34 ms |
| eval `echarts.min.js` (1 MB) | 1041–1114 ms | **306–327 ms** |
| first render, 2 000 points | 416–453 ms | **188–196 ms** |
| warm render, 2 000 points | 51–54 ms | **29–31 ms** |
| warm render, 5 000 points | 84 ms | **32 ms** |
| cold process, one chart — wall | 2.23 s | **1.47 s** |
| SVG out | ~19 KB, flat 2 000 → 5 000 points, because LTTB caps what is drawn | |

**Compiled execution is what makes render cost track the SVG rather than the input.** Interpreted
grows 51 → 84 ms from 2 000 to 5 000 points; the isolate is flat at 29 → 32 ms.

Three findings worth keeping, each of which was a live risk:

- **Pkl and JS coexist in one JVM.** Evaluate a Pkl module, then ECharts, then Pkl again: fine.
- **Truffle is backwards compatible in the direction we need.** `pkl-core` 0.32.1, built against
  Truffle 25.0.1, runs correctly on `truffle-api` 25.3.4.1 — so we take the newest GraalJS line
  and it pulls Truffle forward for Pkl too. The constraint is one-directional and worth stating:
  the runtime may be newer than the language, never older, so GraalJS and `pkl-core` move
  together. Bump GraalJS when Pkl's Truffle floor rises past it.
- **A four-line shim is required.** zrender starts an animation loop at init, so `setTimeout` and
  `clearTimeout` must exist. With `animation: false` they never need to fire, so no-ops do.

### Which JavaScript: Oracle's polyglot isolate

Four ways to run ECharts SSR, under the add-on's own flags (`-Xms64M -Xmx512M
-XX:+UseSerialGC`), 300 points, 300 renders, median of three runs:

| | warm median | RSS | anonymous |
|---|---:|---:|---:|
| interpreted, in-heap | 21 ms | 322 MB | 295 MB |
| community isolate | 10 ms | 421 MB | 315 MB |
| **Oracle isolate** | **10 ms** | **152 MB** | **80 MB** |
| GraalVM JDK, compiled in-heap | 11 ms | 785 MB | 637 MB |

**The isolate is cheaper in memory than interpreting**, because the guest heap and ECharts' AST
live in the isolate's native heap instead of the JVM's — 2× faster and 170 MB smaller, so there is
no trade to weigh, and on a 4 GB Pi (#237) memory decides. **Oracle over community** is memory
too: they render identically, but Oracle's holds flat at 99 → 106 MB anonymous as the series grows
17× where community goes 328 → 469 MB. **A GraalVM JDK base image is rejected**, not deferred: 5×
the isolate's RSS for the same render time, and its one argument, Pkl, does not hold — Pkl's own
`FunctionNode` bails out of compilation on every JDK, Oracle GraalVM included. Native image is
rejected on reachability cost and because it would slow the non-chart render loop. The community
isolate stays a drop-in if the licence is ever unwanted (`home-addon/README.md`).

Four facts about running it, each of which looks wrong and is not:

- **The host runs Truffle's FALLBACK runtime, deliberately.** `truffle-runtime` would drag
  libgraal into the JVM for ~186 MB; guest code compiles inside the isolate, which has its own
  compiler. Measured on the shipped classpath: 501 M ops/sec in the isolate against 15.9 M
  in-heap. So **`Engine.supportsCompilation()` is not a health check** — it reports the host and
  says `false` about an engine doing 501 M ops/sec.
- **`spawnIsolate` goes on `Engine.Builder`, not `Context.Builder`**, where on a shared engine it
  is silently ineffective. It exists there from 25.3 on, which is why polyglot is pinned to 25.3.x
  rather than the 25.0 LTS line.
- **A library and jars of different versions run silently** — no warning, correct output, a
  GraalJS other than the one declared — and `Engine.getVersion()` reports the library's
  (`docs/issue-report-3-graalvm-polyglot-isolate.md`). `JsIsolateSuite` compares the two and fails.
- **Without the isolate jar, `js` is not a language at all** (`Available languages are: [pkl]`):
  the image carries no in-heap JavaScript, so `JsIsolate.engineOrInHeap`'s fallback only ever
  helps a development classpath. In the image a broken isolate means every chart shows its error.

### The runtime shape

**ONE context for the process, serialised by a `Mutex`.** The obvious reason is the weaker one: a
Graal context is not safe for concurrent use, which a pool would also solve. The real one is that
evaluating ECharts costs ~300 ms per context and a pool pays that per member. A warm render is
~30 ms, so one context absorbs the traffic — a page carrying eight distinct charts on a cold
bucket is ~240 ms of serialised drawing, and every viewer after the first in that bucket pays
nothing.

It is built **lazily** (`Resource#memoizedAcquire`), so an instance whose dashboards hold no chart
pays neither the ECharts evaluation nor the isolate's heap.

### What this gives up, and what it does not

Interaction. ECharts' docs are explicit that interaction-related operations do not apply to SSR
output. Hover, tooltips and a draggable axis are out.

What it does **not** give up is theme: inline SVG inherits the page's custom properties, so
`var(--fh-accent)` reaches the stroke verbatim — measured, because zrender passes colours through
rather than normalising them, which a library that parsed colours would not. That makes custom
properties the ONLY way a theme reaches a chart, and every colour in the option is one: the bytes
are shared by every viewer and survive a light/dark switch, so a literal (ECharts' own grey
labels and grid, which the defaults drew) is wrong on the other palette. A theme sets
`--fh-chart-line`, `--fh-chart-fill`, `--fh-chart-grid` and `--fh-chart-label`, each falling back
to a base `--fh-*` token; text inherits the page's font. `ChartSuite` fails on a literal colour.

## Consequences

- **A chart is an ordinary leaf card.** It patches, caches, digests, resumes and survives a morph
  like any other node, needs nothing vendored into `AssetCache`, and works with JS off.
- **The drawing is a transform STAGE, not the provider's job** — see
  [ADR 0031](0031-a-query-is-a-second-shape-of-slot.md). This ADR owns *how* a chart is made; that
  one owns *whose job it is*.
- **Open: a chart does not truly resize.** It is drawn once at a fixed size (400×160 by
  default, `c.historyChart(s).size(w, h)` to override) and the browser scales the whole
  picture, labels and stroke included, because the server draws before any layout exists and
  the first HTML must be complete. Real resizing means laying the chart out at its real width,
  and the candidates are: several widths drawn into one card with a container query picking one
  (first-paint-correct, shared bytes, ~3× the SVG); the client reporting its width as a node
  variable, bucketed, so a resize is a re-query (exact, but the first paint guesses); a
  stretching plot with HTML axis labels (one drawing, but we pick the ticks — what "why not
  hand-rolled SVG" rejects); or drawing in the browser as recorded below, which would make this
  server drawing the no-JS fallback rather than the chart. Not decided; explore before building.
- **Open, and needing a Pi rather than a decision:** what these numbers look like on the target
  hardware — the question has narrowed to whether the FIRST chart in a session is acceptable,
  since every later one in the bucket is free, and to what the isolate's native heap costs
  alongside Pkl's Truffle. Every number above is x86_64; CI runs the isolate on aarch64 only under
  QEMU, which proves it runs and says nothing about cost. Nothing deployed can see that cost
  either: the isolate's heap is native memory inside a `dlopen`ed library, invisible to JVM gauges
  and to `NativeMemoryTracking`. RSS and anonymous from `/proc/self/smaps_rollup` do see it, and
  publishing them as two gauges through the existing `MeterProvider` would make the Pi answerable
  from a normal install — worth doing before that run. And whether ECharts' own SSR text estimate
  is good enough, or `setPlatformAPI({ measureText })` needs real Java font metrics.

## The client-side option, if interaction is ever wanted

Not the decision. Recorded because it was designed, the finding under it was measured, and it is
what we would build the day someone wants a draggable time axis or a real tooltip.

**A shadow root is the fix, and it is free.** The pinned bundle
(`assets-cache/46cf17bf647e-datastar.js`, v1.0.2) contains **zero occurrences** of `shadow`,
`attachShadow` or `customElement` — the morph walks light-DOM children only. So a custom element
holding all of its chart DOM in a shadow root presents the morph with one tag carrying attributes
and no children: it reconciles the attributes and stops.

That makes the host **ordinary rather than protected**, which is the counter-intuitive part: it is
patched like any leaf, and `attributeChangedCallback` → `setOption` *is* the update path.

The data would ride an attribute, or a `_`-prefixed signal — excluded by the pinned bundle's
default `filterSignals.exclude = /(^|\.)_/`, so it is never serialised into an action POST or an
SSE reconnect, which is the ADR 0011 cost that made this look blocked. Size is not the objection:
~300 points as JSON is a few KB against ~19 KB of SVG, and the payload already exists — it is what
`passthrough` answers with.

What it would cost: a ~350 KB `echarts/core` custom build and a fifth vite entry (served hashed
and `immutable` from the jar, so offline still holds); a `disconnectedCallback` → `dispose` plus a
`ResizeObserver`, or a closed popup leaks a chart per open; theme colours passed through the
option, since a shadow root does not inherit the page's CSS; and a smoke test that patches around
a live chart, because "the morph replaced the host" is a failure class that exists nowhere else
here.

The cheaper middle road, if only hover is wanted: ECharts 5.5+ ships a small client that
`hydrate()`s **server-rendered** SSR output for legend toggles and hover. That keeps everything
above exactly as it is and adds a few KB, rather than moving the chart to the browser.
