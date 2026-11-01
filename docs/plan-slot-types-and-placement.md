# Plan: a slot's type says what it holds; the template says where it sits

Discussed in chat on 2026-10-31 and 2026-11-01, reviewing #657 (slot views). Lands on the same
branch.

**State:** P1 and P4 agreed; P2 and P3 proposed, waiting on a yes. P3's shape is still being
discussed: derived from the template, or a value step its handle injects. No code yet.

## Why

The slot model makes a card author, or a function, state things the code already knows:

- `SlotType` mixes two facts. `text` and `bool` say what the value IS; `cssClass` says where the
  card PUTS it (in a `class` attribute, so the theme's class rules reach it, ADR 0020). The template
  already says the second.
- `bool` is set at fill time (`node.pkl`'s `booleanSlot`, `tap.pkl`'s `current`), where
  `cssClass` is on the handle.
- `BoundSlot.bindAs(values, signal, tpe)` takes a placement by position, and the cell's caller
  passes "no signal" for a literal class: a class-only fact in a general function. Literal cell
  classes sit in `Component.bound` although nothing reads them there; the renderer writes them
  from `Cell`.
- `{{x.signal}}` prints nothing on a node whose `x` is not a signal, so `signalPlacementErrors`
  evaluates each node's sections to decide whether a placement renders (`opens`). A card has no
  way to say "only where `x` is a signal".
- The cell's literal classes go through the theme's swap on every render
  (`Renderer.cellClassesInto`), though they are fixed per node.

## Steps, one commit each

### P1. A `Placement` replaces `bindAs`

```scala
final case class Placement(signal: Option[SignalBind], `type`: SlotType)

enum CellKey:
  case Class(name: String)
  case Style(property: String)
  def placement: Placement = this match
    case Class(n) => Placement(Some(SignalBind.Class(n)), SlotType.Bool)
    case Style(p) => Placement(Some(SignalBind.Style(p)), SlotType.Text)
```

- `TemplateSlot.placement` comes from its wire fields; `BoundSlot.bindAt(values, at)(s)` binds a
  `SlotSource` (`bind` still takes a template slot); `valueWhile` binds at `Placement.Bool`.
- `Component.bound` holds the cell's live entries only. A literal class stays in `Cell`, where
  `cellClassesInto` already reads it, and the node plan's constants lose their `CellKey` entries,
  which no lookup reads (a template looks up by name, `Renderer.scala:1558`).
- A literal cell style is refused from `Cell`, in `cellErrors`. `cellClassErrors` and
  `cellStyleErrors` lose their literal tests.

No wire change.

### P2. The cell's literal classes are swapped once

`cellClassesInto` runs each literal class through `themeClasses.expand` on every render. Each
class's expanded text, leading space included, is computed once per renderer and kept by name, so
a render appends a string; a set's wrapper and the live classes share it. Wire order is kept, so no
byte moves. Kept only if RenderBench shows a gain; otherwise it becomes an
`experiments.md` entry.

### P3. `type` is what the value is; a class attribute decides the swap

- `SlotType` is `text | bool`, on both sides. `slotMod.bool(name)` declares a bool handle:
  `tapMod.disabled` and `tapMod.current` use it and are filled with `of`, so `booleanSlot` and the
  fill-time `type = "bool"` in `tap.pkl` go.
- **A slot with a hole inside a `class="…"` value gets the theme's swap.** A hole is `{{x}}` or
  `{{{x}}}`. Sections (`{{#x.has}}`) and views (`{{{x.bind}}}`) are not holes; a section's literal
  text (`fh-badged`) is template text, swapped as today. `Templates.card` finds the holes with the
  scan `ThemeClasses.rewriteTemplate` already makes (one shared regex), whether or not a theme is
  set. A slot signalled as `attr:class` (`slotMod.liveClass`) is class-placed too: its signal
  replaces the class attribute.
- The swap stays per slot, where the value resolves (ADR 0020). One value feeds the hole, the seed
  and every frame, and a swap is not idempotent: a content that keeps its class
  (`fh-busy-spin` → `fh-busy-spin` plus the framework's) grows on a second pass. So a slot with holes
  both inside and outside a class attribute is a build error.
- Only the renderer sees a node and its card's template together, so the flag lives in the node
  plan (`Planned`), and the signal name's `|cssClass` suffix is taken from there.
  `Renderer.scala:1849` names signals off `plan.node.bound` and must read the plan's slots instead.
- `slotMod.cssClass` and `SlotType.CssClass` go. Their five callers (the button's glyph and size,
  the select's glyph, the badge's icon, `layout.pkl`'s class) become `slotMod.slot`. A scan of the
  snapshot templates finds exactly those holes in class attributes, so the rule reproduces
  today's declarations.
- A literal never carries a type. Only the swap read one, and both bool checks skip literals
  (`Dashboard.scala:1638`, `1644`). `wireSlots` writes every literal as a bare string, so the
  `{literal, type}` form and its WireShapeSuite exception go. A bool reading still carries
  `"type": "bool"`: the Scala build checks the reading's CEL type, which Pkl cannot.

### P4. `x.ifSignal(body)` and a template rule replace the per-node check

- `function ifSignal(body: String): String = "{{#\(name).signal}}\(body){{/\(name).signal}}"`. The
  signal's name is in both render forms, so unlike `{{#x}}` the section never closes on the first
  patch. Spike first: a mustache.java section over the `signal` view's String, and `{{x.signal}}`
  still resolving inside it.
- The rule, per card at compile: a `{{x.signal}}` (so every `assign` and `signalName`) outside a
  `{{#x.signal}}` section is a build error. `signalPlacementErrors` and `opens` go, and the check
  runs once per card rather than per node.
- A separator goes inside the section (`x.ifSignal(", " + x.assign(js))`), since a leftover `a, `
  is broken JavaScript. That is the reverse of the class-space rule, so writing-components states
  both.
- Wrapped: the slider's fill, state and fill-colour writes and its patch filter, `onoff.pkl`'s
  `checked` write, and `wheel.pkl`'s patch filter (three slots, so three nested guards). The
  slider's `hueTrack` and `^toggle.has` sections stay: they choose which drag code applies.
- Accepted cost: a card whose author forgot `signal = …` gets a write that paints nothing, where
  today the build names the slot.

## Not in this plan

- A `number` type, together with slot-views point 4 (a `{{x}}` inside a Datastar expression must
  be a number, a bool, or `x.jsValue`). The bare holes in expressions today are the slider's
  `min`/`max` (literal numbers Pkl already types) and text: the call route's pieces, `dataValue`,
  `valueRest`. The check is clean only once a tap's call is no longer spelled out in templates.
- How a tap's call reaches the server: a declared call id in place of the spelled-out URL. Its
  own design round first, then its own plan (ADRs 0016, 0023, 0024).
- `cellStyle` stays: a live custom property on the cell, for any author.

## Wire and snapshots

P3: the literal glyph, size, icon and class slots become bare strings, and `"type": "cssClass"`
goes. P4: the slider, onoff and wheel templates gain their guards; where the slots are signals
the rendered bytes do not move. Regenerated with `dashboardSnapshotsUpdate`; the diff is read
before the commit.

## Tests that change

Approval for these is asked with this plan.

- BuildPhaseSuite, "x.signal is refused wherever it renders on a node whose x is not a signal",
  becomes the template rule's test (P4).
- `components.test.pkl:1142`, `large.type == "cssClass"`: the swap is asserted on the rendered
  button instead (P3).
- ThemeClassesPageSuite's test card: `slotMod.cssClass("look")` becomes `slotMod.slot("look")`,
  same assertions (P3).
- Wire JSON where a literal was `{literal, type}` (P3, mechanical).

## Docs

ADR 0006 (handle constructors, `ifSignal`), ADR 0017 (the signal guard), ADR 0020 (a class
attribute decides the swap), ADR 0034 (`Placement`, cell entries), `terminology.md` (the slot type
entry), `writing-components.md` (no `cssClass`; `ifSignal` and its separator rule), and the
architecture doc where the node plan's shape moves.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`.
RenderBench before and after P1 to P3; validate timing for P4.
