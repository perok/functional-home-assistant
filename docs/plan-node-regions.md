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
| `EntityCard`, `Button`, `Toggle`, `Pill`, `Text`, `MoreInfo`, `TabButton` | `template` only | leaf — unchanged, `self` was never involved |
| `Row`, `Column`, `Grid`, popup (`core/surface.pkl`) | `mount` only | structure — `mount` renamed to a region |
| `If` (`ifhost`) | `mount` only | structure — one baked region |
| `Tabs` | `self` + `mount` | structure — `self` deleted, see below |
| `Slider` | `self` + `mount` | structure — head becomes a node, see below |

Seven `LeafCard`s and seven `ContainerCard`s, and **two cards in the whole library have a `self`,
both of which also have a region.** Every other card is already a pure leaf or pure structure. There
is no third shape to migrate.

**Tabs** costs nothing. Its `self` is the `.tabs` bar, and it wraps `{{#children}}` (the buttons)
because BeerCSS styles tabs with the structural selector `.tabs > a`. But the card's own comment
records that *"the bar carries no live value today"* — so the bar is structure:

```pkl
template = <div class="fh-col">
             <div class="tabs" data-signals__ifmissing="{ ui_{{id}}: {{bakeIndex}} }" …>
               {{#buttons}}{{{html}}}{{/buttons}}
             </div>
             <div id="{{panelId}}" class="tab-panel" …>{{{panel}}}</div>
           </div>
```

The template is rendered with `vars`, which includes `bakeIndex` (`Renderer.traced`), so the signal
seed comes along unchanged, and the buttons stay direct children of `.tabs`. A *live* tab-bar header
— the "tab bar with the current temperature" that `RendererSuite`'s `tabsLive` fixture exists for —
is a leaf node in a third region beside the buttons. Note that `tabsLive`'s `self` already contains
no hole, so that fixture already satisfies the invariant.

Both halves keep working, unchanged in mechanism:

- **Selection is a signal, and the optimistic-update machinery moves with it unchanged.** ADR 0025
  is already the "value in flight" design: `_<groupId>__pending` is what this client ASKED for
  (client-writes-only, `_`-prefixed so it never rides a request), `ui_<groupId>` is what the server
  says it IS showing, and a button reads `pendingOrCommitted` = `($_<g>__pending || $ui_<g>)`.
  `pendingClear` empties it when the committed value agrees or the stream is down; `pendingFail` on
  a real refusal. All three ride on `data-signals__ifmissing` / `data-effect` /
  `data-on:datastar-fetch__document` attributes keyed on `{{id}}`, so they move from the deleted
  `self` onto the template's `.tabs` div verbatim. `vars` includes `bakeIndex`, so the seed is the
  same bytes.

  One hazard shrinks. `__ifmissing` is there because *"a plain seed would wipe an in-flight pending
  on every re-render of the host"* — and today the host IS the patch target, so that is live on
  every tick the moment the bar binds anything. As structure it is never patched, so the modifier
  goes from load-bearing to belt-and-braces (a repaint or a structural insert still re-renders it,
  so keep it).
- **The panel stays lazy.** `fill = "baked"` is the declaration of exactly the property
  `plan-mount-unification` feared losing: only the selected surface renders and streams, and a
  switch is `Patches.hostFill` inner-patching `panelId` as it does today.
- **The committed index already travels as a signal, not as re-rendered markup.**
  `Server.selectionJson` emits `ui_<gid> -> index` from `SurfaceGraph.committedSelections`, and that
  method's doc states the cadence: *"A stream states this when it connects. The per-swap frame is
  enough while a stream is up."* So a swap is a patch for the panel plus a signal for the index, and
  the bar re-highlights client-side. The bar never needed to re-render for a selection change, which
  is why deleting its `self` costs nothing.
- `TabButton` is already `wrapAsCell = false` — *"the anchors must stay DIRECT children of the
  `.tabs` bar"* — so it has no morph target and `Dashboard.validate` already forbids it binding a
  live entity. It doesn't: `active` and `onclick` are client-side expressions. Consistent with the
  new rule rather than an exception to it.

The one thing that improves: a live tab-bar header today would re-send every button with it, since
they sit inside the element the patch targets. As a leaf in its own region it costs its own bytes
and nothing else.

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

