# ADR 0031 — A fetch is a second SHAPE of slot, and what its answer becomes is the transform's

A slot whose value comes from a provider is a different SHAPE from a slot that reads state —
not a state slot with extra fields. And a provider FETCHES: how its answer becomes what the
card puts in the hole is the slot's `transform`, not the provider's business.

## Context

Every slot value was a transform over one entity's current state, held in the `StateStore` and
free to read. History is not: it lives in HA's recorder, takes a round trip, is parameterised by
entity and window, and — the part that decides everything else — is **immutable below its newest
point**.

Two decisions came out of fitting that into the pipeline. They are here together because the
second only became visible after the first shipped, and reading either alone gives a misleading
picture of why the types look as they do.

## Decision 1 — the wire keeps one `SlotSource`; validation produces two shapes

`SlotSource` gains `fetch: Option[FetchTemplate]`. `Dashboard.validate` turns that into

```scala
enum SlotShape:
  case State(slot: BoundSlot)
  case Fetch(ask: SlotAsk)
```

and every site that classifies a slot matches on it.

### Why not a discriminated sum on the wire

The obvious alternative, and worse: circe's `withDiscriminator` stamps a `"type"` tag onto
**every** slot and churns every byte-identity snapshot in `PklBuildSuite`, for a distinction only
the runtime cares about. `SlotSource`'s decoder already carries one special case (a bare string is
a literal); this adds no second one.

### Why a shape and not fields

The first attempt hung `series: Option[String]` beside `transform`/`reads`/`signal`. It needed
**four guards**, which is the tell:

- `series` + `reads = live` puts the entity in `liveEntities`, so a sensor moving every second
  re-fetches its own history every second — correct output, silent cost;
- `series` + `reads = once` freezes the first chart in a process-wide memo keyed by entity;
- `series` + `signal` puts kilobytes of SVG into every action POST and every SSE reconnect;
- and the `once` memo needed an explicit bypass in `buildPlan` on top of the validation.

Four rejections of combinations that should not have been spellable. **`reads` answers *when is
this re-read, and does reading it wake the node*; a fetch answers *where does this value come
from*.** Two axes, and folding them fakes one with the other.

With the split, the guards do not get fixed — they **delete**. A `SlotShape.Fetch` has no `reads`
and no `signal` to read, so the combinations are unreachable rather than rejected. The property
that matters most falls out of the same fact: both entity lists are built from STATE slots, so a
fetch slot has no entity to contribute and **a chart is not a candidate on a state tick**. Nothing
arranges that; it is a property of the type.

### What is checked rather than made unrepresentable

`reads` is INERT on a fetch slot, which raises what the wire should SAY. It says `onRender`,
derived by the Pkl default and checked at build time — not because anything acts on it, but
because `"reads": "live"` beside a fetch is a lie the document tells whoever reads it, a
third-party tool included.

## Decision 2 — the provider fetches; the transform draws

The first build had the provider answer with markup: `Fragment(version, html)`, with the chart
style riding in the fetch's params. That put the presentation of a fetched value inside the thing
that fetches it.

The division was already written down without the types being able to act on it: *`entity` and
`window` are the question; the rest is how it is drawn.*

So: `FetchResolver.answer` returns `Answer(version, json)`, `FetchRequest.History` is
`(entityId, window)`, and `width`/`height`/`unit` moved to the slot's `transform`.

### Why the same field, and not a new one

`transform` already answers *how does this value become what the card puts in the hole*. What
differs between the shapes is the SUBJECT: a CEL string and a `Transform.Simple` read an ENTITY, a
`Transform.Stage` reads a provider's answer. A fetch slot does not get a second field for the same
question, and `SlotShape` keeps each arm on the shape that can use it.

Two spellings were rejected:

- **a bare string** (`transform = "passthrough"`) — a bare string in that field means a CEL
  expression, so it would compile as one and die on an undeclared identifier;
- **a member of `Transform.Simple`** — that type's documented membership rule is "a static lookup
  and TOTAL", evaluated without the engine. A chart renderer is neither, and putting it there
  would falsify the rule its own scaladoc states.

