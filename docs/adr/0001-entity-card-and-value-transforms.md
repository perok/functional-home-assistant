# ADR 0001 — Entity card + per-slot value transforms

- **Status:** Accepted
- **Date:** 2026-06-22 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)
- **Engine:** the transform language is CEL, owned by ADR 0027; this ADR owns
  the model a transform plugs into.

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

The governing constraint is the module's **phase discipline**: the Pkl entry
evaluates at build time and does pure composition — it never sees a live value.
The live value only exists at runtime, in the Scala renderer. So a transform
must be authored as **data** the backend interprets per live value.

## Why an expression on the data path (rejected: template-engine helpers)

Putting transforms in the template engine (Handlebars helpers/subexpressions)
was rejected: the template is **shared and instance-agnostic** while the
transform is **per-instance**, so template-level helpers can't vary per
instance without recompiling templates or proliferating card variants. Mustache
(jmustache) is logic-less by design; Handlebars' helpers resolve from template
text at compile time, not from data — a per-instance transform arriving as data
still needs a separate interpreter. Switching engines buys nothing and costs a
migration.

The language is a standard expression language rather than a bespoke pipe
DSL, so conditionals, string operations and rounding come with a spec instead
of being added per case. Which language is ADR 0027's.

## The design

**A slot's value IS a reading and its steps.** A `ValueExpr` reads a source —
by default the producing entity's state — and runs its steps on it in order.
CEL reads `value`, what came before it: on a reading of the whole entity
(`Part.Whole`, CEL's `fh.Entity`):

- `value.state` — the raw state (String);
- `value.attr` — the attribute map (`value.attr[?'brightness']`; numbers stay numeric);
- `value.domain` / `value.entity_id` — the entity's identity.

A common shape can instead be a simple step (`Step.Simple`), which skips the
expression engine entirely (ADR 0028).

There is **no cross-entity lookup** — a transform is a pure
function of one entity's state + identity (the safety boundary; a slot that
needs *another* entity names it via `Value.Entity.of`, see ADR 0004). Selecting
a value is the reading: the state shows the state, an attribute read an
attribute, an expression over `value.domain` an identity-derived value. There is no
separate "computed" concept. A hardcoded value is a **literal** slot (a bare
JSON string) — no entity, no expression, no compilation.

**`entity_id` is the magical slot.** A component is `(card, slots, regions)`;
the card's subject entity is the slot *named* `entity_id`, always a literal
(`validate` refuses anything else). It *defines* the subject: when Pkl builds
the wire it names that entity as the `entityId` of every other reading that
names none, so the runtime reads each slot's own `entityId` and inherits
nothing. The only non-slot template vars are
backend-injected: the stable node `id` (plus the matched `entity_id` inside a
dynamic case).

**Unavailability is an explicit per-slot bypass.** `bypassUnavailable` makes an
`"unavailable"`/`"unknown"` entity show that word *instead of* running the
transform — what keeps a value display readable when its transform would error
on a non-numeric state. It is off by default, so a read reads as is, and only a
text slot's own read of the state or the whole entity may set it; `validate`
refuses it anywhere else (an operand, a condition, an attribute, a bool slot).
The card sets it, not the reading: a state display's builders (`valueSlot`, the slider's readout,
the second line) set it on any reading of the state or the whole entity they are handed
(`slotMod.textReading`); actions, labels and a slider's position read as is.

**Failure modes are contained.** A genuinely broken transform on a real value
renders the evaluation **error message on its card** — never a silent blank, never
a crashed render. A `null` result becomes `""` so the slot's `default` takes
over. A transform that fails to *compile* is a hard build error
(`Dashboard.validate`) — the dashboard does not load.

**Service actions are data, not a Scala domain table.** Templates take a single
`{{{action}}}` value (`"<domain>/<service>"`), authored in the card builder and
overridable via `c.tap.call(...)`. The renderer never knows HA domains — that is
the part of this decision that has not moved.

The service is resolved **per entity at build time** from a vendored table
that is also allowed to say *no action*, in which case the tap opens more-info
instead. **ADR 0016** owns that; only four domains still resolve their service
from live state.

**The slider resolves its whole config the same way.** One `domainAxes` table
(`components/slider.pkl`: domain → service, key, range, position attribute)
drives the slider's `action`/`key`/`min`/`max`/position, as build-time
literals — inside a candidate set too, since every candidate is a known
entity. Adding a domain (cover/fan/…) is a row, with no builder or backend
change. A slot that reads only identity is `reactive: false`, resolved once
per entity and memoized (ADR 0004).

## Consequences

- Authors get a standard, documented expression language; phase discipline is
  preserved (transforms are data; templates stay pure and shared).
- Compiled once (`Dashboard.validated`), never parsed on the hot path; compile
  errors fail the build with node/slot/source-line context.
- Per-eval cost is higher than a hand-rolled AST — negligible for the rendered
  cards per change; the hot membership scan deliberately does *not* evaluate
  expressions (ADR 0004).
