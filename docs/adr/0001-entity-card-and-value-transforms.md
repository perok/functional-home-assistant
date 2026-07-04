# ADR 0001 — Entity card + per-slot value transforms via JSONata

- **Status:** Accepted
- **Date:** 2026-06-22 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The dashboard renders a recursive layout tree of **cards** (shared Mustache
templates, defined once in the component library) whose slots are filled with
live Home Assistant state at runtime (`Renderer` + `StateStore`). Two needs
drove this design:

1. **A richer read-only card** — an HA-like *entity card*: show `state` by
   default *or* a chosen attribute, plus unit, a secondary line, and a tap
   action.
2. **Per-instance value transforms** — round, scale, append a unit, map
   `on`→`Open`. Crucially a **per-instance** choice (this sensor rounds to 1
   decimal; that one converts °C→°F), not a property of the shared card.

The governing constraint is the module's **phase discipline**: jsonnet/Pkl
evaluate at build time and do pure composition — they never see a live value.
The live value only exists at runtime, in the Scala renderer. So a transform
must be authored as **data** the backend interprets per live value.

## Why JSONata on the data path (rejected: template-engine helpers)

Putting transforms in the template engine (Handlebars helpers/subexpressions)
was rejected: the template is **shared and instance-agnostic** while the
transform is **per-instance**, so template-level helpers can't vary per
instance without recompiling templates or proliferating card variants. Mustache
(jmustache) is logic-less by design; Handlebars' helpers resolve from template
text at compile time, not from data — a per-instance transform arriving as data
still needs a separate interpreter. Switching engines buys nothing and costs a
migration.

For the transform language itself:

| Option | Standard? | Fit for a scalar | Conditionals | Number formatting | Dep |
|---|---|---|---|---|---|
| Bespoke pipe DSL | no | exact | hand-add per case | exact | none |
| **JSONata** | yes (spec) | good | built-in `? :` | `$round(x,n)` | `com.dashjoin:jsonata` |
| jq | yes (ubiquitous) | ok | `if/then` | clunky (float noise) | `jackson-jq` |
| Handlebars subexpr | syntax only | ok | needs helpers | register your own | engine swap |

**Chosen: JSONata** — a documented spec with built-in conditionals, string ops
and `$round`, precompiled once per expression. (Note: `$round` is
round-half-to-even, banker's rounding.)

## The design

**A slot's value IS a transform.** `SlotSource.transform` (default `"$state"`)
is a JSONata expression evaluated against the producing entity's full context:

- `$state` — the raw state (String);
- `$attr` — the attribute object (`$attr.brightness`; numbers stay numeric, so
  arithmetic needs no `$number`);
- `$domain` / `$entity_id` — the entity's identity.

There is **no bare `$`** and **no cross-entity lookup** — a transform is a pure
function of one entity's state + identity (the safety boundary; a slot that
needs *another* entity names it via `entityId`, see ADR 0004). Selecting a
value is the transform: `"$state"` shows the state, `"$attr.brightness"` an
attribute, `"$lookup(…, $domain)"` an identity-derived value. There is no
separate "computed" concept. A hardcoded value is a **literal** slot (a bare
JSON string) — no entity, no JSONata, no compilation.

**`entity_id` is the magical slot.** A component is `(card, slots, children)`;
the card's subject entity is the slot *named* `entity_id` (normally a literal;
a transform form grounds indirection on its own entity). It is the single slot
that does **not** inherit the subject — it *defines* it. Every other slot with
`entityId = None` inherits it. The only non-slot template vars are
backend-injected: the stable node `id` (plus the matched `entity_id` inside a
dynamic case).

**Unavailability is an explicit per-slot bypass.** `bypassUnavailable` (ON by
default) makes an `"unavailable"`/`"unknown"` entity show its raw state
*instead of* running the transform — what keeps a value display readable when
its transform would error on a non-numeric state. Slots that must run their
transform regardless opt out: identity-derived actions (resolve from `$domain`,
not state), labels (keep the friendly_name), a slider's numeric position (fall
back to its `default`).

**Failure modes are contained.** A genuinely broken transform on a real value
renders the JSONata **error message on its card** — never a silent blank, never
a crashed render. A `null` result becomes `""` so the slot's `default` takes
over. A transform that fails to *compile* is a hard build error
(`Dashboard.validate`) — the dashboard does not load.

**Service actions are data, not a Scala domain table.** Templates take a single
`{{{action}}}` value (`"<domain>/<service>"`); the default is an ordinary
identity slot whose transform is a JSONata `$lookup` over `$domain`
(scene/script → `turn_on`, button → `press`, else `homeassistant/toggle`),
authored in the card builder and overridable via `serviceTap(...)`. The
renderer never knows HA domains.

**The slider resolves its whole config the same way.** One `sliderSpec` table
(domain → action/key/min/max/position attribute) drives `action`/`key`/`min`/
`max`/position. In jsonnet the slots are `$lookup(<map>, $domain)` transforms;
on the Pkl track a *static* entity resolves its spec at build time and only a
dynamic `$self` entity uses the runtime `$lookup` tier (ADR 0006). Adding a
domain (cover/fan/…) is a `sliderSpec` row — no builder or backend change.
These identity-derived config slots are `reactive: false`, so they are resolved
once per entity and memoized (ADR 0004), keeping the dynamic render path cheap.

## Consequences

- Authors get a standard, documented expression language; phase discipline is
  preserved (transforms are data; templates stay pure and shared).
- Compiled once (`Transforms.from`), never parsed on the hot path; compile
  errors fail the build with node/slot/source-line context.
- Dependency: `com.dashjoin:jsonata` (pure-JVM JSONata). Thread-safety was
  verified against the bytecode, not assumed: evaluation state lives in a
  per-call frame (`createFrame` + `Frame.bind`, never `assign`), so one
  compiled instance is safely shared across fibers without locking.
- Per-eval cost is higher than a hand-rolled AST — negligible for the rendered
  cards per change; the hot membership scan deliberately does *not* use JSONata
  (ADR 0004).
