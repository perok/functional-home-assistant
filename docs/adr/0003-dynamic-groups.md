# ADR 0003 — Dynamic groups: live membership + per-entity card dispatch

- **Status:** Accepted
- **Date:** 2026-06-24 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Most of a dashboard is **static**: each card is composed at build time against
a concrete entity from the dump, so the set of cards is fixed. But some
sections are inherently **live sets** — membership and the card chosen per
member depend on runtime state:

- "every light that is currently **on**";
- "every `device_class: battery` sensor under **20%**";
- a mixed set where the **card differs per entity** (a light gets a slider,
  anything else a read-only card).

Phase discipline (ADR 0001) means a dynamic group cannot be a build-time
comprehension over live state — it is authored as **data** the renderer
evaluates against live `EntityState`.

## The design

### The model: `LayoutNode.Dynamic(query, cases)`

- `query` is a small **Predicate AST** — `And`/`Or`/`Not`/`Cmp`, where `Cmp`
  compares a property (`domain`, `state`, or `attr:<name>`) with an op
  (`eq/ne/lt/lte/gt/gte`) — evaluated per entity against live state
  (`Renderer.matches`). The AST (not JSONata) is a deliberate performance
  choice — see ADR 0004 for the cost model.
- The renderer filters all entities by `query`; each match renders with the
  **first `case` whose `when` predicate matches** (an entity matching no case
  renders nothing).
- Per match the renderer injects the node `id` and sets the matched entity as a
  literal `entity_id` slot, so every inheriting slot — including the default
  live label (`$attr.friendly_name`, falling back to the id) — binds to the
  match; a slot naming its own entity, or a constant literal, is untouched.

The model has been stable across all authoring-layer iterations — churn lands
in the component library, not in Scala.

### One builder set for static and dynamic

A case is built with the **same leaf builders** as a static card, passing the
sentinel `{ entity_id: '$self' }` (jsonnet `d.matched`; Pkl `hass.SELF`);
`'$self'` is rebound to each match at render time. `dynamic.case(when, node)`
keeps the node's `card` + `slots` and strips only the build-time `entity_id`.
There is no parallel `dyn*` builder set to keep in sync, and no per-case decoy
fields to mock (the label rides in `slots` — ADR 0004).

### Membership and dispatch are separate

- `dynamic.group(query, card)` renders one `card` per matching entity;
- `card` is a single leaf, or a `dynamic.when([...cases...], fallback=...)`
  selector when the card must vary per entity.

Both forms lower to the same `Dynamic(query, cases)` — a plain leaf becomes a
single `always` case. The whole vocabulary (predicate helpers `whenDomain`/
`whenState`/`whenDeviceClass`/`attrLessThan`/`lowBattery`/…, `matched`,
`case`/`when`/`group`) is namespaced under `c.dynamic` (jsonnet) / plain typed
functions (Pkl, ADR 0006); the leaf builders stay top-level because both static
and dynamic use share them.

### Re-render only when a change touches the query

Membership is data-dependent, so a dynamic container can't be reverse-indexed
by entity like static components. But it does **not** re-render on every
event: the state stream carries `StateChange(entityId, previous, current)`, and
a group re-renders only when the changed entity matched its query **before or
after** the change (`Renderer.affectedDynamicIds`) — covering add (¬prev ∧
cur), remove (prev ∧ ¬cur), and in-place update (prev ∧ cur), while an
unrelated entity's change is skipped (the group's HTML would be identical).

This test is **stateless** — no per-group membership cache. A shared member-set
cache would couple to the rendered-HTML diff caching (a connection that hasn't
rendered a removal yet must not skip it); testing old-or-new match needs no
shared state and is correct regardless of where the diff cache lives. The
assumption it rests on: a dynamic card binds to its matched entity (no
cross-entity slots inside a case) — the current invariant.

A re-render of a group re-renders every matched card, but the cards'
action/config slots are memoized identity slots (ADR 0004), so it costs ~2 live
JSONata evals per card. **Future work** (flagged at the change loop in
`Server`): coalesce/debounce event bursts — the query filter bounds *what*
re-renders; batching would bound *how often*.

## Consequences

- One card library serves static and dynamic rendering; a new card type needs
  no dynamic counterpart.
- Keep groups scoped by a selective `query` — the membership scan is
  O(entities) per affecting change.
- Verified by `BuildPhaseSuite`/`PklBuildSuite` (evaluate → hoist → validate)
  and `RendererSuite` (query filtering, per-case dispatch, slot rebinding,
  constant-label override survival).
