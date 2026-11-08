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

**State:** steps 1–3 built, step 4's docs written; the bench is left. Stacked on #674.

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
    recorder treats it as one it does not hold, and decides it on the previous version.
  - A decision nothing can mark dirty is dirty at every version: made once per version a reader
    reaches, as every decision is today, at the cost of evaluating it and a lookup in the
    resolver's cache.
  - A plain tick, a change that moves only what a node shows (a light from 40% to 50%), dirties
    no decision, or one whose value comes out the same, and ends as a `touched` entry.
- **Two values, by who shares them.** Working names: `Shared` and `Viewing` (`View` is taken, see
  the terminology).
  - `Shared`: one per dashboard and state version, for every viewer: the entity states and the
    decisions made from them (today's `Decisions`).
  - The store publishes each version as one `Frame(changes, state)`, the pair its update already
    makes, for every version bump. The recorder folds that stream with the previous `Shared` as
    its accumulator: the next is the previous one's decisions minus those the frame dirtied, on
    the frame's own states, sharing structure, since a copy per frame is O(decisions), the
    3.4 ms rescan the recorder already removed. The previous one's states are the frame's
    "before", so `Patches.beforeSnapshot` goes, and the recorder no longer reads the store. It
    derives every version, watched or not, so the chain does not break; with nobody watching it
    makes nothing. The last two are kept, as `Decided` keeps them today.
  - The store hands the recorder one stream that starts with the current state: subscribed
    (`subscribeAwait`) before that state is read, then the frames newer than it. A start read
    apart from the subscription would miss the frame published between them, and the fold would
    skip a version without its dirty marks.
  - A removal is a change like any other, so a decision that read the entity goes dirty:

    ```scala
    enum StateChange:
      case Moved(entityId: String, previous: Option[EntityState], current: EntityState)
      case Removed(entityId: String, previous: EntityState)
    ```

    Its nodes are touched and render without it until the registry watcher's rebuild lands.
  - A reader reuses only a decision made at its own version or an older one that no change since
    has dirtied, never one made at a newer version, whose inputs may have moved after its own. A
    reader ahead of the recorder starts its version empty: correct, and only unshared, as a
    straggler is today; the recorder later adds what the previous version carries beneath it,
    without overwriting.
  - A version holds two parts: the decisions carried from the previous one, immutable, and the
    fills, decisions readers made on demand, in a `ConcurrentHashMap` as `Decisions` keeps them
    today. A lookup reads the fills, then the carried part; the next version is the carried part
    plus the fills, minus the frame's dirty ones, so only the few fills are copied. Fills do not
    go through the fold: a decision is pure, so two readers making it at once both make it right,
    and a page must not wait on the recorder.
  - A decision a reader makes at version v after v+1 exists is passed on to v+1, unless v+1's
    frame dirtied it, so a viewer's work reaches newer versions without a copy.
  - `Membership` goes, and the fold holds nothing but the previous `Shared`. Which member nodes a
    change reaches (`binding`) is a static entity-to-sets index, read off the compiled tree, then
    each shown set's decision in `Shared` (its present members) and the nodes inside each that
    read the entity (`Member.byEntity`): the recorder and every render read one decision, so they
    cannot disagree. A set the index names but `Shared` lacks is made, as a decision on a chain
    is; a nested set is consulted only while its owner member is present.
  - Fetch answers are not kept in `Shared`: the resolver caches them, and a run holds them only
    while it makes a decision.
  - `Viewing`: one viewer's, for one send. It holds the viewer's selections and node variables,
    and the answers to the slot fetches that send reads.
- **A decision is made in rounds, by walking again.** A walk runs synchronously against a run:
  a decision it reaches that is not made, and whose fetches the run has not answered, notes them
  as needed and reads as not shown, so nothing beneath it is reached, and is not kept. A walk
  that ends with needs has them answered in one batch, and walks again; the decisions it made are
  in `Shared`, so the next walk only reads them. A walk that ends with none is the result. Its
  slot reads are the last batch and become the `Viewing`. Siblings land in one batch, so the
  rounds are the nesting depth of fetch-reading decisions: none for most dashboards, which walk
  once, as today.

  ```scala
  def decide[A](walk: Shared.Run => A): IO[A]   // the loop; a walk reads `shared.asking(run)`
  ```

  One description of what is shown: the walk that decides is the walk the render and the patches
  read, so neither can reach a decision the run did not make. Outside a run, a fetch-reading
  decision not yet made is a wiring bug and raises.
- **Considered: resuming instead of walking again** (Haxl, "There is no fork", ICFP 2014). A walk
  returns `Done(a)` or `Blocked(needs, resume)`, its `flatMap` waiting where one decision sits
  inside another and a `Parallel` whose `map2` merges siblings' needs into one batch, so nothing
  walks twice. The difference is where a walk's position is kept: in the closures, against
  rerunning with `Shared` already holding what was decided. It needs a second description of
  what is shown, the build's chain of decisions (outer before inner) that a resuming walk follows
  before the graphs run, and a decision that chain misses fails at render, the same class of bug
  as "not resolved for this render". Reopen if a walk's cost shows beside its fetches.
- **The recorder decides through the same loop.** Its plan reads the frame's "before" and "after"
  in one run, so a dirty decision shown, or one shown but never made, is made there; one nobody
  shows is not reached.
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
  brings its own data sources and cache beside the history cache, for what the loop does in a few
  lines.
- **Accepted for now: a fetch changes without an event.** A history bucket rolling over dirties
  nothing, so a decision that reads a fetch is made again only when its other inputs change, where
  today it is made again on every version. Later, not now: such values in a store of their own
  that pushes dirty marks, or a `staleAfter`.
- **A fetch's entity is an input only when the author says so.** The machinery knows nothing of
  what is inside a fetch. A value expression can carry a general part, "depends on entity X",
  counted in `entities` like an entity read, so a decision and a slot get the edge alike. The
  author-facing fetch API sets it through an option, off by default; the build says nothing when
  it is off, since that is the author's choice.
- **What goes:** answering every decision fetch in `currentFor`; the build's flat
  `decisionReads`, for the index; the cell (`Decided.answer`, `Decided.fetched`);
  `Evaluator.unanswered` at the decision sites (a `once` slot keeps it, since a fetch is never
  `once`); "not resolved for this render" as a reachable error; the `answer(_, env)` functions
  `Server` hands to `Patches`; a `Decisions` per snapshot that starts empty; `Membership` as a
  second copy of the set decisions; `syncMembers`' fold over every set, and its first frame
  deciding them all; `Patches.beforeSnapshot`; the recorder's read of the store.

## Steps

1. Dirty marks and `Shared`: the index (decisions to fetches, entities to the decisions they
   dirty); the store's `Frame`, its one stream from the current state, `StateChange.Removed`, and
   the recorder's fold; the "depends on entity" part and its
   fetch API option; `Shared` derived from the version before it; `Decided` and `Membership` as
   one cache;
   the decision entry points take `Shared`. The cell stays until step 2, so decisions keep their
   answers in between.
2. The loop and `Viewing`: the pre-read walks, a press, the debug route and the recorder's plan
   run through `decide`; the graphs take a decision's answers from the run, never the cell; render and patch entry points take `Shared` and `Viewing`;
   `Patches.resume` splits; the cell and the answer-everything go.
3. Tests: after random sequences of changes, the carried `Shared` equals deciding from scratch; a
   hidden dirty decision asks nothing; a hidden branch's fetch is never asked; a branch inside a
   branch takes two rounds and siblings one; a second viewer reuses the first one's decisions; a
   reader ahead of the recorder renders correctly; the recorder asks only for the dirty decisions
   shown; fixtures build both values through the same runners, never a hand-made answer map.
4. Docs, framed as dirty marking: pipeline doc §1a (each layer gets its value; its rule "a
   decision is derived from the snapshot, never read from the recorder" becomes "the recorder and
   the readers share one versioned cache"; the lasting goals join its rules) and §6; ADR 0034's
   decision paragraph; the terminology for `Shared`, `Viewing`, dirty and a plain tick; the
   fetch option in `writing-components.md`; a §8 open question: a decision nothing can mark dirty
   is never revisited by the recorder, so a viewer's page can disagree with a fresh decision until
   a page load, until fetches push dirty marks; a follow-up note in the ADR: a lagging recorder
   could fold every queued frame in one pass (fs2 `.chunks`: dirty from all their changes, "before"
   the previous `Shared`, "after" the last state), left out until lag shows. Bench:
   `page`, `tickRender`, a pull and a recorder frame, allocation first with escape analysis off.
