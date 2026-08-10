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
 "present":{"property":"state","op":"eq","value":"on"},
 "orderBy":{"property":"attr:brightness","dir":"desc","tiebreak":"entity_id"},
 "cases":[{"card":"slider","slots":{
     "state":{"transform":"$state","reactive":true},
     "fill": {"transform":"$round($attr.brightness*100/255)","reactive":true},
     "key":{"literal":"brightness"},"min":{"literal":"1"},"max":{"literal":"255"},
     "action":{"literal":"light/turn_on"}}}],
 "members":{"light.a":{"case":0,"vars":{"label":"Taklys"}},
            "light.b":{"case":0,"vars":{"label":"Lampe"}},
            "light.c":{"case":0,"vars":{"label":"Spot"}}}}
```

`present`/`orderBy` carry no `entity` — they are bound by set membership (P6's second shape).
`case` is an **index resolved at build time**: dispatch by domain or `device_class` is a registry
fact, so Pkl picks the winner per candidate. Only a genuinely state-dependent case needs a `when`
on the wire.

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

## Open questions

- The LINQ surface beyond the sketch above — `orderBy` stability rules, what `derived` values exist
  besides `count`, and whether `render` composition needs anything past `Cases`.
- Whether per-member `vars` deduplicate into a shared entity table. `min`/`max`/`key` are
  per-*domain*, not per-entity, so they belong on the case — which may shrink `members` to
  `{"light.a":{"case":0,"label":"Taklys"}}`.
- Whether `present` and `orderBy` on the wire stay unbound-but-set-scoped templates, or are
  expanded per candidate at build time (bigger JSON, dumber runtime).

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
