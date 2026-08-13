# Plan: installable PWA for the Datastar dashboard (fh-datastar-view)

**Status: Phase 1 implemented; Phase 2 is an investigation.** Phase 1 (PWA installability)
is designed down to the route level and built; Phase 2 (HTML caching) is an
investigation — direction and open questions are captured here, but it is deliberately
not specced to the same depth until the spike answers them.

Two-step shape (per conversation): **Phase 1** makes the dashboard installable —
manifest, icons, a minimal service worker served by http4s without caching — and
caches only the safe, immutable static trees. **Phase 2** looks into serving a
cached copy of the page HTML for instant paint, letting the existing SSE resume
machinery heal it to current. Neither phase changes the rendering pipeline server-side;
the resume/repaint/reload path in `Server.openingPatches` (ADR 0011) is exactly the
heal a stale document needs, and it already exists.

## Problem

The dashboard is not installable: no web app manifest, no service worker, no icons.
Installing it gives a standalone window and a home-screen entry — the reason to build
it. Once installed, the interesting question is whether a previously-loaded page can
be shown instantly (or offline) and then brought live over the SSE stream.

Relevant properties of this app:

- The page is **server-rendered HTML per request** (`/`, `/d/:slug`) with live
  entity state baked in; there is no static `index.html` to precache.
- The document **carries its own resume anchor**: `data-init="@get('sse/dashboard/
  $slug/patch…')"` includes the `conn` + four-part cursor (`headHash`, `styleHash`,
  `logId`, `version`) in its query. Caching the raw HTML preserves that anchor, which
  is what makes "stale document, healed by SSE" work.
- The only cacheable static assets (`/web/*` bundles, `/assets/*` theme cache) are
  **already served `immutable`** by http4s (Server.scala:104, AssetCache.scala:51).
- `/sse/*` (the patch stream + all action POSTs), `/system/*`, `/edit/*` must never
  be intercepted by a service worker.
- Chrome's installability criteria (as of the 2024 web.dev revision, still current)
  are: HTTPS + a manifest with `name`/`short_name`, `icons` (192 + 512), `start_url`,
  and `display: standalone`/`fullscreen`/`minimal-ui`. The menu "Install app" path no
  longer requires a service worker, but the **address-bar install prompt and Android
  WebAPK minting still want a SW with a `fetch` handler** — so a minimal SW is the
  practical route.

## Approach overview

- **Phase 1 — installability + safe static caching.** A small set of root-level PWA
  routes served by http4s: `manifest.webmanifest` (`application/manifest+json`),
  `sw.js` (`application/javascript`, `Cache-Control: no-cache`), and two icons. A
  minimal, self-contained `sw.js` registers and cache-firsts only `/web/*` and
  `/assets/*` (same-origin GETs), passing everything else through untouched. The
  manifest `<link>` goes in `Server.page`'s head; registration goes in the inlined
  `shell.ts`, base-relative so both work behind the HA ingress prefix exactly like
  every other app URL.
- **Phase 2 — HTML caching.** Serve a cached page document immediately (or when
  offline), then let SSE heal it via the existing resume/repaint/reload path. This is
  the "almost latest and greatest" snapshot: the cache is refreshed on every
  successful load (stale-while-revalidate), so it is as fresh as the last visit, and
  SSE completes it. The navigate-away refresh idea was considered and superseded —
  see below. **Investigation only in this plan.**

## Phase 1 — changes by file

### `src/js/sw.ts` + `vite.config.ts` (the service worker, built, not committed)

The SW is a **build output like the rest of the frontend** — one more vite entry —
because Phase 2 will need build knowledge in it (the precache list, which is what
open question 1 below hangs on), and the emission machinery already exists:

- `sw.ts` is a new `src/js/` entry, built with the `shell`/`app`/`overlay` trio
  (rollupOptions `input`), and **added to the `classic` set** in vite.config.ts — the
  `fh-assert-self-contained` plugin then fails the build if it ever picks up an
  import/export, exactly like the shell. Its own doc comment pins the whole shape.
