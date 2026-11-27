import { resolve } from "node:path"
import { defineConfig } from "vite"

// The model-based oracle's page (`src/test/js/oracle`), a TEST resource: the
// served shell and Datastar on a linkedom document, as one script a GraalJS
// context evaluates. Unminified, so a stack trace names the source.
export default defineConfig({
  build: {
    target: "es2022",
    outDir: "target/frontend-test",
    emptyOutDir: true,
    minify: false,
    lib: {
      entry: resolve(import.meta.dirname, "src/test/js/oracle/page.js"),
      formats: ["iife"],
      name: "fhOraclePage",
      fileName: () => "fh/oracle-page.js",
    },
  },
})
