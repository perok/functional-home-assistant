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
**expression value** is a named input. They are not alternatives: a slot carries the values its CEL
reads (`SlotSource.values`), and `expressionValues` is the node-level declaration that `foldNode`
distributes onto them. Everything below builds on that one path, and nothing gains a second
evaluator.

**Declaration.** `Node.expressionValues: Mapping<String(!startsWith("__")), ExpressionValue>`, where
`ExpressionValue = String|Int|Float|Boolean|pred.Tally|pred.Predicate`, written as an amend or with
the `expressionValues(m)` builder. On the wire it is `Component.values` (the author's values and
the derived ones below, merged), absent when empty. Two properties, because a `Mapping?` defaulting
to null can be neither amended nor `new {}`'d — both infer `Dynamic` (measured) — and an
always-present `{}` would sit on every node's wire.

**A value is a typed CEL variable** of that node's expressions: String → `string`, Int → `int`,
Float → `double`, Boolean → `bool`, a tally → `int`, a condition → `bool`. Numbers decode by their
SPELLING, as Pkl wrote them, since a `Float` 2.0 and an `Int` 2 have one value; a condition is the
object that carries a `kind`, a tally the one that does not. The expression compiles against the
fixed names plus the node's (`Transform.CelKey`: source and typed names), so a typo, an unknown
name or `lights_on + ' on'` (no `int + string` overload) fails the BUILD. Refused too: a name that
is not a CEL identifier, one hiding `state`/`attr`/`entity_id`/`domain`/`dashboard_slug`, a value
no slot reads, a slot read `once` that reads a count or a condition (the once-cache keys by what a
value IS, so it would show the first answer forever), and a condition comparing an entity it does
not name — a value is shared by content across nodes, so it has no subject, as a state condition
has none.

**Node-local names, shared by content.** Nothing below the node sees its names. That is a node
variable's job (ADR 0033), and a different fact: a viewer's choice, inherited, against a computed
value, not. Sharing comes from content instead — a tally is its candidates and residuals, a
condition its canonical predicate — so two nodes counting the same lights under different names
count the same thing.

**Attached at decode.** `LayoutNode.foldNode` first turns the node's live cell classes and live
custom properties into slots, then puts each value onto every slot whose CEL names it, by the
identifiers the expression PARSES to, so a word inside a string literal is not a read. A slot then
says alone what it reads, and the compile key, the signal name and the watched entities all come
off it; no render path takes the node's values as an argument.

### Boolean slots, and what fills one

A slot carries a **type** (`Slot.type`: `"text"` by default, or `"bool"`), set by the card where it
binds the slot, beside `signal` — the card says how its template reads the value, because a reading
has no binding of its own (`docs/terminology.md`). A `class:` binding is a bool by kind. One field
rather than a per-card list of boolean slots, so another type is one more case: `"number"`, once a
signal can carry a JSON number (a slot value is `String | Boolean` today).

The build refuses a reading on a boolean slot that does not produce a bool, because the template
reads it by truthiness and the string `"false"` is ON. Per tier: CEL by its checked result type
(`dyn` passes, since `state` is `dyn` and refusing it would refuse `state == 'on'`); `Simple`
exactly, since every shape's result is known at build time — only an all-Boolean `match` (what
`c.isOn`/`c.stateIn` build) is a bool.

**What fills one** is a `BooleanInput`: a `Boolean` the build knows, a reading (`c.isOn(l)`, the
fast tier), an `Expr` over the node's values (`c.expr("on > 2")`), or a condition (`lit.any()`,
`q.entity(l).stateIs("on")`). `active`, `disabled` and `classWhen` all take one. A condition is Pkl
sugar onto the one path: the input declares a value named after itself (`__active`,
`__disabled`, `__class_<hash>` — a class name is not an identifier) and a bool slot whose CEL is
that name. The derived values live in `Node.derivedValues`, apart from the author's, because the
`expressionValues(m)` builder assigns and would drop them; names starting `__` are refused in
`expressionValues` so the two cannot collide. An aggregate the build can settle IS a `Boolean` —
`any()` over a room with no lights is `false` — and then there is no live slot at all, and a
`true` `disabled` is the bare attribute.

`disabled` is ORed with the tap's own refusal in the shared refusal helpers (ADR 0015), so a card
that places them honours it with nothing of its own.

### A live custom property on any node

`.cssProperty(name, reading)` sets a custom property on the node's `.fh-cell` from any reading that
produces text: a `Simple` one stays on the fast tier (`simpleMod.fill(…)`), an `Expr` may read the
node's values. It is `classWhen`'s twin: a `cell.style:` slot beside `cell.class:`, the value inline
(`style="--x:…"`) in the document form and a `data-style:--x` binding after the wrapper's seed in
both. Only a custom property (`--…`), since a plain property would fight the theme's framework,
which writes `!important` utilities; a reading that is certainly a bool is refused — that is
`classWhen`'s job.

It cannot drive the slider's own fill: BeerCSS declares `--_end:0%` on `.slider` itself, so a value
inherited from the cell is shadowed, and `beer.min.js` rewrites the element's `style.cssText` on
every move. The slider keeps its element-level `style:--_end` slot, the same mechanism on its own
element.

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
  share, and built a slot behind the author's back. The condition sugar names its value too, but
  after the INPUT, and a condition is the whole yes/no — no text is composed from it.
- **A condition as a slot transform FORM** (beside CEL and `Simple`). No names, but a condition
  would then be evaluated two ways: as a transform, and as a value inside CEL.
- **`CardDef.booleanSlots`, a list of a card's boolean slots.** A list per type where one field
  extends, and on the card rather than on the value it describes.
- **Send the count, let the browser compose the text.** The same expression in CEL for the
  document form and in JS for the patch, which can disagree, and a browser without JS gets only
  the first.
- **Slot references in expressions.** Slots reading slots is a dependency graph with an order and
  cycles. Values are flat: slots read values, values read only state.
- **Resolving names up the ancestors.** Content addressing already shares the work, and inheritance
  would make a node's expression depend on where it is placed.
- **Show/hide through a class.** Hiding is structure, which is `c.iff`'s.

## Consequences

- A slot can now reach other entities than its own, but only as values a node declared.
- A tally whose candidates the build settled has no residuals; the runtime counts an unguarded
  candidate as present, so no build-time fold is needed. Those candidates still wake the node —
  a recompute whose unchanged result the frame diff swallows — the same cost a settled `Count`
  has.
- A condition value on a card with a subject is named under that entity's signal path, since the
  slot inherits the subject; its own `valueKey` keeps it distinct.
- Values are node-local, so a STRUCTURAL card puts a condition's value on the child node whose slot
  reads it — the slider's `disabled` declares `__disabled` on its head.
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
