# Plan: an `If` on live state inside a set's clause

Follows #556 (`docs/plan-set-member-vars.md`), which made a member's children normal nodes and
gave a bake group inside a clause an id of its own (`Surface.group`). What is left is the one
refusal that work kept: `validate` rejects an `If` chosen by entity state inside a clause.

## What changes for an author

```pkl
q.from(dump.climates.all).where(<heating>).render((t) -> (c.column) { children {
  c.entityCard(t)
  c.iff(q.from(<windows in t's area>).where(q.eq(q.stateProp, "on")).any()).then(c.title("Window open"))
}}).build()
```

A clause's own condition reads only the member and switches its whole card. An `If` inside one
can read another entity, and switches a part of the card, so two independent conditions do not
need four clauses.

## Why it is refused today

A flip is found by walking the page's state groups from its root
(`SurfaceGraph.affectedStateGroupsFrom`), and the branches that stay silent are found the same
way (`activeStateSurfacesFrom`). Neither knows that a group inside a member exists only while the
member is present and in that clause. The groups themselves are already static and distinct per
clause, so ids are not the problem.

## The shape

- **Flips from the changed entities, not from every group.** An index `entity -> state groups
  whose condition reads it` (the inverse of `stateGroupEntities`, static). A frame looks up the
  groups its changed entities name; one flips if its selected branch differs before and after,
  and it is reached: every state group above it is on the branch that holds it, up to the root
  being walked. Today every group's condition is evaluated on every frame to decide where to
  descend, which is free at five `If`s and is 200 evaluations a frame at 200 members.
- **A group inside a member counts only while the member is there.** Present, in the group's
  clause, before and after the frame. A member arriving or switching clause is placed whole, and
  that render already picks the branch from state (`resolveBakeTraced`), so it needs no flip.
- **Hidden branches stay silent, per candidate.** `Patches.plan` asks of each surface holding a
  node that binds a changed entity whether it is active (the same walk up), instead of building
  every active surface first. An index `entity -> surfaces binding it` makes that a lookup.
- **`validate` drops the refusal.**
- Page open and a member's placement keep their current paths: they walk what they render.

## Cost

- **A frame:** proportional to the groups and surfaces that read a changed entity, times their
  depth, not to every group on the page. A frame touching nothing inside an `If` costs two map
  lookups per changed entity.
- **Memory, the renderer:** the two inverse indexes, one entry per (entity, group) and (entity,
  surface). Static, built with the graph: a 200-member set with one `If` each adds about 200 group
  entries.
- **Memory, a session:** nothing new; a shown branch's leaves are held as any leaves are.

**Gates** (JMH `-prof gc`, before and after on one machine, alternating runs):

- `publish`, `publishSet`, `publishFlip`, `resumeFlip`: no worse.
- New `publishSetIfs`: a 200-member set, each with an `If` on another entity. A frame that ticks
  an unrelated entity costs about what `publishSet` does; a frame flipping one member's condition
  costs one member's flip.

## Tests

Through the server, a set whose clause holds an `If` on another entity:

- Flipping the condition sends the branch for each present member, nothing for an absent one.
- A member arriving, or switching clause, shows the branch the state picks.
- A tick on an entity inside a hidden branch sends nothing; inside the shown one it sends the leaf.
- `validate`: the clause builds.
- The flip index agrees with the walk it replaces: for random frames over the shipped fixtures,
  the same groups flip (a property test, since the walk is the reference).

## Docs touched

ADR 0007 (the `If`), the rendering pipeline's flip section and file map, terminology's "flip",
`writing-dashboards.md` (an `If` inside a set's card).
