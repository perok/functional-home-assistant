# pkl-lsp: a module `extends` clause kills completion through that module's properties

Not filed upstream yet — this is the write-up to file from, when we get to it.
Found while measuring which namespacing shapes survive editor tooling, before the
`components.pkl` split (see `docs/adr/0006-pkl-authoring-track.md`).

**Environment:** pkl-lsp **0.8.0** (`.pkl-lsp/pkl-lsp-0.8.0.jar`), JDK 25, Linux.
Driven over stdio JSON-RPC directly, so no editor is in the loop.

## The bug

A module that declares `extends "…"` still completes its own top level correctly
— including members inherited from the parent. But completion **through any
property of that module** returns stdlib members only. Removing the `extends`
clause, and changing nothing else, restores it.

## Repro

Three files. `light.pkl` is the module whose members we want to reach:

```pkl
// light.pkl
module light
function controls(l: String, compact: Boolean): String = l
```

```pkl
// base.pkl
open module base
function title(text: String): String = text
```

```pkl
// facade.pkl
module facade extends "base.pkl"      // <-- remove this clause and completion works
import "light.pkl" as lightMod

hidden light: lightMod = lightMod
```

Then, in a consumer, request completion at the trailing dot:

```pkl
module probe
import "facade.pkl" as c
x = c.light.
```

- **Expected:** `controls(String, Boolean)` among the items.
- **Actual:** 11 items, all stdlib (`getClass()`, `toString()`, `hasProperty(String)`, …).
- **With `extends "base.pkl"` deleted from `facade.pkl`:** `controls(String, Boolean)`
  appears as expected.

Completion on `c.` itself is unaffected in both cases, and correctly lists the
inherited `title(String)`.

The same happens when the property is a class instance rather than a module
(`hidden ns: LightNs = new LightNs {}` → `c.ns.`), so it is about the receiver
being a property of an `extends`-ing module, not about module-valued properties
specifically.

### Measuring it

One completion request per file. **Put exactly one trailing dot in the probe
file** — a second incomplete expression earlier in the same file poisons the
parse and makes every position but the last return zero items, which produces
convincing false negatives. That is worth mentioning in the report; it may be a
second (smaller) bug.

## Related findings, same session — not bugs

- **An untyped module property completes nothing:** `hidden light = lightMod`
  gives 0 items and types as `unknown`; `hidden light: lightMod = lightMod`
  gives the full list. Reasonable behaviour, worth knowing: every re-export in
  our library has to carry an explicit type.
- **`signatureHelp` is not implemented.** Server capabilities are
  `codeActionProvider`, `completionProvider`, `definitionProvider`,
  `documentFormattingProvider`, `hoverProvider`, `textDocumentSync`, `workspace`.
  No issue or PR mentioning it exists in apple/pkl-lsp, so there is no in-editor
  parameter hint while typing arguments, and none coming. What the author gets
  instead: completion labels carry **types and arity but no parameter names**
  (`sliderGroup(SlideAxis, List<hass.Entity>)`), and **hover carries the full
  signature with names** plus the doc comment plus a go-to-definition link.
  Hover behaves identically through a typed namespace property.
- **pkl-intellij does not use pkl-lsp.** It is a native PSI plugin (its own
  `PklExprTypeProvider.kt` etc.), so none of the above transfers to IntelliJ
  either way — it is a second, independent implementation, and anything we rely
  on for editor ergonomics wants checking in both.
