import { resolve } from "node:path"
import { defineConfig } from "vite"

// Three bundles, and they are NOT the same shape — which is why this is three
// `vite build --mode …` runs (package.json's `build`) rather than one
// multi-entry build. Vite's library mode picks one format per build, and these
// need two:
//
//   shell    IIFE, inlined into every dashboard page by `Server.page`. Must be
//            a classic script: it defines helpers that a mid-body <script> and
//            Datastar's first effect both call, so a deferred ES module is too
//            late.
//   app      ES module, <script type="module"> from the editor's index.html.
//            CodeMirror + lsp-client are bundled in, which is the whole point:
//            one file, one @codemirror/state instance, no CDN, no import map.
//   overlay  IIFE, a classic <script src> injected into a dashboard under
//            ?edit=1.
//
// The output tree mirrors the CLASSPATH, because sbt's NpmPlugin copies it
// straight into managed resources: `fh/shell.js` is read by `Server`,
// `editor/*.js` are served by `EditorRoutes` next to the hand-written
// index.html/app.css/overlay.css that stay in src/main/resources.
const targets = {
  shell: { entry: "src/js/shell.ts", dir: "fh", format: "iife" },
  app: { entry: "src/js/editor/app.js", dir: "editor", format: "es" },
  overlay: { entry: "src/js/editor/overlay.js", dir: "editor", format: "iife" },
} as const

type Mode = keyof typeof targets

export default defineConfig(({ mode }) => {
  const target = targets[mode as Mode]
  if (!target) {
    throw new Error(
      `unknown build mode "${mode}" — expected one of ${Object.keys(targets).join(", ")}`,
    )
  }
  return {
    build: {
      outDir: `target/frontend/${target.dir}`,
      // sbt deletes target/frontend wholesale before calling this, so nothing
      // stale can survive; leaving it false here is what lets `app` and
      // `overlay` share one directory without the second wiping the first.
      emptyOutDir: false,
      lib: {
        entry: resolve(import.meta.dirname, target.entry),
        formats: [target.format],
        // IIFE needs a global name even when nothing reads it — these bundles
        // export nothing and work through side effects (`window.fh*`, a
        // document-level listener).
        name: "fh",
        // The function form returns the WHOLE filename; the string form would
        // append the format (`shell.iife.js`).
        fileName: () => `${mode}.js`,
      },
    },
  }
})
