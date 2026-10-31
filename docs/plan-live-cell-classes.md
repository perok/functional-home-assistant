# Plan: cell slots

Raised in chat on 2026-10-30, during #657 (slot views); the design was settled in chat on
2026-10-31.

**State:** built. Step 1 kept the wire byte-identical; step 2's snapshot diff is the nine
`cell:class:fh-active` slots moving to `cell.classes` (no `signal`, no `type`), the static lists
becoming `"name": "1"` maps, and an empty `styles` on each cell.

## Why

`classWhen(cls, when)` and `cssProperty(name, reading)` work on any card node because the renderer
binds them on the node's `.fh-cell` wrapper, not in the card's template. To reach the renderer they
travel in the card's own `slots`, under reserved names: `node.pkl`'s `liveClasses` and
`liveProperties` become `cell:class:<cls>` and `cell:style:<prop>` slots (ADR 0034,
`architecture-rendering-pipeline.md`'s cell wrapper). The cell's concerns travelling as the card's
slots is where the edge behaviours come from:

- **Prefix-named slots.** `Dashboard.scala` and `Renderer.scala` test for or strip
  `cell:class:` and `cell:style:` in 12 places, and both prefixes are declared twice (Pkl, Scala).
- **The class is said twice**: in the name and again in the signal (`class:<cls>`), and
  `cellClassErrors` checks that the two agree.
- **`keepsLiveCell`**, a `slots` constraint that exists only because `slots = new { … }` would
  drop them.
- **The intent is hard to read**: an author asks for a class on the cell and gets a slot on the
  card.

Customers: `ActiveNode`'s `fh-active` (written into `liveClasses` directly) and `select.pkl`'s
`fh-chosen`. No shipped card calls `cssProperty`.

## What the design keeps

- **Bound by name** (`data-class:<cls>`, `data-style:<prop>`), so Datastar switches one class
  without touching the wrapper's others (or the overlay's `fh-edit-hl`), and the theme swaps it by
  name (ADR 0020). A whole live class string cannot do that: `data-attr:class` replaces every class.
  One signal per entry, because each reads its own entity.
- **Read on the server like any slot**: CEL or Simple, woken through the entity reverse index,
  inline in the document form plus a binding, then signal frames.
- **The wrapper markup stays the renderer's** (ADR 0008): the template is shared per card, the
  entries are per node, and the seed must come before the bindings.
- **A card node's feature**: a set has no bound context, so its cell takes literals only.

## The design

A **cell slot** is an ordinary slot that the renderer places on the cell, where a **template slot**
is placed by the card's template. The map it sits in is its placement; its key is the class name or
the CSS property. No placement field, no reserved names in `slots`, nothing parsed out of a name.

```pkl
cell {
  classes { ["fh-cols-6"] = "1"; ["fh-on"] = activeReading }
  styles  { ["--fh-hue"] = hueReading }
}
```
```json
"slots": { "label": "…", "class": "…" },
"cell": {
  "classes": { "fh-cols-6": "1", "fh-on": { "entityId": "light.x", "transform": "…" } },
  "styles":  { "--fh-hue": { "entityId": "light.x", "transform": "…" } }
}
```

- A class entry is a literal `"1"` (static; the `setIf` flag form, and an off one is simply not
  written) or a live reading. A style entry is a live reading, as `cssProperty` takes today.
- Box's own `class` slot stays a template slot: it is in `slots`, a different map.
- The builders keep writing `node.pkl`'s mappings (`addCellClass` and its one-class-per-family
  rule, `liveClasses`, `liveProperties`); those go to the wire as `cell` instead of being copied
  into `slots`.

### Step 1: split `SlotSource` the way Pkl already is

Pkl has a `Reading` (what to read) and a `Slot` (a name, plus `signal` and `type`); Scala's
`SlotSource` is both merged, which `WireShapeSuite` excludes as `onSlot`. A value that may not carry
a signal (`valueWhileSlot` today, a cell slot next) still decodes one, and `BoundSlot.bind`
overwrites `type` on it.

```scala
// Pkl `Reading`'s twin; `literal` is the bare-string form.
case class SlotSource(entityId, transform, default, bypassUnavailable, literal, reads,
                      fetch, values, valueWhileSlot: Option[SlotSource])

// Pkl `Slot`'s twin (working name).
case class TemplateSlot(source: SlotSource, signal: Option[SignalBind], `type`: SlotType)
```

