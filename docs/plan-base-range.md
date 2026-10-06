# Plan — a base range control under the slider

Step 5 of the base-components work (ADR 0015's base tier), split out because the slider is
the one HA card that is not a mechanical split. Builds on ADR 0012 (regions), ADR 0016
(what a tap does), ADR 0017 (signal slots), ADR 0019 and ADR 0025 (busy, a value in flight).

## Why

`components/slider.pkl` (1032 lines) is three node classes — `Slider`, `SliderHead`,
`SliderText` — and every one of them reads its entity. There is no slider an author can
point at something that is not one HA entity's attribute, and the card does not follow the
thin rule `Button`, `Switch` and `Tile` now do.

There is also a bug the split exposes. **The drag commit has no refusal.** Every other tap
goes through `tapMod.tapRoute`, which adds `tapDisabled` (the target is unavailable, or in
one of the call's `disabledWhile` states). The commit builds its route with `routeSlots`
directly, and its guard is `busyGuardChange`, which checks only "in flight". Nothing on the
server refuses an unavailable target either, so dragging an unavailable cover posts
`cover/set_cover_position` to HA. The toggle-only track goes through `tapRoute` and is
guarded.

## The design

**`components/base/slider.pkl`: `Slider`, plus the head and text nodes it builds.** It
knows no entity. The HA class becomes `EntitySlider`, as `Button`/`EntityButton` are: it
extends the base, holds the axis (`on`/`entity`) and the per-domain table, and assigns the
base's inputs. The facade follows: `c.entitySlider(axis)` is what `c.slider(axis)` was,
and `c.Slider` is the base.

```pkl
open class Slider extends nodes.Node {
  hidden title: String|slotMod.Slot
  hidden secondary: slotMod.Secondary? = null      // shared with the HA layer (ADR 0015)
  hidden glyph: String? = null                     // the badge's MDI class
  hidden rangeMin: Number
  hidden rangeMax: Number
  hidden position: slotMod.Slot                    // the device's value, as the input's number
  hidden fill: slotMod.Slot                        // `--_end`, e.g. "61.2%"
  hidden fillColor: slotMod.Slot? = null           // null = `fillDefault`, read once
  hidden fillDefault: String = "currentcolor"
  hidden reading: slotMod.Slot? = null             // null = no readout span
  hidden readingFollowsDrag: Boolean = false       // `dragPercent`
  hidden commit: tapMod.Call? = null               // dataKey set, value from the drag
  hidden press: tapMod.TapAction? = null           // set = the track is a button, no input
  hidden actions: Listing<buttonMod.Button> = new {}
  hidden leadingActions: Listing<buttonMod.Button> = new {}  // a subclass's, first
  hidden members: Listing<nodes.LayoutNode> = new {}
  hidden busyVisual: Boolean = true
  hidden subject: String? = null
}
```

The inputs are named as `Tile`'s are, for what they are on the card (`title`,
`glyph`, `reading`), so `EntitySlider` keeps its entity-relative `label`, `icon` and
`readout`, and `min`/`max` stay its author overrides of the domain's range.
`secondary` is the exception both tiers share, and `EntitySlider` inherits it.
`leadingActions` is how the HA `tapAction` shorthand stays first without an author's
`actions` replacing it.

**`position`, `fill` and `reading` are three readings, not one.** The obvious design is a
single `position` the base derives the rest from. It cannot: a transform is a CEL string
or a Simple shape, and neither composes. Deriving `fill` from an arbitrary reading would
mean splicing its CEL into another expression, or reaching into a `SimpleValue` for its
attribute name, which ties the base to one shape. The HA layer already builds all three
from one attribute name (`simpleMod.attr`/`fill`/`percent`), so it keeps doing that.

**The commit is a `Call`** with a `dataKey` and no `dataValue`, because the drag supplies
the value. `Call` documents the two as "both null or both set", so the commit becomes the
one documented exception. `tapRoute` keys `dataValue` on `dataKey` today (`dataValue!!`),
so step 2 keys it on `dataValue` instead. It goes through `tapRoute`, so it gets
`tapDisabled` like every other tap: the input is disabled and the track wears `fh-disabled`
while the target is unavailable. Only unavailable: the commit is a plain `call`, so its
`disabledWhile` is empty, and a cover that is `opening` takes a new position as it should.
That fixes the bug above as a consequence of the split, not a patch beside it. The input already binds `disabled` to the commit's busy
signal, so `busyAttrsChange` ORs the two in its one `data-attr:disabled`.

**`press` is the toggle-only variant**, one card. Today `toggleOnly` swaps the range
input for a full-width button inside the same pill. A separate base card would make a
group's rows two cards for one look. The base takes `press: TapAction?`; set, it renders
the button and no input. `EntitySlider` sets it from `toggleOnly`, as it decides today.

**What stays HA.** `SlideAxis`, `sliderSpec`, `toggleOnly`, the RGB and kelvin fill
expressions, the `"state"`/`"percent"` readout names, `valueExpr`/`percentExpr` (the splice
surface for authors' own readouts) and `sliderAction`. These are what make a slider "on
this entity", which is the HA layer's job by definition.

**The wire does not move.** The registry names stay `slider`, `sliderHead` and
`sliderText`, and the slot names stay what they are. A slider built before the split and
the same slider built after it serialise to the same node bytes, apart from the new
`tapDisabled` slot on the commit (step 2). One deliberate exception in step 4:
`sliderHead` and `sliderText` stop DECLARING `entity_id`, because a declared slot must be
on every node and a base slider may have no subject. Every HA slider still carries it.

```pkl
// author view: a slider over something that is not an HA attribute
new c.Slider {
  title = "Volume"
  rangeMin = 0; rangeMax = 100
  position = new slotMod.Slot { entityId = amp.entity_id; transform = "…" }
  fill = new slotMod.Slot { entityId = amp.entity_id; transform = "…" }
  commit = (c.tap.call("media_player/volume_set", amp)) { dataKey = "volume_level" }
}
```

## Steps (one stacked PR each)

1. **This plan.**
2. **The commit gets its refusal.** The slider's range commit goes through `tapRoute`, the
   input ORs `tapDisabled` with its busy signal, and the track dims. Fact: every slider's
   commit node carries `tapDisabled` on its target, and a browser test that an unavailable
   slider's input is disabled. Before the move, so the behaviour change has a PR of its own.
3. **The rename.** `Slider` → `EntitySlider`, `c.slider` → `c.entitySlider` (the
   `render = c.slider` lambda form too), across the lib, the demo dashboards, the tests
   and the ADRs. Nothing else, so the diff reads as a rename and the wire does not move.
4. **`base/slider.pkl`.** The base `Slider`, with `SliderHead`/`SliderText` moved under
   it, and `c.Slider` naming it. `EntitySlider extends Slider` under the thin rule. ADR
   0015's map and the module `CLAUDE.md` move with it. Tests: the node bytes and visual
   baselines do not change (the declared-slot exception above); an `EntitySlider` equals
   the base built by hand with its inputs; `BaseComponentsSuite` covers the new module.
5. **Close.** This plan deleted once the maintainer agrees.

## Not in scope

- A base form of the slider's group layout beyond `members`: it is already any node.
- Changing how the fill is computed (server reading vs client expression).
