# ADR 0004 — The slot model; AST (not an expression) for queries; attribute memoization

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

A slot's value is a `ValueExpr`, `{source, steps, default}`, whose entity
source carries `of`, `part`, `reads` and `bypassUnavailable`. A slot is one of:

- **A literal** (authored as a bare JSON string): a hardcoded value used
  verbatim — no entity, no expression, no compilation. Hardcoded labels, `min`/
  `max`, constant action URLs.
- **An inheriting reading** (`of: None`): binds to the component's
  subject — the magical `entity_id` slot (ADR 0001). A card binds its entity
  once; every slot reads it. A set's clause node carries that slot as a literal,
  because the build knows the candidate.
- **An own-entity reading** (`of: Some(other)`): the multi-entity card
  (`c.exprOf(other, …)` makes a `value`/`secondary` read another entity); the
  card joins both entities' live-dependency sets.

**The live-dependency set is derived, not declared.**
`Component.liveEntities` derives from the slots: a slot contributes its entity
iff it is non-literal **and `reactive`**. The reverse index and the
morph-wrapper decision read this — adding a live slot is all it takes to make
a component track an entity.

**`reactive: false` ⇒ identity-only, resolved once.** A slot that reads its
entity for identity only (an onclick/action) is a pure function of
`domain`/`entity_id`: it stays out of `liveEntities`, and the renderer resolves
it **once per `(entity, transform)`** and memoizes (`Renderer.identityCache`;
the entity is in the key since action URLs embed it). This keeps a member
re-render cheap: its action slots are cache lookups, not evaluations. Live
slots always re-resolve.

Where the build knows the entity, a literal beats a memoized transform
outright: the slider's `action`/`key`/`min`/`max` are literals even inside a
candidate set, because every candidate is known (ADR 0003).

**Labels are slots, everywhere.** A single `label` argument carries both forms
— a plain **string** becomes a literal slot; `c.expr('<cel>')` a live
expression bound to the entity; absent, the entity's friendly_name is baked as a
literal, since every candidate has a name at build time. The `string | c.expr(...)` convention spans every display field
(`label`, `value`, `secondary`, the slider position); `c.expr` is just a
reading, not field-specific.

## The library writes conditions as data, not CEL

Presence guards, count comparisons, order terms and surface conditions take any bool
`ValueExpr`, CEL included (ADR 0035), but the library writes them as data, never as
CEL. Such a condition is a bool `ValueExpr` of the simple tier
(ADR 0028): a read, `Compare`, `Not`, and `AllOf`/`AnyOf` over a `List` of
conditions or `Count`/`First` over a list of ids. The one evaluator (ADR 0035)
decides it, as it fills a slot:

- It reads the state, or the one attribute, without building an activation, and
  stops a list or a count once its answer is settled, the stop chosen at compile.
- A CEL condition would bind an activation and run the evaluator per evaluation —
  strictly more work.

The ORIGINAL argument was stronger and no longer applies: a query group had no
reverse index, so an affecting change re-scanned every entity in the house, and
that was the loop scaling worst (`entities × events/sec`). Candidate sets
removed the scan (ADR 0003) — presence is evaluated for the candidates a change
can actually move. So the data form is now kept on the narrower ground above,
plus the one that matters more: a condition is DATA the build can fold, dedupe
and reason about, which is what lets a registry term disappear at build time. An
expression string is opaque to all of that.

CEL in a condition is the author's choice, at the cost above.

## Attribute conversion is memoized per state version

A CEL evaluation needs the entity's attributes as Java values. The conversion
lives on `EntityState.javaAttributes`, computed **once per state version** (a
fresh `EntityState` is built on every change, so the cache self-invalidates).
A card with three `attr` slots converts once; the win applies to every
transform. A typed read of one attribute (`EntityState.attribute`) uses the map
when it is built and converts only that attribute when it is not, so a
condition over a fresh state does not pay for every attribute.

## Consequences

- One authoring vocabulary: every card input is a slot (literal / inherited /
  own-entity), the subject is the `entity_id` slot, and the only non-slot
  template var is the backend-injected `id`.
- `bypassUnavailable` defaults **false**: a read reads as is, and a state
  display opts in on its builder (ADR 0001). A condition compares the state's
  text, so `unavailable` is a value it decides over, not a reason to skip it.
- The cost model is explicit: cheap foldable data for presence and conditions
  (the simple tier, decided without the engine), CEL where a displayed value needs
  it, identity slots memoized, attribute conversion
  amortized per state version.
