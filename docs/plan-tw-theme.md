# Plan: Tailwind-themed HA dashboard (`themes-tw.libsonnet`)

**Status: SUPERSEDED** (2026-07-08) by `plan-beercss-theme.md` — the BeerCSS
MD3 theme (`lib/theme-beer.pkl`) is the default and delivers this plan's goal
without the Tailwind toolchain. This plan's lasting ideas were absorbed there:
the semantic class contract (as the theme/component class contract) and the
static `/assets` route (as the offline asset-cache next step). Kept only as a
reference; also note it predates the jsonnet removal (a new theme would be a
Pkl module now, not a `.libsonnet`).

## Goal

Create a swappable theme file `themes-tw.libsonnet` that replaces Pico CSS with Tailwind CSS using the `@apply` directive, giving the dashboard a Home Assistant Lovelace look while maintaining a standard `.ha-*` CSS class API that others can override.

## Architecture

- **Standard CSS API**: templates emit semantic class names (`ha-card`, `ha-entity-card`, `ha-state-badge`, `ha-button`, `ha-slider-card`, `ha-section-title`, `ha-tab`, `ha-popup`)
- **Tailwind `@apply`**: source CSS written with `@apply` directives, compiled via Tailwind CLI to static CSS
- **Dynamic theming**: structural styles via `@apply` (rounded, padding, shadow), colors via `var(--...)` CSS custom properties from the design tokens
- **Build step**: Tailwind standalone CLI binary (no Node.js needed) wired as an sbt resource generator

## CSS source (to compile with Tailwind CLI)

File: `modules/fh-datastar-view/src/main/resources/dashboards/ha-theme.src.css`

