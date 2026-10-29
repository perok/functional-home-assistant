# Plan: a slot is one named object, and its views are parsed once

Discussed in chat on 2026-10-29, after #654 and #655. Regions get the same treatment next, once this
design settles; they are out of scope here.

**State:** design. The decisions under "Open" are still being settled; no code yet.

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

A card holds each slot as a hidden property, and `slots` lists them:

```pkl
hidden label: slotMod.Slot = new { name = "label"; value = slotMod.labelSlot(null, text) }
hidden icon: slotMod.Slot = new { name = "icon"; value = glyph; signal = slotMod.asAttr("class") }  // null value: not set
cardDef = new nodes.CardDef {
  template = """
    <span \(label.bind)>\(label)</span>
    \(icon.ifSet("""
      <i class="\(icon)" \(icon.bind)></i>
      """))
    """
}
slots { label; icon; ...tapMod.tapRoute(tapAction) }
```

- `toString` is the plain hole: `\(label)` is `{{label}}`. A Pkl class can override `toString`, and
  interpolation uses it (spiked on 0.32.1).
- Views are properties (`bind`, `jsValue`, `signal`), so typing `label.` lists them; sections are
  methods (`ifSet(body)`, `ifUnset(body)`), balanced by construction. These replace the seven
  helpers in `core/slot.pkl`.
- A misspelt handle is `Cannot find property 'lable'` at its line, during evaluation and in the
  editor.
- The template reads only the name, so every node of a card still produces the same template;
  `cardsIn` fails loudly if one ever reads a value.
- `slots` is a `Listing`, and a slot whose value is null is left out, which replaces
  `when (glyph != null) { ["icon"] = … }`. A subclass changes a slot by amending its property.
- **Lowering, so the wire does not change:** a `["slots"]` path converter in the library's output
  renderer turns the Listing into the name→value map the backend decodes, a reading's entry
  carrying its slot's `signal` and `type` as today. Spiked with `PklBuild`'s
  settings (JSON output, `evaluateOutputText`): it matches at every depth, drops nulls, and a
  duplicate name fails. The converters are one constant in `core/node.pkl`, used by `site.pkl`'s
  `output` and by the Pkl tests that render a node with a bare `new JsonRenderer {}`. Only
  `Node.slots` carries that name. Authors still write no `output` block.
- Pkl code that reads `slots` as a Mapping moves to the Listing: `Node`'s structural-card
  constraint, `keepsLiveCell`, `valuesNamedIn`, and the slot-producing helpers (`tapRoute`,
  `guardSlots`, `badgeMod.slots`).

## Decided

- **`jsValue`** for the JS view: it holds for both cases, since a literal is the value and
  `$<signal>` evaluates to it. Not `jsReference` or `clientReference`: half the time it is a
  literal, `terminology.md` already defines **Reference** (a node variable's read), and
  "client-owned" names the browser's own signals.
- **`cell:class:`** for the cell slots.
- **`Slot` and `Reading`**, the vocabulary's own split, over a `NamedSlot` beside today's `Slot`.
- **A duplicate slot name is reported by a check on `Node`**, naming the node's card and the slot,
  so the author can find it; the converter's own error names only the slot.

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

Listed here as the code lands. Spelling rewrites are mechanical; a test of the suffix mechanism
itself (`derivedFrom`, `__has` filled per slot) is replaced by a test of the view, never weakened.

## Perf

RenderBench before and after, `-f 2 -wi 4 -i 5 -prof gc`. Expected flat or slightly lower: no `"1"`
entries, fewer missed map lookups per hole, and no joined keys on the direct path, which builds three
per signal slot on every render. The risk is a dotted name falling through to mustache's own
`find`. The handles add an object per slot to Pkl evaluation, so the demo site's evaluation is
timed before and after too.

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
