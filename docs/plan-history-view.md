# Plan — history in the more-info view

**Status:** proposal, nothing built. Issue: more-info should show where a reading has *been*, not
only where it is.

`components/moreinfo.pkl` says so itself today: *"HA's own more-info also carries history and
settings; neither is here."* This is the design for the first half.

Two questions were asked, and they have very different answers. The charting one is settled by one
constraint — Datastar morphs the DOM — and the shape that satisfies it. The retrieval one is the
real design work, because it adds **a second kind of data** to a pipeline that currently has
exactly one.

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

## 2. Charting: an ECharts custom element the morph is allowed to patch

**Ship a client-side chart — ECharts — inside a custom element with a shadow root, configured
through an attribute.**

There is exactly one hard constraint, and it is not weight or vendoring:

**Datastar morphs the DOM.** A canvas chart keeps its state in a JS object associated with an
element. A morph that replaces that element severs the association silently — no error, a dead
chart. So a client-owned chart needs a structural guarantee, not a convention.

**`data-ignore-morph` is not that guarantee.** The `datastar` skill records the measurement: clause
E of the guard is *one-sided and unconditional*, so every element **inside** a marked subtree is
refused as a patch target forever. Marking a chart host to protect it also kills every live update
under it. It is a "this DOM is client-owned, server keep out" tool, not a "skip this during someone
else's morph" tool.

### A shadow root is the guarantee, and it costs nothing

The pinned bundle (`assets-cache/46cf17bf647e-datastar.js`, v1.0.2) contains **zero occurrences of
`shadow`, `attachShadow` or `customElement`**. The morph walks light-DOM children only. So a custom
element that puts all of its chart DOM in a shadow root presents the morph with a single tag
carrying attributes and **no children at all**: the morph reconciles the attributes and stops.

That inverts the escape-hatch rule an earlier draft of this plan wrote down. The host is not
"rendered once and never patched again" — it is patched like anything else, and **the attribute
change IS the update path**: `attributeChangedCallback` → `setOption`. A history chart is therefore
an ordinary leaf card. It patches, caches, digests and resumes with the rest of the tree, and it
needs no isolation mechanism, no `data-ignore-morph`, and no second update channel.

The data rides an **attribute, not a signal** — ADR 0011: every non-`_` signal is serialised into
every action POST and every SSE reconnect for the life of the page, which is the wrong place for a
multi-KB chart payload.

### Why not server-rendered SVG, which was the earlier recommendation

It is not a bad answer; it is a worse total system than it looked, once the two constraints it was
optimising for were relaxed (vendoring, and a 1 MB JS-only dependency, are both acceptable here).
What "just emit SVG" actually obliges us to write:

- value and time scaling, and nice-number tick selection on the value axis;
- tick selection on a **time** axis across 1h → 30d, where each magnitude wants a different
  sensible interval;
- **step-after** path generation, because HA state is a step function and a straight polyline is a
  lie about it;
- gaps and `unavailable` runs as breaks rather than interpolation;
- LTTB downsampling;
- a responsive `viewBox` that does not scale the label text with it.

That is a small chart library — several hundred lines with real tests, mediocre-looking for a
while, and a permanent maintenance surface.

And one thing that does not show up until it bites: **text cannot be measured server-side.** Axis
margins must be sized to the widest label, so a server-rendered chart *guesses*, and the guess is
wrong at a different font, a longer unit, or a locale with wider numerals. The browser measures.

**Server-side charting libraries do not rescue that route.** JFreeChart or XChart can emit SVG, but
they produce a static picture — no hover, no zoom — with defaults we would theme by hand anyway, in
exchange for a heavyweight AWT-flavoured dependency. They buy the axis math and nothing else.
ECharts' own SSR mode needs node at *request* time; we have node only at build time.

| Option | Morph fit | Cost | Verdict |
|---|---|---|---|
| **ECharts in a shadow root** | patches like any leaf | ~350 KB custom build | **Ship this** |
| uPlot, same shape | same | ~45 KB | viable if size ever matters; fewer batteries |
| Server-rendered inline SVG | perfect — it *is* bytes | a chart library we write | the sparkline case only |
| Server-side SVG lib (JFreeChart/XChart) | perfect | heavy dep, static output | no |
| Any chart lib in the light DOM | severed by a morph | — | no |

What ECharts brings that we would otherwise write: `step: 'end'`, `sampling: 'lttb'`, `dataZoom`,
tooltips, time-axis tick selection, and resize.

### What this takes on — stated, not glossed

- **A ~350 KB dependency.** An `echarts/core` custom build (LineChart + GridComponent +
  TooltipComponent + DataZoomComponent) rather than the ~1 MB full bundle. It is an **npm
  dependency and a fifth vite entry**, not an `AssetCache` vendored CDN asset: it lands in managed
  resources as `web/chart-<hash>.js`, is served `immutable` through `FrontendAssets`, and is inside
  the jar — so offline-on-a-LAN is by construction, with nothing to rewrite. Only a page that
  contains a chart loads it.
- **A new failure class that does not exist today:** a chart that dies because the morph *replaced*
  the host rather than reconciling it. Mitigated by a stable node id and by the per-node identity
  cache (identical bytes are never patched at all), but it wants a smoke test that patches around a
  live chart and asserts it still draws.