- **`output.entryFileNames` is overridden per chunk**: the `sw` entry emits to the
  output ROOT as the fixed `sw.js`, everything else keeps `web/[name]-[hash].js`.
  Two reasons, both hard:
  - the browser fetches SW updates **at the registered URL**, so a content-hashed
    `sw-<hash>.js` would strand every install on its first version (a stale SW is
    served `immutable` by the HTTP cache and never re-fetched, so the hash that
    would "update" it never even loads);
  - it must NOT live under `web/`, which is served `immutable` (Server.scala:104) —
    the SW needs `no-cache` (below), and the root position gives it scope `/` for
    free, which Phase 2's navigation caching needs.
- The SW contents (import-free, ~35 lines): `install → skipWaiting`,
  `activate → clients.claim()`, and a fetch handler that cache-firsts same-origin
  GETs whose path contains a `web/` or `assets/` segment (the immutable trees) and
  returns without `respondWith` for everything else — notably `/sse/*`
  (intercepting the SSE GET would break the resume cursor + keepalive) and all
  POSTs. Immutable URLs make cache-first safe: a rebuilt bundle is a different
  URL, so a cache hit can never be stale. There is no pruning yet — the `activate`
  handler clears the old cache name when the SW version bumps (Phase 2 decides
  whether the runtime cache needs an LRU cap).
- The one non-vite detail: `sw.ts` needs a `/// <reference lib="webworker" />`
  (tsconfig's `lib` is `ES2022`/`DOM`/`DOM.Iterable` — the `ServiceWorkerGlobalScope`
  ambient types are not in the DOM lib).

### `src/main/resources/pwa/` (new, committed static resources)

- `manifest.webmanifest` — static JSON, location-independent because every URL inside
  it is relative and resolves against its own URL at install time:
  `name`/`short_name`/`description`, `start_url: "."`, `scope: "."`, `id: "."`,
  `display: "standalone"`, `theme_color`/`background_color` (step-1 hardcode; see
  open questions), and `icons` with `src: "icon-192.png"`/`"icon-512.png"` (relative →
  same directory as the manifest). A static file works for both direct serving and the
  ingress prefix because the manifest itself is served at the app root.
  Deliberately NOT emitted by vite: `build.manifest` only lists entry chunks, and the
  relative `start_url`/icon paths must resolve against the app origin — a hashed
  `web/` URL would be wrong for both.
- `icon-192.png`, `icon-512.png` — generated once from an MDI glyph (the `@mdi/font`
  the theme already vendors, glyph `view-dashboard-outline` U+F0A1D) with the
  0.56 safe-zone padding a maskable icon needs, on the theme's `#006493` primary.
  These are hand-authored static resources, not build output — same status as
  `editor/overlay.css`.

### `runtime/Server.scala`

- **Four explicit allowlisted routes** in the routes block (~:92): `GET /sw.js`,
  `/manifest.webmanifest`, `/icon-192.png`, `/icon-512.png`, all served by the small
  `PwaAssets` object (name → classpath resource + media type), at **root-level URLs**
  so the manifest's relative `start_url`/icon `src` resolve to the app root and the
  SW's scope is the whole prefix. Any other name → 404 — the allowlist is the
  whitelist (same un-forgeable pattern as `FrontendAssets.serves` / `AssetCache`).
  Content types: `application/manifest+json`, `application/javascript`, `image/png`.
- **Every PWA file is served `Cache-Control: no-cache`** — the one header this plan
  insists on, and the reason these files can't ride `/web/`. The browser revalidates
  the SW on every visit and byte-compares it for updates, and re-fetches the manifest
  on every load, so `no-cache` is what makes updates reach clients. **Not `immutable`**:
  the filenames are fixed (no content hash), so an `immutable` icon or manifest would
  strand every client on the first deployed version.
- `page()` (`~:1543`): two additions to the `<head>`, after `<base href>` and the
  inlined shell:
  - `<link rel="manifest" href="manifest.webmanifest">` — relative, so it resolves
    against the base — direct and ingress in one. Dashboard pages only (the editor's
    own `index.html` is not the install target).
  - `<script>fhRegisterSw('sw.js')</script>` — the registration call, via a new
    `Server.swRegisterCall` val (so nothing in the page spells the URL; the shell
    resolves it from the frontend manifest). Inlined alongside `UrlSyncScript` so it
    runs before Datastar's deferred module and this document can start cache-firsting
    `web/`/`assets/` immediately.

