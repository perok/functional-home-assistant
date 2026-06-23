# ADR 0001 — Entity card + per-slot value transforms via JSONata

- **Status:** Accepted
- **Date:** 2026-06-22
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The dashboard renders a recursive layout tree of **cards** (shared Mustache
templates, defined once in `components.libsonnet`) whose slots are filled with
live Home Assistant state at runtime (`Renderer` + `StateStore`). Two needs
arose:

1. **A richer read-only card.** `stateCard` could only ever show an entity's
   `state`. We wanted an HA-like **entity card**: show `state` by default *or* a
   chosen attribute, plus unit, a secondary line, and a tap action.

2. **Per-instance value transforms.** Authors want to shape a value before it is
   shown — round, scale, append a unit, map `on`→`Open`. Crucially this is a
   **per-instance** choice (this sensor rounds to 1 and gets `" kW"`; that one
   converts °C→°F), not a property of the shared card.

The governing constraint is the module's **phase discipline**:

- jsonnet evaluates at **build time**; it does pure composition and never sees a
  live value.
- The live value only exists at **runtime**, in the Scala renderer.

So any transform must be authored in jsonnet as **data** that the backend
interprets per live value — it cannot be a jsonnet function over the value.

## Decision drivers

- Keep the **shared-card / per-instance** split intact (cards are a reusable
  library; instances supply data).
- Honour phase discipline — transforms are data evaluated at runtime, not baked
  into templates.
- Prefer a **standard, documented** expression language over a bespoke one, if it
  fits.
- Keep the **hot path cheap** (every state change re-renders the affected cards
  and re-applies transforms; the rendered-HTML diff cache sits downstream).
- Minimal disruption to the existing renderer/validation pipeline.

## Considered options

### A. The card

Chosen scope: **state-or-attribute selection + unit + secondary info line + tap
action**, no icon (deferred). Implemented by evolving `stateCard` into
`entityCard`; optional pieces are Mustache **sections** that render only when
their value is non-empty (`emptyStringIsFalse(true)` on the jmustache compiler).
`stateCard`/`dynStateCard` remain as thin aliases for back-compat.

### B. Where transforms live (template engine vs data path)

- **Inline in the template engine (Handlebars helpers / subexpressions).**
  Rejected. The template is **shared and instance-agnostic**, but the transform
  is **per-instance**, so template-level helpers can't vary per instance without
  either recompiling a template per instance or proliferating card variants.
  Mustache (what we use, via jmustache) is logic-less — no helpers-with-args.
  Handlebars.java *has* subexpressions, but its helpers resolve from **template
  text at compile time**, not from a value supplied as data — so a per-instance
  `transform` arriving as data still needs a separate interpreter. Net: switching
  engines buys nothing **for value transforms** and costs an engine migration.
  (A separate open question — per-instance variation of the card's **HTML
  structure** — is out of scope here; see the module `readme.md` TODO.)

- **On the data path (a transform on the slot, interpreted at runtime).**
  Chosen. The transform is a string on `SlotSource`, compiled once and evaluated
  per value — consistent with the existing `Predicate` query AST (author writes
  data in jsonnet, Scala interprets at runtime).

### C. The transform language (data-path)

| Option | Standard? | Fit for a scalar | Conditionals | Number formatting | Dep |
|---|---|---|---|---|---|
| Bespoke pipe DSL | no | exact | hand-add per case | exact | none |
| **JSONata** | yes (spec) | good | built-in `? :` | `$round(x,n)` | `com.dashjoin:jsonata` |
| jq | yes (ubiquitous) | ok (pipe-native) | `if/then` | clunky (float noise, round→int) | `jackson-jq` |
| Handlebars subexpr | syntax only | ok | needs `if`/`eq` helper | register your own `round` | engine swap |

**Chosen: JSONata.** It is a documented spec, fits scalar formatting (`$` is the
value, `$number($)` coerces), has built-in conditionals and string ops (so
`on`→`Open` and `round`/unit need no bespoke vocabulary), and precompiles. jq's
number formatting is weak for "round to N decimals"; the bespoke DSL would mean
inventing and maintaining a language; Handlebars-subexpr gives standard *syntax*
but bespoke *functions* plus an engine swap.

## Decision

1. Replace `stateCard` with **`entityCard`** (attribute selection, auto unit,
   secondary line, tap action); keep aliases.
2. Add **`SlotSource.transform: Option[String]`**, a **JSONata** expression
   evaluated by the renderer per live value.

The resolved value (always a String) is the JSONata input `$`. Examples:

