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
- **Each card's structure** — `.fh-slider-head`'s flex line, `.fh-entity-info-attrs`'
  scroll cap, `.fh-popup-close`'s corner. Working on the slider meant editing the
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

So cards write only `fh-` classes, and a theme gives each `fh-` class it has an
opinion on its **content**: the classes the markup carries in its place. One
primitive covers both cases: a content that keeps the class adds to it
(`"fh-disabled disabled"`), and one that leaves it out replaces it.

```
core/tap.pkl     emits     data-class:fh-busy-spin="$_{{id}}__busy_slow"
theme-beer.pkl   declares  classes { ["fh-busy-spin"] = "shape loading-indicator" }
the server       renders   data-class:shape="…" data-class:loading-indicator="…"
```

Two modes, `add` and `replace`, were rejected: one more concept to explain, and
no case the content cannot spell.

**The server applies the rules, not the registry.** A card's markup is a
class-level `cardDef` default, so Pkl can only reach it once the registry is
built — but the classes that most need a theme's word are not all in templates.
The `.fh-cell` wrapper, its `.columns(n)` spans and its `cellClassWhen` live classes
are emitted by `Renderer`, so a Pkl-side rewrite would cover half the markup and
leave a second mechanism for the rest. `Theme.classes` therefore rides the wire,
and `ThemeClasses` rewrites every card template once, when it is compiled
(`class="…"` tokens and `data-class:fh-…` bindings), and expands each class the
renderer emits. A class can also arrive as a slot's VALUE (a `Box`'s author
class, a button's size, a glyph): a slot placed in a `class` attribute is typed
`cssClass` (`slotMod.cssClass(name)`), and the renderer expands its value wherever
it resolves — a literal once per renderer, a reading each time, a signal's value
included. The value is a whole class name (`fh-size-small`, not a `small` the
template completes), because the theme maps whole names. A `cssClass` value gets
no token check: `{{x}}` escapes it, where the cell's classes, written unescaped,
need one (ADR 0008). The markup Scala writes itself goes through the same rewrite: the
page's connection banners and a failed fetch's label. `dashboard.json` shows the
rules beside unrewritten templates and values, which is the "what is going on" a
reader needs.

**Leaving the class out is for a look the framework has its own of.** BeerCSS's
mask beside `fh-busy-spin` would leave the base ring drawing under it, and a
button's `fh-size-small`/`-medium`/`-large` (its `size` slot's value) become BeerCSS's `small`/(none)/`large`,
whose height the theme reads through `--_size`, so the button card's fallback
heights stop applying. Leaving a class out of
its content takes the base CSS's rules with it, so `fh-cell` and `fh-group` must
stay in their own — the shell script and the layout select on them — and
`Dashboard.validate` refuses a content without them. A rule names an `fh-` class:
those are the card's contract, and the object form `data-class="{…}"`, which only
BeerCSS's own `active` uses, is not read.

A theme with no rules gets `fh-busy-spin` and the plain ring in `core/css.pkl` —
the fallback that makes the core kit's promise true. An empty content declines it.

## Consequences

**Each layer is written readable and served minified** (`build/Minifier`, #153): every CSS piece
through csso with `restructure` off, since merging rules across the sheet would reorder the
cascade the three layers depend on, and every script through terser. So a comment in a card's
`css` costs the page nothing.

**The markup is BeerCSS-shaped; its classes are all ours.** The templates keep
the structure BeerCSS styles — `<article>`, `<dialog>`, a `<label>` round a
checkbox and a `<span>`, a range wrapper over a class-less `<span>` track,
anchors straight under a tab bar — because that is what gives the dashboard a
Material look without restating it in CSS. But every class a template writes, and
every class a card's CSS selects, is an `fh-` one (an icon's `mdi` classes
aside), and `PklLibraryTestSuite` checks every card for it. Theme-beer puts
BeerCSS's classes beside them through `classes` (`fh-slider` -> `slider max`,
`fh-tabs` -> `tabs`, `fh-chosen` -> `active`), so another framework is a theme,
not a rewrite of the cards. BeerCSS 4 has no `.card` rule at all (it styles
`<article>`), so `fh-card` needs none.

Two things follow that a card author has to know:

- **A card rule that overrides a framework rule must match its specificity on
  the `fh-` side**, because the framework's selector may count two classes where
  the card's counts one. BeerCSS styles the slider track as `.slider.max`
  (`all:unset`, its own cursor), so the card writes `.fh-slider-body>.fh-slider`:
  `.fh-slider` alone loses, and the touch rules go inert with nothing failing.
- **A card's CSS cannot tell a theme's state by the theme's class.** The spinner
  is `shape loading-indicator` under BeerCSS and `fh-busy-spin` under no theme,
  so `core/tap.pkl` also sets `aria-busy="true"` on a spinning glyph, which no
  class rule reaches; a card that paints a badge leaves a busy one alone with
  `:not([aria-busy=true])`.

**A few BeerCSS-private hooks stay in the theme**, because they are BeerCSS's
own names: `--_size` behind `--fh-press-size`, `--_end` pointed at the slider span's `--fh-fill`, `.shape`'s paint, the `.mdi`
font-family restatement. The icon font itself is every theme's: `Theme`'s default
`deferredStylesheets` loads MDI, since the cards emit its classes.

**A page carries the CSS of the cards its dashboard uses, and no others.** The
registry is read off the nodes (`nodes.cardsIn` over the tree and every surface's
content, `entry.pkl`), so a card no dashboard places costs that dashboard nothing.

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
