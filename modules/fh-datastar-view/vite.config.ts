import { resolve } from "node:path"
import { defineConfig, type Plugin } from "vite"

// Entries that are loaded as CLASSIC scripts — `shell` inlined into every
// dashboard page, `overlay` as a plain <script src>, `sw` as the service
// worker. None may contain an import or an export or the browser refuses the
// whole file, and for the shell that is silent and total: every page loses the
// tab selection, the session handoff and the scroll position.
const classic = new Set(["shell", "sw", "overlay"])

/**
 * Fail the BUILD if a classic entry is not self-contained.
 *
 * Rollup never duplicates code: the moment two entries import one module it
 * hoists that module into a shared chunk which both then `import`. There is no
 * option to opt out per entry — self-contained output means a separate build
 * (vitejs/vite#12203) — so the property cannot be configured, only checked.
 *
 * Checked here, off rollup's own chunk metadata, rather than by pattern-
 * matching the emitted text: `imports`/`exports` is what rollup actually wrote,
 * and this fails at `npm run build` naming the entry, instead of at whatever
 * reads the artifact later.
 */
function assertSelfContained(): Plugin {
  return {
    name: "fh-assert-self-contained",
    generateBundle(_options, bundle) {
      for (const chunk of Object.values(bundle)) {
        if (chunk.type !== "chunk" || !chunk.isEntry) continue
        if (!classic.has(chunk.name)) continue
        const bad = [
          ...chunk.imports.map((i) => `imports "${i}"`),
          ...chunk.dynamicImports.map((i) => `dynamically imports "${i}"`),
          ...chunk.exports.map((e) => `exports "${e}"`),
        ]
        if (bad.length > 0) {
          this.error(
            `"${chunk.name}" is loaded as a classic script but ${bad.join(", ")}. ` +
              `Rollup split a shared module into a chunk, which makes this file a real ES module ` +
              `and the browser will refuse to run any of it. Keep the classic entries (${[...classic].join(", ")}) ` +
              `importing nothing, or give this one its own build.`,
          )
        }
      }
    },
  }
}

// One build, four entries. The output tree mirrors the CLASSPATH, because
// sbt's NpmPlugin copies it straight into managed resources: `fh/shell.js` is
// read and inlined by `Server`, `editor/*.js` are served by `EditorRoutes` next
// to the hand-written index.html/app.css/overlay.css that stay in
// src/main/resources.
//
//   shell    inlined into every dashboard page by `Server.page`. Must work as a
//            CLASSIC script: it defines helpers that a mid-body <script> and
//            Datastar's first effect both call, so a deferred module is too
//            late.
//   app      <script type="module"> from the editor's index.html. CodeMirror +
//            lsp-client are bundled in, which is the point: one file, one
//            @codemirror/state instance, no CDN, no import map.
//   overlay  a classic <script src> injected into a dashboard under ?edit=1.
//   sw       the service worker, served at the fixed root URL `sw.js` — see the
//            `entryFileNames` override below. CLASSIC script, import-free.
//
// NOT `build.lib`, and that is the whole trick. Lib mode looks like the right
// tool for "bundle these entries" and costs two things here:
//
//   - it refuses multiple entries whenever a format is `iife`/`umd`
//     ("Multiple entry points are not supported when output formats include
//     \"umd\" or \"iife\""), which is what pushed an earlier version of this
//     file into three separate `vite build --mode …` runs;
//   - `isEsLibBuild = config.build.lib && format === "es"` HARD-FORCES
//     `minifyWhitespace: false`, ignoring an explicit override, to keep the
//     pure annotations a downstream bundler would tree-shake on. We are not a
//     library and nothing downstream re-bundles this, so that trade is pure
//     cost: it shipped `app.js` at 654 kB where this emits 424 kB.
//
// `rollupOptions.input` has neither problem: one build, three entries, real
// minification.
//
// The two classic scripts are safe as `es` output because they import nothing,
// so rollup emits no import/export statement in them — enforced by the
// `assertSelfContained` plugin above, which fails the build rather than letting
// it ship.
export default defineConfig({
  plugins: [assertSelfContained()],
  build: {
    outDir: "target/frontend",
    emptyOutDir: true,
    // Hashed filenames + a manifest, so nothing has to hardcode an output
    // name and the bundles can be served immutable. `FrontendAssets` reads it.
    manifest: "web/manifest.json",
    // No preload polyfill: these are three independent entries, not an app
    // shell, and one of them is inlined as a classic script.
    modulePreload: false,
    rollupOptions: {
      input: {
        shell: resolve(import.meta.dirname, "src/js/shell.ts"),
        app: resolve(import.meta.dirname, "src/js/editor/app.js"),
        overlay: resolve(import.meta.dirname, "src/js/editor/overlay.js"),
        sw: resolve(import.meta.dirname, "src/js/sw.ts"),
      },
      output: {
        // The service worker is the ONE entry with a FIXED, un-hashed name at
        // the output ROOT: the browser fetches SW updates at the URL it
        // registered, so a content-hashed `sw.js` would strand every install
        // on its first version. It must not live under `web/` either — that
        // tree is served `immutable`, which would fight the `no-cache` an SW
        // needs — and the root position gives it scope `/` by default.
        // Everything else keeps the content-hashed `web/` name, which is what
        // makes serving those `immutable` honest.
        entryFileNames: (chunk) =>
          chunk.name === "sw" ? "sw.js" : "web/[name]-[hash].js",
        // A chunk appearing here at all means the invariant above broke.
        chunkFileNames: "web/[name]-[hash].js",
        assetFileNames: "web/[name]-[hash][extname]",
      },
    },
  },
})
