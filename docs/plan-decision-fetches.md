# Plan: a decision reads a fetch

A fetch works at any depth inside a slot: the render answers it first. A decision made outside a
render does not: a set's clauses and order (`MemberGraph`), a state surface or a state `c.iff`
(`SurfaceGraph`), a `valueWhile` (`Renderer.gateHolds`) and a press on a set
(`Server.targetAtPress`) run with `Evaluator.noFetch`, so a fetch there reads as `""` and a CEL
over it never holds. `validate` refuses a fetch in a `valueWhile` and nowhere else.

Every decision site is fixed at build, and the walk over every expression (`ValueExpr.all`) finds
every fetch one reads. So the decisions' fetches are known before any state arrives, and can be
answered before anything decides, as a render's are before it reads.

**State:** not started.

## Design

- **The build lists them.** The compiled tree carries every fetch any decision reads
  (`Ready.Dashboard.decisionAsks`): set clauses and orders, state surfaces, `valueWhile`s, the sets
  a press acts on. Most dashboards have none, and then nothing below runs.
- **A decision is shared, so its fetch reads no viewer's variable.** `Decisions` are made once per
  snapshot for every viewer, so a fetch whose template reads a node variable (a chooser's window)
  is refused there at build: "a decision is made for every viewer at once, so its fetch cannot
  read the variable 'window'". The `valueWhile` refusal goes; this rule replaces it.
- **Answered per snapshot, read synchronously.** Before anything decides on a snapshot, the list
  is resolved once (`FetchSnapshot.resolve`, through the shared caches) and kept by the
  snapshot's identity, the last two as `Decided` keeps them. Each decision site reads those
  answers where it passes `noFetch` today, through the renderer's `Decided`, so no signature that
  takes `states` changes.
- **Prepared where a snapshot is read.** `Server` reads one in ten places (`stateStore.current`,
  `stateStore.snapshot`); one helper reads it and prepares its answers, and the recorder prepares
  its frame's "before" as well. A decision on a snapshot nobody prepared is a wiring bug and
  raises `FHError.internal`, as an unanswered slot read does.
- **No wake.** As for a slot's fetch, an answer that moves on its own wakes nothing: a decision is
  re-made when its entities change, and the recorder compares a held set against its last
  decision.
- **What goes:** `Evaluator.noFetch` and the renderer's throwing stand-in, and the `valueWhile`
  fetch refusal.

## Steps

1. The build: `ValueExpr.fetches`, the decisions' list on `Ready.Dashboard`, the variable rule.
2. The answers: a per-renderer store by snapshot identity, filled in `IO`, read by every decision
   site; `Server`'s snapshot helper; the recorder's "before".
3. Tests: a set clause, a set order, a state surface, a `valueWhile` and a press, each over a
   fetch, deciding by its answer; the variable refused; a dashboard with no decision fetches
   resolving nothing.
4. Docs: the architecture doc's decision layer, ADR 0034's `valueWhile`, ADR 0007 if a set's
   clause rule moves. Bench: one round, allocation first.