```jsonnet
c.entityCard(power, attribute='power', transform='$round($number($) * 1000) & " W"')
c.entityCard(temp,                      transform='$round($number($) * 1.8 + 32, 1)')
c.entityCard(door,                      transform='$ = "on" ? "Open" : "Closed"')
```

> Revised 2026-06-22: this `$`-as-value binding was later replaced by explicit
> `$state`/`$attr` (no bare `$`), which also removed the unit slot — see the
> updates below.

## Consequences

**Positive**

- Authors get a standard, documented language with conditionals/formatting; no
  bespoke DSL to learn or maintain.
- Phase discipline preserved: transforms are data; templates stay pure and
  shared; the renderer/validation pipeline is unchanged in shape.
- Compiled once (`Renderer.compiledTransforms`); never parsed on the hot path.
  Compile errors surface at build time via `Dashboard.validate`.

**Negative / caveats**

- New dependency `com.dashjoin:jsonata:0.9.8` (pure-JVM JSONata port).
- Slight impedance mismatch: the scalar must be coerced (`$number($)`) for math,
  since HA values arrive as strings.
- `$round` is **round-half-to-even** (banker's rounding), not half-up — noted in
  tests.
- Evaluation errors (e.g. `$number($)` on `"unavailable"`) and `null` results are
  handled defensively: an error falls back to the **raw value**; `null` becomes
  `""` so the slot's `default` can take over. So a transient odd value never
  blanks a card.
- Per-eval cost is higher than a hand-rolled AST (JSON-node boxing). Negligible
  for the handful of cards re-rendered per change; could matter only for a
  dynamic group of hundreds of entities.

**Thread-safety (verified, not assumed)**

dashjoin advertises Jsonata as thread-safe. Confirmed against the 0.9.8 bytecode:
`evaluate` calls `createFrame` per call and threads evaluation state through that
frame as a parameter (stack-local), not instance state; the only instance fields
touched are `timestamp` (read only by `$now`/`$millis`, unused here) and the
`errors` list (error paths, which `Transform.run` catches). So the renderer
shares **one** compiled instance across fibers **without locking**.

## Implementation

- `model/Transform.scala` (new) — `parse` = `jsonata(src)`; `run` =
  `expr.evaluate(value)` then stringify (numbers no-sci/no-trailing-zero;
  `null`→`""`); errors → raw value.
- `model/Dashboard.scala` — `SlotSource.transform`; `validate` compiles every
  transform and reports failures by node/slot.
- `runtime/Renderer.scala` — `compiledTransforms: Map[String, Transform.Compiled]`
  built in the ctor; applied in `renderTemplate` before the `default` fallback.
- `runtime/Templates.scala` — `emptyStringIsFalse(true)` for the entity card's
  optional sections.
- `resources/dashboards/components.libsonnet` — `entityCard` template + builder,
  `dynEntityCard`, `toggleTap`, `stateCard`/`dynStateCard` aliases; `transform`
  threaded onto `slider` too.
- `build.sbt` — `com.dashjoin:jsonata:0.9.8`.

## Verification / result

- `sbt fh-datastar-view/testFull` — **27/27 pass**, including `TransformSuite`
  (round-to-n, scale+unit via `&`, °C→°F, `on`→`Open` ternary, `$uppercase`,
  error→raw fallback, `null`→`""`, malformed-expression rejection) and the build
  phase decoding/validating the example `dashboard.jsonnet`.
- `sbt fh-datastar-view/Compile/compile` — clean (only a pre-existing unrelated
  warning in `Renderer.javaContext`).
- Live (`dashboardServe`) walkthrough — pending; needs the HA instance reachable.

## Update — 2026-06-22: same-entity context (relaxed "no lookups")

The original design fed a transform only the single resolved value. That blocked
genuinely useful same-entity shaping — most concretely, appending an entity's own
`unit_of_measurement`, or formatting from a sibling attribute (`brightness`).

The rule is relaxed to **no _cross-entity_ lookups** (the real safety boundary)
while allowing same-entity reads. The renderer binds the producing entity's
context onto a per-call JSONata frame:

- `$state` — the entity's raw state (String).
- `$attr` — the entity's attribute object (`$attr.unit_of_measurement`,
  `$attr.brightness`); numbers stay numeric so arithmetic needs no `$number`.

There is **no bare `$`**: a transform addresses the entity explicitly through
`$state`/`$attr`, and the slot value is whatever the expression returns
(evaluation input is null). This dropped the original "`$` is the resolved
value" binding — it was ambiguous once same-entity context existed, and removing
it let the dedicated **`unit` slot go away**: the entity card's value is now a
JSONata that, by default, shows the state (or chosen attribute) and appends
`unit_of_measurement` when present (`entityCard`'s `valueTransform` builds it; a
custom transform replaces it and owns the unit). This also fixes the prior
double-unit hazard (a value transform plus an independent auto-unit slot).

