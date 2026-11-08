# Plan: what is shared, what is one viewer's

A state version is read in two rounds: the fetches decisions read, then the fetches the slots on
the paths taken read. The results travel loosely. Twenty-eight entry points in `Renderer`,
`Patches`, `MemberGraph` and `SurfaceGraph` take some mix of `states`, `selections`, `env`, `open`
and a `FetchSnapshot`. Decisions read their answers from a cell on the renderer
(`Decided.fetched`). Two wiring bugs can only be found at run time: a decision made before any
answer (`Evaluator.unanswered`), and a render handed answers for other reads ("not resolved for
this render").

**State:** not started. Stacked on `docs/plan-slot-types.md`.

## Design

- **Two values, by who shares them.** Working names: `Shared` and `Viewing` (`View` is taken, see
  the terminology).
  - `Shared`: one per dashboard and state version, for every viewer. It holds the entity states,
    the answers to every fetch a decision reads (`decisionReads`), and the decisions made from
    them, lazily and each once (today's `Decisions`). `renderer.shared(store)` makes it in `IO`,
    and the renderer keeps the last two, as `Decided` keeps decisions today (the recorder's
    "before" and "after").
  - `Viewing`: one viewer's, for one send. It holds the viewer's selections and node variables,
    and the answers to the slot fetches that send reads. One function makes it in `IO` from a
    `Shared`, the viewer's choices and what is being sent (the page, an arriving surface, the
    nodes a pull touches): it runs the pre-read and answers it, so a render can only be handed
    the answers to its own reads.
- **A `Viewing` depends on a `Shared`, never the reverse.** No decision reads a viewer's variable
  (refused at build, #673) or a slot's value (a guard and an order term see no node values; the
  node values a `valueWhile` reads are among the decision fetches). And `Shared` answers every
  decision fetch in the dashboard, shown or not. So a node that a decision or a selection brings
  into view finds its decisions already answered.
- **Each entry point takes one or both.** `Shared` alone: the graphs, the recorder, a press, the
  node debug route. `Shared` and `Viewing`: anything that renders or pre-reads. `Server` makes
  both, and nothing else reads the store.
- **`Patches.resume` splits in three:** what the client is owed (pure, from the log and
  `Shared`), the `Viewing` for those nodes (`IO`), and the patches (pure). It stops taking an
  `answer` function.
- **What goes:** the cell (`Decided.answer`, `Decided.fetched`); `Evaluator.unanswered` at the
  decision sites (a `once` slot keeps it, since a fetch is never `once`); "not resolved for this
  render" as a reachable error; the `answer(_, env)` functions `Server` hands to `Patches`.

## Steps

1. `Shared`: the decision entry points (graphs, recorder, press, debug) take it instead of
   `states`; `Decisions` becomes its memo; the cell goes.
2. `Viewing`: one builder per kind of send; the render, pre-read and patch entry points take
   `Shared` and `Viewing`; `Patches.resume` splits.
3. Tests: fixtures build both through the same builders, never a hand-made answer map; a node a
   selection opens decides by its answers; a nested set's members; the recorder's "before" and
   "after" sharing one set of answers.
4. Docs: pipeline doc §1a (each layer gets its value) and §6; the terminology for both names.
   Bench: `page`, `tickRender` and a pull, allocation first with escape analysis off.
