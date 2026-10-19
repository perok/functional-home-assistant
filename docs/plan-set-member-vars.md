# Plan: node variables inside a candidate set's clause (#556)

Part of node variables (#209, ADR 0033). Two steps, in order: a member's children become normal
nodes, then a clause can declare a variable of its own.

## What changes for an author

```pkl
q.from(dump.lights.all).caseOf(q.eq(q.stateProp, "on"), c.entitySlider)
```

Each light's slider keeps its own main control (brightness, colour temperature, colour),
switched on its own, and a switch swaps only that light's head. Before this plan a slider
rendered per member kept its first control, because the build refused a declaration inside a
clause.

## Step 1: a member's children are normal nodes

### Why

A member is a node (`MemberGraph`, pipeline §4b), but its children are not: `renderMember`
draws them inline with the member's own id (`structuralVars(m.id)`), no ids of their own and no
baked regions, and the member is their one patch target (ADR 0017). That path dates from the
runtime-query groups, whose members could not be known ahead. Candidates have been static since
ADR 0003, so every id below a member is known at build time; the build's hoist already mints
them (`DashboardBuild.walkMembers`), and nothing at runtime uses them.

What that costs today, besides #556:

- A slider member's head is not a patch target, so a brightness tick re-renders the whole member,
  rows and pill included, where the same slider outside a set patches its head.
- A bake group inside a member renders empty (its host is the member's id), so neither a slider's
  main controls nor an `If` work inside a clause.

**Nothing here is new machinery.** Bytes that differ per viewer already exist in the static tree:
a bake group (a tab panel, an `If` branch, a slider's main controls) renders the viewer's member,
and the rule that keeps that safe is the leaf/structure split. A node holding regions is
structure, never a patch target and never cached, so per-viewer content never enters the per-slug
log or `RenderCache`; only leaves are shared. A member today breaks that rule: it is a log key and
a cache entry whatever its card, which is only safe because nothing inside it can vary per viewer.
Step 1 makes members follow the rule the rest of the page already follows.

### The shape

- **One render path.** A member's subtree is walked by the same code as the static tree, rooted at
  the member's id; each child's id is `LayoutNode.childId(prefix, member, step, child)`, the id the
  hoist and `Dashboard.varScopes` already use. `{{id}}` and `{{hostId}}` are each node's own, so a
  baked region inside a member fills under its own host.
- **The card rule holds inside a member.** A leaf is a patch target and structure is not, as
  everywhere else (pipeline §4a). A member whose card is a leaf (a tile) patches as today; a
  member whose card is structure (a slider) patches through its leaves (its head).
- **Lookup by id goes through the member's current clause.** Every `Member` instance (one per
  clause per candidate, built with the graph) carries the index of its subtree: id → node, and
  entity → the leaves binding it. `renderNodeById` and `renderInputs` resolve a descendant
  through the member that owns it; `Membership.binding` answers the nodes inside members that
  bind the entity, not member roots.
- **Signals are each node's own.** A child seeds its own signals on its own wrapper, as in the
  static tree; the member-wide seed goes.
- **A member holding nodes is placed whole, per viewer.** It has no one element to send, so its
  arrival and a clause switch insert it rendered for this viewer (`renderMemberTraced`), claiming
  its leaves; a leaf member still comes through the cache and morphs in place.
- **A nested set keeps its clause in its id** (`LayoutNode.innerSetId`, now in the model), so the
  runtime walk, `Dashboard.varScopes` and `validate` spell it once.
- **Not in this step:** a branch chosen by live state inside a clause. Flips are found from the
  static tree, so `validate` refuses it with a message that says so (today it fails as a dangling
  bake).

Deleted with it: `ResolvedMember`, `ResolvedChild`, `resolveMember`, `resolveChild`,
`renderResolvedMember*`, `memberBodyInto`, `memberChildInto`, `memberSeedOf`, `memberSignalsOf`,
`renderSet`, `setElement`, `executeInto`, and `VarGraph`'s `setOfMember` fallback.

### Cost: CPU and memory

A member whose card is a leaf (a tile, a plain entity card) renders, keys and patches exactly as
today. Everything below is about a member whose card is structure (a slider, a column of leaves).

**CPU, a live tick: cheaper.** Today a tick re-renders the member's whole subtree and sends all of
it; after, it re-renders the leaves that bind the entity and sends those. A brightness tick on a
slider member renders its head, not its rows and pill.

**CPU, a page open or a set fill: slightly dearer.** The walk is the same work per node as today,
but every leaf inside a member gets its own digest for `holds` where today the member gets one.
The bench's starting numbers put a digest at about 0.45 µs (`holdsSeed`: 200 SHA-256 in 90 µs),
so a 200-member set of two-leaf columns adds about 200 digests, ~90 µs, on a `pageSetTiles` that
costs well over a millisecond. Each leaf also seeds its own signals, so the member-wide seed cache
(`memberSeeds`, which won 12.5% of `pageSet`) is replaced by the static tree's plan-time seeds,
which do the same job per node.

**Memory, the renderer: small and static.** Each `Member` instance (one per clause per candidate,
already built with the graph) carries its subtree index: id → node and entity → leaves. That is
one entry per node below a member: ~600 for 200 two-leaf tiles. Node plans are already one per
authored position below a member (`resolveChild` keys them by member and path), so their count
does not change.

**Memory, a session: grows with leaves.** A session's `holds` keeps a digest per patch target it
was sent. Today that is one per member; after, one per leaf inside a structure member: two per
member for a two-leaf tile, so a 200-member set adds ~200 entries per open session. The per-slug
`RenderCache` keys the same leaves, and each entry holds a leaf's bytes rather than a member's, so
its total bytes stay about the same.

**Gates, measured before and after on the same machine, alternating runs:**

- `RenderBench.pageSet`, `pageSetPlain`, `pageSetTiles` (the structure-member case),
  `resumeMembers`, and the `publish*Set` cells: a page open may cost up to the digest estimate
  above, a tick must not cost more.
- Allocation per op from the same runs (`-prof gc`), and the renderer's retained size with a
  200-candidate set of sliders (a heap histogram, before and after).
- The wire snapshots and visual baselines of sets: only ids and seeds may move, not the look.
- A test of the property: every element id a member's markup carries is one the renderer can
  render by id (as `SetNodeSuite` does for nested sets).

## Step 2: per-member variables

### Decided (2026-10-18)

- **Per member.** Each member's copy of a clause declares its own variable.
- **The server keeps a member's last choice while it is away.** A member's declarer id is fixed at
  build time, so its choice stays in the session under that id; when the member comes back, it
  shows what was last chosen.
- **The URL entry goes with the member**, by the mechanism already built: a declarer's
  `data-fh-url` releases its parameter when its element leaves the page.
- **No card is re-sent** on a choice: the write swaps the head's host, as outside a set (#209:
  a variable unifies where a reader's value comes from, not how it is drawn).

### How

Step 1 already made `Dashboard.varScopes` walk each member's clause nodes under the member's id
(their scopes merge, so a declaration in one clause survives the member moving to another),
dropped `VarGraph`'s `setOfMember` fallback, and let `validate` see member nodes as places a
surface bakes into. What is left:

- `validate` drops the refusal of a declaration inside a clause.
- The slider's `perMember` workaround (keep the first control) goes, and the flag with it.

### Tests

- `SetMemberVarsSuite`, through the server: two lights in a set and one above it, all declaring
  `control`. A write to one member's variable moves only that member's choice and sends only its
  head. A member leaving and returning shows the choice made before it left. A page link naming
  one member's choice opens only that member on it, and each member mirrors its own URL entry. In
  the default dashboard's shape (a slider while on, a card while off), a choice survives the
  member moving to the other clause and back. An `If` over a member's own variable swaps only
  that member's branch.
- `validate`: a declaration in a clause builds, and its readers resolve to the member.
- The three tests pinning the refusal and the first-control workaround are rewritten (approved
  2026-10-18).

## Docs touched

ADR 0017 (a member's children), ADR 0003 and ADR 0033, the rendering pipeline (§4a, §4b, §5, §6),
terminology's "member", the module CLAUDE.md's slider paragraph, and `writing-dashboards.md`'s
sections on sets and on viewer choices (#592's `c.iff` over a variable included).