- **A lifecycle we have not needed before:** `disconnectedCallback` → `dispose`, plus a
  `ResizeObserver`, or a closed more-info popup leaks a chart instance per open.
- **Theme colours must be passed in.** Inside a shadow root the theme's CSS does not reach the
  chart, so line/axis/text colours come from the theme tokens through the option object. That is a
  real coupling and belongs in the shipped component, not in each author's hands.
- **We give up ADR 0024's "usable without JS" for this one card.** Accepted: a chart is a picture,
  not a control, and every control on the page keeps working without it.

### Where server SVG still wins

A tiny inline **sparkline** on a card face — no axes, no labels, no interaction — is ~30 lines of
path emission and none of the problems above. Worth having later, as its own thing. It is not this
card.

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
each:

- `history/history_during_period` — raw recorder rows. Right for short windows.
- `recorder/statistics_during_period` — pre-bucketed 5-minute/hourly long-term statistics. Far
  cheaper for anything over ~24h, and it only exists for entities with a `state_class` — which
  `SensorEntity` already models since #349, so the dashboard can *know at build time* which source
  an entity supports.

That pairing is worth stating as the design rather than an optimisation: **short window → raw
history, long window → statistics**, chosen from the schema we already generate.

### Caching, and where immutability pays

A `SeriesStore` keyed by `(provider, entityId, window, asOfBucket)`, where `asOfBucket` is *now*
rounded down to the window's resolution.

That key is the whole trick. Every viewer looking at "last 24h" shares one fetch and one render for
the entire bucket, and the entry expires by the bucket rolling over rather than by a timer. It is
the pull-side analogue of `StateStore`, and the same principle as `RenderCache`: keyed by what the
render *read*.

### Downsampling stays server-side

ECharts has `sampling: 'lttb'`, but that decides how many points it *draws*, not how many cross the
wire. The server still downsamples — LTTB, target ~300 points — because the payload is an HTML
attribute on every patch and every resume, and because it is the one place the work is done once
for every viewer. ECharts' own sampling is the belt on top of those braces.

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

### The card shape

A history card is **structural, with two regions** — the same split as the slider's head-and-rows,
for the same reason:

- `chart` — a **leaf** holding the `<fh-chart option='…'>` host. Cached hard on the series key. Its
  bytes move when the window changes or the bucket rolls, and at no other time; when they do move,
  the ordinary morph carries the new option into the live chart.
- `now` — the live present, as a **signal slot**. Updates every tick without the chart's bytes
  changing at all.

A tick on the current reading must never re-serialise the series. Two regions is what guarantees
that structurally, rather than by anyone remembering to.

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
2. **`SeriesStore` + `HistoryProvider`**, pure core split from the fetch — the downsampler and the
   bucket key are pure and are where the tests go.
3. **`RenderInputs` gains the series component.** The pipeline change, on its own, with the
   architecture doc updated in the same commit.
4. **`<fh-chart>`**: the npm dependency, the `chart` vite entry, and the custom element —
   shadow root, `attributeChangedCallback` → `setOption`, `disconnectedCallback` → `dispose`,
   `ResizeObserver`. Its acceptance test is a smoke suite that patches the tree around a live chart
   and asserts the chart survives and redraws.
5. **Pkl `core/series.pkl` + a shipped `c.historyChart(e)`** leaf emitting the option JSON, with the
   theme's colours folded in.
6. **Window selection** as a bake group over the existing surface machinery.
7. **`moreInfoBody` gains the chart**, and `moreinfo.pkl`'s "neither is here" comment stops being
   true and gets rewritten.
8. **Docs**: terminology (series / window / provider), architecture §6, a note in the `datastar`
   skill that a shadow root is invisible to the morph (measured off the pinned bundle), and an ADR
   for the provider seam — the decision that needs a home readers will find is *why a fetched series
   is a second kind of data and not a widened slot*, with the shadow-root rule as its companion.

---

## 6. Open questions — spike before building

**These need the live instance, which I could not reach.** Everything about HA's API below is from
documentation, not measurement, and this project's standing rule is that the shipped bytes win over
the prose.

1. **Does `history/history_during_period` exist on your HA's WS API**, or is REST
   `/api/history/period` the only route? Verify before phase 1; it decides whether this rides the
   existing socket or needs a REST client.
2. **What does `recorder/statistics_during_period` actually return** for a sensor with a
   `state_class` — and what is the real cutover point where it beats raw history?
3. **Whose identity should read?** The person's token is the correct answer for a permission-scoped
   read, but it means a second connection per user, exactly as issue #198 describes for taps.
   Whether history is worth that cost is a judgement call, not a technical one.
4. **How big is the attribute?** Measure the serialised option JSON for a week of a chatty sensor
   before fixing the downsample target. The ~300 points above is a guess, and the number that
   matters is bytes on every patch and every resume, not points.
5. **Does a window change need a new fetch or a re-slice?** Fetching the widest window once and
   slicing it for narrower ones trades memory for round trips. Probably wrong for 30d, probably
   right for 1h/24h.
6. **What does the `echarts/core` custom build actually weigh** with the four components we need,
   and does a `chart` entry keep `shell`/`overlay`/`sw` self-contained? The assert-self-contained
   plugin only guards the classic entries, so a shared chunk between `chart` and `app` would be
   legal but wasteful — check what rollup emits.
