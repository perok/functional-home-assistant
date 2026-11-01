# Plan: a slot's type says what it holds, and a literal is its own case

Discussed in chat on 2026-10-31 and 2026-11-01, reviewing #657 (slot views). Lands on the same
branch.

**State:** P1, P2 and P4 built (0d25e25a, 12fb1c8a, aca159c9). P3 (a value step) and P5 are next,
waiting on a go.

## Why

The slot model makes a card author, or a function, state things the code already knows:

- `SlotType` mixes two facts. `text` and `bool` say what the value IS; `cssClass` is something
  done to it, the theme's class swap (ADR 0020).
- `SlotSource` is one flat case class, so a literal carries a transform nothing reads, and every
  consumer tests `literal` first.
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

### P3. `type` is what the value is; the theme's swap is a step on the value

- `SlotType` is `text | bool`, on both sides. `slotMod.bool(name)` declares a bool handle:
  `tapMod.disabled` and `tapMod.current` use it and are filled with `of`, so `booleanSlot` and the
  fill-time `type = "bool"` in `tap.pkl` go.
- **The theme's class swap is a step on the slot's resolved value**, whichever kind it is: a
  literal is swapped once per renderer and inlined, a reading runs its transform and the result is
  swapped, a signal's value included. Pkl cannot see where a template puts a hole, so the card says
  it on the handle: `slotMod.cssClass(name)` sets the step, and every fill (`of`, `setIf`,
  `liveClass`) keeps it, as `type` is kept today. Its five callers stay as they are.
- One step, so one field: `themeClasses` beside `signal` and `type`, on the wire and on
  `TemplateSlot`; `Placement` carries it into `BoundSlot`, where `classed` and the signal name's
  suffix read it in place of `type == CssClass`. `SlotType.CssClass` goes. A list of steps waits
  for a second step.
- A literal never carries a `type`: both bool checks skip literals (`Dashboard.scala:1638`, `1644`).
  `wireSlots` writes a literal as a bare string, or `{literal, themeClasses}` with the step; the
  WireShapeSuite exception is renamed with it. A bool reading still carries `"type": "bool"`: the
  Scala build checks the reading's CEL type, which Pkl cannot.

### P5. `SlotSource` is `Literal | Reading`

- Today one flat case class: a literal still carries an unused default `transform`
  (`Simple.State()`), and 24 lines of `Dashboard` and `Renderer` test `literal` before reading
  anything else. As a sum, a literal with a transform cannot be built, and those tests become
  matches on the case.
- `Literal(value)` is what can be inlined: baked into the node plan once, no entity, no signal,
  never recomputed.
- `Reading` keeps every field it has: `entityId`, `transform`, `default`, `bypassUnavailable`,
  `reads`, `fetch`, `values`, `valueWhile`. What wakes it still comes from what it reads, not from
  the case: its entity or the card's subject, the entities its Simple names (a Simple may read
  another entity), those behind its expression values (a tally, a condition), and its
  `valueWhile`'s. `reads` says what a move does: `live` re-runs it, `once` runs it once per entity
  identity, `onRender` on every render.
- The wire does not change: a bare string or a `{literal, …}` object decodes to `Literal`,
  anything else to `Reading`. Pkl's `value: String|Reading` is already this split.
- About 100 test lines build `SlotSource(literal = Some(…))`; they become `SlotSource.Literal(…)`,
  a mechanical change asked for with this step.

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

P3: `"type": "cssClass"` becomes `"themeClasses": true`, and a bool literal loses its `type`. P5:
none. P4: the slider, onoff and wheel templates gain their guards; where the slots are signals
the rendered bytes do not move. Regenerated with `dashboardSnapshotsUpdate`; the diff is read
before the commit.

## Tests that change

Approval for these is asked with this plan.

- BuildPhaseSuite, "x.signal is refused wherever it renders on a node whose x is not a signal",
  becomes the template rule's test (P4).
- `components.test.pkl:1142`, `large.type == "cssClass"`, reads `large.themeClasses` (P3).
- Wire JSON where a literal was `{literal, type}` (P3, mechanical).
- Test fixtures building `SlotSource(literal = Some(…))` (P5, mechanical).

## Docs

ADR 0006 (handle constructors, `ifSignal`, `SlotSource`'s two cases), ADR 0017 (the signal
guard), ADR 0020 (the swap is a step the handle sets), ADR 0034 (`Placement`, cell entries),
`terminology.md` (the slot type entry), `writing-components.md` (`ifSignal` and its separator
rule), and the architecture doc where the node plan's shape moves.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`.
RenderBench before and after P1 to P3 and P5; validate timing for P4.
