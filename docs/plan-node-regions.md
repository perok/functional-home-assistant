# Plan: named child regions on a node (fh-datastar-view)

> Status: direction picked, pre-implementation. Closes [#151](https://github.com/perok/functional-home-assistant/issues/151)
> and answers the Open question in [ADR 0012](adr/0012-each-session-renders-what-it-is-owed.md),
> which is rewritten in place rather than superseded by a new ADR.
> Delete this file when the work lands.

## Why

A node has ONE `children` list, and every markup part of its card is handed that same list — so a
container spends it on exactly one hole:

| card | `self` (the patch fragment) | `mount` |
|---|---|---|
| `Tabs` (`components/surface.pkl`) | `{{#children}}` — the tab bar | `{{{panel}}}`, filled per viewer |
| `Slider` (`components/slider.pkl`) | splices nothing | `{{#children}}` — the member rows |
| `Row`/`Column`/`Grid`/popup | — | `{{#children}}` |

Nothing supports a card placing children in more than one of its parts. Issue 151 is the first card
that needs to: the slider head's single hardcoded `mdi-power` button becomes a list of author-supplied
action nodes, so the guard, indicator, `fh-disabled`/`fh-loading` and delayed-spinner attributes key
on each action's own id instead of borrowing the slider's `_<id>__busy`. That is what stops one
action's `finished` from clearing another's in-flight busy. The head is the slider's `self`; the
members are its `mount`.

ADR 0012 records the question this raises:

> **Should a `self` splice children at all?** … What would decide it: whether any card ever wants
> AUTHOR-supplied children inside a `self`. Until then the rule is checked, not trusted.

**This plan answers yes**, and pays for the answer by making the guard exact rather than
conservative.

## The mechanism is regions, not head actions

The slider is the first consumer, not the shape of the design. A region mechanism that only handled
eagerly-rendered chrome would leave `mount` as a parallel special case and would have to be widened
the first time a card wanted a lazily-selected region — so it is general from the start:

**A region declares WHERE it is placed and HOW it is filled.**

- `fill = "eager"` — the node's own children, composed into the card's rendering. A row's children,
  a slider's member rows, a tab BAR's buttons, a slider head's actions.
- `fill = "baked"` — a hole some surface fills per viewer, lazily, as its own operation. A tab
  PANEL, a popup, an `If` branch.

With that, `mount` stops being a word. It exists today to carry two unrelated facts at once — "not
in the patch fragment" and "the bake host" — which is why `Row` and `Tabs` both have one while
meaning entirely different things by it.

## What `self` is, since the rest depends on it

`self` answers exactly one question: **when this node's entity moves, which bytes get replaced?**
Everything else follows — `patchTargetId` returns `<id>-self`, that element is the fragment on the
wire, and it is what `Session.holds` digests.

A fragment may compose other NODES. `Tabs` already does — its buttons are children rendered inside
its `self`, and `Renderer.renderIndexed` hands a `self` its children's renderings on purpose. What a
fragment may not contain is a BAKED region, and the reasons survive every simplification below:

- `RenderCache` keys on `RenderInputs`, whose selection half is `activeBakeIndex(id, …)` — the
  node's OWN bake group. Bytes carrying a descendant's baked region vary by a selection the key does
  not name, so two viewers on different tabs get each other's bytes.
- `Session.holds` is a node-to-digest map. A fragment that overwrites a region owned by other
  addressable nodes leaves their entries describing bytes that are no longer in that DOM, and the
  surface flip machinery — which records structure, not content (§4 of the pipeline map) — is a
  second writer to the same region with no knowledge of the first.

Rendering the baked region as an inert empty host inside the fragment does not rescue it. ADR 0012
already rejected that under *"a hollow mount plus a per-connection fill"*, and the reason is the part
worth keeping: **a mount carries client-dependent ATTRIBUTES, not merely children.**

So the constraint stays. What changes is that it becomes a declared fact rather than a guess.

## Step 1 — make the guard exact (`carriesBakeHost`)

Separable, releasable on its own, and no new concepts — it uses machinery that already exists.

```scala
private def carriesMount(node: LayoutNode): Boolean = node match {
  case c: LayoutNode.Component =>
    templates.mounts.contains(c.card) || c.children.exists(carriesMount)
```

`templates.mounts.contains(card)` is true for any card with a mount TEMPLATE — `Row`, `Column` and
`Grid` included, whose mounts hold plain nested children and nothing client-dependent.
`Renderer.mountId`'s own comment concedes it: those mounts "are never fill targets … and simply
never use" their id.

Replace it with the predicate the invariant names: does this subtree contain a mount some surface
BAKES INTO? `SurfaceGraph.bakeGroup(id)` answers that per node id today, and `Index.walk` already
carries the paths that produce ids, so the recursion threads an id rather than only a node.

This lifts an existing over-restriction — a container whose `self` splices children stops being
disqualified by a member that merely *has* a mount — and it is what lets step 2's regions carry any
node at all rather than needing an element-type constraint.

`hasOwnRendering`'s doc comment enumerates the three shapes that fail it; rewrite it here rather than
leave it describing the old predicate.

## Step 2 — regions

### Pkl

```pkl
class Region {
  /// Which markup part places this region's hole: the card's `template`, or its
  /// `self` (the patch fragment). A BAKED region may not be placed in `self`.
  in: "template"|"self" = "template"
  /// eager — the node's own children, composed with the card.
  /// baked  — a hole a surface fills per viewer, lazily, as its own operation.
  fill: "eager"|"baked" = "eager"
}

class CardDef {
  template: String
  /// The patch fragment, spliced into `template` at `{{{self}}}`.
  self: String? = null
  regions: Mapping<String, Region> = new {}
  slots: Listing<String> = new {}
  css: String = ""
  wrapAsCell: Boolean? = null
}

abstract class Node extends LayoutNode {
  /// Child nodes BY REGION. Eager regions only — a baked region's contents are
  /// surfaces, not children.
  children: Mapping<String, Listing<LayoutNode>> = new {}
}
```

`ContainerCard` and `LeafCard` merge into `CardDef`: a leaf is a card with no regions, and the
`mount` property disappears. A card that used to need a separate `mount` part now writes that markup
in `template` directly, because the constraint it existed to enforce is now the declaration
`in = "self"` ⟹ `fill = "eager"`.

Naming: the card declares `regions`; the node's `children` is a map keyed by region name. One
concept, two roles. (Sketched originally as `parts` — say if you prefer that for the node side.)

### Model + validate

`LayoutNode.Component.children: List[LayoutNode]` becomes
`Map[String, List[LayoutNode]]`. `Dashboard.validate` gains:

- every region a node populates is one its card declares, and declares `eager` (an unknown or baked
  region would render nowhere, silently);
- for each declared region, the part named by `in` splices its hole and no other part does;
- `in = "self"` implies `fill = "eager"`;
- region names are plain tokens (`Dashboard.sanitize` maps everything outside `[A-Za-z0-9_]` to `_`,
  and region names now enter node ids — same rule cell classes already get).

The second rule is what retires the runtime grep in `Templates`:

```scala
selvesCarryChildren = dashboard.cards.collect {
  case (name, cd) if cd.self.exists(_.contains("{{#children}}")) => name
}.toSet
```

whose own comment explains itself as a workaround — "a card cannot be asked to declare it
truthfully". With `regions` as the declaration and a build-time assertion holding the templates to
it, it can. The check is the same shape as the one at `Dashboard.scala:800-805` (a signal slot whose
`{{{x__bind}}}` no part places), so this is an existing pattern rather than a new one.

### Node ids — full symmetry, and it breaks tab-state URLs

`LayoutNode.pathId` is a flat `List[Int]` (`path.mkString("c_", "_", "")`). Every child segment
becomes region-qualified, the default region included: `c_2_children_0`, `c_2_headActions_0`. One
grammar, no privileged region.

**Accepted breakage:** node ids are mirrored into the URL for tab state
(`fhUrl('ui.{{id}}', $ui_{{id}})`), so every bookmarked or shared URL carrying a tab selection stops
resolving. Deliberate — a second id grammar to protect them is the worse trade while the design is
pre-v1. `path: List[Int]` becomes a list of `(region, index)`.

### Renderer

- `Index.walk`, `MemberGraph`, the `danglingBakes` walk and the other traversals take
  `children.values.flatten` — mechanical and region-blind, since they only need every node.
- Region-aware in three places: `renderTemplateOf`'s var map (one section per region rather than one
  `children` list); the step-1 guard, which now asks only about regions the card places `in = "self"`;
  and `mountId`, which becomes `regionId(nodeId, region)` for the baked region a surface names.
