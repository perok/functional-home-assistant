# ADR 0003 — Dynamic groups: live membership + per-entity card dispatch

- **Status:** Accepted
- **Date:** 2026-06-24
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Most of a dashboard is **static**: each card is composed in jsonnet against a
concrete entity from the build-time dump (`c.entityCard(dump.entities.sensor_x)`),
so the set of cards is fixed when `dashboard.json` is produced.

But some sections are inherently **live sets** — their membership and the card
chosen per member depend on state that only exists at runtime:

- "every light that is currently **on**" (membership changes as lights toggle);
- "every `device_class: battery` sensor under **20%**" (membership tracks a
  threshold as batteries drain);
- a mixed set where the **card differs per entity** (a light wants a brightness
  slider, a switch a toggle, anything else a read-only card).

The governing constraint is the module's **phase discipline** (see ADR 0001):
jsonnet evaluates at build time and never sees a live value, so a dynamic group
cannot be a jsonnet comprehension over live state. It must be authored as **data**
that the Scala renderer evaluates against live `EntityState`.

This ADR records the dynamic-group design after consolidating an earlier shape
that had parallel `dyn*` builders (`dynEntityCard`/`dynButton`/`dynSlider`) and a
`dynamic(query, [cases])` taking a list.

## Decision drivers

- **One card library.** A dynamic card should be built with the *same* leaf
  builders as a static one — no parallel `dyn*` set to keep in sync.
- **Separate the two concerns.** Membership (which entities) is distinct from
  per-entity card choice (how each renders); the DSL should not conflate them.
- **Stable runtime model.** Authoring ergonomics changed several times; the Scala
  model should not have to.
- **Small top-level surface.** The query/dispatch vocabulary is sizeable and only
  meaningful for dynamic groups, so it should be namespaced, not loose on `c`.

## Decisions

### 1. A dynamic group is a runtime-resolved node

The model is `LayoutNode.Dynamic(query, cases)` (`fh.view.model`):

- `query` is a small **Predicate AST** — `And`/`Or`/`Not`/`Cmp`, where `Cmp`
  compares a property (`domain`, `state`, or `attr:<name>`) with an op
  (`eq/ne/lt/lte/gt/gte`). It is evaluated **per entity against live state**
  (`Renderer.matches`), reading the entity's own `domain`/`state`/attributes.
- The renderer filters all entities by `query`, and for each match renders the
  **first `case` whose `when` predicate matches** (per-domain / per-state
  dispatch; an entity matching no case renders nothing).
- Membership is **data-dependent**, so a dynamic container cannot be
  reverse-indexed by entity like static components — it is re-evaluated on every
  state change (`Renderer.dynamicContainerIds`).
- The renderer auto-injects the matched entity's `id`/`entity_id`/`label` and
  **rebinds every slot's entity** to the match (`Renderer.renderCase`).

### 2. One builder set for static and dynamic — `matched` + `case`

Because the renderer rebinds slots and injects the per-entity params, a case can
be built from the **same leaf builder** as a static card, passing a sentinel
"current entity":

```jsonnet
dynamic.matched:: { entity_id: '$self', friendly_name: '' }
```

Any leaf builder (`c.entityCard`/`c.button`/`c.slider` and presets) accepts it
like a real dump entity; `'$self'` is rebound to each match at render time.
`dynamic.case(when, node)` wraps a built node as a case: it keeps the node's
`card` + `slots` and **strips the params the renderer injects** (`entity_id`,
`label`) plus the unused `entities`/`children`.

This **removed** the `dynEntityCard` / `dynButton` / `dynSlider` builders (and the
old four-argument `case(when, card, params, slots)`). It needed **no** renderer
change — `renderCase` already did the rebinding and injection.

### 3. `group(query, card)` takes one card; `when(...)` is the dispatcher

Membership and card-choice are separated:

- `dynamic.group(query, card)` renders **one** `card` per entity matching
  `query`.
- `card` is usually a single leaf (`c.entityCard(d.matched)`), or a
  `dynamic.when([...branches...], fallback=elseCard)` **selector** when the card
  must vary per entity. `when` returns the list of branches plus, if given, the
  `fallback` as a trailing `always` case.

Both forms **lower to the same** `Dynamic(query, cases)` model — a plain leaf
becomes a single `always` case; a `when` contributes its `cases` (detected via
`std.objectHas(card, 'cases')`). So the runtime model is unchanged regardless of
authoring shape.

```jsonnet
local d = c.dynamic;
// per-entity dispatch
d.group(d.whenState('on'), d.when([
  d.case(d.whenDomain('light'),  c.brightnessSlider(d.matched)),
  d.case(d.whenDomain('switch'), c.toggle(d.matched)),
], fallback=c.entityCard(d.matched, tap=c.toggleTap)))
// single card for every match
d.group(d.lowBattery(20), c.entityCard(d.matched))
```

(The fallback parameter is `fallback`, not `else` — `else` is a jsonnet keyword.)

### 4. The whole DSL is namespaced under `c.dynamic`

Everything dynamic-only lives under one object: the query predicates
(`cmp`/`and`/`or`/`pnot`/`always`/`whenDomain`/`whenState`/`whenDeviceClass`/
`attrLessThan`/`stateLessThan`/`lowBattery`), the `matched` sentinel, and
`case`/`when`/`group`. Dashboards alias it (`local d = c.dynamic;`). The **leaf
builders stay top-level** on `c` — they are shared by static and dynamic use, so
they do not belong under `dynamic`.

Since `dynamic` is now an object, the group constructor is the named field
`dynamic.group(...)` (a jsonnet field cannot be both callable and a namespace).

## Consequences

- **No Scala/model change** came out of these authoring iterations —
  `LayoutNode.Dynamic(query, cases)` and `Renderer.renderCase` are stable; all
  churn was in `components.libsonnet`.
- **One card library** serves both static and dynamic rendering; adding a new
  card type (template + leaf builder) needs no dynamic counterpart.
- A dynamic container re-renders on **every** state change (membership can't be
  reverse-indexed) — acceptable given the small entity counts, but the cost is
  per-group, so keep groups scoped by a selective `query`.
- jsonnet gotcha: the `when`/`group` bodies are object literals, so `self`
  rebinds to the returned object; they reach sibling builders via `$.dynamic.*`
  (the library root) — the same pattern as `$.button` inside `c.tabs` (ADR 0002).
- Verified by `BuildPhaseSuite` (the example dashboard evaluates → hoists →
  validates, exercising the namespaced DSL) and `RendererSuite` (query filtering +
  per-case dispatch + slot rebinding + label injection). Not browser-tested live.
