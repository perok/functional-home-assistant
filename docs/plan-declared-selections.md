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

The headline, in two PRs so each stays reviewable. Decided with the maintainer: the variable
is named `tab` (fixed, as the chooser's `window` is), it holds the member index, and an old
`ui.<id>` link is not read, so it lands on the declared tab.

**4a — tabs switch.** A `Tabs` declares `tab` (`"0"`), its buttons are `setVar("tab", i)`, and
its panel's surfaces are `Activation.Var("tab")`.

- The panel's bake group is a reader of the variable (`Renderer.groupsSelectedBy`): a write
  swaps the panel this viewer is shown, then commits `_var_<id>__tab`, and `refusals` refuses
  a value that is not a member index. The open route refuses a variable-selected panel.
- Every path that bakes takes its selections from the session's variables
  (`SurfaceGraph.varSelections`, merged at the request's edge by `Server.selectionsOf`): the
  page, an action, a minted session, a connect (after adopting the carried values), a renderer
  swap. The pull reads `session.open`, which the write keeps in step.
- `validate` requires the variable in scope at the bar and a declared member index.
- #505's fetch-counting tests pass with only the link spelling and the tab route changed, so
  unopened branches stay unrendered.

**4b — user-selected bake groups go.** After 4a nothing in the library bakes a `User` group,
so `defaultOpen`, the bake arms of `resolveActive`, `committedSelection`'s bake case and
`uiStateFrom` for user groups are deleted, and `validate` refuses a `User` member with a
`bakeInto`. `User` stays for a popup. The Scala fixtures that build a `User` bake group move to
`Var`: `BuildPhaseSuite`, `SurfaceGraphSuite`, `RendererSuite`, `LiveStreamSuite`,
`QueryDriftSuite`, `QueryRenderInputsSuite`, `RenderCacheContentionSuite` and `ServerHarness`.

**The popup host stays `ui_popups`.** It lives in `theme.chrome`, outside every node, so no
node can declare it. Moving it needs ADR 0033's root declaration first, which is future work
there. `uiStateOf` and the `uiState` parameter every render path takes stay for it.

## Not in this plan

- A **plain-slot** reader of a variable (re-render on write). No component needs one. When one
  does, `readersOf` already re-renders whatever it returns, so it needs a slot shape and nothing
  on the write path.
- A variable read **inside a candidate set**: still refused, for ADR 0033's reason.
- **Root declarations** and the popup host: ADR 0033's future work, and the last step after
  this plan.
