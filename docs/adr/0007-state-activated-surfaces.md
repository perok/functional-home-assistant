# ADR 0007 — If/else: a pick over state, not a node kind

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
element, N alternative subtrees as the cases of one pick, exactly one rendered in,
the inactive ones not rendered and not streamed** (ADR 0002). The tab *bar*
and its URL mirror are authoring-layer composition the `tabs` card adds, and
its selection is a node variable (ADR 0033). So an if/else is a choice whose
case is picked by a **condition** instead of a viewer's value:

- **No new `LayoutNode` kind.** An `If` is an ordinary card (`ifhost`, root
  class `fh-ifhost`, template `{{{branch}}}`, so its root hosts the branch and
  `.fh-ifhost:empty` collapses it when no branch holds). Each branch is a node
  of the tree, and the index records the case holding every node below it, so
  hidden-branch silence is **structural**: a node in a case nobody is shown is
  never in what a tick patches or a page fetches (`PickGraph`), not a guard
  bolted onto the patch loop.
- **One pick decides a choice.** A choice's cases are nodes, and one **pick**
  decides them all (`Ready.Pick`): `by` read once, its case looked up. A popup
  is such a case too, of the page's `popups` variable (ADR 0002), so there is
  no second kind of conditional subtree. One choice can read a variable and state
  together, since `by` is one expression: it is decided per viewer, at the
  values that viewer holds (below).

### Selection semantics

A pick over state reads `by` once per version and takes the **case keyed by its
value**, else the `otherwise`; neither shows nothing (the host renders its
wrapper empty — the same stable morph target). A chain `iff(p).elif(q)` is
`by = List(Bool, [p, q]) |> IndexOfTrue`, the index of the first condition
holding, with cases `0..n`, so the first to hold wins; `else` is the
`otherwise`.

**The condition is SUBJECT-FREE**, and that is what removed the quantifier. A
condition (ADR 0004) normally reads whichever entity supplies its subject — a
set member, a card — but an `If` supplies none. So every read in a state
condition names its own entity (`Value.Entity.of`) and a count carries its own
candidates (`Value.Present`); `Dashboard.validate` rejects one that does not
(`ValueExpr.readsMissingSubject`), with a located message. Evaluating a
condition is then a handful of lookups (`PickGraph.decideByState` runs the
compiled `by` with no subject, which nothing reads), and the index of what each
pick reads is exact rather than a heuristic: its `by` reads a known entity set,
so a change outside it cannot move the selection.

The quantifier it replaced was `any`/`none`/`all` over the WHOLE state map, and
those became comparisons on a count over a NAMED set: `any` is `count > 0`,
`none` is `count == 0`, `all` is `count == length`. That is not only cheaper —
it says what an author meant. "Some entity in the house is both `light.x` and
on" was always a circumlocution for "`light.x` is on", and "any light is on" was
never expressible at all without meaning *every* light HA knows about. See
ADR 0003.

### Shared-pass placement (the cache consequence)

