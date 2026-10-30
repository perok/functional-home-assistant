# Plan: a slot is one named object, and its views are parsed once

Discussed in chat on 2026-10-29, after #654 and #655. Regions get the same treatment next, once this
design settles; they are out of scope here.

**State:** built: views (the Scala half, the new spelling, `cell:class:`), the `Slot` → `Reading`
rename, the Pkl `Slot` handles across the library, and flags and signal writes (decided in chat on
2026-10-30). `tapMod.disabled`'s literal `"1"` stays: it is a bool value read with `ifTrue`, live
on other nodes, not a flag.

## Why

Besides its value, `{{x}}`, a template reaches a slot four more ways: `{{{x__bind}}}` (the signal's
binding), `{{#x__has}}` (the node sets `x`), `{{{x__read}}}` (the value as JavaScript) and
`{{x__signal}}` (the signal's name). The suffix is a string convention that three places must agree
on:

- `core/slot.pkl` spells it (`signalBind`, `ifSlot`, `slotRead`, `signalRef`).
- `Renderer` builds keys like `slot + "__bind"` into the same flat maps as slot values, plus a `"1"`
  per slot for `__has`. A slot named `icon__has` would be read both ways.
- `Dashboard.derivedFrom` strips the suffix off again (`derivedSuffixes`).

Two checks still search the template source rather than its compiled tree: `readErrors` and
`signalErrors` (`template.contains("{{{x__read}}}")`). So a binding inside a section on its own
value passes, which is ADR 0017's silent trap: the tile's icon vanished on the first tap.

And a card spells each slot's name as a string in every place it names it: the `slots` key, the
template, each helper call. Since #654 a typo fails the build, but not in the editor and not at the
line.

## The design

### Views, parsed once

- **Spelling:** Mustache's own namespacing, `{{{x.bind}}}`, `{{#x.has}}…{{/x.has}}`,
  `{{{x.jsValue}}}`, `{{x.signal}}`. A slot name may not contain `.`.
- **Read:** `Templates.Reads` records each slot's views off the compiled tree, the same walk that
  reads names. An unknown view (`x.bnid`) is a build error.
- **Render:** `FhObjectHandler.find` catches `x.view` when mustache first binds the name, so the
  slot and view are split once. mustache.java's own `find` (0.9.14) splits on `.` at every lookup
  and would ask the slot's string for `bind`. `NodeContext` answers by slot and view:
  - `has` from the plan's slot set, so no `"1"` entries;
  - `bind`, `jsValue` and `signal` from per-slot bindings keyed by slot, not by a joined string;
  - a plain `{{x}}` skips the binding maps it misses today.
- **Validate, on the tree:**
  - `readErrors`: a template reads `x.jsValue` and `x` is live and not a signal.
  - `signalErrors`: a signal slot whose template places no `x.bind`; for a handler slot, neither
    `x.jsValue` nor `x.signal`.
  - New: `x.bind` inside a section on `x` itself (`{{#x}}`, `{{^x}}`).
  - New: a template still using an old suffix (`x__bind`) names its replacement, as #655 did for
    regions.
- **Removed:** `derivedSuffixes`, `derivedFrom`, the `__` keys, both `template.contains` checks.

`jsValue` (today's `__read`) is needed: a signal slot has no value in the HTML for a handler to
read, so it prints `$<signal>`, and a literal needs JavaScript escaping inside an attribute, not
HTML escaping (`&#39;` decodes back and closes the string), so it prints `'light/toggle'`. One
template then serves both. Its three uses (`service`, `tapDisabled`, `disabled`) all take both.

### The live cell slots

`cell.class:<name>` and `cell.style:<property>` become `cell:class:<name>` and
`cell:style:<property>`, leaving `.` to views; `Dashboard.CellClassPrefix`/`CellStylePrefix` and
`node.pkl`'s `cellClassPrefix`/`cellStylePrefix` change with them. They are the live cell modifiers (`classWhen`,
`cssProperty`), lowered into slots to share slot resolution and signals; the renderer binds them on
the cell, and no template places them. The rest of the name already follows Datastar's
`data-class:<name>`.

### Pkl: a slot is one object, named once

The classes follow `terminology.md`. A **Reading** is an input with no binding yet: today's Pkl
`Slot` class, renamed `slotMod.Reading`, keeping what it reads (`entityId`, `transform`, `reads`,
`valueWhile`, …). A **Slot** is the named, per-node thing a card binds: `slotMod.Slot`, holding
`name`, `value` (a literal, a `Reading`, or null) and the binding, `signal` and `type`, which move
off the reading because the card sets them where it binds it.

A card module names each slot once, as a module-level handle, and `slots` lists them filled:

```pkl
const local label = slotMod.slot("label")
const local icon = slotMod.slot("icon")

class Badge extends nodes.Node {
  cardDef = new nodes.CardDef {
    template = """
      <span \(label.bind)>\(label)</span>
      \(icon.ifSet("""
        <i class="\(icon)"></i>
        """))
      """
  }
  slots {
    slotMod.liveText(label, text)  // a reading becomes a text signal
    icon.of(glyph)  // a null value: not set
    ...tapMod.tapRoute(tapAction)
  }
}
```


- `toString` is the plain hole: `\(label)` is `{{label}}`. A Pkl class can override `toString`, and
  interpolation uses it (spiked on 0.32.1).
- Views are properties (`bind`, `jsValue`, `signalName`, and `raw` for `{{{x}}}`), so typing
  `label.` lists them; `signal` is taken by the binding. Sections are methods (`ifSet`/`ifUnset`
  on `has`, `ifTrue`/`ifFalse` on the value), balanced by construction. `of(v)` fills one. These
  replace `signalBind`, `signalRef`, `slotRead`, `ifSlot`/`endIfSlot`/`ifNoSlot`/`endIfNoSlot` and
  `secondarySlot` in `core/slot.pkl`; `signalled`, `liveClass`, `liveText` and `secondary` take
  a handle and return the filled slot.
- A misspelt handle is `Cannot find property 'lable'` at its line, during evaluation and in the
  editor.
- A handle is a constant, not a property of the node: the template reads only the name, so every
  node of a card still produces the same template, and `cardsIn` fails loudly if one ever reads a
  value.
- `slots` is a `Listing`, and a slot whose value is null is left out, which replaces
  `when (glyph != null) { ["icon"] = … }`. `Node.slotsByName` is the set ones by name, for Pkl that
  reads a slot back (tests, `valuesNamedIn`).
- **Lowering, so the wire does not change:** a `["slots"]` path converter in the library's output
  renderer turns the Listing into the name→value map the backend decodes, a reading's entry
  carrying its slot's `signal` and `type` as today. Spiked with `PklBuild`'s
  settings (JSON output, `evaluateOutputText`): it matches at every depth and drops nulls. The
  lowering is one function, `nodes.wireSlots`, named by the `output` of `site.pkl` and `entry.pkl`;
  a test probe module that renders nodes without amending either gets the same line from
  `PklWorkspace.withWireOutput`. Only `Node.slots` carries that name. Authors still write no
  `output` block.
- A literal handed a `signal` fails in `wireSlots`: a signal needs a reading.
- `valueWhile` is a bool by position: a reading carries no `type`, so the backend types that slot
  itself.
- Pkl code that reads `slots` as a Mapping moves to the Listing: `Node`'s structural-card
  constraint, `keepsLiveCell`, `valuesNamedIn`, and the slot-producing helpers (`tapRoute`,
  `guardSlots`, `badgeMod.slots`).

### Flags and signal writes

Two hand-written conventions survived the handles: about 17 `when (b) { x.of("1") }` flags, and
`"$" + x.signalName` in the bar, the switch, the slider and the wheel (whose local `server(s)`
reinvents it).

- **`x.setIf(b)`** sets a flag slot when `b` holds and leaves it out otherwise, and the template
  reads it with `ifSet`/`ifUnset`, so the `"1"` it writes is a payload nothing reads, written in
  one place. Not `of(Boolean)`: a slot's value can be a real `false` (`attr:disabled`, ADR 0017),
  so `of(false)` meaning "absent" would be ambiguous.
- `when (x != null) { s.of(x!!) }` becomes `s.of(x)`: `of(null)` already leaves the slot out.
- `bar.pkl`'s `hasOf` goes: it is set exactly when `of` is, which `ofSlot.ifSet` already says.
- `button.pkl`'s `sizeSmall`/`sizeMedium`/`sizeLarge` stay three flags: a theme rewrites class
  tokens in the template text only (`theme.classes.rewriteTemplate`, ADR 0020), so a class held in
  a slot value would escape it.
- **Reads** of a signal become `x.jsValue`, which already prints `$<signal>` for a signal and the
  quoted value otherwise. **Writes** become **`x.assign(js)`**, printing `$<signal> = js`.
  `signalName` stays for the signal-patch filter regexes.
- **`x.signal` on a node where `x` is not a signal fails the build.** It prints nothing there, so
  `$ = …` would ship silently: the slider's `hueMarker` assigns `fillColor`, which is a signal only
  when the slider has a colour reading, safe today only because it sits under `hueTrack`.
  `Templates.Reads` records, for each placement of `x.signal`, the sections around it, each as
  open-on-true or open-on-false (`readCodes` already threads their names). `validate` decides each
  per node: a `y.has` section by whether the node sets `y`, one on a literal `y` by its value
  (absent false, non-empty true); a live slot, `""` or an injected variable counts as possibly
  open. If every section may open and `x` is not a signal on that node, the error names the card,
  the slot and the section. `validate` is timed before and after.

## Decided

- **`jsValue`** for the JS view: it holds for both cases, since a literal is the value and
  `$<signal>` evaluates to it. Not `jsReference` or `clientReference`: half the time it is a
  literal, `terminology.md` already defines **Reference** (a node variable's read), and
  "client-owned" names the browser's own signals.
- **`cell:class:`** for the cell slots.
- **`Slot` and `Reading`**, the vocabulary's own split, over a `NamedSlot` beside today's `Slot`.
- **A duplicate slot name is reported by a check on `Node`**, naming the node's card and the slot,
  so the author can find it; the converter's own error names only the slot.
- **`setIf`** for a flag (not `of(Boolean)` or `fromBoolean`) and **`assign`** for a signal write.

## Migration

- Library: 18 `slots` blocks in 13 files, about 83 helper calls, the cell lowering in `node.pkl`,
  and the `Slot` → `Reading` rename (91 uses in 18 files). A reading amended with a binding
  (`(on) { signal = slotMod.asBind }`) becomes a slot holding it.
- Workspaces' own components that name `slotMod.Slot` as a reading fail Pkl's type check;
  `writing-components.md` shows the new form.
- The starter and demo modules: 9 raw suffix spellings.
- Tests and benchmarks: about 860 hand-written spellings, rewritten mechanically; wire snapshots
  regenerate, and only template strings and cell slot names may change.
- Workspaces: an old spelling fails the build with its replacement named. The HA box's instance
  workspace also still needs #655's region rewrite.

## Tests replaced

Spelling rewrites are mechanical; a test of the suffix mechanism itself (`derivedFrom`, `__has`
filled per slot) is replaced by a test of the view, never weakened.

- `TemplateReadsSuite`'s dotted-name test reads views instead (`x.bind` recorded as a view of `x`),
  plus one for a binding inside its own slot's section.
- `BuildPhaseSuite`: an unknown view and an old `__` spelling, `x.bind` in a section on `x`, and a
  dotted slot name each fail the build.
- Pkl tests read `slotsByName["x"].value` (`.signal`, `.type`) where they read `slots["x"]`.
- `WireShapeSuite` compares `Reading` with `SlotSource` minus `signal`/`type`, and asserts the
  `Slot` declares both.
- `components.test.pkl` facts that pin template text read the flags as `{{#x.has}}` and signal
  reads as `{{{x.jsValue}}}`; wire snapshots change by exactly that respelling.
- `BuildPhaseSuite`: `x.signal` is refused where it renders on a node whose `x` is not a signal,
  and a section the node settles (`has`, a literal, an absent slot) decides whether it renders.
- `SignalSectionSuite` scanned the sources for `signalBind("x")` inside `{{#x}}`, a spelling that
  no longer exists. It now compiles every card in `components.pkl`'s `cards` index and asserts
  `boundInOwnSection` is empty, so shipped cards no test dashboard builds stay covered.

## Perf

RenderBench before and after, `-f 2 -wi 4 -i 5 -prof gc`. Expected flat or slightly lower: no `"1"`
entries, fewer missed map lookups per hole, and no joined keys on the direct path, which builds three
per signal slot on every render. The risk is a dotted name falling through to mustache's own
`find`. The handles add an object per slot to Pkl evaluation, so the demo site's evaluation is
timed before and after too.

Views, measured against main on the same machine; allocation is the steady number, times move
±10% run to run and were flat or better:

| bench | B/op before | B/op after |
|---|---|---|
| page | 1 247 661 | 1 223 340 |
| pageSignals | 1 418 216 | 1 416 972 |
| pageSet | 1 258 165 ± 26 820 | 1 260 481 ± 20 417 |
| pageSetTiles | 2 192 565 ± 21 684 | 2 173 645 ± 21 802 (4 forks) |
| pageWalkStream | 992 852 ± 4 988 | 998 135 ± 15 007 (4 forks) |
| resumeMembers | 191 795 | 191 110 |
| tickRender | 67 889 | 65 872 |
| wireCommon | 59 172 | 57 332 |
| direct | 33 600 | 33 600 |
| mustache | 392 001 | 392 001 |

`pageSetTiles` and `pageWalkStream` first read 1–2% higher with 2 forks; with 4 they sit inside
the spread between forks.

Flags and signal writes: `Dashboard.validate` on the smoke dashboard, 200 runs after 200 warm-up,
three rounds: median 8.0–9.7 ms with the `x.signal` check, 8.1–10.3 ms with its call removed.

Handles, Pkl evaluation of the starter site over the house fixture dump, 15 runs after 5 warm-up,
three rounds against the pre-handles library: median 39–41 ms before, 34–37 ms after.

## Docs

- ADR 0017 (the binding, section and `__read` sections), ADR 0016 (`service__read`), ADR 0034
  (`Slot.type` now sits on the slot, not the reading).
- `docs/terminology.md`: a **view** entry; **Slot** and **Reading** name the two Pkl classes.
- `docs/architecture-rendering-pipeline.md` where it names a suffix.
- `writing-components.md`: teach the handle; its `{{{temperature__bind}}}` example goes.
- Module `CLAUDE.md` (the signal-slots paragraph), `core/slot.pkl` and `core/node.pkl` docs.

## Checks

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'`, CI-mode compile of tests and
benchmarks, scalafix, scalafmt, the snapshot diff read, RenderBench and evaluation timing as above.
