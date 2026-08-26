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
its `self`, and `Renderer.renderIndexed` hands a `self` its children's renderings on purpose.

**A node's OWN baked region cannot reach its own `self`, and that is already structural.** In
`Renderer.traced`:

```scala
val selfVars = structuralVars(id) ++ bakeIndex   // no `baked`
val vars     = selfVars ++ baked                 // the mount part only
```

The `self` template is handed no variable holding the baked member, so there is nothing to render it
with. This is the rule at its strongest and it costs nothing; step 2 keeps it as a declared,
validated fact (`in = "self"` ⟹ `fill = "eager"`) rather than an accident of a var map.

**Everything else — a self-region holding nodes that themselves have baked regions, stacked to any
depth — is a BOOKKEEPING job, not a prohibition.** Two arguments were made for banning it and only
one is real:

- *The render cache.* Not an issue. `renderInputs` returns `None` whenever `ownBytesCarryChildren`
  (today simply `children.nonEmpty`), so a node that composes children is already uncached — the
  same answer ADR 0012 gives for the composed surface mount, "its bytes carry its children, so it
  has no sound key". Composition is handled by opting out of the cache, not by being forbidden.
- *`Session.holds`.* Real, and the reason the ban exists today: `renderNodeById` returns a bare
  `String`, and the content-patch path claims only the node it targeted. A content patch that
  composed a baked region would write DOM that the session's records never hear about, so later
  patches for the nodes inside it would be suppressed or duplicated against stale entries.

That second one is already solved elsewhere, for exactly this shape. `Patches.hostFill` renders a
whole surface subtree and emits a patch that CLAIMS every node it placed (from `Traced.own`) and
INVALIDATES what it displaced:

```scala
Addressed(
  Patch.Insert(t.html, PatchMode.Inner, host),
  t.own.map { case (id, p) => id -> Held(Some(Digest.of(p.html)), p.signals) },
  (renderer.surfaces.surfacesAt(host) ++ arriving).flatMap(renderer.surfaceNodeIds)
)
```

A tab switch and a popup open are that call. The content-patch path needs the same treatment, which
is step 1.

Two things this does NOT license, worth keeping in view:

- Rendering a baked region as an inert empty host inside a fragment, UNMARKED. ADR 0012 rejected
  that under *"a hollow mount plus a per-connection fill"*, and its reason is the one that bites:
  **a mount carries client-dependent ATTRIBUTES, not merely children** — so an unmarked hollow host
  does not merely fail to carry the panel, it overwrites the real host's attributes with a
  placeholder's. Compose it fully, or mark it `data-ignore-morph` (see the re-send note above, and
  spike the `hostFill` interaction first). Never emit a bare empty host.