Deleted outright: `Templates.selves`, `Templates.mounts`, `Templates.selvesCarryChildren`,
`Renderer.hasSelf`, `selfElementId`, `patchTargetId` (collapses into `elementId`), `carriesMount`,
`ownBytesCarryChildren`, the `selfVars`/`vars` split in `traced`, and the `compose`/`selfOnly`
two-form split — a patch is always one node's whole template.

**`hasOwnRendering` is REDUCED, not deleted** — its three cases are not one fact:

```scala
case (c: Component, _, _) =>
  if (hasSelf(c.card)) !(selvesCarryChildren && children.exists(carriesMount))  // 1: dies with self
  else !carriesMount(c)                                                         // 2: becomes isLeaf
case (_: SetNode, _, _) => false                                                // 3: untouched
```

1. dies with the concept;
2. becomes `card.regions.isEmpty` — and the recursion inside `carriesMount` collapses with it,
   because under the invariant a leaf card has no regions and therefore no children, so
   `children.exists(carriesMount)` can never fire. A card-level property replaces a subtree walk;
3. *"a member container composes its members and renders nothing of its own; the members are the log
   keys"* — nothing to do with `self`, and it must survive verbatim.

`renderInputs`' gate is `hasOwnRendering(id) && !ownBytesCarryChildren(id)` today; it becomes the
single question **"is this a leaf?"**, with the member branch (`members.memberAt`) unchanged above
it.

### Caching, which is where the win actually lands

**The tick path stops being uncached, for free.** Today a grouped slider is the worst case in the
library: it has a `self`, so the slider node IS the patch target and every light tick re-renders it
— and `ownBytesCarryChildren` (`children.nonEmpty`) turns the cache off for it, even though its
`self` contains no children at all. The proxy is conservative in the one place it is expensive: on
the hot path, for the card the whole library leans on.

After step 2, what re-renders on that tick is `SliderHead`, a leaf. Leaves carry no children by
construction, so they cache. The structural `Slider` node stays uncached, but it is never on the
tick path — it is rendered by the document walk and by a structural insert, and by nothing else.

**And caching becomes compositional, top to bottom.** `renderInputs`' own scaladoc already describes
the second pass:

> *"HOLES where the children go and substituting their (separately keyed) bytes in a second pass —
> `renderTemplateOf` taking `childrenHtml` is the seam. That is an optimisation, not a
> precondition."*

The reason it was never applied uniformly is that a `self` could splice children at arbitrary
nesting, so "this node's own shell" was not a well-defined thing to key. The invariant defines it:
a node's bytes are its own shell (keyed on its own slots) plus each region's bytes (each keyed on
its own). Every level cacheable, nothing composed into a key it cannot compute.

That is **open question #130's first obstacle** — *"what a parent EMBEDS is not what a patch carries
(for a `self` card the cache holds the `self` element alone)"* — struck. Under the invariant what a
parent embeds is a composition of separately-cached shells and leaves. Worth doing as a follow-on
with numbers, not as a step here; the point is that this work is what makes it sound.

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
  /// Step 1 keeps this; step 2 removes it along with the concept. A card
  /// declaring one is a card whose patch target is `{{selfId}}` rather than its
  /// cell — see "What `self` was".
  self: String? = null
  regions: Mapping<String, Region> = new {}
  slots: Listing<String> = new {}
  css: String = ""
  wrapAsCell: Boolean? = null
}