### Why the default is derived

`transform` defaults to `"state"`, which is the identity for a STATE slot and means nothing beside
a fetch. So the Pkl default is derived from the shape — `if (fetch == null) "state" else
passthrough` — exactly as `reads` is, and for the same reason: a fixed default makes the wire lie
on one of the two shapes. The evidence is in the snapshots this change regenerated, where a fetch
slot had been carrying `"transform": "state"`, which was never true of it.

### What the split bought

None of this was designed separately; all of it fell out.

- **Passthrough is the third-party contract, and it is an absence rather than a feature.** No
  transform means the provider's JSON — `{"points": [[epochMillis, value], …]}` — which is what
  someone using their own chart library reads against, so the shape is the chart libraries':
  `[x, y]` pairs, ECharts' own data form, where a null value would be a break in the line. How a
  card gets the payload to its script is the card's business, not the contract's; one that lifts
  it into a Datastar signal must know that a null object FIELD deletes a signal, which an array
  entry never is.
- **The fetch/draw split is structural.** `FetchSnapshot.resolve` deduplicates one call per FETCH and
  one drawing per `(fetch, stage)` — `SlotRead` is that pair, and it is what `RenderInputs`
  carries. Two cards charting one sensor over one window at different sizes cost one fetch and two
  drawings *because the keys say so*.
- **The staged-value cache needs no expiry, and no provider.** A series has a shelf life — it
  stops being current when its bucket rolls, which is what decides when a version moves. A staged
  value has none: it is a function of an answer, and a version names one, so `FetchResolver` keys
  every stage's output by `(question, stage, version)` and replaces in place. That is the half of
  the series cache's eviction it does not need (both are a `SharedCache`, told what retires an
  entry). The ANSWER cache stays the provider's, because when an answer moves is the provider's
  policy — a bucket, a push, never — and the runtime knows a version only as "same data, never
  goes back".
- **The raw-hole rule generalises.** It was "a fetch slot's value is markup, so its hole must be
  `{{{x}}}`". It is now **the last stage decides the hole**: a drawing needs the raw one, and
  passthrough must NOT have one, because its value is an attribute payload and wants escaping.

## Decision 3 — a fetch is also a CEL input

A fetch can be read by name in a STATE slot's CEL, as an expression value (ADR 0034): the slot
keeps its entity, its wake and its signal, and its expression gets the answer beside `state`. A
fetch slot is the case with no entity, its answer staged into the hole; a value fetch is the case
with one, its answer read as data. One fetch, one provider and one cache serve both. A value fetch
is a `SlotAsk` with the passthrough stage, counted among the node's fetches, so the pre-read and
the render key need nothing new.

History answers every reading, and the chart stage thins to about 300 before it draws. Thinning
keeps the shape of a line and drops the single reading a computation wants (a day of minute rows
thinned to 300 kept 100 where a countdown started at 101), and how many points a drawing needs is
the drawing's: so a chart and a computation over one window are one fetch. Thinning that is
cached at the provider, for a data card's payload, would be a stage of its own when one needs it.

## Decision 4 — what history reads is the author's, typed on the wire

