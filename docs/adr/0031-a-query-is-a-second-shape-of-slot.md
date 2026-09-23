# ADR 0031 — A query is a second SHAPE of slot, and what its answer becomes is the transform's

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

`SlotSource` gains `query: Option[SlotQuery]`. `Dashboard.validate` turns that into

```scala
enum SlotShape:
  case State(source: SlotSource)
  case Query(read: SlotRead)
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
this re-read, and does reading it wake the node*; a query answers *where does this value come
from*.** Two axes, and folding them fakes one with the other.

With the split, the guards do not get fixed — they **delete**. A `SlotShape.Query` has no `reads`
and no `signal` to read, so the combinations are unreachable rather than rejected. The property
that matters most falls out of the same fact: both entity lists are built from STATE slots, so a
query slot has no entity to contribute and **a chart is not a candidate on a state tick**. Nothing
arranges that; it is a property of the type.

### What is checked rather than made unrepresentable

`reads` is INERT on a query slot, which raises what the wire should SAY. It says `onRender`,
derived by the Pkl default and checked at build time — not because anything acts on it, but
because `"reads": "live"` beside a query is a lie the document tells whoever reads it, a
third-party tool included.

## Decision 2 — the provider fetches; the transform draws

The first build had the provider answer with markup: `Fragment(version, html)`, with the chart
style riding in the query's params. That put the presentation of a fetched value inside the thing
that fetches it.

`HistoryQuery.parse`'s own doc had already written the division down without the types being able
to act on it: *"`entity` and `window` are the question; the rest is how it is drawn."*

So: `QueryResolver.answer` returns `Answer(version, json)`, `QueryRequest.History` is
`(entityId, window)`, and `width`/`height`/`unit` moved to the slot's `transform`.

### Why the same field, and not a new one

`transform` already answers *how does this value become what the card puts in the hole*. What
differs between the shapes is the SUBJECT: a CEL string and a `Transform.Simple` read an ENTITY, a
`Transform.Stage` reads a provider's answer. A query slot does not get a second field for the same
question, and `SlotShape` keeps each arm on the shape that can use it.

Two spellings were rejected:

- **a bare string** (`transform = "passthrough"`) — a bare string in that field means a CEL
  expression, so it would compile as one and die on an undeclared identifier;
- **a member of `Transform.Simple`** — that type's documented membership rule is "a static lookup
  and TOTAL", evaluated without the engine. A chart renderer is neither, and putting it there
  would falsify the rule its own scaladoc states.

### Why the default is derived

`transform` defaults to `"state"`, which is the identity for a STATE slot and means nothing beside
a query. So the Pkl default is derived from the shape — `if (query == null) "state" else
passthrough` — exactly as `reads` is, and for the same reason: a fixed default makes the wire lie
on one of the two shapes. The evidence is in the snapshots this change regenerated, where a query
slot had been carrying `"transform": "state"`, which was never true of it.

### What the split bought

None of this was designed separately; all of it fell out.

- **Passthrough is the third-party contract, and it is an absence rather than a feature.** No
  transform means the provider's JSON — `[[epochMillis, value], …]` — which is what someone using
  their own chart library reads against. The positional array is a rule, not a preference: the
  payload can ride a Datastar signal, where a JSON null DELETES the signal and orphans every
  binding on it with no error anywhere, so a gap must be an array element and never an object
  field.
- **The fetch/draw split is structural.** `Fragments.resolve` deduplicates one fetch per QUERY and
  one drawing per `(query, stage)` — `SlotRead` is that pair, and it is what `RenderInputs`
  carries. Two cards charting one sensor over one window at different sizes cost one fetch and two
  drawings *because the keys say so*, where before `HistoryProvider` held two private caches and a
  test pinned the choice down.
- **The drawing cache needs no expiry.** A series has a shelf life — it stops being current when
  its bucket rolls, which is what decides when a version moves. A drawing has none: it is a
  deterministic function of an answer, so `ChartStage` keys by version and replaces in place. That
  is the half of `BucketCache` it does not need.
- **The raw-hole rule generalises.** It was "a query slot's value is markup, so its hole must be
  `{{{x}}}`". It is now **the last stage decides the hole**: a drawing needs the raw one, and
  passthrough must NOT have one, because its value is an attribute payload and wants escaping.

## Consequences

- **A provider is a case in a closed sum**, not a registration: `QueryRequest`, `Queries.parse`
  and `QueryResolver`, all pointed at by the compiler. Same for a stage. There is no plugin story
  to pay for, and one `match` says in code what exists where a name→instance map says only what
  somebody remembered.
- **The Pkl core names no provider and no drawing stage.** `core/slot.pkl`'s `Query.provider` is a
  plain String and `core/stage.pkl` knows only `passthrough`; the provider's name, its parameters
  and the chart stage are typed in the component that offers them (`components/history.pkl`). An
  unknown provider is still a build error — from `Queries.parse`, not the Pkl typechecker.
- **The model never NAMES a history type.** `fh.view.model` does not know what a window or a chart
  size is: `params` are untyped on the wire, and the typing lives at both ends — a typed Pkl
  builder, and a pure `parse` at validation. Stated precisely, because the obvious stronger claim
  is false: `Dashboard.scala` imports `fh.view.query` for `Queries.parse`, and `query` imports
  `history`. The seam is `Queries`, not the package boundary — a second provider adds a case
  there and nothing in the model changes.
- **Parsing is pure and separate from resolving**, which removes the wiring hazard entirely.
  Checking a chart needs no store, no HA connection and no JavaScript engine, so
  a bad query or a bad chart size fails wherever a dashboard is built (the size as the wire is
  decoded, the query in `Dashboard.validate`), rather than wherever somebody remembered to pass a
  provider in.
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

## What this does not decide

How a chart is DRAWN — ECharts under GraalJS, and why not a browser chart — is
[ADR 0032](0032-a-chart-is-bytes.md).