- `renderWhole` splices `{{{self}}}` only — the `mounts` template map goes away with the property.
- `ownBytesCarryChildren` (the cache opt-out) becomes "populates a region placed in `self`". Today it
  is `children.nonEmpty`, which needlessly un-caches every slider group, whose `self` never contains
  its members.

### Wire format

`dashboard.json` is generated, never tracked, so there is no migration — but `children` changing
shape is a breaking decoder change, and every suite fixture that hand-writes a node moves with it.

## Step 3 — the slider head, the first consumer

- `Slider` declares a `headActions` region with `in = "self"`; the head grid's last column becomes a
  `.slider-actions` flex row carrying `pointer-events:auto` (the rule currently on `.slider-action`,
  which stops being one element's business).
- A new leaf card for the action itself — a circle icon button with the `lit` signal binding. `c.pill`
  is the wrong reuse: it is a BeerCSS `.chip` with a label and a hardcoded `fh-hug` cell, with no icon
  and no `lit`. It is right about the busy wiring, which the new card copies.
- `tapAction` on the slider becomes sugar for a single-element `headActions` list, and the hardcoded
  `{{#onclick}}…mdi-power…{{/onclick}}` fragment is deleted — one mechanism, not two.
  **Behaviour change for the commit message:** that action's busy signal moves from the slider's
  `_<id>__busy` to the action node's own. That is the point of the issue, but it is not a no-op.

## Step 4 — ADR 0012

Rewrite its **Open** section in place: the question is answered, and the answer belongs where the
question was asked. Its *"Rejected along the way"* entry for the hollow mount stays — it is still
guarding the design, and this plan leans on it. The pipeline map
(`docs/architecture-rendering-pipeline.md`) moves in the same commits: §5's scope table and the
`self`/`mount` vocabulary throughout.

## Sequencing

Steps 1–4 land as separate commits in that order; step 1 is releasable on its own. Delete this file
in the same PR as step 3.

## Rejected

- **`headCount` — a leading-N split of one list.** Smallest possible diff, and a hack: it uses
  `List[LayoutNode]` as an ad-hoc two-region protocol with the boundary carried alongside as an
  integer, adding a fourth implicit thing instead of removing any of the three.
- **A region tag on each child.** Keeps ids and traversals untouched, but the tag lives in the data
  while `selvesCarryChildren` reads the template SOURCE, so the grep cannot see the filter and the
  slider joins the set — costing its head live patches as soon as a member carries a mount. Viable
  only on top of steps 1 and 2, at which point the map is the honest type.
- **Split the slider card** into a bare container holding a `SliderHead` node with the members as
  siblings, actions being ordinary children of the head. Needs no renderer change at all — but trades
  away a documented authoring property ("give a slider children and it is the same card, a group":
  ADR 0006, the class doc, a `PklBuildSuite` test), and answers nothing general, so the next card
  wanting a header control row arrives back here.
- **Everything through mounts** — ADR 0012's other branch, which would make the rule "unnecessary
  rather than merely satisfied". It forces `Tabs`' `self` to become a hollow signals-only element
  beside a `.tabs` mount, because its buttons sit inside the element carrying `{{selfId}}`. Regions
  subsume it instead: the same card expresses one eager region and one baked one without either
  becoming hollow.

### Prior art

`docs/plan-mount-unification.md` (June 2026; landed, then superseded by the self/mount card split)
rejected "named children groups" under the heading *"children is a slot"*. Its reason was that named
children would render every tab PANEL eagerly and hide the inactive ones, defeating lazy surfaces.
That objection is answered here rather than sidestepped: a region declares `fill`, so a panel stays a
lazily-activated surface and only the open one renders and streams. The rejection was of a mechanism
that could express eager regions only — which is the mechanism this plan is careful not to build.
