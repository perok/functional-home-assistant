# Plan — "query" becomes "fetch" for provider reads

"Query" names two things here: `query.pkl`'s `q.` candidate queries (which
entities, decided at build, presence and order live) and a read from a
**provider** such as Home Assistant's history API (a "query slot"). Talking
about the second next to the first, and adding provider reads as CEL inputs
(#541 E3b, on #627), keeps tripping over the shared word.

So the provider side is renamed **fetch**. Nothing changes behaviour; the wire's
`query` field becomes `fetch`, so the byte-identity snapshots move with it.

## What is renamed

- Terms (`docs/terminology.md`): query slot → **fetch slot**; a provider
  answers a **fetch** (its name and parameters). Provider, answer, stage, ask
  and read keep their names.
- Pkl: `slotMod.Query` → `Fetch`, a slot's `query` → `fetch`, `querySlot` →
  `fetchSlot`, `historyQuery` → `historyFetch`.
- Wire: `SlotSource.query` → `fetch`.
- Scala: the package `fh.view.query` → `fh.view.fetch`; `SlotQuery`,
  `QueryTemplate`, `QueryRequest`, `QueryIdentity`, `QueryResolver`,
  `QuerySnapshot`, `Queries`, `HistoryQuery`, `SlotShape.Query` and the
  methods and fields named after them (`Component.queries`,
  `queriesForPage`, …), and the suites and benchmark named after them.
- Docs: ADR 0031 (renamed to match), the architecture doc, and every ADR and
  guide that means a provider read by "query".

## What is not

- `q.` and `query.pkl`, and every candidate-query word: sets, clauses, `where`.
- URL query strings (`uri.query`, `withQueryParam`, `QueryRedactor`) and the
  DOM's `querySelector`.

## Order

This PR is independent of #629 and #627 and goes to `main`. #627 merges it
before E3b, which adds a fetch as an expression value (`c.fetch.history(e)`).
