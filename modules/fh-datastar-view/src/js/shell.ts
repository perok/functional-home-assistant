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
    fhToast: (text: string) => void
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

/**
 * Stop the connection banners painting on a page that is already leaving.
 *
 * Navigating away aborts the SSE stream, which fires `error` on the OUTGOING
 * document — so the page the user is walking away from spends the rest of its
 * life claiming the dashboard is unreachable. The 600ms debounce on the banner's
 * own handler was meant to cover this and does not: it only buys 600ms, and a
 * cross-dashboard navigate on a phone routinely takes longer, so the flash the
 * debounce was supposed to hide is exactly what shows.
 *
 * A CSS class rather than a signal, because the fact is "this document is
 * done", not "the connection is fine" — the banner state stays truthful, it
 * just stops being painted. It also covers the case the signal cannot: a banner
 * that was ALREADY up when the user navigated away.
 *
 * `pagehide` (not `beforeunload`) because it is the event that fires for every
 * way out, bfcache included, and does not ask to be the one that blocks the
 * navigation.
 */
addEventListener("pagehide", () => {
  document.documentElement.classList.add("fh-leaving")
})

let toastTimer: ReturnType<typeof setTimeout> | undefined

/**
 * Toast a refused action as a transient bar.
 *
 * The LOOK is theme-owned (`.fh-toast` in each theme's `styles`); this owns
 * presence only. A later toast replaces an earlier one instead of stacking,
 * and each dismisses itself after ~4s.
 *
 * Exposed as `window.fhToast` because the SERVER is the caller now: a refused
 * action answers 200 patching `_toast`, and the `<body>`'s signal-patch handler
 * calls this with HA's own message. `showToast` stays the local name for the
 * one case a signal cannot reach — a response that is not 200 at all, whose
 * body Datastar never parses.
 */
function showToast(text: string): void {
  let el = document.querySelector<HTMLElement>(".fh-toast")
  if (!el) {
    el = document.createElement("div")
    el.className = "fh-toast"
    el.setAttribute("role", "status")
    document.body.appendChild(el)
  }
  el.textContent = text
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    const toast = document.querySelector<HTMLElement>(".fh-toast")
    if (toast && toast.parentNode) toast.parentNode.removeChild(toast)
    toastTimer = undefined
  }, 4000)
}

window.fhToast = showToast

/**
 * The action-failure half of this page's Datastar fetch events. Datastar
 * dispatches a `datastar-fetch` CustomEvent on `document` for every fetch its
 * attributes make, `detail = {type, el, argsRaw}` (verified against the pinned
 * v1.0.2 bundle: `re = (e,t,n) => document.dispatchEvent(new CustomEvent(j,
 * {detail:{type:e, el:t, argsRaw:n}}))`).
 *
 * This is the REMAINDER, not the main path: a refused action answers 200
 * carrying `_toast` with HA's own message, and `<body>`'s signal-patch handler
 * shows that. What lands here is a response that is not 200 at all — an auth
 * redirect, a route that is gone, a proxy in the way — where the bundle drops
 * the body unread (`if (M !== 200) { … return }`) and `argsRaw` carries only
 * `{status}`. So a status code is all this branch can ever say, which is
 * exactly right for the failures that have no message to give.
 *
 * The filter is the whole point. The persistent SSE stream is ALSO a Datastar
 * fetch (the `@get` on `<body>`), and ITS errors are already the `_sse` /
 * `haDown` banners' job — they arrive with `el` = `<body>`, which sits under no
 * `[data-on:click]`, so this listener toasts only action failures. Without the
 * filter a stream outage would toast "Command failed" over the banner.
 */
document.addEventListener("datastar-fetch", (e: Event) => {
  const detail = (
    e as CustomEvent<{ type: string; el: Element | null; argsRaw: { status: string } }>
  ).detail
  if (!detail || detail.type !== "error") return
  const el = detail.el
  // The escaped colon is load-bearing: `data-on:click` is not a valid CSS
  // selector as written, and `closest` throws a SyntaxError on it — which
  // killed this listener (and the toast with it) before it ever ran.
  if (!el || !el.closest("[data-on\\:click]")) return
  showToast("Command failed (" + detail.argsRaw.status + ")")
})

/**
 * The connection banner's half of the same split, as its own event.
 *
 * `datastar-fetch` fires for EVERY fetch on the page, but only one of them is
 * the SSE stream — the `data-init` `@get`, which fires from `<body>`, so
 * `el === document.body` is exactly it. The banner's handler is debounced (a
 * sub-second blip must never paint), and a debounce keeps only the LAST event
 * of its window: filtering inside that handler is too late, because an
 * action's fetch has already displaced the stream event it followed. So the
 * filter has to happen per-event, here, and the banner listens to a stream that
 * contains nothing else.
 *
 * That displacement was a real bug in both directions: a click the server
 * rejected raised "Reconnecting to the dashboard…" on a live connection, and a
 * stream frame arriving while a tap's POST was failing put the banner away
 * again — leaving nothing on screen to say the tap could not be answered.
 *
 * `detail` carries the fetch `type` and nothing else; classifying it is the
 * banner's job (`Server.page`), not this bridge's.
 */
document.addEventListener("datastar-fetch", (e: Event) => {
  const detail = (e as CustomEvent<{ type: string; el: Element | null }>).detail
  if (!detail || detail.el !== document.body) return
  document.dispatchEvent(
    new CustomEvent("fh-stream", { detail: { type: detail.type } })
  )
})

/**
 * The iOS half of "a dashboard does not zoom under a finger" (#306). The CSS
 * half — `html{touch-action:pan-x pan-y}` under `(pointer:coarse)`, in
 * `core/css.pkl` — is the whole fix everywhere else, and this is here because
 * WebKit does not apply `touch-action` to the page's own pinch zoom.
 *
 * `gesturestart` is Safari's alone: no other engine fires it, so nothing else
 * ever reaches the listener. Registering it behind the same coarse-pointer
 * query the CSS uses keeps the two halves saying one thing — a desktop keeps
 * every zoom it has.
 *
 * Not `passive`, because preventing the default IS the point; the default
 * listener option would make the call a no-op with a console warning.
 */
if (matchMedia("(pointer: coarse)").matches) {
  document.addEventListener(
    "gesturestart",
    (e: Event) => e.preventDefault(),
    { passive: false }
  )
}

export {}
