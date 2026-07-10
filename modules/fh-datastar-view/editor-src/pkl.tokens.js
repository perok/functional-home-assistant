// External tokenizer for Pkl string literals — the Lezer counterpart of
// tree-sitter-pkl's scanner.c. It lexes a whole string literal as one
// `StringLiteral` token, handling every delimiter form:
//
//   "…"            single-line
//   """…"""        multi-line
//   #"…"#  ##"…"## …  custom (pound) delimiters, up to any level
//   #"""…"""#  …    custom-delimited multi-line
//
// Inside a level-N string, an escape / interpolation is `\` + N `#` + a unit,
// and the closing delimiter is `"`(or `"""`) + exactly N `#`. Interpolation is
// not sub-parsed (parity with the previous StreamLanguage highlighter, which
// also coloured the whole literal) — the payoff of the migration is correct
// multi-`#` + multi-line lexing and backtick identifiers, not embedded exprs.
import { ExternalTokenizer } from "@lezer/lr"
import { StringLiteral, SubscriptLBracket, EntryLBracket } from "./pkl.parser.terms.js"

const HASH = 35 /* # */, QUOTE = 34 /* " */, BACKSLASH = 92 /* \ */, EXCL = 33 /* ! */
const LBRACKET = 91 /* [ */, SPACE = 32, TAB = 9, NEWLINE = 10, CR = 13, SEMI = 59

// Do the next `count` chars (from `input.peek(from)`) all equal `code`?
function peekRun(input, from, code, count) {
  for (let i = 0; i < count; i++) if (input.peek(from + i) !== code) return false
  return true
}

export const stringLiteral = new ExternalTokenizer((input) => {
  let pounds = 0
  while (input.next === HASH) { pounds++; input.advance() }
  // `#!` is a shebang, not a string; `#` not followed by `"` is not ours either.
  if (input.next !== QUOTE || (pounds > 0 && input.peek(0) === EXCL)) return

  input.advance() // opening quote
  let multiline = false
  if (input.next === QUOTE && input.peek(1) === QUOTE) {
    multiline = true
    input.advance(); input.advance()
  }
  const quotes = multiline ? 3 : 1

  for (;;) {
    const c = input.next
    if (c < 0) { input.acceptToken(StringLiteral); return } // EOF: unterminated

    if (c === BACKSLASH) {
      // escape / interpolation prefix: `\` + `pounds` hashes, then one unit.
      if (peekRun(input, 1, HASH, pounds)) {
        input.advance(1 + pounds)
        if (input.next >= 0) input.advance() // consume the escaped char / `(`
      } else {
        input.advance()
      }
      continue
    }

    if (c === QUOTE && peekRun(input, 0, QUOTE, quotes) && peekRun(input, quotes, HASH, pounds)) {
      input.advance(quotes + pounds) // closing delimiter
      input.acceptToken(StringLiteral)
      return
    }

    input.advance()
  }
})

// Resolve a single `[` into either a subscript opener (`x[i]`) or a new
// object-entry opener (`["key"] = …`). tree-sitter keys this off a preceding
// newline; we combine that with what the parser can actually accept here
// (`stack.canShift`), which also handles cases the newline test alone can't —
// e.g. the first `[` right after `{`, where only an entry is valid. `[[`
// (member predicate) is left to the built-in token.
export const brackets = new ExternalTokenizer((input, stack) => {
  if (input.next !== LBRACKET || input.peek(1) === LBRACKET) return

  let sameLine = true
  for (let i = -1; ; i--) {
    const c = input.peek(i)
    if (c === SPACE || c === TAB) continue
    if (c < 0 || c === NEWLINE || c === CR || c === SEMI) sameLine = false
    break
  }

  const canSub = stack.canShift(SubscriptLBracket)
  const canEntry = stack.canShift(EntryLBracket)
  let tok
  if (canSub && canEntry) tok = sameLine ? SubscriptLBracket : EntryLBracket
  else if (canSub) tok = SubscriptLBracket
  else tok = EntryLBracket

  input.advance()
  input.acceptToken(tok)
}, { contextual: true })
