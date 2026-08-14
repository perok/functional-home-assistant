# 0017 — Signal slots: a value that changes without re-rendering its card

## Context

Every value that can move cost its whole card. A thermometer ticking from `21.4` to `21.5`
re-rendered its node, re-hashed it, found the digest moved, and morphed the entire
`<article class="card entity">…</article>` across the wire. The bytes were dominated by markup that
had not changed, and the client did a DOM diff to discover that.

The pipeline was already as narrow as a *node* can make it — ADR 0012's per-session pull, suppressed
against that session's own `holds`. Getting narrower means going below the node, and Datastar
already has the mechanism: a signal, and an element bound to it.

## Decision

**A slot opts in — `SlotSource.signal = true` — and its value then travels as a Datastar signal
named `_<nodeId>__<slotName>` instead of as bytes inside the node's element.** A change to it costs
one `datastar-patch-signals` frame for the whole batch rather than a re-rendered card each.

`_`-prefixed deliberately: Datastar's default request filter drops underscore-prefixed signals from
every request body, so a dashboard's worth of live values never joins an action POST or an SSE
reconnect. That is not decoration — it is the reason this does not simply move the cost from the
server's frames to the client's requests.

### Two render forms, which is the whole design

| form | value inline | binding | seed | rendered by |
|---|---|---|---|---|
| **document** | yes | yes | yes | page load, body repaint, mount fill, insert-from-fill |
| **patch** | no | yes | no | `renderNodeById` — the per-node morph path |

```html
<!-- document -->
<div class="fh-cell" id="c_1" data-signals="{_c_1__value: '21.4'}">
  <span class="state" data-text="$_c_1__value">21.4</span>
</div>

<!-- patch -->
<div class="fh-cell" id="c_1">
  <span class="state" data-text="$_c_1__value"></span>
</div>
```

Each form is load-bearing:

- **The morph disappears.** In patch form the value is not in the node's bytes, so its digest does
  not move when only a signal slot does; `Patches.morph` suppresses the element patch and the frame
  carries the value instead. That suppression *is* the feature, and it is why the tests here assert
  what is **not** on the wire — a broken implementation still updates the card, because the morph it
  was meant to replace is still being sent.
- **An element seeds its own signal.** The document form's `data-signals` means a first paint, a
  mount fill or a `?prev=` reconnect repaint needs no frame to be correct. `data-signals`
  overwrites by default in the pinned bundle (`__ifmissing` is opt-in), so re-seeding on a later
  wholesale render is correct rather than a race.
- **A JS-less browser gets the value.** It receives the document and nothing else — no Datastar, no
  SSE, no patches ever — so the inline value is exactly and only what it needs.

The **binding** is present in both forms and absent from neither: it is what the seeded signal
feeds, and a morph that dropped it would leave the element inert for good.

### The renderer owns the binding; the card places it

A card gets one extra template var per signal slot, spliced raw beside the ordinary hole:

```
<span class="state" {{{value__bind}}}>{{value}}</span>
```

`value__bind` is the whole attribute (`data-text="$_c_1__value"`), not a signal name. Three things
follow that a template-authored `data-text` would not give:

- **The plain form stays a one-predicate seam.** `Renderer.isSignalSlot` answering `false`
  everywhere yields exactly the bytes this renderer emitted before signal slots existed — no
  binding, no seed. See "morph-only clients" below.
- **Multiple signal slots per node work.** The seed is **node-level**: one `data-signals` on the
  `.fh-cell` wrapper carrying every signal slot in the patch unit. A per-slot seed puts two
  `data-signals` on one element the moment a card has two, and the browser silently keeps one.
- **Card authors never escape anything.** `data-signals` is compiled as a JS *expression* by the
  pinned bundle, so a value needs JS-string escaping inside HTML-attribute escaping. One place
  (`Datastar.signalsAttr`), not every card.

The wrapper is the right home for the seed because it is renderer-owned and appears in precisely
the renders that should carry it: a `self` card's patch renders `<id>-self` alone and is correctly
seedless, while its document render includes the wrapper.

