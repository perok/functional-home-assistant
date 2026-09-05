# Plan — one rendering, many entities

**Status: investigation, no code.** Issue [#108](https://github.com/perok/functional-home-assistant/issues/108),
narrowed to the half that is actually about replication. Nothing here is decided; the questions at
the end are the maintainer's.

The prompt was more-info, which doubles an unscoped set. The answer turned out not to be about
more-info at all, so this is written as the general question: **when a dashboard says one thing
about six hundred entities, what does it ship six hundred times, and why?**

## What actually replicates

Measured with in-process pkl-core against a synthetic mixed house — 600 entities over six domains
(light with colour temp, on/off light, sensor, binary_sensor, switch, media_player), authored as
`q.from(dump.all).where(state == on).render((e) -> c.entityCard(e))`. Compact JSON, no
pretty-printing.

| | |
|---|---|
| whole document | 588 KB |
| the set's members | 531 KB — **90% of it** |
| per member | 886 B |
| eval | 368 ms |

Now mask each member's own entity id and friendly name and count the distinct trees:

| masking | distinct member trees (of 600) |
|---|---|
| nothing | 600 |
| the entity id | 600 |
| the entity id **and** the label | **5** |

Five: one per domain, plus one per capability split. So the members are not six hundred different
things. They are five things, said six hundred times, and the whole of the difference between two
members of the same domain is **their id and their name** — 39 bytes of the 886.

Encoding that directly — five trees plus a per-member `(id, name)` — is 34.5 KB against 531 KB:
**15.4× on the members, 6.5× on the document**. And 30 KB of the remaining 34.5 KB is the per-entity
data itself, which is the floor. There is no third thing to remove afterwards.

### Plain subtree sharing does not get there

A `$ref` table over repeated subtrees — one concept, no holes, provably invertible — was the
attractive cheap option. Measured on the same document: **1.91×**. Almost nothing shares, because
almost every subtree has an entity id or a name somewhere inside it. **Holes are load-bearing**, and
any design that avoids them is avoiding the win.

## Why: two bakes, one of them deliberate

The 600-vs-5 gap is entirely **build-time baking of entity-derived display values**.

- **The label.** `slot.pkl`'s `labelSlot` bakes `friendly_name` as a literal when the author names
  no label. ADR 0004 records this as a *decision*, not an oversight: the default used to be a live
  `friendly_name ? … : $entity_id` expression, kept alive by the `$self` sentinel because a query
  group's member had no name at build time. Candidate sets made every candidate known at build time
  (ADR 0003), so the sentinel went and the literal became unconditional — "beats a memoized
  transform outright". That reasoning is about *render* cost and is still right. What it did not
  weigh is that a baked literal is per member and a transform is per shape.
- **The icon.** Same shape of fact, smaller: `mdi-thermometer` from the device class. It varies per
  *device class*, so it splits shapes rather than defeating them.

Two things I expected to be blockers and measured as not:

- **The inline more-info popup does not prevent sharing.** Its surface id is the `@@NODE_ID@@`
  token, resolved per node at hoist — already a hole, already identical across members. More-info
  is not structurally special; it just contains a **second entity card**, so it bakes the label
  again. That is the whole of its cost: 500 sensors are 743 KB with the default tap and 378 KB with
  `c.tap.toggle` — a second baked copy of the same entity, nothing more.
- **A set's guards are already cheap.** 63 B per member, and they genuinely differ per member
  (ADR 0003 is explicit that presence residuals diverge). Nothing here proposes touching them.

## What the runtime already does

This is the part that makes the fix smaller than it looks. **A set member is already one rendering
instantiated per entity — at the runtime.** `MemberGraph` mints `MemberId = <setId>_<slug>` for
nodes that are not in the static tree, keeps their reverse-index edges, and knows where each one
hangs (`sourceRoot`). The wire ships N copies of a rendering into a runtime that was going to
materialise N members from it anyway.

Two existing mechanisms are the precedent for the missing piece:

- **`@@NODE_ID@@`** — a hole in an authored value, filled per node when the node is materialised.
- **Slot inheritance from the subject** (`Dashboard.SubjectSlot`, `Renderer.buildPlan`) — every slot
  on a node reads the `entity_id` slot unless it names its own entity, and the renderer already
  supports the subject being resolved per paint rather than being a literal.

So "fill the entity into a shared rendering" is not a new kind of thing; it is the existing hole
mechanism applied to one more kind of hole.

## Three designs

### A — the member names its rendering

The wire stops repeating the node:

```
SetNode {
  candidates: [entityId]
  renderings: [Node]                                  // holes where the entity showed through
  members:    { entityId -> [ {when, rendering: Int, vars: [String]} ] }
}
```

A member keeps its guards (which really are per member) and gains an index plus the handful of
literals that were its own. The runtime fills the holes where it already materialises the member.

**Which literals are holes is decided from evidence, not by a rule the author has to know** — the
failure the previous attempt hit. Render each member's clause a second time against a *sentinel
copy of its own entity* (`(e) { entity_id = "@@ID@@"; friendly_name = "@@NAME@@" }` — Pkl's late
binding re-derives everything computed from those fields, and the capabilities are unchanged, so
the structure is identical); the positions that differ are exactly the identity-derived ones. This
is count-independent — one member and a thousand produce the same shape — which the reverted
design had to bolt on afterwards.

The two bug classes cited in the revert do not come back the same way:

- *a clause carrying another clause's literals* — vars are positional within one clause and
  `validate` can check the count against the clause's hole count;
- *two cards with different cells silently sharing a shape* — sharing is keyed on the author's own
  clause, never on a structural hash, so two clauses never merge.

**Cost:** the sentinel render doubles the render-lambda applications, ~100 ms per 1000 members on
today's numbers. It buys wire and memory and pays in eval time — the opposite trade from what the
edit loop wants, unless the sentinel render is done once per capability group rather than per
member.

### B — stop baking what the runtime can read

Make the default label a live read again — undoing the ADR 0004 paragraph above, with the evidence
it did not have. Measured effect on its own: the 400 members that carry a domain action collapse
from 400 distinct trees to **3**. The 200 with a more-info popup do not, because the popup's own
card bakes its own label; fixing that too is one line in `moreinfo.pkl`.

By itself this shrinks nothing — the wire still repeats the tree — so B is not an alternative to A.
It is what makes A's shape count 5 instead of 600, and it is worth deciding on its own merits:
a renamed entity currently shows a stale label until the dump refreshes, and a live label is right
about that where the bake is wrong.

Against it: an extra transform per card per render, which is exactly what ADR 0004 traded away, and
a label that reads blank if the state store has not seeded.

### C — a subject-parameterized surface

The more-info-shaped fix: register **one** more-info surface per shape and give the tap a subject
(`/sse/surface/:slug/open/__more_info__/light.taklys`), instead of one inline surface per entity.

I looked at what this costs and it is the most expensive of the three, for the least. A surface
whose content depends on which entity *this viewer* opened it for breaks the property
`architecture-rendering-pipeline.md` §5 states plainly — "a viewer's SELECTION is not in the key,
and does not need to be" — so either the subject enters `RenderInputs` (and
`RenderCacheContentionSuite`'s 1.0-renders-a-frame contract changes meaning), or it enters the node
id, and the renderer must synthesize nodes for ids the static tree does not contain. The second is
what `MemberGraph` already does, and ADR 0022 lists the three ways it went wrong — a missing
reverse-index edge, a container read from the static index, a `sourceRoot` that read as main-page
and leaked one client's patch to every connected client.

And the payoff is small, because **an unopened surface costs no CPU at all** (ADR 0003: a set inside
a surface nobody has open is not recorded). The bytes it saves are exactly the bytes A already
saves. C is worth doing when a detail view must show something the build cannot know — not for this.

## Recommendation

**B then A, and not C.** B is a one-line default with an ADR to rewrite and an argument that stands
on its own; it is also what shrinks A. Over baked labels, A needs a per-member `vars` list and gets
15.4×. Over live labels the only per-member value left is the entity id — which `candidates`
already carries — so a member is reduced to its guards and a rendering index, the hole mechanism
collapses to the subject alone, and the ceiling is the 28× measured on a homogeneous set.

Before either, one number is missing: **whether memory or eval time is the thing that hurts.** A
buys wire and memory and costs eval; nothing measured here says the 588 KB is a problem for the
process holding it. The regression guard #108 asks for is still the honest first move, because it
makes the answer visible.

## To decide

1. **Is a live label acceptable again?** It reverses a recorded decision (ADR 0004) and pays a
   transform per card per render to save 40% of a set's bytes. This is the fork everything else
   hangs off.
2. **Where does the subject belong — the node id, or the render key?** C needs the answer; A does
   not, so answering it can wait.
3. **Is `dashboard.json` size a real cost, or only a proxy?** It never reaches a browser. If the
   pain is the 900 ms edit loop, A is the wrong lever and the right one is elsewhere.

## Not verified

- Everything is a synthetic house. The real instance's shape count could be higher — 1069 entities
  across more domains, with device classes splitting sensors further.
- The sentinel-render trick is reasoned from Pkl's late binding, not spiked.
- No measurement of what the decoded model costs in heap, which is the whole of A's memory claim.
