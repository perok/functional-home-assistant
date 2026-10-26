# ADR 0034 — Expression values and conditions: named inputs, and the slots that read them

A node **declares** named values; its slots' CEL **reads** them by name, as typed variables. A
value is a literal, a **tally** (a static candidate list and which of it is present, counted
live), or a **condition** (what `c.iff` takes, decided live). Every yes/no input on a card — a
button's `active`, a `disabled`, a `classWhen` — takes a condition directly, and any node can set a
live CSS custom property from a reading. The floor button this was built for:

```pkl
local lit = q.from(hass.lights(floor.all)).where(q.eq(q.stateProp, "on"))

c.button(floor.floor_name, c.tap.lights.off(floor))
  .secondary(c.expr(#"string(on) + ' / ' + string(total) + ' on'"#))
  .active(lit.any())
  .disabled(lit.none())
  .expressionValues(new Mapping { ["on"] = lit.count(); ["total"] = hass.lights(floor.all).length })
  .cssProperty("--fh-fill", c.expr("string(on * 100 / total) + '%'"))
```

Its tap can take the same set, `c.tap.lights.off(lit)`, so the press turns off exactly what the
count shows (ADR 0003); `floor` reaches whatever HA puts on the floor instead.

## Context

A slot reads ONE entity: its CEL sees that entity's `state`, `attr`, `entity_id`, `domain` and the
`dashboard_slug`, and a card is woken only by the entities its slots name
(`Component.liveEntities`). "3 of 5 lights on" had no spelling. The count already existed —
`q.from(xs).where(…).count()` folds to a static candidate list with a per-candidate residual (ADR
0003) — but only as half of a COMPARISON, for `c.iff` (ADR 0007) and the aggregates. And a yes/no
input could only read one entity (`c.isOn(l)`), so "disabled while no light on this floor is on"
had no spelling either.

## Decision

### One machinery: a slot reads values, a node names them

A **slot** is where a result lands (text, a class, an attribute, a style property). An
**expression value** is a named input. They are not alternatives: `expressionValues` is the
node-level declaration, and each slot is bound to the ones its CEL reads (`BoundSlot`). Everything
below builds on that one path, and nothing gains a second evaluator.

**Declaration.** `Node.expressionValues: Mapping<String, ExpressionValue>`, where
`ExpressionValue = String|Int|Float|Boolean|pred.Tally|pred.Predicate|slot.Fetch`, written as an amend or with
the `expressionValues(m)` builder. On the wire it is `Component.values`, absent when empty. Two
properties, because a `Mapping?` defaulting to null can be neither amended nor `new {}`'d — both
infer `Dynamic` (measured) — and an always-present `{}` would sit on every node's wire.

