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
instead of as bytes inside the node's element.** A change to it costs one
`datastar-patch-signals` frame for the whole batch rather than a re-rendered card each.

**A display signal is named by WHAT IT READS**: `_e.<domain>.<object_id>.<transform>`, so one
entity shown on three cards is one signal and one frame entry (issue #134). The transform is one
path segment — `state`, `attr_<name>`, or `t<hash>` for a computed expression — because the slot
NAME cannot serve: two cards naming one transform differently would stop sharing, and one name over
two transforms would collide.

Dots are path separators, not characters: the bundle rewrites `$_e.light.taklys.state` to
`$['_e']['light']['taklys']['state']`, and `datastar-patch-signals` applies `mergePatch`, which
recurses only where a value is an object. So a frame carries a nested structure and never a flat
dotted key, and a partial patch leaves an entity's siblings alone.

**A two-way binding is the exception and stays `_<nodeId>__<slotName>`.** `SignalBind.Bind` is
interaction state, not an entity value — the input writes it back on every drag — so sharing it by
`(entity, transform)` would let one card's gesture drive another card's readout, which is the
confusion ADR 0025 separated `_<id>__slide` out to avoid.

`_`-prefixed deliberately: Datastar's default request filter drops underscore-prefixed signals from
every request body, so a dashboard's worth of live values never joins an action POST or an SSE
reconnect. That is not decoration — it is the reason this does not simply move the cost from the
server's frames to the client's requests.

### Two render forms, which is the whole design

| form | value inline | binding | seed | rendered by |
|---|---|---|---|---|
| **document** | yes | yes | yes | page load, body repaint, host fill, insert-from-fill |
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

  **The saving is on the wire AND on the server, but the server half had to be claimed
  separately.** A suppressed morph is still RENDERED — rendering it is how we discover its bytes
  did not move — so for a long time a signals tick cost the server slightly MORE than one whose
  bytes moved (262 µs against 232 µs). The `RenderCache` key is supposed to exclude entities
  reaching a node only through signal slots (ADR 0012), but it holds a per-entity
  `contentVersion`, and the shipped card's name reads `friendly_name` as bytes — one slot, enough
  to re-admit the entity and re-render the node on every brightness tick.

  Fixed by comparing the resolved byte-slot values before rendering (ADR 0012):
  `RenderBench.resumeSignals` is now **101.7 µs / 151 kB against `resumeMorphs`' 255 µs / 439 kB**.
  A signals tick is now the cheap one, which is what this ADR always claimed.

  Worth stating plainly because the obvious reading of "the morph disappears" is that the work
  disappeared with it. On a first paint signal slots are a straight cost with no suppression to
  offset them at all (`pageSignals` against `page`, roughly 2x) — that is the case this ADR is
  not about.
- **An element seeds its own signal.** The document form's `data-signals` means a first paint, a
  host fill or a `?prev=` reconnect repaint needs no frame to be correct. `data-signals`
  overwrites by default in the pinned bundle (`__ifmissing` is opt-in), so re-seeding on a later
  wholesale render is correct rather than a race.
- **A JS-less browser gets the value.** It receives the document and nothing else — no Datastar, no
  SSE, no patches ever — so the inline value is exactly and only what it needs.

The **binding** is present in both forms and absent from neither: it is what the seeded signal
feeds, and a morph that dropped it would leave the element inert for good.

### A section around a signal slot tests a companion flag, never the value

The patch form's missing value is not merely absent text — to Mustache it is a FALSE section. So
`{{#value}}…{{/value}}` around a signal slot renders on the document form and **collapses on every
patch**, deleting the element and the binding inside it. Nothing recovers: a binding that is no
longer in the DOM is never written to again. The guard has to be a separate literal slot, present
in both forms:

```
{{#hasIcon}}<i class="{{icon}}" {{{icon__bind}}}></i>{{/hasIcon}}
```

**This bit.** `entityCard` wrapped its icon in `{{#icon}}` — correct while `icon` was a literal,
wrong the moment it became a signal slot. The glyph vanished on the first tap and came back only
on reload, because the tap's patch re-rendered the card in patch form and took the whole `<i>`
with it. It is silent in exactly the way the other guard is not: `Dashboard.validate` rejects a
signal slot whose template never places `{{{<slot>__bind}}}`, but a template that places it
*inside a section keyed on the value* passes.

So every card carrying a signal slot also carries a hand-written literal flag — `hasLit`,
`hasInert`, `liveIcon`, `busy`. **That idiom is a bug generator and the renderer should derive
it**: it already synthesises `<slot>__bind` and `<slot>__signal` per signal slot, and a
`<slot>__has` beside them would delete the flag, the `when` that sets it and the comment
explaining it from every card at once.

### The renderer owns the binding; the card places it

A card gets one extra template var per signal slot, spliced raw beside the ordinary hole:

```
<span class="state fh-text"><span class="fh-text-run" {{{value__bind}}}>{{value}}</span></span>
```

It goes on the element whose text it IS, which is not always the card's outermost one: a
`data-text` patch replaces that element's whole content, so binding a wrapper would delete
whatever else the wrapper holds. (Here the wrapper is the label box of `core/text.pkl` — the
entity card's reading is clipped or scrolled like any other text — and the binding rides on the
run inside it.)

`value__bind` is the whole attribute (`data-text="$_c_1__value"`), not a signal name. Three things
follow that a template-authored `data-text` would not give:

- **The plain form stays a one-predicate seam.** `Renderer.isSignalSlot` answering `false`
  everywhere yields exactly the bytes this renderer emitted before signal slots existed — no
  binding, no seed — see "What was deliberately left out", and issue #133.
- **Multiple signal slots per node work.** The seed is **node-level**: one `data-signals` on the
  `.fh-cell` wrapper carrying every signal slot in the patch unit. A per-slot seed puts two
  `data-signals` on one element the moment a card has two, and the browser silently keeps one.
- **Card authors never escape anything.** `data-signals` is compiled as a JS *expression* by the
  pinned bundle, so a value needs JS-string escaping inside HTML-attribute escaping. One place
  (`Datastar.signalsAttr`), not every card.

The wrapper is the right home for the seed because it is renderer-owned and appears in precisely
the renders that should carry it: the PATCH form is the card's own markup and is correctly
seedless, while its document render includes the wrapper.

**Whether a signal name descends into children is the difference between the two halves of the
graph, not a detail.** In the static tree a child is a node of its own — its own id, wrapper, seed,
patched on its own. A *member's* children are not: they render with `structuralVars(m.id)` and no
ids at all, because the member is their patch target, so their slots live in its namespace.
Descending in the static case would seed a child's signal on its parent's wrapper and leave the
child's binding pointed at a signal nothing ever writes — silent, and permanent.

### Six binding kinds, because a value does not always land in text

`signal`'s value says **where** the value lands — the one thing the renderer cannot infer. It is a
renderer-side enumeration (`SignalBind`, one string on the wire) rather than an attribute the card
writes, which is what keeps the plain form reachable: a card that wrote `data-text` itself could not
have it un-written.

| kind | attribute | for |
|---|---|---|
| `text` | `data-text="$sig"` | a reading, a label, a state |
| `style:<prop>` | `data-style:<prop>="$sig"` | a track fill, a colour — custom properties included |
| `attr:<name>` | `data-attr:<name>="$sig"` | one attribute — a String value fills it, a BOOLEAN sets or removes it |
| `class:<name>` | `data-class:<name>="$sig"` | one class, present while the value is truthy |
| `bind` | `data-bind="sig"` | **two-way** on a form control: the server writes it, the user's input writes it back |
| `handler` | none | a value nothing paints — an event handler reads it by name (see below) |

Every kind reads the signal **bare**, with no expression around it, because the value carries
whatever it needs — a fill arrives as `39.37%`, a colour as `#ffb46b`. An expression in the
attribute would be a second place a value's shape is decided, and the transform already decides it.

### Which runtime evaluates a state-dependent value

The five kinds all spend a signal on the DOM, but a **handler** can read one too, and that decides
where a build-time-known function of state belongs:

- A function whose result the **DOM** consumes is a server-side transform. The result has to be in
  the element's bytes or in its signal, so the server is the only thing that can produce it.
- A function whose result only an **event** consumes is client-side JS over a signal. It need only
  exist at click time, so evaluating it on the server bakes a state-dependent result into bytes
  that were otherwise static.

This does not weaken "every kind reads the signal bare" above: that rule is about *bindings*, where
an expression would be a second place a value's shape is decided. A handler is not a binding — it
consumes the value rather than painting it.

`entityCard`'s tap on a `CallByState` domain (ADR 0016) is miscategorised as the first. A lock's
service is not knowable until the click, so the onclick is rendered from a live transform — which
puts the service in the element's bytes, moves them on every lock/unlock, and drops the tile out of
the identity cache to repaint a URL nobody ever looks at. Read at click time instead, the same
two-way test costs no bytes:

```
data-on:click="@post('sse/action/home/' + $_e.lock.front.t4d7a74a1
  + '/lock.front?node=c_4', {filterSignals:{exclude:'.*'}})"
```

Every byte constant; only the signal moves. The request body also stays empty — the handler reads
the signal locally — which sending the observed state as a payload would have cost.

**The signal carries the RESULT, not the state.** The obvious design is to seed the raw state and
compile the domain table into the click expression as JS. That is one declaration too many: the
table would exist as a CEL transform *and* as a JS ternary, and nothing keeps two spellings of one
lookup honest. Carrying the answer instead leaves the match on the server, in one place, with the
parity suite (ADR 0028) already behind it — only the READ moves to the browser. So there is no
second expression language, no escaping regime, and no drift to prevent.

That is what `SignalBind.Handler` is: a signal with no binding at all, because nothing paints it.
Everything else about it is an ordinary signal slot — one name per `(entity, transform)`, value
withheld from the patch form, seeded on the wrapper, carried in the frame.

The one thing it forces on a card: **the URL is assembled in the template, not in the transform.**
Mustache runs once and a slot's value is spliced raw, so a URL built server-side could not carry a
placeholder for the signal name or `{{dashboardSlug}}`. `tap.serviceClick` is that markup, placed
the way `tap.guard` already is — and `?node={{id}}` comes free with it, where a transform had to
read `el.dataset.fhNode` at click time and got nothing on an unguarded tap.

### `<slot>__read` — a value as an expression, so the card cannot tell the tiers apart

A card composing a slot into a handler gets one var, and the renderer decides its spelling:

| slot | `service__read` |
|---|---|
| signal | `$_e.lock.front.t4d7a74a1` |
| literal / identity-`once` | `'light/toggle'` (quoted through the seed's own escaper) |

So the *static* tap and the *state-dependent* one are the same template — a card branching on
which tier filled a slot is the mistake `liveIcon` already made once, and this is the same fix as
`__has` applied to a value instead of a section. It also keeps the plain form reachable: with
`signalBind` answering `None` everywhere (issue #133) the slot falls back to its quoted literal and
a state-dependent tap still works with no JS at all.

Two `validate` rules, because both failures are silent:

- A `Handler` slot must be READ — `{{{<slot>__read}}}` or the bare `{{<slot>__signal}}` — where a
  painted kind must place `{{{<slot>__bind}}}`. It has no binding to place, so the existing rule
  could not have covered it.
- A slot read as `__read` must not be **live and non-signal**. That is the one shape with no
  answer, and refusing it is the rule rather than a gap: such a value moves in the element's bytes
  every tick, which is precisely what carrying it as a signal exists to stop.

**"You send what you saw" is preserved, and is why this is safe.** The signal is patched in the
same frame as the visible state, so at click time it holds what the user is looking at: the command
is stale in exactly the way the pixels are stale, which is the property ADR 0016 wants. The
alternative of a constant URL that the **server** resolves from its own state was considered and is
wrong — it leaves the pixels stale and makes the command fresh, so a tap on a lock someone else has
since unlocked re-locks it. Self-consistent stale bytes are the property to keep, not the defect to
fix.

**The client's reading stays advisory.** `Server.actionResponse` bounds a tap by entity (ADR 0023)
and does not check the service — already true of the URL this replaces, so nothing here widens it.
A two-way test is invertible (a posted `lock/unlock` can only mean the client saw `locked`), so the
server can compare the implied observation against its own state and refuse a stale tap if that is
ever wanted.

**What it does not buy.** A handler signal is still a value the server decided, so anything the
client must compute from two moving values at click time is out of scope — that would need the
state itself, and the second declaration this avoids. Nothing wants it yet, and the shape to reach
for first is another handler signal.

### A slot value is `String | Boolean`, and the boolean is load-bearing

A boolean attribute is absent or present, and the value has to be able to say which. Datastar's attr
plugin implements the DOM's own model — `l===""||l===!0 → setAttribute(a,"")`,
`l===!1||l==null → removeAttribute(a)` — and `disabled=""` **is** how HTML spells on. So an empty
string SETS, and while `""` was the only falsy value a slot could produce, `attr:` could turn a
boolean attribute on and never off again.

A real `false` is what turns it off, so `SlotValue = String | Boolean`
(`Transform.Simple.Match`'s arms, and any CEL expression whose result is a `bool`). The binding
stays bare, and the mechanism is the plugin's own branch rather than anything invented here.

**Both ends have to carry it, and they fail independently:**

- The **frame and the seed** send a JSON/JS boolean — `{"sig": false}`, `{sig: false}`, unquoted.
  `"false"` would be a truthy string in every binding that reads it.
- The **document form** paints the value inline, and the card places it as a Mustache SECTION
  (`{{#off}}disabled{{/off}}`), never as an attribute value — `disabled="false"` is *present*,
  because a boolean attribute ignores its value. mustache.java drives that section off
  `java.lang.Boolean`, and the **string `"false"` is truthy there**. So `paint` carries the real
  Boolean too: stringifying on the way to the template would disable the control in exactly the
  case that must not, in the form a browser without JS gets, with every signal-level assertion
  still green.

**Rejected: a `flag:` kind emitting `data-attr:x="!!$sig"`.** It shipped briefly. It works, but it
puts a second decision about a value's shape into the attribute — the thing the bare-read rule
exists to prevent — and it exists only because the value type was too narrow. Fixing the type
deleted the kind.

**Rejected: making the value nullable instead.** Built in full and reverted. **`null` in a signals
frame DELETES the signal** and orphans every binding on it (the store proxy is
`if (a == null) delete r[o]`; pinned by `DatastarMorphContractSuite`); reading the name afterwards
re-creates it as `""`, which nothing is watching. A boolean has no such problem — only `null` is
special to the store.

Worth knowing why `false` is safe where it looks like it might not be: a display signal is keyed by
`(entity, transform)` and **shared across binding kinds** (`Renderer.signalName` — only `bind` is
node-scoped), so one value can be read as `data-text` on another card. A boolean-producing transform
is a *different* transform string, so it gets its own signal; it can only be shared with a card
reading the same boolean transform, where `true`/`false` is the honest rendering.

A boolean `disabled` is for a control unavailable **because of the entity's state** — the lock is
already moving, the device is offline. There a real `disabled` is the right answer: it leaves the
tab order and stops the click in the browser, where a dimming class needs a JS guard to mean
anything.

It is **not** the answer for an action in flight. ADR 0019 settled that deliberately: a disabled
button loses focus and fires no events at all, which is worse for a card than an inert one, so
`busy` guards the expression instead. The two cases differ in duration and in who is waiting — a
state the entity is in, against a request the user just made — and that is why they get different
mechanisms rather than one.

`bind` is the odd one out twice over: it takes the signal's *name* rather than a `$`-read, and its
card is **not plain-form-capable** — an interactive control needs a client signal whatever this
setting says. That is not a new limitation; the slider hard-coded `data-signals` + `data-bind` +
`data-on:change` long before any of this.

### The second way a card stops being plain-form-capable: a signal slot on STRUCTURE

A signal slot on a card that holds regions is legal (see the structure rule in
`Dashboard.validate`, which forbids only a live slot carrying BYTES there — a signal needs no patch,
so nothing about structure is in its way). In the **plain** form it has no delivery mechanism at
all: that form emits no binding and no seed, so the value is inline bytes, and inline bytes change
only by patching the node — which structure never is. The card is right at first paint and frozen
after.

Worth naming because it is the same failure the signals form had until `signalsFor` stopped gating
on `hasOwnRendering`: seeded once, then still. Fixing it for one profile re-created it for the other,
and only one of the two has a client today.

Concretely this costs nothing yet — `sliderHead` is the only structural card carrying signal slots,
and the slider was already excluded by the `bind` rule above. What it costs is the *guarantee*: "a
live value lives on a patchable node" used to be true by construction, and was silently doing issue
[#133](https://github.com/perok/functional-home-assistant/issues/133)'s work for it. It is now a
property a card can lose without saying so.

The sharper question underneath it is that **the model cannot tell a display signal from an
interaction one**, and #133 needs exactly that split. The slider's five are not one kind of thing:
`busy_change` (a client indicator) and `value` (server-written, feeding adopt/rollback, with no DOM
home of its own) are machinery a morph-only client should never receive; `state`, `fill` and
`fillColor` are the pixels such a client exists to render. With the distinction named, the rule
becomes exact — display signals must live on a patchable node, interaction signals may live
anywhere and the plain form omits them — and without it, no rule about where signal slots may sit
can be.

And the constraint that makes it more than bookkeeping is **granularity**. A morph carries the whole
of the node it targets, so a display value falling back to a morph should sit on the smallest leaf
that contains it. On structure there is no target at all, so the fallback would have to climb to
some patchable ancestor — not a coarse morph but an unbounded one. `sliderText` is the shape that
gets this right (a rename repaints a `<strong>`); the head's own signals are the shape that does
not.

Deliberately not acted on here: a capability surface designed against no client is what #133 exists
to avoid. Recorded on the issue too.

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

**The predicted fight happened, and the drag lost.** Datastar's style plugin keeps a
`MutationObserver` on the `style` attribute and re-applies its property when anything else writes
there; `beer.min.js` repaints the slider fill on `input` by rewriting the wrapper's whole
`style.cssText`. So every move of a drag was answered by the server's last `--_end` snapping back,
and the gesture showed nothing at all until the release committed it.

The fix keeps the binding server-owned and gives the drag its own paint: `data-on:input` on the
range input writes `$…__fill` (and, where the readout is a percent, `$…__state`) from
`evt.target.value` — so the very re-application that was clobbering the fill now re-applies the
right value. It reads the event rather than the bound signal because `data-bind` listens for
`input` on the same element and the earlier-registered listener wins.

This is **optimistic**: the server's next value overwrites it, and a call HA rejects leaves the fill
where the thumb is. That is the drift `data-bind` already has on the thumb, kept consistent with it,
not a new one. `UiSmokeSuite`'s two mid-drag tests hold the mouse DOWN across the assertion, which
is the only window in which any of this is observable.

### One record, not two

`Session.holds` is `Map[NodeId, Held]`, where `Held(digest: Option[Digest], signals: Map[SignalId,
String])`. Both halves answer "is this worth sending?" about one node.

Sharing the map is not tidiness. A host fill makes everything under it unknown, and with a
separate signal map keyed by signal *name* that invalidation could only be expressed by
string-prefixing the name back into a node id. Keyed by node, it is the id-prefix test
`Patches.applied` already runs.

A signal name is no longer node-scoped, so one value can sit in TWO nodes' `Held.signals`. That is
fine and stays fine: the frame is a map keyed by name, so duplicates collapse, and a node dropped by
a fill merely re-sends a value another node still holds — a redundant frame entry, never a wrong
one. It is why keying signals by value did NOT need the second invalidation mechanism this section
exists to avoid; splitting `holds` to hold them per PATH would only remove that redundancy, which is
an optimisation nobody has measured a need for.

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

The cost is a second `tpl.execute` on the same compiled template, and only that. It is paid exactly
where there is an `own` to fingerprint — a node with its own rendering whose slots carry a signal —
because nothing else ever reads a patch form. Structure therefore renders once, and cannot do
otherwise: `Traced` has no `patch` field for it to render into.

Two things this deliberately does NOT do, both measured rather than assumed
(`benchmarks/RenderBench`, `pageSignals` against `page`):

- **It does not resolve the slots twice.** The forms differ in one step — a signal slot's value is
  withheld — so `resolveTemplate` runs once and `executeResolved` runs per form. Resolving per form
  meant re-running every JSONata transform and re-deriving every signal name to arrive at the same
  map and blank two entries in it, and that duplication, not the `execute`, was most of what a
  signal slot cost a first paint.
- **It does not re-derive the seed.** The `data-signals` values come off that same `Resolved`, so
  the seeded value and the painted value are one computation rather than two that agree.

Together those took the signal-slot premium on a 200-leaf page from ~2.5x a signal-free render to
~1.5x.

## Consequences

- A frame is diffed against what this viewer holds, exactly as bytes are — a node is a candidate
  because an entity it binds moved, which is not the same as its signal slots having moved.
- **One frame per batch**, merged across every node it touched — not one per node. It goes **first**:
  a signal set before the element binding it is simply the value that element paints with when it
  arrives, and Datastar re-evaluates a binding on morph either way. That ordering is what makes a
  member *insert* correct, since its bytes are patch-form and carry no seed.
- **The cursor merges into it when nothing separates them.** `Patches.encode` merges adjacent
  `Patch.Signals` the way it merges adjacent morphs, and the cursor rides as a patch rather than an
  appended event — so a value tick is ONE `datastar-patch-signals` on the wire instead of a frame
  followed by a cursor frame. *Adjacent* is the whole rule, and it is what keeps the cursor honest:
  put an element patch between them and they stay two events, which echoing-as-ack requires
  (ADR 0011).
- **A departing node's signal is not sent, and not cleared.** A `Gone` is not a candidate, so
  nothing is emitted for a value that has left the DOM. Safe rather than merely cheap, because
  signals outlive the elements bound to them: if the member returns with the same value, its
  re-inserted (patch-form, seedless) element reads a store that is still correct. What leaks is one
  entry per departed member on each side, bounded by the dashboard — a set's candidates are static
  (ADR 0003).
- **One entity shown in N places is ONE signal**, because a name is scoped to the value rather than
  to the node showing it ([issue #134](https://github.com/perok/functional-home-assistant/issues/134)).
  The readability this was once thought to cost did not materialise: no name is minted, so the
  binding spells the entity out — `$_e.light.taklys.state` reads better in a frame log than
  `_c_16__fill`, not worse.
- **No `RenderCache` change at all**, and that is a consequence of what was left out (below): the
  cache only ever holds patch-form bytes, so there is one form per (node, selection) and
  `RenderInputs` does not grow.
- `Dashboard.validate` rejects the two otherwise-silent mistakes: `signal` on a constant `literal`
  (nothing to patch), and a card declaring a signal slot whose template never places
  `{{{<slot>__bind}}}` (the patch form withholds the value and nothing puts it back).
- Applied to `entityCard`'s `value` and, where the domain has a state glyph, its `icon`; to the
  `inert` class of a tap whose domain declares transitional states; to the `service` of a
  `CallByState` tap, as a `handler` signal; and to all four of the slider's moving slots. `label`
  stays a registry fact and never moves.

## What was deliberately left out

**A `?signals=false` switch.** It was designed and dropped. The rationale that motivated it —
"environments wanting minimal JS work" — does not survive contact with the page: the client is
already running Datastar, with `data-on`, `data-show`, `data-effect` and `data-init` evaluating in
the connection banner before a card renders. A few `data-text` bindings are noise against that.

The rationale that *does* hold is **protocol capability** — a device that implements
`datastar-patch-elements` but not an expression evaluator — and that is
[issue #133](https://github.com/perok/functional-home-assistant/issues/133), not this ADR. What
belongs here is the consequence for the design: it is why the PLAIN form stays reachable behind one
predicate (`Renderer.signalBind` answering `None`), and therefore why `SignalBind` is a
renderer-side enumeration rather than an attribute a card writes.

Leaving the switch out is also what keeps this change small, non-obviously: it was the only thing
that would have made `RenderInputs` grow, because a plain-form viewer needs a different form out of
the same `Patches.bytes` call.

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
