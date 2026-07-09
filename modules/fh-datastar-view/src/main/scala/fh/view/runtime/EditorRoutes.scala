package fh.view.runtime

import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2

/** The dashboard **editor** surface: a CodeMirror 6 page that edits the Pkl
  * dashboard sources on disk, with live preview and Pkl language support
  * (highlighting locally, completion/hover/diagnostics from the real pkl-lsp).
  *
  *   - `GET  /edit` the editor page (file tree + CodeMirror + preview iframe).
  *   - `GET  /edit/files` the editable source list (top-level `*.pkl` entries +
  *     the `lib` sources), each with its absolute on-disk path (LSP document URI).
  *   - `GET  /edit/file/<rel>` read a source; `PUT` write it. A write lands on
  *     disk and the existing `ServerApp.watchSources` reload repaints every open
  *     preview — no coupling here.
  *   - `GET  /lsp/pkl` the language-server WebSocket ([[LspBridge]]).
  *
  * Editing is **deliberately ungated** for now, safe only because the server
  * binds loopback by default (see the plan's "Deferred: feature gate + security"
  * section). The write path is still clamped: only `<name>.pkl` and
  * `lib/<name>.pkl` under the dashboards dir, each segment matching
  * [[AssetCache.SafeName]] (which rejects `..` and slashes) — no traversal.
  *
  * `dump.pkl` is excluded everywhere: it's the generated, gitignored dump, not
  * an author source.
  */
final class EditorRoutes(
    dashboardsDir: os.Path,
    pklLspJar: Option[os.Path],
    defaultSlug: String
) {

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "edit" =>
        Ok(EditorRoutes.page(defaultSlug, Server.ingressPrefixOf(req)))
          .map(_.withContentType(`Content-Type`(MediaType.text.html)))

      case GET -> Root / "edit" / "files" =>
        Ok(listFiles).map(
          _.withContentType(`Content-Type`(MediaType.application.json))
        )

      case GET -> "edit" /: "file" /: rest =>
        resolveEditable(rest) match {
          case None => NotFound()
          case Some(p) =>
            IO.blocking(Option.when(os.exists(p) && os.isFile(p))(os.read(p)))
              .flatMap {
                case None => NotFound()
                case Some(text) =>
                  Ok(text).map(
                    _.withContentType(`Content-Type`(MediaType.text.plain))
                  )
              }
        }

      case req @ PUT -> "edit" /: "file" /: rest =>
        resolveEditable(rest) match {
          case None => Forbidden("""{"error":"not an editable dashboard source"}""")
          case Some(p) =>
            req.bodyText.compile.string.flatMap { body =>
              IO.blocking(os.write.over(p, body)) *> NoContent()
            }
        }

      case GET -> Root / "lsp" / "pkl" =>
        pklLspJar match {
          case Some(jar) => LspBridge.wsResponse(wsb, jar)
          case None =>
            ServiceUnavailable("""{"error":"pkl-lsp jar not available"}""")
        }
    }

  /** JSON list of editable sources: `{ name, path, slug? }`. `name` is the
    * dashboards-relative path (the editor's identity + `GET/PUT` key), `path`
    * the absolute file (the LSP `file://` document URI); entries carry their
    * `slug` (filename sans `.pkl`) so the page can preview them.
    */
  private def listFiles: String = {
    def entryJson(rel: String, p: os.Path, slug: Option[String]): Json =
      Json.obj(
        "name" -> Json.fromString(rel),
        "path" -> Json.fromString(p.toString),
        "slug" -> slug.fold(Json.Null)(Json.fromString)
      )

    val top = os
      .list(dashboardsDir)
      .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
      .map(p => entryJson(p.last, p, Some(p.last.stripSuffix(".pkl"))))
      .toList
      .sortBy(_.hcursor.get[String]("name").toOption)

    val libDir = dashboardsDir / "lib"
    val lib =
      if (os.exists(libDir))
        os.list(libDir)
          .filter(p => os.isFile(p) && p.last.endsWith(".pkl"))
          .filter(_.last != "dump.pkl")
          .map(p => entryJson(s"lib/${p.last}", p, None))
          .toList
          .sortBy(_.hcursor.get[String]("name").toOption)
      else Nil

    Json.arr((top ++ lib)*).noSpaces
  }

  /** Resolve a request path (`<name>.pkl` or `lib/<name>.pkl`) to an on-disk
    * source under the dashboards dir, or `None` if it isn't a permitted
    * editable file. Every segment must match [[AssetCache.SafeName]] (rejecting
    * `..`, dot-files and slashes), the leaf must be `*.pkl` and not the
    * generated `dump.pkl`, and only depth 1 (entries) or `lib/` depth 2 is
    * allowed.
    */
  private def resolveEditable(rest: Uri.Path): Option[os.Path] = {
    val segs = rest.segments.map(_.decoded()).toList
    val ok =
      segs.nonEmpty &&
        segs.forall(AssetCache.SafeName.matches) &&
        segs.last.endsWith(".pkl") &&
        segs.last != "dump.pkl"
    if (!ok) None
    else
      segs match {
        case name :: Nil          => Some(dashboardsDir / name)
        case "lib" :: name :: Nil => Some(dashboardsDir / "lib" / name)
        case _                    => None
      }
  }
}

