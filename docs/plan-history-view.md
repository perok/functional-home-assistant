# Plan — history in the more-info view

**Status:** proposal, nothing built. Issue: more-info should show where a reading has *been*, not
only where it is.

`components/moreinfo.pkl` says so itself today: *"HA's own more-info also carries history and
settings; neither is here."* This is the design for the first half.

Two questions were asked, and they have very different answers. The charting one turns out to be
nearly settled by constraints this project already has. The retrieval one is the real design work,
because it adds **a second kind of data** to a pipeline that currently has exactly one.

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

## 2. Charting: server-rendered SVG, and why the alternatives lose here

The generic comparison of chart libraries is not very interesting. What decides it is three
constraints this project already has, two of which are written down as traps.

**Datastar morphs the DOM.** A canvas chart keeps its state in a JS object associated with an
element. A morph that touches that element severs the association silently — no error, a dead
chart.

**`data-ignore-morph` is a trap, not the answer.** The `datastar` skill records the measurement:
clause E of the guard is *one-sided and unconditional*, so every element **inside** a marked
subtree is refused as a patch target forever. Marking a chart host to protect it also kills every
live update under it. It is a "this DOM is client-owned, server keep out" tool, not a "skip this
during someone else's morph" tool.

**Offline on a LAN is a feature.** `AssetCache` exists to vendor every CDN asset locally so a
dashboard survives an internet outage. Any library we add is one more thing to vendor, and its
sub-resources to rewrite.

Against those:

| Option | Morph fit | JS weight | Verdict |
|---|---|---|---|
| **Server-rendered inline SVG** | perfect — it *is* bytes | 0 | **Ship this** |
| uPlot | needs isolation | ~45 KB | the escape hatch, not the default |
| Chart.js | needs isolation | ~200 KB | no — weight, for one popup |
| ECharts / Plotly | needs isolation | 300 KB–1 MB | no |
| Observable Plot / D3 | client-built SVG | ~100 KB+ | no — we can emit SVG ourselves |
| Frappe / Chartist | client-built SVG | ~30 KB | no — buys little over server SVG |

**Server-rendered SVG makes a chart an ordinary leaf card.** It patches, caches, digests, resumes
and survives a morph, with no new mechanism and nothing to vendor. It also satisfies ADR 0024's
stated aspiration — *the DOM we send should be as usable as possible without JS* — which a canvas
chart cannot.

Two objections, both answerable:

- *"Thousands of points is a lot of HTML."* You must not ship thousands of points to a phone
  regardless. Downsample server-side (LTTB keeps visual shape far better than decimation), target
  ~300 points, and the path data is a couple of KB. This is work you want done anyway, and the
  server is the only place it can be done once for every viewer.
- *"No tooltips or zoom."* Zoom is a **window change**, which is a selection and therefore a
  server round trip we want anyway — the server holds the data and can re-downsample for the new
  span, which client-side zoom into pre-downsampled points cannot. A hover readout is a `<title>`
  element or a CSS-only band; richer interaction is what the escape hatch is for.

### The escape hatch, and the rule that makes it safe

A third party wanting uPlot-class interaction needs a client-owned region. The rule cannot be
`data-ignore-morph`, per above. It is:

> **The server renders the host element once and never patches it again. Data reaches the chart as
> signals only.**

That binding already exists and is exactly this shape — `SignalBind.asHandler`: a value carried as
a signal that nothing paints, read by an expression. Its doc already describes the case ("a value
that is a function of live state but that only a click ever consumes"). A client-owned chart is the
same trade with a different consumer.

So the escape hatch needs **no new mechanism either** — it needs a documented card shape and a test
proving the host's digest never moves.

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

- `chart` — a **leaf** holding the SVG. Cached hard on the series key. Repaints when the window
  changes or the bucket rolls, and at no other time.
- `now` — the live present, as a **signal slot**. Updates every tick without the chart re-rendering
  at all.

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
2. **`SeriesStore` + `HistoryProvider`**, pure core split from the fetch — the downsampler and the
   bucket key are pure and are where the tests go.
3. **`RenderInputs` gains the series component.** The pipeline change, on its own, with the
   architecture doc updated in the same commit.
4. **Pkl `core/series.pkl` + a shipped `c.historyChart(e)`** leaf emitting SVG.
5. **Window selection** as a bake group over the existing surface machinery.
6. **`moreInfoBody` gains the chart**, and `moreinfo.pkl`'s "neither is here" comment stops being
   true and gets rewritten.
7. **Docs**: terminology (series / window / provider), architecture §6, and an ADR for the provider
   seam — the decision that needs a home readers will find is *why a fetched series is a second
   kind of data and not a widened slot*.

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
4. **How many points is too many?** Measure the SVG path size for a week of a chatty sensor before
   picking the downsample target. The 300 above is a guess.
5. **Does a window change need a new fetch or a re-slice?** Fetching the widest window once and
   slicing it for narrower ones trades memory for round trips. Probably wrong for 30d, probably
   right for 1h/24h.
