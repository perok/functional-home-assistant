# ADR 0003 — Candidate sets: static membership, live presence and order

- **Status:** Accepted
- **Date:** 2026-06-24 (consolidated 2026-07-04; rewritten 2026-08-12 when
  query-driven groups were retired)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Most of a dashboard is **static**: each card is composed at build time against a
concrete entity from the dump, so the set of cards is fixed. But some sections
are inherently **live sets** — which members show, and the card chosen per
member, depend on runtime state:

- "every light that is currently **on**";
- "every `device_class: battery` sensor under **20%**";
- a mixed set where the **card differs per entity** (a light gets a slider,
  anything else a read-only card).

Phase discipline (ADR 0001) means this cannot be a build-time comprehension over
live state. It is authored as **data** the renderer evaluates against live
`EntityState`.

The question this ADR answers is *which* data. It first answered it with a
runtime query, and that turned out to be the wrong half to make dynamic.

## The design: `LayoutNode.SetNode`

**The candidates are decided at BUILD time; the runtime decides only presence
and order.** A set carries:

- `candidates` — entity ids, in render order;
- `members` — per candidate, a list of guarded renderings (`SetClause`);
- `orderBy` / `limit` — present only when they need live state.

A member is a `cond` expression: clauses are tried in order and the first whose
`when` holds decides. **Falling off the end means the member is not rendered** —
which is why there is no separate presence field, and why omitting the fallback
is how "only while on" is written.

A clause carries a COMPLETE node — card, cell, slots, children, inline. Nothing
is shared between clauses or members, so a clause cannot be wrong about which
member it belongs to, and rendering one needs no lookup or merge. A set nested
inside a member's node is an ordinary child with an ordinary id, so "a tile per
room, each holding that room's lights" needs no new concept.

`docs/plan-dynamics-one-entity-lifecycle.md` has the full derivation, the
measurements behind the wire format, and the alternatives that were built and
rejected.

### Why the candidates stopped being a query

The original design was `LayoutNode.Dynamic(query, cases)`: a predicate
evaluated against every entity in the house, each match rendered through the
first matching case, with the matched entity injected as a literal `entity_id`
slot per match. It worked, and it had one structural problem that nothing local
could fix.

**A materialised member had no registry facts.** The generated dump gives an
author a typed entity carrying `friendly_name`, `area_id`, `floor_id`,
`device_id`, `entity_category` and its capability attributes. A query match gave
the runtime an `EntityState` — id, state, attributes — and nothing else. So the
same dashboard had two entity lifecycles, and the dynamic one was silently the
weaker: it could not filter on area, could not bake a label, could not know a
light's colour modes.

The cost was visible on the wire. A `slider` in a dynamic case carried four
`reactive: false` JSONata transforms whose only job was to switch on `$domain`:

```json
"min": { "transform": "$lookup({\"light\":1,\"cover\":0,\"fan\":0}, $domain)",
         "reactive": false }
```

A build-time fact, computed at runtime, per member, because the entity was
unknown at build time. Over static candidates all four are literals, and the
whole `$lookup($domain)` config tier is gone.

Three more things fell out of the same change:

- **A frame costs the changed entities, not the house.** Membership was a
  full-map scan per affected group; a set has a reverse index from entity to the
  candidates whose presence it can decide (itself, plus every candidate whose
  guards NAME it).
- **Registry filtering needs no runtime support.** "Lights in the living room"
  is a build-time selection of candidates, so the runtime predicate never had to
  learn about `area_id` — the requirement disappeared rather than being met.
- **Ghost members are impossible.** An entity vanishing is a registry change,
  which already rebuilds every entry. The old open item below is closed by
  construction.

### The runtime's remaining job

- **Presence** — per candidate, the first clause whose guard holds; none means
  absent. Absent means **absent from the DOM**, not present-and-hidden: a hidden
  wrapper ships the bytes anyway and still costs layout and morph work.
