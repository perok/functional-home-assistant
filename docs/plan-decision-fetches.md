# Plan: a decision reads a fetch

A fetch works at any depth inside a slot: the render answers it first. A decision made outside a
render did not: a set's clauses and order (`MemberGraph`), a state surface or a state `c.iff`
(`SurfaceGraph`), a `valueWhile` (`Renderer.gateHolds`) and a press on a set
(`Server.targetAtPress`) ran with `Evaluator.noFetch`, so a fetch there read as `""` and a CEL
over it never held. `validate` refused a fetch in a `valueWhile` and nowhere else.

Every decision site is fixed at build, and the walk over every expression (`ValueExpr.all`) finds
every fetch one reads. So the decisions' fetches are known before any state arrives, and can be
answered before anything decides, as a render's are before it reads.

**State:** built (steps 1–4), benched: allocation equal to the base with escape analysis off.

## Design

- **The build lists them.** `Dashboard.decisionFetches` walks every decision (set clauses and
  orders, `valueWhile`s, state surfaces, the sets a press acts on) for its fetches, with where each
  is read; `Ready.Dashboard.decisionReads` holds them resolved. Most dashboards have none, and
  then nothing below runs.
- **A decision is shared, so its fetch reads no viewer's variable.** `Decisions` are made once per
  snapshot for every viewer, so a fetch whose template reads a node variable (a chooser's window)
  is refused there at build: "a decision is made for every viewer at once, so its fetch cannot
  read the variable 'window'". The `valueWhile` refusal goes; one whose source is a fetch's answer
  is still refused, as never a bool.
- **The answers depend on no snapshot.** With no variable, the list is fixed at build, so its
  answers are a function of time, not of state. The renderer's `Decided` keeps the newest
  (`Decided.answer`, `Decided.fetched`), and every decision site reads them where it passed
  `noFetch`; a decision once made on a snapshot is kept by `Decisions` as before.
- **Answered where a snapshot is read.** `Renderer.answerDecisions` resolves the list through
  `Server.answer` (the shared caches). `Server.currentFor(renderer)` answers and then reads the
  store, at every site that reads one for a renderer: the recorder's frame (which covers its
  "before" too), a page, a repaint, a host fill, a variable write, a press, the node debug route.
  The pull and the opening read the renderer beside the store and answer right after it
  (`answering`), because `Patches.resume` decides as it is called. A decision before any answer
  reads `Evaluator.unanswered`, which raises `FHError.internal`.
- **No wake.** As for a slot's fetch, an answer that moves on its own wakes nothing: a decision is
  re-made when its entities change, and the recorder compares a held set against its last
  decision.
- **What went:** `Evaluator.noFetch`, the renderer's own throwing stand-in (one `unanswered`
  replaces both; a `once` slot passes it, since a fetch is live and never `once`), and the
  `valueWhile` fetch refusal.

## Steps

1. The build: `ValueExpr.fetches`, `Dashboard.decisionFetches`, `Ready.Dashboard.decisionReads`,
   the variable rule. **Done.**
2. The answers: `Decided`'s newest answers, `Renderer.answerDecisions` and `pressed`,
   `Server.currentFor`. **Done.**
3. Tests: `DecisionFetchSuite` (a set clause, a set order, a state surface, a press, the variable
   refused, a dashboard with no decision fetches asking nothing), `ValueWhileSuite` (a
   `valueWhile` over a fetch), `DecisionFetchServedSuite` (a served page decides by the recorder's
   answer). **Done.**
4. Docs: the pipeline doc's decision layer and fetch section, ADR 0034's `valueWhile`, the
   terminology's decisions. ADR 0007 needed nothing. Bench: one round, allocation first, escape
   analysis off.
