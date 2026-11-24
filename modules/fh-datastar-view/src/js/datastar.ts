// The page's Datastar: the vendored bundle (`vendor/datastar/`, whose
// `datastar.js` names its version on the first line) plus this project's own
// attributes, built into ONE
// module. Datastar's docs recommend hosting the file yourself; bundling it
// here also means there is exactly one Datastar instance on the page, and that
// `build.target` in vite.config.ts lowers its syntax along with ours.
//
// Loaded as `<script type="module">` by `Server.pageInto`. Registering an
// attribute here, at module evaluation, puts it in Datastar's first scan of
// the document: the bundle queues every registration and applies them in one
// pass after a `setTimeout`.

import { attribute, effect } from "./vendor/datastar/datastar.js"

/**
 * `data-fh-url="['<param>', $signal, '<declared>']"` (or `data-fh-url:<name>`): mirror one piece of view
 * state into the page URL without navigating, for as long as the element is in
 * the document (ADR 0005). An empty value drops the param, and so does the
 * optional declared one — a node variable's own, which no param already means —
 * so a URL names only what a viewer changed; so does the last
 * element for that param leaving (issue #411): a closed popup takes its tab
 * bars and window choosers with it, and nothing else would ever clear what they
 * wrote.
 *
 * A hand-rolled `data-query-string`, which is a Datastar Pro plugin we do not
 * have. Signals stay the LIVE carrier (what reaches the server on a reconnect
 * and on every action); the URL is their mirror, for the two things a signal
 * cannot do: survive a refresh, and stay unique per document.
 *
 * `replaceState`: this is view state, not navigation. Back should leave the
 * dashboard, not step back through tab clicks. The exception is `__history`,
 * which the open popup's mirror and a variable declared `.inHistory()` carry:
 * each value is a history entry, so Back steps to the previous one and then to
 * the dashboard; the shell posts the URL Back moved to, and the server shows
 * what it names. A value the URL already names is not pushed, because Back or
 * Forward put it there; that is all that tells the two apart.
 *
 * An empty value cannot tell "cleared" from "never initialised" — Datastar
 * creates a signal as `""` the moment an expression reads one — so a mirror
 * that runs before its seed drops the param until the seed lands.
 *
 * Counted per param, not per element: a morph that replaces a host can add the
 * new element before it removes the old one.
 */
attribute({
  name: "fh-url",
  // A key only keeps attribute names unique: a node declaring two variables
  // mirrors each from its own `data-fh-url:<name>`.
  requirement: { key: "allowed", value: "must" },
  returnsValue: true,
  apply({ rx, mods }) {
    let held: string | null = null
    const stop = effect(() => {
      const [key, raw, declared] = rx() as [string, unknown, string?]
      const shownValue = raw == null ? "" : String(raw)
      const value = shownValue === declared ? "" : shownValue
      if (held !== key) {
        if (held !== null) release(held)
        held = key
        mirrors.set(key, (mirrors.get(key) ?? 0) + 1)
        if (mods.has("history")) historyKeys.add(key)
      }
      if (mods.has("history") && value !== "" && !shown(key, value))
        pushParam(key, value)
      else setParam(key, value)
    })
    return () => {
      stop()
      if (held !== null) release(held)
    }
  },
})

const mirrors = new Map<string, number>()
const historyKeys = new Set<string>()

function release(key: string) {
  const left = (mirrors.get(key) ?? 1) - 1
  if (left > 0) {
    mirrors.set(key, left)
    return
  }
  mirrors.delete(key)
  setParam(key, "")
}

function withParam(key: string, value: string): URL {
  const url = new URL(location.href)
  if (value === "") url.searchParams.delete(key)
  else url.searchParams.set(key, value)
  return url
}

// `history.state` carried over: it marks an entry `pushParam` made, and a tab
// switch inside the popup must not erase that.
function setParam(key: string, value: string) {
  firstPush.delete(key)
  history.replaceState(history.state, "", withParam(key, value))
}

/** On an entry `pushParam` made, naming this value. A page that LOADS with a
 * popup open (a shared link, a refresh of an entry it did not make) fails the
 * first half, so the popup still gets a dashboard entry beneath it and a close,
 * which is always `history.back()`, never leaves the dashboard.
 *
 * The mark is the mirror's, not a key's: two history variables share entries,
 * and a change to one must not strip the other's param from its own.
 */
function shown(key: string, value: string): boolean {
  return (
    history.state?.fhPushed === true &&
    new URL(location.href).searchParams.get(key) === value
  )
}

/** On an entry it did not make, every history param leaves that entry, and
 * the push keeps them all, so a link naming two is one entry above the
 * dashboard. That push waits a microtask: Datastar applies a scan's
 * attributes in one pass, and a mirror applied later in it is a history key
 * the strip must already know.
 */
function pushParam(key: string, value: string) {
  if (history.state?.fhPushed === true) {
    history.pushState({ fhPushed: true }, "", withParam(key, value))
    return
  }
  if (firstPush.size === 0) void Promise.resolve().then(flushFirstPush)
  firstPush.set(key, value)
}

const firstPush = new Map<string, string>()

function flushFirstPush() {
  const under = new URL(location.href)
  const next = new URL(location.href)
  for (const k of historyKeys) under.searchParams.delete(k)
  for (const [k, v] of firstPush) next.searchParams.set(k, v)
  firstPush.clear()
  history.replaceState(history.state, "", under)
  history.pushState({ fhPushed: true }, "", next)
}
