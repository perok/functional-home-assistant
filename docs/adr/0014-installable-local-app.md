# ADR 0014 — The dashboard as an installable local app

- **Status:** Accepted — installability (Phase 1) implemented; HTML caching (Phase 2) is an open investigation
- **Date:** 2026-08-13
- **Scope:** `modules/fh-datastar-view`

Supersedes `docs/plan-pwa-install.md`. Phase 1 was designed down to the route level,
built and tested; Phase 2 is carried forward as deferred work below.

## Context

The dashboard is not installable: no web app manifest, no service worker, no icons.
Installing it gives a standalone window and a home-screen entry — the reason to build
it. On the local network there is no "deployment": one server process serves the whole
app, and "install" is purely a client-side affordance that pins a window to the app's
origin.

Relevant properties of this app:

- The page is **server-rendered HTML per request** (`/`, `/d/:slug`) with live entity
  state baked in; there is no static `index.html` to precache.
- The document **carries its own resume anchor**: `data-init="@get('sse/dashboard/
  $slug/patch…')"` includes the `conn` + four-part cursor (`headHash`, `styleHash`,
  `logId`, `version`) in its query. Caching the raw HTML preserves that anchor, which
  is what makes "stale document, healed by SSE" work.
- The only cacheable static assets (`/web/*` bundles, `/assets/*` theme cache) are
  **already served `immutable`** by http4s (Server.scala:104, AssetCache.scala:51).
- `/sse/*` (the patch stream + all action POSTs), `/system/*`, `/edit/*` must never
  be intercepted by a service worker.
- Chrome's installability criteria: HTTPS + a manifest with `name`/`short_name`,
  `icons` (192 + 512), `start_url`, and `display: standalone`/`fullscreen`/
  `minimal-ui`. The menu "Install app" path no longer requires a service worker, but
  the **address-bar install prompt and Android WebAPK minting still want a SW with a
  `fetch` handler** — so a minimal SW is the practical route.

**The constraint that shapes everything: service workers only register on secure
contexts** — HTTPS or `localhost`. The direct port is plain HTTP, so installability
works over the HA ingress (HTTPS) or `localhost` only, and every Phase-2 idea inherits
that limit.

## Decision

Installability is a small, explicitly allowed surface on the existing server:
**committed static PWA files** (manifest, icons, built SW) served at **root-level
URLs** with `Cache-Control: no-cache`, plus two lines in the page `<head>`. Nothing
about the rendering pipeline changes (ADRs 0008/0011/0012 own it); this ADR adds
transport, not rendering, so `docs/architecture-rendering-pipeline.md` does not move.

### The service worker is a build output, not a committed file

`sw.ts` is one more vite entry (rollupOptions `input`, added to the `classic` set so
`fh-assert-self-contained` still fails the build on any import/export), emitted at the
**output root as the fixed `sw.js`** via an `entryFileNames` override. Two reasons,
both hard:

- the browser fetches SW updates **at the registered URL**, so a content-hashed
  `sw-<hash>.js` would strand every install on its first version (a stale SW is served
  `immutable` by the HTTP cache and never re-fetched, so the hash that would "update"
  it never even loads);
- it must NOT live under `web/`, which is served `immutable` — the SW needs `no-cache`
  (below), and the root position gives it scope `/` for free, which Phase 2's
  navigation caching needs.

The SW contents (import-free): `install → skipWaiting`, `activate → clients.claim()` +
clear the old cache name, and a fetch handler that cache-firsts same-origin GETs whose
path contains a `web/` or `assets/` segment (the immutable trees) and returns without
`respondWith` for everything else — notably `/sse/*` (intercepting the SSE GET would
break the resume cursor + keepalive) and all POSTs. Immutable URLs make cache-first
safe: a rebuilt bundle is a different URL, so a cache hit can never be stale.

### Committed static `pwa/` resources

