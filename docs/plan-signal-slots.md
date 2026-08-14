# Plan — signal slots: a value that changes without re-rendering its card

**Status: designed, not implemented.** Nothing described here exists in the sources yet. When it
lands, its decision moves to an ADR (0017), its shape moves into
[`architecture-rendering-pipeline.md`](architecture-rendering-pipeline.md), and this file is
deleted.

---

## The problem

Today every value that can move costs its whole card. A thermometer ticking from `21.4` to `21.5`
re-renders the node, re-hashes it, finds the digest moved, and morphs the entire
`<article class="card entity">…</article>` into place. The bytes are dominated by markup that did
not change, and the client does a DOM diff to discover that.

The pipeline is already as narrow as a *node* can make it (ADR 0012 — each session renders what it
is owed, suppressed against its own `holds`). Getting narrower means going below the node, and
Datastar already has the mechanism: a signal, and an element bound to it.

## What a signal slot is

A slot opts in — `SlotSource.signal = true`. Its value then travels to the browser as a **Datastar
signal** named `_<nodeId>__<slotName>`, rather than as bytes inside the node's element.

`_`-prefixed deliberately: Datastar's default request filter (`exclude: /(^|\.)_/`) drops
underscore-prefixed signals from every request body, so a dashboard's worth of live values never
joins an action POST or an SSE reconnect. Signals are not free — see the `datastar` skill.

The name is one derivation (`Renderer.signalName`), read from both ends: the renderer injects it
into the template, and the pull path names it in the frame. Written out twice, a drift would be
silent — the card would bind a signal nothing ever patches.

## Two render forms

This is the core of the design and everything else follows from it.

| form | value inline | binding attr | seed attr | who renders it |
|---|---|---|---|---|
| **document** | yes | yes | yes | page load, body repaint, mount fill, insert-from-fill |
| **patch** | no | yes | no | `renderNodeById` — the per-node morph path |

There is a third form latent in the design — **plain**: no binding, no seed, the bytes the renderer
emits today — but it is not reachable by any viewer. It is what a slot renders when
`Renderer.isSignalSlot` answers false, which is one predicate rather than a code path. See
"No `?signals=false`" below for why that seam is kept and not wired up.

```html
<!-- document -->
<div class="fh-cell" id="c_1" data-signals="{_c_1__value: '21.4', _c_1__secondary: 'Kitchen'}">
  <span class="state" data-text="$_c_1__value">21.4</span>
  <span class="sub" data-text="$_c_1__secondary">Kitchen</span>
</div>

<!-- patch -->
<div class="fh-cell" id="c_1">
  <span class="state" data-text="$_c_1__value"></span>
  <span class="sub" data-text="$_c_1__secondary"></span>
</div>
```

What each form buys:

- **the morph disappears.** In patch form the value is not in the node's bytes, so its digest does
  not move when only a signal slot does. `Patches.morph` suppresses the element patch, and one
  `datastar-patch-signals` frame carries the values for the whole batch instead. This is *not* the
  `RenderCache` — that is a sharing cache keyed by what a render reads and it never decides what
  goes on the wire. The decision is `Digest.of(html)` against this session's `holds`, as it always
  was.
- **an element seeds its own signal.** The document form's `data-signals` means a fill, an insert
  or a first paint needs no accompanying frame to be correct. `data-signals` overwrites by default
  in the pinned bundle (`__ifmissing` is opt-in), which is what makes re-seeding on a later
  wholesale render correct rather than a race.
- **no-JS gets the value.** A JS-less browser receives the document and nothing else — no
  Datastar, no SSE, no patches ever. The inline value is exactly what it needs. See the
  no-JS/no-signals split below.

## No `?signals=false` — the seam stays, the switch does not

This started as a per-connection opt-out for "environments where we want minimal JS work". That
rationale does not survive contact with the page: the client is already running Datastar, and
`data-on`, `data-show`, `data-effect` and `data-init` are all evaluating in the connection banner
before a single card renders. A handful of `data-text` bindings is noise against that. There is no
performance scenario where the switch earns its keep.

It is also **not** the same question as no-JS, and conflating them was this design's first mistake:

|  | Datastar running? | gets patches? | what serves it |
|---|---|---|---|
| **no-JS** | no | no | the document's inline values, full stop |
| **morph-only client** | partially — a hand-written subset | yes, element morphs | the plain form + today's diff path |

