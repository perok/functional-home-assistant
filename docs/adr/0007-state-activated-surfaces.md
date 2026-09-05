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
- a member container doesn't fit: it is a per-entity comprehension, not a gate
  around an authored subtree.

## The decision: reuse the tabs machinery literally

A tabs group already has the exact shape an if/else needs: **one stable host
element, N alternative subtrees registered as surfaces, exactly one baked in,
the inactive ones not rendered and not streamed** (ADR 0002). The tab *bar*,
the active-tab signal, and its URL mirror are authoring-layer composition the
`tabs` card adds — the backend never required them. So an if/else is a bake
group whose member is selected by a **condition** instead of a click:

- **No new `LayoutNode` kind.** An `If` is an ordinary host card (`ifhost`,
  template `<div id="{{id}}">{{{branch}}}</div>`) plus inline surfaces riding
  the generic hoist — branch nodes live in *surface indexes*, never in the
  main page's reverse index, so hidden-branch silence is **structural**
  (inactive surfaces are simply never consulted), not a guard bolted onto the
  patch loop.
- **`Surface.activation` is a sum**, replacing the flat `defaultOpen` flag:
  `User(defaultOpen)` | `State(condition: Predicate)`. The sum
  makes the invalid combination (a default-open flag AND a condition on one
  member) unrepresentable; a bake group must be mode-homogeneous
  (`Dashboard.validate` rejects mixing). The flat wire field is retired — no
  legacy decode; an absent `activation` decodes to `User(false)`, so plain
  popups author nothing new.

### Selection semantics

A state-selected group takes the **first member in `bakeIndex` order whose
condition holds**; no member holding bakes empty content (the host renders its
wrapper empty — the same stable morph target). An `else` branch is just the last
member with an always-true condition — `Predicate.And(Nil)`, vacuously true and
reading nothing; `else if` is one more member in between (the authoring layer
currently expresses that by nesting an `If` in `else`; a flat `.elseIf` needs no
wire change).

**The condition is SUBJECT-FREE**, and that is what removed the quantifier. A
`Predicate` (ADR 0004) normally tests whichever entity supplies its subject — a
set member, a dynamic case — but a surface supplies none. So every comparison in
a state condition names its own entity (`Cmp.entity`) and a `Predicate.Count`
carries its own candidates; `Dashboard.validate` rejects one that does not, with
a located message. Evaluating a condition is then a handful of lookups
(`Renderer.holds` → `matchesIn` against `EntityState.none`, which nothing
reads), and `conditionTouched` is exact rather than a heuristic: a group's
conditions read a known entity set, so a change outside it cannot move the
selection.

The quantifier it replaced was `any`/`none`/`all` over the WHOLE state map, and
those became comparisons on a count over a NAMED set: `any` is `count > 0`,
`none` is `count == 0`, `all` is `count == length`. That is not only cheaper —
it says what an author meant. "Some entity in the house is both `light.x` and
on" was always a circumlocution for "`light.x` is on", and "any light is on" was
never expressible at all without meaning *every* light HA knows about. See
ADR 0003.

### Shared-pass placement (the cache consequence)

Because a state selection is server truth, a state-activated group's selection
is identical for every viewer. `Renderer` splits `bakeOwnerIds` into
`userBakeOwnerIds`/`stateBakeOwnerIds`, and `selectedSurfaces` does not seed
state-activated members into a session's open set — their liveness is the shared
pass's job, not a client's.

That distinction outlived the split it was written for. Everything now rides one
shared per-slug pass (ADR 0002), so the question is no longer *which pass* but
**who a patch is addressed to**: a state surface is TRANSPARENT to the
per-connection filter. It is never in anyone's `open` set, so tagging a node with
one would hide it from everybody; a node inside an `If` branch nested in a tab
panel is tagged with the tab panel instead.

Per state change, the recording pass does two things:

