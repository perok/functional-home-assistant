# Plan: sets of areas and floors

A call can leave out an area or a floor while a condition holds, written exactly like a set of
entities, and HA expands the area at the press:

```pkl
local sofieWhileDisco = q.from(List(dump.areas.sofies_soverom))
  .where((a) -> q.entity(dump.entities.light_disco).stateIs("on"))

c.tap.lights.off(dump.floors.inngangsetasje).exclude(sofieWhileDisco)
```

While the disco light is on, HA gets `exclude: {area_id: ["sofies_soverom"]}`; otherwise the call
has no `exclude`. A light added to the room later is covered, since HA resolves the area. The same
set works as a call's target.

## Why `Present`, not a new step

A set's ids already live in one place: `Value.Present(candidates, when)` (Pkl `pred.Tally`), the
candidates whose guard holds. The press evaluates it and `Renderer.ids` sends what it gives. It is
entity-only in three ways, and that is all this changes:

- `ValueExpr.entities` lists its candidates, so they are watched and indexed as entities;
- `Evaluator.present` counts a guarded candidate only while its own entity state exists, so an area
  could never be present;
- the Pkl side reads `entity_id` off every item, and `tap.pkl`'s `targetKey` sends every set as
  `entity_id`.

Widening `Present` to say what its ids are is the first-class version: no `When` step, no CEL, and
the syntax is the one a set of entities already has.

## A. Scala: a `Present` knows what its ids are

- `Value.Present(candidates, when, of: Present.Of = Present.Of.Entity)`, with
  `enum Of { Entity, Area, Floor }`. On the wire `of` is `"area"` or `"floor"`, absent for
  entities, so every entity set's wire, snapshot and call id stay byte-identical (Pkl's
  `JsonRenderer` omits nulls).
- `entities`: the candidates only for `Entity`. An area or floor set moves only by what its guards
  name.
- `Evaluator.present`: for `Area` and `Floor`, a guard runs with no subject and needs no state of
  its own.
- `inputKey`: adds the kind only when it is not `Entity`.
- `validate`:
  - a guard of an area or floor set that reads its member is refused: an area has no state, so
    the guard names an entity;
  - in a call, a set sits under its own key (an area set under `area_id`, a floor set under
    `floor_id`, an entity set under `entity_id`).
- Typing is unchanged: `list(string)`.

## B. Pkl: `q.from` takes areas and floors

- `q.from(xs: List<hass.Entity>|List<hass.Area>|List<hass.Floor>)`. The set's items widen to all
  three, read through one id helper, and `pred.Tally` gains `of: ("area"|"floor")? = null`.
- `where`: a lambda term and a constant fold as they do now. A term that reads the member is a
  build error on an area or floor set (in Pkl where it can tell, else in `validate`, A).
- The entity-only methods on an area or floor set (`render`, `caseOf`, `cases`, `else`, `orderBy`,
  `first`, `limit`) are a build error naming the method.
- `tap.pkl`: `targetKey` reads a tally's `of`. The "set of another domain" check
  (`c.tap.lights.off(set)`) skips area and floor sets.
- **Spike first:** pkl-lsp still completes `where` after `q.from(List(dump.areas.x)).` once `from`
  takes the union (`docs/pkl-editor-support.md`; add a `PklEditorSupportSuite` case).

## C. Tests

- Pkl facts: an area set lowers under `area_id` with `of`, a floor set under `floor_id`; an entity
  set is unchanged and carries no `of`.
- Functional: a floor's lights off with an area excluded while a fixture entity is on sends
  `exclude.area_id`; with it off, no `exclude`. The same for a floor set, and for an area set as
  the target.
- `validate`: a member read in an area guard, and a set under another kind's key, are refused.
- `WireShapeSuite` for the new field. Snapshots and goldens do not move, except a new `taps`
  fixture node.
- Perf: `Evaluator.present` is on the render path, so RenderBench and ValidateBench before and
  after.

## D. Docs

ADR 0003 (a call's set may be of areas or floors), ADR 0023 (`exclude`), ADR 0035 (`Present`),
`terminology.md`, `writing-dashboards.md` (excluding a room while something holds), and the module
`CLAUDE.md`'s `query.pkl` line. Then the local workspace's popup switches to the area set.

## Open questions

1. An area or floor set outside a call (`.count()`, `.any()`, `.present()`): allow it or refuse
   it? Counting is not entity-specific, so I lean to allowing it.
2. A list mixing areas and floors in one set: refuse it, one kind per set (write two sets).
3. An area set as the target (`c.tap.lights.off(areaSet)`) falls out for free. Keep it, or limit
   area sets to `exclude` for now?
