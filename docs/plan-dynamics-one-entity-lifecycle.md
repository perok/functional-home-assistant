# Dynamics: one entity lifecycle

> Status: design. Nothing here is implemented. The wire format and the LINQ surface are
> still being hashed out — this records the shape and the reasoning, not a commitment.

## Context

The dashboard has two ways an entity reaches a node, and they do not agree.

**Static path.** `build/RegistryDump.scala` → `build/PklDump.scala` emits a typed Pkl const per
entity (`e_x: hass.LightEntity`) carrying the registry facts: `entity_id`, `domain`,
`friendly_name`, `area_id`, `floor_id`, `device_id`, `entity_category`, plus the capability
attributes ADR 0013 allows. An author references a real, typed thing; a typo is a Pkl eval error.

**Dynamic path.** A `DynamicGroup` (`lib/components.pkl:1298`) carries a `Predicate` query and
render lambdas applied to `hass.SELF` — a sentinel entity (`lib/hass.pkl:171`) whose `entity_id`
is the literal `"$self"`. At runtime `Renderer.syncMembers` scans the state map and materialises a
`LayoutNode.Component` per match. That instance has **no registry facts**: `EntityState`
(`runtime/StateStore.scala:18`) is `entityId`, `state`, `attributes`, `contentVersion`.

Two lifecycles, two representations, two failure modes — and the dynamic one is silently weaker.

The cost is visible in the shipped `dashboard.json` today. The `slider` case carries four
`reactive: false` JSONata transforms whose only job is to switch on `$domain`:

```json
"min": { "transform": "$lookup({\"light\":1,\"cover\":0,\"fan\":0}, $domain)", "reactive": false }
```

That is a build-time fact computed at runtime, per member, because the entity is unknown at build
time. Static candidates fold all four to literals.

**The conceptual change in one line: an expression gains a known subject — a specific entity, or a
statically known set of them.** Everything below follows from that.

Out of scope: entities appearing or disappearing in HA. That is a registry change, which already
triggers a complete rebuild (`ServerApp.watchRegistryEvents`, 5s debounce, staged re-eval,
renderer hot-swap). None of that machinery is touched.

## Decisions taken, and the notes behind them

Recorded verbatim, because the reasoning behind several of these is not recoverable from the
result. (These were dropped by an earlier rewrite of this file and restored from the conversation
— they predate the first commit, so git does not have them.)

> General notes:
> - That we do not store all the information in the backend for A is not by itself a good argument
>   to discard A
> - The limitation on existing If sounds like a bug and not a design that we want. If we are able
>   to fix that, would that change the reasoning here?
>
> - The capability around loosing ordering on dynamic state values is not good. The dynamic
>   membership machinery around existing dynamics is maybe something that we should connect this
>   to so that we can keep both?
> - In regards to the point above on keeping some of the machinery; I also want to be able to
>   support things like count() on dynamic properties. So we can say, if more than X elements then
>   we show this.
>
> - for allowing explicit entity refs; only show when on and when time is above Y for.ex
>
> FOcus more on the princicples and limitations we need to fix in this plan at the moment rather
> than how to achieve it specifically as we are still in the design phase.

> [on retiring the dynamic machinery] keep parts if needed.

> [on the dump surface] Add `dump.all` + per-domain lists, number 2 as well, but we need to work
> on the syntax. It should be more like LINQ ish thing so it can compose

Settled since:

- **Predicate scope: explicit entity refs allowed**, not subject-only.
- **State-dependent case dispatch: supported.** Members carry a clause list.
- **Time needs no machinery.** `sensor.time` is an ordinary HA entity, so it flows through the
  reverse index like any other. What survives is the RULE, not a mechanism: a term whose input has
  no indexable source must be rejected at validate time, never silently accepted — a node that can
  never be woken is worse than one that errors.
- **`If` and tabs stay.** They are one mechanism (`Surface` + activation mode); its problems are
  bugs, not design.
- **Registry in the backend: accepted.**
- **Dynamic-group machinery: retire the query half, keep the patch half.**

## Principles

**P1 — One lifecycle.** Every entity a dashboard can show has a typed, build-time identity.
Nothing is materialised from a query against live state.

**P2 — Registry facts are build-time constants; state and attributes are the only runtime inputs.**
Per slot: registry-derived → literal (`label` from `friendly_name`; `min`/`max`/`key`/`action`
from domain); state-derived → JSONata, unchanged (`state`, `value`, `fill`). A rename in HA is a
registry change, hence a rebuild, so a literal is correct.

**P3 — Runtime decides presence and order among pre-materialised members. It never invents them.**

**P4 — One expression language, three binding scopes**, differing in unit of output: per-entity
(a member), per-set (count/aggregate/order over a candidate list), per-surface (`Activation.State`
— a tab chosen by an expression instead of a click).

**P5 — Candidate sets are static and finite, so set-level work is O(candidates), not O(entities).**

**P6 — Every expression carries a binding.** Either an explicit entity, or set membership. There
is no unbound third shape, so every expression is indexable by construction.

**P7 — Always reduce the work reaching the client.** A node that is not shown is **absent from the
DOM**, not present-and-hidden: a `hidden` wrapper ships the bytes anyway and still costs layout
and morph work. This is why presence must be real removal and anchored insertion.

**P8 — One `where`; the build splits it mechanically.** A term is static exactly when it touches
only registry facts. Folding happens in the Pkl combinators, so the author never names the seam.

## Limitations this must fix

| | limitation | status |
|---|---|---|
| **L1** | Two entity lifecycles — typed static vs untyped `$self` | the core; addressed by P1 |
| **L2** | The runtime predicate is registry-blind (no `area_id`/`floor_id`/…) | resolved by P2 — those become build-time selection, so the need disappears rather than being met |
| **L3** | Membership costs a full-map scan, because the candidate set is a runtime query | resolved by P5 |
| **L4** | No set-level derivations — no count, no aggregate, and ordering is `entity_id` only (`materialise` sorts `states.toVector.sortBy(_._1)`) | **ordering designed and spiked; count/aggregates still open** |
| **L5** | A predicate term with no indexable source silently never updates | resolved for the motivating case (`sensor.time` is an entity); the validate-time rejection is still to build |
| **L6** | The surface mechanism has three bugs — uncached `bakeGroup`, whole-map `holds`, non-collapsing empty `ifhost` | **open**, and independent of everything else here |
| **L7** | Ghost members on entity removal (ADR 0003 open item) | resolved for free — removal is a registry change, hence a rebuild |
| **L8** | The static selection vocabulary does not compose | resolved by `query.pkl` |