No-JS needs no mode at all. It cannot be detected server-side, and the document already carries
every value inline in both forms. There is **no point suppressing the seed or binding attributes
for it** — they are unknown attributes to a browser with no Datastar: invisible, inert, costing
bytes and nothing else. (A `?nojs` param that disables the stream and adds a meta-refresh is a
coherent separate feature. Not this one.)

The rationale that *does* hold is **protocol capability**, and it is why the plain form stays
reachable. The two SSE event types are nowhere near equally hard to implement:

- `datastar-patch-elements` in `outer` mode is "parse this HTML, swap the element with that id" —
  implementable in a few hundred lines on a microcontroller.
- `datastar-patch-signals` needs a signal store, an evaluator for `$name` expressions, a dependency
  graph, and re-application on change.

So "morph-only" is a real protocol subset, and a cheap ESP32/e-ink wall panel speaking it is a
plausible consumer for a home-automation dashboard (cf. `babe32`, an ESP32 web browser for cheap
black displays).

**Deferred anyway, because shipping the axis now would not deliver its own rationale.** A client
that cannot evaluate expressions is equally defeated by `data-on:click` on every tappable card,
`data-show` on the banners and `data-effect` on the URL mirror. `?signals=false` would be one axis
of a capability profile that does not exist, with the other axes designed blind. When a real device
client exists it should arrive as one profile (`?client=morph-only`) covering all of them, informed
by what that client actually cannot do.

Dropping it is also what keeps this feature small, and the reason is not obvious:

> **The switch is the only thing that would make `RenderInputs` grow.** `RenderCache` only ever
> holds *patch-form* bytes — `Patches.bytes` is its sole entry point, and the document walk bypasses
> the cache entirely. One form per (node, selection). A plain-form viewer needs a *different* form
> out of that same call, which is the only reason `vars` would need a `signals` bucket.

Without the switch, signal slots need **no cache changes at all**. That also leaves the two designs
friendly rather than opposed if the document path is ever moved onto the cache (see the open
question below): the walk would want the patch form, and the patch form is the only thing the cache
holds.

## Authoring: the renderer owns the binding, the card places it

A card template gets **one var per signal slot**, spliced raw. It is the *binding attribute alone*
— `data-text="$_c_1__value"` in both signal forms, `""` in the plain form — and the value keeps its
ordinary `{{value}}` hole beside it:

```
<span class="state" {{{value__bind}}}>{{value}}</span>
```

Three properties this has and the alternatives do not:

- **the plain form stays a one-predicate seam.** The renderer can emit nothing, which a
  template-authored `data-text` could not — so a future morph-only profile costs a predicate rather
  than an authoring migration.
- **multiple signal slots per node work.** The seed is **node-level, not slot-level**: one
  `data-signals` on the node's own `.fh-cell` wrapper carrying every signal slot in the patch unit.
  A per-slot seed var (the first cut) puts two `data-signals` attributes on one element the moment
  a card has two signal slots, and the second is silently dropped.
- **template authors never escape anything.** `data-signals` is compiled as a JS *expression* by
  the pinned bundle, so a value needs JS-string escaping inside HTML-attribute escaping — one
  place (`Datastar.signalsAttr`), not every card.

The wrapper is the right home for the seed because it is already renderer-owned and it appears in
exactly the renders that should carry it: a `self` card's patch renders `<id>-self` alone and is
correctly seedless, while its document/fill render includes the wrapper. A member's children fold
into the member's wrapper, because `signalsFor` stops where `Member.entitiesOf` stops — a nested
set is its own patch unit and owns its own signals. `wrapAsCell = false` cards need no rule:
`Dashboard.validate` already refuses live entity slots on them.

