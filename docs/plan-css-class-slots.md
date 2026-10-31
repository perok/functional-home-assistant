# Plan: a slot can hold CSS classes the theme sees

Discussed in chat on 2026-10-30, during #657 (slot views). Lands on the same branch.

**State:** built. A typed literal reaches the wire as `{literal, type}`, which the `SlotSource`
decoder already read; a bare string would have lost its type.

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
- `slider.pkl` sets its `group` slot to `fh-slider-group` on a slider with members: the same gap
  as `Box`, for one class.
- `slotMod.liveClass` puts a reading into an element's whole `class` (the button's glyph, the
  badge icon), and `select.pkl`'s options put an `mdi …` literal there; no swap reaches either.

## The design

- **A slot type `cssClass`**, beside `text` and `bool` (`SlotType`, both sides). Its value is a
  space-separated class list, as an HTML `class` attribute is.
- **The type is on the handle.** `slotMod.cssClass(name)` makes a handle whose `type` is
  `"cssClass"`, filled like any other: `of`, `setIf`, `signalled` and `liveClass` amend the handle,
  so the type rides along. There is no list method; a caller with several classes joins them, as
  `Box` does.

  ```pkl
  const local sizeClass = slotMod.cssClass("size")
  const local pressClass: String = "fh-press \(sizeClass) \(glyphSlot.ifSet("fh-badged"))"

  slots { sizeClass.of("fh-size-\(size)") }   // the button's size "small" → "fh-size-small"
  ```

  The value is the whole class name because the theme maps whole names (`fh-size-small` →
  `small`); `fh-size-{{size}}` in the template would hide it from the theme just as a value does
  today. The handle must not share a name with a property of the card: inside the class the
  property wins.
- **A literal or a reading**, like every other slot.
- **One swap, where a value is resolved** (`ThemeClasses.expandAll`): a literal once per renderer,
  in the node's plan (`NodePlan.constants`); a reading each time it resolves, which covers both the
  bytes and a signal's value. Nothing runs on the client, and nothing at all when the theme maps
  no classes. The wire and `Validated` keep the author's own classes, as they keep the author's
  templates.
- **A `cssClass` signal's name carries the type.** A display signal is named by what it reads
  (`Renderer.signalName`, `_e.<domain>.<object>.<transform>`), so a class slot and a text slot
  reading the same thing would share one signal while wanting different values, one swapped and
  one raw.
- **No build-time class check for slots.** `{{x}}` escapes a slot's value, so an odd token cannot
  break the markup; the cell's classes need `classTokenErrors` because the renderer writes them
  unescaped.

### Customers

- `Box`: `extraClass` becomes `slotMod.cssClass("class")`.
- The button: one `sizeClass` slot, replacing the three flags and their three sections in
  `pressClass`. The BeerCSS theme's `fh-size-medium = ""` swaps to nothing, as it does today.
- `liveClass`: the button's `glyphSlot` and `badge.pkl`'s `icon` become `cssClass` handles, and so
  does `select.pkl`'s `glyphSlot` (its two option cards). Their values are `mdi …`, which no theme
  maps, so their output does not change.
- The slider's `group` becomes a flag instead: `groupSlot.setIf(!members.isEmpty)` and
  `groupSlot.ifSet("fh-slider-group")`. One class, on or off, is what a flag is for, and the class
  is then template text the theme already rewrites.

Every slot placed inside a `class` attribute was found by scanning the snapshot templates and the
library's `class="…"` attributes; the list above is all of them.

In a `class` attribute this work touches, the separating space sits in the text before a section,
never inside it: `"fh-card fh-slider-card \(groupSlot.ifSet("fh-slider-group"))"`. An unset
section leaves a trailing space, which HTML ignores.

### Classes Scala writes

Two more places write `fh-` classes that no swap reaches, outside any slot. Both are swapped with
`rewriteTemplate`, the pass the card templates already go through:

- **The page shell's connection banners** (`Server.pageInto`: `fh-offline`, `fh-offline-sse`,
  `fh-offline-ha`, `fh-offline-action`), through the page's renderer's theme classes.
- **A failed fetch's label** (`Fetches.label`, the runtime's copy of `core/text.pkl`'s `label`,
  "Chart unavailable"). The Pkl copy is template text and is swapped; the runtime one is not. The
  renderer swaps a read whose answer is the failure (`Staged.FailedVersion`), so a working chart
  pays nothing.

## Not in scope

- The live cell classes and properties (`classWhen`, `cellStyle`): their API is
  `docs/plan-live-cell-classes.md`, picked up after this.
- Swapping the cell's static classes once per plan instead of per render
  (`Renderer.cellClassesInto`): a render-speed change unrelated to slots, for its own RenderBench
  pass.
- A `bool` type on the handle; `booleanSlot` sets it per fill today.

## Wire and snapshots

`Box`'s `class`, the button's `size` and the glyph and icon slots gain `"type": "cssClass"`; the
button's three flag slots become one `size` slot and its template loses the three size sections;
the slider's `group` value becomes `"1"` and its template section holds the class. The snapshot
diff is read for exactly that.

## Docs

ADR 0020 (a `cssClass` value is swapped too), ADR 0017 (a `cssClass` signal's name),
`architecture-rendering-pipeline.md` (where the swap runs, the signal name), `terminology.md`
(**Slot type**), `writing-components.md` (an `fh-` class in a value goes in a `cssClass` slot),
`core/slot.pkl` docs.

## Checks

- A render test with a theme that maps a `Box` class, a button size and a `liveClass` reading's
  class: each arrives swapped, the reading in the document form and in its signal frame.
- One dashboard with a text slot and a `cssClass` slot reading the same thing: the class comes out
  swapped and the text raw, under two signals.
- Under a theme that maps them, the shell's banner classes and a failed chart's label arrive
  swapped.
- `sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`, CI-mode compile of tests and
  benchmarks, scalafix, scalafmt, the snapshot diff read, RenderBench before and after.