abstract class Node extends LayoutNode {
  /// What fills each EAGER region.
  children: Mapping<String, Listing<LayoutNode>> = new {}
  /// What fills each BAKED region — the bake group competing for that hole.
  /// Replaces `inlineSurfaces`; the build lifts these into the top-level
  /// registry exactly as the hoist does today.
  surfaces: Mapping<String, Listing<surfaceMod.SurfaceDef>> = new {}
}
```

Two maps rather than one, because a baked region's contents are a different type: a `SurfaceDef`
carries activation conditions and chrome, not just nodes. `fill` is precisely the declaration of
which map a region draws from, and validate checks it draws from that one and not the other.

**`inlineSurfaces` is absorbed by this, and so are `bakeInto`/`bakeAs`.** A surface written in a node's
`surfaces["panel"]` is saying `bakeInto = this node, bakeAs = "panel"` — the region it is written in
IS the statement. `Surface.hostId` (`<bakeInto>_<bakeAs>`) becomes `regionId(id, region)`, the same
string. **Bake group** stops being a separate word too: it is "the surfaces in one baked region".

Laziness is untouched. What makes a panel lazy is that surfaces live in a registry and
`resolveBakeTraced` renders only the selected member — a property of the registry and the selection,
not of where the author typed the panel. Being written in a region changes the authoring surface and
nothing about when bytes are produced.

**Popups do not collapse.** A popup has `bakeInto = None` and hosts at the page-level
`Dashboard.PopupHostId`, and it is authored inside a *tap*, not in a region of its owning node. So
the inline-marker-and-hoist machinery stays for that case — which is also what keeps the `NODE_ID`
splice's home alive. Two of the three baked shapes (tabs panels, `If` branches) become regions; the
popup stays a hoist.

`ContainerCard` and `LeafCard` merge into `CardDef`, and `mount` disappears as a property. `self`
survives step 1 and dies in step 2; a leaf is a card with no regions.

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

### A child already reaches its owner's signals — `NODE_ID`

Worth writing down, because it looks like a gap regions would have to open and is not one.
Datastar's store is one flat namespace, so the only question is whether a child's markup can *name*
a signal its parent owns. It can: `core/node.pkl` defines `NODE_ID = "@@NODE_ID@@"`, and
`DashboardBuild` *"splices `idBase` into every `NodeIdToken` in the node's subtree"*. Tabs is the
existing user — `surface.pkl:157`:

```pkl
active = "\(tapMod.pendingOrCommitted(nodes.NODE_ID)) == \(i)"
```

That is a tab BUTTON's slot naming the TABS node's pending/committed signals. The scoping is already
right, and deliberately so: the walk resolves bottom-up so that *"the only `NodeIdToken`s left in
this subtree belong to THIS node"* — innermost owner wins, and tabs nested in tabs does not cross
wires. Regions do not disturb any of it; `children["buttons"]` is the same subtree it was.

**Two defects in it, neither caused by this work.** `DashboardBuild.walk` branches on whether the
node carries an `inlineSurfaces` marker:

```scala
obj1(InlineSurfacesKey).flatMap(_.asObject) match {
  case None         => (Json.fromJsonObject(obj1), childSurfaces)   // no splice at all
  case Some(marker) => … splice(…, NodeIdToken, idBase) …
}
```

1. **The splice is a passenger on the hoist, not a service.** Tabs works because its panels are
   inline surfaces, so the fixup runs as a side effect of hoisting them. A card that only wants to
   hand its own children a signal reference — a `Slider` handing its head one — hits the `None`
   branch and gets no splice.

   **The fix is not "run it at every node".** `walk` recurses into children *first*, so the marker
   is currently what makes the bottom-up rule mean "innermost OWNER wins" rather than "innermost
   NODE wins". An unmarked intermediate node passes tokens through untouched — which is why a card
   can construct a `Row` around its buttons today and still have their tokens resolve to itself.
   Splice everywhere and that `Row` claims them.

   The distinction the splice needs is authorship — *"my card class wrote this string"* — and no
   pass over JSON can see it, because an author-placed child and a card-constructed one are the same
   shape. Today `inlineSurfaces` stands in for it, and calling that a coincidence was wrong: **the
   coupling is structural.** A card hands its children a reference to its own id in order to name a
   SELECTION it owns — that is what the reference is for — and a card owning a selection is a card
   with a bake group, whose alternatives are surfaces. So "carries surfaces" and "writes tokens"
   are not two facts that happen to coincide; the second implies the first. The gap is only a card
   wanting to share a NON-selection value with its children, and no card wants one (ADR 0017 signal
   slots already cover sharing a display value client-side).

   **So the splice does not need fixing** — but see the scope note below, because step 1 moves the
   key it branches on.
2. **A surviving token is silent.** Nothing validates that no `@@…@@` remains: `Dashboard.validate`
   never mentions `NodeIdToken`, and the only occurrence of the string in `src/main/scala` is its
   own definition. So an unspliced token renders literally into the DOM as `@@NODE_ID@@`, and the
   first sign of it is a binding that quietly never matches.

**Step 1 is entangled with this, and the check belongs IN step 1.** Absorbing `inlineSurfaces` into
a per-region `surfaces` map moves the very key `walk` branches on, so step 1 must update that branch
— it is not optional and it is not deferrable. Keeping the behaviour identical is mechanical (branch
on the new key; `Tabs` still carries surfaces, so the splice still fires for it). Getting it wrong
is silent: every tab button renders `@@NODE_ID@@` and its highlight simply never matches. That is
exactly what defect (2)'s check catches, so it lands in step 1 as the regression detector for a
change step 1 is definitely making — not "whenever this file is next touched".

What steps 1–3 do NOT need is any change to the splice's semantics. Each head action keys on its own
id, which is the point of #151, and `SliderHead` gets its entity from Pkl (a value the class holds)
rather than from an id (a value only the build knows).

**Not** a reason to move the owner id into a declared slot. The parent still cannot know the id at
authoring time, so the slot's VALUE would be the same token — one more declaration and the same
splice. The token is doing something a slot cannot: `NODE_ID` means *"the card that wrote this
string"*, which is authorship, not tree position, so the renderer cannot supply it as a structural
var the way it supplies `{{id}}`. What makes it explicit is (2), not ceremony around it.

`DashboardBuild.walk` does move with the children map: its `ChildrenKey` recursion becomes one pass
per region.

### Node variables — the direction, not this plan's work

**What this is and is not.** It is not a prerequisite for regions: the case a card actually needs —
handing its children a reference to a selection it owns — is served today, and served correctly,
because owning a selection implies carrying surfaces implies carrying the splice marker (above). It
is what would turn that from a mechanism that works into a mechanism that is *declared*. Recorded
here because regions are what make it expressible, and because it changes what is worth doing about
`NODE_ID`.

A string convention (`ui_<gid>`, `_<g>__pending`) ties nothing to a node's lifecycle. Compare how
entity reads work: a node DECLARES the entities its slots read, and the recorder, `renderInputs` and
the cache all derive from that declaration. A selection has no such declaration — the only reason
`ui_<gid>` works is that a browser evaluates the string. Nothing server-side knows that a tab button
depends on the tabs node's selection.

The shape that fixes it: a node **declares a variable**, a descendant **reads it through a slot**,
and an action **writes it**. `RenderInputs` already has the field for it —
`RenderInputs(entityVersions, vars)`, whose `vars` today carries exactly one hardcoded entry,
`bakeIndex`. A node variable is that field generalised, and `bakeIndex` becomes its first instance
rather than a special case.

**This is also the answer to the `NODE_ID` walk problem**, and a better one than marking authorship.
A variable is resolved by NAME up the ancestor chain, not by splicing a subtree:

- an intermediate container that declares nothing is transparent — the `Row` case that works today
  by accident keeps working, by rule;
- a nested `Tabs` declaring the same name shadows correctly, which is what "innermost owner wins"
  was approximating;
- a reader with no enclosing declarer is a BUILD ERROR naming the node and the variable, where an
  unresolved token today renders `@@NODE_ID@@` into the DOM. Defect (2) stops needing a check
  because the case stops being representable.

**One thing it must not collapse.** "The variable changed, so re-render its readers" would regress
the tab bar from zero bytes per click to every button re-rendered. The two readings that exist today
must both survive: a plain slot re-renders its reader (right for a panel, whose content changes) and
a signal slot pushes a value with no re-render (right for the highlight, which is a class). A
variable unifies where the NAME comes from, not how a reader consumes it — ADR 0017's split stays.

### Renderer

- `Index.walk`, `MemberGraph`, the `danglingBakes` walk and the other traversals take
  `children.values.flatten` — mechanical and region-blind, since they only need every node.
- Region-aware in two places: `renderTemplateOf`'s var map (one section per region rather than one
  `children` list, plus a `{{<region>Id}}` var per declared region), and `mountId` →
  `regionId(nodeId, region)`.
- **`bakeAs` and the region name become one field.** `Surface.hostId` is already
  `<bakeInto>_<bakeAs>`, and `regionId(id, "panel")` is `<id>_panel` — byte-identical, so `c_2_panel`
  stays `c_2_panel`. `mountId`'s doc already frames that derivation as *"removing a duplication
  rather than adding one"*; regions finish it, because a surface naming the region it bakes into is
  the same statement as naming its template var.
- A region needs an id only where something FILLS it — baked regions and set mounts. `Row`/`Grid`
  eager regions never do (`mountId`: *"their children arrive nested — so they fall back to the
  node's own id and simply never use it"*), so named regions do not force an id onto every hole.

**Open — a set's mount has no region to be named by.** `Patches` uses `renderer.mountId(gid)` as an
`Append` anchor when a member arrives with no sibling, and as an `Inner` target on a refill. But a
`SetNode` is not a `Component`: it has no `cardDef` and so declares no regions, and `mountId(gid)`
resolves through the `bakeGroup`-empty fallback to the set's own `elementId`. That works today
because a set has exactly one implicit hole. `regionId(id, region)` has no region to pass. Either
`mountId` keeps a `SetNode` branch, or sets get a reserved implicit region name. **Decide before
starting step 1** — it is small, but it is the one place the region model does not obviously reach,
and the arch doc's §5 leans on it (*"a set mount's children ARE `gid_…`, so there the container's id
is the right root"*).
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
- **`docs/architecture-rendering-pipeline.md`**: mostly a rename, but **"mount" has three senses in
  it and they must be disentangled, not swept**:
  1. the **bake host** — the fill/refill/horizon machinery, the `Patches.applied` box, `hostEvicts`
     (§5 lines ~374, 391, 436, 470, 494–518). Becomes "baked region";
  2. a card's **children hole** — `Row`/`Grid`. Becomes "eager region";
  3. a **set's member container** — *"a set mount's children ARE `gid_…`"* (§5 line ~518). This one
     has no card and no declared region; it is whatever the open question above resolves to, and
     glossing it as "region" before that is decided would put a wrong sentence in the map.

  Two substantive edits beyond the rename: §7's cache row ("A composed surface mount is NOT cached")
  narrows, and open question #130's first obstacle is struck.
- **`docs/terminology.md`**: `self` and `mount` are deleted as terms; `region`, `leaf card` and
  `structural card` replace them. `bake`/`bake group`/`flip` are unaffected.

## Sequencing

Steps 1–4 land as separate commits in that order. Step 1 is releasable on its own. Delete this file
in the same PR as step 3.

**The checkpoint between 1 and 2 is worth naming:** step 1 enforces the invariant, which forces
`Tabs` to migrate (its buttons move out of its `self` and into the template), so **step 1 ends with
exactly one card in the library still declaring a `self`** — `Slider`. Step 2 is then "remove the
last one, and the concept with it". If step 1 ends with two, something was missed.

**Step 1 splits, and the first cut is the invariant alone.** Reading `core/node.pkl` changed the
shape of this: the invariant is not new machinery, it is **a tightening of a constraint that already
exists**, and half of it is already enforced —

```pkl
self: String?(this == null ||
  (this.contains("{{selfId}}") && !this.contains("{{{mount}}}"))) = null