- The wire stays flat: `TemplateSlot`'s decoder reads the `SlotSource` from the same object, then
  `signal` and `type`. No wire change, so the snapshots stay byte-identical.
- `Component.slots` holds `TemplateSlot`s, which export their source's fields as `BoundSlot`
  does. `BoundSlot` carries the source plus the signal and type, so its readers stay put; code
  reading `signal`/`type` off a `SlotSource` moves.
- `valueWhileSlot`'s bool comes from its position, built in `bind`, not overwritten.
- `WireShapeSuite` checks `Reading` against `SlotSource` and `Slot` against `TemplateSlot`; the
  `onSlot` exclusion goes.

### Step 2: cell slots

- **Wire:** `Cell(classes: ListMap[String, SlotSource], styles: ListMap[String, SlotSource])`,
  read by the same `Decoder[SlotSource]`, in wire order (the class attribute's).
- **Keys as a type:** `Component.bound` and the renderer's per-node maps key by

  ```scala
  type SlotKey = String | CellKey   // a template slot by its name
  enum CellKey:
    case Class(name: String)
    case Style(property: String)
  ```

  A template's lookup by name is a `String` and allocates nothing on the render path; a cell key
  never equals one, so a template cannot see a cell slot, and the template check ("every slot must
  be named") reads `slots`, which holds none. Readers that walk every slot (the reverse index,
  fetches, the once-cache) see cell slots without change.
- **Signal and type from the key:** `bound` builds a cell class reading as `Class(name)` and bool,
  a cell style as `Style(property)` and text. A literal class gets no signal; it is a static class.
- **Rendering:** the wrapper's `class` holds every literal class plus the live ones that hold at
  render, each theme-swapped; `style` joins the live properties; one `data-class:<name>` or
  `data-style:<prop>` per live entry, after the seed (`cellBindingsInto`'s order).
- **Checks:** `classTokenErrors` per class key; a class reading must be live; `cellStyleErrors`'
  custom-property and not-a-bool rules per style key. Their "must be a `class:x` signal" halves go,
  since the signal can no longer disagree. A set's cell holding a reading is refused, mirroring the
  Pkl throw.

**Removed:** both prefix constants, the 12 prefix sites, `keepsLiveCell`, the prefix branches of
`readByRenderer` and `signalErrors`, `Cell.classes` as a list, and the lowering loop in
`node.pkl`'s `slots`.

## Considered

- **A placement field on each slot** (`at: "cell"`) and **reserved `class`/`style` keys in
  `slots`**: both state the placement per value or in the template's names (Box's `class`
  collides). The map is the placement stated once per node.
- **One server-side signal holding the whole class string**: one slot would need several readings,
  and `data-attr:class` replaces the wrapper's other classes.
- **Deriving the signal in `bound` over today's `SlotSource`**: a wrong `signal` on the wire would
  decode and be silently replaced. Step 1 removes the field instead.
- **A Simple `match` instead of `classWhen`**: no. A `match` picks one of several literal classes
  by state; `classWhen` ties one class to any yes/no input, such as a count above two.

## The verbs

`cellClass` and `classWhen` keep their names; `cssProperty` becomes `cellStyle`, after the map it
writes. One `cellClass(name, when)` is out: Pkl has no overloading ("Duplicate definition of
member"), so a static class would read `cellClass("fh-wide", true)`.

## Later

- Whether `cellStyle` is needed at all: no shipped card calls it. Looked at once this lands.
- Rejecting unknown wire fields: the model's decoders ignore them
  (`Configuration.default.withDefaults`).

## Costs and checks

- Step 1: snapshots byte-identical; `WireShapeSuite` tightened.
- Step 2: a wire change (decoder, `WireShapeSuite`, snapshots regenerated with
  `dashboardSnapshotsUpdate` and the diff read); RenderBench before and after.
- Tests that read `cell.classes` or the prefixed slots (the layout tests, `LiveCellClassSuite`,
  `FailedDashboardSuite`) move to the new shape; approved on 2026-10-31.
- Docs in the same commits: ADR 0008 (the cell), ADR 0034 (live cell classes and properties),
  `terminology.md` (**Slot**: cell slot vs template slot; **Cell**), the architecture doc, and
  `writing-components.md` if the verbs change.
