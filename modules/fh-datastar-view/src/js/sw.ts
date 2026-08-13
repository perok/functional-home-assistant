// The service worker — the "worker" half of the installable PWA. The page
// registers it via the shell (`fhRegisterSw`, called from `Server`'s page head
// with the manifest-resolved URL); once active it cache-firsts the two
// immutable static trees — `/web/*` (content-hashed bundles) and `/assets/*`
// (the theme's cached external assets) — and passes everything else through
// untouched: notably the `/sse/*` stream (intercepting the SSE GET would break
// the resume cursor + heartbeat) and all POSTs, plus navigations (Phase 1
// caches no HTML — Phase 2's NetworkFirst is where that lands, and it needs
// the network for the server's `_reload` to reach the page).
//
// Cache-first is safe here only because those two trees are served `immutable`
// and their URLs carry a content hash: a rebuilt bundle is a NEW URL, so a
// cache hit can never be stale, and a miss simply fetches the new hash and
// caches it. An old SW therefore never blocks a new bundle.
//
// IMPORTANT: like `shell.ts`, no import/export — emitted as an `es` chunk that
// must work as a CLASSIC script. The `sw` entry is in vite.config.ts's
// `classic` set, so the `fh-assert-self-contained` plugin fails the build if
// that ever breaks.
//
// It is emitted at a FIXED `sw.js` name at the app root on purpose: the browser
// fetches SW updates at the registered URL, so a content-hashed filename would
// strand every install on its first version. The "latest and greatest" comes
// from `Cache-Control: no-cache` on the serving route + re-registering on every
// page load (which triggers the update check), not from a new hash. The root
// position also gives the SW scope `/`, which Phase 2's navigation caching
// needs.

/// <reference lib="webworker" />

declare const self: ServiceWorkerGlobalScope

const CACHE = "fh-static"

self.addEventListener("install", () => {
  self.skipWaiting()
})

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener("fetch", (event) => {
  const req = event.request
  if (req.method !== "GET") return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  if (!url.pathname.includes("/web/") && !url.pathname.includes("/assets/"))
    return
  event.respondWith(
    caches.open(CACHE).then((cache) =>
      cache.match(req).then((hit) => {
        if (hit) return hit
        return fetch(req).then((resp) => {
          if (resp.ok) cache.put(req, resp.clone())
          return resp
        })
      })
    )
  )
})

export {}
