# ADR 0034 — Expression values: named inputs a node's own expressions read

A node **declares** named values; its slots' CEL **reads** them by name, as typed variables. A
value is a literal, or a **tally** — a static candidate list and which of it is present, counted
live. The first user is a floor's button whose second line says how many of its lights are on:

```pkl
c.button(f.floor_name, c.tap.lights.off(f))
  .secondary(c.expr(#"string(lights_on) + ' / ' + string(lights_total) + ' on'"#))
  .expressionValues(new Mapping {
    ["lights_on"] = q.from(lights).where(q.eq(q.stateProp, "on")).count()
    ["lights_total"] = lights.length
  })
```

## Context

A slot reads ONE entity: its CEL sees that entity's `state`, `attr`, `entity_id`, `domain` and the
`dashboard_slug`, and a card is woken only by the entities its slots name
(`Component.liveEntities`). "3 of 5 lights on" had no spelling. The count already existed —
`q.from(xs).where(…).count()` folds to a static candidate list with a per-candidate residual (ADR
0003) — but only as half of a COMPARISON, for `c.iff` (ADR 0007) and the aggregates.

## Decision

**Declaration.** `Node.expressionValues: Mapping<String, ExpressionValue>`, where
`ExpressionValue = String|Int|Float|Boolean|pred.Tally`, written as an amend or with the
`expressionValues(m)` builder. On the wire it is `Component.values`, absent when empty. Two
properties, because a `Mapping?` defaulting to null can be neither amended nor `new {}`'d — both
infer `Dynamic` (measured) — and an always-present `{}` would sit on every node's wire.

**A value is a typed CEL variable** of that node's expressions: String → `string`, Int → `int`,
Float → `double`, Boolean → `bool`, a tally → `int`. Numbers decode by their SPELLING, as Pkl
wrote them, since a `Float` 2.0 and an `Int` 2 have one value. The expression compiles against the
fixed names plus the node's (`Transform.CelKey`: source and typed names), so a typo, an unknown
name or `lights_on + ' on'` (no `int + string` overload) fails the BUILD. Refused too: a name that
is not a CEL identifier, one hiding `state`/`attr`/`entity_id`/`domain`/`dashboard_slug`, a value
no slot reads, and a slot read `once` that reads a count — the once-cache keys by what a value IS,
so it would show the first count forever.

**Node-local names, shared by content.** Nothing below the node sees its names. That is a node
variable's job (ADR 0033), and a different fact: a viewer's choice, inherited, against a computed
value, not. Sharing comes from content instead — a tally is its candidates and residuals — so two
nodes counting the same lights under different names count the same thing.

**Attached at decode.** `LayoutNode.foldNode` puts each value onto every slot whose CEL names it
(`SlotSource.values`), by the identifiers the expression PARSES to, so a word inside a string
literal is not a read. A slot then says alone what it reads, and the compile key, the signal name
and the watched entities all come off it; no render path takes the node's values as an argument.

**What is shared with `c.iff`, and what is not.**

| | |
|---|---|
| Which entities can move it | Shared: `Predicate.Tally.referencedEntities` — the candidates and the entities their residuals name. They join the node's `liveEntities` (the reverse index that wakes a page node) and `referencedEntities`, so the watched set the upstream subscription asks for (ADR 0030) and ADR 0023's bound. |
| Evaluating it | Shared: `Conditions.present`, the one interpreter. A `Count` is a tally compared to a number. |
| What fires | Not shared, deliberately. A flipped condition swaps a surface (`SurfaceGraph.affectedStateGroups`); a moved count patches a slot. Two jobs, two paths. |

**The server computes the text.** The whole expression is evaluated on the server and sent the way
its slot is carried. On `secondary`, which every card carries as a text signal when it is live
(ADR 0017), that is one entry in an ordinary `datastar-patch-signals` frame. The slot's `valueKey`
carries its values, so the signal is the ordinary display path and two nodes saying the same thing
over the same values share one name; a tick that leaves the count alone sends nothing, because the
frame is diffed against what each session holds.

## Rejected

- **A reading built from the count, with implicit names.** The first proposal,
  `q.from(…).where(…).reading("… count … total …")`, gave the expression `count`, `total` and
  `names` it never declared, fused WHICH set with WHAT text so two texts over one set could not
  share, and built a slot behind the author's back.
- **Send the count, let the browser compose the text.** The same expression in CEL for the
  document form and in JS for the patch, which can disagree, and a browser without JS gets only
  the first.
- **Slot references in expressions.** Slots reading slots is a dependency graph with an order and
  cycles. Values are flat: slots read values, values read only state.
- **Resolving names up the ancestors.** Content addressing already shares the work, and inheritance
  would make a node's expression depend on where it is placed.

## Consequences

- A slot can now reach other entities than its own, but only as numbers a node declared.
- A tally whose candidates the build settled has no residuals; the runtime counts an unguarded
  candidate as present, so no build-time fold is needed. Those candidates still wake the node —
  a recompute whose unchanged result the frame diff swallows — the same cost a settled `Count`
  has.
- Not built, and why: a `names` value (the present members' names — a list, so a second CEL type
  and an order to decide); a per-tick cache of evaluated tallies shared with `c.iff` (counting a
  floor is trivial; add it when measured); string interpolation (CEL has none).
- `ExpressionValuesSuite` holds the behaviour from a real Pkl entry: the document, a flip sending a
  frame and not the line, a brightness tick sending nothing, shared and distinct signals, a popup,
  a candidate-set member, and the build refusals.