object EditorRoutes {

  /** The editor page. Self-contained: a bare HTML shell whose module script
    * loads CodeMirror 6 + `@codemirror/lsp-client` from a CDN via an import map
    * (the `*` prefix externalizes each package's own imports back to the map, so
    * every `@codemirror` package resolves to a single instance — CM's facets
    * break under duplicates). Dynamic values are injected as a JSON `<script>`;
    * the heavy JS is a non-interpolated resource string.
    *
    * All same-origin URLs are relative and resolve against `<base href>` (`/`
    * direct, the ingress prefix behind the HA proxy). The WebSocket URL is built
    * from `location` in JS (the `WebSocket` ctor ignores `<base href>`).
    */
  def page(defaultSlug: String, ingressPrefix: Option[String]): String = {
    val basePath = ingressPrefix.fold("/")(p => s"$p/")
    val config = Json
      .obj(
        "defaultSlug" -> Json.fromString(defaultSlug),
        "basePath" -> Json.fromString(basePath)
      )
      .noSpaces
    shell
      .replace("__BASE_HREF__", basePath)
      .replace("__CONFIG__", config)
  }

  // The page shell. `<base href>` is filled per request; `__CONFIG__` is a JSON
  // blob read by the app script. The import map + app script are static. A
  // `def` (not a `val`): it splices `ImportMap`/`Css`/`AppJs`, which are object
  // vals initialized AFTER this in declaration order — a `val` here would
  // capture them as null.
  private def shell: String =
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <base href="__BASE_HREF__">
       |  <title>Dashboard editor</title>
       |  <script type="application/json" id="fh-editor-config">__CONFIG__</script>
       |  <script type="importmap">$ImportMap</script>
       |  <style>$Css</style>
       |</head>
       |<body>
       |  <div id="fh-editor">
       |    <aside id="fh-files"><div class="fh-hd">Dashboards</div><ul id="fh-file-list"></ul></aside>
       |    <main id="fh-main">
       |      <div id="fh-editor-host"></div>
       |      <div id="fh-status"><span id="fh-status-file"></span><span id="fh-status-msg"></span></div>
       |    </main>
       |    <section id="fh-preview">
       |      <div class="fh-hd">Preview: <select id="fh-preview-slug"></select></div>
       |      <iframe id="fh-preview-frame" title="dashboard preview"></iframe>
       |    </section>
       |  </div>
       |  <script type="module">$AppJs</script>
       |</body>
       |</html>
       |""".stripMargin

  private val ImportMap: String =
    """{
      |  "imports": {
      |    "@codemirror/state": "https://esm.sh/*@codemirror/state@6",
      |    "@codemirror/view": "https://esm.sh/*@codemirror/view@6",
      |    "@codemirror/language": "https://esm.sh/*@codemirror/language@6",
      |    "@codemirror/commands": "https://esm.sh/*@codemirror/commands@6",
      |    "@codemirror/autocomplete": "https://esm.sh/*@codemirror/autocomplete@6",
      |    "@codemirror/lint": "https://esm.sh/*@codemirror/lint@6",
      |    "@codemirror/lsp-client": "https://esm.sh/*@codemirror/lsp-client@6",
      |    "@lezer/common": "https://esm.sh/*@lezer/common@1",
      |    "@lezer/highlight": "https://esm.sh/*@lezer/highlight@1",
      |    "@lezer/lr": "https://esm.sh/*@lezer/lr@1",
      |    "style-mod": "https://esm.sh/*style-mod@4",
      |    "w3c-keyname": "https://esm.sh/*w3c-keyname@2",
      |    "crelt": "https://esm.sh/*crelt@1",
      |    "marked": "https://esm.sh/*marked@15",
      |    "vscode-languageserver-protocol": "https://esm.sh/*vscode-languageserver-protocol@3",
      |    "vscode-jsonrpc": "https://esm.sh/*vscode-jsonrpc@8",
      |    "vscode-languageserver-types": "https://esm.sh/*vscode-languageserver-types@3"
      |  }
      |}""".stripMargin

  private val Css: String =
    """*{box-sizing:border-box}
      |html,body{margin:0;height:100%;font-family:system-ui,sans-serif;background:#1e1e1e;color:#ddd}
      |#fh-editor{display:grid;grid-template-columns:200px 1fr 40%;height:100vh}
      |#fh-files{border-right:1px solid #333;overflow:auto}
      |.fh-hd{padding:8px 10px;font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:#888;border-bottom:1px solid #333}
      |#fh-file-list{list-style:none;margin:0;padding:0}
      |#fh-file-list li{padding:6px 10px;cursor:pointer;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
      |#fh-file-list li:hover{background:#2a2d2e}
      |#fh-file-list li.active{background:#37373d;color:#fff}
      |#fh-file-list li.lib{color:#9aa;padding-left:20px}
      |#fh-main{display:flex;flex-direction:column;min-width:0}
      |#fh-editor-host{flex:1;min-height:0;overflow:hidden}
      |#fh-editor-host .cm-editor{height:100%}
      |#fh-status{display:flex;justify-content:space-between;padding:4px 10px;font-size:12px;background:#007acc;color:#fff}
      |#fh-preview{border-left:1px solid #333;display:flex;flex-direction:column;min-width:0}
      |#fh-preview-frame{flex:1;border:0;background:#fff}
      |#fh-preview-slug{background:#333;color:#ddd;border:1px solid #555;border-radius:3px}
      |""".stripMargin

  // The application script. Non-interpolated (raw `${}`/`$` would fight the
  // Scala interpolator) — reads its config from the JSON script tag. Registers a
  // hand-written Pkl StreamLanguage (base highlighting), wires the pkl-lsp
  // WebSocket through @codemirror/lsp-client, saves on Ctrl+S, and reloads the
  // preview iframe on save (belt-and-suspenders — the server watcher also
  // repaints it live).
  private val AppJs: String =
    """
import {EditorState, Compartment} from "@codemirror/state"
import {EditorView, keymap, lineNumbers, highlightActiveLine} from "@codemirror/view"
import {defaultKeymap, history, historyKeymap, indentWithTab} from "@codemirror/commands"
import {StreamLanguage, LanguageSupport, syntaxHighlighting, defaultHighlightStyle, indentOnInput, bracketMatching} from "@codemirror/language"
import {closeBrackets} from "@codemirror/autocomplete"
import {LSPClient, languageServerExtensions, languageServerSupport} from "@codemirror/lsp-client"

const cfg = JSON.parse(document.getElementById("fh-editor-config").textContent)
const Q3 = String.fromCharCode(34, 34, 34) // literal triple-quote, kept out of the Scala string

// --- Pkl base highlighting: a small StreamLanguage tokenizer -----------------
const KEYWORDS = new Set(("abstract amends as class const else extends external false fixed for function " +
  "hidden if import in is let local module new nothing null open out outer read super this throw trace " +
  "true typealias unknown when").split(" "))
const pklStream = StreamLanguage.define({
  name: "pkl",
  startState() { return {inBlock: false} },
  token(stream, state) {
    if (state.inBlock) { // inside /* */
      if (stream.skipTo("*/")) { stream.match("*/"); state.inBlock = false } else stream.skipToEnd()
      return "comment"
    }
    if (stream.eatSpace()) return null
    // comments
    if (stream.match("//")) { stream.skipToEnd(); return "comment" }
    if (stream.match("/*")) { state.inBlock = true; return "comment" }
    // strings: triple-quoted and single-line (with #"..."# not handled, rare)
    if (stream.match(Q3)) { while (!stream.eol()) { if (stream.match(Q3)) break; stream.next() } return "string" }
    if (stream.peek() === '"') { stream.next(); while (!stream.eol()) { const c = stream.next(); if (c === '\\') stream.next(); else if (c === '"') break } return "string" }
    // annotations @Foo
    if (stream.match(/^@[A-Za-z_][\w.]*/)) return "meta"
    // numbers
    if (stream.match(/^0x[0-9a-fA-F_]+/) || stream.match(/^\d[\d_]*(\.\d[\d_]*)?([eE][+-]?\d+)?/)) return "number"
    // identifiers / keywords
    if (stream.match(/^[A-Za-z_$][\w$]*/)) {
      const w = stream.current()
      if (KEYWORDS.has(w)) return "keyword"
      if (/^[A-Z]/.test(w)) return "typeName"
      return "variableName"
    }
    stream.next()
    return null
  },
  languageData: { commentTokens: { line: "//", block: {open: "/*", close: "*/"} } }
})
const pkl = () => new LanguageSupport(pklStream)

// --- editor plumbing ---------------------------------------------------------
const host = document.getElementById("fh-editor-host")
const statusFile = document.getElementById("fh-status-file")
const statusMsg = document.getElementById("fh-status-msg")
const setMsg = (m) => { statusMsg.textContent = m }

const baseExt = [
  lineNumbers(), history(), highlightActiveLine(),
  indentOnInput(), bracketMatching(), closeBrackets(),
  syntaxHighlighting(defaultHighlightStyle),
  keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]),
  EditorView.theme({ "&": { fontSize: "13px" } }, { dark: true })
]

// One LSP client for the whole session; each opened file gets client.plugin(uri).
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
    unsubscribe(h) { handlers.delete(h) }
  }
}