**A value is a typed CEL variable** of that node's expressions: String → `string`, Int → `int`,
Float → `double`, Boolean → `bool`, a tally → `int`, a condition → `bool`, a fetch → its provider's
answer (history's points, `list(list(double))` of `[epochMillis, value]`). Numbers decode by their
SPELLING, as Pkl wrote them, since a `Float` 2.0 and an `Int` 2 have one value; a condition is the
object that carries a `kind`, a fetch the one that carries a `provider`, a tally neither. The expression compiles against the
fixed names plus the node's (`Transform.CelKey`: source and typed names), so a typo, an unknown
name or `lights_on + ' on'` (no `int + string` overload) fails the BUILD. Refused too: a name that
is not a CEL identifier, one hiding `state`/`attr`/`entity_id`/`domain`/`dashboard_slug`/`time_zone`, a value
no slot reads, a slot read `once` that reads a count or a condition (the once-cache keys by what a
value IS, so it would show the first answer forever; a fetch too), and a condition comparing an entity it does
not name — a value is shared by content across nodes, so it has no subject, as a state condition
has none.

**Node-local names, shared by content.** Nothing below the node sees its names. That is a node
variable's job (ADR 0033), and a different fact: a viewer's choice, inherited, against a computed
value, not. Sharing comes from content instead — a tally is its candidates and residuals, a
condition its canonical predicate — so two nodes counting the same lights under different names
count the same thing.

**Bound on the node.** Binding is the build pass that connects a name to its definition and type
(`docs/terminology.md`). `LayoutNode.Component.bound` runs it lazily, once per node: it pairs each
slot, live cell classes and properties included (Pkl writes those as slots), with the values its
CEL names, by the identifiers the expression PARSES to, so a word inside a string literal is not a
read. It is derived from the decoded node rather than written back into it, so no slot can be seen
before it is bound, or bound twice, and the decoder only decodes. The bound slot says alone what it
reads: the compile key, the signal name and the watched entities all come off it, and no render
path takes the node's values as an argument. The binding fixes each name's type, not its value,
which `Conditions.resolve` computes on every render.

### A slot's own values

A slot may carry values of its own (`Slot.expressionValues`, `values` on the wire beside the
node's), the same kinds. A function can then hand out a finished slot that brings its inputs —
`c.bar.remainingOfPeak(left)`'s whole carries the countdown's history — with no node having to
declare them, and two slots can use one name for different reads. Binding merges the node's and
the slot's; a name in both is a build error, as is one the slot's own expression does not read.

### A fetch is a value read on demand

A fetch value (`c.fetch.history(e).over(w)`, ADR 0031) is a provider read by name: the bar's whole is
the countdown's highest reading since it last read 0, CEL over its history. It adds no wake: the
slot is read when its own entity changes, or when its card is drawn, and the provider's answer is
whatever its cache holds then (history's, per 5-minute period). Nothing watches the answer itself;
a push when a period ends with no entity change is designed when something needs one. Reading on
every change is cheap because the cost is rows, not calls: with the server's exact call against a
real house, a countdown's history took 15–30 ms for 24 h or 7 d (a row a minute while it runs, none
while idle), where a dense power sensor took 0.5–3.4 s.

The answer is read before the CEL runs, on both paths. A value fetch counts among the node's fetches
(`BoundSlot.fetches`), so a render's pre-read and its render key carry it as a fetch slot's are
carried, and the signal path, which already pre-reads the fetches of the nodes it updates, hands
them to `signalsFor`. A site that reads no snapshot (a `once` slot, a subject) refuses a fetch at
build time and raises if one ever reaches it. What a value's fetch reads joins
`Dashboard.fetchedEntities`, so a viewer's value is bounded as a fetch slot's is (ADR 0023).

### Boolean slots, and what fills one

A slot carries a **type** (`Slot.type`: `"text"` by default, or `"bool"`), set by the card where it
binds the slot, beside `signal` — the card says how its template reads the value, because a reading
has no binding of its own (`docs/terminology.md`). A `class:` binding is a bool by kind. One field
rather than a per-card list of boolean slots, so another type is one more case: `"number"`, once a
signal can carry a JSON number (a slot value is `String | Boolean` today).

The build refuses a reading on a boolean slot that does not produce a bool, because the template
reads it by truthiness and the string `"false"` is ON. Per tier: CEL by its checked result type
(`dyn` passes, since `state` is `dyn` and refusing it would refuse `state == 'on'`); `Simple`
exactly, since every shape's result is known at build time — a condition, or an all-Boolean
`match` (what `c.isOn`/`c.stateIn` build), is a bool.

**What fills one** is a `BooleanInput`: a `Boolean` the build knows, a reading (`c.isOn(l)`, the
fast tier), an `Expr` over the node's values (`c.expr("on > 2")`), or a condition (`lit.any()`,
`q.entity(l).stateIs("on")`). `active`, `disabled` and `classWhen` all take one. A condition IS the
bool slot's transform, the fast tier's bool half (ADR 0028), decided directly by `Conditions` with
no CEL and no name; a comparison that names no entity reads the slot's, and the build refuses one
on a slot that has none. An aggregate the build can settle IS a `Boolean` —
`any()` over a room with no lights is `false` — and then there is no live slot at all, and a
`true` `disabled` is the bare attribute.

`disabled` is ORed with the tap's own refusal in the shared refusal helpers (ADR 0015), so a card
that places them honours it with nothing of its own.

### A slot's value only while a condition holds: `valueWhile`

A slot's `valueWhile` is a boolean input too, `true` by default: while it does not hold, the slot's
value is `""`, its transform does not run and **its fetches are not read**. The progress card's
countdown has no value between jobs. It is the slot-sized case of what the pre-read does for
regions: a `c.iff` branch that does not show and a candidate that is not present are not asked
either. It is lowered as the others are, onto a bool slot nested in
the slot (`valueWhileSlot` on the wire, absent when `true`) whose transform is the condition, CEL
or a constant (`false` is `neverHolds`, an empty `Or`); it reads the slot's entity when it names none.

It is decided first, and **once per state snapshot**, in that snapshot's decisions (the pipeline
doc's §1a), keyed by content (the entity it is decided over, `BoundSlot.gateEntity` being the one
rule, and the condition) so two slots gated alike share one. A node's fetching slots each carry
their `valueWhile` (`Fetching`), and the pre-read asks a slot's fetches only where it holds; the
render key, the paint and the signal path ask the same decisions, so none decides it again. A
slot whose subject only a paint knows (a transform subject) is asked ungated, because asking too
much is safe and asking too little raises. The condition's entities wake the slot, and it is part of the slot's `valueKey`, so the signal a slot
with one sends is not the one a card without one shares (ADR 0017). The build refuses one that
reads a fetch (it decides whether fetches are read), one that does not produce a bool, and one on
a `once` slot, whose memo would keep its first answer.

`""` is the empty value, as a CEL `null` already is: a `null` in a signals frame deletes the signal
(ADR 0017). What it means is the card's to say; the progress bar hides itself on it.

**What would reopen it:** cel-java's async functions (cel-expr/cel-java#1240, merged after 0.14.0)
would let a fetch be a function the CEL calls only on the branch that reaches it, so a fetch would
be read lazily without a gate. `valueWhile` stays even then: it is the condition a reader can see,
and a slot behind it runs nothing.

### A live custom property on any node

