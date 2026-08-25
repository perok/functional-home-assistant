# ADR 0026 — The look is BeerCSS: an MD3 framework that styles semantic HTML

- **Status:** Accepted
- **Date:** 2026-07-08 (recorded as an ADR 2026-08-25, superseding
  `docs/plan-beercss-theme.md` and `docs/plan-tw-theme.md`, both deleted)
- **Scope:** `lib/theme.pkl`, `lib/theme-beer.pkl`, `runtime/AssetCache.scala`
- **Owns:** the choice of CSS framework and what that choice constrains.
  ADR 0020 owns how CSS is *layered* (base / component / theme); ADR 0008 owns
  the `fh-` layout contract. Neither depends on this one being BeerCSS.

## Context

The dashboard needed a Home Assistant-like look — neutral background, white
cards, 12px radius, HA blue — on a frontend that is **backend-rendered, has no
build step, and is not an SPA**: Mustache templates emit final HTML strings and
Datastar morphs them into place. The placeholder Pico theme did not get there.

The field of maintained Material Design 3 implementations that survive those
constraints is small:

| Option | Verdict |
|---|---|
| **BeerCSS 4** | **Chosen.** The only maintained MD3 implementation that is plain CSS over *semantic elements* — it styles `<article>` as a card, `<button>`, `<dialog>`, `<i>` natively, which is what the templates already emit. ~14 KB brotli, MIT, active, no build step, JS mostly optional. |
| Material Web (`@material/web`) | Web Components, and **in maintenance mode** (Google moved the team to its internal Wiz framework). Dead end. |
| MDUI 2/3 | Web Components + shadow DOM, ~85 KB gz. Client-side rendering fights the model: the backend owns the HTML. Rejected. |
| MDC-web / Materialize | MD2, deprecated or stale. Rejected. |
| Tailwind `@apply` | Full control, but hand-maintains every component's CSS *and* adds a toolchain. Rejected; its lasting ideas — a semantic class contract, a static assets route — landed here and in ADR 0020 without it. |

The clincher held in practice: **the first version shipped with no template
changes at all.** BeerCSS's element styling applies underneath the contract
classes the cards already carried.

## The decision

`lib/theme-beer.pkl` is the default and only shipped theme, wired by
`entry.pkl`, loading a **pinned** BeerCSS from CDN — never `@latest`, because
the version is part of the visual contract and rides the wire snapshots.
`theme.pkl` stays the contract (`open class Theme`); the interim Pico port was
deleted rather than kept in lockstep (ADR 0008).

Three properties of the framework are load-bearing for everything downstream,
and each is a trap if forgotten.

### 1. BeerCSS has no `prefers-color-scheme`; its JS sets a body class

`beer.min.css` puts light values on `:root, body.light` and dark values **only**
on `body.dark`; "follow the device" is `beer.min.js` adding the class at
startup. A `body.dark` rule therefore beats anything defined on `:root`, which
is why this theme's palette is emitted at **body specificity with all three
class states in every selector**. The mechanics are the canonical module doc on
`theme-beer.pkl` — read that before touching the palette.

The consequence to know here: **colour mode is device-driven only.** A manual
`ui("mode", …)` flip would move the body class but not our media query, so it
is unsupported.

### 2. Whatever `beer.min.js` paints, the backend must also paint

The framework's JS owns some visuals through inline styles — the slider's track
fill is `--_start`/`--_end` private properties it writes on `input` events. A
Datastar morph wipes an inline style it did not author, and with the properties
defaulting to 0% the track snapped to *fully filled*. So the value is baked
server-side and the JS repaint is treated as the transient one, not the source
of truth (`components/slider.pkl` carries the split, including why the fill
colour rides an inner `<span>`: beer.min.js assigns the wrapper's whole
`style.cssText`).

The general rule: a framework behaviour that survives a morph may be used;
one that *re-establishes state after* a morph may not be relied on for
anything the server can render itself. beer.min.js binds via delegated
listeners and a MutationObserver, so its bindings do survive.

### 3. The icon set is MDI, not the Material Symbols BeerCSS bundles

Home Assistant's own entity `icon` attribute is an MDI name, so MDI is the only
set that can render the icon an author actually chose. The theme loads
`@mdi/font` and restates the `font-family` on the card selector, because
BeerCSS styles a bare `<i>` as Material Symbols and would otherwise win the
cascade. Its cost (~394 KB of woff2 for a few dozen used glyphs) and the
build-time SVG-inlining path that should replace it are on the `mdiCdn` doc
comment in `theme-beer.pkl`.

## Consequences

- **The DOM is BeerCSS-flavoured on purpose**, and the rules keyed on framework
  class names live in the cards that emit them — ADR 0020 has that argument and
  the `--fh-*` colour seam that keeps a card from naming a framework role.
- **Offline works from a warm cache.** `AssetCache` fetches every theme
  stylesheet and script (and the relative `url(...)` refs inside a cached
  stylesheet) once at startup, serves them from `/assets/:name`, and rewrites
  the page URLs; any fetch failure falls back to the original URL. Local
  controls keep working when the internet does not, which is the case that
  matters.
- **Framework class names can leak into our contract classes.** `.tabs` is a
  BeerCSS element class and the Tabs container emits `fh-col tabs`; the theme
  neutralises the overlap explicitly. Expect to check for this when adding a
  contract class whose name is a plausible framework one.
- **Tabs run on BeerCSS's native `.tabs > a` markup** (ADR 0006's `TabButton`),
  which means its indicator rules own the geometry: a `.tabs>a{inline-size:auto}`
  override to make labels hug their text was tried and reverted — it detaches
  the active underline, because the anchor stops being its containing block.
- **Nothing calls `ui(...)` or `material-dynamic-colors` at runtime.** Behaviour
  is Datastar's and the backend's; BeerCSS is paint plus the two JS jobs above.

Per-component markup and class names are upstream documentation — the `beercss`
skill points at the context7 source and carries the project-specific caveats
this ADR does not repeat.
