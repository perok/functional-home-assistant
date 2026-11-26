// The browser the page's own scripts expect, inside a GraalJS context: a
// linkedom document parsed from `globalThis.__html` at `globalThis.__url`,
// set up before Datastar evaluates (it reads `document` at module load).
//
// Everything that would happen later in a browser waits for the host instead,
// so a run is deterministic: timers are one queue the host steps
// (`__oracle.step`), and every fetch is held until the host answers it.
import "./pre.js"
import * as L from "linkedom"

const g = globalThis

let now = 0
let seq = 0
const timers = new Map()
g.setTimeout = (fn, ms = 0, ...args) => {
  const id = ++seq
  timers.set(id, { at: now + Math.max(0, Number(ms) || 0), fn, args })
  return id
}
g.clearTimeout = (id) => {
  timers.delete(id)
}
// A repeating timer would keep the queue from ever settling; nothing on a
// dashboard page sets one.
g.setInterval = () => 0
g.clearInterval = () => {}
g.requestAnimationFrame = (fn) => g.setTimeout(() => fn(now), 16)
g.cancelAnimationFrame = (id) => g.clearTimeout(id)
g.performance ??= { now: () => now }

/** Runs the earliest timer, ties by creation; false when none is left. */
const step = () => {
  let next = null
  for (const [id, t] of timers)
    if (!next || t.at < next[1].at || (t.at === next[1].at && id < next[0])) next = [id, t]
  if (!next) return false
  timers.delete(next[0])
  now = Math.max(now, next[1].at)
  try {
    next[1].fn(...next[1].args)
  } catch (e) {
    problems.push("threw: " + (e && e.message))
  }
  return true
}

/** What the oracle counts as a miss: a warning or an error the page logged. */
const problems = []
let quiet = false
const logged = (level) => (...args) => {
  if (quiet) return
  problems.push(
    level + ": " + args.map((a) => (a instanceof Error ? a.message : typeof a === "string" ? a : JSON.stringify(a))).join(" "),
  )
}
g.console.warn = logged("warn")
g.console.error = logged("error")

class TextDecoderP {
  decode(u8) {
    if (!u8) return ""
    let s = ""
    for (const b of u8) s += String.fromCharCode(b)
    return decodeURIComponent(escape(s))
  }
}
class TextEncoderP {
  encode(s) {
    const b = unescape(encodeURIComponent(s))
    return Uint8Array.from(b, (c) => c.charCodeAt(0))
  }
}
g.TextDecoder ??= TextDecoderP
g.TextEncoder ??= TextEncoderP

const dec = (s) => decodeURIComponent(s.replace(/\+/g, " "))
const enc = (s) => encodeURIComponent(s).replace(/%20/g, "+")
class URLSearchParamsP {
  constructor(q = "") {
    this._ = []
    q = String(q).replace(/^\?/, "")
    if (q)
      for (const kv of q.split("&")) {
        const i = kv.indexOf("=")
        this._.push(i < 0 ? [dec(kv), ""] : [dec(kv.slice(0, i)), dec(kv.slice(i + 1))])
      }
  }
  get(k) {
    const e = this._.find((e) => e[0] === k)
    return e ? e[1] : null
  }
  has(k) {
    return this._.some((e) => e[0] === k)
  }
  set(k, v) {
    const i = this._.findIndex((e) => e[0] === k)
    if (i < 0) this._.push([k, String(v)])
    else {
      this._[i][1] = String(v)
      this._ = this._.filter((e, j) => e[0] !== k || j === i)
    }
  }
  append(k, v) {
    this._.push([k, String(v)])
  }
  delete(k) {
    this._ = this._.filter((e) => e[0] !== k)
  }
  sort() {
    this._.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
  }
  forEach(f) {
    this._.forEach(([k, v]) => f(v, k, this))
  }
  keys() {
    return this._.map((e) => e[0])[Symbol.iterator]()
  }
  entries() {
    return this._[Symbol.iterator]()
  }
  [Symbol.iterator]() {
    return this._[Symbol.iterator]()
  }
  get size() {
    return this._.length
  }
  toString() {
    return this._.map(([k, v]) => enc(k) + "=" + enc(v)).join("&")
  }
}
class URLP {
  constructor(u, base) {
    u = String(u)
    if (!/^[a-z]+:/i.test(u)) {
      const b = new URLP(base ?? "http://fake.test/")
      u = u.startsWith("/")
        ? b.origin + u
        : u.startsWith("?")
          ? b.origin + b.pathname + u
          : b.origin + b.pathname.replace(/[^/]*$/, "") + u
    }
    const m = /^([a-z]+:)\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(u)
    if (!m) throw new TypeError("Invalid URL: " + u)
    this.protocol = m[1]
    this.host = m[2]
    this.pathname = m[3] || "/"
    this.searchParams = new URLSearchParamsP(m[4] || "")
    this.hash = m[5] || ""
  }
  get origin() {
    return this.protocol + "//" + this.host
  }
  get search() {
    const s = this.searchParams.toString()
    return s ? "?" + s : ""
  }
  set search(s) {
    this.searchParams = new URLSearchParamsP(s)
  }
  get href() {
    return this.origin + this.pathname + this.search + this.hash
  }
  toString() {
    return this.href
  }
}
g.URL ??= URLP
g.URLSearchParams ??= URLSearchParamsP

