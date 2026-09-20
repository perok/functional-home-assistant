# Plan — history in the more-info view

**Status:** phases 1–7 built. A provider now fetches and the slot's `transform` decides what its
answer becomes; every render path resolves what it reads. What is left is the DOCS phase, and then
this file is deleted. Interactive window selection has **moved to issue #209**: it is that issue's
node-variable mechanism with a query slot as one more reader, not a thing this plan builds. Issue:
more-info should show where a reading has *been*, not only where it is.

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

Four words for `docs/terminology.md`, chosen to not collide with what is there:

- **Series** — a time-ordered set of readings for one entity over one window. Not state: fetched,
  parameterised, and never in the `StateStore`.
- **Window** — the span and resolution a series covers (`last 24h at 5 min`). A viewer
  **selection**, in the sense the codebase already uses: it belongs with bake index and open set,
  not with entity state.
- **Provider** — the named thing that answers a query, with DATA. `history` is the only one, and
  which providers exist is a closed sum and a `match` rather than a registry: adding one is a case,
  a member of a Pkl union and a resolver arm, all three of which a typechecker points at.
- **Query slot** — a slot whose value comes from a provider rather than from live state. What that
  value BECOMES is the slot's `transform` (a **stage**: a chart, or passthrough), which is the one
  field both slot shapes carry. Distinct from `query.pkl`'s `q.` surface, which is a build-time
  filter over CANDIDATES and reaches no network: this one is a runtime read, and only a component
  author ever writes one.

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

## 3. Retrieval: the query slot

### A slot, but not a state slot

A chart is a value a card puts in a hole, so it IS a slot. What it is not is a *state* slot: its
value does not come from the `StateStore` and is not a transform, and — the part that decides the
shape — **it is markup, where every other slot value is an escaped scalar**.

The first attempt added `series: Option[String]` beside `transform`/`reads`/`signal` on the one
`SlotSource`. That needed four guards to be safe, which is the tell that the shape was wrong:

- `series` + `reads = live` puts the entity in `liveEntities`, so a sensor moving every second
  re-fetches its own history every second — correct output, silent cost;
- `series` + `reads = once` freezes the first chart in a process-wide memo keyed by entity;
- `series` + `signal` puts kilobytes of SVG into every action POST and every SSE reconnect;
- and the `once` memo needed an explicit bypass in `buildPlan` on top of the validation.

Four rejections of combinations that should not have been spellable. `reads` answers *when is this
re-read and does it wake the node*; a query answers *where does this value come from*. Two axes,
and folding them fakes one with the other.

### Two shapes, one wire

The wire keeps ONE `SlotSource`, gaining `query: Option[SlotQuery]`:

```scala
case class SlotQuery(provider: String, params: Map[String, String])
```

A discriminated sum on the wire was the obvious alternative and is worse: circe's
`withDiscriminator` stamps a `"type"` tag onto EVERY slot, churning every byte-identity snapshot in
`PklBuildSuite` for a distinction only the renderer cares about. `SlotSource`'s decoder already
carries one special case (a bare string is a literal); this adds no second one.

The split happens at **validation**, which is where this codebase already puts proofs:

```scala
// what every slot-classification site matches on
enum SlotShape:
  case State(source: SlotSource)
  case Query(query: SlotQuery)
```

Three consequences, and the first is the whole point:

- the four guards **delete**. Every site that asks what a slot is matches on `SlotShape`, and the
  `Query` arm has no `reads` and no `signal` to read — so the combinations are unreachable rather
  than rejected. (`transform` is the one field both shapes have, and they read different arms of
  it — see "What a query answers with" below;)
- `fh.view.model` never imports `fh.view.history`. The model does not know what a window is —
  `Queries.parse` turns `params` into a typed `QueryRequest` during `validate`, and a bad window is
  a build error naming the four that exist;
- a second provider is a case in a closed sum — `QueryRequest`, `Queries.parse`, `QueryResolver`,
  and the union in `core/slot.pkl` — not a registration. There is no plugin story to pay for, and
  one `match` says in code what providers exist where a name→instance map says only what somebody
  remembered to put in it.

`reads` is INERT on a query slot, which raises the question of what the wire should say. It says
`onRender`, derived by the Pkl default and checked at build time — not because anything acts on it,
but because `"reads": "live"` beside a query is a lie the document tells whoever reads it, a
third-party tool included.

### Identity still rides the request

`ServiceCalls` is the precedent for the seam because **identity is the question**: HA attributes a
call to whoever owns the connection, so the one shared socket makes every tap the add-on's own.
Recorder data is permission-scoped the same way, so a provider derives its identity from the
`Request` once and carries it — which is why `QueryIdentity` is already the first component of the
cache key, before any per-user provider exists. A cache key that omits identity is a permission
leak rather than a performance bug, and retrofitting one is how that leak gets written.

### The authoring API is a slot builder, for component authors only

`c.historyChart(e)` is the wrong thing to describe this by: that is `components.pkl`, the
DASHBOARD-author tier (ADR 0015), and a shipped card is a separate deliverable. The query API is a
slot builder and belongs in `core/slot.pkl`, beside `labelSlot` / `valueSlot` / `secondarySlot`:

```pkl
/// A slot whose value is a FRAGMENT from a provider, not a transform over state.
const function querySlot(provider: String, params: Mapping<String, String>): Slot

/// The history provider's slot: one entity's readings over one window, drawn.
const function chartSlot(entity: hass.Entity, window: Window): Slot
```

Putting them in `core/slot.pkl` rather than a new module also removes a naming collision: a
`core/query.pkl` sitting next to `query.pkl` would read as the same feature, and it is not one.

### The escaping trap becomes a build error

A query slot's value is markup, so its hole must be the raw `{{{chart}}}`. Written `{{chart}}` the
page displays `&lt;svg …` as text — a failure with no error anywhere, and the first thing a chart
card author gets wrong. `Templates` already inspects the parsed template AST (that is how it finds
`{{#region}}` bodies that are exactly `{{{html}}}`), so **requiring the raw hole for a query slot
is a build-time check**, not a convention.

### What a query answers with: the transform decides

Nothing built. Written down because it is the first thing a third-party card author will ask for,
and because the answer decides whether `Fragment` is the right shape today — it is, and this says
why.

A query answers with **JSON**. What reaches the hole is decided by the slot's `transform` — the
field that already answers "how does this value become what the card puts in the hole" on the other
shape. A query slot does not get a second field for the same question, and `chartSlot` sets the
transform rather than the provider deciding to draw.

`transform` is ONE wire fact with two forms today, and the query shape adds a third plus an
absence:

```scala
transform: String | Transform.Simple | Transform.Stage | Null
```

- `null` — **passthrough**. The provider's JSON, escaped into the hole. This is the third-party
  contract: whoever wants `[[t, v], …]` for their own chart library asks for no transform at all.
- `Transform.Stage.Chart(spec)` — the built-in renderer: series in, SVG out. What `chartSlot` sets.
- a CEL string / `Transform.Simple` — belong to `SlotShape.State` and stay there.

Which arms are legal where is the two-shape split doing the job it already does: `State` takes the
string/`Simple` pair, `Query` takes `Stage` or nothing.

This falsifies half a claim the code currently makes. `SlotShape`'s doc says "`reads`, `transform`
and `signal` belong to `SlotShape.State` alone", and after phase 6 `transform` is the one field
both shapes have — they read different arms of it. That sentence is part of the change.

**Where a stage RUNS, which is the load-bearing part.** Drawing is `IO` — a WebSocket fetch and a
JavaScript isolate — and a render is a synchronous walk building a string. That constraint is why
the provider draws today, and it does not go away by moving the decision: it says only that a stage
cannot run *during* the walk.

It runs in the same place the fetch already does. `Fragments.resolve` is the pre-walk snapshot, it
is `IO`, and it already knows every query a render reads; it learns the STAGE beside each one and
answers with finished bytes, exactly as now. So the walk is untouched — it still drops a string in a
hole — and three things fall out of the key widening from `SlotQuery` to `(SlotQuery, Stage)`:

- **the fetch/draw split stops being the provider's private business and becomes structural.** One
  fetch per query, one drawing per pair: two widths of one sensor-window are deduplicated at
  different levels because they are different keys, rather than because `HistoryProvider` holds two
  caches internally and a test asserts it;
- **`ChartStyle` leaves the query**, which the code has already written down without being able to
  act on it: `HistoryQuery.parse`'s own doc says "`entity` and `window` are the question; the rest
  is how it is drawn". The inversion is exactly moving "the rest" across the seam. `width`,
  `height` and `unit` stop being wire parameters and become the `Chart` stage's spec,
  `QueryRequest.History` becomes `(entityId, window)`, `parse` loses its `intParam`, `chartSlot`
  stops threading `unit` through the params, and `QueryRenderInputsSuite`'s fixture — which
  currently distinguishes its two queries by `width` — needs a different second axis;
- **the drawing cache needs no expiry function.** `BucketCache` exists because the SERIES has a
  shelf life; a drawing is a deterministic function of an answer, so keying it by `(pair, version)`
  and replacing in place is enough — an entry for a superseded version is dead the moment the
  version moves. Bucket expiry stays where it belongs, on the thing that decides when a version
  moves at all.

`Fragment` still crosses into the walk as `(version, html)`. What changes is one layer down: the
PROVIDER answers `(version, json)`, and the stage is what turns that into the bytes.

**Why `null` rather than a `{kind: "passthrough"}` object.** A bare string in this field means a
CEL expression, so `"passthrough"` would compile as CEL and die on an undeclared identifier. And it
does not belong in `Transform.Simple`, whose documented membership rule is a static, TOTAL lookup —
a chart renderer is neither, and putting it there would falsify the type's own definition. Absence
is what identity actually is, and unlike the other two spellings it is unambiguous.

**The default is derived, exactly like `reads`.** `transform = if (query == null) "state" else null`
in `core/slot.pkl`. `"state"` is the identity for a STATE slot and is meaningless beside a query, so
deriving it keeps the wire truthful on both shapes instead of carrying a default that only reads
correctly on one. Same rule, same reason, same build-time check as the `reads` derivation above.

