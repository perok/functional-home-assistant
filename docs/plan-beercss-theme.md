# Plan: BeerCSS Material Design 3 theme (`lib/theme-beer.pkl`)

**Status: DEFAULT theme** (browser-signed-off + flipped 2026-07-08) —
`entry.pkl` wires every dashboard to `theme-beer.pkl` unless the entry
overrides; the pilot entry is retired. `theme.pkl` is now the contract module
(the `open class Theme` + the shared `layoutCss`); `theme-beer.pkl` is the
default AND only shipped implementation (the interim `theme-pico.pkl` port of
the original look was deleted with ADR 0007 rather than kept in lockstep).
Remaining work: offline asset caching + phase-2 polish (see "Next steps").
Supersedes `plan-tw-theme.md`.

## Goal

A swappable theme giving the dashboard a clean, Home Assistant-like Material
Design 3 look, replacing the placeholder Pico theme — HA's visual language
(neutral background, white cards, 12px radius, HA-blue `#03a9f4` primary) on
MD3 surfaces/typography, with light/dark following the device.

## Why BeerCSS (decision record)

The field of maintained MD3 options for a **backend-rendered, no-build,
no-SPA** frontend is small:

| Option | Verdict |
|---|---|
| **BeerCSS 4** | **Chosen.** The only maintained MD3 implementation that is plain CSS over *semantic HTML elements* — it styles `<article>` as a card, `<button>`, `<dialog>`, `<i>` natively, which is exactly what our Mustache templates already emit. ~14 KB brotli, MIT, active (4.0.23, 2026-06; M3 Expressive), no build step, JS mostly optional. |
| Material Web (`@material/web`) | Web Components; **in maintenance mode** (Google moved the team to its internal Wiz framework). Dead end. |
| MDUI 2/3 | Web Components + shadow DOM, ~85 KB gz. Client-side rendering fights our model: the backend renders final HTML strings and Datastar morphs them. Rejected. |
| MDC-web / Materialize | MD2, deprecated/stale. Rejected. |
| Tailwind `@apply` (`plan-tw-theme.md`) | Full control but hand-maintains every component's CSS *and* adds a toolchain. BeerCSS gets the same look with zero build steps and (phase 1) zero template changes. Fallback if BeerCSS proves too opinionated. |

The clincher held up in practice: **phase 1 shipped with no template changes.**
BeerCSS's element styling applies underneath the contract classes.

## Spike results (verified on 4.0.23, 2026-07-07)

- **`beer.min.css` has NO `prefers-color-scheme` media query.** Light values
  sit on `:root, body.light`; dark ONLY on `body.dark`. CSS-only default is
  light — "auto follows device" is `beer.min.js`'s doing.
- **`beer.min.js` auto-sets `light`/`dark` on `<body>` at startup** (from
  `matchMedia`) when neither class is present. So a `body.dark` rule can
  override anything we put on `:root` — this forced the body-specificity color
  bridge (see `theme-beer.pkl`'s module doc, which is the canonical
  explanation).
- The **Material Symbols fonts ship inside the CDN dist** with absolute-URL
  fallbacks in the `@font-face` — icons (`<i>home</i>`) need no extra link.
- **Dialogs work CSS-only**: hidden by default, shown via `dialog[open]` *or*
  `.active` — our transient `<dialog open class="popup">` patch works as-is.
- **The slider track fill is JS-driven** (`--_start`/`--_end` private props
  updated by `beer.min.js`); the `.slider` wrapper markup is inert without it.
- Element→variable map: `article` reads `--surface-container-low`, `dialog`
  `--surface-container-high`, `button` `--primary`/`--on-primary`; `article`'s
  radius is `.75rem` = HA's 12px already.
- **`.tabs` is a BeerCSS element class** (row flex, bottom border, nowrap) and
  our Tabs container emits `class="fh-col tabs"` — the leakage is neutralized
  in the theme (`.fh-col.tabs{white-space:normal;border-block-end:none}`).