`src/main/resources/pwa/` holds `manifest.webmanifest` + `icon-192.png` +
`icon-512.png`, deliberately NOT emitted by vite: `build.manifest` only lists entry
chunks, and the manifest's relative URLs must resolve against the app origin at install
time. The manifest's `start_url`/`scope`/`id` are all `"."`; `icons` are relative to the
manifest itself, so the file works for both direct serving and the ingress prefix.
Icons are rasterized once from the app's `logo.svg` (repo root): the logo's own padding
keeps it inside the maskable safe zone (its content spans ±38% of the canvas against
the 40% circle), so `purpose: "any maskable"` stays honest.

### Serving (PwaAssets)

Four allowlisted root routes in the http4s router — `GET /sw.js`,
`/manifest.webmanifest`, `/icon-192.png`, `/icon-512.png` — resolved by name to a
classpath resource + media type (`application/manifest+json`, `application/javascript`,
`image/png`); any other name → 404 (same un-forgeable pattern as `FrontendAssets.serves`
/ `AssetCache`). **Every PWA file is served `Cache-Control: no-cache`** — the one header
this design insists on, and the reason these files can't ride `/web/`. The browser
revalidates the SW on every visit and byte-compares it for updates, and re-fetches the
manifest on every load; `no-cache` is what makes updates reach clients. **Not
`immutable`**: the filenames are fixed (no content hash), so an `immutable` icon or
manifest would strand every client on the first deployed version.

### The page head

`page()` gains two things, both base-relative so they work behind the HA ingress prefix
exactly like every other app URL: `<link rel="manifest" href="manifest.webmanifest">`
(after `<base href>`, dashboard pages only — the editor's own `index.html` is not the
install target), and `<script>fhRegisterSw('sw.js')</script>` via a `Server.swRegisterCall`
val (so nothing in the page spells the URL; the shell resolves it from the frontend
manifest), inlined before Datastar's deferred module so this document can start
cache-firsting `web/`/`assets/` immediately.

### The registration helper

`fhRegisterSw(url)` in `shell.ts`:
`if (!window.isSecureContext) return; navigator.serviceWorker.register(url).catch(() => {})`.
The secure-context guard is a cheap synchronous way to avoid a noisy rejection on the
plain-HTTP direct port (installability still works there via the manifest — `register`
is what needs the secure origin, not the install prompt). A `register` failure is
**silent by design**: re-registering on every load is the update check, and nothing a
page does can un-install a live worker, so the only loss is this load's worker.
`register`'s relative URL resolves against the document base, so the SW lands at
`{prefix}/sw.js` with scope `{prefix}/` behind ingress.

## Consequences

- `GET /sw.js`, `/manifest.webmanifest`, `/icon-192.png`, `/icon-512.png` return 200
  with the right content type and `Cache-Control: no-cache`; any other name → 404
  (covered by `ServerRoutesSuite`, including an arbitrary `sw-ish.js` name).
- The rendered dashboard page carries the manifest link and the `fhRegisterSw` call;
  the inlined shell defines `window.fhRegisterSw=` (covered by `EditorSuite`, extended
  to the four helper names).
- The `sw.js` bundle is guarded by `fh-assert-self-contained`, same as the shell.
- A frontend rebuild is invisible to the SW's own URL: `sw.js` is fixed and `no-cache`,
  so clients re-fetch and byte-compare on every visit.
- Installability works over the HA ingress (HTTPS) or `localhost`; the plain-HTTP
  direct port gets the manifest path but no SW.

## Alternatives rejected

- **Install from a public HTTPS origin and let the SW reroute to the LAN's plain
  HTTP.** Raised in the PR #121 discussion; rejected. A service worker's scope only
  covers the origin it is registered on, so a SW hosted on a public origin cannot touch
  the LAN app. And `fetch()`/SSE from HTTPS to a plain-HTTP address is **active mixed
  content**, blocked by browsers outright; the escape (CORS +
  `Access-Control-Allow-Private-Network`) assumes a server that speaks both, which plain
  HTTP behind an ingress does not. Workable versions of the idea are **a private CA on
  the devices** or **a reverse proxy with an internal-domain certificate** — tracked as
  follow-up on issue #98, both future work, neither a redesign of this ADR.
