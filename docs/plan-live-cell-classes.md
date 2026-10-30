# Plan: live cell classes that say what they do

Raised in chat on 2026-10-30, during #657 (slot views). Picked up after
`docs/plan-css-class-slots.md` lands.

**State:** ideas only; no direction picked.

## Why

`classWhen(cls, when)` and `cssProperty(name, reading)` work on any card node because the renderer
binds them on the node's `.fh-cell` wrapper, not in the card's template. To reach the renderer they
travel in the card's own `slots`, under reserved names: `node.pkl`'s `liveClasses` and
`liveProperties` become `cell:class:<cls>` and `cell:style:<prop>` slots (ADR 0034,
`architecture-rendering-pipeline.md`'s cell wrapper). The cell's concerns travelling as the card's
slots is where the edge behaviours come from:

- **Prefix-named slots.** `Dashboard.scala` and `Renderer.scala` test for or strip
  `cell:class:` and `cell:style:` in 12 places.
- **`keepsLiveCell`**, a `slots` constraint that exists only because `slots = new { … }` would
  drop them.
- **A throw on a set's clause**: "a live class is a slot, which only a card node has".
- **Two verbs for one thing**: `classWhen` with a plain `Boolean` already falls back to
  `cellClass`.
- **The intent is hard to read**: an author asks for a class on the cell and gets a slot on the
  card.

Customers: `ActiveNode`'s `fh-active` (written into `liveClasses` directly) and `select.pkl`'s
`fh-chosen`. No shipped card calls `cssProperty`.

## What any design keeps

- **Bound by name** (`data-class:<cls>`), so Datastar switches the one class without touching the
  wrapper's others, and the theme swaps it by name (ADR 0020). A whole live class string cannot do
  that on the wrapper: `data-attr:class` replaces every class there.
- **Read on the server like a slot**: CEL or Simple, woken through the entity reverse index,
  inline in the document form plus a binding, then signal frames.
- **A card node's feature**: it needs a component's bound context, so a set's clause cannot take
  one.

## Ideas so far

### A. One verb, and the cell carries them

```pkl
c.button(…).cellClass("fh-wide")                 // static, as today
c.entityButton(l).cellClass("warm", c.isOn(l))   // live: any BooleanInput
c.slider(…).cellStyle("--hue", reading)          // replaces cssProperty
```

The live entries move out of `slots` into the wire `Cell`, beside its static `classes`, and Scala
binds them like slots internally. The prefixes, `keepsLiveCell` and the prefix branches go.
Cost: a wire change (decoder, `WireShapeSuite`, snapshots), the renderer's hot path (RenderBench),
and ADR 0034's choice to share slot resolution moves from the wire into Scala.

### B. Rename only

`classWhen` → `cellClass(name, when)`, `cssProperty` → `cellStyle`, the slot lowering kept. It
reads better and keeps every edge behaviour.

### Considered: a Simple `match` instead of `classWhen`

No; the containment goes the other way. A `match` picks one of several literal classes by state.
`classWhen` ties one class to any yes/no input, such as a count above two, which a `match` cannot
express. A `match` with literal arms could be lowered into one `classWhen` per arm.

## Open

The question this plan is for: an API whose intent is plain, that uses the existing API rather
than adding edge behaviours. Neither A nor B is accepted.