```css
@tailwind base;
@tailwind components;

@layer components {
  .ha-card {
    @apply rounded-xl shadow-md p-4;
    background-color: var(--card-background-color, #fff);
    color: var(--primary-text-color, #212121);
  }
  .ha-card.tappable { cursor: pointer; }
  .ha-card.tappable:hover {
    @apply shadow-lg;
    transform: translateY(-1px);
  }

  .ha-entity-card {
    @apply rounded-xl shadow-md p-4 relative overflow-hidden transition-shadow;
    background: var(--card-background-color, #fff);
    color: var(--primary-text-color, #212121);
    transition: box-shadow .15s, transform .15s;
  }
  .ha-entity-card.tappable { cursor: pointer; }
  .ha-entity-card.tappable:hover {
    @apply shadow-lg;
    transform: translateY(-1px);
  }
  /* Left accent bar */
  .ha-entity-card::before {
    content: ''; position: absolute; inset: 0;
    border-left: 3px solid var(--primary-color, #03a9f4);
    border-radius: .75rem 0 0 .75rem;
    pointer-events: none;
  }
  /* Domain colors */
  .ha-entity-card[data-domain="light"]::before { border-left-color: #f9a825; }
  .ha-entity-card[data-domain="switch"]::before { border-left-color: #43a047; }
  .ha-entity-card[data-domain="sensor"]::before { border-left-color: #039be5; }
  .ha-entity-card[data-domain="binary_sensor"]::before { border-left-color: #7b1fa2; }
  .ha-entity-card[data-domain="lock"]::before { border-left-color: #e53935; }
  .ha-entity-card[data-domain="cover"]::before { border-left-color: #795548; }
  .ha-entity-card[data-domain="climate"]::before { border-left-color: #ff7043; }
  .ha-entity-card[data-domain="fan"]::before { border-left-color: #26c6da; }

  .ha-entity-card-header { @apply flex items-center gap-3 mb-1; }
  .ha-entity-icon {
    @apply w-8 h-8 rounded-full flex items-center justify-center shrink-0;
    background: var(--primary-color, #03a9f4); opacity: .15;
    font-size: 1rem; color: var(--primary-color, #03a9f4);
  }
  .ha-entity-name { @apply flex-1 text-sm font-medium truncate; color: var(--primary-text-color, #212121); }
  .ha-state-badge {
    @apply text-xs font-semibold px-2 py-0.5 rounded-full shrink-0 whitespace-nowrap;
    background: var(--secondary-text-color, #727272);
    color: var(--text-primary-color, #fff);
  }
  /* State colors */
  .ha-state-badge[data-state="on"] { background: #43a047; }
  .ha-state-badge[data-state="off"] { background: #9e9e9e; }
  .ha-state-badge[data-state="home"] { background: #43a047; }
  .ha-state-badge[data-state="not_home"] { background: #9e9e9e; }
  .ha-state-badge[data-state="open"] { background: #f9a825; }
  .ha-state-badge[data-state="closed"] { background: #78909c; }
  .ha-state-badge[data-state="locked"] { background: #43a047; }
  .ha-state-badge[data-state="unlocked"] { background: #e53935; }
  .ha-state-badge[data-state="unavailable"] { background: #bdbdbd; opacity: .6; }

  .ha-secondary { @apply text-xs mt-0.5 pl-11; color: var(--secondary-text-color, #727272); }

  .ha-button {
    @apply inline-flex items-center justify-center gap-2 px-4 py-2 rounded-xl
           text-sm font-medium cursor-pointer whitespace-nowrap transition-all;
    background: var(--card-background-color, #fff);
    color: var(--primary-text-color, #212121);
    border: 1px solid var(--divider-color, rgba(0,0,0,.12));
    box-shadow: 0 1px 2px 0 rgb(0 0 0 / .05);
  }
  .ha-button:hover { border-color: var(--primary-color, #03a9f4); color: var(--primary-color, #03a9f4); }
  .ha-button:active {
    transform: translateY(0);
    box-shadow: 0 0 0 2px var(--primary-color, #03a9f4);
  }
  .ha-button.primary {
    background: var(--primary-color, #03a9f4);
    color: var(--text-primary-color, #fff);
    border-color: transparent;
  }
  .ha-button.primary:hover { filter: brightness(1.1); color: var(--text-primary-color, #fff); }
  .ha-button.tab-active {
    background: var(--primary-color, #03a9f4);
    color: var(--text-primary-color, #fff);
    border-color: transparent;
  }

  .ha-slider-card { @apply rounded-xl shadow-md p-4 min-w-56; background: var(--card-background-color, #fff); color: var(--primary-text-color, #212121); }
  .ha-slider-header { @apply flex justify-between items-center gap-2 mb-3; }
  .ha-slider-label { @apply text-sm font-medium; color: var(--primary-text-color, #212121); }
  .ha-slider-value { @apply text-sm font-semibold; color: var(--primary-color, #03a9f4); }
  .ha-slider-input {
    @apply appearance-none w-full h-2 rounded-full cursor-pointer outline-none;
    background: var(--secondary-background-color, #e5e5e5);
  }
  .ha-slider-input::-webkit-slider-thumb {
    @apply appearance-none w-5 h-5 rounded-full shadow-md cursor-pointer transition-transform;
    background: var(--primary-color, #03a9f4);
  }
  .ha-slider-input::-webkit-slider-thumb:hover { transform: scale(1.15); }
  .ha-slider-input::-moz-range-thumb {
    @apply w-5 h-5 rounded-full shadow-md cursor-pointer border-none;
    background: var(--primary-color, #03a9f4);
  }
  .ha-slider-input::-moz-range-track { @apply h-2 rounded-full; background: var(--secondary-background-color, #e5e5e5); }

  .ha-tabs { @apply flex flex-col gap-4; }
  .ha-tabbar { @apply flex gap-1 flex-wrap p-1 rounded-xl; background: var(--secondary-background-color, #e5e5e5); }
  .ha-tab {
    @apply inline-flex items-center justify-center px-3 py-1.5 rounded-lg
           text-sm font-medium border-none cursor-pointer whitespace-nowrap transition-all;
    background: transparent;
    color: var(--secondary-text-color, #727272);
  }
  .ha-tab:hover { color: var(--primary-text-color, #212121); }
  .ha-tab.active {
    @apply shadow-sm;
    background: var(--card-background-color, #fff);
    color: var(--primary-text-color, #212121);
  }
  .ha-tab-panel { display: contents; }

  .ha-popup {
    @apply border-none rounded-2xl shadow-2xl;
    background: var(--card-background-color, #fff);
    max-width: 90vw; max-height: 90vh;
  }
  .ha-popup::backdrop { background: rgb(0 0 0 / .4); }
  .ha-popup-close {
    @apply absolute top-3 right-3 w-8 h-8 rounded-full border-none
           cursor-pointer flex items-center justify-center text-base transition-all;
    background: var(--secondary-background-color, #e5e5e5);
    color: var(--secondary-text-color, #727272);
  }
  .ha-popup-close:hover { background: var(--error-color, #db4437); color: #fff; }

  .ha-section-title {
    @apply text-base font-semibold tracking-wide uppercase;
    color: var(--primary-color, #03a9f4);
  }

  .ha-row { @apply flex gap-4 flex-wrap; }
  .ha-col { @apply flex flex-col gap-4; }
  .ha-row > .ha-col { @apply flex-1 min-w-0; align-self: flex-start; }
  .ha-cell { display: contents; }

  body {
    background: var(--primary-background-color, #fafafa);
    color: var(--primary-text-color, #212121);
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
      "Helvetica Neue", Arial, sans-serif;
  }

  @keyframes ha-popup-in {
    from { opacity: 0; transform: scale(.95) translateY(-.5rem); }
    to { opacity: 1; transform: scale(1) translateY(0); }
  }
  @keyframes ha-fade-in {
    from { opacity: 0; } to { opacity: 1; }
  }
}

@tailwind utilities;
```

## Files to create

### 1. `themes-tw.libsonnet` at `src/main/resources/dashboards/`

```jsonnet
// Swappable theme: Tailwind-based implementation of the standard .ha-* component classes.
local tokens = import 'tokens.libsonnet';

{
  tokens: tokens.light,
  tokensDark: tokens.dark,

  stylesheets: [
    'https://cdn.jsdelivr.net/npm/@mdi/font@7.4.47/css/materialdesignicons.min.css',
    // Compiled Tailwind output — served from static route
    '/assets/ha-theme.css',
  ],

  styles: '',
}
```