**The raw-hole check generalises rather than being replaced.** Today: a query slot's hole must be
`{{{x}}}`. With the transform deciding the media it becomes **the last stage decides the hole** — a
renderer emits markup and needs the raw hole; passthrough emits a value and must NOT have one, since
it is an attribute value and wants escaping. One property of the pipeline instead of a rule per
shape.

**Chaining is not foreclosed and constrains nothing now.** Widening one stage to a list is
decoder-local and additive — the wire is Pkl-produced and decode-only, and a single stage still
serialises as a scalar, so no snapshot moves. The eventual shape is `Reshape* Render?`: any number
of `Json => Json` stages then at most one renderer, so `cel` then `chart` is legal and `chart` then
`cel` is a build error rather than a runtime surprise. A CEL stage over rows needs one thing that
does not exist yet: `Cel.scala` declares five ENTITY-shaped variables (`state`, `attr`, `entity_id`,
`domain`, `dashboard_slug`), so a transform over rows has no `rows` to name. Same engine, same
registered extensions (comprehensions, `join`, `filter`, `map` are all already there), second
variable declaration. Not now.

**No sum type anywhere.** `Fragment` stays flat because a passthrough answer is a string in a hole
like any other, and the provider's own `(version, json)` is one shape and not two — the stage is
where the branch lives, which is the whole point of putting it there. The version semantics are
untouched at every level: same bucket, same non-decreasing rule, same `RenderInputs` component,
whichever stage produced the bytes.

Cost, measured: the passthrough JSON is the same downsampled series the SVG was drawn from, so the
series cache serves both and a second consumer costs one serialisation rather than a second fetch.
~300 points is a few KB against the SVG's ~19 KB — size is not the objection anywhere here.

**The signal route is unblocked, and is a separate decision.** A client element that wants the rows
reactively rather than once wants them in a signal, and the objection that looked fatal is not: a
`_`-prefixed name is excluded by the pinned bundle's default `filterSignals.exclude = /(^|\.)_/`, so
`_series` is never serialised into an action POST or an SSE reconnect and ADR 0011's cost does not
apply. First paint needs no template helper either — `Datastar.SignalSeed` already owns the one
`data-signals` attribute per node (present in the document render, absent from the patch), so a
JSON-valued variant of `escapeJsInto` is the whole change.

Three things to settle before that is built, not after:

- **It makes `signal` legal on the query shape too**, which is the rest of the `SlotShape` doc
  claim above. Worth saying out loud, because that field being unreachable is what the split bought
  — a query slot carrying kilobytes into every action POST was one of the four original guards.
- **A JSON null in a signals frame DELETES that signal**, orphaning every binding on it with no
  error anywhere. History data genuinely has `unavailable` gaps, so gaps must be POSITIONAL:
  `[[t, null], …]` is an array element and is fine, `{"t": …, "v": null}` is a deletion.
  `Datastar.signalsJson` already carries the rule.
- **Whatever element holds the payload is severed by the next morph** if it keeps chart state beside
  it — no error, a dead chart. The Appendix has the measured fix (a shadow root, which the pinned
  bundle does not walk), and it is the same fix whether the payload arrives by signal or attribute.
  So passthrough is shippable on its own; a LIVE client chart is not, until that host exists.

The remaining third-party ask is a different picture drawn HERE — a bar chart, a threshold band, an
axis pinned to 0–100. That is the `Chart` stage's spec being extensible, a named closed set of knobs
parsed where `width` is parsed today, and it needs nothing from this section. The trap to avoid is
opening the whole ECharts option object as passthrough JSON: it looks generous and makes ECharts our
public API by accident, so a version bump breaks a dashboard we have never seen. Closed knobs first;
a raw escape hatch only with a real need and the version pin stated as part of it.

### Query parameters the client can set — this is issue #209, not a new mechanism

Nothing built, and the shape is not this plan's to pick. **Issue #209 (node variables) already
designs exactly this**, and an earlier draft of this section designed a second one beside it —
which is the "one mechanism, not two parallel ones" failure, committed in the document rather than
in code.

#209's shape: a node **declares** a variable, a descendant **reads** it by name up the ancestor
chain, an action **writes** it, and a read with no enclosing declarer is a build error naming the
node and the variable. A settable query parameter is that, with a query slot as the reader:

```pkl
// declared where the scope is — the panel, or the chart itself
vars { ["window"] = "24h" }

// read by name; `entity` is a literal and stays one
query { params { ["entity"] = "sensor.t"; ["window"] = varRef("window") } }

// written by the SAME action a tab bar's selection uses
setVar("window", "5h")
```

Three things the unification buys over what this section previously proposed:

- **The mechanical-parameter rule stops needing a flag.** The draft had `settable: Listing<String>`
  opting keys in, so `entity` could not be substituted. With variables the distinction is the
  SPELLING: `["entity"] = "sensor.t"` is a literal and there is nowhere for a write to land;
  `varRef("window")` is a read of a declared variable, and only declared variables are writable. An
  illegal state that cannot be spelled, rather than a permission list somebody maintains — which is
  what the rest of this design does everywhere else. `settable` deletes.
