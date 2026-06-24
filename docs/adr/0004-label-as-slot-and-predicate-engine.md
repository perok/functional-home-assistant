# ADR 0004 — Label as a slot; AST (not JSONata) for dynamic queries; attribute-conversion memoization

- **Status:** Accepted
- **Date:** 2026-06-24
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)
- **Supersedes:** parts of [ADR 0003](0003-dynamic-groups.md) — decision 2's
  `matched` decoy shape and the `case`-strip (see below). 0003's overall model
  (`LayoutNode.Dynamic(query, cases)`, the group/when split, the namespacing)
  stands.

## Context

ADR 0003 unified static and dynamic cards on one builder set via a `matched`
sentinel — a **decoy entity** (`{ entity_id: '$self', friendly_name: '' }`)
handed to a leaf builder, after which `dynamic.case` **stripped** the params the
renderer re-injects (`entity_id`, `label`). Two problems surfaced:

1. **The decoy is coupled to builder internals.** It must mock every field a
   builder reads off an entity (today `entity_id` and `friendly_name`), and the
   `case`-strip must list every injected param. Two hand-maintained lists that
   silently track the builders.
2. **An author can't fix a per-case `label`.** Because `case` stripped `label`,
   `c.entityCard(d.matched, label='Foo')` lost the `'Foo'` — the matched
   entity's friendly_name always won. The label was the contested param, and the
   build-then-strip round-trip existed only because of merge order.

The root asymmetry: a slot carries its `entity` as **structured data** the
renderer can rebind, whereas `label` baked the entity into an **opaque param
string**, so a real author value was indistinguishable from decoy fallout.

A separate, related question came up: should the dynamic-group **membership /
dispatch predicates** also move to JSONata (which we already use for slot
values), deleting the custom Predicate AST?

## Decisions

### 1. `label` is a slot, not a param

For entity-bound cards (`entityCard`, `button`/`toggle`, `slider`), `label`
moves from `params` to a `label` **slot**, resolved by the same JSONata
machinery as `value`/`secondary`. This makes a label first-class: it can be
**live** and entity-derived (e.g. `'$round($number($state),1) & "°"'`), not just
static text. `sectionTitle` keeps `label` as a plain param — it has no entity, so
a transform would be pure waste. *label is a slot exactly where it can derive
from an entity.*

