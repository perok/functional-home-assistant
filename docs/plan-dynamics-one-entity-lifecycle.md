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
 "cases":[{"card":"slider","slots":{
     "state":{"transform":"$state","reactive":true},
     "fill": {"transform":"$round($attr.brightness*100/255)","reactive":true},
     "key":{"literal":"brightness"},"min":{"literal":"1"},"max":{"literal":"255"},
     "action":{"literal":"light/turn_on"}}}],
 "members":{"light.a":{"vars":{"label":"Taklys"},"alts":[{"cond":1,"shape":0}]},
            "light.b":{"vars":{"label":"Lampe"}, "alts":[{"cond":1,"shape":0}]},
            "light.c":{"vars":{"label":"Spot"},  "alts":[{"cond":1,"shape":0}]}}}
```

Two dedup tables, `cases` and `conditions`; members are thin rows of indices plus their varying
literals. `cond: 0` is always `null` = unconditional. `orderBy` carries no `entity` — it is bound
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
"cases":[{"card":"slider",...},{"card":"toggle",...}],
"members": {
  "light.a": {"vars":{"label":"Taklys"}, "alts":[{"cond":1,"shape":0},{"cond":0,"shape":1}]}
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
| ordering | not supported | O(P log P), only when a *present* member's key moves |
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
any member change wakes the count node, its digest moves, one morph. `count > 2` as a surface
condition is then an ordinary bound predicate.

## Making an unbound expression unrepresentable

Today it is trivially representable — the free constructors take no subject:

```pkl
function stateIs(s: String): Cmp = cmp("state", "eq", s)   // no subject anywhere
```

Fix: **delete the free constructors; make the binding a non-null field.**

```pkl
class Bound extends Predicate {
  entity: String            // non-null -> unconstructible without a subject
  property: String          // "state" | "attr:<name>" only
  op: PredicateOp
  value: Any
}

abstract class Entity {
  function stateIs(s: String): Bound = new { entity = entity_id; property = "state"; op = "eq"; value = s }
}
```

`domainIs`/`entityIs` disappear from the runtime vocabulary — domain is a build-time filter, entity
is now the binding — leaving runtime comparisons as `state` and `attr:*` only.

Pkl is the primary guarantee; a hand-written AST or hand-edited `dashboard.json` is on its own. A
cheap non-null `entity` check goes in `Dashboard.validate` as belt-and-braces, not as the mechanism.

## LINQ surface (shape agreed, details open)

One `where`, with folding in the combinators:

```pkl
e.domain.is(hass.domains.Light)   // -> Static(true|false)  — registry fact
e.stateIs("on")                   // -> Bound{...}          — live

Static(false).and(x) -> Static(false)   // candidate dropped from `candidates`
Static(true).and(x)  -> x               // term vanishes
Static(true).or(x)   -> Static(true)    // always present; `present` omitted
```

`Static` never reaches the wire, so no `whereStatic`/`where` split is needed and `inArea` is just
sugar for `where((e) -> e.area_id.is("stue"))`. Case dispatch uses the same folding:

```pkl
.render(new Cases {
  [(e) -> e.domain.is(Light)]       = (e) -> c.slider(e)
  [(e) -> e.domain.is(MediaPlayer)] = (e) -> c.mediaCard(e)
  default                           = (e) -> c.entityCard(e)
})
```

## Pkl mechanics (verified on 0.32.1, the `pkl-core` pin)

Spiked rather than assumed, per the module's "verify semantics empirically" rule:

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

**`orderBy` stays set-level, deliberately.** It has no equivalent divergence problem because a
sort needs one comparable key across the set. An author *could* write a key that folds to
different properties per member; that is not supported, and should be rejected rather than
emitted — comparing brightness against temperature is not an ordering.

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