- **One write feeds every reader beneath the declarer.** The draft's
  `queryParam(node, slot, param, value)` named one slot on one node, so a control over three charts
  needed three writes and had to know three node ids. Resolution up the ancestor chain is what the
  actual case wants: "last 5 hours" applies to the panel, and a nested declaration shadows for the
  one chart that should differ.
- **The reference is build-checked**, which is what the maintainer asked for when they said a slot
  reading another slot must carry the reference to it. #209 already specifies it, down to the error
  naming the node and the variable.

**What the query slot CONTRIBUTES to #209**, which its issue does not have yet — worth carrying
over when that plan is written:

- **A third reader kind, and it is the expensive one.** #209 is explicit that a variable change
  must not collapse into "re-render every reader": the tab bar would go from zero bytes a click to
  every button re-rendered. It names the two readings that must both survive — a plain slot
  re-renders its reader, a signal slot pushes a value with no re-render. A QUERY slot is a third: a
  change means **re-resolve, then re-render**, which is a fetch. It is also the only one that meets
  the complete-first-render rule, and it meets it cleanly — a write is an UPDATE, so the new chart
  may land after the press, with ADR 0025's pending/committed keeping the press instant while the
  fetch is in flight.
- **#209's stated caution is already spent, and it should know.** Its "the plan's stated foothold
  is gone" note records that `RenderInputs` is entity versions only — a bake owner holds its
  content in regions, structure is never cached, so no node's bytes mention a selection — and warns
  that reintroducing a selection dimension should re-measure the contention
  `RenderCacheContentionSuite` holds at one render per frame. **Phase 3 already reintroduced it**:
  `RenderInputs` carries a second map today. The predicted cost is in §3 — two sessions on
  divergent parameters have unordered keys, so neither is a straggler and each install evicts the
  other — and it is a PREDICTION read off `RenderCache`'s source, not a measurement.
  `RenderCacheContentionSuite` is where it stops being one.
- **A variable may want a DOMAIN, not just a name.** `window` is a closed union. If a declaration
  can carry its set, a bad write is refused generically and `Window.byName` never sees one; if it
  cannot, `Queries.parse` rejects at the boundary as it does today and the control's error signal
  says so. Either works. This is the use that makes the question concrete.

**Recommendation: take this out of this plan.** It is #209's, it wants #209's plan first, and
nothing else here waits on it — phases 6 and 7 are independent, and they are the two that matter.
Building the parallel mechanism first and unifying afterwards is the expensive order.

**What this costs the two caches, against what is actually built:**

- **The series cache needs nothing added.** It is new like everything else here — `SeriesStore`
  over `BucketCache`, built in phase 2 on this stack — but it is already the right shape for this:
  it sweeps on every cold insert by asking each key when IT stops being current, so live keys are
  bounded by what is in flight inside one bucket, not by how many distinct parameter sets have ever
  been asked for. Two windows are two fetches; one window across twenty sessions is one. A value
  the provider rejects never becomes a key at all.
- **The HTML cache is where it shows, and the bound is deliberate.** `RenderCache` holds ONE
  generation per node, and two sessions on different windows have `RenderInputs` that are
  *unordered* — neither `isAtLeast` the other, which is precisely the rule that stops a chart of the
  wrong span being served. So neither is a straggler and each install replaces the other's: two
  sessions on divergent parameters alternate, costing one render per pull each. Never wrong bytes,
  only no sharing. That is the trade `RenderCache`'s own doc names for its single slot, and its own
  answer applies — if a real deployment shows it mattering, measure before widening the bound.
  Nothing to build now.

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

### The provider owns its caching, and the version IS the policy

The pipeline needs exactly two things back from a provider:

```scala
final case class Answer(version: Long, data: Json)

enum QueryRequest:
  case History(entityId: String, window: Window)

object Queries:
  def parse(q: SlotQuery): Either[String, QueryRequest]   // PURE

final class QueryResolver(history: HistoryProvider):
  def one(id: QueryIdentity, r: QueryRequest, asOf: Instant): IO[Answer]
```

Data, and a number saying **as of when this content became current**. Caching, dedupe and eviction
of the FETCH stay private to the implementation, and that is a decision rather than an omission.
Drawing is not the provider's, and the `style` that used to ride in `QueryRequest.History` is not
either — see "the transform decides" above. A provider answers *what was recorded*; how it is
presented is the slot's.

This is a correction to what is built, not a migration: the whole stack is unmerged, so the
provider's second cache and its `ChartDraw` argument are deleted rather than deprecated. Phase 6
may well be cheaper as a rebase of phases 2 and 4 than as a phase that undoes them — the
maintainer's call, since it is their branch stack.

**A closed sum and a `match`, not a registry.** There is no plugin story to pay for here: a
name→instance map says only what somebody remembered to wire up, where one `match` says in code what
exists. Adding a provider is a case in the enum, a member of the union in `core/slot.pkl`, and an
arm in the resolver — all three of which the compiler or the Pkl typechecker points at.

**Parsing is pure and separate from resolving**, which is what removes the wiring hazard entirely:
checking a chart needs no store, no HA connection and no JavaScript engine, so `Dashboard.validate`
calls `Queries.parse` directly and a bad query is a build error wherever a dashboard is built. Only
`QueryResolver` needs the running machinery, and it is reached at render time, by ordinary
constructor wiring.

