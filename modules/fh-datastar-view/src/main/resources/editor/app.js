// The dashboard editor front-end (CodeMirror 6 + @codemirror/lsp-client).
// Served as a real file from resources/editor/ — NOT embedded in Scala — so it
// can be linted, read normally, and edited live (refresh the browser; no sbt
// rebuild). Dependencies resolve via the import map in index.html.

// All CodeMirror + lsp-client symbols come from the pre-bundled vendor.js
// (built by editor-src/ with esbuild) — one file, no import map, no CDN, one
// @codemirror/state instance. Rebuild it only when deps change.
import {
  EditorState, Compartment,
  EditorView, keymap, lineNumbers, highlightActiveLine,
  defaultKeymap, history, historyKeymap, indentWithTab,
  syntaxHighlighting, defaultHighlightStyle, indentOnInput, bracketMatching,
  closeBrackets,
  LSPClient, languageServerExtensions, languageServerSupport,
  pkl,
} from "./vendor.js"

const cfg = JSON.parse(document.getElementById("fh-editor-config").textContent)

const statusFile = document.getElementById("fh-status-file")
const statusMsg = document.getElementById("fh-status-msg")
const setMsg = (m) => { statusMsg.textContent = m }

// Surface any runtime error on-screen (not just the console).
addEventListener("error", (e) => setMsg("JS error: " + (e.message || (e.error && e.error.message) || e.error)))
addEventListener("unhandledrejection", (e) => setMsg("error: " + ((e.reason && e.reason.message) || e.reason)))

// Pkl syntax highlighting is the Lezer parser bundled into vendor.js (built
// from editor-src/pkl.grammar, translated from tree-sitter-pkl); `pkl()`
// returns its CodeMirror LanguageSupport.

// --- editor ----------------------------------------------------------------
const host = document.getElementById("fh-editor-host")

const baseExt = [
  lineNumbers(), history(), highlightActiveLine(),
  indentOnInput(), bracketMatching(), closeBrackets(),
  syntaxHighlighting(defaultHighlightStyle),
  keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]),
  EditorView.theme({ "&": { fontSize: "13px" } }, { dark: true }),
]

// One LSP client per session over the /lsp/pkl WebSocket. The WebSocket ctor
// ignores <base href>, so build the URL from location + the configured base.
const wsUrl = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + cfg.basePath + "lsp/pkl"
function makeTransport() {
  let socket, handlers = new Set(), queue = []
  function connect() {
    socket = new WebSocket(wsUrl)
    socket.onmessage = (e) => handlers.forEach((h) => h(e.data))
    socket.onopen = () => { queue.forEach((m) => socket.send(m)); queue = [] }
    socket.onclose = () => setMsg("lsp disconnected")
    socket.onerror = () => setMsg("lsp error")
  }
  connect()
  return {
    send(message) { if (socket.readyState === WebSocket.OPEN) socket.send(message); else queue.push(message) },
    subscribe(h) { handlers.add(h) },
    unsubscribe(h) { handlers.delete(h) },
  }
}

let lspClient = null
try {
  lspClient = new LSPClient({ extensions: languageServerExtensions() }).connect(makeTransport())
} catch (err) { setMsg("lsp unavailable: " + err.message) }

// Guarded: a broken editor must never blank the file tree / preview.
const lspComp = new Compartment()
let view = null
try {
  view = new EditorView({ parent: host, state: EditorState.create({ doc: "", extensions: [baseExt, pkl(), lspComp.of([])] }) })
} catch (err) { setMsg("editor init failed: " + err.message); console.error(err) }

let current = null

async function openFile(f) {
  current = f
  statusFile.textContent = f.name
  document.querySelectorAll("#fh-file-list li").forEach((li) => li.classList.toggle("active", li.dataset.name === f.name))
  const res = await fetch(cfg.basePath + "edit/file/" + f.name)
  const text = res.ok ? await res.text() : "// failed to load " + f.name
  if (!view) { setMsg("editor unavailable — file loaded but not editable"); return }
  setMsg("")
  const uri = "file://" + f.path
  const lspExt = lspClient ? languageServerSupport(lspClient, uri) : []
  view.setState(EditorState.create({ doc: text, extensions: [baseExt, pkl(), lspComp.of(lspExt)] }))
  view.focus()
}

async function save() {
  if (!current || !view) return
  setMsg("saving…")
  const res = await fetch(cfg.basePath + "edit/file/" + current.name, { method: "PUT", body: view.state.doc.toString() })
  setMsg(res.ok ? "saved ✓" : "save failed")
  // The preview iframe repaints itself live via its SSE stream on reload.
}

addEventListener("keydown", (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === "s") { e.preventDefault(); save() }
})

// --- preview ---------------------------------------------------------------
const previewSel = document.getElementById("fh-preview-slug")
const previewFrame = document.getElementById("fh-preview-frame")
function loadPreview() {
  const slug = previewSel.value || cfg.defaultSlug
  previewFrame.src = cfg.basePath + "d/" + slug + "?edit=1"
}
previewSel.addEventListener("change", loadPreview)

// Focus messages from the preview overlay (source-line jump is the pkl:syntax follow-up).
addEventListener("message", (e) => {
  const m = e.data
  if (m && m.type === "fh-focus") setMsg("node " + m.nodeId + " (preview " + m.slug + ")")
})

// --- file list -------------------------------------------------------------
async function loadFiles() {
  let files
  try {
    const r = await fetch(cfg.basePath + "edit/files")
    if (!r.ok) { setMsg("GET edit/files -> " + r.status); return }
    files = await r.json()
  } catch (e) { setMsg("could not load file list: " + e.message); return }

  const list = document.getElementById("fh-file-list")
  list.innerHTML = ""
  previewSel.innerHTML = ""
  if (!files || !files.length) { setMsg("no dashboards returned by edit/files"); return }

  for (const f of files) {
    const li = document.createElement("li")
    li.textContent = f.name
    li.dataset.name = f.name
    if (f.name.startsWith("lib/")) li.classList.add("lib")
    li.addEventListener("click", () => openFile(f))
    list.appendChild(li)
    if (f.slug) {
      const opt = document.createElement("option")
      opt.value = f.slug
      opt.textContent = f.slug
      previewSel.appendChild(opt)
    }
  }
  previewSel.value = cfg.defaultSlug
  loadPreview()

  const params = new URLSearchParams(location.search)
  const wanted = params.get("file")
  const first = files.find((f) => f.name === wanted) || files.find((f) => f.slug) || files[0]
  if (first) await openFile(first)

  const line = parseInt(params.get("line") || "", 10)
  if (view && line > 0 && view.state.doc.lines >= line) {
    const pos = view.state.doc.line(line).from
    view.dispatch({ selection: { anchor: pos }, scrollIntoView: true })
  }
}
loadFiles()