A **single** `label` argument carries both forms — there is no separate
`labelTransform` (an early split that grew a parallel `<field>Transform`
parameter and diverged from `value`'s `transform=`). The jsonnet
`labelSlot(eo, label)` helper (replacing `nameOf`) chooses the slot's shape:

- `label` a **string** → a **constant** slot (`{ transform: '"…"' }`, no
  `entityId`) — the common literal case, no ceremony.
- `label = c.expr('<jsonata>')` → a **live** expression bound to the entity
  (`{ entityId, transform: <expr> }`) — e.g.
  `c.expr('$attr.friendly_name & " " & $state')`. `c.expr(s):: { transform: s }`
  is a reusable wrapper (the same one can front other display fields later).
- no `label`, the **match** sentinel (`$self`) → `{ entityId: '$self',
  transform: '$attr.friendly_name ? … : $entity_id' }` — the matched entity's
  live name, rebound per match, falling back to its id.
- no `label`, a **static dump entity** → its friendly_name baked as a
  **constant** (no entity, no live lookup) — the cheap common-case default.

### 2. A slot's `entityId` is optional (`None` ⇒ constant)

`SlotSource.entity: String` became `entityId: Option[String] = None`. `None`
marks a **constant** slot whose transform reads no entity (a literal label). The
renderer resolves it against an empty state — so it allocates **no attribute
context** — and `renderCase` **does not rebind** it to the matched entity. This
is what makes a per-case literal `label` survive (problem 2 above), and what
keeps a constant label off the per-render attribute-conversion path (a perf win,
not just tidiness — see decision 4). Entity-bound slots (value/action/`$self`
labels) carry `Some(id)` and are rebound as before.

Consequently the `matched` decoy shrinks to `{ entity_id: '$self' }` (no
`friendly_name`), and `dynamic.case` strips only `entity_id` — the label now
rides in `slots`. The renderer no longer injects a `label` param;
`Dashboard.injectedDynamic` drops it.

### 3. Keep the Predicate AST for queries; reject JSONata there

The dynamic-group membership query and per-case `when` **stay** the custom
Predicate AST (`And/Or/Not/Cmp`, `Renderer.matches`); they are **not** moved to
JSONata. Rationale is performance, and it is specific to where each runs:

- A dynamic group has **no reverse index** — it re-scans **every** entity on
  **every** `state_changed` event (`renderDynamic` filters all states). This is
  the hottest loop in the system, and HA fires state changes constantly.
- `Renderer.matches` reads `state`/`attributes.get(name)` straight off the
  existing Scala `Map` — a pattern match, zero conversion.
- A JSONata predicate would, per entity per scan, build a fresh Java attribute
  document (see decision 4) and walk an expression tree — strictly more work, in
  exactly the loop that scales worst (`entities × events/sec`).

JSONata stays for **slot values** (including the new label), where the set is
bounded (only rendered cards) and already JSONata. The seam is: cheap AST for
the `N×`-per-event membership scan; JSONata for the small set of values actually
displayed. (We also lose nothing by keeping the AST — dynamic groups can't be
reverse-indexed regardless, so there was no structure-introspection benefit
JSONata would have cost us.)

### 4. Memoize the attribute JSON→Java conversion on `EntityState`

`Transform.run` previously converted the entity's full attribute map to Java
values (`attrObject`/`toJava`) on **every** evaluation — even for expressions
that never touch `$attr`, and once per slot. For fat attribute maps (lights,
climate) this dominated per-eval cost. The conversion moved to
`EntityState.javaAttributes`, a `lazy val` computed **once per state version**
(a fresh `EntityState` is built on every change, so the cache self-invalidates).
A card with three `$attr` slots now converts once, not three times, and the win
applies to **every** transform, not just labels.

## Consequences

- The `matched` decoy and the `case`-strip's `label` handling are gone; a
  per-case `label` (or any param) is now author-controllable. The decoy carries
  one field (`entity_id`), not a growing list of mocked attributes.
- Labels can be **live** (`label=c.expr('…')`) through the same `label`
  argument that takes a plain string, folding label into the same value
  vocabulary as the rest of the card; the common static label is a cheap
  constant slot with no entity binding.
- The `string | c.expr(...)` convention spans every display field:
  `entityCard`'s **`label`, `value`, `secondary`** (replacing the old
  `attribute=`/`transform=` params) and the **`slider`'s position** (replacing
  its positional `attr` + `transform=`). A plain string is the field's shortcut
  (label → literal text; value/secondary/slider → an attribute), and
  `c.expr('…')` is a live expression everywhere. The shared decode is the
  `exprOrAttr` helper; unit auto-appending stays an entity-card `value`-only
  display nicety (the slider's position is a bare number for its range input).
  This is purely an authoring convenience over the already-generic slot model —
  every slot is one `SlotSource` (entity + transform), so `c.expr` is just a
  partial `SlotSource` and is not field-specific.
- Dynamic queries keep their fast path; the cost model is explicit (AST for
  membership, JSONata for values).
- Attribute conversion is amortized per state version across all slots/evals.
- Verified by `fh-datastar-view/testFull` (47): a constant slot resolves against
  empty state; a dynamic case's constant label override is **not** rebound to
  the match (the motivating bug); the matched-entity friendly_name path still
  renders live; `EntityState.javaAttributes` is identity-stable; and the
  build-phase e2e still evaluates the real `components.libsonnet` → hoist →
  validate with `label` as a required slot. Not browser-tested live.