**Bucket expiry is a property of append-only-past data, not of queries.** It works for history
because the past is immutable and only the tail grows, so "now, floored to a resolution" is a
legitimate version. A weather forecast breaks it (the FUTURE is what changes), a camera still
breaks it (continuous), a template render breaks it (arbitrary). A logbook would fit — but the
provider is the only thing that knows which class it is in, so a shared cache would have to be
configured by every provider into the shape it needed, which is worse than none.

The payoff is that **fall-through needs no mechanism**: it is what a version does when the content
has no natural shelf life.

- History returns `window.bucketOf(asOf)` — stable for the whole bucket, so every viewer looking
  at "last 24h" shares one fetch, and the entry expires by the bucket rolling rather than by a
  timer. The drawing is shared by the same number, one level up: the stage cache keys on that
  version, so one bucket is one SVG however many viewers, which is what keeps the JavaScript
  context off the hot path.
- A provider with nothing better returns `asOf.toEpochMilli` — different every render, so
  `RenderInputs` never matches, the node never serves from cache, and the provider is called every
  time. Uncached by construction, with no opt-out flag and no special case.

The failure mode of the second is COST, and it is visible — the node re-renders — never staleness.

One contract detail makes it hold: a version must be **non-decreasing**, because
`RenderInputs.isAtLeast` compares with `>=`. "When this became current" always satisfies that; a
content hash would not. A provider that genuinely cannot produce one is the trigger to compare
queries by EQUALITY instead — strictly more discriminating, which is the direction `RenderInputs`'
own doc says to err in.

#### The bug this dissolves

A shared `BucketCache[K, V]` was built and is wrong, in a way worth recording because the generic
is what invited it. Its sweep keeps entries whose bucket is `>=` the newly inserted key's — but
keys from different windows expire on different schedules. A 1h read buckets by the minute, a 30d
read by the hour, so inserting a 1h entry at 12:01 evicts a 30d entry bucketed at 12:00 that is
valid for another 59 minutes. **Measured: 3 drawings where 2 is correct**, and with any 1h chart on
the page every coarser chart is evicted about once a minute — which destroys exactly the sharing
the cache exists for.

A key-agnostic `bucketOf: K => Instant` cannot know two keys expire differently, so "newest bucket
wins" looks obviously right. Inside the history provider every key carries its own `Window`, so
"is this entry still current" is answerable per key and the wrong version is awkward to write
rather than natural. The fix is still needed wherever the code lands: `SeriesStore` carries the
same line on the branch below this one.

#### The first render is COMPLETE, and that decides what absence means

The standing rule this project has always held: **the first HTML a browser gets is fully rendered.**
Only UPDATES are deferred. A hole that fills in later is not a slower render, it is a different
product, and nothing here gets to introduce one.

That rule settles a question this section used to get wrong, so it is worth stating what it rules
out. Three things were tangled together as "no fragment", and they are not one state:

- **Not yet arrived.** Stops existing. The render waits for its answers; there is no version of
  this that reaches a browser.
- **Empty.** A sensor added today has no recorded rows. That is a SUCCESSFUL answer whose data is
  empty, and after phase 6 the stage renders it — an empty chart with its axes, a `[]`. It was
  never the pipeline's business, and nothing needs designing for it.
- **Failed.** The provider raised, or the stage did. This is the only one left, and it is an
  ERROR: on the document path it raises `FHError.unavailable` and the page fails to load, which
  is what the repo's terminal-error rule already says to do with "this cannot be served". On the
  UPDATE path it must not — raising there kills the SSE stream — so a background refresh that
  fails keeps the last good bytes, skips the patch, and **says so in the shell's toast**. The
  mechanism exists: `Server.ToastSignal` (`_toast`) already carries HA's own words out of
  `actionRefused`, and a refresh that could not redraw a chart is the same class of fact — the
  page is fine, the operation failed. Silent-and-logged would leave a viewer looking at a chart
  that quietly stopped being current.

**Resolving before the walk is what makes the error path expressible**, and that is the argument
this plan was missing. The document walk's writes ARE the response body (architecture §6a): once it
starts, the status line and the `<head>` are gone. A query resolved beforehand can raise while an
error response is still possible; one resolved lazily could only truncate a page already on the
wire. Pre-resolution reads as a performance choice and is not one.

**But it is not free either, and phase 7 is where it starts being charged for.** The effect type is
not what decides this — a walk that starts every query at once, pushes as far as its dependencies
allow and awaits only at the point of use is a real design, not a strawman, and it would put bytes
on the wire EARLIER. What it costs is the status code: once the `<head>` is out, a failure can only
render as content. So the honest statement of the trade is:

