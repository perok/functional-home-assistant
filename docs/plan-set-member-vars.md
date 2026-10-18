# Plan: node variables inside a candidate set's clause (#556)

Part of node variables (#209, ADR 0033). Two steps, in order: a member's children become normal
nodes, then a clause can declare a variable of its own.

## What changes for an author

```pkl
q.from(dump.lights.all).caseOf(q.eq(q.stateProp, "on"), c.entitySlider)
```

Each light's slider keeps its own main control (brightness, colour temperature, colour),
switched on its own, and a switch swaps only that light's head. Today a slider rendered per
member keeps its first control, because the build refuses a declaration inside a clause.

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
  through the member that owns it; `Membership.binding` answers leaves, not member roots.
- **Signals are each node's own.** A child seeds its own signals on its own wrapper, as in the
  static tree; the member-wide seed goes.
- **Unchanged:** arrival, departure, order and a clause switch still move the member as one
  element; a nested set keeps `innerSetId`, whose clause index keeps two clauses' sets apart.

Deleted with it: `ResolvedMember`, `ResolvedChild`, `resolveMember`, `resolveChild`,
`renderResolvedMember*`, `memberBodyInto`, `memberChildInto`, `memberSeedOf`, `memberSignalsOf`.

### Gates

- The wire snapshots and visual baselines of sets: only ids and seeds may move, not the look.
- `RenderBench.pageSet`, `pageSetPlain` and the resume cells, before and after; and the member
  graph's size on a big set.
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

- `Dashboard.varScopes` walks each member's clause nodes under the member's id, from the set's
  scope; every clause of a candidate shares the id, so their scopes merge and a declaration in one
  clause survives the member moving to another. `VarGraph`'s `setOfMember` fallback goes.
- `validate` drops the refusal and sees member nodes as places a surface can bake into.
- The slider's `perMember` workaround (keep the first control) goes, and the flag with it.

Done on the local branch `set-member-vars-wip`, waiting for step 1.

### Tests

- Through the server: two lights in a set, each with its own control. A write to one member's
  variable moves only that member's head. A member leaving and returning shows the choice made
  before it left.
- `validate`: a declaration in a clause builds.
- The three tests pinning the refusal and the first-control workaround are rewritten (approved
  2026-10-18).

## Docs touched

ADR 0017 (a member's children), ADR 0003 and ADR 0033, the rendering pipeline (§4a, §4b, §5, §6),
terminology's "member", the module CLAUDE.md's slider paragraph.
