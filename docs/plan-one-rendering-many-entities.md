# Plan — one rendering, many entities

**Status: investigation, no code.** Issue [#108](https://github.com/perok/functional-home-assistant/issues/108),
narrowed to the half that is actually about replication. Nothing here is decided; the questions at
the end are the maintainer's.

The prompt was more-info, which doubles an unscoped set. The answer turned out not to be about
more-info at all, so this is written as the general question: **when a dashboard says one thing
about six hundred entities, what does it ship six hundred times, and why?**

**The conclusion is to build none of it.** After the fold fix (#327) evaluation is linear and
~150–330 ms at real house sizes, and the artifact gzips 30–43×, so the two costs that motivated
#108 are gone. What replicates and why is recorded below anyway, because it is real, it will be
noticed again, and the next person should not have to re-measure it.

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

## Is eval the problem? No — measured after the fold fix

The question this investigation was really for. A house shaped like a real instance (mostly
sensors, some lights, mixed device classes), evaluating the two query shapes `pkl-demo` runs — an
unscoped `dump.all` set plus the `q.optional(q.prop("device_class"))` battery filter — min of 7
runs, in-process pkl-core:

| entities | dump + entry, no cards | demo-like, before the fold fix (#327) | after |
|---|---|---|---|
| 250 | 52 ms | 91 ms | **69 ms** |
| 500 | 36 ms | 119 ms | **90 ms** |
| 1000 | 43 ms | 233 ms | **150 ms** |
| 2000 | 63 ms | 515 ms | **331 ms** |

Byte-identical output on both sides. Three things this says:

- **The typed dump module is a flat ~50–95 ms tax**, barely moving from 250 entities to 4000. Pkl
  evaluates the entity classes that are touched, not all of them, so one class per entity is not
  the cost it looks like.
- **The fold fix took ~35% off the shape that matters**, and what is left grows linearly: 166 / 317
  / 779 ms at 1000 / 2000 / 4000 entities is n^1.15, the creep being allocation rather than an
  algorithmic term. There is no quadratic left to find.
- **A scoped set is cheap and stays cheap**: 79 / 122 / 219 ms over the same range.

So at any plausible house size an unscoped dashboard evaluates in **~150–330 ms**, most of it the
per-candidate node building that is the actual work. That is not an edit-loop problem, and it is
not what the 912 ms in the issue was.

## And the wire compresses away

| | raw | gzip |
|---|---|---|
| 1000 entities, demo-like | 1.24 MB | **40.6 KB** |
| 2000 | 2.44 MB | 64.3 KB |
| 4000 | 4.85 MB | 111 KB |

30–43×, because repetition is exactly what a compressor is for. Anything that ships this artifact
over a wire — `fh pull`, the editor — can have the win without a format. **The 15.4× above is
therefore not a reason to build anything**; it is only interesting if the cost is *heap*, where
compression does not help and nothing here has measured it.

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

### B — stop baking what the runtime can read — **rejected**

Make the default label a live read again, undoing the ADR 0004 paragraph above. Measured effect on
its own: the 400 members that carry a domain action collapse from 400 distinct trees to **3**.

**Ruled out by the maintainer, and the reason is the right one:** it moves work from build time to
run time, which is the wrong direction for this project — what a dashboard can settle during Pkl
evaluation, it should. ADR 0004's trade stands, now with the wire consequence on the record beside
it rather than unweighed. Recorded here so the next person measuring the 600-vs-5 gap does not
re-derive the idea and re-propose it.

The consequence for A: the shape count stays 5 rather than 3, and a member still needs a small
`vars` list rather than nothing at all.

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

## Recommendation: build none of them yet

The investigation talked itself out of its own designs, which is worth stating plainly rather than
shipping a proposal the evidence no longer supports.

- **B is rejected** — it moves cost to run time.
- **A's whole payoff is bytes**, and bytes gzip 30–43×. It costs a hole mechanism, a `vars` list, a
  `validate` rule and a second render per member — paid in *eval* time, the one budget that is
  actually scarce. That is the wrong currency to spend for a compressible artifact.
- **C is the most machinery for the least**, and would break the render-key property in §5 of the
  pipeline doc for bytes A already saves.

**Nor is the heap an open question.** `modules/benchmarks` already measures allocation per page
open with `-prof gc`, and `GET /system/diagnostics` (#319) reports the JVM heap beside the
container's cgroup figure on the live add-on. Against those instruments: one page open allocates
**1.1–3.8 MB, per open**, where the whole decoded dashboard is of that same order **once**, inside a
512 MB ceiling with `ExitOnOutOfMemoryError`. Interning the model 15× would save a fraction of a
percent — below what either instrument resolves. The issue's lever 4 is not worth building either,
and if the live figure ever disagrees it is one HTTP GET away.

## What remains

**Nothing.** What #108 called a regression guard is now `benchmarks/EvalBench` — the eval cost at
250/1000/4000 entities, with its baseline in the scaladoc, run by hand.

It is a benchmark rather than a test on purpose. An assertion needs either an absolute threshold
to calibrate per machine or four evaluations on every CI run, to catch a bug class that has
happened once; what actually had value was that the measurement exists at all, since the quadratic
survived from 2026-08 only because nobody could ask the question cheaply. The reasoning about
*why* the fold is split lives in `query.pkl`, next to the code that has to keep it true.

## Not verified

- **The real instance was not measured.** The live HA is unreachable from here and the pinned
  `@fh-home` package is not in the local cache, so every figure is a synthetic house shaped like
  one. The real dump has areas, floors, devices and group members that these do not, so its
  absolute numbers will be higher; the scaling and the A/B ratio should hold.
- The mild superlinearity at 4000 entities (n^1.15) is attributed to allocation on the evidence
  that nothing algorithmic remains — not profiled.
- The sentinel-render trick is reasoned from Pkl's late binding, not spiked.
- The heap argument is a proportion against the benchmarks' allocation figures, not a direct
  measurement of the decoded model's retained size.