- **resolve, then stream** (today) — a BARRIER: gather every query the render implies, fire them in
  one `parTraverse`, await the batch, walk. What normally makes a barrier wrong is DYNAMIC
  dependencies, where a request is only knowable after an earlier node resolves, so the barrier
  stalls what could have proceeded and then has to gather again. **That case is designed out here**,
  and not by luck: the query set is found by walking the static tree before the render
  (`Renderer.queriesForSurface`), candidate sets carry static candidate lists whose conditions
  evaluate against the state snapshot already in hand, and the one genuinely dynamic case — a
  parameter coming from elsewhere in the tree — is what #209's DECLARED references make static. A
  declared edge can be topologically ordered; an implicit one would have forced discovery mid-walk.
  So there is never a second gather phase, and the barrier costs nothing it is usually charged for.
  It also buys the thing phase 7 needs: **`Fragments` can be TOTAL only because of it** — if
  queries could be discovered mid-walk, no value could carry the proof that every query this render
  reads has an answer, and absence would have to be handled at every read instead of being
  unrepresentable. The one real cost is time to first byte — nothing goes out until the slowest
  query lands;
- **stream, then await lazily**: TTFB untouched and the recorder fetch overlaps the browser's
  subresource loading, at the price of every failure being a drawn "unavailable" rather than a
  status.

Both satisfy the rule above as long as a failure renders as something COMPLETE. The choice between
them rests on a preference — that a timeout should be an error — and not on a constraint, which is
worth knowing before it hardens into one.

**The barrier covers the DRAWING too, not only the fetch.** After phase 6 the stage runs where the
fetch runs, because drawing is `IO` (a Graal context) and the walk is synchronous. So what a render
awaits is N fetches *and* N drawings. Two things follow that nobody has priced:

- **Drawings are SERIALISED, deliberately.** `ChartRenderer` holds one context for the process
  behind a `Mutex` — not because a Graal context is unsafe to share (a pool would fix that) but
  because evaluating ECharts costs ~300 ms per context and a pool pays it per member. Shipped
  numbers: a warm render is **29–32 ms** on the isolate, the first in the process ~190 ms. So a
  page carrying eight distinct charts on a cold bucket is ~240 ms of serialised drawing before the
  first byte — absorbable, and worth knowing it is there.
- **It falsifies a claim `ChartRenderer`'s own doc makes**, which phase 7 must fix in the same
  commit: "render rate is bounded by `SeriesStore` to roughly one per window per bucket, which one
  context absorbs". That was true while charts lived only in popups. A page carries many ENTITIES
  at once, so the bound is one per (entity, window) per bucket. One context still absorbs it; the
  sentence is still wrong.

**On memory, which is the third axis and the least of the three.** A barrier holds every answer for
the render at once, where a lazy walk would hold each only until its node is written — and §6a took
a page's peak from ~500 kB to one node precisely to avoid that shape. But the bytes here are
**shared cache entries**: the series cache holds the rows and the stage cache holds the drawing
whether or not a render is in flight, so `Fragments` holds REFERENCES, not copies, and N concurrent
renders of one page cost one set of bytes rather than N. The document itself still streams.

Where it would be real is an **uncached** provider — one answering `asOf.toEpochMilli`, uncached by
construction — since every render then materialises its own answers and nothing is shared. That is
the trigger to revisit, along with TTFB: **stay with the barrier until one of them bites, then
measure flushing the `<head>` before awaiting.** Not before — it costs the error status, and
neither cost is being paid today.

The concrete consequence to weigh when phase 7 lands: §6a names time to first byte as one of three
targets the streamed walk was built to serve (the browser fetching stylesheets and module scripts
while the body is still being walked). **Making the page path resolve gives part of that back on
any page carrying a chart** — the head waits on a recorder query. Nobody has paid that yet only
because the page path resolves nothing at all, which is the defect phase 7 fixes. If it bites, the
cheapest thing to measure first is flushing the `<head>` and then awaiting, which keeps the
subresource overlap and loses only the error status.

**A timeout is an error, not a fallback** — and it is not a number this design has to pick.
Two bounds already exist and between them a hang is not the expected failure:
`HAWSApiLowLevel.pingTimeout` (10 s) means a dead socket surfaces as a failed command rather than
a wait, and `HaFeed.SeedTimeout` (60 s) is the existing precedent for the SHAPE — a boundary wait
that raises `FHError` rather than degrading. What is genuinely slow here is slow, not hung: a 30 d
read on a Pi with a large recorder, paid by the first viewer in a bucket and by nobody after.

One thing the bound may NOT be: **ember's idle timeout.** It fires against a response that is
already streaming, so it truncates a half-written document — which is the exact outcome the
complete-first-render rule exists to forbid. A bound here has to sit on the pre-walk resolution.

#### `Fragments.none` is a mistake, and it is the mechanism of the violation

`Fragments.resolve` is called from exactly ONE place — the surface swap in `Server`. Nine other
render entry points take `fragments: Fragments = Fragments.none` as a DEFAULT ARGUMENT. So the
page path renders a chart's hole empty and has no way to fill it, and it compiles clean: not
typing anything gets you the incomplete render.

The empty set is not wrong as a VALUE — the signals path provably reads no query, and it is that
path's honest answer. It is wrong as a default, and `html(query): Option[String]` is wrong with it.

The fix is the repo's own parse-don't-validate rule applied one level down: **`Fragments` is TOTAL
over the queries this render reads**, constructible only by resolving all of them, so the renderer
indexes it instead of branching on an absence that can no longer occur. A path that reads a query
and was handed no answers becomes unrepresentable rather than quiet.

