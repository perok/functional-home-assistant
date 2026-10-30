# Plan: a slot can hold CSS classes the theme sees

Discussed in chat on 2026-10-30, during #657 (slot views). Lands on the same branch.

**State:** design; no code yet.

## Why

A theme swaps our `fh-` class names for its own (ADR 0020, `ThemeClasses`), in three places: the
card templates once, at compile (`rewriteTemplate`), and the cell wrapper's classes and live-class
bindings at render. A class held in a slot VALUE is in none of them, so the theme never sees it:

- `layout.pkl`'s `Box` puts `fh-justify-*`, `fh-wrap`, `fh-fill-screen` and `fh-start` into its
  `class` slot. No shipped theme maps those yet, so nothing breaks today; a theme that mapped
  `fh-wrap` would be silently ignored.
- The button's size had to become three flags (`sizeSmall`/`sizeMedium`/`sizeLarge`) so each
  `fh-size-*` token is literal template text for the BeerCSS theme to swap (`small`, `large`).
  The size is fixed per node, but the template is shared and Mustache cannot compare, so every
  value needed its own section.

## The design

- **A slot type `cssClass`**, beside `text` and `bool` (`SlotType`, both sides; ADR 0034 already
  expects more types). Its value is a space-separated list of class tokens.
- **Filled through one method:** `x.cssClass(classes: List<String>)` on the Pkl `Slot` sets the
  value and the type; an empty list leaves the slot out.
- **Literal only.** A reading on a `cssClass` slot is a build error: the theme must see every class
  the slot can produce, and a live value is a class it never sees. Each token is checked as a
  plain CSS token, as a cell class already is (`classTokenErrors`).
- **Swapped once, where the templates are.** The renderer's per-node plan (`NodePlan`, built once
  per renderer) expands a `cssClass` slot's tokens through `theme.classes`, so a paint costs
  nothing new, and the wire and `Validated` keep the author's own tokens, as the template does.
- **The cell's static classes move to the same place.** `Renderer.cellClasses` expands
  `cell.classes` on every render today; once per plan instead is one mechanism for static classes,
  and a render does less. RenderBench before and after.

### Customers

- `Box`'s `class` slot: `extraClass.cssClass(…)`, its author `cssClass` and `ownClasses` together.
- The button: one `size` slot, `sizeSlot.cssClass(List("fh-size-\(size)"))`, placed once in
  `pressClass`, replacing the three flags. The BeerCSS theme's `fh-size-medium = ""` swaps to
  nothing, as it does today.

## Not in scope

- **A class that follows live state** (a Simple `match` whose arms are classes). Nothing needs one,
  and `classWhen` covers "this class while that holds". If one is wanted later, it would lower at
  build into one `classWhen` per distinct arm value, since the arms are literals: a class string
  that changes cannot be bound by Datastar's `data-class`, which toggles named classes.
- The live cell classes (`classWhen`) stay as they are: a boolean per class name, swapped by name
  in the binding.

## Wire and snapshots

`Box` slots and the button's size slot gain `"type": "cssClass"`; the button template loses the
three size sections. The snapshot diff is read for exactly that.

## Docs

ADR 0020 (where the swap applies), ADR 0034 (the third slot type), `terminology.md` (**Slot
type**), `writing-components.md` (an `fh-` class from a value goes through `x.cssClass`),
`core/slot.pkl` and `core/node.pkl` docs.

## Checks

A render test with a theme that maps a `Box` class and a button size: both arrive swapped, and a
reading on a `cssClass` slot fails the build.

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`, CI-mode compile of tests and
benchmarks, scalafix, scalafmt, the snapshot diff read, RenderBench before and after.
