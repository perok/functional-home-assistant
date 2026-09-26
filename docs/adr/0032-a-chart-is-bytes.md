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
rather than normalising them, which a library that parsed colours would not.

## Consequences

- **A chart is an ordinary leaf card.** It patches, caches, digests, resumes and survives a morph
  like any other node, needs nothing vendored into `AssetCache`, and works with JS off.
- **The drawing is a transform STAGE, not the provider's job** — see
  [ADR 0031](0031-a-query-is-a-second-shape-of-slot.md). This ADR owns *how* a chart is made; that
  one owns *whose job it is*.
- **Open, and needing a Pi rather than a decision:** what these numbers look like on the target
  hardware — the question has narrowed to whether the FIRST chart in a session is acceptable,
  since every later one in the bucket is free, and to what the isolate's native heap costs
  alongside Pkl's Truffle. And whether ECharts' own SSR text estimate is good enough, or
  `setPlatformAPI({ measureText })` needs real Java font metrics.

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