- **Order** — `orderBy` is a lexicographic list of `SortKey.Prop` (a value) or
  `SortKey.Holds` (a predicate's truth), compared only among the PRESENT
  members. An ordering that folded entirely to registry facts leaves
  `candidates` pre-sorted and `orderBy` empty, so the runtime compares nothing.
- **`limit`** — at most this many present members, after ordering. Every
  candidate still ships, because which are cut depends on live values.

A member appearing, disappearing or moving is the same `Gone`/`Placed` patch
pair. There is no separate visibility path, and no fourth node kind.

**Ordering by a continuously live key is the trap to design against.** It
reorders the DOM whenever any present member crosses a neighbour. The stable
tiebreak is candidate order (the sort is stable over it), `Patches.reordered`
keeps a longest increasing subsequence so one light overtaking three costs one
move, and a set carrying a live ordering or a limit is rebuilt rather than
patched in place — one entity moving can reorder its neighbours or push a
different member past the cut.

### Membership is maintained, not rescanned

Only a CHANGED entity can have moved a candidate's presence, so a frame costs
O(changes) per set. The graph holds the member ORDER, so "the successor of this
arrival" — what an `insert before` needs — is a lookup rather than a sort.

The frame boundary is still where the question is asked, because two entities
can move in opposite directions in one tick and each single-entity view of that
reports a change the frame did not make (`Renderer.syncMembers`, and
`architecture-rendering-pipeline.md` §4b).

**What a membership change costs.** The recorder writes a delta where it can:
one `Mutation` per member that moved. A whole-mount fill happens only in the two
cases where it costs nothing — the UNCHANGED set is empty (everything arrived,
or everything left, so the fill re-sends nothing and one patch replaces N), or
the log holds no member entry to patch against. Nothing is rendered while
recording; each session renders what it is owed when it pulls (ADR 0012),
through the per-slug render cache, so N viewers of one set cost one render of
each changed member. A set inside a surface no session has open is not recorded
at all, and one inside an inactive state branch (ADR 0007) is structurally
silent.

Two silent failure modes are worth naming, because both were real bugs:

- **A clause switch whose arriving card binds nothing live** contributes no
  reverse-index edge, so nothing would name it while its bytes moved. The
  member's ID is the sound handle — it exists whatever the card renders, since
  `validate` rejects a `wrapAsCell = false` card as a clause precisely so every
  member has its own element — so `syncMembers` reports the members it REPLACED
  and the recorder touches those by id.
- **Container selection reads `memberSources`, not the static index.** A nested
  set is not in the static index — it hangs off a member — so selecting from the
  index meant the inner set synced, its members moved, and nothing recorded it:
  correct ids, correct HTML, zero patches.

One cost remains: **a re-rendered card re-evaluates its slots**, though the
identity slots are memoized (ADR 0004).

## Authoring

`lib/query.pkl` is the only thing that builds a set:

```pkl
q.from(dump.areas.stue.lights)
  .where(q.eq(q.stateProp, "on"))
  .caseOf(q.eq(q.prop("domain"), "light"), c.slider)
  .`else`((e) -> c.entityCard(e))
  .build()
```

**One `where`; the build splits it mechanically.** A term is static exactly when
it touches only registry facts, and the fold happens in the Pkl combinators, so
the author never names the seam: a registry term selects candidates and
disappears, a live term becomes a per-member guard. `q.prop(name)` resolves
against the candidates — a registry fact if all of them carry it, a live
attribute if none do, a build error if only some (unless wrapped in
`q.optional`, which reads missing as null).

A load-bearing consequence, silent when broken: **the fold drops a branch whose
guard cannot hold BEFORE applying its render lambda.** That is what makes
per-domain dispatch over a mixed set work at all — `c.slider` takes a light, and
a media_player must never reach it.

**Plain Pkl stays the first answer.** A `for` over a typed dump list is still
the right way to render a fixed set of lights; the query language earns its
place only for what static composition cannot do — filtering on live state,
ordering by a live value, counting, limiting, presence. Which rooms exist is
registry data, so "a tile per room" is a plain `for` with a set inside; a set
nested in another set's `render` is for when the OUTER membership is live too
(HA light groups that are on).

## What this replaced

`LayoutNode.Dynamic`, `DynamicCase`, the full-map membership scan, `hass.SELF` /
`DynamicEntity` / `isDynamic`, the free predicate constructors
(`domainIs`/`stateIs`/`entityIs`/`lowBattery`/…), the `caseOf` that applied a
render lambda to a `$self` placeholder, and the slider's runtime
`$lookup($domain)` config tier. All deleted; nothing decodes `kind: "dynamic"`.

**Closed by the rewrite:** the open item this ADR carried — a removed entity
leaving a ghost member, because membership was maintained from deltas and
`StateStore` publishes no `StateChange` for a removal. Candidates come from the
dump, an entity vanishing is a registry change, and a registry change rebuilds
the renderer. There is nothing left to go stale.

## Consequences

- One card library serves every rendering; a new card type needs no counterpart.
- A set's wire size is linear in its candidates (~459 bytes each), which costs
  eval time and server memory — `dashboard.json` never reaches the browser.
  Nothing the client or the frame loop does is linear in candidates.
- Every entity a dashboard can show has ONE typed, build-time identity. That is
  the property this rewrite existed to get.
- Verified by `PklBuildSuite` (Pkl → wire, including the shipped starter entry),
  `query.test.pkl` (the fold, the authoring surface), `SetNodeSuite` (presence,
  order, limit, nesting, cross-entity guards end-to-end through the real
  `Server`) and `DynamicGroupSuite` (the per-member patch machinery).