let lspClient = null
try {
  lspClient = new LSPClient({ extensions: languageServerExtensions() }).connect(makeTransport())
} catch (err) { setMsg("lsp unavailable: " + err.message) }

const lspComp = new Compartment()
let view = new EditorView({ parent: host, state: EditorState.create({ doc: "", extensions: [baseExt, pkl(), lspComp.of([])] }) })

let current = null // {name, path, slug}

async function openFile(f) {
  const res = await fetch(cfg.basePath + "edit/file/" + f.name)
  const text = res.ok ? await res.text() : "// failed to load " + f.name
  current = f
  statusFile.textContent = f.name
  setMsg("")
  const uri = "file://" + f.path
  const lspExt = lspClient ? languageServerSupport(lspClient, uri) : []
  view.setState(EditorState.create({ doc: text, extensions: [baseExt, pkl(), lspComp.of(lspExt)] }))
  document.querySelectorAll("#fh-file-list li").forEach((li) => li.classList.toggle("active", li.dataset.name === f.name))
  view.focus()
}

async function save() {
  if (!current) return
  setMsg("saving…")
  const res = await fetch(cfg.basePath + "edit/file/" + current.name, { method: "PUT", body: view.state.doc.toString() })
  if (res.ok) { setMsg("saved ✓"); reloadPreview() } else setMsg("save failed")
}

