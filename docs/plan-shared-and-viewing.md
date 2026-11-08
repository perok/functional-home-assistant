# Plan: what is shared, what is one viewer's

A state version is read in two rounds: the fetches decisions read, then the fetches the slots on
the paths taken read. Four things are wrong with how that works today:

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
- **Every version decides everything again.** `Decided` starts each snapshot empty, so a reader
  re-makes every decision on its path after any change; the recorder folds over every set each
  frame, and on its first frame decides them all, shown or not.

**State:** not started. Stacked on #674.

## Goals

- Work happens only where a dirty mark meets something shown.
- One mechanism for the page, a pull, a press and the recorder.
- Shared work is done once for every viewer; a viewer's own work is only its choices.
- Cost follows what changed and what is shown, not the dashboard's size or its hidden parts.
- The build knows the shape; the runtime only looks it up.
- What has been answered is in the types, so "not answered" cannot be represented.

## Design

- **A change marks what it touches dirty; work happens only where a dirty mark meets something
  shown.**
  - A changed entity looks up, in the index, the nodes it touches (`componentsFor`, `binding`, as
    today) and the decisions it touches: a set's (`movedBy`), a state group's branch
    (`stateGroupsReading`), a `valueWhile`'s.
  - Each edge is read off the compiled expression that makes the decision (a clause's `when`, an
    order term, a surface's condition, the `valueWhile` itself), never kept beside it: a missing
    edge does not fail, it leaves a decision stale.
  - A decision's value depends only on entity states and fetch answers, never on another
    decision (`Evaluator` never reads `Decisions`). What depends on an outer decision is only
    whether an inner one is shown, and that is read from the cache. So the dirty set is a lookup
    and does not cascade.
  - A dirty decision is made again only when a reader reaches it: a viewer's walk (the page, an
    arriving surface, a pull), a press, the node debug route, or the recorder for what some
    session has open. One nobody shows stays dirty and costs nothing; when it is shown again, the
    recorder treats it as a set it does not hold, which it already decides on the frame's
    "before".
  - A plain tick, a change that moves only what a node shows (a light from 40% to 50%), dirties
    no decision, or one whose value comes out the same, and ends as a `touched` entry.
- **Two values, by who shares them.** Working names: `Shared` and `Viewing` (`View` is taken, see
  the terminology).
  - `Shared`: one per dashboard and state version, for every viewer: the entity states and the
    decisions made from them (today's `Decisions`). It is derived from the version before it:
    that one's decisions minus those the change marked dirty, sharing structure, since a copy per
    frame is O(decisions), the 3.4 ms rescan the recorder already removed. The recorder derives
    each version, watched or not, so the chain does not break; with nobody watching it makes
    nothing. The last two are kept, as `Decided` keeps them today.
  - A reader reuses only a decision made at its own version or an older one that no change since
    has dirtied, never one made at a newer version, whose inputs may have moved after its own. A
    reader ahead of the recorder (between the store's write and the recorder's pass) starts from
    an empty `Shared`: correct, and only unshared, as a straggler is today.
  - The recorder's held `Membership` is the previous version's set decisions, so `Decided` and
    `Membership` become one cache.
  - Fetch answers are not kept in `Shared`: the resolver caches them, and a run holds them only
    while it makes a decision.
  - `Viewing`: one viewer's, for one send. It holds the viewer's selections and node variables,
    and the answers to the slot fetches that send reads.
- **A decision is made in rounds.** A walk that reaches a decision that is dirty or not yet made,
  and whose fetches the run has not answered, does not raise and does not wait: it returns what
  it needs and how to go on.

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
  When a viewer's walk is `Done`, its result is the slot reads on the final paths, which are the
  last batch and become the `Viewing`. The number of rounds is the nesting depth of fetch-reading
  decisions, fixed at build: none for most dashboards, which then walk as today.
- **Decision code stays synchronous.** The build's index (each decision to its fetches, each
  entity to the decisions it dirties) tells the walk whether a decision can be made; only then
  does it call the graphs, which never see a missing answer. The render is a plain function over
  `Shared` and `Viewing`: it walks exactly the paths the run completed.
- **The recorder walks nothing.** From the changed entities it looks up the dirty decisions,
  keeps those shown (the chain above each is static, outer before inner), and makes them through
  the same runner, one batch per nesting level. A decision on a chain is read from the cache, or
  made through the runner too when it is not there: not dirty, but never made.
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
- **Accepted for now: a fetch changes without an event.** A history bucket rolling over dirties
  nothing, so a decision that reads a fetch is made again only when its entities change, where
  today it is made again on every version. Later: such values in a store of their own that
  pushes dirty marks.
- **Open: a decision that reads only a fetch never goes dirty.** `ValueExpr.entities` counts no
  fetch (`Value.Fetch` gives `Nil`), so a branch whose condition reads only a sensor's history is
  never made again until a reload. Dirtying it on every change of `FetchTemplate.entity` may cost
  too much for a dense sensor. One candidate: the Pkl side sets a flag on the wire saying the
  fetch follows its entity. To settle before step 1 lands.
- **What goes:** answering every decision fetch in `currentFor`; the build's flat
  `decisionReads`, for the index; the cell (`Decided.answer`, `Decided.fetched`);
  `Evaluator.unanswered` at the decision sites (a `once` slot keeps it, since a fetch is never
  `once`); "not resolved for this render" as a reachable error; the `answer(_, env)` functions
  `Server` hands to `Patches`; a `Decisions` per snapshot that starts empty; `Membership` as a
  second copy of the set decisions; `syncMembers`' fold over every set, and its first frame
  deciding them all.

## Steps

1. Dirty marks and `Shared`: the index (decisions to fetches, entities to the decisions they
   dirty); `Shared` derived from the version before it; `Decided` and `Membership` as one cache;
   the decision entry points take `Shared`. The cell stays until step 2, so decisions keep their
   answers in between.
2. `Step` and `Viewing`: the pre-read walks return `Step`, with the plain path for subtrees with
   no fetch-reading decision; one runner per kind of send; the recorder makes the dirty decisions
   shown through it; render and patch entry points take `Shared` and `Viewing`;
   `Patches.resume` splits; the cell and the answer-everything go.
3. Tests: after random sequences of changes, the carried `Shared` equals deciding from scratch; a
   hidden dirty decision asks nothing; a hidden branch's fetch is never asked; a branch inside a
   branch takes two rounds and siblings one; a second viewer reuses the first one's decisions; a
   reader ahead of the recorder renders correctly; the recorder asks only for the dirty decisions
   shown; fixtures build both values through the same runners, never a hand-made answer map.
4. Docs, framed as dirty marking: pipeline doc §1a (each layer gets its value; its rule "a
   decision is derived from the snapshot, never read from the recorder" becomes "the recorder and
   the readers share one versioned cache"; the lasting goals join its rules) and §6; ADR 0034's
   decision paragraph; the terminology for `Shared`, `Viewing`, dirty and a plain tick. Bench:
   `page`, `tickRender`, a pull and a recorder frame, allocation first with escape analysis off.