## Presence and ordering are one concept

- `candidates` — static, from Pkl.
- `present ⊆ candidates` — runtime predicate per candidate.
- `order` — a live key, **compared only within `present`**.

The DOM holds exactly `present`, in `order` restricted to `present`; patches are emitted only when
that restriction changes. A hidden member's sort key moving emits nothing — it acquires a position
when it becomes present, via the `Placed` we were already sending. So presence is evaluated for
every candidate, order only over the present subset.

`Gone`/`Placed` already exist (`Patches.recordDynamic`; wire forms `Patch.Remove` /
`Patch.Insert(mode, target)`). Given P7, a node that isn't shown isn't a member — so there is no
separate "node visibility gate" concept to design. Presence *is* membership.

**Trap to design against:** ordering by a continuously live key (brightness, temperature) reorders
the DOM whenever any present member crosses a neighbour — a stream of `Gone`/`Placed` pairs, which
is exactly what P7 exists to prevent. A stable tiebreak (`entity_id`) is mandatory; ordering by raw
sensor values should not be made convenient.

## Wire format: D2 (leaning, not committed)

Authored:

```pkl
dump.stue.lights
  .where((e) -> e.stateIs("on"))
  .orderBy((e) -> e.attr("brightness"), "desc")
  .render((e) -> c.slider(e))
```

Emitted:

```json
{"kind":"set",
 "candidates":["light.a","light.b","light.c"],
 "conditions":[null,{"property":"state","op":"eq","value":"on"}],
 "orderBy":{"property":"attr:brightness","dir":"desc","tiebreak":"entity_id"},
 "shapes":[{"card":"slider","slots":{
     "state":{"transform":"$state","reactive":true},
     "fill": {"transform":"$round($attr.brightness*100/255)","reactive":true},
     "key":{"literal":"brightness"},"min":{"literal":"1"},"max":{"literal":"255"},
     "action":{"literal":"light/turn_on"}}}],
 "members":{"light.a":{"vars":{"label":"Taklys"},"clauses":[{"cond":1,"shape":0}]},
            "light.b":{"vars":{"label":"Lampe"}, "clauses":[{"cond":1,"shape":0}]},
            "light.c":{"vars":{"label":"Spot"},  "clauses":[{"cond":1,"shape":0}]}}}
```