```

A `self` is already forbidden from containing the `{{{mount}}}` hole, with the same reasoning this
plan gives. What it misses is `{{#children}}`, which is exactly what `Tabs` does and exactly what
`Templates.selvesCarryChildren` exists to detect at runtime. So:

- **1a — the invariant.** Widen that constraint to reject `{{#children}}` as well — the two holes
  the renderer actually fills, hence the two that could smuggle another node's bytes into a patch.
  Say it again in `Dashboard.validate`, because `cards` is decoded from JSON and a guarantee that
  stops at the Pkl boundary is not one. `Tabs` migrates (its
  bar moves into `template`, leaving it with no `self` at all), and `Templates.selvesCarryChildren`
  plus the `hasOwnRendering` clause that consumes it are deleted — the runtime grep is replaced by
  a build-time type refinement. **No model change, no renaming, no id change.** Ends at the
  checkpoint: `Slider` is the only card with a `self`, and it holds no hole.
- **1b — regions, CARD side.** `CardDef.mount` becomes `regions: Map[String, Region]` with the hole
  moved into `template`; `ContainerCard`/`LeafCard` merge; `{{mountId}}` becomes `{{hostId}}`, which
  is what the renderer already called it (`Surface.hostId`). The node side is untouched — `children`
  stays a `List` filling the single declared eager region. Cut here because the counts said so:
  `mount = Some` is 20 test sites, `children = List` is 28+ across more files, so this is the
  smaller half and it is behaviour-preserving.
- **1c — regions, NODE side.** `LayoutNode.Component.children` becomes `Map[String, List[LayoutNode]]`.
  The wire does not move: `children` decodes from a bare ARRAY (the default region, which is what the
  authoring layer emits for a one-hole card) or an object keyed by region — the two forms `SlotSource`
  already has, for the same reason. So no Pkl changes and no snapshot moves. Cut apart from ids
  after counting: the model change is 13 walker sites in main; the id grammar is 136 literal id
  assertions plus every snapshot and the tab-state URLs.
- **1d — ids.** Region-qualified node ids and the tab-state URL breakage. Until this lands, ids are
  assigned by flattening the regions in name order (`Component.orderedChildren`), which is sound only
  while a card has ONE eager region — with two, adding or renaming one silently renumbers that card's
  children. So `validate` rejects a second eager region and says why; **that rule and the flattening
  are deleted by this cut, together.** It also carries the rule 1b left out: *a template that splices
  `{{#children}}` must declare a region for it* — until then `isStructure` is not quite the single
  source of truth (nothing is unsafe: `carriesMount`'s recursion still covers the safety property),
  and the rule forces every `{{#children}}`-splicing fixture to name its region, which is churn this
  cut already pays.
- **1e — the surface↔region relation, checked.** NOT the absorption this plan first described.
  Counting the users settled it: `inlineSurfaces` has **six-plus callers on the POPUP path**
  (`entity.pkl`, `control.pkl` ×3, `slider.pkl`, `tap.pkl`) against two on the baked path (`Tabs`,
  `If`) — and a popup does not collapse into a region (it hosts at the page-level overlay and is
  authored inside a tap). So absorbing the baked two would convert the minority, leave the majority
  on `inlineSurfaces`, and churn every surface id: it would CREATE the second mechanism this plan
  exists to remove, not remove one.

  What is worth having is the relation made checkable. `bakeAs` names the template var a surface
  substitutes into, which since 1b IS a region name, so `validate` now asserts the target node's
  card declares that region AND declares it `baked`. Same class of defect as the existing
  `danglingBakes` and the same silent symptom — the host renders its wrapper with an empty hole,
  indistinguishable from a state group that legitimately matched nothing.

  Plus the leftover-token check, which belongs here because it is the same "a build step did not
  run and nothing said so" failure.

1a is worth doing alone regardless of how the rest is cut: it is the safety property, it is small,
and it moves a stringly-typed runtime check into the type system.

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

## Follow-ups — deliberately not in this plan

Each is recorded so it is not rediscovered as a surprise mid-implementation. None blocks steps 1–4.

- **Make the wire carry ONE explicit form for `children`.** 1c made the decoder accept a bare array
  (the default region) as well as a region-keyed object, so the authoring layer's existing emission
  kept working unchanged. That is the right trade for the migration and the wrong one to keep: two
  shapes on the wire means every reader has to know both, and the explicit one is the honest record
  of what a node holds.

  The end state keeps the Pkl AUTHORING sugar exactly as it is — a card with one region is still
  written `Grid { children { … } }`, because naming the only hole adds nothing — and changes only
  what Pkl EMITS: a hidden `children: Listing` feeding an emitted region map, then drop the array
  branch from the decoder. Note the one snag to settle when doing it: the authored and emitted
  properties cannot both be called `children` in Pkl, so the emitted one needs a name — `regions`
  mirrors `CardDef.regions` ("the card declares them, the node fills them") at the cost of one word
  meaning two things.

  Wire-only, so it moves every snapshot; worth doing right after ids settle, not before.
- **Run the Pkl suite from Scala.** The blocker on this branch is that `pkl test` needs a CLI nobody
  has here, so `.pkl` edits ship unverified. `pkl-core` is already a dependency and
  `Evaluator.evaluateTest(ModuleSource, overwrite)` is public API returning `TestResults` with
  `failed()` / `totalFailures()` / per-test failure messages. The four `src/test/pkl/*.test.pkl`
  modules import by RELATIVE PATH, not `@fh-dashboard`, so no project, lockfile or package resolver
  is needed — `EvaluatorBuilder.preconfigured()` plus filesystem read permission covers it. They are
  **facts-only** (zero `examples` blocks, no `.pcf` baselines), so `evaluateTest` writes no files and
  the `--overwrite` mode is not needed. `PklBuildSuite` already proves in-process Pkl evaluation
  works under the module's Truffle/JPMS setup. **Do this FIRST** — it is independent of the plan, and
  it is what makes "the pure-Pkl suite runs on every step" true rather than aspirational.
- **Node variables** (see the section above). What turns the owner-reference mechanism from one that
  works into one that is declared. Touches `RenderInputs`, the recorder, the tap layer and ADR 0017
  together; wants its own plan.
- **Compositional caching / [#130](https://github.com/perok/functional-home-assistant/issues/130).**
  The invariant makes the two-pass shell split sound; doing it wants numbers, not just soundness.
- **The `NODE_ID` splice decoupling.** Deliberately NOT done — the coupling is structural (a card
  handing children its id is naming a selection it owns, hence has a bake group, hence carries
  surfaces). Revisit only if a card wants to share a non-selection value, and prefer node variables
  if so. The leftover-token check that made its failure mode visible landed in 1e.

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