class AbortSignalP {
  constructor() {
    this.aborted = false
    this.reason = undefined
    this._l = []
  }
  addEventListener(_, f) {
    this._l.push(f)
  }
  removeEventListener() {}
  throwIfAborted() {
    if (this.aborted) throw this.reason
  }
}
class AbortControllerP {
  constructor() {
    this.signal = new AbortSignalP()
  }
  abort(r) {
    if (this.signal.aborted) return
    this.signal.aborted = true
    this.signal.reason = r ?? new Error("AbortError")
    this.signal._l.forEach((f) => f())
  }
}
g.AbortController ??= AbortControllerP

// linkedom maps a dataset key back through uhyphen, which splits digits
// (`__delay.300ms` becomes `__delay.30-0ms`, and Datastar then refuses the
// attribute); the HTML spec's rule instead.
const toAttr = (k) => "data-" + k.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase())
const toKey = (n) => n.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())
Object.defineProperty(L.Element.prototype, "dataset", {
  configurable: true,
  get() {
    const el = this
    const o = {}
    for (const { name, value } of el.attributes) if (name.startsWith("data-")) o[toKey(name)] = value
    return new Proxy(o, {
      get: (_, k) => (typeof k === "string" ? (el.getAttribute(toAttr(k)) ?? undefined) : undefined),
      set: (t, k, v) => {
        el.setAttribute(toAttr(k), String(v))
        t[k] = String(v)
        return true
      },
      deleteProperty: (t, k) => {
        el.removeAttribute(toAttr(k))
        return delete t[k]
      },
    })
  },
})

const dom = L.parseHTML(g.__html)
for (const k of Object.keys(L)) if (/^[A-Z]/.test(k) && typeof L[k] === "function" && !(k in g)) g[k] = L[k]
// The window's own constructors are getters on `dom`, which no key listing shows.
for (const k of ["MutationObserver", "CustomEvent", "Event", "DOMParser", "HTMLElement", "Element", "Node", "DocumentFragment"])
  g[k] = dom[k]
const { document } = dom

// linkedom calls an observer back even when a disconnect emptied its records,
// where a browser skips it. Datastar's `data-attr` re-observes in its callback,
// so an instance a morph had cleaned up came back, and two observers of one
// attribute woke each other forever (the microtask queue never drained).
const LinkedomObserver = g.MutationObserver
g.MutationObserver = class extends LinkedomObserver {
  constructor(callback) {
    super((records, observer) => records.length && callback(records, observer))
  }
}

// Datastar parses every patch with `DOMParser`, and linkedom does not adopt a
// node another document made when it is inserted, so the page's
// MutationObserver never heard a morph's new elements and none of their
// bindings ran. A browser adopts on insert; here the parse does.
const parseFromString = g.DOMParser.prototype.parseFromString
g.DOMParser.prototype.parseFromString = function (...args) {
  const parsed = parseFromString.apply(this, args)
  const adopt = (n) => {
    n.ownerDocument = document
    if (n.localName === "template") adopt(n.content)
    for (const c of n.childNodes ?? []) adopt(c)
  }
  adopt(parsed.documentElement)
  return parsed
}