**Whether a signal name descends into children is the difference between the two halves of the
graph, not a detail.** In the static tree a child is a node of its own — its own id, wrapper, seed,
patched on its own. A *member's* children are not: they render with `structuralVars(m.id)` and no
ids at all, because the member is their patch target, so their slots live in its namespace.
Descending in the static case would seed a child's signal on its parent's wrapper and leave the
child's binding pointed at a signal nothing ever writes — silent, and permanent.

### Four binding kinds, because a value does not always land in text

`signal`'s value says **where** the value lands — the one thing the renderer cannot infer. It is a
renderer-side enumeration (`SignalBind`, one string on the wire) rather than an attribute the card
writes, which is what keeps the plain form reachable: a card that wrote `data-text` itself could not
have it un-written.

| kind | attribute | for |
|---|---|---|
| `text` | `data-text="$sig"` | a reading, a label, a state |
| `style:<prop>` | `data-style:<prop>="$sig"` | a track fill, a colour — custom properties included |
| `attr:<name>` | `data-attr:<name>="$sig"` | one attribute |
| `bind` | `data-bind="sig"` | **two-way** on a form control: the server writes it, the user's input writes it back |

Every kind reads the signal **bare**, with no expression around it, because the value carries
whatever it needs — a fill arrives as `39.37%`, a colour as `#ffb46b`. An expression in the
attribute would be a second place a value's shape is decided, and the transform already decides it.

`bind` is the odd one out twice over: it takes the signal's *name* rather than a `$`-read, and its
card is **not plain-form-capable** — an interactive control needs a client signal whatever this
setting says. That is not a new limitation; the slider hard-coded `data-signals` + `data-bind` +
`data-on:change` long before any of this.

For the one thing a canned attribute cannot cover — a card composing the signal into an expression
of its own, as the slider's action URL does — the renderer also injects `<slot>__signal`, the bare
name. Not a binding, so it does not compromise the plain form; a card that uses it is simply
relying on a signal existing.

### The slider is the shape that justifies all four

`entityCard` has one moving value. The slider has **four**, and they are the reason the kinds are
not optional: getting any one of them wrong re-renders the card and the other three buy nothing.

| slot | kind | why |
|---|---|---|
| `state` | `text` | the readout |
| `value` | `bind` | the range input's position — replaces a hand-rolled `data-signals="{ _val_<id>: … }"`, so there is one signal where there were two |
| `fill` | `style:--_end` | the track fill; the transform now emits its own `%` |
| `fillColor` | `style:background` | moves with a light's colour |

**One known risk, and it needs a browser to settle.** Datastar's style plugin keeps a
`MutationObserver` on the `style` attribute and re-applies its property when anything else writes
there. `beer.min.js` repaints the slider fill on `input` events during a drag. So during a drag the
two may fight, with the server's `--_end` snapping back over BeerCSS's live paint. If it does, the
fix is to make the fill a client-side function of the *bound* signal rather than a server value of
its own — either a `data-style` expression over `$…__value`, or a CSS `calc()` off a bound custom
property. Neither is verifiable from a terminal, so it is called out rather than guessed at.

### One record, not two

`Session.holds` is `Map[NodeId, Held]`, where `Held(digest: Option[Digest], signals: Map[SignalId,
String])`. Both halves answer "is this worth sending?" about one node.

Sharing the map is not tidiness. A mount fill makes everything under it unknown, and with a
separate signal map keyed by signal *name* that invalidation could only be expressed by
string-prefixing the name back into a node id. Keyed by node, it is the id-prefix test
`Patches.applied` already runs.

`digest` is optional because a patch can establish one half alone: a patch-form morph carries bytes
and says nothing about the values bound inside them, and a `Patch.Signals` frame is the mirror
image. So `Held` doubles as the delta a patch reports and the record a session keeps, with
`Held.merge` as the single rule for combining them — `applied` merges per node rather than
replacing, or each patch would forget the half it was silent about.