### 2. `ha-theme.src.css` at `src/main/resources/dashboards/`
The CSS source with `@apply` directives (full content above).

### 3. `tailwind.config.js` at project root

```js
/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './modules/fh-datastar-view/src/main/resources/dashboards/**/*.{jsonnet,html}',
  ],
  theme: { extend: {} },
  plugins: [],
}
```

## Files to modify

### 4. `build.sbt` — add Tailwind CLI resource generator

New resource generator on `fh-datastar-view` that runs:
```
tools/tailwindcss -i ha-theme.src.css -o ../static/ha-theme.css --minify
```

Produces the compiled CSS as a managed resource in `src/main/resources/static/ha-theme.css`.

### 5. `Server.scala` — add static file serving route

Add a route to serve `/assets/*` from classpath resources (so `/assets/ha-theme.css` resolves).

### 6. `components.libsonnet` — rewrite all 7 templates

- `fhrow` → `class="ha-row{{#class}} {{class}}{{/class}}"`
- `fhcol` → `class="ha-col{{#class}} {{class}}{{/class}}"`
- `sectionTitle` → `class="ha-section-title"`
- `entityCard`:
  ```html
  <article class="ha-entity-card{{#tappable}} tappable{{/tappable}}"
    data-domain="{{domain}}" data-state="{{state-class}}"
    {{#tappable}}data-on:click="{{{onclick}}}"{{/tappable}}>
    <div class="ha-entity-card-header">
      <span class="ha-entity-name">{{label}}</span>
      <span class="ha-state-badge">{{value}}</span>
    </div>
    {{#secondary}}<div class="ha-secondary">{{secondary}}</div>{{/secondary}}
  </article>
  ```
  - Adds `domain` slot (identity-derived, reactive: false) — extracted from entity_id prefix
  - Adds `state-class` slot (reactive: true, injects raw state for CSS selectors)
- `button` → `class="ha-button{{#active}} tab-active{{/active}}"`
- `slider` → `ha-slider-card`, `ha-slider-header`, `ha-slider-label`, `ha-slider-value`, `ha-slider-input`
- `tabs` → `ha-tabs`, `ha-tabbar`, `ha-tab`, `ha-tab-panel`
- `popup` → `ha-popup`, `ha-popup-close`

### 7. `Renderer.scala` — update wrapper class

- `fh-cell` → `ha-cell` in `render()` method (line ~310)

### 8. `dashboard.jsonnet` + other dashboard files

- Switch `theme:` import from `theme.libsonnet` to `themes-tw.libsonnet`

## Approach for dynamic theming (light/dark)

Structural styles (`rounded`, `padding`, `flex`, `shadow`) go through `@apply` and compile to fixed values. Color values go through `var(--token-name)` so they flip with `prefers-color-scheme: dark`.

The `@apply` and `var()` are mixed in the same rule — `@apply` handles the non-color utilities, then we override the color properties with `var()` after. This works because `@apply` expands first, then the explicit properties override.

## Build step details

Using Tailwind's standalone CLI (no Node.js required):

```bash
# Download once:
curl -sL https://github.com/tailwindlabs/tailwindcss/releases/download/v3.4.17/tailwindcss-linux-x64 \
  -o tools/tailwindcss && chmod +x tools/tailwindcss

# Compile:
tools/tailwindcss \
  -i modules/fh-datastar-view/src/main/resources/dashboards/ha-theme.src.css \
  -o modules/fh-datastar-view/src/main/resources/static/ha-theme.css \
  --minify
```

Wired in `build.sbt` as a resource generator on `fh-datastar-view`:
```scala
Compile / resourceGenerators += Def.task {
  val src = (Compile / resourceDirectory).value / "dashboards" / "ha-theme.src.css"
  val out = (Compile / resourceDirectory).value / "static" / "ha-theme.css"
  val tw = file("tools/tailwindcss")
  if (tw.exists()) {
    scala.sys.process.Process(Seq(
      tw.getAbsolutePath, "-i", src.getAbsolutePath,
      "-o", out.getAbsolutePath, "--minify"
    )).!!
  }
  out :: Nil
}.taskValue
```

This makes the compiled CSS a managed resource, automatically re-compiled on `sbt compile` (and by extension every `sbt dashboardBuild`).

## Execution order

1. Create `ha-theme.src.css` with the full `@apply` source
2. Create `themes-tw.libsonnet`
3. Create `tailwind.config.js`
4. Download Tailwind standalone CLI to `tools/`
5. Wire the sbt resource generator in `build.sbt`
6. Add static route in `Server.scala` for `/assets/*`
7. Rewrite templates in `components.libsonnet`
8. Update `fh-cell` → `ha-cell` in `Renderer.scala`
9. Switch dashboard imports to `themes-tw.libsonnet`
10. Run the build, verify, test