let href = g.__url
let reloaded = false
const entries = [{ state: null, href }]
const history = {
  get state() {
    return entries[entries.length - 1].state
  },
  get length() {
    return entries.length
  },
  replaceState(state, _, u) {
    href = new URL(String(u), href).href
    entries[entries.length - 1] = { state, href }
  },
  pushState(state, _, u) {
    href = new URL(String(u), href).href
    entries.push({ state, href })
  },
  // The entry above is dropped rather than kept for a Forward nobody steps.
  back() {
    if (entries.length < 2) return
    entries.pop()
    href = entries[entries.length - 1].href
    g.setTimeout(() => {
      const e = new g.Event("popstate")
      e.state = history.state
      g.dispatchEvent(e)
    })
  },
  forward() {},
}
const location = {
  get href() {
    return href
  },
  get search() {
    return new URL(href).search
  },
  get pathname() {
    return new URL(href).pathname
  },
  get origin() {
    return new URL(href).origin
  },
  reload() {
    reloaded = true
  },
}
// The page's `<base href>` against its URL, as `fetch` resolves an action's.
Object.defineProperty(document, "baseURI", {
  configurable: true,
  get: () => new URL(document.querySelector("base")?.getAttribute("href") ?? "", href).href,
})

const storage = () => {
  const m = new Map()
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
  }
}

Object.assign(g, {
  window: g,
  self: g,
  document,
  history,
  location,
  sessionStorage: storage(),
  localStorage: storage(),
  navigator: { userAgent: "fh-oracle" },
  isSecureContext: false,
  scrollY: 0,
  scrollTo: () => {},
  matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  CSS: { escape: (s) => String(s).replace(/([^\w-])/g, "\\$1") },
})
g.MathMLElement ??= class {}
g.HTMLSelectElement ??= class {}
g.HTMLTextAreaElement ??= class {}
// The window hears only what `history.back` dispatches: pagehide and the
// gesture guards listen and never hear anything.
const windowListeners = []
g.addEventListener = (type, f) => {
  windowListeners.push({ type, f })
}
g.removeEventListener = (type, f) => {
  const i = windowListeners.findIndex((l) => l.type === type && l.f === f)
  if (i >= 0) windowListeners.splice(i, 1)
}
g.dispatchEvent = (e) => {
  for (const { type, f } of windowListeners.slice())
    if (type === e.type) typeof f === "function" ? f.call(g, e) : f.handleEvent(e)
  return true
}

// fetch: the stream (`@get` of `sse/dashboard/…`) stays open until the host
// ends it, and is never answered — its frames arrive through `apply`. Any
// other request waits in `pending` for the host's answer.
class Headers {
  constructor(h = {}) {
    this.h = {}
    for (const k in h) this.h[k.toLowerCase()] = h[k]
  }
  get(k) {
    return this.h[k.toLowerCase()] ?? null
  }
  has(k) {
    return k.toLowerCase() in this.h
  }
}
const response = (status, text, onRead) => {
  const bytes = new TextEncoder().encode(text)
  let sent = false
  return {
    ok: status >= 200 && status < 300,
    status,
    redirected: false,
    headers: new Headers({ "content-type": "text/event-stream" }),
    body: {
      getReader: () => ({
        read: onRead ?? (async () => (sent ? { done: true, value: undefined } : ((sent = true), { done: false, value: bytes }))),
        releaseLock() {},
        cancel: async () => {},
      }),
    },
    text: async () => text,
  }
}
const pathOf = (u) => {
  const url = new URL(u, href)
  return url.pathname + url.search
}
const streams = []
const pending = []
let nextRequest = 0
g.fetch = (input, init = {}) => {
  const method = init.method ?? "GET"
  const path = pathOf(String(input))
  if (method === "GET" && path.includes("/sse/dashboard/"))
    return new Promise((resolveFetch) => {
      let end
      const ended = new Promise((r) => (end = r))
      streams.push({ path, taken: false, end })
      resolveFetch(response(200, "", () => ended.then(() => ({ done: true, value: undefined }))))
    })
  return new Promise((resolveFetch, rejectFetch) => {
    pending.push({ id: ++nextRequest, method, path, body: init.body == null ? "" : String(init.body), resolveFetch, rejectFetch })
  })
}

g.__oracle = {
  step,
  problems,
  reloaded: () => reloaded,
  entries: () => entries.map(({ state, href }) => ({ pushed: state?.fhPushed === true, query: new URL(href).search })),
  streams,
  pending,
  response,
  setQuiet: (q) => {
    quiet = q
  },
}
