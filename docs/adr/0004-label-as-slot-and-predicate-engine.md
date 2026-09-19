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
  subject — the magical `entity_id` slot (ADR 0001). A card binds its entity
  once; every slot reads it. A set's clause node carries that slot as a literal,
  because the build knows the candidate.
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
it). This keeps a member re-render cheap: its action slots are cache lookups, not
JSONata evals. Live slots always re-resolve.

The slider's config used to be the biggest user of this — `action`/`key`/`min`/
`max` each rode as a `$lookup($domain)` because a query group's member was
unknown until it matched. Over static candidates all four are LITERALS, which
beats a memoized transform outright (ADR 0003).

**Labels are slots, everywhere.** A single `label` argument carries both forms
— a plain **string** becomes a literal slot; `c.expr('<jsonata>')` a live
expression bound to the entity; absent, the entity's friendly_name is baked as a
literal. That last case used to have a second branch: a `$self` sentinel got a
live `friendly_name ? … : $entity_id` default, because a query group's member
had no name at build time. Every candidate has one now, so the default is always
the literal. The `string | c.expr(...)` convention spans every display field
(`label`, `value`, `secondary`, the slider position); `c.expr` is just a partial
`SlotSource`, not field-specific.

## Predicates stay an AST — JSONata rejected there

Presence guards, count comparisons and surface conditions (`And/Or/Not/Cmp/
Count`, `Renderer.matchesIn`) are **not** JSONata, although slot values are:

- `matchesIn` reads `state`/`attributes.get(name)` straight off the Scala map —
  a pattern match, zero conversion.
- A JSONata predicate would build a Java attribute document and walk an
  expression tree per evaluation — strictly more work.

The ORIGINAL argument was stronger and no longer applies: a query group had no
reverse index, so an affecting change re-scanned every entity in the house, and
that was the loop scaling worst (`entities × events/sec`). Candidate sets
removed the scan (ADR 0003) — presence is evaluated for the candidates a change
can actually move. So the AST is now kept on the narrower ground above, plus the
one that matters more: a predicate is DATA the build can fold, dedupe and reason
about, which is what lets a registry term disappear at build time. A JSONata
string is opaque to all of that.

JSONata stays for slot values, where the set is bounded (only rendered cards).

## Attribute conversion is memoized per state version

`Transform.run` needs the entity's attributes as Java values. The conversion
lives on `EntityState.javaAttributes`, a `lazy val` computed **once per state
version** (a fresh `EntityState` is built on every change, so the cache
self-invalidates). A card with three `$attr` slots converts once; the win
applies to every transform.

## Consequences

- One authoring vocabulary: every card input is a slot (literal / inherited /
  own-entity), the subject is the `entity_id` slot, and the only non-slot
  template var is the backend-injected `id`.
- `bypassUnavailable` defaults **true** (value displays stay readable on
  `unavailable` without opting in); identity actions, labels, and the slider
  position opt out in the builders (ADR 0001).
- The cost model is explicit: a cheap foldable AST for presence and conditions,
  JSONata for displayed values, identity slots memoized, attribute conversion
  amortized per state version.
