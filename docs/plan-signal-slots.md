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

## Three render forms

This is the core of the design and everything else follows from it.

| form | value inline | binding attr | seed attr | who renders it |
|---|---|---|---|---|
| **plain** | yes | no | no | any viewer with `?signals=false` |
| **document** | yes | yes | yes | page load, body repaint, mount fill, insert-from-fill |
| **patch** | no | yes | no | `renderNodeById` — the per-node morph path |

```html
<!-- plain: byte-identical to what the renderer emits today -->
<div class="fh-cell" id="c_1"><span class="state">21.4</span></div>

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

## `?signals=false` is not "no JS"

They are independent, and conflating them was the first thing this design got wrong.

|  | Datastar running? | gets patches? | what serves it |
|---|---|---|---|
| **no-JS** | no | no | the document's inline values, full stop |
| **`?signals=false`** | yes | yes, element morphs | the plain form + today's diff path |

No-JS cannot be detected server-side and needs no mode: the document already carries every value
inline in all three forms. There is **no point suppressing the seed or binding attributes for it** —
they are unknown attributes to a browser with no Datastar, invisible and inert, costing bytes and
nothing else.

`?signals=false` is a live client that wants less reactive work. That means it must get the
**plain** form: emitting `data-text` + `data-signals` *and* morphing the whole card on every change
is strictly more client work than today, which would make the flag a lie. So the mode reaches the
render, and the binding must therefore be renderer-controlled — which decides the authoring API
below.

The mode is per connection, captured on the document and forwarded to the SSE URL (a session minted
by a bookmarked SSE endpoint has no document to ask). It rides in `Session.signalsOn` and lands in
`RenderInputs.vars` — **only for nodes that actually declare a signal slot**, so viewers on either
side of the flag do not share a generation for those nodes and every other node still mints exactly
one bucket.

## Authoring: the renderer owns the binding, the card places it

A card template gets **one var per signal slot**, spliced raw. It is the *binding attribute alone*
— `data-text="$_c_1__value"` in the signal forms, `""` in the plain form — and the value keeps its
ordinary `{{value}}` hole beside it:

```
<span class="state" {{{value__bind}}}>{{value}}</span>
```

Three properties this has and the alternatives do not:

- **the plain form is genuinely plain.** The renderer can emit nothing, which a template-authored
  `data-text` could not.
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
hatch, because an escape hatch is precisely what would let a binding survive into the plain form.
Deliberately deferred; the slider is the obvious second customer.

Pkl side: `core/slot.pkl` gains `Slot.signal`, a `signalText(name)` helper emitting
`{{{name__bind}}}`, and `WireShapeSuite` keeps the Pkl class and `SlotSource` in step. First
customer is `entityCard`'s `value`.

## Session state: one map, not two

`holds` becomes `Map[NodeId, Held]`:

```scala
case class Held(digest: Digest, signals: Map[SignalId, String] = Map.empty)
```

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
"document form until the first tick, patch form after".

So `Traced` carries both: `html` (document form — what goes to the browser) and per-node `own` in
**patch** form (what seeds `holds`). One extra `renderTemplateOf` call, only for nodes that declare
a signal slot, only on the document/fill path, never on the live tick.

Worth being precise about what that costs, because the obvious reassurance is wrong: **the document
path does not go through the `RenderCache` at all.** `Patches.bytes` is the only entry point; the
page walk renders directly. So two viewers loading the same dashboard simultaneously already each
render the whole page today, signal slots or not — the cache's ×1 guarantee is about the *pull*
path (§5 of the pipeline map). The second render is therefore genuinely a second render, bounded to
opted-in nodes on a path that is already unshared.

That the document's per-node patch-form bytes are *exactly* what `Patches.bytes` would later cache
is a real observation, and pre-seeding the cache from a page render would make the first live tick
free for every concurrent loader. It needs `RenderCache.apply`'s single-flight `IO` shape to grow a
pure install path, so it is **out of scope here** — recorded as an open question, not built.

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
  ×1 row is about the pull path, not the document path.
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
  (`runtime/Sessions.scala` · `Session.holds`'s `Held`, `signalsOn`).
- **§8, open questions** — pre-seeding the `RenderCache` from a document render.

## Order of work

1. `SignalId` in `Ids.scala`; `SlotSource.signal`; `Dashboard.validate`'s two rules.
2. `Renderer`: `signalName`, `signalsFor`, the three forms in `renderTemplateOf`, the node-level
   seed on the wrapper, `Traced`'s second form, `renderInputs`' bucket.
3. `Patches`: `Patch.Signals`, `Addressed.signals`, `signalFrame`, `applied` folding both.
4. `Sessions`/`Server`: `Held`, `signalsOn`, the `?signals=false` param and its forwarding,
   re-seeding on document / repaint / reload-repaint / host swap.
5. Pkl: `Slot.signal`, `signalText`, `entityCard`'s `value`; regenerate the wire snapshots
   (`sbt dashboardSnapshotsUpdate`) and read the JSON diff.
6. Tests: a suite pinning (a) a signal-only change emits a frame and **no** element patch,
   (b) `?signals=false` emits a morph and **no** frame, (c) the document carries value + seed and a
   patch carries neither, (d) two signal slots on one node produce one seed attribute.
7. ADR 0017 + the pipeline-map edits above, in the same commit as the code.