### `holds` means one thing for a session's whole life

The document renders document-form; the patch path renders patch-form. Seeding `holds` from the
document's own bytes would make the two digests differ, so the first live change to each signal
node would send one morph it otherwise would not — harmless and self-healing, but it makes `holds`
mean "document form until the first tick, patch form after".

So `Renderer.Traced` carries both: `html` (document form, to the browser) and per-node `own` in
patch form (what seeds `holds`), as `Painted(html, signals)` — a product, because a node has bytes
*and* signals and one render establishes both.

The cost is a second `tpl.execute` on the same compiled template, needed only where a node declares
signal slots itself or has own rendering and embeds a descendant that does. The guard is exact
(`kids.exists(_.patch ne _.html)`), so a signal-free subtree hands the same `String` reference back
and does no extra work. In the shipped library the second clause almost never fires:
`grid`/`row`/`column` are bare containers with no own rendering and are never log keys.

## Consequences

- A frame is diffed against what this viewer holds, exactly as bytes are — a node is a candidate
  because an entity it binds moved, which is not the same as its signal slots having moved.
- The frame goes **first** in a batch. A signal set before the element binding it is simply the
  value that element paints with when it arrives, and Datastar re-evaluates a binding on morph
  either way. That ordering is what makes a member *insert* correct: its bytes are patch-form and
  carry no seed, so the frame is the only thing that gives it a value.
- **No `RenderCache` change at all**, and that is a consequence of what was left out (below): the
  cache only ever holds patch-form bytes, so there is one form per (node, selection) and
  `RenderInputs` does not grow.
- `Dashboard.validate` rejects the two otherwise-silent mistakes: `signal` on a constant `literal`
  (nothing to patch), and a card declaring a signal slot whose template never places
  `{{{<slot>__bind}}}` (the patch form withholds the value and nothing puts it back).
- Applied to `entityCard`'s `value` — the one thing on that card that moves on an ordinary tick;
  icon, label and tap are registry facts or literals and never move at all — and to all four of the
  slider's moving slots.

## What was deliberately left out

**A `?signals=false` switch.** It was designed and dropped. The rationale that motivated it —
"environments wanting minimal JS work" — does not survive contact with the page: the client is
already running Datastar, with `data-on`, `data-show`, `data-effect` and `data-init` evaluating in
the connection banner before a card renders. A few `data-text` bindings are noise against that.

The rationale that *does* hold is **protocol capability**, and it is why the plain form stays
reachable as a predicate. The two SSE event types are nowhere near equally hard to implement:
`datastar-patch-elements` in `outer` mode is "parse this HTML, swap the element with that id",
implementable on a microcontroller; `datastar-patch-signals` needs a signal store, an evaluator for
`$name`, a dependency graph and re-application on change. A cheap ESP32/e-ink wall panel speaking
the morph-only subset is a plausible consumer for a home-automation dashboard.

Shipping that one axis now would not deliver its own rationale, though: such a client is equally
defeated by `data-on:click` on every tappable card, `data-show` on the banners and `data-effect` on
the URL mirror. It wants one capability profile (`?client=morph-only`) covering all of them,
designed when a real client exists. And leaving it out is what keeps this small — the switch was
the only thing that would have made `RenderInputs` grow, because a plain-form viewer needs a
different form out of the same `Patches.bytes` call.

**No-JS is a separate question and needs no mode.** It cannot be detected server-side, and the
document already carries every value inline in both forms. Suppressing the seed or binding for it
would buy nothing: they are unknown attributes to a browser with no Datastar — invisible, inert,
costing bytes and nothing else. A `?nojs` param that drops the stream and adds a meta-refresh is a
coherent feature; it is not this one.

**Moving the document walk onto the `RenderCache`** —
[issue #130](https://github.com/perok/functional-home-assistant/issues/130). Neither a page load
nor a repaint is cached today, so two simultaneous loaders each render the whole page and the first
live tick is cold. It composes with this design rather than fighting it: the walk would want the
patch form, and the patch form is the only thing the cache holds.
