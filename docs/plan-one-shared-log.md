# Plan: the self/mount split, one shared change ledger, per-client filtering

**Status: designed, not implemented.** Supersedes the "variant-keyed log" deferred in
ADR 0011, and replaces an earlier draft of this plan built on an empty-baked host plus a
`data-ignore-morph` freeze — see "Rejected".

## The three statements everything else follows from

> **1. A node's patch carries its own rendering and never the contents of a *mount*.**
> A mount is filled independently; anything else the card composes — a tab bar's buttons — rides
> with it.

> **2. Everything that changes a client's DOM goes through the log. The only per-client thing
> in the system is which surface a client has selected.**

> **3. The log records WHEN each node last changed, never WHAT it contains. Content is always
> rendered now.**

(1) is the structural principle: *if a host changes, the content it hosts must not re-render*. It
is not an optimisation and not enforced by suppression — the fragment cannot contain that content.
Precisely: it is enforced for every card that CAN be patched. A card with a mount and no `self`
(`Grid`, `Row`, `Column`, `If`) would carry its mount's contents if it were ever patched — and the
node-level rule makes that unreachable by rejecting a live slot on exactly that shape.
The boundary is the **mount**, not "children": `Tabs.children` are the `TabButton` nodes and they
ride inside the `self`, because the bar IS the card's own rendering. That is safe because a
`TabButton` is `wrapAsCell = false`, so the live-slot rule keeps it out of the reverse index and it
is never independently logged or patched. A custom container composing *patchable* children into
its self would cost duplicate bytes — never incorrectness, which is precisely what
`coveredByAncestor` was for ("correctness never depended on that").
(2) is the safety property: no path may put HTML on a screen without the log learning about it,
or the diff pass will silently suppress a later, genuine change.
(3) is what makes (2) cheap to honour — with a fingerprint rather than bytes, a path unsure of
what it put in the DOM can simply **drop the entry**, and the worst outcome is a re-send.

A fourth statement was added later, and it is the one the fill mechanism violated — see
"Render at the edge": **every rendering is for a viewer, and is complete when it leaves.**

**The standing constraint over all three:** *a complete update is a fallback, never a design
choice.* Deltas first; wholesale re-send only where the delta genuinely cannot be computed, and
then at the smallest granularity that works — refill one group, not the body. The case that
decides it is a phone foregrounding repeatedly: a design that re-sends a panel or a group on
each short reconnect defeats the point of the module even when it is correct.

## The goal

Delete the shared/per-session split (ADR 0002) without giving up per-client economy.

- **One render pipeline and one `FragmentLog` per slug.** Today a tab watched by N clients is
  rendered and diffed N times against N private caches; it becomes one render against one log.
- **`Session.lastRendered`, `sessionOwnedMainIds`, `sessionOnlyStateGroups` and
  `subtreeHasUserOwner` all go**, along with `changedPatches`, the `sessionPaint` block and the
  popup-restore block in `openingPatches`.
- **Popups, tabs and `If` branches are one mechanism** — content in a mount, filled by one
  operation.
- **`coveredByAncestor` is re-targeted, not deleted** — from fragments to mutations. Its content
  rationale dies (statement (1) means no fragment contains another node); its structural one
  survives, because a `Placed` re-supplies a whole subtree. See "Containers record structure as
  mutations".
- **The log stops storing rendered HTML.**

**What is PRESERVED, not added:** a client never receives patches for a surface it is not
viewing. `Scope.Session` already renders only `open` surfaces, so this is current behaviour;
the per-connection filter keeps it once rendering moves to the shared pass. Do not regress it —
it is what makes a high-traffic tab free for a phone looking elsewhere.

## The split

A card that holds other nodes renders in two parts:

- **`self`** — the card's own presentation (a tab bar, a header, a frame). This is what the
  patch path renders and diffs, under the DOM id `<nodeId>-self`.
- **`mount`** — the element its children occupy. The patch path never renders into it; it is
  filled as its own operation.

They are **siblings**: the `self` element must not be an ancestor of the mount. That is the
whole mechanism — a top-level patch matches only the element carrying its own id, so a patch at
`#c_2-self` cannot reach `#c_2_panel`.

**Measured** (`DatastarMorphContractSuite`, pinned bundle, headless Chromium):

```
patch #h-self             →  h-self = NEW,   h_keep = KEEP     mount untouched
control: patch #c whole   →  c-self = NEW,   c_keep = <gone>   mount wiped
```

`-` is deliberate: `Dashboard.sanitize` maps everything outside `[A-Za-z0-9_]` to `_`, so no
generated id can contain a hyphen. Every `startsWith(id + "_")` test in the log
(`hasChildOf`, the flip's prefix invalidation) is therefore structurally unable to mistake
`c_2-self` for a child of `c_2`.

The control is the pre-split behaviour and is why the test cannot pass vacuously: patching the
parent with an empty mount *does* wipe it. Targeting is what makes the difference.

**A corollary that constrains every fill design: `Inner` is all-or-nothing over a mount's
children.** A child named but rendered empty is wiped (spike 4 `n1`); a child omitted is deleted
(`n2`). So a fill cannot be partial — it cannot carry some children and leave the rest standing.
Anything that wants to preserve existing children must not be an `Inner` at their parent.

**BeerCSS already requires this shape.** The `Tabs` template is a `.tabs` bar and a
`.tab-panel` as **siblings** — BeerCSS's tab styling is the structural selector `.tabs > a`, so
`TabButton` even opts out of the universal `.fh-cell` wrapper to keep the anchors direct
children. `If` is `<div id="{{id}}">{{{branch}}}</div>` — pure mount, no self at all. `Popup`
is a `<dialog>` composed into a surface's content, mounted at `#popups`. The split matches the
markup the framework already dictates rather than imposing a new one.

**Two consequences worth stating up front.**

- **The tab bar is already selection-independent.** `TabButton` drives its highlight with
  `data-class="{active: {{{active}}}}"` off the `ui_<id>` signal — no server-rendered active
  class. So a `self` fragment is single-valued and shareable *with no bake at all*.
- **The `data-signals` seed is safe by construction.** `data-signals="{ ui_{{id}}: {{bakeIndex}} }"`
  sits on the panel element, which is the mount. The document path creates it with the client's
  selection; the patch path never touches it. The tab-reset hazard cannot occur.

### The split deletes `Renderer.render`'s cell conditional

The wrapper is conditional today for a structural reason the split removes:

```scala
if (noWrapCards(c.card)) html                                   // authored wrapAsCell = false
else if (c.liveEntities.isEmpty && bakeGroup(id).nonEmpty) html // Tabs, If -- bake owners
else s"<div class=\"fh-cell...\" id=\"$id\">$html</div>"
```

Branch 2 exists because a bake owner "is never itself a morph target (only its baked
panel/branch ... is)", and because `If`'s template carries `id="{{id}}"` itself, so wrapping
"would duplicate the id and leave the flip's outer-morph patch rootless". Both reasons dissolve:

- **The ids stop colliding by construction.** A template no longer writes `id="{{id}}"` — it
  writes `{{selfId}}` and `{{mountId}}`, and the node id belongs to the cell alone. Three
  disjoint ids, one owner each.
- **A bake owner CAN be a morph target now**, safely, because its patch is its `self` element,
  which does not contain the mount.

So branch 2 is simply **deleted**, and `Tabs`/`If` become cells like every other node. The
conditional becomes:

```scala
if (noWrapCards(c.card)) html
else s"<div class=\"fh-cell...\" id=\"$id\">$html</div>"
```

The branch it removes is precisely the one whose own comment describes this plan's bug — a
live-binding bake owner "IS a morph target for its own state-driven re-render", and today that
re-render carries the panel.

**A container can now safely HAVE a cell**, because the cell is never its morph target — its
`self` is. That is the whole reason branch 2 existed, and it is gone.

### The cell and the patch target become orthogonal

This is what the split really buys, and it is why the conditional could not have been removed
before. Today the cell IS the morph target, so a card cannot be a layout item without its patches
carrying everything inside it — which is exactly why a bake owner had to be denied a cell. After
the split they are separate concerns:

| | Element | Meaning | Controlled by |
|---|---|---|---|
| cell | `id="{{id}}"`, `.fh-cell` | layout item — a flex/grid child carrying `fh-cols-*` | `wrapAsCell` (authored) |
| self | `id="{{selfId}}"` | patch target for the card's own rendering | card shape (a container declaring `self`) |

So **`wrapAsCell = false` stops implying "never a morph target"**, and it stops being a way to
say "I do not want layout". A cell describes only how a node is sized **inside its parent** —
`.fh-grid > .fh-cell` is `flex: 0 1 calc(… * 4 / 12 …)`, a **third**; `.fh-row > .fh-cell` is
`flex: 1 1 0`; `.fh-col > .fh-cell` is `width: 100%`. It says nothing about the node's children,
which are styled by the node's own container class. So it is already a per-node property, and a
container has no reason to refuse one.

`wrapAsCell = false` therefore keeps one honest meaning:

> **My root must not be wrapped in a layout box.**

One card qualifies: `TabButton`, whose anchor must stay a direct DOM child of `.tabs` for
BeerCSS's `.tabs > a` selector. Neither `Tabs` nor `If` does — both render real boxes and are
ordinary layout participants.

**Migration note — a fix, not a break.** `.columns(6)` on a `Tabs` card starts **working**. Today
it is accepted by `validate` (the check only fires for cards whose `cardDef.wrapAsCell` is false,
which `Tabs` does not set) and then **silently dropped** by the renderer, because branch 2 skips
the very wrapper those classes would ride on. A silent no-op becomes a real layout instruction.

**The visual consequence, which is real.** `Tabs` and `If` have no cell today, so they match no
`.fh-X > .fh-cell` rule and are `flex: 0 1 auto` — content-sized. Given cells they would become
4/12.

**Decided: both default their own cell to `fh-cols-full`** — authored in Pkl rather than an engine
special case, and overridable per node with `.columns(n)`.

Be clear about what that is, though: `fh-cols-full` is `flex-basis: 100%`, so it is **not**
byte-identical to today's content-sizing — it is the intended shape for a section-level component,
not a preservation of the status quo. In practice a tabs panel's content is usually near-full-width
anyway, so the two should look alike, but that is an expectation and only a browser can settle it
(ADR 0006). The baseline is re-taken once, deliberately, after that look.

If the look shows a regression, the exact-today fallback is one CSS rule —
`.fh-cols-auto{flex-basis:auto}` as the default instead, which reproduces `flex: 0 1 auto` on a
cell. Note even that is not perfectly identical: a cell also picks up `min-width: 0` and the
`max-width:640px` full-width rule, both of which today's non-cell `Tabs`/`If` escape. Both are
improvements (no overflow; full width on phones), but they are changes.

### `If` is an ordinary cell — transparency was unnecessary

An intermediate draft made `If` layout-transparent (`display: contents` via a new `fh-contents`
class) and had it override `addCellClass` to forward layout classes onto both branch cells. Both
are unnecessary, and the reason is one line of `components.pkl`:

```pkl
["then"] = new SurfaceDef { content = new Row { children = `then` } }
```

**The branch is always `Row`-wrapped.** So it is a box no matter what and can never flow into the
grandparent grid — the only thing transparency would have bought. Without transparency, forwarding
has nothing to reach that a plain cell does not.

So `If` takes a cell like every other node:

- `iff(p).columns(6)` sizes the `If` in its parent — builders work directly, no override.
- The `If`'s mount is a plain block `<div>`, so the branch's cell (also a block) fills its width.
  **No CSS change at all** — no `fh-contents`, no doubled `.fh-X > … > .fh-cell` selectors, no
  media-query variant.
