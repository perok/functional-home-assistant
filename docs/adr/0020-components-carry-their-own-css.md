# ADR 0020 — Components carry their own CSS; the theme is the paint

- **Status:** Accepted
- **Date:** 2026-08-17
- **Scope:** `lib/core/css.pkl` (new), `lib/core/node.pkl`, `lib/core/tap.pkl`,
  `lib/theme.pkl` + `lib/theme-beer.pkl`, every `lib/components/*.pkl`,
  `lib/entry.pkl`, `model/Dashboard.scala`, `model/ThemeClasses.scala`,
  `runtime/Renderer.scala`, `runtime/Templates.scala`
- **Refines:** ADR 0008 (which put the `fh-` layout contract in `theme.pkl`) and
  ADR 0019 (whose "the busy look is hardcoded to BeerCSS's classes" debt this
  closes). ADR 0015 owns the library's module tiers; this adds one module to them.

## Context

Every rule the dashboard needed lived in one string: `theme-beer.pkl`'s `styles`,
~250 lines. Three unrelated things were in there.

- **The layout contract** (`.fh-grid`/`.fh-row`/`.fh-cell`/`.fh-cols-*`) — the
  same for every theme, and already written as a shared `const layoutCss` each
  theme was asked to interpolate at the top of its own `styles`. "Asked to" is
  the problem: a theme that forgot produced a dashboard with no layout at all.
- **Each card's structure** — `.slider-head`'s flex line, `.entity-info-attrs`'
  scroll cap, `.popup-close`'s corner. Working on the slider meant editing the
  slider's markup in one file and the 30 rules that make it a slider in another,
  400 lines away, with nothing keeping the two in step.
- **The actual theme** — the MD3 palette, BeerCSS's own knobs, the paint.

The cost of the second one compounds: a second theme could not exist without
re-implementing every card, and a card could not be reviewed as a whole.

## The decision

**Three layers, one owner each, concatenated in cascade order.**

| Layer | Owner | Holds |
|---|---|---|
| 1. base | `lib/core/css.pkl`, put on `Dashboard.css` by `entry.pkl` | the `fh-` layout contract, the `--fh-*` variables, and the classes the RUNTIME emits or binds: `fh-disabled`/`fh-loading`/`fh-busy-spin`, the offline banners, the toast, the shared `.state`/`.section` |
| 2. components | each card's `cardDef.css` | the structure of the markup that card emits |
| 3. theme | `theme.styles` | the palette, the `--fh-*` re-pointing, the framework's own class names, and any retune of 1–2 |

`Renderer.themeStyleTag` emits them in that order inside the one
`<style id="fh-theme">`. Later beats earlier by document order, so a theme
overrides a card and a card overrides the base — and neither lower layer has to
know it might be overridden, which is the property that makes the split cheap.

**Layer 1 is a dashboard property, not a theme property.** That is the whole
difference between "reusable" and "guaranteed": `entry.pkl` assigns
`css = cssMod.baseCss`, so a theme cannot omit the layout contract, only
override it.

### The colour seam: `--fh-*`

A card's CSS may not name a framework's colour role. `core/css.pkl` defines one
semantic variable per job (`--fh-accent`, `--fh-text-dim`, `--fh-surface-alt`,
`--fh-error`, …) defaulted to the HA-named token that means the same thing, and
a theme re-points them to whatever it has. BeerCSS points `--fh-surface-alt` at
`--surface-container-highest`; a theme with no such tier inherits
`--secondary-background-color` and still renders correctly.

This is the mirror of the token bridge that was already there — `theme-beer`
pointing *framework* names at *HA* tokens — and both are needed. Cards cannot
simply read the HA token names: three of the colours they use
(`--surface-container-highest`, `--surface-container`, `--on-error`) have no HA
equivalent, approximating them moves dark-mode pixels, and a theme wanting to fix
that would have to redefine an HA token globally, which the framework bridge
reads too.

### How a theme gets its OWN class names into a card's markup

The spinner is the case that forced this. Its look is BeerCSS's `.shape` +
`.loading-indicator` — an SVG mask that morphs itself — and those are *class
names*, bound by `core/tap.pkl`, in the framework-agnostic core kit. CSS cannot
launder that: there is no `@extend`, so a neutral class cannot inherit a
framework's rules, and the mask asset is only reachable through BeerCSS's own
selector.

