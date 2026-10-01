# Plan — base components, and the HA layer on top

Follows `docs/plan-explicit-tap-targets.md` (#435–#438), which made a tap name its target
and took the entity out of `Button`. Builds on ADR 0015 (library tiers), ADR 0016 (what a
tap does), ADR 0017 (signal slots) and ADR 0019 (busy).

## Why

#438 split `Button` from `EntityButton`, but the split is only skin-deep:

- **The base still has the entity's holes.** `Button`'s template places `lit` and `inert`,
  which only `EntityButton` fills, and `Button` carries `extraInert` for its subclass.
- **`lit` paints nothing.** The only rule for it is `.slider-actions>.fh-cell>button.lit`
  (`slider.pkl`), so `c.entityButton(l).lit(true)` outside a slider adds a class that no
  stylesheet reads.
- **The refusal sits on the wrong thing.** `lock.openLatch(l)` puts `CANNOT_OPEN` on the
  button that OPENS the confirm popup. The popup's "Open the door" button, the one that
  posts `lock/open`, refuses only while unavailable. So does every
  `c.button(…, c.tap.locks.openLatch(l))`.
- **`inertWhile` has two homes**: `Call.inertWhile` and `EntityButton.inertWhile`, unioned
  in `tapMod.inertStates`, and the second reads the target or the card's entity depending
  on the tap's kind.
- **"inert" is HTML's word for something else.** The `inert` attribute takes a whole
  subtree out of focus and events; we render `disabled`.

The same shape repeats in every HA-bound card: `Toggle`, `EntityCard` and `Slider` each
read their entity inside the card, so none of them has a form that knows nothing about HA.

## The design

**A base component says WHERE a value lands. The HA layer says WHAT it reads.**

Today a `Slot` carries both: the read (`entityId` + `transform`) and the binding
(`signal = asClass("lit")`). The class name `lit` is picked in the HA layer and has to
match CSS that lives elsewhere. Split them:

```pkl
// core/slot.pkl — a live read with no binding yet; it names its own entity
typealias Reading = Slot(entityId != null && signal == null)

// components/base/button.pkl — no hass import
open class Button extends nodes.Node {
  hidden label: String|slotMod.Reading
  hidden icon: String?
  hidden tapAction: tapMod.TapAction
  hidden active: Boolean|slotMod.Reading = false     // painted by THIS card's css
  hidden disabled: Boolean|slotMod.Reading = false   // OR the tap's own refusal
  slots {
    when (active is slotMod.Reading) {
      ["active"] = (active) { signal = slotMod.asClass("fh-active") }
    }
    …
  }
}
```

The base names its inputs by meaning (`active`, `disabled`), not by mechanism. A generic
`classWhen: Mapping<String, Reading>` was considered and rejected. The class name would be
the author's, so no card's CSS could style it (`lit` is the evidence). And a template
cannot place a variable number of holes.

**A tap carries its own refusal.** `Call.inertWhile: Listing<String>` becomes
`Call.disabledWhen: Reading?`. `byDomain` fills it from `hass/actions.pkl`'s transitional
states, and `c.tap.locks.openLatch(l)` fills it with `CANNOT_OPEN`. `tapRoute` adds
"target unavailable", as it does today. Each card ORs its own `disabled` onto it, so the
latch's confirm button is guarded wherever it is placed. A `Click` has no target, so a
popup opener's guard is the card's `disabled`.

The OR is a template expression over two holes,
`data-attr:disabled="{{{refused__read}}} || {{{disabled__read}}}"`, with a mustache OR
for the document form. `slotRead` already answers a literal and a signal, so this should
need no backend change. **The spike proves it** (step 1).

**The HA layer is thin subclasses, not plain functions.** The first idea was
`entityButton(e)` as a function returning a base `Button`. In Pkl that loses the entity
for everything after the call:

- a function has no default arguments;
- the result no longer knows its entity, so `c.entityCard(l).value("brightness")` and
  `(c.entityCard(l)) { value = "brightness" }` would have to become
  `.value(c.attr(l, "brightness"))`. That names `l` twice on the commonest card.

So an HA component stays a class that extends its base, under one rule:

> **It declares no card, no template and no slots. It holds `entity` and entity-relative
> sugar, and assigns its base's inputs from them.**

```pkl
// components/control.pkl — the HA layer
class EntityButton extends base.Button {
  hidden entity: hass.Entity
  label = c.name(entity)                         // a Reading: friendly_name
  tapAction = tapMod.byDomain(entity) ?? throw(…)
}
c.entityButton(l).active(c.isOn(l))              // base builder, HA reading
```

The rule is checkable (step 3). Under it, an HA class is exactly the "function over a
flexible component" the split asks for, but one that keeps its entity for the builders
after it.

**The tree.** `components/base/` holds the components that know nothing about HA. It must
not import `hass.pkl`, and a test enforces that. `components/` itself is the HA layer. The
facade's names do not change: `c.button`, `c.entityButton`, `c.toggle` and
`c.entityCard` stay where authors find them.

| base (`components/base/`) | HA layer (`components/`) |
|---|---|
| `Button`, `Pill`, `iconButton` | `EntityButton` |
| `Switch` (`on: Reading` → `checked` + `switched`) | `Toggle` |
| `Tile` (icon, label, value, secondary: `Reading`s) | `EntityCard` |
| `SectionTitle`, `Label`, `Tabs`, `If`, `CardFeatures` | `lock`, `light`, `moreinfo`, `history`, `progress` |
| range control (step 5) | `Slider` |

`Switch` is the clearest case of the split. `Toggle` derives two slots, `checked` (bind) and
`switched` (attr), from one reading of the entity. The base takes that reading once and
picks both bindings itself.

**Names.** `inert` → `disabled` throughout, including `InertAs`, which becomes how a host
shows `disabled`: a real attribute, or a class plus the click guard. `lit` → `active`. HA's
frontend has a per-domain notion of "active". Whether to vendor it into `hass/` is a later
decision; this plan ships `c.isOn(e)`.

## What it costs

- **Wire format:** slot names change (`lit`, `inert`, `entity_id` on base cards). Wire
  snapshots are regenerated deliberately, and visual baselines may move where `active`
  gains a style outside the slider.
- **Authors:** `.lit(true)` → `.active(c.isOn(l))`, `.inertWhile(xs)` →
  `.disabled(c.stateIn(l, xs))`. Taps are untouched.
- **A reading must name its entity.** On a base card there is no subject entity to fall
  back on, so `c.expr(…)` without an entity is accepted only by HA-layer classes, which
  bind it to theirs.

## Steps (one stacked PR each)

1. **This plan, plus the spike.** One base `Button` with `active` and `disabled` readings,
   and a tap whose refusal is ORed with `disabled` through `slotRead`. Prove three things:
   validate accepts the template; the document and patch forms both disable; a Boolean
   reading drives a class binding (`asClass`'s doc says only `""` is falsy, which predates
   `SlotValue` being `String|Boolean`).
2. **`Call.disabledWhen`** replaces both `inertWhile`s. `byDomain` and `openLatch` fill it.
   Test the property, not the line: every node whose tap posts `lock/open` is disabled
   while the lock is `open`, whatever card it is on.
3. **`components/base/` with `Button`/`Pill`.** `EntityButton` follows the thin-subclass
   rule, `active` gets a style on the button card itself, and the base stops placing an
   `entity_id`. Two tests: no `components/base/` module imports `hass.pkl`, and no HA-layer
   class sets `cardDef` or `slots`.
4. **`Switch` and `Tile`**, with `Toggle` and `EntityCard` thin on top. Text, tabs and `If`
   move to `base/`.
5. **The slider**: a base range control and `Slider` thin on top. It is the largest card
   (`slider.pkl`, ~1000 lines). If step 4 shows the cost, this becomes its own plan.
6. **Close.** ADR 0015 rewritten for the split, terminology (`Reading`, `active`,
   `disabled`), the module `CLAUDE.md` and the pipeline doc checked, and this plan deleted.

## Not in scope

- A CEL-picked tap service, checked by re-running the CEL in the call route
  (`Dashboard.calls`). Noted for later.
- Generating per-domain verbs from HA's service list. They stay hand-written, as HA's
  own are.