A history fetch reads a **window**: `Raw(span)`, the recorder's rows, or `Statistics(span, period,
fallback)`, HA's long-term statistics. The author picks (`c.window.raw(1.h)`,
`c.window.statistics(7.d, "hour")`); the server picks nothing. The four windows a chart used to be
limited to (`1h`/`24h`/`7d`/`30d`) are now only more-info's presets (`c.window.presets`). HA takes
any span, and statistics at `5minute` up to `year`.

- **Why statistics are worth asking for.** A power meter's 24 h of raw rows was 23,691 rows and
  847 KB in 439 ms over WS, all to draw 300 points; its 5-minute statistics were 287 rows and 27 KB
  in 43 ms. A popup waits for its fetches, so that read was most of a cold popup open.
- **`fallback` is off unless asked for.** Only an entity with a `state_class` has statistics, so
  without it such a chart draws nothing. More-info turns it on, because it shows any sensor.
- **No raw tail.** The newest bucket ends minutes before now (1–5 minutes at `5minute`, measured),
  and `Series.heldUntil` carries it there. HA's own more-info chart draws statistics the same way.
  Its cost: a sudden change appears at the next bucket, not at once.
- **The bucket is derived, not a knob:** the period for statistics, and a 720th of the span (at
  least a minute) for raw rows, which gives the presets 1 min, 5 min, 1 h and 1 h. Cache entries
  are the distinct windows on screen, and only an author adds one.
- **Typed on the wire, decoded straight into the request.** The fetch's `entity` and `window` are
  fields, decoded by circe into `FetchTemplate`, as the fast expressions decode
  (ADR 0028 point 3). The string `params` map parsed by its provider afterwards was dropped: a
  window would have needed a string grammar (`"7d@hour"`) parsed back out again.
- **A node variable picks a window by key.** A variable lives in a URL and a signal, so it holds a
  string; a `Chosen` window carries the table (`{"kind": "Chosen", "by": {"var": "window"},
  "options": {…}}`), and `Renderer.refusals` refuses a key the table lacks. Simple's `Match` was
  considered and rejected: it maps a live entity's state, which nobody controls, so it must be
  total, and its `otherwise` would hide a bad key instead of refusing it.
- **A period no shorter than the span is a build error.** HA answers `hour` over one hour with
  nothing (measured), so the chart would be blank forever.

## Consequences

- **A provider is a case in a closed sum**, not a registration: `FetchRequest`, `FetchTemplate`'s
  decoder and `FetchResolver`, all pointed at by the compiler. Same for a stage. There is no plugin story
  to pay for, and one `match` says in code what exists where a name→instance map says only what
  somebody remembered.
- **The Pkl core names no provider and no drawing stage.** `core/slot.pkl`'s `Fetch` holds only
  a `provider` String and `core/stage.pkl` knows only `passthrough`; the provider's question
  (`HistoryFetch`), its windows (`components/window.pkl`) and the chart stage are typed in the
  components that offer them. An unknown provider is a decode failure naming the known ones.
- **A provider is typed in the model, as a stage is.** `FetchTemplate` is history's question, and
  a second provider makes it a sum, as `FetchRequest` is. `Stage.Chart` carries a `ChartStyle`,
  typed at both ends (`components/history.pkl`'s `ChartParams`). Each is decoded with the wire, so
  no value is parsed twice.
- **Decoding and validating are pure and separate from resolving**, which removes the wiring
  hazard entirely. Checking a chart needs no store, no HA connection and no JavaScript engine, so a
  bad fetch or a bad chart size fails wherever a dashboard is built (both as the wire is decoded;
  a window's period against its span, and a chosen window's declared key, in
  `Dashboard.validate`), rather than wherever somebody remembered to pass a provider in.
- **Identity is the first component of every cache key**, before any per-user provider exists.
  Recorder data is permission-scoped, so a key that omits it is a permission leak rather than a
  performance bug — and retrofitting one is how that leak gets written. Today every read is the
  add-on's own identity and everyone shares. Whether it should be the person's token is open, and
  it is the same question issue #198 asked of taps — "what happens if we use the access_token we
  are given on login?" — which was closed without being answered in the code: every call is still
  the add-on's. For reads the cost is a second HA connection per user, and whether history is
  worth that is a judgement about cost, not a technical blocker.
- **The render key gained a second dimension**, which issue #209 should know about: its own note
  says `RenderInputs` is entity versions only and that reintroducing a selection dimension should
  re-measure `RenderCacheContentionSuite`. It is back, deliberately. The predicted cost is that
  two sessions holding different reads have *unordered* keys, so neither is a straggler and each
  install evicts the other — read off `RenderCache`'s source, not measured.

## Open questions

- **Nothing wakes a node because a fetch's VERSION moved.** The live path is driven by
  `stateStore.changes`; a bucket rolling is not a state change, so a chart on an open page goes
  stale until something else that node reads happens to move. On a wall tablet — the case this
  frontend exists for — that is indefinitely. The key is already correct: a render at the new
  bucket produces new bytes. Nothing asks for that render.

  **The shape is decided; nothing of it is built.** The PROVIDER rings, because only it knows when
  its answer moves — a bucket rolling, a push arriving, or never — and the runtime knows a version
  only as "same data, never goes back". So a provider gains one member beside `answer`:

  ```scala
  def moved: Stream[IO, Question => Boolean] // the questions whose answer just moved
  ```

  A predicate rather than a list of questions, so history needs no idea who is watching: at a
  window's bucket boundary it emits `{ case (_, History(_, w)) => w == rolled }`, and a node
  nobody can see costs nothing, since a pull renders only what its session can see. It fires AFTER the
  answer has moved — for history, at the boundary by the clock `answer` reads — for the same reason
  the state doorbell rings after the log is written. A static provider's stream is empty. The wake
  is the DATA's, not the drawing's: a passthrough read of the same question wakes too, and no stage
  is involved.

  What the recorder does with it, and the one piece of real work:

  - **It needs a version of its own.** The log, the doorbell and every session cursor count in
    `StateStore` versions, and a bucket rolling mints none, so a session already at the current
    version would never pull. The store mints one — a batch with no entity in it, publishing no
    `StateChange` — and the recorder records the frame at it. "One version per HA event-loop tick"
    becomes "one version per recorded frame", which is what the version was for.
  - **It touches every node whose ask CAN resolve to a moved question**, not the exact set: with a
    window chosen by a variable (#209) the exact set is per session. Over-touching is the safe
    direction — a session whose values resolve elsewhere re-renders to the same key, hits the
    render cache, and sends nothing.
  - The standing cost: every open dashboard holding a 1 h chart re-renders once a minute. Correct,
    since the chart did change, and it is new load on a house where nothing is happening.

  **A pushed provider also needs INTEREST**, which history does not: a subscription costs an open
  HA stream, so it must end when nobody reads its question. Interest is per question, not per
  session — a session's reads change while it lives (a tab closed, a variable set), and two
  sessions on one question share one subscription. It is derived, not stored: a session's held
  nodes, their asks, and its own values give its questions. A count per question acquires the
  provider's `watch(question): Resource` on 0 → 1 and releases it one session-linger after
  1 → 0, so a reconnect or a tab flicker does not churn it. **A first answer is awaited**, bounded
  by `SharedCache.Timeout` and answered by the error label after it, like any answer — not a
  placeholder filled in later, which would break §0's complete render (a subscription typically
  sends its current value first).

  Two alternatives were rejected. **Piggybacking the state tick** is nearly free and does not fix
  the motivating case: a quiet house never ticks. **Letting the browser ask**
  (`data-on-interval` on chart nodes) moves a server-truth decision into the page, costs a request
  per chart per interval, and contradicts §0, where the server decides what a client is owed.

  One consequence to carry: a failed chart recovers only when something next renders it. Its
  failure is remembered for `FailureTtl` and then retried, but no ring says the retry is due, so
  on a quiet page it waits for its next bucket.

- **A failure is the chart's, not the page's.** A fetch or drawing that fails, or takes longer
  than `SharedCache.Timeout`, is answered with `Staged.failed` and a version below any real one,
  so the next good answer moves the render key. A chart's hole gets the runtime's error LABEL — the
  same structure as the library's `label`, styled by the base CSS every dashboard carries, so no
  card has to remember to handle it; a data hole is left empty, since it is escaped. The page and
  the live stream carry on; only a read that does not resolve — a wiring bug — raises. A failure is
  remembered for a short window rather than retried by every render, which would otherwise each
  wait out the timeout again.

## What this does not decide

How a chart is DRAWN — ECharts under GraalJS, and why not a browser chart — is
[ADR 0032](0032-a-chart-is-bytes.md).
