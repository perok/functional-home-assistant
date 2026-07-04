# ADR 0004 — The slot model; AST (not JSONata) for queries; attribute memoization

- **Status:** Accepted
- **Date:** 2026-06-24 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Early versions split a card's inputs into `params` (static) and `slots` (live),
baked the label into an opaque param string, and handed dynamic cases a decoy
entity that mocked every field a builder read. That caused real bugs (a
per-case `label` override was silently lost to the matched entity's
friendly_name) and two hand-maintained lists that silently tracked builder
internals. The fixes converged on **one vocabulary — the slot** — and this ADR
records the resulting model, plus two performance decisions that shape the hot
path.

## The slot model

`SlotSource` is `{ entityId, transform = "$state", default,
bypassUnavailable = true, literal, reactive = true }`. A slot is one of:

- **A literal** (authored as a bare JSON string): a hardcoded value used
  verbatim — no entity, no JSONata, no compilation. Hardcoded labels, `min`/
  `max`, constant action URLs.
- **An inheriting transform** (`entityId: None`): binds to the component's
  subject — the magical `entity_id` slot (ADR 0001) — or, in a dynamic case,
  the matched entity injected per render. A card binds its entity once; every
  slot reads it.
- **An own-entity transform** (`entityId: Some(other)`): the multi-entity card
  (`c.exprOf(other, …)` makes a `value`/`secondary` read another entity); the
  card joins both entities' live-dependency sets.

**The live-dependency set is derived, not declared.**
`Component.liveEntities` derives from the slots: a slot contributes its entity
iff it is non-literal **and `reactive`**. The reverse index and the
morph-wrapper decision read this — adding a live slot is all it takes to make
a component track an entity.

**`reactive: false` ⇒ identity-only, resolved once.** A slot that reads its
entity for identity only (an onclick/action, the slider's `$domain` config) is
a pure function of `$domain`/`$entity_id`: it stays out of `liveEntities`, and
the renderer resolves it **once per `(entity, transform)`** and memoizes
(`Renderer.identityCache`; `$entity_id` is in the key since action URLs embed
it). This is what keeps the dynamic render path cheap — a group re-render's
action/config slots are cache lookups, not JSONata evals. Live slots always
re-resolve.

**Labels are slots, everywhere.** A single `label` argument carries both forms
— a plain **string** becomes a literal slot; `c.expr('<jsonata>')` a live
expression bound to the entity; absent, a static dump entity bakes its
friendly_name as a literal while the `$self` sentinel gets the live
`friendly_name ? … : $entity_id` default. The `string | c.expr(...)`
convention spans every display field (`label`, `value`, `secondary`, the
slider position); `c.expr` is just a partial `SlotSource`, not field-specific.
Because a per-case literal label carries no entity, `renderCase` never rebinds
it — the author's override survives dispatch (the motivating bug).

## Queries stay a Predicate AST — JSONata rejected there

Dynamic-group membership/dispatch predicates (`And/Or/Not/Cmp`,
`Renderer.matches`) are **not** JSONata, although slot values are. The cost
model is the seam:

- A dynamic group has no reverse index — an affecting change re-scans **every**
  entity (`renderDynamic` filters all states). This is the loop that scales
  worst (`entities × events/sec`).
- `Renderer.matches` reads `state`/`attributes.get(name)` straight off the
  Scala map — a pattern match, zero conversion.
- A JSONata predicate would, per entity per scan, build a Java attribute
  document and walk an expression tree — strictly more work in exactly the
  wrong loop.

JSONata stays for slot values, where the set is bounded (only rendered cards).
Nothing was lost by keeping the AST — dynamic groups can't be reverse-indexed
regardless.

## Attribute conversion is memoized per state version

`Transform.run` needs the entity's attributes as Java values. The conversion
lives on `EntityState.javaAttributes`, a `lazy val` computed **once per state
version** (a fresh `EntityState` is built on every change, so the cache
self-invalidates). A card with three `$attr` slots converts once; the win
applies to every transform.

## Consequences

- One authoring vocabulary: every card input is a slot (literal / inherited /
  own-entity), the subject is the `entity_id` slot, and the only non-slot
  template vars are backend-injected (`id`, the matched `entity_id` in dynamic
  cases).
- `bypassUnavailable` defaults **true** (value displays stay readable on
  `unavailable` without opting in); identity actions, labels, and the slider
  position opt out in the builders (ADR 0001).
- The cost model is explicit: cheap AST for the per-event membership scan,
  JSONata for displayed values, identity slots memoized, attribute conversion
  amortized per state version.
