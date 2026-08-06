import { resolve } from "node:path"
import { defineConfig } from "vite"

// One build, three entries. The output tree mirrors the CLASSPATH, because
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
// so rollup emits no import/export statement in them. That is an INVARIANT, not
// a coincidence — the moment two entries share a module, rollup splits it into
// a chunk and both entries gain a real `import`, at which point `shell.js`
// inlined into <head> throws and every page silently loses the tab selection,
// the session handoff and the scroll position. `EditorSuite` asserts the
// absence, so that lands as a test failure rather than a blank dashboard.
export default defineConfig({
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
      },
      output: {
        entryFileNames: "web/[name]-[hash].js",
        // A chunk appearing here at all means the invariant above broke.
        chunkFileNames: "web/[name]-[hash].js",
        assetFileNames: "web/[name]-[hash][extname]",
      },
    },
  },
})