### `src/js/shell.ts`

- A new fourth helper, `fhRegisterSw(url)`:
  `if (!window.isSecureContext) return; navigator.serviceWorker.register(url).catch(() => {})`.
  The secure-context guard is a cheap synchronous way to avoid a noisy rejection on
  the plain-HTTP direct port (installability still works there via the manifest —
  `register` is what needs the secure origin, not the install prompt). A `register`
  failure is **silent by design**: re-registering on every load is the update check,
  and nothing a page does can un-install a live worker, so the only loss is this
  load's worker. `register`'s relative URL resolves against the document base, so the
  SW lands at `{prefix}/sw.js` with scope `{prefix}/` behind ingress.

### Tests (`ServerRoutesSuite`, `EditorSuite`)

- `GET /sw.js` → 200, `application/javascript`, `Cache-Control: no-cache`.
- `GET /manifest.webmanifest` → 200, `application/manifest+json`, `no-cache`; body
  names the two icons + `display: standalone` (content is checked on the classpath,
  see below).
- `GET /icon-512.png` → 200, `image/png`, `no-cache`; an arbitrary name
  (`sw-ish.js`, `/pwa/manifest.webmanifest`) → 404.
- The rendered page contains `<link rel="manifest" href="${PwaAssets.manifestUrl}">`
  and the `fhRegisterSw('${PwaAssets.swUrl}')` call (via the `pageHtml` helper), and
  the inlined shell defines `window.fhRegisterSw=` (mirrors the existing
  `EditorSuite` helpers check, extended to the four names).
- The `sw.js` bundle is covered by the same `fh-assert-self-contained` build guard as
  the shell (its `classic` set now names `sw`).

## Phase 2 — HTML caching (investigation)

### The model: cached document, SSE-healed

A page snapshot is a document whose cursor lags the store. The server already heals
exactly that shape on every connect: `openingPatches` (Server.scala:761) picks
**resume** (catch-up patches) when the cursor names what the DOM holds, **repaint**
(whole body, `#dashboard`) when the log gap is too big or the log rotated, and
**reload** (`_reload` → `window.location.reload()`, Server.scala:1535) when the head's
unpatchable part (`headHash`) changed. An adopted-or-minted session + the `data-init`
restore query is all the SW needs to hand over; the server owns the repair. This is
ADR 0011's design working as intended.

### "Almost latest and greatest", and why not on navigate-away

The original idea — update the cached copy when navigating away so a later return
starts from the freshest known snapshot — is only ever "almost" current anyway, and
it is the wrong place to do it:

- `pagehide`/`unload` cannot fetch-and-read a response: `sendBeacon` and keep-alive
  fetches can't return a body, and an async `fetch` is not guaranteed to complete
  while the document is going away.
- The same "almost latest" property is delivered automatically by **serving the cache
  immediately and revalidating the network in the background on every load** (SWR /
  NetworkFirst). The cache is then as fresh as the *last visit*, it improves across
  repeat visits without any unload hook, and there is nothing to arrange at teardown.

So Phase 2 replaces "refresh on navigate-away" with SWR, which is the standard pattern
and a superset of the intent.

### Shape of the Phase-2 SW (pending spike)

- **Navigations (`/`, `/d/:slug`): NetworkFirst with a short timeout (~2s)** — serve
  the cached page if the network doesn't answer in time or fails, else serve fresh and
  write the cache. **Never pure cache-first for navigations**: when the server sends
  `_reload` (headHash changed — a dashboard/theme edit), the reload must reach the
  network. Cache-first would hand back the same cached head, mismatch again, and loop
  forever. NetworkFirst makes the reload path network-bound and the loop impossible.
- **Only cache successful (200) responses.** The page URL already carries the
  `?ui.*` view-state params, so keying by full URL gives a per-tab/per-popup snapshot
  for free; cap with a small LRU and prune old slugs.