**Naming.** A member is a `cond` expression: its **clauses** are tried in order and the first
whose guard holds decides the rendering; falling off the end means the member is not rendered.
They are not "alternatives" — nothing is being destructured. The two dedup tables are **`shapes`**
(a card plus the slots identical across its members) and **`conditions`** (the guards). The old
`DynamicCase` bundled a predicate WITH a rendering; this format splits them, so "case" no longer
names anything here. Members are thin rows of indices plus their varying literals. `cond: 0` is always `null` = unconditional. `orderBy` carries no `entity` — it is bound
by set membership (P6's second shape). See "Resolved by the spike" for why there is no `present`.

**Case dispatch may be static or state-dependent** (decided: we support both). The Mapping key
returns a union, and the build applies every key to every candidate:

```pkl
hidden cases: Mapping<(Entity) -> Boolean|Bound, (Entity) -> Node>
```

A key returning `Boolean` folds to a case index; one returning `Bound` defers to the wire. `&&`
still refuses to mix static and runtime *within* a term (see "Pkl mechanics"), so a key is cleanly
one or the other. Members therefore carry either a bare index or an alternatives list, first match
winning:

```json
"conditions":[null,{"kind":"cmp","property":"state","op":"eq","value":"on"}],
"shapes":[{"card":"slider",...},{"card":"toggle",...}],
"members": {
  "light.a": {"vars":{"label":"Taklys"}, "clauses":[{"cond":1,"shape":0},{"cond":0,"shape":1}]}
}
```

First match wins, so `light.a` is a slider while on and a toggle otherwise; had the fallback been
absent it would simply be absent while off.

Re-dispatch is free in practice: a member only re-evaluates its alts when its own entity changes,
which is when it was re-rendering anyway.

### Complexity

`N` = candidates in a set, `P` = present members, `E` = all entities in HA, `Δ` = entities changed
in a frame, `C` = distinct shapes (`C ≤ N`, usually `C ≪ N`).

| | today | after |
|---|---|---|
| `dashboard.json` | O(1) per group | **O(C·slots + N·vars)** |
| membership per frame | **O(E · groups)** — full map scan | **O(Δ)** index lookups |
| presence eval | — | O(Δ) per frame; O(N) once at renderer construction |
| ordering (any live key) | not supported | O(P log P), only when a *present* member's key moves |
| ordering (all keys static) | not supported | **O(P)** — pre-sorted at build, runtime only filters |
| DOM elements | O(P) | **O(P)** |
| bytes to client | O(changed) | O(changed present) |

**The wire grows linearly in candidates; nothing the client or the frame loop does is linear in
candidates.** A 200-candidate set with 5 present costs 200 JSON entries, 5 DOM elements, and a
hash lookup per change. The one real regression — `dashboard.json` per set going from constant to
linear — buys the frame loop going from O(E · groups) to O(Δ). Byte count is therefore not a
design driver here; it is server-side metadata, not work reaching the browser.

**Rejected: D1 (full expansion, one Component node per candidate).** N × (card + all slots +
predicate) instead of 1 × that + N × (id + label), and — decisively — three sibling nodes have no
shared thing owning their relative order, so D1 cannot express `orderBy` or `count` at all.

**On `$self`, which D2 appears to reintroduce.** `$self` as a *runtime-materialised unknown* is the
bug. `$self` as a *template parameter over a statically known list*, bound at renderer
construction before any state arrives, is fine. Same token, different lifecycle — that distinction
is the whole plan.

## Set-derived values (count, aggregates)

A member set is a node; derived values over it are child nodes sharing its candidate list:

```
set(candidates=[a,b,c])
  ├── member(a), member(b)        ← presence + order
  └── count(where: on) = 2        ← sub-node, same candidates as index key
```

`byEntity: Map[String, Set[NodeId]]` already does this — each candidate points at the set node, so
any member change wakes the count node, its digest moves, one morph.

**Sketch only.** Nothing here is spiked and there is no wire representation, so `count > 2` as a
condition is an intention rather than a design. See "Not yet designed".

## Making an unbound expression unrepresentable

Every term carries a binding, so there is no unbound form to construct. Today that binding is
**set membership**: `wire.Bound` is `{property, op, value}` with no entity, because the set's
`candidates` say who it applies to. The free constructors that made `stateIs("on")` meaningful on
its own are gone; a term is only reachable through `q.eq/ne/gt/lt` over a `q.prop(...)`, or through
`q.candidate(...)` which is resolved against a specific candidate at build time.

**Not yet built: the other binding P6 allows — an explicit reference to a DIFFERENT entity.** See
"Cross-entity references" below; this is a known hole, not a settled design.

## Pkl mechanics (verified on 0.32.1, the `pkl-core` pin)

Spiked rather than assumed. The full list now lives in the module `CLAUDE.md` gotchas; kept here
are the ones that shaped THIS design:

- **A lambda can be a `Mapping` key**, and keys can be applied to dispatch. The Mapping must be
  `hidden` — functions cannot render to output, which is already how `DynamicGroup.branches` is
  declared. The language reference confirms non-String keys generally (`[new Dynamic { … }] = …`).
- **`&&` type-errors when mixing a static `Boolean` with a runtime predicate object**:
  `Operator `&&` is not defined for operand types `Boolean` and `Bound``. So the build/runtime
  split is enforced by the type system for free — a state condition cannot be smuggled into a
  static position.
- **Structural equality holds for independently-built objects**, and `Map` dedupes by structural
  key. `distinct`, `groupBy` and `fold` all work on class instances.
- Consequence: the D1→D2 shape dedup *could* run in Pkl. It should still run in Scala
  (`DashboardBuild`), because `distinct` alone is not enough — the varying literals must be holed
  out first, which is a cross-candidate comparison per slot key, easier to test as a pure function.
  This is now a choice, not a constraint.

## The spike is scaffolding, not a parallel implementation

`src/test/pkl/dynamics/{wire,query,fixtures}.pkl` exist so the design can be argued against
running code. They are NOT a second implementation to maintain, and leaving them as one would be
this plan's own failure mode: two parallel definitions of the same thing, drifting.

So the machinery moves onto the real code progressively:

- **`query.pkl` moves to `lib/query.pkl`** and becomes the shipped authoring namespace, imported
  as `@fh-dashboard/query.pkl`. It already knows nothing about cards, so nothing else has to move
  with it.
- **`wire.pkl` dissolves.** Its classes become the Scala wire model in `model/Dashboard.scala`,
  and its fold becomes the build-time pass in `DashboardBuild` (which is where the shape
  compression belongs anyway — see the D1/D2 note). Pkl keeps only what an author touches: the
  property/term constructors and the chain.
- **`fixtures.pkl` shrinks to the test home only.** Every scenario that can run against the real
  generated `@fh-home` dump should, so the tests exercise the actual typed entities and the actual
  capability data. Fixtures survive only for cases the test home cannot produce — a deliberately
  mixed-availability set, an entity with a capability nobody owns — and each one that stays should
  say why.

The end state is: no `dynamics/` directory, scenarios running against the real dump, and the
worked examples still asserting the same wire properties. Until then, treat anything under
`dynamics/` as a proposal with a shelf life.

## What we keep, delete, and fix

**Keep** — the presence-and-order patch machinery: `Gone`/`Placed`, insert anchors,
`insertOrdered`, `recordDynamic`, the member-graph projections. Retargeted from a runtime query
result to a static candidate list.

**Delete** — the membership-by-query half: `touchesDynamic`, the full-map matching in
`affectedDynamics`, `materialise`/`memberOf`, and on the Pkl side the free predicate constructors,
`hass.SELF` as a runtime unknown, `DynamicEntity`, `isDynamic`.

**Fix — three independent bugs in the surface mechanism** (tabs and `If` are correctly *one*
mechanism differing only in activation mode — verified at `components.pkl:843` vs `:1368` — and
that stays; ADR 0007 stands):

1. `Renderer.holds` (`:684`) evaluates an entity-pinned condition by scanning the entire state map.
   **Fixed by the binding change** — a bound condition becomes a lookup.
2. `Renderer.bakeGroup` (`:524`) is a `def` doing `dashboard.surfaces.toList` + full scan, called
   ~5× per node per render → O(nodes × surfaces). Independent missing memo.
3. An unmatched `ifhost` emits `<div class="fh-cell fh-cols-full">` — a full-width empty flex line.
   Independent; the one current violation of P7.

ADR 0003 (dynamic groups) and ADR 0004 (predicate engine) are rewritten in place. ADR 0007 stands,
with its cost claims qualified (they are per-branch-*content* claims and say nothing about
per-group *selection* cost). `docs/architecture-rendering-pipeline.md` §4b describes the deleted
half and changes in the same commit.

## Resolved by the spike

**There is no set-level `present` field; presence collapses into `alts`, which index a deduped
condition table.**

The problem, from one authored expression `e.supported_features > 4 || e.stateIs("on")` over four
lights:

```
light.taklys   ALWAYS present (decided at build time)
light.lampe    present WHILE state == on
light.spot     ALWAYS present (decided at build time)
light.kjokken  ALWAYS present (decided at build time)
```

A statically-true term short-circuits the disjunction, so the residual differs per candidate. A
single set-level predicate must pick one for all four: pick `true` and `lampe` shows while off;
pick `state == on` and the other three vanish when off. Both wrong. So presence must be
per-member — and once it is per-member it is indistinguishable from "no alternative matched", so
it belongs *in* the alternative rather than beside it. A member is present iff some alt's
condition holds.

That alone would repeat the predicate once per member per alternative. The residuals diverge, but
only into a handful of distinct values — the same situation as slot literals — so the same
compression applies: a `conditions: List<Term?>` table with index 0 always `null`
(= unconditional), and `Alt` carrying `{cond, shape}`, two indices. A fully static set has one
condition entry; the four-light case above has two, shared by four members.

The wire therefore has exactly two dedup tables, `cases` and `conditions`, with members as thin
rows of indices plus their varying literals. Pinned by `dynamics-spike.test.pkl` ("presence
residuals diverge across members of one set"). "One mechanism, not two parallel ones" arriving
from evidence rather than taste.

**Ordering is a LIST of positions, each folding independently.** Superseded in detail by "One
property vocabulary" below — the shape settled on references rather than lambdas, which removed
`StaticSort` and `MemberDef.sortVars` entirely. Read that section, not this paragraph, for the
current form.

**The one error case is a single POSITION that folds inconsistently across candidates** — static
for one candidate, live for another. Those two values are not mutually comparable (a brightness
against a name), and unlike presence there is nothing useful to do with the divergence. This is
narrow: it is not about having several keys, which is fully supported.

`orderBy` does NOT join the `conditions` table — a key extractor is not a guard.

**The build emits an already-split node; the runtime never re-derives the split.** The spike does
the fold at build time and emits only the residue — candidates, per-alt conditions, shapes. The
runtime receives no static terms at all and cannot tell which were folded away. The cost is that
`dashboard.json` sits further from what was authored, which matters for the editor; the benefit is
that the runtime carries no folding logic and cannot disagree with the build about it.

**Presence and ordering are one mechanism, confirmed.** Presence is "some alt matched"; a member
appearing or disappearing is `Placed`/`Gone`, which is the same patch pair a reorder emits. There
is no separate visibility path to build.

**Per-member `vars` need no shared entity table (for now).** The shape compression already hoists
anything constant across a shape's members onto the case, which is exactly the per-*domain*
literals (`min`/`max`/`key`/`action`) that motivated the idea. A shared table would only help
entities appearing in several sets — revisit if that shows up in a measurement, not before.

## The query surface (`query.pkl`)

Its own namespace, not part of `components.pkl` — this is a query language, and a card knows
nothing about it. A `render` lambda returns an ordinary `Node`; that is the only seam. Entry is
`q.from(list)` rather than generated `EntitySet`-typed dump properties: more flexible, and it keeps
`PklDump` out of it. Candidates are typed `Any`, not `Entity`, deliberately — a member need not be
an entity (a tile per room makes AREAS the candidates) and baking that in now would have to be
unpicked.

```pkl
q.from(dump.all)
  .where((e) -> List(e.domain == "light", e.area_id == "stue"))   // a LIST is AND
  .where((e) -> q.any(List(e.dimmable, e.stateIs("on"))))         // OR is explicit, nests
  .orderBy(List(q.desc((e) -> e.stateIs("on")),                   // sort by a PREDICATE
                q.desc((e) -> e.attrKey("brightness")),           // sort by a property
                (e) -> e.friendly_name))                          // bare lambda = ascending
  .caseOf((e) -> e.supported_features > 4, (e) -> c.slider(e))
  .`else`((e) -> c.toggle(e))
```

- **A list argument is conjunction**, so the common case needs no wrapper. `q.any` / `q.all` /
  `q.not` give explicit structure and nest freely. `&&`/`||` still cannot cross the static/live
  seam — Pkl rejects `Boolean && Term` — which is what keeps a live condition out of a static
  position.
- **`else` is optional, and its absence is meaningful**: a candidate matching no clause is simply
  not rendered. `.caseOf(live).build()` with no `else` is exactly "show it only while on".
- **`cases(Mapping)`** takes several branches at once; a Mapping keeps author order (= match
  order) and rejects duplicate keys.
- **Ordering by a predicate** replaces the `equals` field: an order position is a property
  reference (sort by value) or a `Term` (sort by whether it holds). One fewer concept, and it
  reuses the predicate vocabulary.

## One property vocabulary, shared by predicates and ordering

A property is NAMED, not namespaced by the author: `q.prop("friendly_name")`. The build resolves
where it comes from — a registry fact when every candidate carries it as a property (so
`getProperty` can read it), a live attribute otherwise. `friendly_name` is genuinely both, and an
author should not have to know which we picked. `q.attr(n)` forces the live reading when a name is
both and you want live.

The same references drive filtering and ordering, so a predicate and a sort key are written
identically:

```pkl
q.from(dump.all)
  .where(q.eq(q.prop("domain"), "light"))               // data form — no lambda
  .where(q.candidate((e) -> e.supported_features > 4))  // reads the candidate, composes
  .orderBy(List(q.desc(q.eq(q.stateProp, "on")),        // sort by a predicate's truth
                q.desc(q.prop("brightness")),           // live attribute
                q.asc(q.prop("friendly_name"))))        // registry fact
```

See "The scenarios, in verified authoring syntax" below for the worked examples — those are copied
from a test and compile; this one is illustrative.

`q.eq/ne/gt/lt` build a `Pending` — an unresolved comparison. Resolving it against the candidates
either FOLDS it to a `Boolean` (the build could read the property) or produces a live `Bound`. So
the static/live split is reached through one vocabulary instead of two, and the author never picks
a side.

**Boolean structure.** A list is conjunction and `q.any` is disjunction, and they nest both ways —
`a OR (b AND c)` is `q.any(List(a, q.all(List(b, c))))`, tested. The top-level shape is whatever
you write; a list is not imposed.

**`orderBy` takes one position or a list**, and is declarative rather than a lambda: a `where`
predicate genuinely depends on the candidate, an ordering key never does. For a genuinely COMPUTED
ordering, sort the input list with ordinary Pkl before `from`.

Dropping lambdas from ordering removed `StaticSort`, `MemberDef.sortVars`, the static/live
divergence error, and the fold-consistency check — nothing can diverge when every position is a
reference. Two outcomes remain:

| positions | emitted | runtime |
|---|---|---|
| all build-readable | **nothing; `candidates` pre-sorted** | O(P) filter, no comparisons |
| any live | the whole list, readable ones still references | O(P log P) stable sort |

## `candidate` is a term, not a lambda

`where` still accepts a bare lambda, but the composable form is `q.candidate((e) -> ...)`. This is
not sugar: a bare lambda **cannot** sit inside `q.any`/`q.all`/`q.not`, because outside `where`
there is no candidate in scope for it to read. A `candidate` term is first-class data, so it
composes with the data form freely:

```pkl
.where(q.any(List(
  q.candidate((e) -> e.supported_features > 4),   // reads the candidate, folds at build
  q.eq(q.stateProp, "on"))))                      // live, defers
```

`all`/`any`/`not` fold immediately when every operand is already resolved and defer to an
unresolved `Group` when one is not, so the same three functions cover both and an author never
picks between an eager and a lazy variant.

## Attribute-name validation: derive the schema, do not observe it

The gap: a typo'd live attribute (`q.prop("brightnes")`) resolves to something that never matches
and nothing catches it. Fixing it needs a `domain -> {name: type}` schema. Where that schema comes
from decides whether it is safe to put in the dump.

**Observing current attribute names is NOT safe.** Measured against the live instance (1069
entities): 290 unavailable (27%), 308 distinct (domain, attribute) pairs, and **128 of them (42%)
held by exactly one entity in their domain**. An unavailable entity drops its volatile attributes,
so one zigbee bulb leaving the mesh would delete an attribute, move the dump's content hash,
re-seed the package and re-evaluate every dashboard — on routine flapping. That is what
`CapabilityAttributes` exists to prevent. (~6.8 KB emitted; size was never the issue.)

**Deriving it from capability attributes IS safe**, because those survive unavailability. Verified
on the live instance — the HA docs do not state this, so it is worth keeping:

| lights | capability-attr kinds | volatile kinds |
|---|---|---|
| available (45) | 6 | 12 — `brightness`, `color_mode`, `color_temp_kelvin`, `effect`, `hs_color`, … |
| unavailable (3) | 5 | **2** — only `friendly_name`, `restored` |

An unavailable `light` still reports `supported_color_modes`, `supported_features`, `effect_list`,
`min/max_color_temp_kelvin`. So a schema computed FROM those is a pure function of data that is
already in the dump and already verified non-churning — it inherits their stability, and
unavailability cannot shrink it.

Also: a state change alone does not move the name set either. A light that is off still carries
`brightness` as a KEY with a null value (`light.relative_bibliotek`). The intuition that on/off
would churn a schema is simply wrong.

**The schema carries NAMES AND TYPES ONLY — never a value, not even null.** The observation above
("a light that is off still carries `brightness` with a null value") describes HA's STATE payload,
not a proposed dump shape. A `brightness: null` field in the dump would be a value slot with
nothing in it, and a value slot is exactly the thing that later gets helpfully filled in with the
live reading at dump time — reintroducing the churn this whole section exists to avoid. Make it
structurally impossible instead:

```pkl
// dump.pkl — a Mapping of name -> TYPE NAME. There is nowhere for a value to go.
local sig_light_ct_xy_44: Mapping<String, String> = new {
  ["brightness"] = "Int"
  ["color_temp_kelvin"] = "Int"
  ["xy_color"] = "List<Float>"
  ["effect"] = "String"
}

const hidden e_taklys: hass.LightEntity = new {
  entity_id = "light.taklys"
  volatileAttrs = sig_light_ct_xy_44
}
```

**Deduped by capability signature**, the same shared-table trick `shapes` and `conditions` use.
Measured on the live instance: 1069 entities collapse to 214 distinct capability signatures
(lights: 48 entities, 8 signatures), which is ~59 KB emitted against ~292 KB if written per
entity.

This is ADR 0013's existing pattern extended from TYPING to the attribute schema: `hass-light.pkl`
already vendors `ColorMode` and `LightEntityFeature`, so `supported_color_modes: [color_temp, xy]`
already implies which volatile attributes exist. Consequences:

- coverage is progressive, exactly like ADR 0013 — `light` is modelled, other domains fall through
  unvalidated until someone models them. That is a feature: an unmodelled domain is untouched.
- `restored` is an HA internal flag that appears on unavailable entities; exclude it.
- the check can then live in Pkl (author-time, pkl-lsp) rather than only in `Dashboard.validate`,
  which keeps the ADR 0006 editor-feedback story intact.

## How aggressively does the build actually narrow?

Measured rather than assumed. What the fold already does:

- a candidate whose presence folds to `false` never reaches the wire
- a candidate no branch can match is dropped (`liveBranches` empties, the candidate goes)
- a statically-true term vanishes from a conjunction; a statically-false one decides it
- a comparison against a property the candidate LACKS folds to false and drops it — so
  `q.eq(q.optional(q.prop("brightnessMax")), 255)` over a mixed set leaves only the candidates
  that could ever match

Two real gaps were found by inspecting emitted JSON, both now fixed and pinned:

**Duplicate terms were not collapsed.** A presence residual identical to a branch guard produced
`and[state==on, state==on]`, because `conjoin` built a `PAnd` directly instead of routing through
the fold.

**Same-kind nesting was not flattened.** `all(a, all(b, c))` emitted a nested `and` — earlier
probes missed it because a single-element inner group collapses by accident.

Both matter for more than tidiness: the `conditions` table dedupes BY STRUCTURE, so two authored
expressions meaning the same thing have to reduce to the same term or they take two table entries
and the runtime evaluates the same predicate twice. `all`/`any` now canonicalise — flatten
same-kind nesting, dedupe structurally equal terms, collapse a single survivor — and one level of
flattening suffices because every group is built through them.

Known remaining slack, not worth fixing yet: an unsatisfiable conjunction (`state==on AND
state==off`) is not detected, and a typo'd live attribute cannot be, for the reason below.

## Validation: what the build can and cannot check

`q.prop(name)` is resolved against the candidates by counting how many carry it as a property:

| candidates carrying it | resolution |
|---|---|
| all | registry fact, readable by the build |
| none | live attribute — **unvalidatable**, see below |
| some | **build error**, unless wrapped in `q.optional(...)` |

The middle row is the limit TODAY, not permanently — see "derive the schema, do not observe it":
volatile attribute VALUES are deliberately kept out of the dump (they would churn its content hash), so the build has no list of legal attribute
names to check a typo against. `q.prop("brightnes")` resolves to a live attribute that never
matches, and nothing catches it.

The third row is the one worth having. A property on SOME candidates would otherwise silently
resolve to a live attribute and quietly stop being the registry fact the author meant — a genuinely
mixed set, or a set that became mixed when a new domain was added. `q.optional(q.prop(...))`
accepts it deliberately and reads missing as null. All four cases are pinned in
`query-surface.test.pkl`.

## The scenarios, in verified authoring syntax

Every snippet below is **copied from `modules/fh-datastar-view/src/test/pkl/query-scenarios.test.pkl`
and compiles**. That file is the canonical set: each scenario asserts the one wire property it
exists to demonstrate, so if the surface changes these break first and this section cannot quietly
drift from what works. Do not edit the snippets here without changing the test.

```pkl
// S1 — every light in a room, shown while it is on.
//      One shape shared by three members; one condition shared by all of them.
q.from(dump.stue.lights)
  .where(q.eq(q.stateProp, "on"))
  .render(slider)

// S2 — an interpolated label. Computed at build time, so still a per-member var.
q.from(dump.stue.lights).render((e) -> c.entityCard(e, label = "Blah \(e.friendly_name)"))

// S3 — a different card per domain. Registry data: resolved at build, no guard on the wire.
q.from(dump.all)
  .cases(new Mapping {
    [q.eq(q.prop("domain"), "light")]        = slider
    [q.eq(q.prop("domain"), "media_player")] = mediaCard
  })

// S4 — dispatch on a CAPABILITY. Also registry data, also fully static.
q.from(dump.stue.lights)
  .caseOf(q.candidate((e) -> e.supported_features > 4), slider)
  .`else`(toggle)

// S5 — dispatch on LIVE state. Both shapes stay; the choice rides the wire as a
//      clause list, first match winning.
q.from(dump.stue.lights)
  .caseOf(q.eq(q.stateProp, "on"), slider)
  .`else`(toggle)

// S6 — omit the `else` and an unmatched candidate is simply ABSENT. This is how
//      "only while on" is written; the missing fallback is the feature.
q.from(dump.stue.lights).caseOf(q.eq(q.stateProp, "on"), slider)

// S7 — a list is AND, `any` is OR, and they nest. Dimmables come out
//      unconditional; the dumb one defers to its state.
q.from(dump.all)
  .where(List(
    q.eq(q.prop("domain"), "light"),
    q.eq(q.prop("area_id"), "stue"),
    q.any(List(q.candidate((e) -> e.supported_features > 4),
               q.eq(q.stateProp, "on")))))
  .render(slider)

// S8 — a OR (b AND c), the other nesting.
q.from(dump.all)
  .where(q.any(List(
    q.eq(q.prop("domain"), "media_player"),
    q.all(List(q.eq(q.prop("domain"), "light"),
               q.eq(q.prop("area_id"), "stue"))))))
  .render(toggle)

// S9 — multi-key: on first, then brightest, then by name. The registry position
//      stays a reference; nothing rides per member.
q.from(dump.stue.lights)
  .orderBy(List(
    q.desc(q.eq(q.stateProp, "on")),
    q.desc(q.prop("brightness")),
    q.asc(q.prop("friendly_name"))))
  .render(slider)

// S10 — an all-registry ordering is resolved NOW: `orderBy` is null on the wire
//       and the runtime only filters. Alphabetical costs it nothing.
q.from(dump.all)
  .where(q.eq(q.prop("domain"), "light"))
  .orderBy(q.asc(q.prop("friendly_name")))
  .render(toggle)

// S11 — a property only SOME candidates carry is a build error, because it would
//       silently become a live attribute for the rest. `optional` accepts it and
//       reads missing as null, so the ones lacking it compare false and drop out.
q.from(mixedSet).where(q.eq(q.optional(q.prop("brightnessMax")), 255)).render(toggle)
```

Three of them (S1, S5, S9) also capture their emitted JSON in
`query-scenarios.test.pkl-expected.pcf`, so a wire-format change shows up as a reviewable diff
rather than a discussion.

## Typing: `Any` is mostly avoidable after all

Pkl has no user generics — `EntitySet<T>` is rejected outright — but that does not force `Any`
everywhere. Three moves cover almost all of it:

- **A marker supertype.** `abstract class Candidate { entity_id: String }`, which both entities and
  areas extend. It says what a set actually requires (an id, nothing else) and rejects a list of
  the wrong thing. Authors narrow back to the concrete type by annotating the lambda —
  `.render((e: hass.LightEntity) -> …)` — which works and rejects a wrong annotation.
- **Union typealiases** where the variants are known: `Scalar = String|Int|Float|Boolean` for
  comparison values, `Cond = Boolean|Term|Pending|CandidateTerm|Group` for conditions.
- **Inlining a union** where an alias would be cyclic.

`Any` survives in exactly three places, each for a reason worth knowing:

- `CandidateTerm.fn` and `Group.items` — **a typealias may not be cyclic in Pkl** ("Type alias
  definitions must not be cyclic"), and `Cond` mentions both classes. Inlining does not help; the
  union still closes the loop. So the two positions that would close it take `Any`, and `Cond` is
  used everywhere else.
- `Node.children` / `fills` take the inlined `Node|SetNode|Hole` rather than a `Child` alias, for
  the same rule.
- `applyOp`'s operands, which are genuinely heterogeneous.

The public surface — `from`, `where`, `render`, `orderBy`, `entity`, the comparison builders — is
now precisely typed.

## The registry lives in the backend

Decided: keeping `area_id`/`floor_id`/etc. in the runtime is acceptable. That is what lets a
resolved registry property stay a REFERENCE rather than shipping a value per member, and keeps
`dashboard.json` from carrying what the backend already knows. `RegistryDump` already fetches all
of it, so this is one table from the existing source, not a second representation of identity —
candidates are still decided at build time.

The same move is available for `vars` (a bare `friendly_name` label could become a reference
instead of a literal), which would shrink members further AND dissolve the var-vs-case
count-dependence below, since there would be no literals left to diff. Not done yet — an
interpolated label like `"Blah \(e.friendly_name)"` is computed and must stay a literal, so both
mechanisms are needed either way.

## Cross-entity references

P6 allows two bindings — set membership, or an explicit entity. Only the first is built:
`wire.Bound` is `{property, op, value}`, so every term is about the member. Both halves are wanted,
for different reasons.

### Whole-set conditions belong to `If`, not to a member term

"Show these lights only in the evening" is a statement about the SET, and `Activation.State`
already expresses that shape. So a set-wide cross-entity condition needs no new member machinery —
it is a surface condition.

**But `If` is built on precisely the unbound predicate this plan removes**, and that is not a
coincidence:

```scala
case State(condition: Predicate, quantifier: Quantifier = Quantifier.Any)

// Renderer.holds
case Quantifier.Any => states.values.exists(Renderer.matches(condition, _))
```

An unbound predicate, quantified over the WHOLE state map — which is L6's `holds` bug restated.
Leaving `If` alone would leave two predicate languages in the tree, exactly what P4 says we will
not have.

**Follow-up task: give an `If` condition a candidate set.** Instead of quantifying over every
entity, it quantifies over a named set. Four things fall out at once:

- `holds` becomes a walk of N known candidates instead of a full-map scan — L6's second bug fixed
  **by construction** rather than by adding a pinned-condition shortcut
- `Quantifier` (`Any`/`None`/`All`) starts meaning what an author intends — "any light in the
  living room", not "any entity in the house"
- one predicate language, so P4 is real rather than aspirational
- whole-set cross-entity conditions get a home with no new concepts

This should be sequenced before, or with, deleting the free constructors from `components.pkl` —
otherwise `If` is left holding the only references to them.

### Per-member cross-entity refs are wanted too

A concrete case that is NOT set-wide: **motion-gated room lights.** Show each light only while its
own room's motion sensor is active — the sensor differs per member, and the pairing is derivable at
build time from `area_id`:

```pkl
q.from(dump.all.lights)
  .where(q.candidate((e) -> q.entityIs(motionSensorFor(e.area_id)).eq(q.stateProp, "on")))
```

`motionSensorFor` is ordinary Pkl over the dump, so the REFERENCE is resolved at build time to a
specific `entity_id` per member, while the state test stays live. Others of the same shape: a
bulb gated on its Zigbee router being online, a media control gated on its TV's power sensor.

Consequences to design, not assume:

- `Bound` gains an OPTIONAL `entity`; absent means "the member", which keeps every existing term
  unchanged.
- The reverse-index key becomes `candidates ∪ referenced entities`. Members naming different
  entities is fine — the `conditions` table already dedupes, so it gets one entry per distinct
  sensor.
- Nothing else moves: these are still per-member clauses, resolved the same way.

**Built** (`query-scenarios.test.pkl` S12/S13/S14). The SINGLE-entity case is as short as the
many-case, because a term that names its own entity needs no set and no quantifier — the list it
evaluates over is exactly what it references:

```pkl
c.iff(q.entity(dump.e_taklys).stateIs("on")).then(banner)          // one
c.iff(q.from(dump.stue.lights).any(q.eq(q.stateProp, "on"))).then(banner)  // many
```

`q.entity` keeps the ENTITY, not just its id, so a comparison is checked against that exact entity
(S15). Pkl has no generics, so `EntityRef<LightEntity>` is impossible and the static type cannot be
carried — but per-entity is the sharper question anyway, and it matches ADR 0013, which puts
capabilities on a class generated PER ENTITY: "does this entity have `brightness`" beats "is this a
light". A bare String is accepted for computed ids, at the cost of the check.

An unknown name warns rather than errors **only because the capability-derived schema is not built
yet** — that is an implementation gap, not a limit of the design. Volatile VALUES stay out of the
dump permanently (they would churn its content hash); volatile NAMES arrive with the schema, which
is derived from capability attributes and therefore safe to put there. When it lands this becomes a
hard error: a name in neither the registry nor the schema is a typo.
(`is` is reserved in Pkl — the type-test operator — hence `stateIs`.)

 `Bound.entity` is optional — absent means "the
member", so every existing term is unchanged — and `q.entity(id).eq(...)` names another entity.
Verified: each light's condition names its OWN room's sensor, the `conditions` table dedupes the
three living-room lights to one entry, and a cross-entity term conjoins with an ordinary one.
`wire.referencedEntities` extracts the ids, so the reverse-index key is
`candidates ++ referencedEntities`.

## Composite members

Two problems wore one name, and both fall to the same rule.

**(a) A member renders a subtree.** The candidate is still an entity; the rendering is not a leaf.
`Node` gains `children`, and the compression walks one level down.

**(b) A member is not an entity and contains a nested set** — "a tile per room". Areas become the
candidates; each tile holds a set over its own lights:

```pkl
q.from(dump.areas).render((a) ->
  c.card(
    c.title(a.name),
    q.from(a.lights).where(q.eq(q.stateProp, "on")).render(slider)))
```

**The finding: a nested set can never live in a shared shape**, because two areas never hold the
same lights. It has to be per-member data. But that is not a new mechanism — it is the SAME rule
that decides slot-vs-var, applied to child positions: a child that is identical across the members
sharing a shape stays in the shape; one that varies becomes a `Hole` the member fills.

```json
"shapes": [ { "card": "card", "slots": {}, "children": [ {"kind":"hole","idx":0} ] } ],
"members": {
  "area.stue": { "vars": {"title":"Stue"}, "fills": [ { "kind":"set", "candidates":[…] } ] }
}
```

So the skeleton still compresses to ONE shape for three tiles, the title is a var, and only the
genuinely per-room part — the nested set — is repeated. Verified: each tile's inner set carries its
own candidates, conditions and shapes, and behaves exactly as it would standalone.

Mechanics worth knowing:

- shapes group by card **and child count**: positions only align at equal arity, and a differing
  arity is a genuinely different shape.
- an invariant child (a divider, a fixed header) stays in the shape rather than becoming a hole —
  the compression really does apply at depth, not just at the top.
- an empty room yields an empty nested set, not an error.
- `children`/`fills` render as `[]` on every node in the spike's JSON. The real wire should omit
  them when empty; that is a circe concern, not a design one.

**An empty room can be dropped at BUILD time** — `lights` is registry data, so
`.where(q.candidate((a) -> a.lights.length > 0))` removes the tile with no runtime involvement.
Hiding a room when none of its lights are ON is the different problem: that needs an aggregate over
the inner set, and is the one part of (b) still missing.

## One predicate language: what actually changes

`If` today carries an unbound predicate quantified over the whole state map. Giving it a candidate
set is the unification, and it collapses three open items into one.

**Authoring.**

```pkl
// today — an unbound predicate, existentially quantified over EVERY entity in the house.
// "entity X is on" is spelled as "some entity is both X and on".
c.iff(c.entityIs("light.taklys").and(c.stateIs("on"))).then(banner)

// unified — a predicate over a NAMED set, which is what the author meant.
c.iff(q.from(dump.stue.lights).any(q.eq(q.stateProp, "on"))).then(banner)
c.iff(q.from(dump.stue.lights).count(q.eq(q.stateProp, "on")).gt(2)).then(banner)
```

**Runtime.**

```scala
// today
case State(condition: Predicate, quantifier: Quantifier)
case Quantifier.Any => states.values.exists(Renderer.matches(condition, _))   // O(all entities)

// unified
case State(over: List[String], condition: Term, quantifier: Quantifier)
case Quantifier.Any => over.exists(id => states.get(id).exists(Renderer.matches(condition, _)))
```

`over` is the same static candidate list a set already has, so the scan becomes a walk of N knowns
— **L6's `holds` bug fixed by construction**, not by adding a pinned-condition shortcut.

**The three items that collapse into one:**

- the two predicate languages become one (P4 stops being aspirational)
- `q.from(set).any(...)` / `.count(...)` IS the aggregate listed under "Not yet designed" — the
  same expression, read as a boolean instead of a branch selector
- and it is composite (b)'s missing piece: "hide the room when none of its lights are on" is an
  aggregate over the tile's inner set

Sequence it before deleting the free constructors from `components.pkl`, or `If` is left holding
the only references to them.

## Ids under recursion — and an invariant that retires itself

First, the correction. I framed this as "alternate a static-position segment and an entity-key
segment", implying the key segment was load-bearing. **It is not.** With static candidates, a
member's position in the candidate list is exactly as static as its entity id, and so is the hole
index, and so is the set's authored path. Walk the whole id:

| segment | varies at runtime? |
|---|---|
| set's authored path | no |
| candidate index / entity id | no — candidates are static |
| hole index | no — holes are fixed by the shape |
| inner candidate | no |

**Nothing in an id varies at runtime.** Presence and order do, but neither is part of identity — the
DOM reorders elements while their ids stay put. So a fully positional id (`3.7.0.2`) would work.

That exposes something worth recording: **§4b's "ids are key-derived, never positional" invariant
is a CONSEQUENCE of runtime-computed membership, not a law.** It exists because a positional id
renames every node below an arrival — and arrivals were what the old dynamic groups did. Static
candidates remove arrivals from the graph entirely, so the invariant retires with the machinery
that needed it. Anything claiming otherwise in ADR 0003 or §4b should be rewritten as history,
not as a rule.

**Recommendation: keep entity-keyed ids anyway** — but as a preference, not a requirement:

- they are readable in patches, logs and devtools; `c_3/light.taklys` tells you what a fragment is,
  `3.7` does not, and live patch debugging is where that pays
- they survive a rebuild when the entity does, whereas an index shifts when any earlier entity is
  added or removed. The functional gain is small (a renderer swap forces a full body repaint, so
  digests reseed regardless) but it is not negative
- it is the status quo — members are already `{gid}_{entity}` — so switching is churn for no gain

`MemberKey` needs no new variant either way. It stays `Entity`; only the scope qualifying it nests.

## What composite changes in the architecture

`docs/architecture-rendering-pipeline.md` §4b opens: *"The dashboard's graph has two halves. The
static half (`Renderer.allIndexed`) is computed once… The dynamic half (`MemberGraph`) is a group's
members, and it is maintained by the state stream rather than computed."* Three of those clauses
stop being true.

- **Membership is no longer maintained by the state stream.** Candidates are static, so members
  belong to the computed half. What the frame loop maintains is a PROJECTION over them — which are
  present, in what order — not the set itself. The two halves merge into one graph plus a per-frame
  projection, and `syncMembers` loses its reason to exist.
- **The graph becomes recursive.** A member can contain a nested set, so it is a tree of sets
  rather than a flat `group -> members` map. Ids nest accordingly
  (`set / memberKey / childIdx / innerSet / memberKey`), and `MemberKey` being key-derived rather
  than positional matters more, not less.
- **The reverse index gains non-candidate edges.** `candidates ++ referencedEntities` — a node can
  now be woken by an entity it does not render.
- **The DYNAMICS kind changes meaning.** It was "a query-driven group whose MEMBERSHIP may have
  moved". Membership cannot move any more; only presence and order can. Still `Gone`/`Placed`, still
  no fourth kind — but computed from a projection rather than from a scan.

The doc must change in the same commit as the code, per the repo rule. §4b is the section that
moves; §3's kind table needs the DYNAMICS row reworded.

## Not yet designed

Named so they are not mistaken for oversights. The first two are wanted; the rest are known
trade-offs.

**Composite members — the structural half is now spiked** (`composite.test.pkl`). See "Composite
members" below. What remains is the live variant of an empty-room test, which needs aggregates.

**Aggregates.** `count`/`any`/`all`/`min` over a set. Sketched (a derived value is a node whose
index key is the candidate list) but never spiked, and no wire representation exists. (b) depends
on it, and so does "show this when more than X are on".

**`limit` / take N.** "The three brightest" is runtime — it depends on live ordering — and there is
no `limit` field. Cheap to add after ordering, but it introduces a third member state:
present-but-cut, distinct from absent.

**Per-member layout.** `cell` (`columns(n)`, `fullWidth()`) lived on the old `DynamicCase`. Under
`shapes` it belongs to the shape, so a per-member span would have to become a var. Unspecified.

**Cross-set interleaving.** Two sets are two DOM regions and cannot be ordered against each other.
Accepted, not solved — it is the price of splitting a query into two.

**Unsatisfiable conjunctions** (`state == on AND state == off`) are not detected. Exotic; detecting
it means a satisfiability check.

## Open questions

- The LINQ surface beyond the sketch above — `orderBy` stability rules, what `derived` values exist
  besides `count`, and whether `render` composition needs anything past `Cases`.
- **var-vs-case is count-dependent.** The spike decides by diffing the members that share a shape,
  so a single-member shape has nothing to diff and bakes its entity-derived literal onto the case;
  adding a second entity of that kind restructures the emitted shape. Pinned by
  `dynamics-spike.test.pkl` ("single-member shapes bake their literal onto the case").

  *Probably cosmetic, but unverified.* It would only matter if a shape index carried meaning
  beyond selecting card + slots — it does not today — and adding an entity is a registry change,
  which already forces a rebuild, renderer swap and full body repaint, so the reshuffle rides
  along with work that was happening anyway. Before relying on that, confirm the repaint claim
  against `Server.reloadRepaints` rather than taking it from this document. If it turns out to
  matter, the stable rule is P2 applied directly — a literal slot is always a per-member var, a
  transform slot always stays on the case — at the cost of repeating genuinely constant literals.

## How we would know it works

- `hass.SELF` as a runtime unknown is gone, and an expression touching `area_id`/`friendly_name` on
  a dynamically-presented entity yields the real value (L1/L2).
- A frame still costs the number of changed entities, not node count: `Patches.plan` walks
  `changes.flatMap(componentsFor)` and nothing introduces a per-frame walk over all nodes.
  `RendererSuite`/`RenderCacheContentionSuite`/`SharedPassSuite` stay green **unmodified**.
- A non-present member's order key moving emits **zero** patches; it acquires its position in the
  `Placed` that shows it.
- A node that is not shown is absent from the served markup — assert on the markup, not only the
  screenshot. Same for an unmatched surface host, which must occupy no layout space.
- A count over an N-candidate set does N lookups, not one per entity in the house — assert against
  a synthetic large state map (the "2 000-entity house" shape at `Renderer.scala:382`).
- An unbound expression is unconstructible in Pkl; `Dashboard.validate` rejects one arriving by
  hand with a located message.
- Do not run the pkl suites concurrently with `dashboardServe` — shared dump cache, fake timeouts.