1. **Flips** (`Renderer.affectedStateGroups`, same two-step cost model as
   `dynamicDelta`: O(1) shortcut — the change must touch an entity the group's
   conditions actually name — before the full before/after selection compare): record where the branch went, **prune** the group's
   cache entries, and defer the render. Hidden-branch churn deliberately leaves
   stale cache entries; the flip-prune is what makes that correct.

   The fill itself is `Patches.fillHost` — the same primitive a tab switch and
   a popup open use, since all three evict a host and overwrite what it holds
   (ADR 0012). It arrives as one `Inner` at the host element, not a morph of the
   node: the node's own HTML embeds the branch, so morphing it would weld host
   to content, which is exactly what the leaf/structure split prevents (ADR
   0008).
2. **Active-member liveness** (`Renderer.activeStateSurfaces`, transitive —
   a nested state group contributes only through its active ancestor branch):
   record the active members' affected components and dynamic groups. Inactive
   STATE members are never consulted —
   that IS the no-updates guarantee, and it is structural: their ids never
   enter the selection.

   Structural silence covers state members directly. A *user*-selected surface
   nested inside an inactive branch — a tab panel inside a hidden `If` — needs
   one more step, because `selectedSurfaces` reports a selection for every bake
   group whether or not it is on screen, so `open` alone would keep recording
   it. The recorder therefore filters each session's open set through
   `Renderer.visibleSurface`, the visibility CHAIN (ADR 0012): every user
   surface above a node selected AND every state surface above it active. A
   group in an unopened tab is not merely unsent — it is never planned.

The one crossing edge: a state group whose subtree contains a *user-activated*
bake owner (tabs inside an `If`). Its flip places a branch whose HTML is not one
thing but one thing per selection, so it cannot be rendered once for everyone.
That stopped being a case at all when the session became the thing that renders
(ADR 0012): the flip is RECORDED as a mutation, and whichever session pulls it
performs the placement with its own selections. It arrives as ONE complete patch,
with that viewer's panel already inside it — the same shape the old
render-rather-than-bytes machinery produced, with nothing left to key or defer.

An earlier design instead routed these groups to a per-session pass
(`sessionOnlyStateGroups`). It did not work: the flip rendered the branch with no
client at all, so routing it merely changed which cache it was diffed against,
and every viewer was handed the default tab. The bug outlived the mechanism meant
to prevent it because nothing tested two clients on different tabs across a flip.

### Authoring (Pkl)

`c.iff(cond)` returns an `If` node supporting both a builder and an amend form
(both set the same hidden properties; the derived `inlineSurfaces` is
late-bound, so either path re-derives it). The condition comes from `query.pkl`,
which is what makes it name its entities:

```pkl
c.iff(q.entity(dump.e_alarm).stateIs("armed_away"))          // one entity
  .then(c.title("⚠ Alarm armed"))
  .`else`(c.title("All quiet"))

c.iff(q.from(hass.lights(dump.areas.stue.all))                // a named set
        .where(q.eq(q.stateProp, "on")).any())
  .then(c.title("Someone is up"))

(c.iff(...)) { `then` { c.title("…") c.entityCard(e) } `else` { c.title("…") } }
```

`iff` also accepts a `Boolean`, because the fold can settle a count before
anything runs (`q.from(emptyRoom).any()` is `false`); it becomes an
always/never condition rather than a type error the author has to route around.
`iffNone`/`iffAll` are gone with the quantifiers — `.none()`/`.all()` on the set
say the same thing about the set the author named.

`else` is a Pkl reserved word — backticks at the property, the method, and
every call site (spike-verified; ADR 0006's gotchas). `.then(..)`/`` .`else`(..) `` take
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
- **A flat `defaultOpen` + optional `condition` on `Surface`**:
  representable nonsense (both set at once) and an implicit mode; the
  `Activation` sum states the mode and scopes each mode's fields.

## Consequences

- An inactive branch costs nothing: no render, no patch, no membership scan.
  The flip repaint is the reconciliation point (morph + cache prune).
- Surfaces are the single "conditionally shown subtree" primitive with two
  activation modes: user-triggered selections are per-client truth and decide
  who a patch reaches, state-driven ones are server truth and reach everyone.
  New conditional UI should pick a mode, not a new mechanism.
- Verified by `RendererSuite` (selection/counting/owner split),
  `ServerSuite` (hidden-branch silence, flip morph + prune, nesting, popup
  containment), `BuildPhaseSuite` (activation decode/validate/hoist) and
  `PklBuildSuite` (full-pipeline If entries + the `pkl-if` snapshot).
