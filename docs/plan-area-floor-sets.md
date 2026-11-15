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

## A. Scala: a set knows what its ids are

- One enum for what an id names, `IdKind { Entity, Area, Floor }`, each with its HA key
  (`entity_id`, `area_id`, `floor_id`). It replaces the call's string keys
  (`CallDecl.target`/`exclude` become `ListMap[IdKind, List[ValueExpr]]`, `CallDecl.TargetKeys`
  goes), so "a set sits under its own key" compares two `IdKind`s. `CallId` hashes the key's wire
  name, so no call id moves. Device and label targets later are one more case.
- `Value.Present(candidates, when, of: IdKind = IdKind.Entity)`. On the wire `of` is `"area"` or
  `"floor"`, absent for entities, so every entity set's wire, snapshot and call id stay
  byte-identical (Pkl's `JsonRenderer` omits nulls).
- `LayoutNode.SetNode` gets the same `of`, so a rendered set of areas or floors is a set like any
  other. Its presence already runs guards with no subject (`MemberGraph.memberOf`), so only what
  treats its candidates as entities changes: `referencedEntities`, `watchedEntities`, the wake
  index (`movedBy`).
- `entities`: the candidates only for `Entity`. An area or floor set moves only by what its guards
  name.
- `Evaluator.present`: for `Area` and `Floor`, a guard runs with no subject and needs no state of
  its own.
- `inputKey`: adds the kind only when it is not `Entity`.
- `validate`:
  - a guard, clause `when` or order term of an area or floor set that reads its member is refused:
    an area has no state, so the guard names an entity. Ordering by a static property
    (`area_name`) folds at build and is fine;
  - in a call, a set sits under its own key (an area set under `area_id`, a floor set under
    `floor_id`, an entity set under `entity_id`).
- Typing is unchanged: `list(string)`.

## B. Pkl: `q.from` takes areas and floors

- `q.from(xs: List<hass.Entity>|List<hass.Area>|List<hass.Floor>)`. The set's items widen to all
  three, read through one id helper, and `pred.Tally` gains `of: ("area"|"floor")? = null`.
- `where`: a lambda term and a constant fold as they do now. A term that reads the member is a
  build error on an area or floor set (in Pkl where it can tell, else in `validate`, A).
- One set class for all three kinds, the same API: `render`, `caseOf`, `orderBy`, `first` and
  `limit` work on a set of areas as on a set of entities (the render lambda takes the area). No
  split class: a rendered room set gives what a `for` with `c.iff` gives, so there is nothing to
  forbid, and one class keeps completion as it is.
- One id helper in core for the three `if is Area / is Floor` ladders (`tap.pkl`'s `targetKey` and
  `targetId`, and the set's ids). No base class over `hass.Entity`/`Area`/`Floor`: an entity's own
  `area_id` is where it lives, not how it is targeted, so no field name fits all three.
- `tap.pkl`: `targetKey` reads a tally's `of`. The "set of another domain" check
  (`c.tap.lights.off(set)`) skips area and floor sets.
- Not Pkl generics: 0.33 makes methods generic, not classes, so `from<T>` would still return one
  class and `where`'s lambda would keep its declared type.

## C. Tests

- Pkl facts: an area set lowers under `area_id` with `of`, a floor set under `floor_id`; an entity
  set is unchanged and carries no `of`.
- Functional: a floor's lights off with an area excluded while a fixture entity is on sends
  `exclude.area_id`; with it off, no `exclude`. The same for a floor set, and for an area set as
  the target.
- A rendered set of areas: a room's card is shown while its guard holds, and gone when it fails.
- `validate`: a member read in an area set's guard, clause or order term, a mixed list, and a set
  under another kind's key, are refused.
- `WireShapeSuite` for the new field. Snapshots and goldens do not move, except a new `taps`
  fixture node.
- Perf: `Evaluator.present` is on the render path, so RenderBench and ValidateBench before and
  after.

## D. Docs

ADR 0003 (a set's candidates may be areas or floors), ADR 0023 (`exclude`), ADR 0035 (`Present`),
`terminology.md`, `writing-dashboards.md` (excluding a room while something holds), and the module
`CLAUDE.md`'s `query.pkl` line. Then the local workspace's popup switches to the area set.

## Decided

1. An area or floor set works outside a call too: `.count()`, `.any()`, `.none()`, `.all()` and
   `.present()`. Counting is not entity-specific.
2. One kind per set: a list mixing areas and floors (or either with entities) is refused at build.
   Write two sets.
3. An area or floor set is a valid target as well as an `exclude` (`c.tap.lights.off(areaSet)`):
   HA expands what the press sends.