No binding exposes any other entity, so transforms remain a pure function of one
entity's state. Bindings use `createFrame()` + `Frame.bind` (a fresh child of the
shared, read-only std-library environment) — **not** `assign`, which would mutate
shared instance state — so the one compiled instance stays safely shared across
fibers without locking. `Transform.run(expr, EntityState)` takes the entity
state directly (a transitional coupling until state is JSONata-native). On an
evaluation failure it returns the **JSONata error message**, so the failing
**card shows the error** — contained to that card, never silently falling back to
the raw value and never crashing the render. The earlier `Context.value` fallback
field is gone — the entity state is passed directly.

Two distinct failure modes, kept separate:

- **Non-value states.** An `"unavailable"`/`"unknown"` entity
  (`EntityState.unavailable`) is handled in the **renderer, before the
  transform**: it shows its raw state and skips JSONata entirely. So such a
  sensor stays readable even under a custom numeric transform (e.g.
  `$round($number($state), 1)`) that would otherwise error — the bypass, not the
  transform, is what keeps it readable.
- **A genuinely broken transform** on a real value still renders the JSONata
  error message on its card (above).

## Update — 2026-06-23: transform-only slots, typed inputs, identity actions

The project is **pre-v1 alpha** (see the root `README.md`), so this round took
breaking changes freely to collapse accumulated special cases. Three linked
changes:

**1. A slot is just a transform.** `SlotSource` is now
`{ entity, transform = "$state", default, bypassUnavailable = false }` — the
`attribute` field is **gone**. Selecting a value *is* the transform: `"$state"`
(the default) shows the state, `"$attr.brightness"` an attribute,
`"$lookup(…, $domain)"` an identity-derived value. The transform context gains
two identity bindings alongside `$state`/`$attr`: **`$domain`** (the entity-id
prefix) and **`$entity_id`** (the full id). So one mechanism — a JSONata
expression over the entity's full context — serves both live values and
identity-derived values; there is no separate "computed"/"compute" concept. The
renderer synthesizes an empty state for an absent entity, so an identity slot
(e.g. an action from `$domain`) resolves even before any state has arrived.
`Transform.run(expr, entityId, entity)` takes the id alongside the
`EntityState`.

**2. The unavailable bypass is an explicit per-slot flag.** The 2026-06-22
"renderer skips the transform for unavailable/unknown" behaviour is no longer
automatic — it would wrongly clobber an identity slot (an action must resolve
regardless of availability). It is now `SlotSource.bypassUnavailable`, **off by
default**, set `true` by the *value-display* builders (entity card value /
secondary, slider state). When on, an unavailable/unknown entity shows its raw
state and skips the transform; when off, the transform runs (and a genuinely
broken one still shows its error on the card).

**3. Service actions default from the entity's domain, as data — no Scala
domain table.** The `entity` card / `button` / `slider` templates build the
action route from a single **`{{{action}}}`** param (`"<domain>/<service>"`,
e.g. `"homeassistant/toggle"`) instead of separate `{{domain}}/{{service}}`.
`action` is an ordinary **identity slot** whose transform is authored in the
card builder (`components.libsonnet` `defaultToggleAction`): a JSONata
`$lookup` over `$domain` (scene/script → `turn_on`, button → `press`, else
`homeassistant/toggle`), overridable by `serviceTap(...)` / an explicit `action`
arg. So `dynButton`/`toggle` no longer take `domain`/`service` — the renderer
never knows about HA domains; the mapping lives as data with the card that
"knows its action". (We chose a JSONata-computed param over Handlebars helpers:
helpers would relocate the same logic into Scala or force an engine migration,
which ADR option B already rejected.)

**Supporting model changes.** `CardDef` replaced the flat `inputs: List[String]`
with typed **`params`** (static/injected) + **`slots`** (live), so
`Dashboard.validate` checks each against the right source instead of
hardcoding the injected set inline; the injected names are the single-source-of-
truth constants `Dashboard.injectedStatic` (`id`) / `injectedDynamic`
(`id`, `entity_id`, `label`). The template param carrying the entity id was
renamed `{{{entity}}}` → **`{{{entity_id}}}`** so both channels (template param
and JSONata binding) match HA's `entity_id`. The dropped `unit` slot and the
`stateCard`/`dynStateCard` aliases from earlier updates remain as described.