- **Pass-through unchanged:** every `/sse/` URL, all POSTs, `/system/*`, `/edit/*`.
  The action POSTs must never be cached, and the SSE GET must not be intercepted.
- **Exclude `?edit=1`** (the editor preview) from caching.

### Open questions to spike before Phase-2 implementation

1. **Precache vs runtime cache-first for `/web/*` + `/assets/*`.** Phase 1 caches them
   at runtime. Precache-at-install needs the hashed asset list baked into `sw.js` at
   build time — and Phase 1 already moved the SW INTO the vite build (emitted at the
   output root as the fixed `sw.js`, in the `classic` set), so the remaining question
   is whether to also emit the precache list. That would mean reading vite's
   `build.manifest` (the same source `FrontendAssets` uses) and either generating a
   static precache list in `sw.js` or doing `vite-plugin-pwa` `injectManifest` (Workbox
   — in maintenance mode; a tiny hand-rolled precache is the other option). Runtime
   cache-first may be enough given the HTTP cache already serves repeat loads of
   immutable assets — measure before adding machinery.
2. **Is instant-paint worth it online?** On a LAN the server render is typically
   <100 ms; the win is slow/offline links and phone-to-ingress round trips. Decide
   whether offline display of the last snapshot is an actual requirement, and whether
   NetworkFirst-with-timeout or plain network-first-with-offline-fallback is the right
   default once measured.
3. **Reload-vs-navigation discrimination.** If a later design ever wants cache-first
   navigations online, the SW cannot tell a user navigation from a server-ordered
   `location.reload()`. One escape: have the server add a cache-busting marker to the
   reload URL. Not needed under NetworkFirst; record it so nobody adds strict
   cache-first without revisiting the `_reload` loop.
4. **Theme-driven manifest colors.** `theme_color`/`background_color` are hardcoded in
   Phase 1; the theme owns tokens (`theme-beer.pkl`). Option: a dynamic manifest route
   later. Deferred — hardcoding is fine for install.
5. **Secure context.** SW registration needs HTTPS or localhost. The direct port is
   plain HTTP, so installability works over the HA ingress or `localhost` only. The
   offline-HTML goal inherits this constraint.

## Verification

- `sbt fh-datastar-view/testFull` after each phase — the gate, no live HA needed.
- In-browser (Phase 1): `sbt dashboardServe` on `http://localhost:8080` (localhost is a
  secure context), then DevTools → Application: manifest valid, SW registered +
  activated, install prompt appears; reload twice and confirm `/web/*` + `/assets/*`
  served from the SW cache (Network tab → "ServiceWorker" responder) while `/sse/`
  requests are untouched; confirm the "Reload" banner path still hard-reloads.
- Phase 2: timed first-paint with cached vs cold HTML over a throttled/offline profile;
  confirm a dashboard edit (headHash change) does **not** loop reloads; confirm offline
  shows the last snapshot with the "Reconnecting…" banner.
- Best-effort live check via the HA ingress (HTTPS) if reachable; never block on it.

## Phasing

1. **Phase 1 (implemented):** `src/js/sw.ts` + vite `classic`/`entryFileNames` changes,
   `pwa/` manifest + icons, `PwaAssets` routes, manifest link + `swRegisterCall` in the
   page head, `fhRegisterSw` in the shell, and tests. Green after
   `sbt fh-datastar-view/testFull`.
2. **Phase 2 (investigate, then spec):** spike the open questions above (primarily
   precache-vs-runtime and the offline requirement), then write the Phase-2 section of
   this plan into implementation detail — or into a new ADR when the direction is
   picked (the SW caching strategy is a genuine design decision; the repo rule is a new
   decision gets a new ADR, and since neither phase touches the rendering pipeline,
   `docs/architecture-rendering-pipeline.md` does not move).

## Out of scope (Phase 1)

- Push notifications, background sync, share target — nothing this dashboard needs.
- Icons for the editor page / splash screen variants (`any maskable` split, SVG
  favicon) — one 192 + one 512 set to start.
- Theme-driven manifest colors — deferred (open question 4).