- `beer.min.js` binds via delegated listeners + a MutationObserver, so
  Datastar SSE morphs *should* re-bind — **confirm during browser sign-off**.

## What shipped (phase 1)

- **`Theme.scripts`** — themes can now inject external JS: `scripts:
  Listing<String>` in `theme.pkl`'s Theme class → `scripts` on the wire
  (`Dashboard.scala` Theme) → `Renderer.scripts` → `<script type="module"
  src>` tags in `Server.page()`'s head, after the stylesheet links, before
  Datastar. This was a wire-format change; snapshots were regenerated
  deliberately.
- **`lib/theme-beer.pkl`**: `class BeerTheme extends Theme` (Theme is now
  `open`) — pinned BeerCSS 4.0.23 CDN css + js, `responsive` chrome, and a
  `styles` block = MD3 palette at body specificity → dark palette under our
  media query → HA-token re-pointing (HA blue as `--primary`, HA card color
  as the article/dialog surfaces) → residual contract CSS (fh-row/col, tabs
  neutralization + tabbar, `.popup-close` un-filling).
- **Palette ownership (decided 2026-07-08)**: the 36-role MD3 palettes
  (variable names per [BeerCSS SETTINGS.md](https://github.com/beercss/beercss/blob/main/docs/SETTINGS.md),
  generated once from seed `#03a9f4` with `@material/material-color-utilities`)
  live **on `BeerTheme`** as amendable `hidden md3Light`/`md3Dark` props —
  `styles` reads them late-bound, so
  `theme = (beer.theme) { md3Light { ["primary"] = "#..." } }` retunes the
  theme (spike-verified). They are BeerCSS-specific, so they do NOT sit in the
  shared `tokens.pkl`; the HA-named tokens stay there as the cross-theme /
  HA-drop-in contract (and are equally amendable per instance via
  `tokens { ... }` — `tokens` is an ordinary Theme property).
- **Slider (2026-07-08)**: the `slider` card template now emits BeerCSS's
  slider markup (`<div class="slider max"><input type=range…><span></span></div>`)
  — the full MD3 track/thumb. The wrapper is inert under non-BeerCSS themes.
  Fill updates are beer.min.js's job (browser-confirmed that value changes
  from other sources flow through).
- **`pkl-beer.pkl`**: pilot entry at `/d/pkl-beer` — pkl-demo's tree plus a
  tabs group, `theme = beer.theme`; every card kind under the new theme while
  all other dashboards keep Pico.
- **`PklBuildSuite`**: `pkl-beer` added to the wire-snapshot tests
  (`snapshots/pkl-beer.json`).
