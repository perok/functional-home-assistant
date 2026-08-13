# Plan: installable PWA for the Datastar dashboard (fh-datastar-view)

**Status: deferred design plan, not implemented.** Phase 1 (PWA installability) is
designed down to the route level and ready to build; Phase 2 (HTML caching) is an
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

### `src/main/resources/pwa/` (new, committed static resources)

- `sw.js` — the service worker, hand-written and import-free (~30 lines; it is
  loaded as a classic script and must stay self-contained, like `shell.ts`). Contents:
  `install → skipWaiting`, `activate → clients.claim()`, and a fetch handler that
  cache-firsts same-origin GETs whose path contains a `web/` or `assets/` segment
  (the immutable trees) and returns without `respondWith` for everything else —
  notably `/sse/*` (intercepting the SSE GET would break the resume cursor + keepalive)
  and all POSTs. Immutable URLs make cache-first safe: a rebuilt bundle is a different
  URL, so a cache hit can never be stale.
- `manifest.webmanifest` — static JSON, location-independent because every URL inside
  it is relative and resolves against its own URL at install time:
  `name`/`short_name`/`description`, `start_url: "."`, `scope: "."`, `id: "."`,
  `display: "standalone"`, `theme_color`/`background_color` (step-1 hardcode; see
  open questions), and `icons` with `src: "icon-192.png"`/`"icon-512.png"` (relative →
  same directory as the manifest). A static file works for both direct serving and the
  ingress prefix because the manifest itself is served at the app root.
- `icon-192.png`, `icon-512.png` — generated once from an MDI glyph (the `@mdi/font`
  the theme already vendors) with the safe-zone padding a maskable icon needs, and
  committed. These are hand-authored static resources, not build output — same status
  as `editor/overlay.css`.

### `runtime/Server.scala`

- New allowlisted route in the routes block (~:92), serving four named files from the
  `pwa/` classpath dir at **root-level URLs** (so the manifest's relative `start_url`
  and icon `src` resolve to the app root, and the SW's scope defaults to the whole
  prefix):
  - `GET -> Root / file if PwaAssets.serves(file)`, where `PwaAssets` (a small object
    in the runtime package) maps name → resource path + content type + cache header:
    - `manifest.webmanifest` → `application/manifest+json` (via `MediaType.unsafeParse`
      if the pinned http4s lacks the `application.manifest+json` constant), `no-cache`
      (the browser revalidates so manifest edits are seen promptly).
    - `sw.js` → `application/javascript`, **`Cache-Control: no-cache`** — the one
      header this plan insists on. A service worker update is byte-compared by the
      browser on every visit, and `no-cache` guarantees it revalidates instead of
      serving a stale SW. This is the "served without caching" requirement.
    - `icon-*.png` → `image/png`, `immutable` like the other static assets (fixed
      names; a changed icon is a deliberate, rare deploy).
  - Any other name → 404 (the allowlist is the whitelist; same un-forgeable pattern
    as `FrontendAssets.serves` / `AssetCache.SafeName`). No path sanitising needed.
- `page()` (`~:1543`): add `<link rel="manifest" href="manifest.webmanifest">` to the
  `<head>` after `<base href>`. Relative, so it resolves against the base — direct and
  ingress in one. Dashboard pages only (the editor's own `index.html` is not the
  install target).

### `src/js/shell.ts`

- Append registration, guarded so the plain-HTTP direct port (not a secure context)
  produces no noisy rejection:
  `if ("serviceWorker" in navigator && window.isSecureContext) navigator.serviceWorker.register("sw.js").catch(() => {})`.
  `register`'s relative URL resolves against the document base (`<base href>`), so the
  SW lands at `{prefix}/sw.js` with scope `{prefix}/` behind ingress. The shell is
  already inlined into every dashboard page, so this runs on first load.
- No vite/`package.json` change in Phase 1 — the SW is a static resource, deliberately
  decoupled from the build until Phase 2 needs build knowledge (see open questions).

### Tests (`ServerRoutesSuite`, `EditorSuite`)

- `GET /sw.js` → 200, `application/javascript`, `Cache-Control: no-cache`.
- `GET /manifest.webmanifest` → 200, `application/manifest+json` (or the chosen
  fallback media type), body parses and names the two icons + `display: standalone`.
- `GET /icon-192.png` / `icon-512.png` → 200, `image/png`, immutable; an arbitrary
  name (`sw.js`, `..%2F..`) → 404.
- The rendered page contains `<link rel="manifest" href="manifest.webmanifest">`
  (assert via the existing `page` output test).
- `FrontendAssets.content("shell")` contains the `serviceWorker.register` call (the
  shell is inlined, so this is the "registration ships on every page" check).
- The `sw.js` resource exists, is non-empty, and contains no `import`/`export`
  statement (it must load as a classic script).

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
   build time, which means moving the SW into the vite build: a per-chunk
   `output.entryFileNames` override emitting `sw.js` un-hashed (vs the global
   `web/[name]-[hash].js`), adding `sw` to the `classic` set in `vite.config.ts`'s
   `assertSelfContained`, and reading `build.manifest` (the same source
   `FrontendAssets` uses) to generate the list — or `vite-plugin-pwa` `injectManifest`
   if Workbox is wanted (it is in maintenance mode; a tiny hand-rolled precache is the
   other option). Runtime cache-first may be enough given the HTTP cache already
   serves repeat loads of immutable assets — measure before adding machinery.
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

1. **Phase 1 (implementable now):** `pwa/` resources + `PwaAssets` routes + manifest
   link + shell registration + tests + icon generation. Green commit after
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