What that deletes, which is the tell that it is the right shape: the `forQueries` rule that a
missing query is a distinct key from any version it could have — and its test — exists ONLY
because `Fragments.none` can reach a node that reads a query. Remove the default and the rule it
needed goes with it. The failing-query test keeps its point and changes its layer: after phase 6 it
reaches failure through a drawing that throws, which is a STAGE failure, and what it should assert
is the error, not a hole.

### Downsampling stays server-side

ECharts' `sampling: 'lttb'` decides how many points it *draws*. We downsample before that anyway —
LTTB, target ~300 points — because the fetch itself should not carry thousands of rows through the
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

Both lists are built from STATE slots, and a query slot is not one. So:

**A history chart is not a candidate on a state tick, and it is not arranged for — it is
structural.** A query slot contributes to neither list because it has no entity read to contribute,
so a reading arriving cannot wake it. No change to candidate selection is needed. This is the
single most important consequence, and the two-shape split is what makes it a property of the type
rather than of a `reads` value someone could set differently.

What *does* need a change is the cache key. `RenderInputs` is currently only per-entity content
versions; a chart's bytes depend on what its query returned instead. So:

> **`RenderInputs` gains a second component: the queries the node read, each at its provider's
> version.** One field, and it is the only pipeline change this design requires.

The field is a `Long` per query and says nothing about where the number comes from — only that it
is non-decreasing. For `history` it is the bucket; for a provider with nothing better it is the
fetch time, which makes that provider uncached by construction. See §3.

Phase 6 widens the key it is a map of from `SlotQuery` to `(SlotQuery, Stage)` — two widths of one
sensor-window are one fetch and two drawings, so they are one version and two keys. The component's
semantics do not move with it: still a `Long`, still non-decreasing, still the provider's number,
since a stage is a deterministic function of the answer and has no version of its own.

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

- `chart` — a **leaf** whose one slot is the query. Cached hard on the query's version.
- `now` — the live present, as a **signal slot**. Updates every tick, costing one entry in a
  signals frame, while the chart's bytes stand still.

The two must be separate NODES, not two slots on one: only a node is a patch target with its own
`RenderInputs`, so a chart that shared a node with `now` would re-send ~19 KB on every reading.

A tick on the current reading must never repaint the chart. Two regions is what guarantees that
structurally, rather than by anyone remembering to.

### Window selection is a node variable — the same fact as a tab selection

"Changing the axis" is a control writing a declared variable that the chart's query reads. Not a
new capability: it is issue #209, and a window and a tab selection turn out to be the SAME fact —
a per-connection named value a control writes and a descendant consumes. See §3.

Two earlier answers here were both wrong, and in opposite directions. **A bake group** was this
plan's first answer, and a bake swaps STRUCTURE — it would have needed a genuinely new capability,
that baking a selection can trigger a fetch, which it cannot, surfaces being rendered from state
alone. **A per-slot settable parameter** was the second, and it was a second mechanism for what
#209 already designs. What is actually true is narrower than either: a window is not a structure
and not a chart-specific knob, it is a variable, and a chart is one more kind of reader.

The closed `Window` union does the job it always did wherever the write is checked — a value
outside it is refused, and one that somehow is not fails `Queries.parse`. Interaction does not
widen what the server accepts.

### Fetching is bounded by what is being rendered, not by the surface

More-info is a **triggered surface**, rendered only while open, so a chart nobody has opened is
never fetched and closing the popup ends the obligation. That is a genuine bound and it is free —
but it is a property of WHERE the first chart was put, not a design, and the plan previously leaned
on it as though it were one.

A chart on a page is bounded by the page being open and nothing else, so **a dashboard with N
charts blocks its first paint on N recorder queries.** That is the one visible price of the
complete-first-render rule, and it is the price rather than a defect: the alternative is a page
that paints with holes in it. What softens it is sharing, not laziness — every viewer inside a
bucket after the first pays nothing, because the series cache has already answered.

So the laziness worth keeping is per-QUERY, not per-surface: a stage the page does not render is
not run, and a window nobody is looking at is not fetched. Being cheap because the popup is shut
is luck, and it runs out the first time a chart is placed on a dashboard.

---

## 5. Phases

Each is independently mergeable and independently useful.

1. **`ha-api`**: the two WS commands and their decoders. Testable against `FakeHomeAssistant`, no
   live HA.
2. **The history provider's guts**, pure core split from the fetch — the downsampler and the
   bucket key are pure and are where the tests go. It holds ONE store, the fetch's — phase 6 moves
   the drawing and its cache out to the stage, where the fetch/draw split is structural rather than
   a private implementation choice a test has to pin down. Source selection is decided by a learned
   `Retention` rather than a threshold: a window it already covers costs one call, an unproven one
   asks both sources in parallel and keeps whichever covers more time, and the history half is what
   teaches it. Retention is a lower bound taken as the MAXIMUM across entities, because per entity
   a daily purge and a day-old sensor give the same answer.
