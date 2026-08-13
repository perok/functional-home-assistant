// The page shell's own JavaScript: four helpers the server-rendered document
// needs before Datastar (a deferred module) has run. INLINED into the page head
// by `Server.page` — not linked — because
// `fhConn` is called from the middle of the body and `fhUrl` from the first
// Datastar effect, so neither can afford a deferred module or a second round
// trip.
//
// These four names, the `prev` query parameter, and the two sessionStorage
// keys are protocol shared with the backend; `Server.PrevConnParam` and
// `ServerRoutesSuite` pin the ones that matter from the other side.
//
// IMPORTS NOTHING, and must not: the bundle is emitted as an `es` chunk and is
// usable as a classic script only because rollup has no import to write into
// it. One shared module between this and `editor/overlay.js` splits a chunk out
// and breaks both. `EditorSuite` guards it; see vite.config.ts.

declare global {
  interface Window {
    fhUrl: (key: string, value: string | null) => void
    fhConn: (id: string) => void
    fhScroll: (slug: string) => void
    fhRegisterSw: (url: string) => void
  }
}

/**
 * Mirror one piece of view state into the page URL without navigating: set the
 * param, or drop it when the value is empty.
 *
 * A hand-rolled `data-query-string`, which is a Datastar Pro plugin we do not
 * have (ADR 0005). Signals stay the LIVE carrier — they are what reaches the
 * server on a reconnect and on every action — and the URL is their mirror, for
 * the two things a signal cannot do: survive a refresh, and stay unique per
 * document (a cookie is per-origin, so a second tab on the same dashboard would
 * overwrite the first one's selection).
 *
 * `replaceState`, never `pushState`: this is view state, not navigation. Back
 * should leave the dashboard, not step back through tab clicks.
 *
 * An empty value DROPS the param, and that is not defensive: it is how a client
 * says "closed" (a dismissed popup). It does mean this cannot tell "cleared"
 * from "never initialised" — Datastar creates a signal as `""` the moment an
 * expression reads one — which is why the seeds that feed it must ASSERT rather
 * than initialise-if-missing. See `Tabs` in components.pkl.
 */
window.fhUrl = (key, value) => {
  const url = new URL(location.href)
  if (value === "" || value == null) url.searchParams.delete(key)
  else url.searchParams.set(key, value)
  history.replaceState(null, "", url)
}

/**
 * Hand this tab's PREVIOUS session id to the stream that is about to replace
 * it, and remember the new one for the next load.
 *
 * `sessionStorage` because it is the only storage with the lifetime wanted: per
 * TAB and surviving a reload. A cookie is per browser, so two tabs on one
 * dashboard would name the same predecessor and each would try to retire the
 * other's live session; `localStorage` is worse for the same reason.
 *
 * It rewrites the `data-init` URL rather than fetching anything, so the
 * retirement rides the connect the server was going to handle anyway — no extra
 * request on every page load. Safe to run at that point in the parse: `<body>`
 * exists (the tag is open), and Datastar is a deferred module, so the attribute
 * is final before anything reads it.
 *
 * Silent on failure by design. Private browsing and storage-partitioned embeds
 * can throw on `sessionStorage`, and the whole feature is an optimisation —
 * losing it means a superseded session waits out its adoption window, which is
 * what happened before this existed.
 */
window.fhConn = (id) => {
  try {
    const key = "fh.conn"
    const previous = sessionStorage.getItem(key)
    sessionStorage.setItem(key, id)
    if (!previous || previous === id) return
    const body = document.body
    const init = body && body.getAttribute("data-init")
    if (!init) return
    const mine = "conn=" + encodeURIComponent(id)
    body.setAttribute(
      "data-init",
      init.replace(mine, mine + "&prev=" + encodeURIComponent(previous)),
    )
  } catch {
    /* storage unavailable — the session just waits out its adoption window */
  }
}

/**
 * Restore this dashboard's scroll offset, and arrange to save it again when the
 * document goes away.
 *
 * Crossing to another dashboard is a real document load (ADR 0002), so the page
 * that comes back is a NEW document: nothing on it survives that the server did
 * not render, and scroll is not something the server knows. That left the
 * offset to the browser, and on this page the browser cannot help — a document
 * holding a streaming `fetch` (Datastar's SSE `@get`) is not back/forward-cache
 * eligible, so even a back button re-loads the document rather than restoring a
 * live one. Returning to a long dashboard therefore always landed at the top.
 *
 * `sessionStorage`, keyed by slug, for the same reason `fhConn` uses it: per TAB
 * and surviving a load, so two tabs on one dashboard do not drag each other
 * around. Deliberately NOT the URL mirror that carries the tab and the popup
 * (ADR 0005): those are the view a link should name, while an offset would
 * rewrite the URL on every scroll frame and make a shared link land somebody
 * else mid-page.
 *
 * `scrollRestoration='manual'` because the browser's own attempt is what this
 * replaces, not races: with `auto` it can re-apply its offset AFTER this runs,
 * and on the reload path that offset is 0.
 *
 * CALLED LAST IN `<body>`, and that is the whole reason there is no visible
 * jump: the body is server-rendered and the stylesheets are render-blocking, so
 * the document has its final height by the time the closing script runs, and
 * the offset is set before the first paint.
 */
window.fhScroll = (slug) => {
  try {
    const key = "fh.scroll." + slug
    history.scrollRestoration = "manual"
    addEventListener("pagehide", () => {
      try {
        sessionStorage.setItem(key, String(scrollY))
      } catch {
        /* nothing to do but land at the top next time */
      }
    })
    const saved = Number(sessionStorage.getItem(key))
    if (saved > 0) scrollTo(0, saved)
  } catch {
    /* storage unavailable — landing at the top is where we were */
  }
}

/**
 * Install the service worker, and keep checking for updates.
 *
 * Deliberately NOT a third-party helper (the PWA patterns library's `sw-shell` /
 * `pwa-install` components, which import this file and are v0.x) — a plain
 * `navigator.serviceWorker.register` is the whole mechanism:
 *
 * - It is a no-op in insecure contexts (`register` needs a secure origin, and
 *   `window.isSecureContext` is the cheap synchronous guard), so an HTTP-only
 *   LAN address still gets the install prompt — the manifest link, not the SW,
 *   is what drives installability.
 * - The worker takes over the current page (`skipWaiting` in its install + a
 *   call that activates it), so this load can begin cache-firsting `web/` and
 *   `assets/` immediately.
 * - Re-registering with the same URL on every page load is the update check:
 *   the browser re-fetches the script (the route serves it no-cache), and on
 *   change installs the new worker, which then takes over on the NEXT load —
 *   nothing a page does here can un-install or downgrade a live worker, so a
 *   register failure must be silent.
 */
window.fhRegisterSw = (url) => {
  if (!window.isSecureContext) return
  const sw = navigator.serviceWorker
  if (!sw) return
  sw.register(url).catch(() => {})
}

export {}
