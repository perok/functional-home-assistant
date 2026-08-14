# ADR 0016 — What a tap does: per-entity defaults, and more-info as the floor

- **Status:** Accepted
- **Date:** 2026-08-14
- **Scope:** `lib/hass/actions.pkl`, `lib/core/tap.pkl`, `lib/components/{entity,control,moreinfo}.pkl`
- **Refines:** ADR 0001, which made a service action a JSONata value rather than
  a Scala domain table. That still holds — this one replaces *which* JSONata,
  and when there should be none.

## Context

Every entity card carried the same default click: one `reactive: false` slot
whose transform was a `$lookup` over `$domain`, with `homeassistant/toggle` as
the else-branch. The else-branch is the bug. "Anything I do not recognise is
toggleable" is false for most of a house — measured on a live instance of 1069
entities, **143 could actually be toggled**. The other 87% still rendered
`class="… tappable"` with a pointer cursor over a call HA would reject. `lock`
was in the dead set: HA locks have `lock`/`unlock`/`open` and no `toggle`.

It was also the last `$lookup($domain)` transform of its kind. The icon and
slider ones folded to literals once candidate sets became static (ADR 0003) and
an entity's identity was therefore known at build time. Folding this one *as
written* would only have baked a wrong answer faster, which is why it waited.

## The decision

**A tap is decided per entity, at build time, from a vendored table** — and the
table is allowed to say *nothing*.

```
hass/actions.pkl   domain -> Call | CallByState | (absent)
core/tap.pkl       domainTap(e): Tap?          — the table, as a Tap
components/…       defaultTap(e) = domainTap(e) ?? moreInfo(e)
```

### 1. The table says whether, not just which

`hass/actions.pkl` follows the `hass/light.pkl` pattern: HA's own model,
vendored, checked against a live `/api/services` (83 service domains) rather
than written from memory. A domain that is **absent has no sensible tap**, and
that absence is the half that could not be expressed before — a `$lookup` with a
fallback has no way to return "no action".

Some absences are deliberate rather than unfinished, and the table says so:
`update` has `install` (flashing firmware on a stray tap), `alarm_control_panel`
has arm/disarm, `number`/`select`/`text`/`date` need a value *chosen*. Each of
those opens more-info, which is the right answer, not a missing one.

Adding a domain is a table row. Nothing in Scala knows an HA domain, which is
the part of ADR 0001 that carries over unchanged.

### 2. State-dependent actions stay JSONata (option (a))

A lock needs `lock/unlock` when locked and `lock/lock` when not, so its service
is genuinely not a build-time literal. Three shapes were weighed:

- **(a) a JSONata conditional** on the click slot — `$state = "locked" ? … : …`;
- **(b) a server-side intent route** (`POST /sse/toggle/<entity_id>`) resolving
  domain + state in Scala;
- **(c) both**, literals where a real `toggle` exists and a route where it does not.

**(a).** The affected set is small and stays small — most domains have a real
`toggle` service and remain literals — so (b) would put domain knowledge in two
places (Pkl decides *whether*, Scala decides *which*) to serve four rows:
`lock`, `vacuum`, `lawn_mower`, `timer`. It is modelled as a `CallByState`
class, not a hand-written JSONata string, so the table stays data.

The cost is that this one slot is `reactive` and drops out of `Renderer`'s
identity cache. It buys no extra wakeups: the card already tracks its entity for
the state it displays. Revisit (b) if the state-dependent list grows past a
two-way conditional — that is the trigger to watch, not the row count.

### 3. More-info is the floor, which is why it landed first

`EntityCard.tap` now defaults to `defaultTap(entity)`. Where the domain has no
action, that is `moreInfo(e)` — the popup from issue #106's first half. So
**nothing renders as clickable-but-dead, and nothing renders as inert either**:
a card either does the thing its domain implies, or shows you everything it
knows. `tap = null` is the explicit opt-out.

The regress this creates is real and silent: `moreInfoBody(e)` contains an
entity card, whose default tap for a non-actionable entity is this same popup,
whose body contains that card. Pkl's laziness does not save it, because a card's
`slots` force its tap. The body therefore pins its card's tap to `domainTap(e)`,
and a Pkl fact holds that line.

### 4. A pressable card with nothing to press is a build error

`Button`/`Pill`/`Toggle` defaulted to `action ?? toggleTap`. Since the factories
require an action this only bit in the amend form, where `(c.button) { label =
"x" }` posted `homeassistant/toggle` with an *empty* entity id. They now derive
from their entity where they have one and `throw` where they do not. A button is
defined by what pressing it does; not knowing that is not a state it should be
able to reach. This is the same "make the illegal state unrepresentable" move as
`Dashboard.Validated`, at the authoring layer.

`toggleTap` survives as an explicit escape hatch, now a plain literal
(`homeassistant/toggle`) rather than a lookup — the right answer for a domain
this library does not know but the author does.

## Consequences

- **The wire got smaller, not bigger.** The worry was that a more-info popup per
  non-actionable card would balloon the evaluated JSON. Measured on the demo
  dashboard against the live instance: 1,145,048 → 987,570 chars, **14% less**,
  while `$lookup` transforms went 1069 → 0 and inline popups went 3 → 28. A
  ~250-character JSONata string on every card cost more than the popups it was
  hiding. The two hand-written house dashboards are byte-identical — they hold
  no entity cards.
- **Every tap route is now a build-time literal except four domains.** That is
  the property ADR 0003 promised and the last place still spending runtime on a
  question the build had already answered.
- **The table can be wrong in exactly one safe direction.** A domain that gains
  a service upstream is a missing row → more-info. A domain whose service is
  *renamed* would be a wrong row, but HA does not rename services for the same
  reason it does not renumber feature bits. Re-check `/api/services` when syncing
  to a newer HA.
- **This is a breaking behaviour change** (alpha, deliberate): a card that
  previously posted `homeassistant/toggle` for an unrecognised domain now opens
  more-info instead. Any dashboard relying on the old blanket toggle names
  `c.tappable` or `c.tap.toggleTap` to get it back.
- **Deriving the table from the instance is the obvious follow-up** and stays out
  of scope: `/api/services` is per-instance ground truth, so custom integrations
  would work. It needs the same churn analysis `CapabilityAttributes` got before
  it can enter the dump (ADR 0013) — services move when integrations are added,
  which is registry-ish and probably fine, but worth measuring rather than
  assuming. The general form of that is typed access to every service *with its
  inputs*, which is a codegen opportunity of the same shape as the entity dump.