So cards write only `fh-` classes, and a theme declares a rule per `fh-` class
it has an opinion on: **add** its own classes beside it, or **replace** it.

```
core/tap.pkl     emits     data-class:fh-busy-spin="$_{{id}}__busy_slow"
theme-beer.pkl   declares  classes { ["fh-busy-spin"] = replace("shape loading-indicator") }
the server       renders   data-class:shape="…" data-class:loading-indicator="…"
```

**The server applies the rules, not the registry.** A card's markup is a
class-level `cardDef` default, so Pkl can only reach it once the registry is
built — but the classes that most need a theme's word are not all in templates.
The `.fh-cell` wrapper, its `.columns(n)` spans and its `classWhen` live classes
are emitted by `Renderer`, so a Pkl-side rewrite would cover half the markup and
leave a second mechanism for the rest. `Theme.classes` therefore rides the wire,
and `ThemeClasses` rewrites every card template once, when it is compiled
(`class="…"` tokens and `data-class:fh-…` bindings), and expands each class the
renderer emits. `dashboard.json` shows the rules beside unrewritten templates,
which is the "what is going on" a reader needs.

**Replace exists for the spinner.** Adding BeerCSS's mask beside `fh-busy-spin`
would leave the base ring drawing under it. Replacing a class takes the base
CSS's rules with it, so `fh-cell` and `fh-group` take add only — the shell
script and the layout select on them — and `Dashboard.validate` refuses a
replace there. A rule names an `fh-` class: those are the card's contract, and
the object form `data-class="{…}"`, which only BeerCSS's own `active` uses, is
not read.

A theme with no rules gets `fh-busy-spin` and the plain ring in `core/css.pkl` —
the fallback that makes the core kit's promise true. `replace("")` declines it.

## Consequences

**The DOM is still BeerCSS-flavoured, deliberately.** `<article class="card">`,
`.slider.max`, `.chip`, `.switch`, `.tabs > a` stay in the templates, and the
rules keyed on them moved *into* the cards that emit them. Renaming them to
`fh-*` would not neutralise the DOM; it would move the entire MD3 look into
hand-written CSS and give up "BeerCSS styles semantic elements for us", which is
why the dashboard has a Material look at all. What a card owes a future theme is
its own class contract (`.slider-head`, `.entity-info`, `.popup-close`) and
colour through `--fh-*` — both of which it now has.

**A few BeerCSS-private hooks stay in the theme**, because they name things no
card emits: `--_padding` (its card-scale knob), `.shape`'s paint, the `.mdi`
font-family restatement. The rule that decides is "does this card emit the
selector", not "is this declaration structural".

**Every registered card's CSS is emitted, used or not.** A dashboard's registry
is one library's worth, a handful of KB, and pruning it would have to account for
surfaces and dynamic cases; the renderer has the information when that becomes
worth doing. The same block is also hand-minified in the Pkl sources — a runtime
minifier would let the sources be written for humans instead, and is the more
valuable of the two follow-ups.

**A card's JavaScript rides with it too: `cardDef.script`.** The same argument
as its CSS — the slider's touch rules leave the range input inert and its
gesture script drives it, so the two halves live in one card. Every registered
card's script is inlined once per page, as a classic script ahead of the theme's
`inlineScripts` (`Dashboard.cardScripts`), and a theme cannot switch one off: a
card without its script is broken, not restyled. That a card module can run any
JS on the page is accepted — a dashboard runs only the modules its author chose
to import.

**Every `Theme` property has a working default**, so a theme states only what it
changes and `new Theme {}` is a complete one: the shared HA tokens, no framework,
and a frame holding exactly the `#dashboard` and `#popups` hosts. That is what
makes "the core kit renders under any theme" checkable rather than aspirational
(`PklBuildSuite`, "a bare `new Theme {}`").

**`Dashboard.css` and `CardDef.css` are wire fields**, both defaulting to `""`,
so a dashboard JSON written before this decodes unchanged and renders unstyled
rather than wrongly. Both feed `Renderer.styleFingerprint`, or a CSS-only change
would leave a reconnect holding a stale stylesheet that still hashed equal.
`CardDef.script` is optional (null in Pkl, absent on the wire) and feeds
`Renderer.headFingerprint` instead: a script cannot be un-run, so a change to
one reloads the page rather than patching it.
