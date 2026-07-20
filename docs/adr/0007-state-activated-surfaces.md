# ADR 0007 — State-activated surfaces: if/else as an activation mode, not a node kind

- **Status:** Accepted
- **Date:** 2026-07-10
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Dashboards need conditional sections: "show the alarm card while armed, a
quiet-state placeholder otherwise" — a **static, author-composed subtree**
gated on live entity state. The requirements that shaped the design:

- the hidden branch must be **not rendered and not live** — its entities'
  churn must produce zero renders and zero SSE patches;
- the choice is **server truth** (a pure function of entity state, identical
  for every viewer), unlike a tab, whose active member is per-client;
- `LayoutNode.Dynamic` doesn't fit: it is a per-entity comprehension, not a
  gate around an authored subtree.

## The decision: reuse the tabs machinery literally

A tabs group already has the exact shape an if/else needs: **one stable host
element, N alternative subtrees registered as surfaces, exactly one baked in,
the inactive ones not rendered and not streamed** (ADR 0002). The tab *bar*,
the active-tab signal, and the cookie are authoring-layer composition the
`tabs` card adds — the backend never required them. So an if/else is a bake
group whose member is selected by a **condition** instead of a click:

- **No new `LayoutNode` kind.** An `If` is an ordinary host card (`ifhost`,
  template `<div id="{{id}}">{{{branch}}}</div>`) plus inline surfaces riding
  the generic hoist — branch nodes live in *surface indexes*, never in the
  main page's reverse index, so hidden-branch silence is **structural**
  (inactive surfaces are simply never consulted), not a guard bolted onto the
  patch loop.
- **`Surface.activation` is a sum**, replacing the flat `defaultOpen` flag:
  `User(defaultOpen)` | `State(condition: Predicate, quantifier)`. The sum
  makes the invalid combination (a default-open flag AND a condition on one
  member) unrepresentable; a bake group must be mode-homogeneous
  (`Dashboard.validate` rejects mixing). The flat wire field is retired — no
  legacy decode; an absent `activation` decodes to `User(false)`, so plain
  popups author nothing new.

### Selection semantics

A state-selected group takes the **first member in `bakeIndex` order whose
quantified condition holds**; no member holding bakes empty content (the host
renders its wrapper empty — the same stable morph target). An `else` branch is
just the last member with an always-true condition; `else if` is one more
member in between (the authoring layer currently expresses that by nesting an
`If` in `else`; a flat `.elseIf` needs no wire change).

A `Predicate` (ADR 0004) tests ONE entity, but a surface's condition must
decide over the whole state map, so `Activation.State` carries a
**quantifier**: `any` (∃ — with an `entity_id` pin, the "entity X is in state
Y" case), `none` (∄ — deliberately its own quantifier: `Not` inside the
condition still quantifies existentially), `all` (∀). `Cmp` gained the
`entity_id` property (`Renderer.matches`) so a condition can pin one entity;
the Pkl helper is `entityIs(id)`.

### Shared-pass placement (the cache consequence)

Because a state selection is server truth, state-activated groups ride the
**shared per-slug pass** (render once per slug, every viewer gets the same
patch) — the opposite placement from user-activated bake owners, which stay
per-session because their HTML bakes the client's cookie-selected member
(ADR 0002). `Renderer` therefore splits `bakeOwnerIds` into
`userBakeOwnerIds`/`stateBakeOwnerIds`, and `selectedSurfaces` no longer seeds
state-activated members into a session's open set — their liveness is the
shared pass's job.

Per state change, the shared pass does two things (`Server.diffPatches` grew a
`flips` leg; mirrored in the per-session pass for state groups nested inside
user-opened surfaces):

1. **Flips** (`Renderer.affectedStateGroups`, same two-step cost model as
   `dynamicDelta`: O(1) shortcut — the changed entity's own match must have
   flipped for some member's condition — before the full before/after
   selection compare): re-render the host (`resolveBake` bakes the newly
   selected member against *current* state), morph it, and **prune** the
   group's cache entries (`bakeMemberPrefixes` — same contract as
   `repaintGroup`), so re-revealed nodes diff from a known base. Hidden-branch
   churn deliberately leaves stale cache entries; the flip-prune is what makes
   that correct.
2. **Active-member liveness** (`Renderer.activeStateSurfaces`, transitive —
   a nested state group contributes only through its active ancestor branch):
   patch the active members' affected components and dynamic groups against
   the shared per-slug cache. Inactive members are never consulted — that IS
   the no-updates guarantee.

The one crossing edge: a state group whose subtree contains a *user-activated*
bake owner (tabs inside an If) — its flip must bake the session's
cookie-selected tab, so those groups (`Renderer.sessionOnlyStateGroups`) are
excluded from the shared flip path and handled per session.

### Authoring (Pkl)

`c.iff(cond)` / `c.iffNone(cond)` / `c.iffAll(cond)` return an `If` node
supporting both a builder and an amend form (both set the same hidden
properties; the derived `inlineSurfaces` is late-bound, so either path
re-derives it):

```pkl
c.iff(c.entityIs("alarm_control_panel.home").and(c.stateIs("armed_away")))
  .then(c.title("⚠ Alarm armed"))
  .`else`(c.title("All quiet"))

(c.iff(...)) { `then` { c.title("…") c.entityCard(e) } `else` { c.title("…") } }
```

`else` is a Pkl reserved word — backticks at the property, the method, and
every call site (verified; see the then/else spike in
`docs/plan-pkl-authoring-ergonomics.md`). `.then(..)`/`` .`else`(..) `` take
`LayoutNode|Listing<LayoutNode>` (Pkl has no default method parameters; a bare
`new {…}` cannot infer `Listing` from the parameter type, so multi-child
branches pass `new Listing {…}` or use the amend form). Both members emit
`bakeAs = "branch"` — `resolveBake` reads the first member's `bakeAs`, so one
group shares one bake var. Demo entry: `pkl-if.pkl`.

## Rejected along the way

- **A dedicated `LayoutNode.If`** (quantified condition + one children array
  with a then/else split index): workable, and the split-index trick kept the
  generic hoist/pathId untouched — but it duplicated the surface concept
  (lazily-activated subtree, stable host, bake-on-select) as a second
  mechanism, needed a per-node ancestor-guard map to silence hidden children,
  and left branch nodes in the main reverse index to be filtered at patch
  time. The surface reuse gets silence structurally and inherits hoist,
  baking, and id namespacing for free.
- **Riding the user-surface session machinery** (a condition as a synthetic
  "click"): renders every branch once per connection and turns a global state
  flip into a walk over every session's open set — contorting per-client
  machinery to carry server truth.
- **A flat `defaultOpen` + optional `condition`/`quantifier` on `Surface`**:
  representable nonsense (both set at once) and an implicit mode; the
  `Activation` sum states the mode and scopes each mode's fields.

## Consequences

- An inactive branch costs nothing: no render, no patch, no membership scan.
  The flip repaint is the reconciliation point (morph + cache prune).
- Surfaces are now the single "conditionally shown subtree" primitive with two
  activation modes; anything user-triggered stays per-session, anything
  state-driven is shared. New conditional UI should pick a mode, not a new
  mechanism.
- Verified by `RendererSuite` (selection/quantifiers/owner split),
  `ServerSuite` (hidden-branch silence, flip morph + prune, nesting, popup
  containment), `BuildPhaseSuite` (activation decode/validate/hoist) and
  `PklBuildSuite` (full-pipeline If entries + the `pkl-if` snapshot).
