---
name: beercss
description: BeerCSS (Material Design 3 CSS framework) reference for the fh-datastar-view theme — consumption model, verified facts, JS caveats, and how it must coexist with Datastar SSE morphing. Use when writing or reviewing the BeerCSS theme, its token bridge, or BeerCSS classes in card templates.
---

# BeerCSS (Material Design 3)

Pinned version: **4.x** (4.0.23 as of 2026-06-29; re-verify before bumping).
CDN: `https://cdn.jsdelivr.net/npm/beercss@<version>/dist/cdn/beer.min.css`.
MIT. ~14 KB brotli. Implements **MD3 / M3 Expressive**.

## Docs — fetch per component, don't guess

The docs are per-component markdown in the repo (readable via raw URLs):

- Index: `https://raw.githubusercontent.com/beercss/beercss/main/docs/INDEX.md`
- Per element: `.../docs/<ELEMENT>.md` — e.g. `CARD.md`, `DIALOG.md`, `TABS.md`,
  `SLIDER.md`, `BUTTON.md`, `HELPERS.md`, `SETTINGS.md`, `JAVASCRIPT.md`.
- The beercss.com site is JS-rendered — WebFetch gets nothing useful from it;
  use the raw GitHub markdown.

As with Pkl: **verify empirically before relying on a behavior** — a scratch
`.html` file with the pinned CDN link and the exact markup our Mustache
templates emit, checked in a browser (or `curl` the `beer.min.css` and grep the
selector). Blog posts are mostly v3; class names changed across majors.

## Consumption model (three ingredients)

1. **Settings** — global: `<body class="light|dark">` (no class = follows
   device `prefers-color-scheme`), CSS custom properties (`--primary`,
   `--on-primary`, `--primary-container`, `--surface`, `--surface-container*`,
   `--on-surface`, `--on-surface-variant`, `--outline`, `--outline-variant`,
   `--error`, `--background`, `--size`, `--font`, `--font-icon`, `--speed1..4`).
   Theming without JS = just define these variables in CSS.
2. **Elements** — BeerCSS styles **semantic HTML directly**: `<article>` is a
   card, `<button>` a filled MD3 button, `<dialog>` a dialog, `<nav>` a bar,
   `<i>icon_name</i>` a Material Symbols icon. This is why it fits our
   templates: they already emit `article`/`button`/`dialog`. GOTCHA: `<header>`
   is styled as a 4rem app-bar grid (`display:grid;min-block-size:4rem`) —
   inside a card template it can swallow inline label text (bit the slider
   card); use a `.row` div (`.max` = flex-grow spacer) for label lines
   instead. Tab bars are `.tabs > a` anchors (the `TabButton` card).
3. **Helpers** — modifier classes: `round`/`no-round`/`border`/`fill`,
   `padding`/`margin`/`space` (+ `tiny-|small-|medium-|large-` prefixes),
   `elevate`, `left-|center-|right-align`, `primary|secondary|tertiary|error`
   (+ `-container`/`-text`/`-border` variants), `responsive`, `s`/`m`/`l`
   breakpoint prefixes, `active`.

## JavaScript: we do NOT ship beer.min.js

`beer.min.js` is optional — it provides `ui(...)` (mode/theme switching,
element triggering via `data-ui`) and `material-dynamic-colors` (runtime MD3
palette from a hex/image). Verified exceptions where it IS required upstream:

- **slider**: the track-fill of `<div class="slider"><input type=range><span></span></div>`
  is JS-updated; without JS the fill doesn't track the value.
- **textarea**: auto-resize (non-Chromium).

Project stance: CSS-only. Backend/Datastar already own the behaviors the JS
provides — dialogs are transient `<dialog open>` fragments patched into
`#popups` (works CSS-only; `.active` class is the alternative), tab switching
is our surface swap + `data-class` active toggling, theming is our token
system. If beer.min.js is ever added (e.g. for the slider), first verify its
bindings survive Datastar morphs (delegated document-level listeners survive;
per-element listeners bound at init do not).

## Project conventions (fh-datastar-view)

- `lib/theme-beer.pkl` is the DEFAULT theme (wired in `entry.pkl`; contract
  class in `theme.pkl`, Pico alternative in `theme-pico.pkl` — see
  `docs/plan-beercss-theme.md`); contract classes (`.card`, `.popup`,
  `.tabbar`, `.fh-row`…) stay on the markup — BeerCSS element styling applies
  *underneath* them.
- The color variable names are BeerCSS's MD3 roles — **SETTINGS.md (link
  above) is the authoritative list**. The palettes live ON `BeerTheme`
  (`theme-beer.pkl`) as amendable `hidden md3Light`/`md3Dark` props (styles
  recompute late-bound: `(beer.theme) { md3Light { ["primary"] = … } }`);
  HA-named tokens stay in the shared `tokens.pkl` and win last via the
  re-pointing layer. Nothing uses `material-dynamic-colors` at runtime.
- Pin the CDN version in the stylesheet URL; never `@latest` (wire snapshots +
  visual stability).
