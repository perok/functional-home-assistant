# Plan — expression values: named inputs a node's expressions read

Builds on ADR 0003 (candidate sets, the build-time fold), ADR 0007 (state conditions), ADR 0017
(signal slots), ADR 0023 (what a dashboard may call and read) and ADR 0015 (`secondary`, the tiers).

## Why

A slot reads ONE entity: its CEL sees that entity's `state`, `attr`, `entity_id`, `domain` and
the `dashboard_slug` (`Cel.scala`), and a card is woken only by the entities its slots name
(`Component.liveEntities`). So "3 of 5 lights on" under a floor's button has no spelling. The
count itself exists — `q.from(xs).where(…).count()` folds to a static candidate list with a
per-candidate residual (`Predicate.Count`) — but only as half of a COMPARISON, for `c.iff` and
the aggregates.

## The design

A node DECLARES named values; its slots' expressions read them by name.

```pkl
local lights = hass.lights(f.all)

((c.button(f.floor_name, c.tap.lights.off(f))) {
  expressionValues {
    ["lights_on"] = q.from(lights).where(q.eq(q.stateProp, "on")).count()
    ["lights_total"] = lights.length
  }
}).secondary(c.expr(#"string(lights_on) + ' / ' + string(lights_total) + ' on'"#))
```

**The type.** In `core/`, because `Node` may not depend on `query.pkl` (ADR 0015):

```pkl
// core/predicate.pkl, beside the other wire classes
class Tally {
  candidates: Listing<String>
  `when`: Mapping<String, Predicate> = new {}
}

// core/node.pkl
typealias ExpressionValue = String|Int|Float|Boolean|pred.Tally
expressionValues: Mapping<String, ExpressionValue>? = null
```

`CountRef` (query.pkl) becomes a `pred.Tally`, so `.count()` goes in as it is and `.gt(2)` still
builds a `Count`. `Count` cannot also extend `Tally` — it extends `Predicate`, and Pkl has single
inheritance — so it keeps its flat `candidates`/`when` (its wire shape) and is BUILT from a
tally. A count the build already settled is an `Int`, the same fold `knownCount` does for a
comparison today.

**Each value is a typed CEL variable** of the node's expressions: String → `string`, Int →
`int`, Float → `double`, Boolean → `bool`, Tally → `int`. The expression compiles against the
fixed variables plus the node's names, so a typo, an unknown name or `lights_on + ' on'` (no
`int + string` overload — measured against the pinned cel 0.14.0) fails the BUILD. Refused too: a
name that is not a CEL identifier, one that shadows `state`/`attr`/`entity_id`/`domain`/
`dashboard_slug`, and a value no slot of the node reads.

**Node-local names, shared machinery.** The names belong to the node; nothing resolves them up
the ancestor chain (that is what a node variable does, and it is a different fact: a viewer's
choice, not a computed value). Sharing comes from CONTENT instead: a tally is identified by its
candidates and residuals, so two nodes counting the same lights under different names are the
same tally.

What is shared with `c.iff` and the aggregates, and what is not:

| | |
|---|---|
| Which entities can move it | Shared: `Predicate.referencedEntities` on the tally — candidates plus the entities their residuals name. |
| Evaluating it | Shared: `Count` becomes "a tally compared to a number", and `Conditions` gains the one interpreter both call. |
| What fires | Not shared, deliberately. A flip swaps a surface (`SurfaceGraph.affectedStateGroups`); a value patches a slot through the component path (reverse index → `signalFrame` diffed against holds). Two jobs, two paths. |

**It goes out as a signal.** A slot whose expression reads an expression value is a signal slot
(ADR 0017): the server evaluates the whole expression and sends the finished text in an ordinary
`datastar-patch-signals` frame. Not "send `lights_on` and let the browser assemble the text" —
that is the same expression in CEL (for the document form) and JS (for the patch), which can
disagree, and a JS-less browser would get only the first. The signal is a display signal named
by content (`_v.<hash of transform + the values it reads>`), so two nodes saying the same thing
share one name, and a brightness tick that leaves the count alone sends nothing: the frame is
diffed against what each session holds.

**What the node watches.** A slot reading values adds each tally's referenced entities to the
node's `liveEntities` (signal slot: not to `liveEntitiesAsBytes`), and the dashboard's
`watchedEntities` and `referencedEntities` take them too — unwatched, a change never arrives;
unreferenced, ADR 0023's bound would refuse a read the dashboard does make.

## Steps (one stacked PR each)

1. **This plan.**
2. **`Tally` under `Count`.** Both sides: `pred.Tally` in Pkl, `CountRef` extending it and
   building its `Count` from it; a Scala `Tally` that `Predicate.Count` exposes, and
   `Conditions.present` as the one interpreter. No behaviour change and no wire change — the
   snapshots are the proof.
3. **A live `secondary` is a signal.** When `secondary` is a reading, the tile, the button and
   `sliderText` carry it as a text signal (`textMod.liveRun`), guarded by `secondary__has` and
   never by the value — a blank patch-form value is a false section (ADR 0017's "this bit").
   Every existing `c.expr`/`c.exprOf` secondary gets cheaper on the way. A wire change: the
   slot's `signal`, and `sliderText`'s template.
4. **`expressionValues`.** The Pkl type and builder, the wire field on `Component`, `validate`
   (names, shadowing, unread values, CEL compiled with the node's variables), the renderer
   (values resolved once per render, the content-named signal), and the watched/referenced
   entity sets. Tests: the facts, `validate` refusals, and a `SignalSlotSuite`-style NEGATIVE
   contract — a brightness tick on a counted light sends nothing, an on/off flip sends one entry.
   `docs/terminology.md` gains **Expression value**; the architecture doc's slot section moves.
5. **Close.** This plan deleted once the maintainer agrees; whether the decision wants an ADR of
   its own (or a section of 0017) is asked then.

## Not in scope

- Values reading values. Flat on purpose: slots read values, values read only state, so there
  is no evaluation order and no cycle to detect.
- A per-tick cache of evaluated tallies shared with `c.iff`. Counting a floor's lights is
  trivial; add it when a measurement says so.
- `names` (the present members' friendly names) as a value kind — a list, so a second CEL type
  and an order to decide. Next, if the count proves the shape.
- String interpolation. CEL has none; `string(x) + …` is the spelling.