- The `If` still says nothing about its content's styling: it sizes itself, the content fills, and
  the content's internal layout stays the `Row`'s business. Which is what `wrapAsCell` meant all
  along — a property of the node, not of its children.

`wrapAsCell = false` therefore has exactly ONE user again: `TabButton`.

*(Kept in "Rejected" so transparency is not re-derived: it becomes worth revisiting only if a
branch is ever allowed to be something other than a single `Row`.)*

### One predicate is the whole seam

There is no new render function to invent. The discriminator is **does this card declare a
`self`?**, and it drives both halves:

| | Card declares `self` | Leaf card | Container with no `self` |
|---|---|---|---|
| patch path renders | the `self` part alone | the whole `template` | — never patched |
| `patchTargetId(nodeId)` | `nodeId + "-self"` | `nodeId` | — |
| logged fragment | fingerprint of the `self` HTML | fingerprint of the whole node | **never logged** |

`Templates` compiles `self` alongside `template` at startup, so the patch path is a lookup, not a
re-parse. The asymmetry between the first two columns is deliberate and harmless: the diff always
compares like with like, because the same predicate chooses what to render and what to compare.

**The third column is the one that must stay empty.** A container with no `self` — `Grid`, `Row`,
`Column`, `If` — has only a mount, so its whole HTML *contains its children*; logging or patching
it would violate statement (1) outright. It never happens because such a card can never enter a
diff set, and that is enforced, not hoped for: the node-level rule rejects a live slot on exactly
this shape (see "What changes in the authoring layer").

### The structural vars come from one derivation

`{{selfId}}`, `{{mountId}}`, `{{id}}` and `{{bakeIndex}}` are all backend-built — an author never
composes an id. For that to hold everywhere, the derivation must live in **one** place: given a
node id, produce the structural var map. Today it does not — `Renderer.render` builds
`Map("id" -> id) ++ structural ++ baked` while `renderCase` separately injects `id` and
`entity_id`, so a var added to one site silently misses the other.

Consolidating them is the work item, and it settles a case that would otherwise be a hole: a
container card used as a **dynamic case** gets `selfId`/`mountId` derived from its member id
(`dynamicChildId(gid, entityId)`) for free, with no per-call-site knowledge. The rule to state in
the ADR:

> Structural vars are a pure function of the node id in scope. Whatever produces a node id —
> `pathId` for the static tree, `dynamicChildId` for a group member — feeds the same derivation.

### No shipped card exercises the `self` machinery

`Tabs`'s bar is static strings (`label`/`onclick`/`active`); `If` has no self; `Grid`/`Row`/
`Column` carry only a `class` slot whose value is a literal. So none of them can enter the reverse
index, and today's library patches container **content** only, never container chrome.

Note the reason, because an earlier draft got it wrong: it is not that these cards have no slots
— `Grid` has one — but that the slot's VALUE is a literal, so `liveEntities` is empty (see "What
changes in the authoring layer"). The `self` machinery exists for a card that has both a mount and
a *live* slot — "a tab bar with the current temperature in its header" — which is legitimate
authoring the design must support, not a shape we ship today.

## Why no variant key is needed

`Dashboard.surfacePrefix` already namespaces every surface's content: `s_detail__c_0` vs
`s_settings__c_0`. Tab A's nodes and tab B's nodes cannot collide in a `Map[nodeId, Fragment]`.
**The node id already is the variant key** — one log holds every variant with no key change.

