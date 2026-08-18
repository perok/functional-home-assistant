# ADR 0020 — Components carry their own CSS; the theme is the paint

- **Status:** Accepted
- **Date:** 2026-08-17
- **Scope:** `lib/core/css.pkl` (new), `lib/core/node.pkl`, `lib/core/tap.pkl`,
  `lib/theme.pkl` + `lib/theme-beer.pkl`, every `lib/components/*.pkl`,
  `lib/entry.pkl`, `model/Dashboard.scala`, `runtime/Renderer.scala`
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

So the names come from the theme, and the mechanism is a hole in the template:

```
core/tap.pkl    emits  @@CLASSBIND:busySpin:$_{{id}}__busy_slow@@
entry.pkl       calls  cards = c.cardsWith(componentModules.toList(), theme.classes)
core/node.pkl   fills  data-class:shape="…" data-class:loading-indicator="…"
```

**It has to happen in the registry, and that is the interesting part.** A card's
markup is a class-level `cardDef` default harvested by `pkl:reflect`, so it can
only reference `const` members of its own module's imports — never the theme,
which the *entry* picks much later, and Pkl has neither parameterised modules nor
a way for an importer to rebind a `const`. But the registry is assembled by an
ordinary function call, in the entry, where the theme is in scope. So the theme
reaches the templates one step after they are written, by rewriting the holes —
the same splice-a-token-and-fill-it-later shape as `NODE_ID`.

`Theme.classes` is `hidden`: it is consumed during evaluation and never reaches
the wire, so the runtime does not learn that a theme had an opinion.

A theme that names nothing gets `fh-busy-spin` and the plain ring in
`core/css.pkl` — the fallback that makes the core kit's promise true.

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

**The slider's gesture script still lives in the theme.** `sliderHoldScript` is
the JS half of rules that are now the slider card's, so it belongs with the card
under the same argument as its CSS. Scripts have no `cardDef` hole yet; adding
one is open work, and it is the same shape as `css`.

**`Dashboard.css` and `CardDef.css` are wire fields**, both defaulting to `""`,
so a dashboard JSON written before this decodes unchanged and renders unstyled
rather than wrongly. Both feed `Renderer.styleFingerprint`, or a CSS-only change
would leave a reconnect holding a stale stylesheet that still hashed equal.
