// Minimal service worker: just enough to make the dashboard installable and to
// keep the shell + static assets available offline. It deliberately does NOT
// touch the live data paths.
//
//  - live SSE stream (`/sse/`) and any non-GET (action POSTs): always network,
//    never cached — a stale action or a replayed stream would be wrong.
//  - static assets (`/assets/`, `/pwa/`): cache-first (mirrors the server's
//    AssetCache; "offline HA is a feature").
//  - navigations + everything else: network-first, falling back to cache so the
//    installed app still opens its last shell when the server is unreachable.
const CACHE = 'fh-pwa-v1';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return; // actions/POSTs bypass the SW entirely
  const url = new URL(req.url);
  if (url.pathname.includes('/sse/')) return; // live stream: always the network

  const staticAsset =
    url.pathname.includes('/assets/') || url.pathname.includes('/pwa/');

  if (staticAsset) {
    event.respondWith(
      caches.open(CACHE).then((c) =>
        c.match(req).then(
          (hit) =>
            hit ||
            fetch(req).then((res) => {
              if (res.ok) c.put(req, res.clone());
              return res;
            })
        )
      )
    );
    return;
  }

  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok && req.mode === 'navigate') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
        }
        return res;
      })
      .catch(() => caches.match(req))
  );
});
