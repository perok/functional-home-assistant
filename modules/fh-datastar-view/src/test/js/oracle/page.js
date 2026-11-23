// The model-based oracle's page (`fh.view.testkit.FakePage`): the page's own
// shell and Datastar, as the server serves them, on the document `setup.js`
// builds. Each call leaves the page as it is now; the host settles it with
// `step` until that says nothing is left.
import "./setup.js"
import "../../../js/shell.ts"
import "../../../js/datastar.ts"
import { getPath, root } from "../../../js/vendor/datastar/datastar.js"

const o = globalThis.__oracle

/** A frame as the stream's reader hands it to Datastar: each data line's first
 * word names an argument, repeated lines joined. A remove whose target is
 * gone is not a miss: a set's member removal relies on it.
 */
const apply = (type, data) => {
  const args = {}
  for (const line of data.split("\n")) {
    const i = line.indexOf(" ")
    const key = i < 0 ? line : line.slice(0, i)
    const value = i < 0 ? "" : line.slice(i + 1)
    args[key] = key in args ? args[key] + "\n" + value : value
  }
  o.setQuiet(args.mode === "remove")
  try {
    document.dispatchEvent(
      new CustomEvent("datastar-fetch", { detail: { type, el: document.body, argsRaw: args } }),
    )
  } catch (e) {
    o.problems.push("threw: " + (e && e.message))
  } finally {
    o.setQuiet(false)
  }
}

globalThis.__page = {
  step: () => o.step(),
  html: () => document.documentElement.outerHTML,
  url: () => location.href,
  signal: (path) => {
    const v = getPath(path)
    return v === undefined ? null : JSON.stringify(v)
  },
  signals: () => JSON.stringify(root),
  problems: () => JSON.stringify(o.problems),
  reloaded: () => o.reloaded(),
  apply,
  /** Clicks the first element whose click handler posts to `path`. */
  press: (path) => {
    const needle = "@post('" + path + "'"
    for (const el of document.querySelectorAll("*"))
      for (const { name, value } of el.attributes)
        if (name.startsWith("data-on:click") && value.includes(needle)) {
          el.dispatchEvent(new Event("click", { bubbles: true }))
          return true
        }
    return false
  },
  /** The requests waiting for an answer, as JSON. */
  requests: () =>
    JSON.stringify(o.pending.map(({ id, method, path, body }) => ({ id, method, path, body }))),
  answer: (id, status, text) => {
    const i = o.pending.findIndex((r) => r.id === id)
    if (i < 0) throw new Error("no request " + id)
    const [r] = o.pending.splice(i, 1)
    r.resolveFetch(o.response(status, text))
  },
  /** The stream request nobody has taken, or null. */
  takeStream: () => {
    const s = o.streams.find((s) => !s.taken)
    if (!s) return null
    s.taken = true
    return s.path
  },
  /** Every open stream ends, as a dropped connection does: Datastar retries. */
  endStreams: () => {
    for (const s of o.streams) if (s.taken && s.end) (s.end(), (s.end = null))
  },
}