- **Snapshot-gate gotcha found en route**: a long-lived sbt server started
  from a shell that exported `FH_UPDATE_SNAPSHOTS=1` keeps regenerating
  forever — the gate silently off. Documented in the suite; regenerate via the
  scoped `sys.props` command instead (see the suite's comment).

## Verification

1. ~~Spikes S1–S3~~ — done (results above).
2. ~~`sbt 'fh-datastar-view/testFull'`~~ — 95/95 green with the gate active.
3. **Browser check (user)**: `sbt dashboardServe`, open `/d/pkl-beer` — cards,
   buttons, slider (fill via beer.min.js), tabs, both popups, dark mode, and
   whether beer.min.js behaviors survive SSE morphs.
4. Only then: default flip + deliberate snapshot regen.

## Next steps (in order)

1. ~~**Browser sign-off**~~ — done 2026-07-08 (cards, tabs, popups, slider,
   external value updates all confirmed working).
2. ~~**Default flip**~~ — done 2026-07-08: `entry.pkl` imports
   `theme-beer.pkl`; the old Pico theme moved to `theme-pico.pkl` and
   `theme.pkl` was reduced to the contract class; pilot entry + its snapshot
   deleted; snapshots regenerated (both now carry the beer theme);
   `plan-tw-theme.md` marked superseded. (`theme-pico.pkl` itself was later
   deleted with ADR 0007 — BeerCSS is the only shipped theme.)
3. ~~**Offline theme assets — backend download + cache.**~~ Done 2026-07-08:
   `AssetCache` (`fh/view/runtime/AssetCache.scala`) fetches every theme
   `stylesheets`/`scripts` URL once at startup, persists under URL-hashed
   names in `assets-cache/` (module dir, gitignored; `FH_ASSETS_DIR`
   overrides), serves `GET /assets/:name` (immutable cache headers, dir
   listing = whitelist), and `Server.page()` rewrites the page URLs through
   it. Relative `url(...)` refs inside a cached stylesheet (the Material
   Symbols woff2) are fetched + rewritten too; absolute/`data:` refs are left
   as CDN fallbacks. Any fetch failure just keeps the original URL — offline
   with a warm cache works, offline with a cold cache degrades to exactly the
   pre-cache behavior. URLs first appearing via live-reload pass through
   until the next restart. Covered by `AssetCacheSuite` (stub client).
4. **Phase 2 polish** — implemented 2026-07-08, **browser sign-off pending**:
   - Tabs on BeerCSS's NATIVE markup (user-decided after seeing the interim
     CSS-only underline version): the `Tabs` bar is `.tabs > a` — a new
     internal `TabButton` card class renders each tab as an anchor with the
     `data-class` active toggle + cookie onclick, and `Button` lost its tab
     arm. The MD3 look (underline indicator, hover/press states, even
     distribution) is framework CSS now; the beer theme keeps only
     `.tab-panel`. (The since-deleted theme-pico styled `.tabs > a` as the
     old bordered pills.)
   - Entity-domain icons: the `entityCard` header renders
     `{{#icon}}<i>{{icon}}</i>{{/icon}}`; the `icon` slot is an author
     literal or (default) a runtime `$lookup($domain)` identity slot over a
     `domainIcons` table in components.pkl — so dynamic-group ($self) cards
     get the matched entity's icon too, and unlisted domains render nothing.
     Material Symbols names; the font ships in the BeerCSS dist; a theme
     without an icon font should hide `.entity header i`.
   - Slider label line: the `<header>` was swallowed by BeerCSS (it styles
     `header` as a 4rem app-bar grid), and BeerCSS's `.row`/`.max` helpers
     wrapped the state text per-character (`.max` = inline-size:100%) —
     replaced with a `.slider-head` class contract both themes style
     (flex, space-between).
   - **Slider fill is backend-rendered** (the load-bearing fix): BeerCSS's
     track fill is an inline style (`--_start`/`--_end`) its JS writes on
     `input` events; a Datastar morph wipes it and, the vars defaulting to
     0%, the track snapped to fully-filled ("drags itself right"). The
     template now bakes `style="--_start: 0%;--_end: {{fill}}%"` from a new
     `fill` slot — `100 - value%` of the min..max range, honouring the same
     three config tiers as the other slider slots, null-guarded so an OFF
     light renders an empty fill rather than a JSONata error. Correct on
     every morph by construction; beer.min.js still repaints live during a
     drag. Evaluation-tested in `TransformSuite` (both tiers). The `.tooltip`
     value bubble was tried and REMOVED — its value text bled through behind
     the track, and the head line already shows the live value.
   - Tabs keep BeerCSS's default even distribution. A
     `.tabs>a{inline-size:auto}` label-hugging override was tried and
     REVERTED: it detached the active underline from the anchor (browser
     feedback; the anchor stops being the underline's containing block).
   - ~~BeerCSS `.slider` markup~~ — done earlier the same day (see "What
     shipped").

## Relationship to the Tailwind plan

`plan-tw-theme.md` targeted the same goal via hand-written Tailwind CSS. Its
lasting ideas — a semantic class contract, a static assets route — are
absorbed here (the assets route arrives with next-step 3). Mark it superseded
when the default flips.