- **Workbox / `vite-plugin-pwa` `injectManifest`.** Workbox is in maintenance mode, and
  a tiny hand-rolled SW needs nothing it offers; runtime cache-first covers the
  immutable trees today. Revisit only if Phase 2's precache list makes a build-time
  manifest reader worthwhile.
- **A committed static `sw.js` instead of a vite entry.** Phase 2 will need build
  knowledge in the SW (a precache list), and the emission machinery already exists — the
  SW is built exactly like the shell. Committing it would duplicate that machinery and
  fork the source of truth.
- **Precache-at-install for `/web/*` + `/assets/*` (Phase 1).** Deferred, not chosen:
  the HTTP cache already serves repeat loads of immutable assets; measure before adding
  machinery (see Deferred, Q1).

## Deferred

Phase 2 (HTML caching) is an **investigation, deliberately not specced to the same
depth** until its spike answers the open questions. The direction: serve a cached copy
of the page document immediately (or when offline), then let SSE heal it. The server
already heals exactly that shape on every connect — `openingPatches` (Server.scala:761)
picks **resume** (catch-up patches), **repaint** (whole body) or **reload** when the log
gap is too big / the head changed — which is ADR 0011's design working as intended.

The navigate-away refresh idea was considered and **superseded by
stale-while-revalidate**: `pagehide`/`unload` cannot fetch-and-read a response
(`sendBeacon` and keep-alive fetches return no body), and SWR delivers the same "as
fresh as the last visit" property automatically by serving the cache immediately and
revalidating in the background on every load — nothing to arrange at teardown.

Phase-2 SW shape (pending the spike): navigations are **NetworkFirst with a short
timeout (~2 s)** — never pure cache-first for navigations, because when the server sends
`_reload` (headHash changed — a dashboard/theme edit) the reload must reach the network;
cache-first would hand back the same cached head, mismatch again, and loop forever. Only
successful (200) responses are cached, keyed by full URL (the `?ui.*` view-state params
make per-tab/per-popup snapshots for free), capped with a small LRU; pass-through
unchanged for `/sse/*`, all POSTs, `/system/*`, `/edit/*`; `?edit=1` excluded.

Open questions to spike before Phase-2 implementation:

1. **Precache vs runtime cache-first** for `/web/*` + `/assets/*`. Precache-at-install
   needs the hashed asset list baked into `sw.js` at build time (reading vite's
   `build.manifest`, the same source `FrontendAssets` uses). Runtime cache-first may be
   enough given the HTTP cache already serves repeat loads of immutable assets — measure
   before adding machinery.
2. **Is instant-paint worth it online?** On a LAN the server render is typically
   <100 ms; the win is slow/offline links and phone-to-ingress round trips. Decide
   whether offline display of the last snapshot is an actual requirement.
3. **Reload-vs-navigation discrimination.** Under cache-first navigations the SW cannot
   tell a user navigation from a server-ordered `location.reload()`. One escape: a
   cache-busting marker on the reload URL. Not needed under NetworkFirst; recorded so
   nobody adds strict cache-first without revisiting the `_reload` loop.
