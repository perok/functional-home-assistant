# Plan: named regions on a node, and the end of `self` (fh-datastar-view)

> Status: direction picked, pre-implementation. Closes [#151](https://github.com/perok/functional-home-assistant/issues/151)
> and answers the Open question in [ADR 0012](adr/0012-each-session-renders-what-it-is-owed.md),
> which is rewritten in place rather than superseded by a new ADR.
> Delete this file when the work lands.

## Why

A node has ONE `children` list, and every markup part of its card is handed that same list — so a
container spends it on exactly one hole. Issue 151 is the first card that needs two: the slider
head's single hardcoded `mdi-power` button becomes a list of author-supplied action nodes, so the
guard, indicator, `fh-disabled`/`fh-loading` and delayed-spinner attributes key on each action's own
id instead of borrowing the slider's `_<id>__busy`. That is what stops one action's `finished` from
clearing another's in-flight busy.

Named regions are the mechanism. Working out where they may be placed turned out to answer a larger
question, so this plan does two things: it adds regions, and it deletes `self`.

## The invariant

> **Every hole in a card's template is disjoint from every other hole.**

No hole nests inside another. Not a hole inside `self`, and not a hole inside another region — the
second case matters the moment regions are plural, because an outer region's refill would carry the
inner one's bytes exactly as a `self` patch would.

Stated about the template, it is checkable at build with no runtime knowledge at all. That is the
whole point: it replaces `Templates.selvesCarryChildren`, a `String.contains("{{#children}}")` grep
whose own comment concedes it is a workaround ("a card cannot be asked to declare it truthfully").

What it buys is that **a node's patch can never carry another node's bytes**. Not policed, not
opted out of — unrepresentable. Every design in this area that tried to make the composing case
*safe* (see Rejected) was working around a shape the invariant simply removes.

## What `self` was, and why it goes

`self` exists for exactly one reason, and `Renderer.patchTargetId` says so:

```scala
def patchTargetId(id: NodeId): DomId =
  allIndexed.get(id) match {
    case Some((c: LayoutNode.Component, _, _)) if hasSelf(c.card) =>
      Renderer.selfElementId(id)      // <id>-self
    case _ => elementId(id)           // the .fh-cell wrapper
  }
```

> *"A container declaring a `self` targets `<id>-self`, so its patch cannot reach the sibling mount
> holding its children."*

That is the entire job: give a card that has regions a patch target excluding them. A leaf card
needs no such thing — its whole template is its fragment, and the `.fh-cell` wrapper is the target.

So under the invariant, `self` is precisely *"the leaf-shaped part of a card that also has regions."*
Which can be a node in a region instead, with its own cell as its own target — and then the concept
is gone.

### The rule that replaces it

> **A card is a LEAF (no regions — its template is its patch fragment) or STRUCTURE (regions — never
> a patch target). A structural card's own markup changes only through signal slots.**

The last clause is the escape hatch, and it is the mechanism this project already has for it: ADR
0017 signal slots push a value without re-rendering anything, so a structural element can carry a
live class or label without ever being patched. A structural card wanting *markup* to change puts
that markup in a region, as a node.

### The library says this costs almost nothing

Every card in `lib/`, by which parts it declares:

| card | declares | under the rule |
|---|---|---|
| `EntityCard`, `Button`, `Toggle`, `Pill` | `template` only | leaf — unchanged, `self` was never involved |
| `Row`, `Column`, `Grid`, popup | `mount` only | structure — `mount` renamed to a region |
| `If` (`ifhost`) | `mount` only | structure — one baked region |
| `Tabs` | `self` + `mount` | structure — `self` deleted, see below |
| `Slider` | `self` + `mount` | structure — head becomes a node, see below |

**Two cards in the whole library have a `self`, and both also have a region.** Every other card is
already a pure leaf or pure structure. There is no third shape to migrate.

**Tabs** costs nothing. Its `self` is the `.tabs` bar, and it wraps `{{#children}}` (the buttons)
because BeerCSS styles tabs with the structural selector `.tabs > a`. But the card's own comment
records that *"the bar carries no live value today"* — so the bar is structure:

```pkl
template = <div class="fh-col">
             <div class="tabs" data-signals__ifmissing="{ ui_{{id}}: {{bakeIndex}} }" …>
               {{#buttons}}{{{html}}}{{/buttons}}
             </div>
             <div id="{{regionId_panel}}" class="tab-panel" …>{{{panel}}}</div>
           </div>
```

The template is rendered with `vars`, which includes `bakeIndex` (`Renderer.traced`), so the signal
seed comes along unchanged, and the buttons stay direct children of `.tabs`. A *live* tab-bar header
— the "tab bar with the current temperature" that `RendererSuite`'s `tabsLive` fixture exists for —
is a leaf node in a third region beside the buttons. Note that `tabsLive`'s `self` already contains
no hole, so that fixture already satisfies the invariant.

**Slider** is the one real cost. Its `self` is the head row: label, fill bar, input, toggle, readout
— one indivisible chunk of markup driven by one entity. It becomes a `SliderHead` leaf node in a
`head` region, constructed by the `Slider` class itself, never written by an author. The bill:

- **One extra node per slider** — an id, a changelog entry, a `Session.holds` entry.
- Its `.fh-cell` wrapper must be `display:contents` so the slider's grid is unaffected. Precedent
  in the same card family: `.tab-panel{display:contents}`, for the same reason.
- It cannot use `wrapAsCell = false`: `Dashboard.validate` rejects an unwrapped card that binds live
  entities, because *"an unwrapped node has no morph target"*. The cell IS the target here, which is
  the whole mechanism.

This is **not** the "split the slider card" option rejected below. The author still writes
`c.slider(light).withSubSliders(rows)` and still gets one card; the head is internal.

### What gets deleted

`Templates.selves`, `Templates.mounts`, `Templates.selvesCarryChildren`, `Renderer.hasSelf`,
`selfElementId`, `patchTargetId` (collapses into `elementId`), `mountId` (becomes
`regionId(id, region)`), `hasOwnRendering`, `carriesMount`, `ownBytesCarryChildren`, the
`selfVars`/`vars` split in `traced`, and the `compose`/`selfOnly` two-form split — a patch is always
one node's whole template.

Two consequences worth naming:

- **Every leaf is cacheable.** `ownBytesCarryChildren` is `children.nonEmpty`, a conservative proxy
  for "my bytes carry my kids". Under the rule a patched node has no kids in its bytes, ever, so the
  opt-out has nothing to opt out. A slider group is uncached today; it stops being.
- **Open question #130 loses its first obstacle.** Its wording is *"what a parent EMBEDS is not what
  a patch carries (for a `self` card the cache holds the `self` element alone)"*. Under the rule
  what a parent embeds is a composition of separately-cached leaves, which is the same thing.

## Step 1 — regions

### Pkl

```pkl
class Region {
  /// eager — the node's own children, composed with the card.
  /// baked  — a hole a surface fills per viewer, lazily, as its own operation.
  fill: "eager"|"baked" = "eager"
}

class CardDef {
  template: String
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

`ContainerCard` and `LeafCard` merge into `CardDef`; `self` and `mount` both disappear as properties.
A leaf is a card with no regions.

`fill` keeps the distinction `mount` used to blur: `mount` carried two unrelated facts at once —
"not in the patch fragment" and "the bake host" — which is why `Row` and `Tabs` both had one while
meaning entirely different things by it. The first fact is now the invariant; only the second is a
declaration.

### Model + validate

`LayoutNode.Component.children: List[LayoutNode]` becomes `Map[String, List[LayoutNode]]`.
`Dashboard.validate` gains:

- **the invariant**: the holes a template splices are pairwise disjoint. Parse the template's
  section structure once at build rather than grepping it per render;
- a card with any region is never a patch target, so its template binds no live entity slot — signal
  slots only. Same shape as the existing `wrapAsCell=false` rejection, and the same reason;
- every region a node populates is one its card declares, and declares `eager`;
- every declared region is spliced by the template exactly once;
- region names are plain tokens — they enter node ids, so `Dashboard.sanitize`'s rule applies as it
  already does to cell classes.

The second rule is what makes the leaf/structure split real rather than a convention.

### Node ids — full symmetry, and it breaks tab-state URLs

`LayoutNode.pathId` is a flat `List[Int]` (`path.mkString("c_", "_", "")`). Every child segment
becomes region-qualified, the default region included: `c_2_children_0`, `c_2_headActions_0`. One
grammar, no privileged region. `path: List[Int]` becomes a list of `(region, index)`.

**Accepted breakage:** node ids are mirrored into the URL for tab state
(`fhUrl('ui.{{id}}', $ui_{{id}})`), so every bookmarked or shared URL carrying a tab selection stops
resolving. Deliberate — a second id grammar to protect them is the worse trade while the design is
pre-v1.

### Renderer

- `Index.walk`, `MemberGraph`, the `danglingBakes` walk and the other traversals take
  `children.values.flatten` — mechanical and region-blind, since they only need every node.
- Region-aware in two places: `renderTemplateOf`'s var map (one section per region rather than one
  `children` list), and `mountId` → `regionId(nodeId, region)` for the baked region a surface names.
- `Tabs` migrates here (its buttons move into the template), because the invariant rejects its
  current shape the moment it is enforced.

### Wire format

`dashboard.json` is generated, never tracked, so there is no migration — but `children` changing
shape is a breaking decoder change, and every suite fixture that hand-writes a node moves with it.

## Step 2 — delete `self`

Mechanical once step 1 holds, because the invariant already forbids the only shape that made `self`
load-bearing.

- `Slider` gains its internal `head` region and `SliderHead` card; the head markup moves across
  unchanged, plus a `display:contents` cell.
- `patchTargetId` collapses into `elementId`, and the *"what I morph vs what I am"* distinction its
  doc comment describes goes with it — one element per node again.
- Everything in "What gets deleted" above.

The test that matters is the property, not the deletion: **a patch for any node must write bytes for
that node alone.** A grouped slider nested inside a grouped slider, and a tab inside one, are the
fixtures — under the old model those were the shapes that could smuggle a subtree into a fragment.

## Step 3 — the slider head actions, the first consumer

- `Slider` declares a `headActions` region beside `head`; the head grid's last column becomes a
  `.slider-actions` flex row carrying `pointer-events:auto` (the rule currently on `.slider-action`,
  which stops being one element's business).
- A new leaf card for the action itself — a circle icon button with the `lit` signal binding. `c.pill`
  is the wrong reuse: it is a BeerCSS `.chip` with a label and a hardcoded `fh-hug` cell, with no icon
  and no `lit`. It is right about the busy wiring, which the new card copies.
- `tapAction` on the slider becomes sugar for a single-element `headActions` list, and the hardcoded
  `{{#onclick}}…mdi-power…{{/onclick}}` fragment is deleted — one mechanism, not two.
  **Behaviour change for the commit message:** that action's busy signal moves from the slider's
  `_<id>__busy` to the action node's own. That is the point of the issue, but it is not a no-op.

## Step 4 — the docs

- **ADR 0012**: rewrite the **Open** section in place — the question *"should a `self` splice
  children at all?"* is answered by the concept no longer existing. Its *"Rejected along the way"*
  entry for the hollow mount stays; it is still guarding the design.
- **`docs/architecture-rendering-pipeline.md`**: nearly every "mount" in it is a *surface* mount —
  the bake host in the fill/refill/horizon machinery, the `Patches.applied` box, `hostEvicts`. That
  concept survives intact as "baked region"; it is a rename. Two substantive edits: §7's cache row
  ("A composed surface mount is NOT cached") narrows, and open question #130's first obstacle is
  struck.
- **`docs/terminology.md`**: `self` and `mount` are deleted as terms; `region`, `leaf card` and
  `structural card` replace them. `bake`/`bake group`/`flip` are unaffected.

## Sequencing

Steps 1–4 land as separate commits in that order. Step 1 is releasable on its own. Delete this file
in the same PR as step 3.

### Expected test movement

Not a no-op, though the behavioural surface is small:

- any `.pkl` byte moves the `@fh-dashboard` package hash — the pure-Pkl suite runs on every step;
- **Tabs' emitted DOM changes**: `id="{{selfId}}"` is on the `.tabs` div today and afterwards is not.
  Wire snapshots (`fixture-surfaces.json`, `fixture-features.json`) move, and probably the tabs
  visual baseline. The `data-signals__ifmissing`/`pendingClear`/`pendingFail` attributes key on
  `{{id}}`, not `selfId`, so those ride along unchanged;
- **Slider's DOM gains one wrapper** (the head's `display:contents` cell) — same for the snapshots;
- `ServerHarness`'s synthetic `"tabs"` card has `{{#children}}` in its `self` — the shape the build
  now rejects — so it and any test asserting patch-target behaviour through it are restructured;
- `RendererSuite`'s `tabsLive`, and the whole bake / surface / resume / membership layer, should be
  untouched.

Performance: nothing new is introduced anywhere, and two improvements become available (every leaf
cacheable; #130's first obstacle gone). The over-send this plan originally set out to prevent is not
reachable in today's library at all — every `{{{panel}}}`/`{{{branch}}}` sits in a `mount`, never a
`self` — so removing it saves nothing now. Its value is that it stays impossible.

## Rejected

- **`headCount` — a leading-N split of one list.** Smallest possible diff, and a hack: it uses
  `List[LayoutNode]` as an ad-hoc two-region protocol with the boundary carried alongside as an
  integer, adding a fourth implicit thing instead of removing any of the three.
- **A region tag on each child.** Keeps ids and traversals untouched, but is dishonest: a child would
  declare where its parent puts it, with region membership recoverable only by filtering, and
  `validate` re-checking per child what the map holds by construction. Rejected on "types hold
  truth".
- **`Region.in = "template"|"self"`, policed by `holds = "leaves"`.** This plan's own earlier draft:
  let a region sit inside the patch fragment, and add a declaration forbidding containers there so
  the composing case stays cheap. Rejected because the invariant removes the case instead of
  policing it — two knobs, a validate rule and a review question, all deleted by one constraint on
  the template. The general lesson: a rule with an exception knob loses to an absolute rule you can
  check at build.
- **`data-ignore-morph` on the bake host**, so an ancestor's fragment could carry an empty host and
  disturb nothing. **Measured and unusable.** In the pinned bundle (`assets-cache/*-datastar.js`,
  v1.0.2) the top-level morph entry is:

  ```js
  Hn=(e,t,n="outer")=>{ if( z(e)&&z(t)&&e.hasAttribute(Re)&&t.hasAttribute(Re)
                          || e.parentElement?.closest(Fn) ) return;   // Re="data-ignore-morph"
  ```

  Precedence is `(A&&B&&C&&D)||E`, and the guard runs before any mode branch. The second disjunct is
  unconditional and one-sided: **any element inside a marked subtree is unpatchable, in every mode,
  silently.** Every slider under a marked host would stop receiving its own updates entirely. This
  also settles the spike the earlier draft flagged — a patch aimed *at* the marked element is
  refused only when the arriving fragment also carries the attribute, since `closest` starts at the
  parent; aimed *below* it, always. Fold into `.claude/skills/datastar/SKILL.md`, replacing the
  "worth a spike" line with the measurement.
- **Splitting the slider card in the AUTHORING model** — a bare container holding a `SliderHead`
  node with the members as siblings. Still rejected: it trades away a documented authoring property
  ("give a slider children and it is the same card, a group": ADR 0006, the class doc, a
  `PklBuildSuite` test). Step 2 adopts the *internal* version, where the `Slider` class constructs
  its own head and the author sees one card.
- **Everything through mounts** — ADR 0012's other branch. Its objection was that `Tabs`' `self`
  would have to become a hollow signals-only element beside a `.tabs` mount, because its buttons sit
  inside the element carrying `{{selfId}}`. Answered by going further: `Tabs` needs no `self`, so
  nothing becomes hollow.

### Prior art

`docs/plan-mount-unification.md` (June 2026; landed, then superseded by the self/mount card split)
rejected "named children groups" under the heading *"children is a slot"*. Its reason was that named
children would render every tab PANEL eagerly and hide the inactive ones, defeating lazy surfaces.
That objection is answered here rather than sidestepped: a region declares `fill`, so a panel stays a
lazily-activated surface and only the open one renders and streams. The rejection was of a mechanism
that could express eager regions only — which is the mechanism this plan is careful not to build.