3. **The query slot, and `RenderInputs` gains its component.** The pipeline change, on its own,
   with the architecture doc updated in the same commit: `SlotQuery` on the wire, the two-shape
   `SlotShape` split matched at every classification site, `Queries.parse` turning `params` into a
   `QueryRequest`, and the
   `Templates` check that a query slot's hole is the raw one. The prediction held — a chart is not
   a candidate on a state tick, and with the split it is not even spellable otherwise. What the
   phase does NOT include is the live half: nothing fills a version on the pull path yet, so a
   rolled bucket does not wake its node; that is phase 7's, which is where the pull path starts
   resolving queries at all.
4. **The chart renderer**: the vendored ECharts bundle as a resource and a host behind a plain
   `IO[String]` — series in, SVG out. The engine is `JsIsolate`, already on the classpath and
   already a process-lifetime `Resource`, so this phase adds the `Source` and the context handling
   and nothing else. Its tests are ordinary: no browser, no HA, just a function.
5. **The authoring surface, in two tiers, and the popup that uses it.** `querySlot` / `chartSlot`
   in `core/slot.pkl` beside the other slot builders; `c.historyChart(e)` in `components.pkl` on
   top. `moreInfoBody` composes one **conditionally on `SensorEntity.isNumeric`**, which the dump
   already knows — so "where a chart makes sense" is answered at BUILD time and a `binary_sensor`,
   a light or an `enum` sensor composes no chart rather than composing one that fetches nothing.
   Theme colour needs nothing folded in after all: inline SVG inherits the page's custom
   properties, so `var(--fh-accent)` reaches the stroke verbatim (measured — zrender passes it
   through rather than normalising it, which a library that parsed colours would not).

   The surface path is wired with it, because a chart nobody can see is not a phase: a triggered
   surface's queries are resolved before it renders (`Server.swapHost`), which is exactly where
   more-info is filled. The engine is LAZY (`Resource#memoizedAcquire`), so an instance whose
   dashboards hold no chart pays neither the ECharts evaluation nor the isolate's heap.
6. ~~**The transform decides the answer**~~ — DONE. The inversion, and the largest of them.
   `transform` gains its `Stage` arm and its `null`; the Pkl default derives it from the query;
   `chartSlot` sets `Chart` where the provider used to draw unconditionally; `QueryResolver`
   answers `(version, json)`; `ChartStyle` and `width` leave the query for the stage's spec;
   `Fragments` keys by `(query, stage)` and runs the stage where it already runs the fetch; the
   drawing cache moves out of `HistoryProvider` and loses its expiry function, keying on the
   version instead; and the raw-hole check generalises to "the last stage decides the hole".
   Passthrough — a third party's `[[t, v], …]` — then falls out rather than being added.
   Since none of the stack is merged this is a correction to phases 2 and 4 rather than a layer on
   top of them, and folding it into a rebase of those two is likely cheaper than landing it as its
   own phase.
7. ~~**Every render path resolves what it reads.**~~ DONE. Unfinished work in this stack's own
   commit rather than a bug against main: `Fragments` arrived in `72a345fb`, which is on this branch and in no PR yet,
   so this is a correction to make before the branch is opened rather than something to track.
   `Fragments.resolve` has one caller (the surface swap); nine other entry points default to
   `Fragments.none`, so a chart on a page, and an `Activation.State` bake member on the shared
   per-slug pass, would ship an incomplete first paint. The phase makes `Fragments` total over the
   queries a render reads, removes the default so a path that reads a query cannot be handed
   nothing, drops `html`'s `Option`, deletes the absent-is-not-zero rule that only existed to
   survive the default, and makes a bound raise rather than degrade. It also rewrites
   `ChartRenderer`'s "roughly one per window per bucket" claim, which this phase falsifies by
   putting many entities' charts on one page.

   Like phase 6, this is cheapest folded back into the commit that introduced the shape. Three of
   the four remaining items are now corrections to unmerged commits rather than layers on them,
   which is a fact about the stack worth acting on: the shape to land is the one the design
   arrived at, not the one it passed through.
8. ~~**Settable query parameters, and the controls that write them.**~~ **Moved to issue #209** —
   it is that issue's mechanism with a query slot as one more reader, and an earlier draft here
   designed a second one beside it. §3 records what the query slot contributes to #209's plan: a
   third reader kind whose change costs a fetch, the fact that phase 3 has already spent #209's
   caution about reintroducing a selection dimension into `RenderInputs`, and the question of
   whether a variable declares a DOMAIN. Nothing in this plan waits on it.
9. **Docs**: terminology (series / window / provider / query slot), architecture §6, and an ADR for
   the query seam — the decision that needs a home readers will find is *why a fetched fragment is
   a second SHAPE of slot rather than a state slot with extra fields*, with "a chart is bytes, and
   the JS that makes them runs here" as its companion.

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
5. ~~**How long may a provider hold up a render?**~~ *Answered, and the answer is "do not add
   one yet".* The SHAPE is settled by the complete-first-render rule (§3): a bound raises rather
   than degrading. Whether a bound is NEEDED is answered by what already exists — a 10 s WS ping
   timeout means a dead socket fails a command instead of hanging, so the remaining case is slow
   rather than stuck, and a number invented without a real provider being slow would be a guess
   with a failure mode. `HaFeed.SeedTimeout` is the pattern to copy if one is ever wanted.

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
