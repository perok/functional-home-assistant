// Standalone terminal verifier for the Pkl Lezer parser: parses every real
// dashboard .pkl in the repo, reports any error (⚠) nodes with context, and
// prints a highlight-tag dump for a sample so colours can be eyeballed without
// a browser. Run: `node pkl.test.mjs`.
import { readFileSync, readdirSync } from "node:fs"
import { join } from "node:path"
import { parser } from "./pkl.parser.js"
import { styleTags, tags as t } from "@lezer/highlight"

const DASH = "../src/main/resources/dashboards"

function pklFiles(dir) {
  const out = []
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) out.push(...pklFiles(join(dir, e.name)))
    else if (e.name.endsWith(".pkl")) out.push(join(dir, e.name))
  }
  return out
}

function errorsIn(text) {
  const tree = parser.parse(text)
  const errs = []
  tree.iterate({
    enter(n) {
      if (n.type.isError) {
        const from = n.from
        const lineStart = text.lastIndexOf("\n", from - 1) + 1
        const lineNo = text.slice(0, from).split("\n").length
        const lineEnd = text.indexOf("\n", from)
        const line = text.slice(lineStart, lineEnd < 0 ? text.length : lineEnd)
        errs.push({ lineNo, col: from - lineStart, line })
      }
    },
  })
  return errs
}

let total = 0, failed = 0
for (const f of pklFiles(DASH)) {
  total++
  const text = readFileSync(f, "utf8")
  const errs = errorsIn(text)
  if (errs.length) {
    failed++
    console.log(`\x1b[31mFAIL\x1b[0m ${f}  (${errs.length} error node(s))`)
    for (const e of errs.slice(0, 5)) {
      console.log(`   L${e.lineNo}:${e.col}  ${e.line.trim()}`)
    }
  } else {
    console.log(`\x1b[32mok  \x1b[0m ${f}`)
  }
}

console.log(`\n${total - failed}/${total} files parsed with no error nodes`)
process.exit(failed ? 1 : 0)