Because a state selection is server truth, a pick over state is identical for
every viewer. `PickGraph` splits its picks three ways: `statePicks` (state
alone), `varPicks` (the plain variable, a lookup of the viewer's value) and
`viewerPicks` (anything else reading a variable, alone or with state). A
state pick's liveness is the shared pass's job, a variable pick's the viewer's
values (`PickGraph.chosen`).

A viewer pick is the one place a pick is both: its `by` reads state and the
viewer's variables, so the case is made per viewer, at that viewer's values
(`PickGraph.decideByViewer`, kept per values by `Shared.branchAt`, ADR 0033),
and the `If` is a flip for exactly the viewers whose case it moves. A value no
case takes, with no `else`, shows nothing: only the plain variable's declared
value is held to a case, since a computed `by` has no such value to check.

That distinction outlived the split it was written for. Everything now rides one
shared per-slug pass (ADR 0002), so the question is no longer *which pass* but
**who a patch is addressed to**: a state pick's case is a container like any
other, shown to everyone while its pick holds it, so the per-connection filter
asks the visibility chain (below) of it as of a tab panel.

Per state change, the recording pass does two things:

1. **Flips** (`PickGraph.affectedStateGroups`): found from the changed
   entities, through an index of what each pick's `by` reads, so a frame
   that touches no condition costs a lookup per entity however many `If`s the
   page holds. A pick flips when its case differs before and after and it
   is shown both times: its root visible past no other pick flipping
   (`PickGraph.visibleContainer`), and, inside a set member, the member
   present in that clause. A member arriving or switching clause is
   placed whole with the branch its state picks, and a pick below another that
   flips is inside that one's fill, so neither is a flip. Record where the
   branch went, **prune** the pick's cache entries, and defer the render.
   Hidden-branch churn deliberately leaves stale cache entries; the flip-prune
   is what makes that correct.

   A viewer pick flips per values (`PickGraph.affectedViewerGroups`): the
   same test, once per distinct values live sessions hold, each against the
   containers on screen for the sessions holding them. It is a flip if it moves
   for some, logged by its host and the case arriving for each; for the others
   nothing is logged, and it cuts no one's visibility, since a node under its
   case still ticks for a viewer it did not move.

   The fill itself is a `Patch.Fill` of the host — what a tab switch and a
   popup open send too, since each is a pick changing case (ADR 0012). It arrives as one `Inner` at the host element, not a morph of the
   node: the node's own HTML embeds the branch, so morphing it would weld host
   to content, which is exactly what the leaf/structure split prevents (ADR
   0008).
2. **Active-member liveness** (`PickGraph.visibleContainer`, per container
   binding a changed entity): a container contributes its affected components and
   sets only if it is visible and no pick above it is flipping. A set member's
   bindings and a set's moves are asked the same of the case they sit in,
   so a frame asks only what reads a changed entity, never every shown
   case. Page open asks it of every case (`shownCases`), and a
   member's placement renders the branch its state picks.
   Inactive STATE members are never consulted — that IS the no-updates
   guarantee, and it is structural: their ids never enter the selection.

   Structural silence covers state members directly. A variable's case
   nested inside an inactive branch — a tab panel inside a hidden `If` — needs
   one more step, because `PickGraph.chosen` names the case a viewer's value
   picks whether or not it is on screen. The recorder therefore asks
   `PickGraph.onScreen` (`visibleContainer` per container), the visibility
   CHAIN (ADR 0012): every pick above a node holding the case it sits in, and
   inside a set member, the member in that clause. It is the one test of
   "shown": the flips, the liveness above and page open all ask it. A
   pick in an unopened tab is not merely unsent — it is never planned.

The one crossing edge: a pick over state whose case holds a pick over a
variable (tabs inside an `If`). Its flip places a branch whose HTML is not one
thing but one thing per selection, so it cannot be rendered once for everyone.
That stopped being a case at all when the session became the thing that renders
(ADR 0012): the flip is LOGGED as its host and arriving case, and whichever session pulls it
performs the placement with its own values. It arrives as ONE complete patch,
with that viewer's panel already inside it — the same shape the old
render-rather-than-bytes machinery produced, with nothing left to key or defer.

An earlier design instead routed these choices to a per-session pass
(`sessionOnlyStateGroups`). It did not work: the flip rendered the branch with no
client at all, so routing it merely changed which cache it was diffed against,
and every viewer was handed the default tab. The bug outlived the mechanism meant
to prevent it because nothing tested two clients on different tabs across a flip.

### Authoring (Pkl)

A choice is the fill of a nodes slot (`slot.of(nodeMod.iff(cond)…)`,
`slot.of(nodeMod.match(v.value, cases))`, `core/choice.pkl`), and the node whose
slot it fills writes it on the wire as `Fill.Nodes(Choice.Chosen)`: a `by`, a
case per key holding one node, and an `otherwise`. Each case is a node with the
id a fixed node at its index of the slot would have (`<host>_<slot>_<i>`, the
default slot's `<host>_<i>`), the otherwise after the cases (`Dashboard.picks`),
hosted at the card's root: a choice is its card's whole
template, which the build checks, and one beside other content goes in a
`group` (`core/group.pkl`). A pick over a
variable looks the viewer's value up among the keys; one over state reads `by`
and looks its value up. `c.iff(cond)` returns an
`If` node, which is such a card and supports both a builder and an amend form
(both set the same hidden properties; the choice is late-bound, so either path
re-derives it). The condition
comes from `query.pkl`, which is what makes it name its entities:

```pkl
c.iff(q.entity(dump.e_alarm).stateIs("armed_away"))          // one entity
  .then(c.title("⚠ Alarm armed"))
  .`else`(c.title("All quiet"))

c.iff(q.from(hass.lights(dump.areas.stue.all))                // a named set
        .where(q.eq(q.stateProp, "on")).any())
  .then(c.title("Someone is up"))

(c.iff(...)) { `then` { c.title("…") c.entityCard(e) } `else` { c.title("…") } }

c.iff(p).then(a).elif(q).then(b).`else`(x)                     // first to hold wins
```

Each step returns `this`, `.then` filling the branch last begun. A chain of
conditions is `by = List(Bool, [p, q]) |> IndexOfTrue` with cases `0..n`, a
lone one `by = p` with the case `true`, and the `else` the `otherwise` (none
while empty, over state). A chain whose conditions all read one variable
(`valueIs`) is a match on the variable's value, one case per value; one mixing a
variable with live state, or reading two variables, lowers to the same
first-true position, decided per viewer, and its empty `else` is no case. A
node holds one choice, and a case is one node (several sit in a row).

`iff` also takes a node variable at one value, `c.iff(window.valueIs("30d"))`
(#510). Then the pick is per viewer like a `c.select`'s panels, so the branch is
each viewer's own and no flip is involved: `then` is the case at the value, and
`else` the otherwise. The otherwise is always there, empty without an `else`, so
the If shows something at every value. `valueIs` is not a predicate, so one
condition cannot combine it with entity state, but a chain may take one
`elif` of each (`c.iff(mode.valueIs("a")).then(x).elif(q.entity(e).stateIs("on")).then(y)`):
that is a viewer pick, and a state change moving the case for some viewers flips it
for those alone (`Patches.plan`'s viewer flips).

A choice over a node variable that is not an `If` is `nodeMod.match(v.value,
cases)`: one case per key (`t0`, `t1`, … in the cases' order) and
`.otherwise(x)` at every other value; without one, a value no case takes shows
nothing, and `validate` refuses a declared value no case takes. Tabs, a
select's cycle and dropdown label, and a slider's main controls are such
matches. A key holding several nodes puts them in a row; one node is its own
case.

`iff` also accepts a `Boolean`, because the fold can settle a count before
anything runs (`q.from(emptyRoom).any()` is `false`); it becomes an
always/never condition rather than a type error the author has to route around.
`iffNone`/`iffAll` are gone with the quantifiers — `.none()`/`.all()` on the set
say the same thing about the set the author named.

`else` is a Pkl reserved word — backticks at the property, the method, and
every call site (spike-verified; ADR 0006's gotchas). `.then(..)`/`` .`else`(..) `` take
`LayoutNode|Listing<LayoutNode>` (Pkl has no default method parameters, and a
bare `new {…}` cannot instantiate a union, even on 0.33, so multi-child
branches pass `new Listing {…}` or use the amend form). Demo entry:
`pkl-if.pkl`.

## Rejected along the way

- **A dedicated `LayoutNode.If`** (quantified condition + one children array
  with a then/else split index): workable, but it duplicated the pick
  (lazily rendered subtree, stable host, render on select) as a second
  mechanism, needed a per-node ancestor-guard map to silence hidden children,
  and left branch nodes in the main reverse index to be filtered at patch
  time. Reusing the pick gets silence structurally and inherits lazy
  rendering and the case ids for free.
- **Riding the per-session selection machinery** (a condition as a synthetic
  "click"): renders every branch once per connection and turns a global state
  flip into a walk over every session's open set — contorting per-client
  machinery to carry server truth.
- **A flat `defaultOpen` + optional `condition` per subtree**:
  representable nonsense (both set at once) and an implicit mode. So was the
  activation sum that replaced it (`User | Var | State` per member, with a
  `bakeIndex` order), once a choice became one `by` with keyed cases: a
  member-by-member activation could disagree with its siblings, and deciding
  ran each member's condition where one read of `by` does.

## Consequences

- An inactive branch costs nothing: no render, no patch, no membership scan.
  The flip repaint is the reconciliation point (morph + cache prune).
- The pick is the single "conditionally shown subtree" primitive, decided two
  ways: by a viewer's values (a tab, a popup), which are per-client truth and
  decide who a patch reaches, or by state, which is server truth and reaches
  everyone. New conditional UI should pick one, not a new mechanism.
- Verified by `RendererSuite` (selection/counting/owner split),
  `ServerSuite` (hidden-branch silence, flip morph + prune, nesting, popup
  containment), `BuildPhaseSuite` (choice decode/validate) and
  `PklBuildSuite` (full-pipeline If entries + the `pkl-if` snapshot).
