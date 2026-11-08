# Plan: what is shared, what is one viewer's

A state version is read in two rounds: the fetches decisions read, then the fetches the slots on
the paths taken read. Three things are wrong with how that works today:

- **The results travel loosely.** Twenty-eight entry points in `Renderer`, `Patches`,
  `MemberGraph` and `SurfaceGraph` take some mix of `states`, `selections`, `env`, `open` and a
  `FetchSnapshot`, and decisions read their answers from a cell on the renderer
  (`Decided.fetched`).
- **Two wiring bugs show only at run time:** a decision made before any answer
  (`Evaluator.unanswered`), and a render handed answers for other reads ("not resolved for this
  render").
- **Every decision fetch is answered on every read**, for the whole dashboard, shown or not, and
  on every recorder frame even with nobody connected. Through the history cache that is one call
  to HA per fetch each time its bucket ends: ten hidden tabs gated on a dense sensor's week cost
  ten recorder calls every 14 minutes, at 0.5–3.4 s each, for tabs nobody opened.

**State:** not started. Stacked on `docs/plan-slot-types.md`.

## Design

- **Two values, by who shares them.** Working names: `Shared` and `Viewing` (`View` is taken, see
  the terminology).
  - `Shared`: one per dashboard and state version, for every viewer. It holds the entity states,
    the answers to the decision fetches asked so far, and the decisions made from them, each once
    (today's `Decisions`). Both fill on demand and are kept for the last two versions, as
    `Decided` keeps decisions today (the recorder's "before" and "after"), so what one viewer's
    walk answered and decided the next one reuses.
  - `Viewing`: one viewer's, for one send. It holds the viewer's selections and node variables,
    and the answers to the slot fetches that send reads.
- **Only what is shown is answered, in rounds.** The pre-read walks what this send shows (the
  page, an arriving surface, the nodes a pull touches). A decision whose fetches `Shared` has not
  answered does not raise and does not wait: the walk returns what it needs and how to go on.

  ```scala
  enum Step[+A]:
    case Done(a: A)
    case Blocked(needs: Set[SlotRead], resume: Answers => Step[A])

  def run[A](s: Step[A]): IO[A] = s match
    case Step.Done(a)           => IO.pure(a)
    case Step.Blocked(needs, k) => answer(needs).flatMap(as => run(k(as)))
  ```

  `flatMap` waits where one decision sits inside another. The batching is cats' `Parallel`, as
  `Either` pairs with `Validated`: `Step`'s monad is sequential, and its parallel applicative
  `Step.Par` has a `map2` that merges two `Blocked` into one with both sets of needs, so
  `parTraverse` over siblings asks for all of theirs in one batch:

  ```scala
  case (Blocked(n1, k1), Blocked(n2, k2)) =>
    Blocked(n1 ++ n2, as => map2(k1(as), k2(as))(f))
  ```

  That is Haxl's model ("There is no fork", ICFP 2014), and the reason `map2` is not derived from
  `flatMap`. The continuation holds where the walk was, so nothing walks again.
  When the walk is `Done`, its result is the slot reads on the final paths, which are the last
  batch and become the `Viewing`. The number of rounds is the nesting depth of fetch-reading
  decisions, fixed at build: none for most dashboards, which then walk as today.
- **Decision code stays synchronous.** The build's index (each decision to its fetches, each
  entity to the decisions it moves) tells the walk whether a decision is answered; only then does
  it call the graphs, which never see a missing answer. The render is a plain function over
  `Shared` and `Viewing`: it walks exactly the paths the run completed.
- **No walk where nothing is discovered.** The recorder re-makes only the sets a frame touches,
  and a press decides one set, both known from the index, so each answers those fetches in one
  batch and then decides. A hidden set is still answered when its own entities change, because
  membership follows the state stream so a later viewer starts from the right "before".
- **The plain path stays plain.** A subtree whose index shows no fetch-reading decision is walked
  without `Step`, so the common case allocates nothing new.
- **A `Viewing` depends on a `Shared`, never the reverse.** No decision reads a viewer's variable
  (refused at build, #673) or a slot's value (a guard and an order term see no node values; a
  `valueWhile`'s node values count as its own fetches).
- **Each entry point takes one or both.** `Shared` alone: the graphs, the recorder, a press, the
  node debug route. `Shared` and `Viewing`: anything that renders. `Server` makes both, and
  nothing else reads the store. `Patches.resume` splits in three: what the client is owed, the
  run for those nodes, the patches.
- **Not a `Dispatcher`.** It runs an `IO` from code outside one, by blocking or by firing a
  future, so inside the walk it would block a compute thread per fetch, answer them one at a time
  and put effects back in the synchronous core. Not the `fetch` library (Haxl for Scala): it
  brings its own data sources and cache beside the history cache, for what `Step` does in a few
  dozen lines.
- **What goes:** answering every decision fetch in `currentFor`; the build's flat
  `decisionReads`, for the index; the cell (`Decided.answer`, `Decided.fetched`);
  `Evaluator.unanswered` at the decision sites (a `once` slot keeps it, since a fetch is never
  `once`); "not resolved for this render" as a reachable error; the `answer(_, env)` functions
  `Server` hands to `Patches`.

## Steps

1. The index and `Shared`: decisions to fetches, entities to decisions; the decision entry
   points take `Shared`; the recorder and a press answer from the index; the cell and the
   answer-everything go.
2. `Step` and `Viewing`: the pre-read walks return `Step`, with the plain path for subtrees with
   no fetch-reading decision; one runner per kind of send; render and patch entry points take
   `Shared` and `Viewing`; `Patches.resume` splits.
3. Tests: a hidden branch's fetch is never asked; a branch inside a branch takes two rounds and
   siblings one; a second viewer reuses the first one's answers; the recorder asks only for the
   sets a frame touches; fixtures build both values through the same runners, never a hand-made
   answer map.
4. Docs: pipeline doc §1a (each layer gets its value) and §6; ADR 0034's decision paragraph; the
   terminology for both names. Bench: `page`, `tickRender` and a pull, allocation first with
   escape analysis off.