4. ~~**Theme-driven manifest colors.**~~ **Answered: yes, from the dashboard served at `/`.**
   `/manifest.webmanifest` fills `theme_color`/`background_color` per request from
   `ChromeColors.from(theme)` — the default dashboard's `primary-background-color`, both palettes —
   and the committed file's values are only the fallback for an instance with no theme to ask
   (nothing registered, or a dashboard that failed to build).

   The two members are not the same kind of value, and the difference decides how much this
   matters. `theme_color` is a **default**: a page's own `<meta name="theme-color">` overrides it
   everywhere the manifest applies, and every dashboard page carries a scheme-qualified pair from
   `Renderer.themeColorTags` — so the manifest value is what the surfaces with no document of ours
   get (the splash, the task switcher, and any page we serve without a meta). `background_color`
   has **no meta equivalent and cannot have one**: it paints the window before the stylesheets
   load, i.e. before there is a document to carry a meta. The manifest is its only channel.

   **Which dashboard is a choice; which scheme is not.** A manifest is one per ORIGIN, so it takes
   the theme of whatever `/` serves. It no longer holds only one colour, though:
   [`color_scheme_dark`](https://github.com/w3c/manifest/pull/1207) (merged into the spec
   2026-04-09) is an ordered map of overrides for the *themeable members* — exactly `theme_color`
   and `background_color` — applied when the OS is in dark mode. Both are emitted into it, so the
   manifest says the same thing the metas do.

   The bare members are therefore the light-mode value **and** the value for every browser that
   does not implement the override — today Chrome (crbug.com/383165202; WebKit shipped it in
   May 2026), which is nearly every client here. So `ChromeColors.base` is **pinned to the dark
   colour** and the override is currently a no-op. On Chrome the trade is one-sided: base-dark
   costs a light-mode phone a dark bar, base-light costs a dark-mode phone the white stripe this
   whole path exists to fix.

   It is not free, though, and calling it a wait for implementations would be wrong. WebKit
   already implements the override, so a Safari-installed app would read the light/dark pair
   correctly today and instead gets a dark bar in light mode *because of this pin*. We are buying
   the Chrome majority at Safari's expense — worth revisiting the moment this runs anywhere iOS
   is the common client. When Chrome ships, flip `base` to `light`; the manifest test in
   `ServerRoutesSuite` pins the current choice, so it goes red on that flip by design.

   A theme defining only one palette fills both the base and the override with it, rather than
   leaving a scheme to the browser's own chrome.

   Every page of ours in scope either carries a meta or deliberately does not: dashboards do, the
   editor names its own fixed dark (`editor/index.html`), and the failed-dashboard error page has
   no theme to derive one from and correctly takes the manifest's. That settles the browser-tab
   case only — inside the installed app Chrome ignores every one of those metas, per the
   divergence below, and each of those pages gets the manifest's colour. Acceptable: the manifest
   is dark while the pin holds, so the worst outcome is the editor wearing the dashboard's dark
   rather than its own.

   **Chrome diverges from the spec here, which is why we keep `theme_color` at all.** The advice
   you will find when you search this — [SO 79744082](https://stackoverflow.com/a/79744082) and
   [its author's dev.to post](https://dev.to/fedtti/how-to-provide-light-and-dark-theme-color-variants-in-pwa-1mml),
   both Aug 2025 — is to drop `theme_color` from the manifest and let the two metas do the whole
   job. The metas half is right and we do it. The removal half is not: an installed PWA's status
   bar on Chrome takes the **manifest's** `theme_color` and ignores the document's meta
   (crbug [40759522](https://issues.chromium.org/issues/40759522),
   [40686953](https://issues.chromium.org/issues/40686953),
   [40634649](https://issues.chromium.org/issues/40634649) — titles readable, bodies need a
   sign-in, so the fix status is unknown). Remove it and a standalone app has no colour source at
   all. That divergence is also what explains the observation that started this: a white bar weeks
   after the tokens moved, with no deploy to correlate against.

   The consequence: for an INSTALLED app the manifest is the only channel that reaches the status
   bar, so `color_scheme_dark` is the only route there will ever be to per-scheme chrome there.
   The metas serve the surfaces it does not reach — an ordinary browser tab, `minimal-ui`. Still
   unconfirmed on a device.

   Note that an installed app takes manifest changes LAZILY — Chrome caches it at install — so a
   change here surfaces on phones days after the deploy that made it, which is what made the
   original symptom hard to attribute.
5. **HTTPS on the intranet** (issue #98 follow-up): a private CA or reverse proxy so the
   SW activates on LAN devices — the precondition for Phase 2's offline goal to matter
   outside `localhost`.

Out of scope throughout: push notifications, background sync, share target; the
`any`/`maskable` split and SVG favicon; anything that touches the rendering pipeline.