- Deep composition for free. A content patch that composes a bake host re-sends that host's whole
  baked content even when only the outer node's own slot moved: the fragment is one blob morphed
  onto one element, so the panel's bytes have to be *in* it.

  **Not reachable today, or after step 3** — it needs a `self`-placed region holding a `Tabs` or an
  `iff` host, and the only two self-regions that exist hold leaves by construction (a tab bar holds
  `TabButton`s the card generates; the slider head holds icon buttons). A popup does not count: an
  unbaked surface hosts at the page-level overlay (`Dashboard.PopupHostId`), so a tap that opens one
  never makes its opener a bake host.

  So this is a property to check when declaring a NEW self-region, not a cost this work incurs. If a
  region is chrome, say so — a `holds = "leaves"` declaration on `Region` makes it unrepresentable
  rather than merely unlikely, and costs one validate rule. Leave it open where an author genuinely
  wants containers there.

  **`data-ignore-morph` is the third option, and it is real** — read off the pinned bundle
  (`assets-cache/*-datastar.js`, v1.0.2), in the per-node morph `dt`:

  ```js
  dt=(e,t)=>{ ... if(r.hasAttribute(Re)&&s.hasAttribute(Re))return e; ...   // Re = data-ignore-morph
  ```

  It returns the EXISTING node before any attribute reconciliation, so the element, its attributes
  and its whole subtree are left untouched — and because this is the per-node walk, it applies while
  an ancestor is being morphed, not only when the patch targets that element. The guard is
  **both-sided**: the skip happens only if the node in the DOM *and* the node in the arriving
  fragment both carry the attribute. The published docs state neither the both-sided requirement nor
  that it survives an ancestor morph.

  So a bake host marked `data-ignore-morph` lets an ancestor's fragment carry an EMPTY host and
  disturb nothing: no re-send, and no bookkeeping to do, because the patch writes nothing there.

  **Open risk before designing on it:** `Patches.hostFill` inner-patches that same host. If an
  `Inner` patch reaches `dt` with the host on both sides, the fill would be refused by the same
  guard. It probably does not — under `Inner` the arriving side is the new children rather than a
  copy of the host — but that is inference from the call shape, not something read or measured.
  Spike it against `DatastarMorphContractSuite` (with a control, per that suite's own lesson) before
  the design leans on it.

## Step 1 — trace the content-patch path, and delete the ban

Separable, releasable on its own, and it uses a pattern already in production rather than inventing
one. It is also what lets step 2's regions carry any node at all, instead of needing an
element-type constraint on what a self-region may hold.

- `renderNodeById` gains a traced sibling returning `Traced` rather than `String` — the walk already
  computes per-node bytes (`Traced.own`), so this is a matter of not discarding them on this path.
- The content patch is built like `Patches.hostFill`: `claims` from `t.own`, `invalidates` for the
  surfaces it displaced. `Patches.applied` already knows how to fold both into a session.
- `hasOwnRendering` loses its third clause entirely — the `selvesCarryChildren && children.exists(
  carriesMount)` exclusion — and with it `Templates.selvesCarryChildren` and `carriesMount` go. Its
  doc comment enumerates three shapes that fail it; two survive (a bare container, a candidate-set
  root) and the third is deleted, so rewrite it here.
- `ownBytesCarryChildren` stays as-is for now: opting composed nodes out of the render cache is the
  correct answer, not a workaround, and step 2 only refines *which* children count.

The test that matters is the one that would have caught the original bug rather than the shape of
it: a node whose fragment composes a subtree must leave the session holding a correct digest for
**every** node it wrote, so a later change to any of them is neither suppressed nor re-sent. A
grouped slider nested inside a grouped slider, and a tab inside one, are the two fixtures.

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

Step 1 already deletes `Templates.selvesCarryChildren` — the string grep whose own comment concedes
it is a workaround ("a card cannot be asked to declare it truthfully"). What the second rule adds is
the thing that makes the declaration trustworthy in the first place: a card that says a region is
placed `in = "self"` and then splices it somewhere else is now a build error, not a silent
mismatch. Same shape as the check at `Dashboard.scala:800-805` (a signal slot whose `{{{x__bind}}}`
no part places), so it is an existing pattern rather than a new one.

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
- Region-aware in two places: `renderTemplateOf`'s var map (one section per region rather than one
  `children` list), and `mountId`, which becomes `regionId(nodeId, region)` for the baked region a
  surface names.
- `renderWhole` splices `{{{self}}}` only — the `mounts` template map goes away with the property.
- `traced`'s `selfVars`/`vars` split becomes "a region placed `in = "self"` sees no baked member",
  which is the same fact it enforces today, now derived from the declaration.
- `ownBytesCarryChildren` (the cache opt-out) can narrow from `children.nonEmpty` to "populates a
  region placed in `self`" — today it needlessly un-caches every slider group, whose `self` never
  contains its members. Optional and measurable; leave it if the numbers say it does not matter.

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
- **A region tag on each child.** Keeps ids and traversals untouched, and once step 1 has deleted
  the template grep it is no longer *unsound* — just dishonest: a child would be declaring where its
  parent puts it, with region membership recoverable only by filtering, and `validate` re-checking
  per child what the map holds by construction. Rejected on "types hold truth", not on breakage.
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
