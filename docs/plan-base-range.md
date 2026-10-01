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

**`components/base/range.pkl`: `Range`, plus the head and text nodes it builds.** It knows
no entity. The HA `Slider` extends it, holds the axis (`on`/`entity`) and the per-domain
table, and assigns `Range`'s inputs:

```pkl
open class Range extends nodes.Node {
  hidden label: String|slotMod.Reading
  hidden secondary: (String|slotMod.Reading)? = null
  hidden icon: String? = null
  hidden min: Number
  hidden max: Number
  hidden position: slotMod.Reading           // the device's value, as the input's number
  hidden fill: slotMod.Reading               // `--_end`, e.g. "61.2%"
  hidden fillColor: slotMod.Reading? = null  // null = the theme paints it
  hidden fillDefault: String = "currentcolor"
  hidden readout: slotMod.Reading? = null    // null = no readout span
  hidden readoutFollowsDrag: Boolean = false // today's `dragPercent`
  hidden commit: tapMod.Call                 // dataKey set, value from the drag
  hidden press: tapMod.TapAction? = null     // set = the track is a button, no input
  hidden actions: Listing<buttonMod.Button> = new {}
  hidden members: Listing<nodes.LayoutNode> = new {}
  hidden busyVisual: Boolean = true
  hidden subject: String? = null
}
```

**`position`, `fill` and `readout` are three readings, not one.** The obvious design is a
single `position` the base derives the rest from. It cannot: a transform is a CEL string
or a Simple shape, and neither composes. Deriving `fill` from an arbitrary reading would
mean splicing its CEL into another expression, or reaching into a `SimpleValue` for its
attribute name, which ties the base to one shape. The HA layer already builds all three
from one attribute name (`simpleMod.attr`/`fill`/`percent`), so it keeps doing that.

**The commit is a `Call`** with a `dataKey` and no `dataValue`, because the drag supplies
the value. `Call` documents the two as "both null or both set", so the commit becomes the
one documented exception. `tapRoute` keys `dataValue` on `dataKey` today (`dataValue!!`),
so step 2 keys it on `dataValue` instead. It goes through `tapRoute`, so it gets `tapDisabled` like every other
tap: the input is disabled and the track wears `fh-disabled` while the target is
unavailable or in a `disabledWhile` state. That fixes the bug above as a consequence of the
split, not a patch beside it. The input already binds `disabled` to the commit's busy
signal, so the OR is the same `attrWhenEither` shape `Button` uses.

**`press` is the toggle-only variant**, kept as one card. Today `toggleOnly` swaps the range
input for a full-width button inside the same pill. A separate base card would make a
group's rows two cards for one look. `Range` takes `press: TapAction?`; set, it renders the
button and no input. The HA `Slider` sets it from `toggleOnly`, as it decides today.

**What stays HA.** `SlideAxis`, `sliderSpec`, `toggleOnly`, the RGB and kelvin fill
expressions, the `"state"`/`"percent"` readout names, `valueExpr`/`percentExpr` (the splice
surface for authors' own readouts) and `sliderAction`. These are what make a slider "on
this entity", which is the HA layer's job by definition.

**The wire does not move.** The registry names stay `slider`, `sliderHead` and
`sliderText`, and the slot names stay what they are. A slider built today and the same
slider built after the split must serialise to the same bytes, apart from the new
`tapDisabled` slot on the commit. That is the test for the move (step 3).

```pkl
// author view: a range over something that is not an HA attribute
(rangeMod.Range) {
  label = "Volume"
  min = 0; max = 100
  position = c.exprOf(amp, "attr[?'volume'].orValue(0)")
  fill = c.exprOf(amp, "…")
  commit = (c.tap.call("media_player/volume_set", amp)) { dataKey = "volume_level" }
}
```

## Steps (one stacked PR each)

1. **This plan.**
2. **The commit gets its refusal.** The slider's range commit goes through `tapRoute`, the
   input ORs `tapDisabled` with its busy signal, and the track dims. Fact: every slider's
   commit node carries `tapDisabled` on its target, and a browser test that an unavailable
   slider's input is disabled. Before the move, so the behaviour change has a PR of its own.
3. **`base/range.pkl`.** `Range`, with `SliderHead`/`SliderText` moved under it.
   `Slider extends Range` under the thin rule. Tests: the wire snapshots and visual
   baselines do not change; a `Slider` equals the `Range` built by hand with its inputs;
   `BaseComponentsSuite` covers the new module.
4. **Close.** ADR 0015's table, terminology (if `Range` is a new word), the module
   `CLAUDE.md`, and this plan deleted once the maintainer agrees.

## Open questions

- `press` on `Range` (one card, as above), or a separate base `Bar` card for the toggle-only
  track.
- Whether step 2 should also refuse while the target is in its domain's transitional states
  (a cover `opening`), as `byDomain` taps do, or only while unavailable.
- The name: `Range` (what it is) or `Slider` with the HA class renamed `EntitySlider`, which
  would match `EntityButton` but rename the most-used card in the facade.

## Not in scope

- A base form of the slider's group layout beyond `members`: it is already any node.
- Changing how the fill is computed (server reading vs client expression).