This is what makes per-connection work safe to write into a shared structure. A fill is
*triggered* per connection (which member to show is a client's business) but what it *produces*
is client-independent: the nodes inside a panel do not depend on who is looking. Two clients
filling different tabs of the same host write disjoint keys.

Under the split there is nothing left that is multi-valued. The bake — a host embedding its
selected member's HTML — was the sole reason `Session.lastRendered` existed, and it survives only
on the document path.

## The per-connection filter

The shared pass renders once; the connection decides who sees it. Patches carry
`Option[surfaceId]`, tagged where they are built (the renderer knows the surface prefix), so the
test is set membership — no string parsing on the hot path.

> A patch tagged with surface `S` goes to a connection iff `S` is in that connection's `open`.
> Untagged patches always ride through.

**The tag names the innermost USER-selected surface**, and that qualifier is load-bearing.
`open` comes from `Renderer.selectedSurfaces`, which does `filterNot(isStateGroup)` — a
state-activated branch is *never* in it, because its visibility is server-decided and identical for
every client. So tagging a node with a state surface would filter its patches away from
**everyone**. State surfaces are transparent to the filter: a node inside an `If` branch nested in
a tab panel is tagged with the tab panel, not the branch.

`open` lists nested user selections, so a node inside a popup inside a tab is tagged with the
popup and both entries are present — `Option.forall` over the single tag is the whole test.

**Resolve "innermost user surface" from a PARENT POINTER, not from the node id** (decided while
landing W7). A node id encodes only its own surface prefix — `s_<sid>__c_0` — and a nesting is
three independent prefixes with no link between them, so the containing chain is not recoverable
from an id at all. The alternative, threading the originating user sid down every branch of
`plan`'s walk, is what mis-tags a nested case the moment the walk grows a branch.

The relation is not new information. `Surface.bakeInto` names the NODE a surface bakes into,
`allIndexed` knows which index — and therefore which surface — any node lives in, and
`stateGidsByRoot` already computes exactly this parent-ness, but only for state groups and only
for its own walk. So this is naming something the renderer already derives and discards:

> `surfaceParent: Map[String, String]` — the surface containing this one, absent for a main-rooted
> one. `userSurfaceOf(sid)` walks it until it reaches a non-state surface: the filter tag, defined
> once.

`plan` then only needs the sid a node came from, which it always has. It also unifies
`stateGidsByRoot`, which is the same relation computed narrowly. Land it as the FIRST move of W6,
where it is immediately used — adding it earlier would be an unused abstraction.

Untagged, and therefore always sent: cursor signals, `haDown`/keepalive, and every main-page node.

**Over-sending is safe; under-sending is not.** A morph at an id the DOM lacks is a silent no-op,
so the filter can only ever cost bytes. That asymmetry is why anything the renderer cannot
attribute to a user-selected surface stays untagged.

## Filling a mount: one operation, five triggers

`PatchMode.Inner` into the mount's id. That is `swapHost` today, unchanged.

| Trigger | Scope | Notes |
|---|---|---|
| First paint / body repaint | per connection | The **document** path renders nested, so mounts arrive filled. No separate operation. |
| Tab select, popup open/close | per connection | The existing `swapHost`; close is `swapHost(None)`. |
| Dynamic-group repaint | shared | The heavy-churn path (≥50%, `MaxChurnFraction`); per-entity deltas are unchanged. |
| Group refill below its horizon | per connection | The resume fallback of last resort; scoped to one group. See "The group fallback". |
| Flip revealing a mount | per connection | ✅ LANDED in W6, then **superseded** — see "Render at the edge". The mount is no longer created hollow, so there is nothing to fill. |

An `If` flip is NOT here: it is a membership delta, not a fill (see "Containers record structure
as mutations").

The last row is the one case that needs the connection's `open` set, so it is appended by the
per-connection stage on seeing a flip patch, keeping it ordered behind the flip's mutations.
`open` needs no maintenance: `Renderer.selectedSurfaces` already returns the selection for every
user bake group whether or not its branch is visible — which is right for *knowing what to fill*
and wrong for *deciding what to render*; see the reachability intersection under "Lazy render".

A flip therefore emits its mutations first, inserting the branch (including a nested tabs card
with an empty mount), and then the per-connection fill puts this client's member into that mount.
Parent before child, same stream.

**A fill is the one operation that carries a subtree**, and that does not contradict statement
(1). Statement (1) governs what a node's *diff* emits — its own rendering, never the contents of
its mount. A fill is not a node's diff: it targets a mount and replaces its contents wholesale,
and it is triggered by selection, a reveal, or a repaint — never by a node's own HTML changing.
That is also why `coveredByMutation` is needed: anything a fill or a `Placed` re-supplies must not
also be sent as its own fragment.

## The log is a change ledger, not a content store

`FragmentLog.fragments` is already keyed by node id, so a node ticking every second holds **one**
entry, not a history. What accumulates is `mutations`, which is why `Retention` and `horizon`
exist, and only there — and the per-group horizon applies to dynamic groups alone (see "The group
fallback").

The change is the entry's payload. Today `Fragment(html, version)` serves two jobs:

1. **Diff suppression** — `if (log.html(gid).contains(html)) (log, Nil)`, four call sites. This
   is the noise reduction the module exists for.
2. **Supplying content on resume** — `since(v)` returns `Fragment`s and `Patches.resume` does
   `owed.fragments.map(f => Patch.Morph(f.html))`.

Job 1 needs only a **fingerprint**. Job 2 should not exist: `Patches.resume` already receives
`(renderer, log, states, version)` and already calls `renderer.dynamicMembers(gid, states)`.

> **`Fragment(html, version)` becomes `Fragment(fingerprint, version)`.** `since(v)` returns node
> ids and versions; the resume path renders them from one current snapshot.

**The shapes, together, so the work items visibly meet.** Three `List[String]` fields with no type
distinction is the smell this codebase names explicitly ("types hold truth", "name recurring
implicit concepts"), so the ids get types first:

```scala
opaque type NodeId = String   // tree-derived: "c_0_1", "s_detail__c_0", "g_light_a".
                              // A log key AND a renderer input. Produced only by
                              // pathId / surfacePrefix / dynamicChildId.
opaque type DomId  = String   // what a patch TARGETS: "c_2-self", "c_2_panel", "#popups".
                              // Produced only by patchTargetId / Surface.hostId.

enum MemberKey:               // how a container names one of its members
  case Entity(id: String)     // dynamic group  -> renderDynamicChild
  case Surface(id: String)    // state-group branch -> renderSurface

  def render(r: Renderer, container: NodeId, st: Map[String, EntityState]): Option[String] =
    this match
      case Entity(e)  => r.renderDynamicChild(container, e, st)
      case Surface(s) => r.renderSurface(s, st)

case class Fragment(fingerprint: Array[Byte], version: Long)   // 128-bit digest; was (html, version)

enum Mutation:                                                 // a STORED fact; unchanged in kind
  case Gone(stamp: Stamp)
  case Placed(container: NodeId, member: MemberKey, stamp: Stamp)   // was (gid, entityId)

case class Resume(
    nodes:  List[NodeId],                     // render these now  (was List[Fragment])
    moved:  List[(NodeId, Mutation)],
    refill: List[NodeId]                      // containers below their horizon
)

def since(v: Long): Resume                    // was Option[Resume]
```

**`NodeId` vs `DomId` makes a leak a compile error.** Conflating them is the hazard T4d exists to
catch — a `-self` id or a mount id ending up as a log key. A type retires the test's harder half:
`patchTargetId: NodeId => DomId` is one-way, and nothing can travel back. This is "parse, don't
validate" applied to ids, and it ripples through `Renderer`, `FragmentLog`, `Patches` and `Server`
— a real refactor, deliberately taken.

**`MemberKey` removes two per-kind dispatches.** An earlier draft said "the renderer resolves a
member key to HTML by container kind, and the anchor derivation is likewise per-kind" — two rules a
human applies, keyed off a distinction a bare `String` refused to carry. As a sum type each variant
owns its own resolution, which is the `CommandResponse.decodeMessage` shape this codebase already
prefers.

**`refill` is a field, not a `Mutation` case — and that was a close call worth recording.**
A `Mutation.Refill(container)` looked strictly better: stored at the eviction version it would mean
"incomplete below X", `since`'s existing `version >= v` filter would serve it, and the per-group
horizon map would disappear entirely. It fails on key collision. `mutations` is keyed by node id,
and a container can also be a *member* of another container, needing both a `Refill` (as container)
and a `Placed`/`Gone` (as member) under one key — latest-wins would silently drop one.

Today that is unreachable, but only incidentally: a dynamic group cannot be a dynamic case (a case
renders a card, and `Dynamic` is not a card) and cannot be a branch root (a branch is always
`new Row { … }`). **The second block disappears the moment branches take a single node** — a change
already under discussion — and the branch root id is `s_<sid>__c` whatever node sits there. A
reserved key namespace (`gid-refill`) would restore storage but forces `coveredByMutation` to decode
keys back to container ids, so the uniformity that motivated it evaporates on the read side.

The deletion the enum case was supposed to buy survives anyway: "a refilled group's members must
not also be sent" is not a rule to remember, it is `coveredByMutation(nodeId, moved ++ refill)` at
the single call site.

**`since` becomes total.** Its `Option` existed solely for `v < horizon` ⇒ repaint. With the horizon
per group and expressed as a `refill` entry, there is no global "cannot serve you" case left: every
remaining repaint trigger — no cursor, log-id mismatch, cursor ahead of the store, changed
`headHash` — is checked by the caller in `openingPatches`.

**The invariant that makes all of this work: every log key must be resolvable by the renderer.**
The ledger renders content *from* keys, so a key the renderer cannot resolve is a fragment that can
never be sent again. Resolvable means a static node id (`allIndexed`) or a dynamic child id of a
known group (`dynamicChildId`) — note the second: member ids are computed per entity and are
deliberately NOT in the static index.

`Mutation` needs no content either: it already carries what re-derives its member
(`(container: NodeId, member: MemberKey)`), so `log.html(nodeId)` at the placement site becomes
`member.render(renderer, container, states)`.

**What this buys.**

- **One resume rule instead of two mechanisms** (log history for the main page, re-render for
  surfaces) and no invariant argument to justify mixing them.
- **Invalidation becomes an always-correct fallback.** Today, dropping an entry destroys content
  the resume path needed, so every DOM-touching path must write back exact bytes. With a
  fingerprint, a missing entry means "unknown — send it". **The failure mode moves from silent,
  permanent staleness to redundant bytes.**
- **The repaint branch collapses to one line**, and the popup restore block disappears with it.

**What it costs, plainly.**

- **A resume renders K nodes instead of reading K strings.** Bounded above by one full body
  render, which the repaint branch already does on every live reload. Per reconnect, never on the
  hot path. If several clients reconnect together they each render; for a household dashboard
  that is a handful, and that is a better answer than a per-snapshot render cache, which would be
  the content store under a new name.
- **A fingerprint collision silently suppresses a patch.** Use a 128-bit digest, not
  `String.hashCode`.
- **Test churn:** `FragmentLogSuite` asserts `.html` throughout; `ServerSuite`'s helpers seed
  logs with HTML.
- Memory drops ~50 KB → ~5 KB per slug. Real, but not the reason.

**`coveredByAncestor` is re-targeted, not deleted.** Its *content* rationale dies — under
statement (1) no fragment contains another node, so no ancestor's cached HTML can cover a
descendant. Its *structural* rationale survives, because a `Placed` re-supplies a whole subtree.
See "Containers record structure as mutations" for the replacement.

## Containers record structure as mutations

**Every container's structural change is a membership delta**, and `mutations` is already that
record — for dynamic groups. The plan's job is to let state groups join it rather than inventing a
second mechanism.

**An `If` flip is a membership change on a list of one:**

```
Gone(s_c_0_1_then__c)  +  Placed(s_c_0_1_else__c, into the If's mount)
```

Live path: `remove` + `append`. Resume: replay the mutations. Checked against the mechanics —
`insertInto` falls back to `Patch.Insert(html, Append, "#" + containerId)` when a member has no
successor, which is always the case for one member; latest-wins per node id handles repeated flips
(`then → else → then` leaves `Placed(then)` and `Gone(else)`, both at the last flip's version,
exactly as the doc's "a rejoin is simply `Placed` replacing `Gone`"); and a condition matching NO
branch is a `Gone` with no `Placed`.

`Mutation.Placed` generalises from `(gid, entityId)` to `(container: NodeId, member: MemberKey)`.
`MemberKey` is a sum type — `Entity` for a group member, `Surface` for a branch — and each variant
carries its own resolution (`renderDynamicChild` / `renderSurface`), so there is no per-kind
dispatch for a caller to remember. Anchor derivation is the same story, and a one-member container
never needs an anchor at all.

**Why the container's fragment goes but its structure stays.** A container fragment does two jobs
fused: it carries content (every child's HTML — statement (1)'s violation, and the reason
`coveredByAncestor` exists) and it records structure (which member is in the mount). Splitting
them keeps the useful half. This is identity only, never content: if a container's record moved
when a *child's content* changed, every child change would re-supply the container — the exact
problem the split exists to remove, arriving through the back door.

**Without this the plan had a hole.** Deleting the container fragment without replacing its
structural half would leave a client that was disconnected during a flip showing the old branch
**permanently**: the `If` would have no entry and no version, the new branch's nodes would arrive
as morphs against ids its DOM does not contain (silent no-ops), and the old branch's nodes were
invalidated so nothing removes them. `selectedSurfaces` does `filterNot(isStateGroup)`, so an
`If`'s branches are never in `open` either. Recording the flip as a mutation closes it.

**A state group needs no eviction and no horizon.** Its member set is its branches — fixed, tiny,
latest-wins over at most two keys — so unlike a dynamic group over unbounded entities it can never
accumulate. `Retention` and the per-group horizon apply to dynamic groups only.

### The dynamic group is the third mount

`repaintGroup` is today the one place a **patch** carries other nodes, so statement (1) has an
exception until it changes:

```scala
val pruned = log.invalidateWhere(k => k == gid || k.startsWith(gid + "_"))
(pruned.set(gid, html, at.version), List(Patch.Morph(html)))
```

The group's whole HTML — every member inside it — is logged under `gid` and emitted as an outer
morph. That is precisely what `coveredByAncestor` exists for (`gid_light_a` starts with `gid_`),
so the deletion this plan claims is blocked while it stands.

**The group element IS a mount**, and it already merges cell, container and id into one element:

```scala
s"""<div class="fh-cell fh-group${Renderer.cellClasses(d.cell)}" id="$id">${children.mkString}</div>"""
```

So its fill target is the **node id**, not a `Surface.hostId` — a dynamic group is not a bake
group and has no `bakeAs`. That is the second mount-id derivation, and the plan states only the
first elsewhere: `{{mountId}}` is `Surface.hostId` for a CARD's mount, while a dynamic group's
mount is its own node id. Both are engine-derived; neither is authored.

(It is also the precedent for merging cell and container into one element — the shape rejected as
"Option B" for cards. It works here because a group's root IS its container; a `Tabs` root is not,
since its mount is the panel beside the bar. Merging cards would reduce DOM depth for
`Grid`/`Row`/`Column`/`If` but changes every container's markup, so it is out of scope here.)

The treatment the other two containers get applies to the group as well, unifying the operation
across all three:

| Operation | Today | After |
|---|---|---|
| `If` flip | outer morph of the host, host fragment logged | **membership mutations** — `remove` + `append` |
| dynamic-group repaint (heavy churn) | outer morph of the group, group fragment logged | `Inner` fill at the mount |
| below-horizon group refill | (a whole-body repaint) | `Inner` fill at the mount |

With that, **no node logs a fragment containing another node** and statement (1) holds without
exception. The per-entity delta path (`DynamicDelta.InPlace`, `remove`/`before`/`after`) is
untouched — this changes only what was already wholesale.

**`coveredByAncestor` survives, re-targeted.** Its fragment-based form stops working here anyway:
a branch root is a `Row` — a container with no `self` — so it logs no fragment, and the test would
return false for every node inside it. But the dedupe is still needed, because a `Placed`
re-supplies its whole subtree while the nodes inside it also carry `version >= cursor` and would
each ship as a no-op morph first. So it keys on mutations instead:

```scala
def coveredByMutation(nodeId: String, moved: Set[String]): Boolean =
  moved.exists(id => id != nodeId && nodeId.startsWith(id + "_"))
```

Smaller and more honest than what it replaces: not "an ancestor's cached HTML already contains
this", but "a mutation is re-supplying an ancestor".

Concretely — a client at cursor v20, an `If` that flipped to `else` at v30:

```
mutations : Gone(s_then__c)@v30 , Placed(s_else__c)@v30
fragments : s_else__c_0 @v30 , s_else__c_1 @v30      ← the branch's inner nodes, live-rendered
```

Without the dedupe the client receives `morph(s_else__c_0)` and `morph(s_else__c_1)` — both silent
no-ops, since its DOM holds no such elements yet — and only then the `remove` plus the `append`
that actually carries them. Two wasted fragments. With `coveredByMutation`,
`"s_else__c_0".startsWith("s_else__c" + "_")` is true, so both are skipped and only the delta goes.

One fill rule applies to the wholesale cases: **a fill writes its members' fingerprints**, or the
next live diff compares against a baseline the client never had (statement (2)). The second rule an
earlier draft needed — "and its members are excluded from the same batch's candidate set" — is not a
rule any more: a wholesale case puts the container in `Resume.refill`, and
`coveredByMutation(nodeId, moved ++ refill)` drops every `container_<member>` by prefix.

## The group fallback: a per-group horizon, and a scoped refill

`mutations` stays. It is what lets a returning client be told "this member left, that one
arrived" instead of being handed the group again, and that is the short-reconnect path the
standing constraint protects. What changes is the blast radius when its history IS gone.

**`horizon` is global today, but the incompleteness it describes is per-group.** It exists
solely for `mutations` eviction — `fragments` never needs it, since it "holds only nodes that
currently EXIST" — and mutations are exclusively dynamic-group membership facts. So today:

> one churning group's `Gone` entries age out → the global horizon rises → every client below it
> takes a **whole-body repaint**, though only that one group's history was lost.

Make it `Map[gid, Long]`, the granularity at which completeness is actually lost:

| Cursor vs. that group's horizon | Sent |
|---|---|
| at or above | its mutations replayed as per-member deltas — today's behaviour |
| below | that group refilled — **the only new wholesale case, and it replaces a bigger one** |

The whole-body repaint stops being reachable through eviction at all. It remains only for the
genuinely global reasons: no cursor at all (a first connection), a log-id mismatch (server
restart, renderer hot-swap), a cursor ahead of the store, and a changed `headHash`.

**The refill carries the group's current content in full.** An earlier draft of this section had
it send an ordered *skeleton* of empty placeholders and leave content to the resume candidates.
That is wrong, and our own measurements say so: `Inner` morphs the mount's children against the
payload, so an incoming `<div id="x"></div>` against an existing `<div id="x">content</div>`
**removes the content** (spike 4 `n1`; the control in `DatastarMorphContractSuite`). The skeleton
would blank every member it named, and the resume rule would then decline to re-send the
unchanged ones because their fingerprint matches. There is no partial form either — omitting a
member from an `Inner` payload deletes it outright (`n2`). Inner is all-or-nothing over the
mount's children.

So this is a genuine wholesale update, and it is allowed only because of what it replaces:

| | Blast radius |
|---|---|
| today | the **whole body**, because one group's eviction raises a global horizon |
| here | **one group**, and only for a client whose cursor predates that group's horizon |

**Two toolsets, and the horizon picks between them.** This is the distinction the withdrawn
skeleton blurred:

| | Tool | Preserves siblings? | Used when |
|---|---|---|---|
| delta | per-child `remove` + `before`/`after`, each a top-level patch | **yes** — each matches its own id, the rest of the page is untouched | live path always; resume **at or above** the group's horizon |
| wholesale | `Inner` at the mount | no — all-or-nothing over the children | resume **below** the horizon; heavy churn; select/open |

Above the horizon nothing changes: `Patches.resume` already replays `Mutation.Gone`/`Placed` as
per-child patches ("`before` the nearest member ordered after it that the client's DOM can anchor
on"), which is a true delta and never touches an unchanged sibling. The wholesale tool is reached
only where the knowledge needed for the delta is gone.

The one rule a wholesale case carries — **it writes its members' fingerprints** — is stated with
its reasoning under "Containers record structure as mutations", along with why there is no second
rule about excluding those members from the candidate set.

**Nothing else changes.** Every other fill target is empty when it is filled — a popup opening, a
tab being selected — so there is no delta to preserve, and a tab resume does not fill at all. The
live path keeps its per-entity `remove`/`before`/`append` deltas, and the group refill stays what
it is: the fallback of last resort, now scoped to one group instead of the page.

## What changes in the authoring layer

1. **`CardDef` gains two named parts, and `template` keeps its meaning.** It already declares
   `slots` and `wrapAsCell`; this is one more declaration of the same kind, and it is purely
   additive — a leaf card is unchanged.

   ```pkl
   cardDef = new CardDef {
     template = #"<div class="fh-col">{{{self}}}{{{mount}}}</div>"#
     self     = #"<div id="{{selfId}}" class="tabs">{{#children}}{{{html}}}{{/children}}</div>"#
     mount    = #"<div id="{{mountId}}" class="tab-panel" data-signals="...">{{{panel}}}</div>"#
   }
   ```

   `template` is the whole card with two holes (defaulting to `{{{self}}}{{{mount}}}`); the
   **document path renders it** with the mount filled, the **patch path renders `self` alone**.
   One source, two uses — no duplicated markup and no string surgery on rendered HTML.

   **The engine owns both ids.** `{{selfId}}` and `{{mountId}}` are structural vars like `{{id}}`
   and `{{bakeIndex}}`; the author never composes an id string. This *removes* an existing
   duplication rather than adding one: today the Pkl template hardcodes `id="{{id}}_panel"` while
   Scala independently derives the same string as `Surface.hostId = s"${into}_${as}"` — two
   languages agreeing by convention, with nothing checking it. After this, `Surface.hostId` is the
   single source the template reads.

   **`{{mountId}}` is not a new id — it IS `Surface.hostId`.** For `Tabs` it resolves to
   `c_2_panel`, byte-identical to what the template hardcodes today. So `{{selfId}}` is the only
   genuinely new id in the design, and the rendered document is unchanged apart from an id on the
   bar. **The visual baselines therefore do not move** (T16 stands as written).

   **A mount needs an id only where something fills it.** That is exactly where `bakeAs` already
   names it — a tab panel, an `If` branch, the popup host. `Grid`/`Row`/`Column` mounts are never
   fill targets (their children arrive nested, through the document path or a parent's fill), so
   they carry no id and the `contains("{{mountId}}")` constraint does not apply to them. The
   constraint belongs on the fill-target shape, not on every card with children.

   **One self and one mount per card.** True for everything shipped (`bakeAs` already assumes one
   bake hole per group); more would make `mount` a Mapping, and there is no case for it. `If` has
   a mount and no self; `Grid`/`Row`/`Column` have a mount (`children`) and no self.

2. **The card shape becomes a Pkl type, so an invalid dashboard is unconstructable.**
   `CardDef` splits in two — with a mount and without:

   ```pkl
   abstract class CardDef { template: String; slots: Listing<String> = new {}; wrapAsCell: Boolean? = null }

   class LeafCard extends CardDef {}                    // no mount

   class ContainerCard extends CardDef {
     mount: String                                      // where child nodes go
     self: String?(this == null || !this.contains("{{{mount}}}")) = null
   }
   ```

   **An earlier draft had THREE classes** — `PlainCard` (mount, no `slots` property at all) for
   plain containers — and claimed that made `Grid`/`Row`/`Column`/`If` unable to enter a diff set
   *by type*. **That is false, and it would have rejected the library's three most basic cards.**
   `Grid`, `Row` and `Column` each carry a `class` slot on the very element that holds the
   children:

   ```
   <div class="fh-grid{{#class}} {{class}}{{/class}}">{{#children}}{{{html}}}{{/children}}</div>
   ```

   So they have slots (ruling out `PlainCard`) and their single element is both self and mount
   (violating the non-nesting constraint). They are safe not because of their type but because
   that slot's VALUE is a literal — `cssClass: String?`, never an entity — so `liveEntities` is
   empty and they never reach the reverse index.

   **So the rule belongs at the node, where reactivity is known**, not at the card:

   > A node with a **live** slot, whose card has a `mount` and no `self`, is a build error.

   That is the real hazard — a card that both hosts content and re-renders on state — and it is
   caught exactly where it can be seen. `Grid` keeps its template unchanged; a custom "tab bar
   with a live temperature" is rejected until it declares a `self`. The mechanism is the
   sibling-reference constraint already spiked (`slots` reading `cardDef`, `toMap().values.any`,
   and a `d is ContainerCard` type test).

   **Liveness is statically knowable in Pkl**, so this stays an authoring-layer rule and does not
   fall back to `validate`. `Slot|String` IS the distinction: `SlotSource`'s own doc says a literal
   "is authored as a bare JSON string rather than an object", so a Pkl `String` is exactly
   `literal.nonEmpty` and a Pkl `Slot` is exactly `literal.isEmpty`. Nor can a transform reach an
   entity it was not given — "with neither (no slot `entityId`, no `entity_id` param) the transform
   runs against an empty state" — so there is no hidden liveness a static check would miss. The
   predicate mirrors `liveEntities` exactly, subject case included (note `subjectEntity` is
   `slots.get("entity_id").flatMap(_.literal)`, so the subject counts only when `entity_id` is a
   literal):

   ```
   subjectPresent = slots.containsKey("entity_id") && slots["entity_id"] is String
   live  ⟺  ∃ v ∈ slots.values : v is Slot && v.reactive && (v.entityId != null || subjectPresent)
   ```

   This is not a Scala derivation duplicated into Pkl — it is one wire-level distinction expressed
   in the two type systems that share the wire format, the same deliberate mirroring `class Slot`
   already is ("mirror fh.view.model").

   **Spike-verified** (pkl-core 0.31.1, scratch lib mirroring the real `cardsOf` reflection):

   ```
   ok / registry            -> EVALUATED
   self without {{selfId}}  -> Type constraint `contains("{{selfId}}")` violated
   self containing mount    -> Type constraint `!contains("{{{mount}}}")` violated
   slot-name typo on a node -> Type constraint `keys.every((k) -> declaredSlots(cardDef)...)` violated
   live slot on a bare card -> Type constraint `!bare(cardDef) || !toMap().values.any(...)` violated
   cell params on a bare card -> Type constraint `!bare(cardDef) || this == null` violated
   bare card as a dynamic case -> card 'tab' has wrapAsCell=false and cannot be a dynamic-group case
   declared slot not provided -> Type constraint `declared(cardDef).every((d) -> keys.contains(d))` violated
   ```

   What is unrepresentable after this:

   - **`self` cannot be an ancestor of the mount.** `self` and `mount` are separate strings placed
     at independent holes, so the only way to nest them is for `self` to contain the mount hole.
     Forbidding that makes "they are siblings" — the whole mechanism — structural.
   - A `self` part must carry the engine's id.
   - A LIVE slot on a container with a mount and no `self`.

   **NOT the slot-name pair, and this is a finding from landing it.** Both directions —
   "every provided key is declared" and "every declared slot is provided" — were spiked against a
   scratch lib where every card declared every slot, and both fire correctly there. Against the
   REAL library each one rejects a shipped card:

   - `Grid`/`Row`/`Column` declare **no** `slots` at all in `cardDef` yet provide `["class"]`, so
     *provided ⊆ declared* rejects them.
   - `Grid`'s `class` is provided only `when (cssClass != null)`, so *declared ⊆ provided* rejects
     it the moment the card does declare it.

   The cause is that `cardDef.slots` today means **required**, not *declarable* — `validate`'s
   `checkRef` flags declared-but-missing and deliberately ignores extras ("only flags missing
   *required* slots and ignores extra ones"). Enforcing either direction needs the card vocabulary
   to separate the two (a `slots` set plus an `optional` set, or `Listing<SlotDecl>`), which is a
   change to the authoring API and not a mechanical port. **Deferred, with the slot-name check
   staying in `validate` where it is correct today.** Pick the vocabulary first.

   **The `wrapAsCell = false` rules also stay in `validate` for now** (live slots, cell params,
   dynamic-case use). Two of the three want a constraint on `cell`, which is declared on
   `LayoutNode` — above the class that knows `cardDef` — so it needs a narrowing spike first, and
   `TabButton` is the only card affected. No behaviour depends on where they live.

   **The registry needs no change, and the JSON is polymorphic for free.** `cardsOf` reflects over
   `Node` subclasses, not `CardDef` ones, and only tests `v is CardDef` — which a subclass
   satisfies. Pkl renders each object's OWN properties, so the emitted registry discriminates
   itself with no tag field:

   ```
   ["tabs"]   { template; wrapAsCell; slots; mount; self }
   ["fhgrid"] { template; wrapAsCell; slots; mount }
   ["entity"] { template; wrapAsCell; slots }
   ```

3. **The popup selection signal**: the popup `onclick` sets `$ui_popups` client-side, the way tab
   buttons already set `$ui_<id>`, replacing the server-pushed `PopupSignal`.

Everything else holds: the `{cards, card}` model, `dashboard.json`, the `ui.<id>` URL mirror, the
tab button's `onclick`, and `.fh-cell` wrapping — but ADR 0008's "the cell is the layout item
AND the Datastar morph target" now splits in two: the cell stays the **layout item** for every
node, and stays the **patch target** for leaves, while a container's patch target moves inward to
its `self` element. One function owns that mapping, in one direction only:

> `patchTargetId(nodeId)` = `nodeId` for a leaf, `nodeId + "-self"` for a container.

**The log key is always the node id.** `Dashboard.pathId` ids are the stable thing every other
structure speaks — the reverse index, the diff cache, the cursor — and the DOM id is a rendering
detail derived from it. Nothing maps back, so a `-self` id can never enter the log, the index, or
a cursor.

**Consequences for the safety nets.** The `PklBuildSuite` wire snapshots move once, deliberately
(scoped `FH_UPDATE_SNAPSHOTS`, never exported). The visual baselines (`tabs.png`,
`popup-open.png`, `full-dashboard.png`) must **not** move: the split changes which element an id
sits on, not the rendered document. If they move, the document path leaked.

## The resume rule

One rule, one candidate set, one snapshot:

> **Candidates** = nodes whose logged version is `>= cursor`, plus every node in
> `session.open ∩ reachable`.
> **For each**: render it from the current snapshot, and send it when
> `version >= cursor || fingerprint != stored`, treating a MISSING entry as "send".

- `version >= cursor` — the node changed at or after the cursor, so the client may never have
  applied it. Send what we have now; it is at least as new.
- `fingerprint != stored` — the log's idea of the node disagrees with the current render. That is
  the untracked case: a surface the lazy gate skipped while nobody was viewing it.
- **`∩ reachable` is the same intersection the render gate uses** (see "Lazy render"). Without it a
  reconnect renders every surface a client has selected inside an *inactive* `If` branch — nodes
  that are in nobody's DOM. The two sets must agree, or the resume does work the live path
  deliberately skips.

**A node with no log entry is not a candidate, and that is sound only under a precondition worth
stating.** An entry is absent in exactly two cases: the node has not changed since the log began —
so the client's document render is still valid — or it was invalidated. Every invalidation must
therefore either be followed by a re-supply (a group repaint's fill re-supplies and re-fingerprints
its members) or be scoped to nodes that are candidates by the other route (the repaint branch
invalidates only the session's open surfaces, which `open ∩ reachable` covers). **A future
invalidation that satisfies neither would silently strand a node** — check it against this rule.

**The cursor selects which nodes; the renderer decides what to send.** The cursor is never
consulted for content, so filtering surface patches out of a cursor-bearing stream cannot desync
anything. This is the sentence ADR 0011 should gain.

**A resume needs no special handling for containers.** A container with a `self` has that as its
candidate fragment, and it does not contain the mount — so a client returning after a long absence
gets the bar's new HTML and keeps its panel, with the panel's own nodes reconciled on their own
ids. Under the previous draft this case required a freeze.

**The repaint branch** (no cursor, stale cursor, mismatched log) renders the body through the
document path, so the client's DOM is current by construction. It owes the log one thing:
**invalidate the entries for the surfaces this session has open.** Without that, a surface whose
entry went stale while unviewed leaves a baseline the live diff will compare against and suppress
a real change. Invalidating is correct by statement (3) and costs at most a redundant re-send —
including to other clients, since the log is shared.

The **live-reload** repaint needs none of this: a renderer hot-swap mints a fresh log, so every
cursor against the old one is rejected. It loses `session.lastRendered.update(_.cleared)`, which
has nothing left to clear.

The popup needs no branch of its own. A body repaint replaces `#dashboard` only, and `#popups`
lives in `theme.chrome` outside it, so an open dialog is never disturbed; its nodes are in `open`,
so the ordinary rule reconciles them. That deletes `popupOf` and `claimedPopup`.

**The host reset survives** (deviation, found landing W11). One case is not a resume question at
all: a claim naming a surface this dashboard renamed, removed, or never had. That dialog is in
nobody's open set and no rule reconciles it, so without the reset it sits on screen forever. It is
now one condition over the ui state rather than a popup-specific pair of functions.

**Two supporting facts.**

- **Over-sending is always safe.** A morph targeting an id in an unfilled mount is a silent
  no-op, so the filter can only cost bytes, never correctness.
- **The select/render race has no gap.** `swapHost` updates `open` *before* reading the snapshot,
  so a change diffed before the update has a version ≤ the fill's snapshot and is already in the
  fill; a change after it is filtered against the new `open`.

## Corollary: a client-owned mount

A card hosting its own JS (React, a chart library, a web component) is a mount the **server never
fills**: it renders `<div id="N" data-my-widget></div>` and the client builds the subtree.

The split protects it from *ancestor* morphs for free — no ancestor fragment contains it. What it
does not protect against is a morph of `N` itself, which happens whenever `N`'s own slots change,
and on the document path (first paint, every repaint, every live reload). Any of those re-supplies
a childless `<div id="N">` and deletes the widget's tree.

So `data-ignore-morph` on the widget root is **mandatory**, authored in the card template.
**Measured** (`DatastarMorphContractSuite`, second test):

```
ancestor morph carrying an empty body  →  label = NEW, protected child = KEEP
patch aimed INSIDE the protected body  →  dropped
```

The second half is the *desired* behaviour here and was fatal for a server-filled panel — the
requirements are inverted:

| | Server-filled mount | Client-owned mount |
|---|---|---|
| An ancestor morph must skip it | yes (structural, via the split) | yes |
| Patches aimed INSIDE must work | **yes** — live updates go there | **no** — the JS owns that DOM |

The attribute must be authored on both sides (`dt` requires it on the incoming fragment too), and
the server talks to such a node through **signals**, not HTML patches.

**Two things it does NOT stop**, measured while exploring the freeze and worth keeping now that
they bear on this shape instead:

| Aimed at | Result |
|---|---|
| `inner` AT the protected element | **applies** — it would wipe the widget's tree |
| `replace` at a node INSIDE it | **applies** — it penetrates the guard |

So the protection is against *ancestor morphs and outer patches aimed inside*, not against every
write. An `inner` at a client-owned root is still destructive — which is why such a node must
never be a mount the server fills. And `replace` is the escape hatch if the server ever genuinely
must force a subtree, though signals remain the intended channel.

Note the vendored docs are
**wrong** here — `attributes.md:218` says "preserve element content across morphs but allow
attribute updates", but the bundle's `dt` returns before any attribute sync. That contradiction is
why the contract is a suite rather than a note.

## Lazy render: skip unviewed variants (adopted)

Do not render nodes in variants nobody has selected. The waste grows with the dashboard:
`openPopupInline` hoists a surface per node, so a detail popup on every entity card roughly
doubles the render work for content open ~0% of the time — all of it JSONata and Mustache.

The render set is `affectedComponents(entityId)` plus the surfaces in
`⋃ session.open ∩ reachable`, where `reachable` excludes anything inside an inactive state
branch (`activeStateSurfaces(states)` already computes it).

**The intersection is not optional.** `selectedSurfaces` returns the selected member of every
user bake group *whether or not its branch is currently visible* — it filters out state groups,
but not user groups nested inside one. So an un-intersected `⋃ session.open` contains the tab
surface of a tabs card inside a hidden `If` branch, and the gate would render and push it on
every tick while it sits in nobody's DOM. That would silently break `Renderer`'s hidden-branch
guarantee ("inactive members are never consulted — that IS the guarantee, and it is
structural") and waste exactly what this gate exists to save.

The consequence for the flip: a newly-revealed branch's surfaces have **cold** log entries, since
nothing was rendering them. That is fine — the fill renders fresh from the current snapshot
either way — but it is the opposite of what an earlier draft of this plan claimed.

**Identifying what changed is not gated — only rendering it is.** `componentsFor(entityId)` is a
static reverse index, computed once per renderer; resolving a state change to its affected nodes
needs no rendered output and no versions. The gate skips the JSONata + Mustache evaluation.

**No tracking metadata is needed.** A skipped variant simply has a stale fingerprint, which the
resume rule's second disjunct catches by rendering and comparing. Staleness is unobservable while
it lasts: one mount holds one member at a time, so an unselected variant is in nobody's DOM.

**The saving comes from the node-set filter, not from laziness.** The diff is render → compare →
emit-if-different, so the comparison happens *after* the render; forcing a lazy thunk in order to
compare IS the render. The skip belongs where the node set is chosen.

**Independently: memoize transform evaluation within a diff pass**, keyed by
`(entityId, transform)`. Inside one pass the snapshot is fixed, so it is a pure function, and one
entity commonly drives several nodes re-evaluating the same JSONata. `Renderer.identityCache`
already does this for `reactive = false` slots.

**It needs no eviction, because it must not be cross-pass.** Scoped to one pass and discarded, it
is bounded by the node set that pass touched. A cross-pass cache would need the store version in
the key and every entry would be garbage on the next tick — eviction machinery for entries with a
one-pass lifetime, which a scoped `mutable.Map` gives for free. No cache library is warranted.

**Not via `lazy val` or `WeakReference`.** Deferring the render rather than skipping it back-fires
on memory: a deferred fragment means "the HTML as of version V", so the thunk must capture the
entity snapshot at V, retaining one snapshot per unrendered fragment across every retained
version. `WeakReference` cannot help — it wraps the *result*, while the closure holding the
snapshot stays strongly reachable. And under the ledger there is no HTML to defer in the first
place: a fragment is a fingerprint, computed from a render that either happened or was skipped.

## Rejected

### The empty-baked host plus a `data-ignore-morph` freeze

The previous draft of this plan. The host kept baking its selected member on the document path
but baked **empty** on the patch path, making its fragment single-valued and shareable — a
deliberate lie about the panel — and then prevented the lie from ever being applied by toggling
`data-ignore-morph` on the panel around every morph of the host or an ancestor.

It worked, and was verified end to end. It is rejected because the split reaches the same
property structurally and deletes the machinery the freeze needed: a `{{{hostAttrs}}}` template
hole, a validate rule to force it, a "morphable" predicate over the tree, per-host freeze signals,
a tight-bracket ordering constraint inside every batch (freeze / host fragment / unfreeze must
precede any patch aimed into that panel, or the panel updates are silently dropped), a freeze on
the resume path, and a fill site that existed only to repair a mount the lie had emptied on a
state flip.

The deciding argument is the principle, not the line count: with the empty bake, "a host's change
does not re-render its children" holds because the children are *sent and then suppressed*. With
the split it holds because they were never in the fragment.

**Re-checked against the `Grid` finding**, which is the one cost the freeze did not have: the
split makes every container card declare `template` + `mount` where it used to declare one
template, `Grid`/`Row`/`Column` included, even though their rendered HTML is byte-identical
afterwards. That is a mechanical edit to four cards. Against it, the freeze needed a template
hole, a validate rule, a tree predicate, per-host signals, an intra-batch ordering invariant whose
violation is silent, and a browser contract suite. The balance is not close, and the rejection
stands.

### A truthful host rendered per client

Keep the bake honest and keep a small per-session pass for bake hosts and their renderable
ancestors. No lie, no freeze — but a host's own change re-sends its entire panel, which violates
statement (1) outright. Considered and dropped.

### Variant-keyed log entries (`c_2@t1`)

Works for a host, but an ancestor of a host is multi-valued in the *set* of selections beneath it,
so the key would carry a combination rather than a value. This is what ADR 0011 deferred as
overcomplicated, and it remains so. The split removes the multi-valuedness instead of keying it.

### Keeping rendered HTML in the log

Because the resume path *reads* content from the log, any path writing to a client's DOM must
write the same bytes back, and forgetting is silent and permanent. The concrete failure, on the
live path rather than at reconnect:

```
v10  log: s_t1__c_0 = "A"
v20  client selects t1, fill renders "B" -> client DOM "B", log still "A"@v10
v30  state changes it back to "A"
     live diff pass: current "A" == stored "A" -> NO patch emitted
     client sits on "B" indefinitely
```

Keeping content also forces two mechanisms at reconnect and an argument to justify mixing them.

### Gating the render by a union, while still trusting the cursor for surfaces

An earlier draft rendered only the surfaces some live session had selected and then resumed those
surfaces from log history. **The union was never the mistake — reading content out of the log
was.** If a surface leaves the union its entries stop advancing; a client that drops while it was
the *only* viewer returns holding a cursor **newer** than that surface's last logged version, so
`since(cursor)` returns nothing and its DOM keeps pre-drop values forever. The single-phone case
is the common one, so this would have destroyed the win the plan exists for. With the ledger the
objection cannot even be stated.

### A skeleton refill — structure only, content from the resume candidates

Proposed and adopted for one round, then withdrawn. The idea: on a below-horizon group refill,
`Inner` the mount with the members as *ordered empty placeholders*, restoring membership and
order while the ordinary resume rule supplies content — so a member unchanged since the cursor
would never be re-sent, keeping even the fallback close to a delta.

It cannot work, and the evidence was already in this document. `Inner` is all-or-nothing over the
mount's children: a placeholder named-but-empty **wipes** that member (spike 4 `n1`), and omitting
it **deletes** it (`n2`). So the skeleton blanks every member it names, and the resume rule then
declines to repair the unchanged ones precisely because their fingerprint matches — the plan's
own economy turning into a correctness bug.

The error underneath it was using the **wholesale** tool to do a **delta's** job. Those are
different mechanisms with different DOM semantics: `before`/`after`/`remove` are per-child
top-level patches that leave every unnamed sibling standing, while `Inner` replaces a mount's
children entirely. The delta path already exists and is unaffected by this rejection — above a
group's horizon, `Patches.resume` replays mutations as exactly those per-child patches.

Recorded rather than deleted because the wish is reasonable and will recur: separating structure
from content in a fill would be genuinely useful. Any future attempt needs the per-child
mechanism, which needs to know what the client currently holds — and below the horizon that is
precisely what has been lost. Extending the window (evicting against the oldest live cursor
rather than a wall clock, the `FragmentLog.Retention` FUTURE note) is the real lever, not a
cleverer payload.

### Making `If` layout-transparent

Proposed and withdrawn within one round. `If` would render `display: contents` (a new
`fh-contents` class in `layoutCss`) so its branch participated directly in the grandparent's
layout, with `addCellClass` overridden to forward layout classes onto both branch cells —
virtual dispatch confirmed by spike, so the mechanism worked.

Two facts kill it, and the second is the durable one.

**The branch is always a single box.** `content = new Row { children = then }`, so exactly one
element ever reaches the grandparent's layout — transparent wrapper or not. Transparency and an
ordinary cell are therefore *visually identical*, and the cell needs no CSS.

**Forwarding cannot work without transparency, because `flex-basis` needs a flex parent.** The
layout classes are `flex-basis` only:

```css
.fh-cols-full{flex-basis:100%}            /* and .fh-cols-<n>, same shape */
.fh-col{display:flex;flex-direction:column}
```

The `If`'s mount is a plain block `<div>`, so a forwarded `fh-cols-6` on the branch's cell does
**nothing** — the cell is not a flex item. Making the mount `.fh-col` does not help either: that is
`flex-direction: column`, so `flex-basis` would size the HEIGHT. So "forward the builders" and
"make the wrapper transparent" are not two options; the first requires the second, which in turn
requires the layout selectors doubled (`.fh-grid > .fh-contents > .fh-cell`, plus the
row/column/group variants and the `max-width:640px` rule) to restore `min-width: 0` and the mobile
override, both of which are direct-child selectors.

An ordinary cell gets the same pixels for none of that: the `If` sizes itself, and the branch's
cell — a block inside a block — fills it.

**And nested `If`s rule it out outright.** `components.pkl` calls nesting an `If` inside `else`
"the else-if story", so it is a shipped feature, and it produces:

```
.fh-grid > [If₁ mount, contents] > [If₂ mount, contents] > .fh-cell(Z)
```

Two levels of `.fh-contents`, three for a three-arm chain, unbounded. `.fh-grid > .fh-contents >
.fh-cell` reaches exactly one. **Plain CSS cannot follow arbitrary nesting** — it would need a rule
per depth, or the renderer injecting ancestor-appropriate classes onto the branch cell. That is a
disproportionate mechanism for a layout nicety, and it is the reason this is closed rather than
merely deferred.

`If`-as-cell nests correctly by construction: `If₁ cell > mount > If₂ cell > mount > content`, each
a block filling its parent. `iff(a).columns(6)` sizes the whole chain and every arm fills it —
which is what an else-if should do anyway, since all arms occupy one slot.

**This oscillated across three rounds** (cell → no-cell-and-forward → cell) because the facts above
were not in evidence for the first two. Recorded here so it settles.

**Separately: should a branch take one node instead of a `Listing`?** `then`/`else` are Listings
that `If` wraps in a `Row`, so `c.iff(p).then(card)` silently renders a `Row`. Making them a single
`LayoutNode` — with an explicit `c.row { … }` when you want several — is attractive on its own
terms (no hidden wrapper, and explicit composition is already this project's preference). It is an
ADR 0007 authoring question, NOT a layout mechanism: it does not make `If` transparent, because the
wrapper is still needed as the fill target. `Tabs` wraps each tab's cards the same way, so the
question is symmetric and belongs to both.

### `mode replace`

Measured:

| | Case | Result |
|---|---|---|
| a | `data-on:click` on a replaced element | clicks 0 → 1 — **bindings re-attach** |
| b | signal-bound input, replaced, server sent no value | **77** — **signal-backed state survives** |
| c | user-typed value, not signal-backed | `''` — lost |
| d | focus on the replaced element | `focus_me → <none>` — lost |
| e | descendant state when the **container** is replaced | `''` — subtree nuked |

(b) is the surprise: Datastar keeps state in *signals*, not the DOM, so a replaced element
re-binds and re-reads. In this library essentially all interactive state is signal-backed (the
slider is `data-bind`, tab selection is `ui_<id>`) and there are no free-text inputs, so (c)
barely applies. What breaks is **focus**, **CSS transition continuity**, and (e).

Not adopted, because the cost/benefit is inverted: morph's expense scales with subtree size, so
replacing leaves saves almost nothing, while the containers where it would save real work are
exactly where (e) is most destructive. And the residual lands badly — dragging a slider *is* the
case where the server patches a node under the user's finger, which morph survives and replace
does not. Under the split, containers are patched as `self` elements anyway, so the case for
`replace` shrinks further.

## Resolved: every node is a cell, including containers

The open question was which element is the mount for a card with a `mount` and no `self`
(`If`, `Grid`, `Row`, `Column`, `Popup`) — a distinct element inside the cell, or the cell itself.

**The answer is neither special case.** `{{mountId}}` is always the derived host id
(`Surface.hostId`) on the element that holds the children, and every node gets its `.fh-cell`
wrapper as ADR 0008 always intended. A container can have a cell because the cell is no longer
its morph target — its `self` is.

An intermediate draft had `Tabs`/`If` declare `wrapAsCell = false` to preserve today's sizing.
That was an abuse of the flag: a cell describes how a node is sized **inside its parent** and
says nothing about its children, so "this container must not impose layout on its content" was
never a reason to refuse one. See "The cell and the patch target become orthogonal".

Worked through with a `Grid` containing an `If` whose active branch holds a row of cards.

### Today

```html
<div class="fh-cell" id="c_0">
  <div class="fh-grid">                          <!-- Grid's own element, no id -->
    <div id="c_0_1">                             <!-- If: id from the template, NO cell -->
      <div class="fh-cell" id="s_c_0_1_then__c">…the branch…</div>
    </div>
  </div>
</div>
```

### After

```html
<div class="fh-cell" id="c_0">
  <div class="fh-grid">                          <!-- unchanged: never a fill target, needs no id -->
    <div class="fh-cell fh-cols-6" id="c_0_1">   <!-- If: a cell like everything else -->
      <div id="c_0_1_branch">                    <!-- If's mount, a plain block -->
        <div class="fh-cell" id="s_c_0_1_then__c">…the branch, filling it…</div>
      </div>
    </div>
  </div>
</div>
```

`Grid`/`Row`/`Column` are untouched — their flex container is never a fill target, so it needs no
id. `If` gains the universal cell, and its mount id now comes from `Surface.hostId` rather than
being hardcoded as `{{id}}`. `iff(p).columns(6)` sizes the `If`; the branch cell is a block inside
a block and fills it, with no CSS change.

`Tabs` gains a cell and one id:

```html
<div class="fh-cell fh-cols-full" id="c_2">
  <div class="fh-col">
    <div class="tabs" id="c_2-self">…anchors…</div>          <!-- NEW id: the patch target -->
    <div class="tab-panel" id="c_2_panel" data-signals="…">  <!-- unchanged: Surface.hostId -->
      …the open panel…
    </div>
  </div>
</div>
```

### The patches

A live tabs bar re-rendering — the case the split exists to make possible:

```
event: datastar-patch-elements
data: elements <div class="tabs" id="c_2-self"><a …>Lights</a><a …>Climate</a></div>
```

Outer, matched by its own id; the panel is a sibling and the cell an ancestor, neither mentioned.
What the same change sends today, which is the bug:

```
event: datastar-patch-elements
data: elements <div class="fh-cell" id="c_2"><div class="fh-col"><div class="tabs">…</div>
               <div id="c_2_panel">…the whole open panel…</div></div></div>
```

An `If` flip — a membership delta, not a fill (see "Containers record structure as mutations"),
so the mount id appears only as the append target:

```
event: datastar-patch-elements
data: mode remove
data: selector #s_c_0_1_then__c

event: datastar-patch-elements
data: mode append
data: selector #c_0_1_branch
data: elements <div class="fh-cell" id="s_c_0_1_else__c">…</div>
```

### What this settles, and what it costs

- **No new id scheme.** `{{selfId}}` is the only new id; `{{mountId}}` is `Surface.hostId` under a
  name; plain containers carry none.
- **`.columns(n)` on a `Tabs`/`If` starts working**, where today it is silently dropped.
- **One `wrapAsCell = false` card remains** — `TabButton`, for the genuine markup constraint.
- **The baselines move, once and deliberately.** `Tabs`/`If` go from content-sized
  (`flex: 0 1 auto`) to a cell with the decided `fh-cols-full` default. That is the intended
  section-level shape rather than a preservation of today's content-sizing, so **it needs a look in
  a browser (ADR 0006)** — the one part of this plan a terminal cannot check. See "The cell and the
  patch target become orthogonal" for the exact-today fallback if it regresses.

## Render at the edge: every rendering is for a viewer

**Status: designed, not implemented.** Supersedes the "flip revealing a mount" fill landed in
W6 (see "Filling a mount"), and with it the whole idea of rendering for nobody.

### What W6 got wrong

W6 made the shared pass render once for every client, which is right, and concluded that a
USER-selected mount therefore cannot be filled, which is not. It rendered such a mount EMPTY
(`Viewer.Nobody`) and had each connection fill its own afterwards. That works — it is tested and
it ships — but it costs two things that turn out not to be worth paying.

**Two DOM updates for one change.** A flip inserts the branch with a hollow mount, then a second
patch fills it. Both ride the same stream in the same write, so a paint between them is unlikely
rather than impossible; "unlikely" is not a property worth designing around.

**"For nobody" is not a real mode, and it leaked.** A mount carries client-dependent
ATTRIBUTES, not just client-dependent children: the tabs mount seeds its selection signal from
`bakeIndex`. Rendering it hollow emitted `data-signals="{ ui_x:  }"` — not valid, and not fixable
by filling the children, which is why the fill had to grow into replacing the whole element. The
mode was wrong, and every patch to it was a patch to the wrong thing.

### The correction: variants, not audiences

The audience is irrelevant. What a node's HTML depends on is entity state, plus which member is
mounted in each user-selected group inside it — and that second axis is a **closed set the server
already owns**: the members of that group. A tabs card with two tabs has two variants of its
subtree. Nothing about that requires knowing who is connected.

(An earlier draft of this section proposed reading the connected clients' open sets and baking the
member they all happened to agree on. That makes the rendered bytes depend on the audience — the
same dashboard in the same state producing different HTML depending on who is watching — for no
gain over doing it properly. Rejected.)

- **The shared pass decides; it does not render.** On a change it works out which nodes are
  affected and records that in the log — node X changed at version V — plus the structural
  mutations. Audience-independent, and exactly what statement (3) says the log is for.
- **The published item carries a memoised render per variant**, not finished bytes.
- **The connection assembles.** At send time it picks its own variant — from `session.open`, which
  is live truth, not the `uiState` it arrived with — and sends complete HTML.
- **The memo restores the sharing.** The first connection to need a variant forces it; the rest
  reuse the same string. Two clients on the same tab render once, which was the whole point of the
  shared pass and still holds. Two clients on different tabs render twice, which is unavoidable and
  correct: they are looking at different HTML.

### Why this needs no eviction policy

The memo's lifetime is the published item's. It is forced on demand, held by the subscriber queues
that still carry the item, and collected by ordinary reachability once every connection has passed
it by. Nothing outlives what it was computed for, so nothing can go stale.

Do **not** key it on the store version. That counter is global — bumped by any entity change
anywhere — so a humidity sensor would invalidate every node on every dashboard. A cache keyed on it
is permanently cold and pays only costs.

The memory profile is unchanged from today: a queued event already holds its bytes, and the same
object is shared across all subscriber queues. The only difference is that the bytes are computed
on first demand instead of up front.

### The property this buys: complete on first render

There is one rendering function and every call has a viewer. The document path, a live patch, a
resume, a mount fill — same function, same completeness. So:

> **4. Every rendering is for a viewer, and is complete when it leaves.** No path emits markup
> with a hole in it to be filled by a later message.

The first raw HTML must already contain everything — the dashboard has to be right before Datastar
loads, which is the same reason `c.navigate` ships an `<a href>` rather than a click handler. That
was always true of the document path; what changes is that it becomes true of *every* path, rather
than a property one path happened to have. "The first paint is complete" and "a mount created live
is complete" stop being two guarantees.

This does not weaken statement (1). That governs what a node's own *diff* emits — its `self`, never
its mount's contents. Creating a subtree (a mount fill, a flip's branch) legitimately carries one;
the change here is that it carries a COMPLETE one.

### What it deletes

`Viewer.Nobody`, `Patches.Reveal`, `Server.fillMount`, and the "flip revealing a mount" row of the
fill table. `Viewer` collapses to "the client's selections", which is the only thing it ever
sensibly was.

It also largely absorbs W13: rendering per connection at send time inherently does not render what
nobody is looking at, so the lazy-render gate stops being a separate mechanism.

### The seed becomes `__ifmissing`

Verified against the pinned bundle: `data-signals` takes an `__ifmissing` modifier (and the
`datastar-patch-signals` event an `onlyIfMissing` line), and the DEFAULT is overwrite.

That default is the mismatch. A `bakeIndex` seed means "initialise this signal if it has no
value"; plain `data-signals` says "assert this value", so every re-render of a tabs mount
overwrites whatever tab the client is actually on. Datastar signals live in a global store rather
than on the element, so with the modifier the selection simply survives the branch being removed
and re-added.

It closes two holes nothing covers today:

- **The click race.** A tab click sets `$ui` locally while its `@post` is in flight; any patch
  rendered from the server's older view lands in that window and resets the bar.
- **Hot-reload repaints.** `reloadRepaints` renders with the `uiState` captured at connect and
  re-seeds `session.open` from it, snapping a client back to the tab it opened the page on. That
  also wants `uiStateFrom(open)`, the same fix the fill took.

**It has to land WITH this phase, not before it.** Against the hollow render it would break the
case it looks like it fixes: a branch not yet visible since page load has no signal, so the hollow
mount's default index applies — and the fill can no longer correct it, because the signal now
exists. Tab 1's content under a tab 0 highlight, reached from the other side. Once every mount is
rendered for its viewer there is no wrong value to correct, and the modifier is purely protective.

### The digest

Suppression ("this entity ticked but this node's HTML is identical") needs the bytes, so the log's
digest stays — keyed by `(node, variant)` rather than node alone. For almost every node there is
exactly one variant and it is what we have today; only nodes with a user mount in their subtree
carry more than one entry.

### Deferred

A longer-lived `(state, node, variant) → HTML` cache spanning batches. That is where a reference
that the GC may reclaim earns its keep, and it wants `SoftReference`: a plain `WeakReference` entry
dies at the next GC regardless of memory pressure, so it would cache essentially nothing. Soft
references have their own drag on pause-time goals, so this is a decision to make against a
measurement rather than up front.

## Work items

| # | Change | Where |
|---|---|---|
| W1 | `CardDef` gains `self`/`mount` (`template` keeps its meaning, two holes); engine-owned `{{selfId}}` (new) and `{{mountId}}` (= `Surface.hostId` for a card, the node id for a dynamic group; required only on fill targets); document path renders `template` nested, patch path renders `self` alone | `Dashboard`, `Renderer`, `lib/components.pkl` |
| W1b | Delete the cell conditional's bake-owner branch — every node is a cell again, `TabButton` the sole opt-out. `Tabs` and `If` gain cells defaulting to `fh-cols-full`. `wrapAsCell = false` re-specified as "my root must not be wrapped in a layout box", no longer implying "never a morph target" | `Renderer.render`, `lib/components.pkl` |
| W2 | `CardDef` splits into `LeafCard`/`ContainerCard` (optional `self`) with type constraints; the "live slot + mount + no self" rule moves onto `Node.slots`. The slot-name checks and the three `wrapAsCell = false` rules STAY in `validate` — see the finding under "What changes in the authoring layer". Hoist-resolved relations (`bakeInto` → a card with a mount, dangling surface refs) stay too | `lib/components.pkl` |
| W3 | `patchTargetId(nodeId)` — the one-way node-id → DOM-id mapping, discriminated by whether the card declares a `self` (the same predicate that chooses what the patch path renders). Consolidate the structural-var derivation into ONE function so `render` and `renderCase` cannot diverge. Log keys stay node ids | `Patches`, `Renderer` |
| W4 | **The ledger**: opaque `NodeId`/`DomId`; `MemberKey` as a sum type owning its own `render`; `Fragment` holds a 128-bit digest; `Resume(nodes, moved, refill)` and `since` becomes TOTAL; `Patches.resume` renders from `(renderer, states)`; **re-target `coveredByAncestor` from fragments to mutations** as `coveredByMutation(nodeId, moved ++ refill)`, and drop the version sort's correctness role | `FragmentLog`, `Patches`, `Renderer`, `Server` |
| W5 | `resolveBake` becomes document-path only — the patch path never renders a mount | `Renderer.resolveBake` |
| W6 ✅ LANDED | Delete `Session.lastRendered` and `changedPatches`; collapse `Patches.Scope`'s two cases to one `visibleSurfaces: Set[String]` | `Server`, `Sessions`, `Patches` |
| W7 | Tag patches with `Option[surfaceId]` — the innermost **user-selected** surface, state surfaces transparent; filter per connection in `sseStream` | `Patches`, `Server` |
| W8 | The resume rule: candidates = `version >= cursor` ∪ `open ∩ reachable`; render from one snapshot; send on `version >= cursor \|\| fingerprint != stored`, a MISSING entry counting as send | `Server.openingPatches` |
| W9 | Reconnect repaint: invalidate the session's open surfaces' entries; delete the `sessionPaint` and popup-restore blocks. `reloadRepaints` drops `lastRendered.cleared` | `Server` |
| W10 | `flipStateGroup` emits membership mutations (`Gone` + `Placed`) instead of a host morph; `repaintGroup` emits a mount fill; both stop logging a container-level fragment. `Mutation` generalises to `(containerId, memberKey)`. The per-connection stage appends fills for user groups revealed by a flip | `Patches`, `FragmentLog`, `Server.sseStream` |
| W10b | **Every fill writes its members' fingerprints** — `swapHost` (select, popup open, flip-reveal) as well as the wholesale cases, or statement (2) is violated and the next live diff suppresses a real change (T4b). `uiState` leaves `swapHost`/`renderSurface` with it: surface content is client-independent, so only the document path needs it | `Server.swapHost`, `Renderer.renderSurface` |
| W11 ✅ LANDED | Retire `PopupSignal`/`popupOf`/`claimedPopup` in favour of `ui_<hostId>`; the open/close taps set it client-side like a tab button. The host reset stays — see "The resume rule" | `Server`, `Renderer`, `lib/components.pkl` |
| W12 ✅ LANDED | Delete `sessionOwnedMainIds`, `sessionOnlyStateGroups`, `subtreeHasUserOwner` | `Renderer` (fell out of W6 — `userOwnersIn` replaced `subtreeHasUserOwner`) |
| W13 (mostly absorbed by W16) | Lazy render: gate the render set on `⋃ session.open ∩ reachable` (reachability from `activeStateSurfaces` — the intersection is load-bearing); per-pass transform memo | `Server`, `Patches`, `Renderer` |
| W14 | `horizon` becomes `Map[gid, Long]` for DYNAMIC groups only (a state group's branches are a fixed set, so its mutations never accumulate); a cursor below a group's horizon puts that group in `Resume.refill`, so `coveredByMutation(nodeId, moved ++ refill)` drops its members. The refill carries the group's content in full and writes its members' fingerprints. Eviction can no longer trigger a body repaint | `FragmentLog`, `Patches.resume` |
| W16 | **Render at the edge**: `Tabs`' mount seeds via `data-signals__ifmissing` (with this item, never before it); the shared pass records the change set and publishes a memoised per-variant render instead of bytes; each connection assembles its own variant from `session.open` at send time. Deletes `Viewer.Nobody`, `Patches.Reveal`, `Server.fillMount`. The log's digest is keyed by `(node, variant)` | `Patches`, `Server`, `Renderer`, `FragmentLog` |
| W17 | Deferred, measurement-gated: a longer-lived `(state, node, variant) -> HTML` cache across batches, `SoftReference`-held | `Renderer` |
| W15 | ADR 0002 rewritten (the split is gone); ADR 0011 gains statements (1) and (3) and the resume rule; ADR 0008 gains the cell/self relationship; ADR 0007 checked | `docs/adr/` |

### W6 as landed

Three commits, and the middle one found a real bug.

**The tag comes from a parent pointer.** `surfaceParent` is `rootOf(bakeInto)` — a surface sits
where its host node sits — and `userSurfaceOf` walks it, passing THROUGH state surfaces (a branch
chosen by entity state hides nothing) and stopping at the first user surface, or at the main page
meaning "everyone". `stateGidsByRoot` was the same relation computed narrowly and is now `rootOf`.
Note the distinction that matters: deriving the chain by PARSING an id is impossible, but looking
the id up in `allIndexed` is exact — so `userSurfaceOfNode` needs no threading through `plan` at
all, which is better than what this document originally proposed.

**`visible` is a render gate, never a correctness input.** `Sessions.openIn(slug)` unions every
connected client's open set; it decides what is worth RENDERING once, while the tag decides who
RECEIVES it. Erring wide costs server bytes, erring narrow drops an update — so the union is the
only safe direction.

**The guard written first failed on the code as it stood.** Two clients on different tabs inside a
flipping branch: after the branch returns, both are shown tab 0. `sessionOnlyStateGroups` was meant
to prevent precisely this and did not — `flipStateGroup` renders the arriving branch with no client
at all, so routing it to the per-session pass changed which cache it was diffed against and nothing
else. A live defect, not merely an obstacle to deleting the pass.

The fix is to stop pretending a shared render can choose. `Viewer` names who a render is for:
`Client(uiState)` (an absent key is still a client — one who has not chosen) or `Nobody`, for which
a USER-selected mount renders EMPTY. The flip then emits a `Reveal` next to its mount patch and each
connection fills that mount with the member IT has open. This is the "flip revealing a mount" fill
W10 anticipated, and it is the ONLY per-connection rendering left.

With it, `planSession`/`changedPatches`/`Session.lastRendered`/`sessionOnlyStateGroups`/
`subtreeHasUserOwner` are gone, as is the exclusion of user bake owners from the shared selection —
under the split their patch is their `self`, which is state-pure. A connection no longer subscribes
to `StateStore.changes` at all; the harness readiness gates dropping from 2-and-3 to 1 is the
clearest statement of what changed.

## Landing order

The items are not independent, and one dependency dominates: **the opaque `NodeId`/`DomId` from W4
ripple through `Renderer`, `FragmentLog`, `Patches` and `Server`.** Landed late, each of those files
is touched twice — once in its old form, once retyped. Landed first, it is a mechanical
behaviour-free commit that everything after is written against.

| Phase | Items | Ships alone because |
|---|---|---|
| **0 — types** ✅ LANDED | the opaque `NodeId`/`DomId` half of W4 | pure retyping, no behaviour change |
| **1 — authoring + render** ✅ LANDED | W1, W1b, W2, W3, W5 | the split changes *what a patch targets*; the shared/per-session structure is untouched, so it works on today's two passes |
| **2 — the ledger** ✅ LANDED | W4 proper, W10, W14 | `Fragment` → fingerprint, `Resume` reshaped, flips become mutations — both passes still exist |
| **3 — the collapse** ✅ LANDED | W6 ✅, W7 ✅, W8 ✅, W9 ✅, W10b ✅, W11 ✅, W12 ✅, W13 (absorbed by W16) | the per-session pass dies here; nothing earlier depends on that |
| **4 — render at the edge** | W16 (W17 deferred) | needs the collapse landed first: it replaces the fill W6 introduced, and mostly absorbs W13 |
| **5 — docs** | W15 | last, so the ADRs describe what actually shipped |

**Phase 0 as landed**, since phase 1 is written against it. `NodeId`/`DomId` are
`opaque type X <: String = String` in `fh/view/model/Ids.scala` — the upper bound is deliberate (a
node id IS a string for interpolation and prefix tests, and widening at those uses costs nothing),
while the direction that matters, a bare `String` or a `DomId` where a `NodeId` belongs, stays an
error. Three consequences worth knowing:

- **`NodeId` → `DomId` is three functions on `Renderer`, not one.** `patchTargetId` (what a content
  morph aims at — this is the one W3 makes discriminate), `elementId` (the node's own `.fh-cell`,
  what a `remove` deletes and an `insert` anchors `before`) and `mountId` (where children go).
  All three are identity today, and separating them up front is what keeps W3 from silently
  re-targeting a `remove`: once a container morphs its `self` alone, "what I morph" and "what I am"
  stop being the same element.
- **`Patch.Insert`/`Patch.Remove` name a `DomId`, not a selector string**, and the `#` is added in
  `toSse`. That is what makes the crossing unavoidable rather than a convention.
- **`Surface.bakeInto` is `Option[NodeId]`** with a `given Decoder[NodeId]`, parsed at the wire
  boundary rather than re-wrapped at each use — `bakeOwnerIds` on `Renderer` is now the one place
  that authored relation enters. Test suites get a `given Conversion[String, NodeId]`
  (`testkit/TestIds`) instead of ~130 wrappers: the type guards the server, and a suite's literal
  id IS the spec.

**Phase 2 as landed** — two things the plan did not name:

- **`Mutation.Gone` had to gain its container.** The per-group horizon keys on "whose membership
  history just became incomplete", and a `Gone` only carried the departing node's id — from which
  the container is NOT derivable (a group id contains underscores and so does a sanitised entity).
  So `container` moved onto the `Mutation` enum itself and both cases carry it, which is better
  typing regardless: every structural fact now says whose mount it is about.
- **`Renderer.renderLogged` and `renderMount` are the two inverses the ledger needs.** Because the
  log holds a digest, a resume RENDERS its candidates rather than reading them back, so every key
  must resolve — including a dynamic member id, which is per-entity and deliberately not in the
  static index. `renderLogged` resolves both kinds (and returns `None`, rather than crashing, for a
  key naming nothing that exists now); `renderMount` answers "what is in this container's mount"
  for a fill, for either kind of container.

**The property that makes phase 1 safe is worth stating on its own: the self/mount split is
independent of the shared-log collapse.** It is the riskiest change in the plan — it moves DOM ids,
moves the wire snapshots once, and re-takes the `Tabs`/`If` visual baselines — and it can land and
soak by itself, on the existing two-pass structure, before anything about the log changes.

The one boundary genuinely open to argument is 2 / 3: W8's resume rule and W6's `changedPatches`
deletion are the two halves an implementer would most want to exercise together, so folding them
into one phase is defensible if the intermediate state proves awkward to test.

## Tests

| # | Test | Suite | Pins |
|---|---|---|---|
| T1 | A container's patch-path render is its `self` element only — the mount's id does not appear in it at all | `RendererSuite` | **statement (1)** |
| T2 | A host slot change patches `#c_2-self` and emits nothing for the panel's nodes; the panel's DOM is untouched | `ServerSuite` | the principle end to end |
| T3 | A high-traffic tab is not pushed to a client viewing another tab — A on t0, B on t1; A receives **zero** events mentioning `s_<gid>_t1` | `ServerSuite` | **PRESERVES today's behaviour** |
| T4 | A **sole** client reconnects after its tab went unrendered: only the nodes that actually changed are sent; a reconnect with nothing changed sends nothing | `ServerSuite` | the resume rule's two disjuncts |
| T4b | Fill-then-cycle-back on the LIVE path: select a surface, let a node change and change back, no reconnect — the client must not be left showing the pre-change value | `ServerSuite` | **statement (2)** |
| T4c | `since` returns node ids, and a resume sends HTML rendered from the CURRENT snapshot | `FragmentLogSuite`, `ServerSuite` | **statement (3)** |
| T4d | After a full diff pass — on a dashboard WITH a dynamic group — every log key is resolvable by the renderer: a static node id or a dynamic child id. `forall(allIndexed.contains)` alone is WRONG; member ids are per-entity and not in the static index. (A `DomId` leaking in is now a COMPILE error, so this pins only the resolvability half) | `ServerSuite` | the ledger renders content from keys, so an unresolvable key is a fragment that can never be sent |
| T5 | A surface the client never selected gets nothing on reconnect; selecting it then yields full current content | `ServerSuite` | the cursor selects candidates, never content |
| T6 | Selecting a tab fills it on that connection only — A gets one Inner patch into `#<gid>_panel`; B gets nothing | `ServerSuite` | fill-on-select is per-connection |
| T7 | A popup is filled and resumed by the same path as a tab, with no popup-specific patch on reconnect | `ServerSuite` | one mechanism |
| T8 | An `If` flip emits `remove` + `append` membership mutations, not a fill; a flip revealing a user group additionally fills THAT mount per connection with the client's member | `ServerSuite` | **the flip is a delta** |
| T8b | A client disconnects, an `If` flips while it is away, it reconnects: it gets `remove` for the old branch and the new branch inserted — and NOT a no-op morph per node inside the new branch | `ServerSuite` | the hole this closes, plus `coveredByMutation` |
| T9 | The document render bakes the client's selection, `bakeIndex` included (flash-free refresh onto a non-default tab, no signal reset) | `RendererSuite` | the document/patch split is deliberate |
| T10 | A state-selected host still renders only the active branch | `RendererSuite` | hidden-branch guarantee |
| T11 | A repaint invalidates the open surfaces' entries: a node changing back to its pre-repaint value afterwards is still sent | `ServerSuite` | the repaint's one obligation |
| T12 | Pkl rejects, at eval: a `self` without `{{selfId}}`; a `self` containing the mount hole; a LIVE slot on a container with a mount and no `self`. And ACCEPTS `Grid` unchanged, `class` slot and all. (The slot-name pair and the `wrapAsCell` rules stay in `validate` — see W2) | `PklBuildSuite` | unconstructable by authors |
| T12b | `validate` still rejects a surface whose `bakeInto` names a card with no mount, and a node naming an unregistered card | `DashboardSuite` | what Pkl cannot see, plus the lookup's failure arm |
| T12c | The emitted registry carries `mount`+`self`+`slots` for `Tabs`, `mount`+`slots` for `Grid` (its `class` slot), `mount` alone for `If`, and no `mount` for a leaf | `PklBuildSuite` | polymorphic emission, no tag field |
| T13 | The two Datastar contracts — sibling isolation with its wiping control, and `data-ignore-morph` in both directions | `DatastarMorphContractSuite` | upgrade guard: on failure the split is unsafe, not the test |
| T14 | Selecting t1 deselects t0 in `open` (host exclusivity) | `ServerSuite` | the filter stays accurate |
| T14b | No log key holds a fragment containing another node: after a group repaint the log has member entries and no `gid` entry | `ServerSuite` | statement (1) without exception — what makes `coveredByAncestor` deletable |
| T15 | A surface no live session has selected is not rendered — no patches, no fingerprint advance | `ServerSuite` | the lazy-render gate |
| T15b | A tab surface selected in `open` but sitting inside an INACTIVE `If` branch is not rendered; it starts rendering only once the branch flips active | `ServerSuite` | the reachability intersection — the hidden-branch guarantee |
| T15c | One group's mutations aging out does NOT repaint the body: a client resuming below that group's horizon gets a refill for that group and ordinary deltas everywhere else | `ServerSuite` | per-group horizon — the blast radius |
| T15d | A refilled group appears in `Resume.refill`, its members are NOT also sent as individual candidates, and the refill advances their fingerprints | `ServerSuite` | the fill carries a subtree — no duplicate, no stale baseline |
| T16 | Wire snapshots move once, deliberately. Visual baselines: unchanged for leaf cards and plain containers; re-taken ONCE for `Tabs`/`If`, which gain a cell — after browser confirmation, never regenerated blind | `PklBuildSuite`, `ComponentVisualSuite` | the only deliberate visual change in the plan |
| T16b | A container WITH a `self` and `wrapAsCell = false` and a live slot is ACCEPTED; a leaf with the same is rejected | `PklBuildSuite` | `wrapAsCell` no longer implies "never a morph target" — the rule conditions on card shape |
| T16c | `.columns(6)` on a `Tabs` node reaches the rendered `.fh-cell` classes | `RendererSuite` | today it is silently dropped |
| T17 | The tabs demo end-to-end through the real Pkl path | `PklDashboardBehaviourSuite` | Tier-A |

Browser confirmation via `sbt dashboardServe` is required on two counts (ADR 0006): the
`Tabs`/`If` cell default is a deliberate visual change (T16), and the behaviour under reconnect is
the part a terminal cannot see at all.

## The accounting

**Deleted:** `Session.lastRendered`, `changedPatches`, `sessionOwnedMainIds`,
`sessionOnlyStateGroups`, `subtreeHasUserOwner`, the `sessionPaint` and popup-restore blocks,
`PopupSignal`/`popupOf`/`claimedPopup`, the rendered HTML in `Fragment`, and `since`'s
version-ordering argument. (`coveredByAncestor` is NOT deleted — it is re-targeted from fragments
to mutations as `coveredByMutation`.)

**Collapsed:** `Patches.Scope`'s two cases become one `visibleSurfaces: Set[String]`. The global
`horizon` becomes per-group, so mutation eviction can no longer force a whole-body repaint —
the wholesale fallback shrinks from the dashboard to one group.
Resume-by-history and reconcile-by-render become one rule. The `If`'s host morph + prune becomes
membership mutations — the same record dynamic groups already keep — and the group repaint becomes
a mount fill, the same operation as tab select and popup open.

**Survives:** `Session.open` (it *is* the per-connection filter and the render union), `swapHost`
and both surface routes, `resolveActive`/`selectedSurfaces`/`uiStateAnomalies`, `uiState` through
`Renderer` (document path only), `userBakeOwnerIds` and `stateBakeOwnerIds` (which mounts exist,
and who decides their selection).

**Added:** `CardDef` as two classes (`LeafCard`/`ContainerCard`, the latter with an optional `self`)
with the engine-owned `{{selfId}}`/`{{mountId}}`; opaque `NodeId`/`DomId` and the `MemberKey` sum
type; patch tagging with `Option[surfaceId]`; the per-connection filter; the lazy-render gate; and
K renders per reconnect in place of K log reads.

**Made unrepresentable rather than checked:** a `DomId` used as a log key (opaque types), a
container's card shape violating the split (Pkl classes + constraints), and a per-kind dispatch on
how to resolve a member (`MemberKey` owns its own `render`).

**Net:** one render pipeline instead of two, one log instead of N+1, a log that cannot lie about
content because it holds none, and a container that cannot disturb its children because its patch
does not contain them. **ADR 0002's shared/per-session split collapses to "everything is shared,
some of it is filtered."**
