# Plan — base components, and the HA layer on top

Follows the explicit-tap-targets work (#435–#438), which made a tap name its target
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
  hidden active: Boolean|slotMod.Reading = false     // classWhen("fh-active", …), below
  hidden disabled: Boolean|slotMod.Reading = false   // OR the tap's own refusal
  …
}
```

The base names its inputs by meaning (`active`, `disabled`), not by mechanism.

**A live class goes on the cell, not in the template.** Every node already has a
renderer-owned `.fh-cell` wrapper, which carries its static classes (`.cellClass(…)`) and
its signal seed. `classWhen(name, reading)` becomes a builder on every `LayoutNode`, next to
`cellClass`. The renderer emits one `data-class:<name>` per entry on the wrapper, and puts
the class inline in the document form so it shows without JS:

```pkl
c.entityButton(l).classWhen("warm", c.isOn(l))   // any node, any card
```

```css
.fh-cell.warm>button{…}                          /* the author's css or a theme */
```

Two alternatives were rejected:

- **A `classWhen` hole in each card's template.** The template is per CARD and has fixed hole
  names, so a per-node list does not fit. Every card would also have to place the hole, and
  a card that forgot it would drop the classes silently. That is how `Toggle` once had no
  inert check.
- **Named inputs only.** That leaves an author no way to style a node by its state.

`active` is the shipped use of `classWhen`, not a second mechanism. The button card sets
`classWhen("fh-active", active)`, and its own CSS owns the look:
`.fh-cell.fh-active>button{background:var(--fh-accent);color:var(--fh-on-accent)}`.

That is the rule the slider has today, written for every button rather than for the
slider's head only. Today it is `.slider-actions>.fh-cell>button.lit` (`slider.pkl`),
which is why `.lit(true)` anywhere else paints nothing. The slider still paints its head's
buttons by position, but on the cell class:
`.slider-actions>.fh-cell:not(.fh-active)>button` for the resting look. Both rules weigh
(0,2,1) and card CSS is emitted sorted by card name, so an unguarded resting rule from
`slider` would land after `button` and hide `active`. `sliderAction(e, t)`
becomes `c.entityButton(e)` with `active = c.isOn(e)`, and the info action in its doc says
`.active(false)`.

**A tap carries its own refusal.** `Call.inertWhile` becomes
`Call.disabledWhile: Listing<String>`, states of the call's target. `byDomain` fills it
from `hass/actions.pkl`'s transitional states, and `c.tap.locks.openLatch(l)` fills it
with `CANNOT_OPEN`. `tapRoute` adds "target unavailable", as it does today. Each card ORs
its own `disabled` onto it, so the latch's confirm button is guarded wherever it is
placed. A `Click` has no target, so a popup opener's guard is the card's `disabled`.

A list of states and not a `Reading`. The refusal is always about the call's own target,
and `unavailable` has to join it. A list joins by union, inside one slot. Two readings
would need a second OR, and the class host (the entity card, `inertClass`) has no
expression to put one in.

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
class EntityButton extends buttonMod.Button {
  hidden entity: hass.Entity
  label = entity.friendly_name ?? entity.entity_id
  tapAction = tapMod.byDomain(entity) ?? throw(…)
  subject = entity.entity_id
}
c.entityButton(l).active(c.isOn(l))              // base builder, HA reading
```

The rule is checkable, as a property: an HA component's slots, card and cell equal those
of the base built by hand with the same inputs (`components.test.pkl`). Under it, an HA
class is exactly the "function over a flexible component" the split asks for, but one
that keeps its entity for the builders after it.

**`subject` is how a reading without an entity finds one.** A base card takes an
optional `subject: String?` and places it as `entity_id` only when set. That is the
existing subject-slot mechanism, so an entity-less `c.expr(…)` on an HA card keeps
reading that card's entity. The demo dashboards' entity-card labels depend on it. The
base never sets it.

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
- **A backend change for `classWhen`.** `Cell` gains live classes on the wire
  (`Dashboard.scala`, `WireShapeSuite`). The renderer emits their bindings on the wrapper in
  both forms and seeds their signals with the node's, and `validate` checks them. A reading
  is a display signal, named by what it reads (ADR 0017), so a `classWhen` and a card slot
  on the same reading share one signal.
- **Authors:** `.lit(true)` → `.active(c.isOn(l))`, `.inertWhile(xs)` →
  `.disabled(c.stateIn(l, xs))`. Taps are untouched.
- **A reading on a base card must name its entity** (`c.exprOf`, `c.isOn`). Only an HA
  card sets `subject`, which an entity-less `c.expr(…)` reads.

## Steps (one stacked PR each)

1. **This plan, plus the spike.** Two things carry the risk:
   - **`classWhen` on the cell.** The wire field, the renderer's bindings and inline class,
     and the seed. Prove that a Boolean reading drives the class in both forms. `asClass`'s
     doc says only `""` is falsy, which predates `SlotValue` being `String|Boolean`.
   - **The disabled OR.** A tap's refusal ORed with the card's `disabled` through
     `slotRead`. Prove that validate accepts the template and that the document and patch
     forms both disable.
2. **`Call.disabledWhile`** replaces both `inertWhile`s. `byDomain` and `openLatch` fill it.
   Test the property, not the line: every node whose tap posts `lock/open` is disabled
   while the lock is `open`, whatever card it is on.
3. **`components/base/` with `Button`/`Pill`.** `EntityButton` follows the thin-subclass
   rule. `active` is `classWhen("fh-active", …)`, styled by the button card. The slider's
   `lit` rule goes, and `sliderAction` uses `active`. A node's cell is derived from
   `cellClasses`/`liveClasses` inputs, so builder order cannot drop `active`. Tests: no
   `components/base/` module imports a `hass` module (`BaseComponentsSuite`), an
   entity button equals the base built by hand, and builder order leaves the cell equal.
4. **`Switch` and `Tile`**, with `Toggle` and `EntityCard` thin on top. Text, tabs, `If`
   and `CardFeatures` move to `base/`. The registry names stay `toggle` and `entityCard`,
   so the wire does not move. The module is `base/onoff.pkl`, because `switch` is a Pkl
   keyword.
5. **The slider**: a base range control and `Slider` thin on top. It is the largest card
   (`slider.pkl`, ~1000 lines). If step 4 shows the cost, this becomes its own plan.
6. **Close.** ADR 0015 rewritten for the split, terminology (`Reading`, `active`,
   `disabled`), the module `CLAUDE.md` and the pipeline doc checked, and this plan deleted.

## Not in scope

- A CEL-picked tap service, checked by re-running the CEL in the call route
  (`Dashboard.calls`). Noted for later.
- Generating per-domain verbs from HA's service list. They stay hand-written, as HA's
  own are.