`.cssProperty(name, reading)` sets a custom property on the node's `.fh-cell` from any reading that
produces text: a `Simple` one stays on the fast tier (`simpleMod.fill(…)`), an `Expr` may read the
node's values. It is `classWhen`'s twin: a `cell.style:` slot beside `cell.class:`, the value inline
(`style="--x:…"`) in the document form and a `data-style:--x` binding after the wrapper's seed in
both. Only a custom property (`--…`), since a plain property would fight the theme's framework,
which writes `!important` utilities; a reading that is certainly a bool is refused — that is
`classWhen`'s job.

It cannot drive the slider's own fill: the track's `<span>` declares its own `--fh-fill` inline, so
a value inherited from the cell is shadowed. The slider keeps its element-level `style:--fh-fill`
slot, the same mechanism on its own element.

### What is shared with `c.iff`, and what is not

| | |
|---|---|
| Which entities can move it | Shared: `Predicate.referencedEntities`, and for a tally its candidates plus the entities their residuals name. They join the node's `liveEntities` (the reverse index that wakes a page node) and `referencedEntities`, so the watched set the upstream subscription asks for (ADR 0030) and ADR 0023's bound. |
| Evaluating it | Shared: `Conditions`, the one interpreter — `present` counts a tally, `matchesIn` decides a condition. |
| What fires | Not shared, deliberately. A flipped `c.iff` swaps a surface (`SurfaceGraph.affectedStateGroups`); a moved value patches a slot. Two jobs, two paths. |

**The server computes the result.** The whole expression is evaluated on the server and sent the
way its slot is carried — on a live `secondary`, a class, a `disabled` or a custom property, one
entry in an ordinary `datastar-patch-signals` frame. The slot's `valueKey` carries its values, so
two nodes saying the same thing over the same values share one name; a tick that leaves the answer
alone sends nothing, because the frame is diffed against what each session holds.

## Rejected

- **A reading built from the count, with implicit names.** The first proposal,
  `q.from(…).where(…).reading("… count … total …")`, gave the expression `count`, `total` and
  `names` it never declared, fused WHICH set with WHAT text so two texts over one set could not
  share, and built a slot behind the author's back. A boolean input builds a slot too, but from
  the condition the author wrote, which is the whole yes/no — no text is composed from it.
- **A condition read through a derived value** (`__active`, a bool slot whose CEL is that name).
  It was the first lowering: every boolean input on a card ran `Conditions` and then a CEL call
  that did nothing, at five times the allocation of deciding it directly (`RenderBench.condition`
  vs `conditionCel`), and it needed a second mapping on every node and a reserved `__` prefix.
  A condition is evaluated two ways now only where an author asks: as a transform, and as a
  value an author's CEL reads by name.
- **`CardDef.booleanSlots`, a list of a card's boolean slots.** A list per type where one field
  extends, and on the card rather than on the value it describes.
- **Send the count, let the browser compose the text.** The same expression in CEL for the
  document form and in JS for the patch, which can disagree, and a browser without JS gets only
  the first.
- **Slot references in expressions.** Slots reading slots is a dependency graph with an order and
  cycles. Values are flat: slots read values, values read only state.
- **Resolving names up the ancestors.** Content addressing already shares the work, and inheritance
  would make a node's expression depend on where it is placed.
- **Show/hide through a class.** Hiding is structure, which is `c.iff`'s, a card's own part
  included: the progress card's bar sits in one.
- **"When it changes, do X" triggers.** That is an automation, and automations belong in HA.
- **`cssVar` as the name.** "vars" already means three things here (`docs/terminology.md`), so the
  builder is `cssProperty`.

## Consequences

- A slot can now reach other entities than its own: as values a node declared, or as a fast read
  that names one.
- A tally whose candidates the build settled has no residuals; the runtime counts an unguarded
  candidate as present, so no build-time fold is needed. Those candidates still wake the node —
  a recompute whose unchanged result the frame diff swallows — the same cost a settled `Count`
  has.
- A condition value on a card with a subject is named under that entity's signal path, since the
  slot inherits the subject; its own `valueKey` keeps it distinct.
- Values are node-local. An author's values go on the card, and the card hands each part the ones
  that part's slots name (`nodes.valuesNamedIn`): a slider's second line reads them on its text
  node, its readout on its head. It matches names with a regex, since Pkl cannot run the CEL
  parser; a wrong match is caught by the build, which binds by the parsed identifiers and refuses
  a value no expression reads.
- Not built, and why: a `names` value (the present members' names — a list, so a second CEL type
  and an order to decide); a `Simple` shape over values (a percent of two counts needs its own
  totality rule for `total = 0`); a `"number"` slot type (needs a numeric signal value); a
  per-tick cache of evaluated tallies shared with `c.iff` (counting a floor is trivial; add it when
  measured); string interpolation (CEL has none).
- `ExpressionValuesSuite` holds the behaviour from a real Pkl entry: the document, a flip sending a
  frame and not the line, a brightness tick sending nothing, shared and distinct signals, a popup,
  a candidate-set member, conditions as values, the boolean inputs (tint, disable, class, settled
  at build), `disabled` on a tile and a slider, `cssProperty`, and the build refusals.
  `LiveCellClassSmokeSuite` holds what only a browser shows: a live class, an ORed `disabled` and
  a live property each survive a morph of their node.