**First cut is text bindings only.** `__bind` is `data-text`. An attribute-position slot (a
slider's `width:{{percent}}%`) wants `data-style`/`data-attr`, which is a renderer-side enumeration
— `SlotSource.signal` becoming a small sum rather than a `Boolean` — not a template-side escape
hatch, because an escape hatch is precisely what would let a binding survive into the plain form
and break a morph-only client. Deliberately deferred; the slider is the obvious second customer.

Pkl side: `core/slot.pkl` gains `Slot.signal`, a `signalText(name)` helper emitting
`{{{name__bind}}}`, and `WireShapeSuite` keeps the Pkl class and `SlotSource` in step. First
customer is `entityCard`'s `value`.

## Session state: one map, not two

`holds` becomes `Map[NodeId, Held]`:

```scala
case class Held(digest: Digest, signals: Map[SignalId, String] = Map.empty)
```

There is no second cursor here and no second version pointer: `position` and `told` are untouched.
`Held` is the same "what does this client have" question `holds` already answers, asked of two
units.

A first cut had a parallel `Session.signals: Ref[IO, Map[String, String]]` with its own
invalidation — and that invalidation had to re-derive node ancestry by *string-prefixing the signal
name* (`_<nodeId>_…`), because the map was keyed by name. Two mechanisms for one job, and the
fragile half was the one parsing a name back into an id.

Signals are node-scoped by construction, so folding them into `holds` costs nothing and buys: one
invalidation rule (the existing id-prefix test in `Patches.applied`), one `Ref`, one fold per pull,
and no string surgery. `Addressed` grows `signals: Map[SignalId, String]` beside `establishes`, and
`applied` handles both.

To the question of why `holds` is a `Ref` at all: it is per-connection mutable state written from
two fibers — the session's own pull loop, and the action-POST fibers that run `swapHost`/`hostFill`
— so it needs a concurrent cell. A plain `Ref` rather than a `SignallingRef` because nothing
watches it. That does not change here.

`SignalId` is a new opaque type in `fh/view/model/Ids.scala`, alongside `NodeId` and `DomId` and
for the same stated reason: a signal name is currently a bare `String` threaded through
`signalsFor`, the session record, `Datastar.signalsJson`/`signalsAttr` and the frame. It is a
recurring implicit concept, which is the repo's standing signal to name it. `<: String` like its
siblings, minted only by `Renderer.signalName`.

## `holds` means one thing for a session's whole life

The document renders document-form; the per-node patch path renders patch-form. If `holds` is
seeded from the document's own bytes, the two digests differ and the **first live change to each
signal node sends one morph it otherwise would not** — once per node per page load, deleting inline
text that `data-text` had already overwritten. Harmless and self-healing, but it makes `holds` mean
"document form until the first tick, patch form after", which is the two-meanings-for-one-field
this pipeline has had to chase bugs out of before.

So `Traced` carries both forms:

```scala
private[runtime] case class Traced(
    html: String,                 // document form — the bytes the browser gets
    patch: String,                // patch form — same node, signal values withheld
    own: Map[NodeId, String]      // per node, in PATCH form: what seeds `holds`
)
```

**What the second form actually costs.** It is a second `tpl.execute` on the *same compiled
template* with a different context map (signal slots emptied, seed absent) — one execute, not a
subtree walk, and not a partial re-render either: jmustache has no partial execution, so the
template is re-run whole.

It is needed where a node **declares signal slots itself**, or **has own rendering and embeds a
descendant that does** — children ride inside a parent's bytes, so such a parent's patch form has
to be built from its children's patch forms. The guard is exact and cheap:

```
if (declaresSignals(node) || kids.exists(_.patch ne _.html)) <second execute>
else Traced(html, html, …)      // same reference, zero extra work
```

The second clause almost never fires in the shipped library: `grid`/`row`/`column` are bare
containers (a mount, no `self`), so they have no own rendering, are never log keys, and never need
a patch form. The realistic cost is one extra execute on the signal node itself.

Worth being precise about the path it sits on, because the obvious reassurance is wrong: **the
document walk does not go through the `RenderCache` at all**, and neither does a repaint — they are
the same pure walk. `Patches.bytes` is the cache's only entry point, so two viewers loading the
same dashboard simultaneously already each render the whole page today, signal slots or not. The
cache's ×1 guarantee is about the *pull* path (§5 of the pipeline map). The second execute is
therefore genuinely extra, bounded to opted-in nodes, on a path that was already unshared.

**Why the walk cannot simply use the cache** (the natural "make first paint behave like a repaint"
question) — two reasons, and only the second is incidental:

- *What a parent embeds is not what a patch carries.* The cache holds `renderNodeById` output. For
  a leaf that is the whole wrapped card, which is what the parent needs; for a `self`-declaring
  container it is the `self` element alone, where the parent needs `template` with `self` and
  `mount` spliced. The cache holds a different projection for exactly the nodes that compose.
- *The walk is pure, the cache is `IO`.* `RenderCache.apply` is single-flight through a `Deferred`
  under an `uncancelable` mask; reaching it means making `renderPage`/`renderBody`/`renderSurface`
  effectful, which touches every caller and test.

Neither is fatal — leaf bytes could come from the cache during a walk, giving both sharing between
simultaneous loads and a warm cache for the first live tick. That is a restructuring of the
document path, **out of scope here**, recorded as an open question. Note it composes with this
design rather than fighting it: the walk would want the patch form, and (having dropped
`?signals=false`) the patch form is the only thing the cache ever holds.

## Validation

Both failures are otherwise silent — the card renders, the patches shrink, the value stops moving:

- `signal = true` on a `literal` slot: a constant has nothing to patch.
- a card declaring a signal slot whose template never splices `{{{<slot>__bind}}}`: the patch form
  blanks the value and nothing puts it back.

## What moves in `architecture-rendering-pipeline.md`

The recorder is **untouched**. A signal-slot change still moves the node's version in the changelog
exactly as before; the node is still a candidate. What changes is only what the session does with
it once rendered — which keeps this entirely inside the PER-CLIENT half.

- **§1 diagram, CLIENT subgraph only.** `PULL` gains "…in the PATCH form: a signal slot's value is
  not in these bytes"; a new node `SIGS` between `PULL` and `MERGE` ("one datastar-patch-signals
  frame · the candidates' signal slots, diffed against this session's record"); `APPL` gains "…and
  what the frame set"; `SESS`'s `holds` becomes "holds (digest + signals)".
- **§2, "each connection wakes and PULLS"** — one added line beside `send the ones whose digest is
  not what it holds`.
- **§5, the scope table** — the per-client *Writes* row, and a sentence making explicit that the
  ×1 row is about the pull path: the document walk and the repaint are not cached, and never were.
- **§6, the pull-path diagram** — the one structural change. `HOLD -->|yes| DROP` stops being the
  end of the story:

  ```
  HOLD -->|yes| SIG{"did a signal slot move?"}
  SIG -->|no| DROP
  SIG -->|yes| FRAME["one signals frame — no element patch"]
  ```

  …and the "does this client already have these bytes?" paragraph gains a companion: the digest is
  asked of the *patch form*, which is why a value moving alone lands on the cheap side of it.
- **§7, where each box lives** — three rows: the two forms + the name
  (`runtime/Renderer.scala` · `signalName`, `signalsFor`, `renderTemplateOf`'s form), the frame
  (`runtime/Patches.scala` · `signalFrame`), what a client's signals hold
  (`runtime/Sessions.scala` · `Session.holds`'s `Held`).
- **§8, open questions** — moving the document walk onto the `RenderCache` (which subsumes
  "pre-seed it from a page render"), and the morph-only client profile.

## Order of work

1. `SignalId` in `Ids.scala`; `SlotSource.signal`; `Dashboard.validate`'s two rules.
2. `Renderer`: `signalName`, `signalsFor`, the two forms in `renderTemplateOf`, the node-level seed
   on the wrapper, `Traced.patch` with its `ne` guard. **No `renderInputs` change.**
3. `Patches`: `Patch.Signals`, `Addressed.signals`, `signalFrame`, `applied` folding both halves of
   `Held`.
4. `Sessions`/`Server`: `Held`; re-seeding on document / repaint / reload-repaint / host swap.
5. Pkl: `Slot.signal`, `signalText`, `entityCard`'s `value`; regenerate the wire snapshots
   (`sbt dashboardSnapshotsUpdate`) and read the JSON diff.
6. Tests: a suite pinning (a) a signal-only change emits a frame and **no** element patch,
   (b) a change to a non-signal slot still morphs, (c) the document carries value + seed and a
   patch carries neither, (d) two signal slots on one node produce one seed attribute,
   (e) `holds` seeded by the document suppresses the first tick's morph (the invariant `Traced.patch`
   buys), (f) a member arriving mid-stream gets its signal in the same batch.
7. ADR 0017 + the pipeline-map edits above, in the same commit as the code.

## Deliberately not in this change

- `?signals=false` / a morph-only client profile — see above.
- A `?nojs` mode (no stream, meta-refresh).
- Non-text bindings (`data-style`, `data-attr`), which the slider wants.
- Moving the document walk onto the `RenderCache`.
