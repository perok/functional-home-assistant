# Plan — declared selections

Work in flight for [issue #209](https://github.com/perok/functional-home-assistant/issues/209).
The node-variable mechanism itself is ADR 0033 (shipped in #382). This file is the route from
there to the issue's headline: a tab bar whose selection is a declared node variable, not the
`ui_<gid>` string convention. Delete it when the last step is done or refiled as its own issue.

## Where it stands

A node declares a variable, a descendant's QUERY reads it by name, a viewer's choice writes it
per session. Four gaps keep it at one user (`c.windowChooser`):

1. **A child cannot name its declarer.** `@@NODE_ID@@` is spliced only at a node carrying
   `inlineSurfaces`, so the chooser renders its own buttons as markup. Markup cannot carry a
   tap, so the buttons cannot be guarded (ADR 0019): one shared busy signal is forbidden, and
   a slow window redraw shows nothing on the button pressed (#412, #429).
2. **A choice dies with its session.** The committed `_var_*` signals are `_`-prefixed, so a
   reconnect does not carry them. A restart or a reap mints a fresh session at the declared
   values, and the opening frame resets the bar. A tab survives the same reconnect, because
   `ui_<gid>` rides it.
3. **Only a query parameter reads a variable.** A bake group cannot be selected by one.
4. **So the tab bar is still the convention**: `ui_<gid>`, `_<gid>__pending`,
   `SurfaceGraph.committedSelection`, `ui.` on the URL, `uiStateOf` threaded through every
   render path.

## Steps

Each is one PR, stacked in this order.

### 1. A node names its declarer

`varMod.declarer(name)` is a token, `@@VAR:<name>@@`, that the hoist replaces with the id of
the nearest ancestor declaring `name` — the same scope rule a `Ref.Var` resolves by
(`Renderer.varScopes`), applied one pass earlier, because a signal name or a route is a string
the renderer never parses.

- Spliced into each node's OWN fields with the scope at that node, so a nested declaration
  shadows for its subtree and nothing else. `@@NODE_ID@@`'s subtree-wide splice would give the
  outer declarer to everything below it.
- A surface's content is a scope root (ADR 0033), so a token inside a popup resolves only
  against declarations inside that popup.
- A candidate set's clauses inherit the scope. A READ inside a set stays refused, because the
  reader's id is minted at run time. A token only spells the declarer's id, which is static.
- No declarer is a build error naming the node and the variable, raised by the hoist. The
  unresolved-token check stays as the backstop.

### 2. A guarded write, and the chooser's buttons as nodes

`tapMod.setVar(name, value)` is a `SetVar`: a `Click` with `busy = true`, whose onclick sets
the pending value and posts `sse/var/<slug>/<declarer>/<name>/<value>?group=…`, both through
the declarer token. `tapMod.chosen(name)` is the pending-or-committed expression a highlight
reads.

`WindowChooser` keeps its declaration and its card. Its bar becomes a `bar` region of `tab`
nodes, `TabButton` taking a `SetVar` as well as an `OpenSurface`. Each button then has its own
`_<id>__busy`, so the bar dims and rings like a tab bar with nothing new on the server. The
wire stays byte-identical for the route and the signals.

**A signal reader is not a new slot kind.** The highlight reads `_var_<declarer>__<name>`, which
the server already writes for EVERY declaration — seeded by the shell, restated on every connect,
committed on every write. What the issue asked for (a press that re-renders nothing) is true
already. The missing half was only the name, which step 1 supplies.

### 3. A choice survives its session

The SSE GET includes `^_var_` (`Server.SseInclude`), and a connect reads the committed values
for this build's declarations. A live session's own choices win over them. A minted session —
after a restart, or once lingering ran out — adopts them through `Renderer.refusals`, the check
every other entry passes. The opening frame then restates the bar's own choice instead of
resetting it.

Narrowed by exact signal name per declaration, never by parsing `_var_<d>__<n>`. A declarer id
can itself contain `__` (`s_<sid>__c`).

### 3b. An owned surface inherits its host's scope

A popup stays a scope root, because one content may be opened from many places. A tab panel or
an `If` branch is baked into exactly one node, so it starts with that node's scope. Without this,
a `c.windowChooser` above a `c.tabs` cannot feed the charts in its panels: that is a build error.
`Dashboard.varScopes` is the one rule the renderer and validation read, and the hoist applies the
same rule to declarer tokens. A write still re-renders only the readers this viewer is shown,
pinned by a recorder test: a choice fetches only the visible panel's chart, and the hidden one is
fetched at the new window when opened.

### 4. A tab bar writes a variable

The headline. A `Tabs` declares `tab` (its member index, `"0"` by default), its buttons are
`setVar("tab", i)`, and its panel's bake group is SELECTED by that variable:

- `Surface.activation` gains a third case, `Var(name)`: the group's member is the one whose
  `bakeIndex` equals the variable as seen from the `bakeInto` node. Only `Tabs` bakes a
  `User` group today, so after the switch no bake group is `User`: `defaultOpen` and the bake
  arms of `resolveActive` are deleted in the same change, not left beside `Var`. `User` stays
  for a popup, which has no host of its own to bake into.
- A write whose (declarer, name) selects a bake group swaps the host (`swapHost`, unchanged),
  and the refusal for it is "no member at that index". This is the same reader-parses rule
  `refusals` applies to a query. **The panel must count as a reader**: today `Server.setVar`
  refuses a variable no query reads (`readers.isEmpty`), which would refuse every tab press.
- `resolveActive`, `selectedSurfaces` and `committedSelections` read the `VarEnv` for these
  groups, on **every** path that bakes: the page, a surface fill, an `If` flip's placement
  (ADR 0007's crossing edge) and a reconnect. A nested bar's panel is baked inside its outer
  panel's fill, so a path that misses the env shows the inner default. The commit is
  `_var_<id>__tab`, the URL `v.<id>.tab`, and the pending and clear helpers are the variable
  ones the chooser already uses.
- **Unopened branches stay unrendered.** Selection still decides the open set, and the
  visibility chain (`visibleSurface`) still filters it, so only the source of the choice
  moves. #505's tests pin it with a recorder that counts fetches: side-by-side and nested bars,
  a choice inside a hidden panel, and a tab opened live. They are written against today's
  `ui_`, and step 4 must keep them passing unchanged except for the link spelling.
- Several bars on one page and nested bars need nothing extra: each `Tabs` is its own
  declarer, so its value, signal and URL param are keyed apart, and a nested bar's declaration
  shadows the outer one (step 3b).
- ADRs 0005, 0007, 0025 and 0033 and the arch doc get rewritten in the same change. The
  `PklBuildSuite` snapshots and `UiSmokeSuite`'s `ui.` assertions move with the wire.

**The popup host stays `ui_popups`.** It lives in `theme.chrome`, outside every node, so no
node can declare it. Moving it needs ADR 0033's root declaration first, which is future work
there. After step 4 `uiStateOf` carries exactly one key, and that is the honest marker of
what is left.

#### Open for the maintainer before step 4

Each has a recommendation; none is built.

- **Name the variable `tab` or let the author name it?** Recommended: fixed, as the chooser's
  is, so the declaration and the buttons cannot drift. Naming it would let a button elsewhere
  in the panel switch tabs, which step 1 makes possible; that can come when one is wanted.
- **Index or label as the value?** Recommended: the index. It is what `bakeIndex` and today's
  `ui.` carry, and `tapMod.setVar` refuses a label with a space or a `'`. A label would
  survive reordering the tabs.
- **Old `ui.<id>` links.** Recommended: let them land on the default tab, the treatment ADR
  0033 gives a stale `v.` link. Translating them is cheap, but it is a second spelling to keep.

#### What it moves

It is not a reviewable diff without these, so they are listed rather than discovered:

- **Existing tests, in two groups.** Those that build a `User` bake group in Scala move with
  the model: `BuildPhaseSuite`, `SurfaceGraphSuite`, `RendererSuite`, `LiveStreamSuite`,
  `QueryDriftSuite`, `QueryRenderInputsSuite`, `RenderCacheContentionSuite` and
  `ServerHarness`'s fixtures. Those that read `ui_`/`ui.` off a page move with the wire:
  `ServerRoutesSuite`, `ResumeSuite`, `SetMembershipSuite`, `PklDashboardBehaviourSuite`'s
  tab tests, `UiSmokeSuite`, `DatastarMorphContractSuite`, the tab facts in
  `components.test.pkl`, and the two wire snapshots. Some of the second group assert the
  popup's `ui_popups`, which does not move.
- **`uiStateOf` and its threading** stay for the popup, so every render path keeps its
  `uiState` parameter. Dropping it waits on the popup host.

## Not in this plan

- A **plain-slot** reader of a variable (re-render on write). No component needs one. When one
  does, `readersOf` already re-renders whatever it returns, so it needs a slot shape and nothing
  on the write path.
- A variable read **inside a candidate set**: still refused, for ADR 0033's reason.
- **Root declarations** and the popup host: ADR 0033's future work, and the last step after
  this plan.