window.addEventListener("keydown", (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === "s") { e.preventDefault(); save() }
})

// --- preview -----------------------------------------------------------------
const previewSel = document.getElementById("fh-preview-slug")
const previewFrame = document.getElementById("fh-preview-frame")
function reloadPreview() {
  const slug = previewSel.value || cfg.defaultSlug
  // ?edit=1 turns on the in-dashboard Focus/Debug overlay (server-injected).
  previewFrame.src = cfg.basePath + "d/" + slug + "?edit=1&t=" + Date.now()
}
previewSel.addEventListener("change", reloadPreview)

// Focus messages from the preview overlay. For now we surface the node id in
// the status bar; jumping to the exact source line is the pkl:syntax follow-up.
window.addEventListener("message", (e) => {
  const m = e.data
  if (m && m.type === "fh-focus") setMsg("node " + m.nodeId + " (preview " + m.slug + ")")
})

// --- file list ---------------------------------------------------------------
async function loadFiles() {
  const files = await (await fetch(cfg.basePath + "edit/files")).json()
  const list = document.getElementById("fh-file-list")
  list.innerHTML = ""
  previewSel.innerHTML = ""
  for (const f of files) {
    const li = document.createElement("li")
    li.textContent = f.name
    li.dataset.name = f.name
    if (f.name.startsWith("lib/")) li.classList.add("lib")
    li.addEventListener("click", () => openFile(f))
    list.appendChild(li)
    if (f.slug) {
      const opt = document.createElement("option")
      opt.value = f.slug; opt.textContent = f.slug
      previewSel.appendChild(opt)
    }
  }
  previewSel.value = cfg.defaultSlug
  reloadPreview()
  // Open the file named by ?file=, else the first entry.
  const wanted = new URLSearchParams(location.search).get("file")
  const first = files.find((f) => f.name === wanted) || files.find((f) => f.slug) || files[0]
  if (first) await openFile(first)
  // Reveal ?line= if provided.
  const line = parseInt(new URLSearchParams(location.search).get("line") || "", 10)
  if (line > 0 && view.state.doc.lines >= line) {
    const pos = view.state.doc.line(line).from
    view.dispatch({ selection: { anchor: pos }, scrollIntoView: true })
  }
}
loadFiles()
"""
}
