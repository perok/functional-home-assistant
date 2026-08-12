# Dynamics: one entity lifecycle

> Status: **design settled, nothing implemented in the product.** Every decision here is spiked
> and tested against running Pkl under `modules/fh-datastar-view/src/test/pkl/` — but that spike is
> scaffolding, not the shipped library (see "The spike is scaffolding"). What remains is moving it
> onto the real code.
>
> **How this is organised.** *Why* states the problem, the principles and the limitations being
> fixed. *The format* and *The authoring surface* are the design. *What it costs and what it checks*
> is the behaviour that follows. *What this changes in the product* is the impact on existing code.
> *Closing out* is verification and what was deliberately not solved. The appendix holds the Pkl
> semantics established along the way, and the original design notes.
>
> Every claim here is backed by a test under `modules/fh-datastar-view/src/test/pkl/`; where a
> section names a scenario (S12, C7, …) that is the test pinning it.

# Why

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

**P9 — Plain Pkl stays first-class.** The query language is for LIVENESS. Static composition —
a `for` over a typed dump list — must remain the simplest way to write a dashboard, and must never
become the second-class way:

```pkl
for (light in dump.stue.lights) { c.slider(light) }     // still the right answer
```

This is a commitment, not an accident of the current state. `q.` adds ~16 concepts, and they earn
their place only for what static composition genuinely cannot do: filtering on live state,
ordering by a live value, counting, limiting, presence. Reaching for `q.from(...)` to render a
fixed list of lights is a regression in authoring cost with nothing bought. Expect the drift to go
the other way — toward "use the query language for everything" — and resist it: an author should
be able to write a useful dashboard having never met `q.`.

## Limitations this must fix

| | limitation | status |
|---|---|---|
| **L1** | Two entity lifecycles — typed static vs untyped `$self` | the core; addressed by P1 |
| **L2** | The runtime predicate is registry-blind (no `area_id`/`floor_id`/…) | resolved by P2 — those become build-time selection, so the need disappears rather than being met |
| **L3** | Membership costs a full-map scan, because the candidate set is a runtime query | resolved by P5 |
| **L4** | No set-level derivations — no count, no aggregate, and ordering is `entity_id` only (`materialise` sorts `states.toVector.sortBy(_._1)`) | resolved — ordering and aggregates both spiked |
| **L5** | A predicate term with no indexable source silently never updates | resolved for the motivating case (`sensor.time` is an entity); the validate-time rejection is still to build |
| **L6** | The surface mechanism has three bugs — uncached `bakeGroup`, whole-map `holds`, non-collapsing empty `ifhost` | designed, **not built**: `holds` is fixed by construction once `If` takes a candidate set; the other two are independent |
| **L7** | Ghost members on entity removal (ADR 0003 open item) | resolved for free — removal is a registry change, hence a rebuild |
| **L8** | The static selection vocabulary does not compose | resolved by `query.pkl` |

# The format

## The wire format

Authored:

```pkl
q.from(dump.stue.lights)
  .where(q.eq(q.stateProp, "on"))
  .orderBy(q.desc(q.prop("brightness")))
  .render((e) -> c.slider(e))
```

Emitted — one `set` node owning the candidate list, the ordering and the limit; each member a list
of guarded renderings:

```json
{"kind":"set",
 "candidates":["light.a","light.b","light.c"],
 "orderBy":[{"by":{"property":"attr:brightness"},"dir":"desc"}],
 "limit":null,
 "members":{
   "light.a":{"clauses":[
     {"when":{"kind":"cmp","property":"state","op":"eq","value":"on"},
      "node":{"card":"slider","cell":{"classes":["fh-cols-6"]},
              "slots":{"label":{"literal":"Taklys"},
                       "state":{"transform":"$state","reactive":true}},
              "children":[]}}]}}}
```

**A clause is a complete rendering.** `when` is the guard — null means unconditional — and `node`
is the whole thing: card, cell, slots, children, inline. Nothing is shared, so nothing has to be
looked up or merged, and a clause cannot be wrong about which member it belongs to.

A member is a `cond` expression: clauses are tried in order and the first whose guard holds decides.
**Falling off the end means the member is not rendered** — which is why there is no separate
presence field, and why omitting the `else` is how you write "only while on".

A nested set is simply a child of a node. There is no hole/fill indirection, because there is
nothing shared to punch a hole in.

### Complexity

`N` = candidates, `P` = present members, `E` = all entities in HA, `Δ` = entities changed in a frame.

| | today | after |
|---|---|---|
| `dashboard.json` | O(1) per group | **O(N)**, ~459 bytes per candidate |
| membership per frame | **O(E · groups)** — full map scan | **O(Δ)** index lookups |
| presence eval | — | O(Δ) per frame; O(N) once at renderer construction |
| ordering (any live key) | not supported | O(P log P), only when a *present* member's key moves |
| ordering (all keys static) | not supported | **O(P)** — pre-sorted at build, runtime only filters |
| DOM elements | O(P) | **O(P)** |
| bytes to client | O(changed) | O(changed present) |

The wire grows linearly in candidates; nothing the client or the frame loop does is linear in
candidates. `dashboard.json` never reaches the browser — it is a build artifact and the runtime
holds the model in memory — so its size costs eval time and memory, not client work.

## Rejected: the compressed format, and when to revisit it

This was briefly designed the other way: shared `shapes` and `conditions` tables, members as thin
rows of indices plus their varying literals (`vars`) and nested sets (`fills`), with `holes` marking
where a shape's children vary. Built, tested, then removed.

**Measured**, on N synthetic lights (post-fold, so no `$domain` lookups):

| candidates | compressed | expanded | ratio |
|---|---|---|---|
| 10 | 2.1 KB | 4.4 KB | 2.1× |
| 50 | 9.2 KB | 22.3 KB | 2.4× |
| 200 | 36.1 KB | 89.4 KB | 2.5× |
| 1000 | 180.7 KB | 448.0 KB | 2.5× |

Per-candidate cost is flat either way — 185 bytes compressed, 459 expanded — so both grow linearly
and the ratio settles at ~2.5×. No exponential term.

**What the 2.5× cost:** four concepts (`shapes`, `conditions`, `holes`/`fills`, `vars`) and two
classes of bug, both found in the spike and both structurally impossible in the expanded form — a
clause carrying another clause's literals, and two cards with different cells silently sharing a
shape. It also needed a rule for whether a literal is shared or per-member, which then had to be
made count-independent to stop identical authoring emitting two different structures.

A bad trade for a server-side artifact. **Revisit only if memory or Pkl eval time on a large real
dashboard actually bites** — and the cheaper first move then is for the BACKEND to intern identical
terms at load, recovering the sharing without putting any of it on the wire. What interning does
not recover is repeated transform text, which is most of the difference.

## Resolved by the spike

**There is no set-level `present` field; presence collapses into the CLAUSES, which index a deduped
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
property vocabulary" — the shape settled on references rather than lambdas, which removed
`StaticSort` and `MemberDef.sortVars` entirely. Read that section, not this paragraph, for the
current form.

**The one error case is a single POSITION that folds inconsistently across candidates** — static
for one candidate, live for another. Those two values are not mutually comparable (a brightness
against a name), and unlike presence there is nothing useful to do with the divergence. This is
narrow: it is not about having several keys, which is fully supported.

`orderBy` does NOT join the `conditions` table — a key extractor is not a guard.

**The build emits an already-split node; the runtime never re-derives the split.** The spike does
the fold at build time and emits only the residue — candidates and per-clause guards. The
runtime receives no static terms at all and cannot tell which were folded away. The cost is that
`dashboard.json` sits further from what was authored, which matters for the editor; the benefit is
that the runtime carries no folding logic and cannot disagree with the build about it.

**Presence and ordering are one mechanism, confirmed.** Presence is "some alt matched"; a member
appearing or disappearing is `Placed`/`Gone`, which is the same patch pair a reorder emits. There
is no separate visibility path to build.

**A shared entity table is not needed.** Registry facts that the runtime can resolve become
`reg:` references and never ride per member at all; anything computed is a literal on the node.
Revisit only if a measurement says otherwise.

## Layout

`cell` (`columns(n)`, `fullWidth()`, `hug()`) sits on the member's node, like every other part of
its rendering:

```pkl
.caseOf(q.candidate((e) -> e.supported_features > 4), (e) -> c.slider(e).columns(6))
.`else`((e) -> c.toggle(e).columns(3))
```

Under the compressed format this needed care — the cell had to join shape identity, or two members
with the same card and different widths silently shared one. Expanded, the question does not arise:
a node carries its own cell and nothing merges.

## `limit`: a third member state

`limit: Int?` on the set, applied AFTER ordering. Every candidate still ships — which of them is
cut depends on the live ordering, so it can only be decided at runtime.

It introduces a member state the format did not have: **present-but-cut**, distinct from absent.
Under P7 a cut member is absent from the DOM, not hidden in it, so the two look the same to the
client and differ only in why. Worth naming because the runtime has to distinguish them: an absent
member has no matching clause, a cut one matched but lost its place.

## Aggregates: count the present members

The reduction that makes these cheap: **an aggregate counts the PRESENT members of a set**, and
presence is already per-member and already computed. So `count` needs no predicate machinery of its
own, and the three quantifiers become three comparisons:

```
any   ->  count > 0
none  ->  count == 0
all   ->  count == candidates.length
```

**That is what retires `Quantifier`.** It existed only because an unbound predicate had to be
quantified over the whole state map; over a known candidate list there is nothing left for it to
say. `Agg { op, over: SetNode, of: PropRef? }` plus `AggCmp extends Term` — so a comparison on an
aggregate is an ORDINARY term and composes with everything: a member clause, a surface condition,
an `and`/`or`.

```pkl
q.from(dump.stue.lights).where(q.eq(q.stateProp, "on")).count().gt(2)
q.from(dump.stue.lights).where(q.eq(q.stateProp, "on")).any()      // same thing
```

Two things the spike turned up:

- **An aggregate needs presence, not rendering** — but presence lives on a clause, and a candidate
  with no clause is dropped. A counted set therefore gets one unconditional marker branch whose
  shape is never rendered.
- **`count` over a statically empty set folds to a constant.** `count(∅)` is knowably 0, so
  `any()` on an empty room becomes `false` and the tile is dropped at build time instead of
  carrying a condition that can never hold. Same fold as everywhere else, one level up — and it is
  what makes the live tile-per-room case work (C8).

This closes composite (b): "hide the room while none of its lights are on" is an aggregate over the
tile's own inner set, and each room's condition carries its own.

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

**A nested set is simply a child of the member's node.** The compressed format needed a hole/fill
indirection here — two areas never hold the same lights, so a nested set could never live in a
shared shape and had to be punched out per member. Expanded, there is nothing shared to punch a
hole in, and a tile is just a node with a set inside it.

```json
"members": {
  "area.stue": { "clauses": [ { "node": { "card":"card",
      "slots": {"title":{"literal":"Stue"}},
      "children": [ { "kind":"set", "candidates":[…] } ] } } ] }
}
```

Each tile is a complete node with its own title and its own nested set. Verified: the inner set
carries its own candidates and guards and behaves exactly as it would standalone.

Mechanics worth knowing:

- an invariant child (a divider, a fixed header) is simply repeated on each member — cheap, and it
  cannot be wrong about which member it belongs to.
- an empty room yields an empty nested set, not an error.
- `children` renders as `[]` on every node in the spike's JSON. The real wire should omit it when
  empty; that is a circe concern, not a design one.

**An empty room can be dropped at BUILD time** — `lights` is registry data, so
`.where(q.candidate((a) -> a.lights.length > 0))` removes the tile with no runtime involvement.
Hiding a room when none of its lights are ON is the different problem: that needs an aggregate over
the inner set, and is the one part of (b) still missing.

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

**What the WIRE must carry for this to be derivable**, pinned by C11–C13:

- Two sets over the same entity keep entirely independent member entries — their own clauses,
  vars and conditions. Nothing is shared or merged, so `(set, entity)` separates them and `entity`
  alone would collide (C11).
- That holds even in the nastiest arrangement: an entity that is an outer member AND a candidate of
  a set nested inside its OWN member (C12).
- **A set carries no id of its own, deliberately.** Identity comes from CONTAINMENT — a set is
  reached either as a node in the layout tree or as exactly one member's fill, and that path is
  what the backend keys on. C13 pins the containment being unambiguous: every nested set hangs off
  one member's node, so no set is reachable by two paths.

The spike cannot test the ids themselves — it does not generate them. That belongs with the
`wire.pkl` → `DashboardBuild` move, and these tests are what it has to keep true.

# The authoring surface

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

See "The scenarios, in verified authoring syntax" for the worked examples — those are copied
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

## Making an unbound expression unrepresentable

Every term carries a binding, so there is no unbound form to construct. Today that binding is
**set membership**: `wire.Bound` is `{property, op, value}` with no entity, because the set's
`candidates` say who it applies to. The free constructors that made `stateIs("on")` meaningful on
its own are gone; a term is only reachable through `q.eq/ne/gt/lt` over a `q.prop(...)`, or through
`q.candidate(...)` which is resolved against a specific candidate at build time.

The other binding P6 allows — an explicit reference to a DIFFERENT entity — is **also built**:
`Bound.entity` is optional, absent meaning "the member". See "Cross-entity references".

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
  .where(q.candidate((e) -> q.entity(motionSensorFor(e.area_id)).eq(q.stateProp, "on")))
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
- `Node.children` takes the inlined `Node|SetNode` rather than a `Child` alias, for the same rule.
- `applyOp`'s operands, which are genuinely heterogeneous.

The public surface — `from`, `where`, `render`, `orderBy`, `entity`, the comparison builders — is
now precisely typed.

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

// S5 — dispatch on LIVE state. Both renderings stay; the choice rides the wire
//      as a clause list, first match winning.
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

# What it costs and what it checks

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
state==off`) is not detected (see "Settled as non-issues"), and a typo'd live attribute cannot
be until the capability-derived schema lands.

## Validation: what the build can and cannot check

`q.prop(name)` is resolved against the candidates by counting how many carry it as a property:

| candidates carrying it | resolution |
|---|---|
| all | registry fact, readable by the build |
| none | live attribute — **unvalidatable** until the schema lands |
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

**Deduped by capability signature** — a shared table is right HERE, unlike in the node format,
because a signature is genuinely shared by many entities and carries no per-entity data.
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

## The registry lives in the backend

Decided: keeping `area_id`/`floor_id`/etc. in the runtime is acceptable. That is what lets a
resolved registry property stay a REFERENCE rather than shipping a value per member, and keeps
`dashboard.json` from carrying what the backend already knows. `RegistryDump` already fetches all
of it, so this is one table from the existing source, not a second representation of identity —
candidates are still decided at build time.

The same move is available for `vars` (a bare `friendly_name` label could become a reference
instead of a literal), which would shrink members further AND dissolve the var-vs-case
count-dependence ("var-vs-shape is decided by TYPE"), since there would be no literals left to
diff. Not done yet — an
interpolated label like `"Blah \(e.friendly_name)"` is computed and must stay a literal, so both
mechanisms are needed either way.

# What this changes in the product

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
- `q.from(set).any(...)` / `.count(...)` IS the aggregate (see "Aggregates: count the present
  members") — the same expression, read as a boolean instead of a branch selector
- and it is composite (b)'s missing piece: "hide the room when none of its lights are on" is an
  aggregate over the tile's inner set

Sequence it before deleting the free constructors from `components.pkl`, or `If` is left holding
the only references to them.

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

## Phases

Ordered by dependency, and by what can stop without leaving a mess. Each phase is independently
shippable — if the next one never happens, the tree is still coherent.

**Phase 0 — decoupled fixes.** *Shipped (`f66ba31`).* `bakeGroup` memoised; an unmatched `If`
collapses. Neither touches the format; both were live bugs.

**Phase 1 — one slice through the runtime.** *Shipped, except the build fold.* `SetNode` /
`SetMember` / `SetClause` / `SortTerm` in the Scala wire model, `Predicate.Cmp.entity` for a
cross-entity guard, and the renderer consuming all of it through one `MemberSource` interface that
the query-driven group now shares. `SetNodeSuite` drives S1 end to end through the real `Server`:
presence-via-clauses, `Placed`/`Gone` at the authored position, an unguarded clause surviving an
entity HA never reported, and a guard woken by an entity that is not a candidate.

Three things it settled:

- **A frame costs Δ.** `MemberSource.affected` maps a change to the candidates it can move —
  itself, plus every candidate whose guards name it — so nothing walks the candidate list. The
  cross-entity case is exactly why that has to be a reverse index rather than `change.entityId`:
  the sensor's change moves a member that is not the sensor.
- **Placement had to be generalised, not reused.** `insertOrdered` sorted by entity id, which is
  right for a query group and wrong for a set: candidate order is AUTHORED. It now asks the source
  for an ordinal.
- **Nothing else in the pipeline needed a set case.** `Patches`, the changelog, the churn
  heuristic and the resume path all reach a set through `isDynamicContainer` / `renderMount` and
  were untouched — which is the evidence that presence and membership really are one mechanism.

What is left of phase 1 is the build side: the Pkl fold in `DashboardBuild`, which arrives with
phase 2's authoring surface. `orderBy` and `limit` are modelled but unread, and a clause whose node
is a nested set is dropped rather than half-rendered — both phase 3. *Stop here and nothing is
worse: dynamic groups still work, and nothing can author a set.*

**Phase 2 — the authoring surface.** *Shipped, except retiring the spike.* `lib/query.pkl` is the
authoring namespace, imported as `@fh-dashboard/query.pkl`; the wire classes
(`SetNode`/`SetMember`/`SetClause`) live in `components.pkl`, which owns the layout vocabulary, and
`Cmp` gained the optional `entity`. `query.test.pkl` covers the surface against typed entities;
`PklBuildSuite` carries a query over a real dump through `SourceEval` into a decoded `SetNode`.

**The fold runs in Pkl.** A render lambda (`(e) -> c.slider(e)`) can only be applied in Pkl, so Pkl
iterates the candidates whatever else happens; folding the guards there costs nothing extra, where
emitting them unfolded for Scala would mean two passes and a second place deciding what presence
means. The Scala side keeps the model and the renderer.

Deliberately NOT shipped in the surface, rather than shipped-and-erroring, so pkl-lsp says "unknown
method" at the point of use: `limit`, aggregates (`count`/`any`/`none`), and nested sets. Ordering
IS here but only when every key folds to a registry fact — a live key throws, naming the phase-3
gap, because emitting an `orderBy` the renderer ignores would be silent.

*Stop here and you have the new thing alongside the old.*

**Phase 3 — ordering, aggregates, limit, composite.** Each is additive over Phase 1's path and
independently testable. Composite (b) needs aggregates; nothing else has an internal order. Ends by
checking the shipped syntax against every scenario the spike pins, and then deleting the spike.

*Ordering and `limit` are shipped.* Both are live-only concerns, so both landed together:
`SortKey` is a sum of `Prop` (a value) and `Holds` (a predicate's truth), the fold emits only the
positions it could not resolve, and a set carrying either is no longer patched incrementally —
`syncMembers` rebuilds its member list, because one entity moving can reorder its neighbours or
push a different member past the cut.

Two things this turned up that the design had not:

- **A pure reorder emitted nothing.** `recordDynamic` computed churn as arrivals plus departures,
  and stopped at zero — correct while the only container was a query group, whose entity-id order
  cannot move. A move is now a third kind of churn, patched as the same `Gone`/`Placed` pair an
  arrival is, so it needs no new patch kind.
- **Which members move has to be MINIMISED**, or the trap this plan already names ("a stream of
  `Gone`/`Placed` pairs, which is exactly what P7 exists to prevent") arrives by construction.
  `Patches.reordered` keeps a longest increasing subsequence of the old positions and moves only
  the rest, so one light overtaking three others costs one move, not four.

*`count` is shipped* — the original ask, "if more than X elements then we show this". It came out
smaller than designed, in two ways worth keeping:

- **No `SetNode` inside the term.** The spike embedded the whole set, which forced a marker branch
  onto any set being counted (an aggregate needs presence, and presence lived on a clause, and a
  candidate with no clause was dropped). `Predicate.Count` carries what a count actually reads —
  the candidate ids and their presence guards — so counting is not rendering and a counted set
  needs no card at all. The marker branch is gone.
- **It is an ordinary `Predicate`**, so it composes everywhere one does, and it retires the
  quantifiers exactly as designed: `any` is `count > 0`, `none` is `count == 0`, `all` is
  `count == length`. `q.` exposes those three names, and they build the same term.

The static fold works one level up as predicted: a count whose candidates all resolve at build
time IS a number, so `q.from(emptyRoom).any()` is `false` before anything runs and the card it
guards never reaches the wire. `min`/`max` are not here — they were the spike's addition, not a
requirement, and can arrive with a use case.

*The churn heuristic is gone.* `MaxChurnFraction` filled a mount wholesale past half the group's
members. That was written for a query group over the whole house, where membership could swing
without bound; over a static candidate list it is backwards for ordinary frames, because **a fill
re-renders the members that did not change** and raises the mount's horizon, dropping every client
below that cursor onto the same wholesale path. At its own motivating boundary — removing 1 of 2
members — the delta is a single `remove` carrying no HTML, and the fill it chose instead re-sent
the survivor for nothing.

The rule now: **fill when the UNCHANGED set is empty** — everything arrived, or everything left, so
the fill re-sends nothing and one patch replaces N — or when `hasChildOf` is false and there is no
baseline to patch against. No new primitives; `Gone`/`Placed` were already complete, and these are
just the two cases where collapsing them costs nothing. The case the fraction genuinely won,
near-total churn of many tiny members, is narrow enough to pay for out of simplicity.

*Composite (a) is shipped, (b) is not, and they turned out to be different sizes.* A member
rendering a SUBTREE is small: the children ride inside the member's bytes with no ids of their own,
so the member stays the single patch target, and the only real work was the reverse index —
`Component.liveEntities` stops at the node, which is right for an addressable node and wrong for a
member, whose children are not addressable. Without `Member.entitiesOf` walking them, a tile whose
child binds a second entity silently stops updating.

**(b) is shipped in the runtime.** A set nested inside a member is an ordinary container with an
ordinary id: the whole tree of sets is enumerated at renderer construction (candidates are static,
so it is knowable before any state arrives), and the inner members are graph nodes that patch
themselves. A bulb going out removes its own element; its tile is never re-rendered and the other
tiles are not touched.

**The `self`/`mount` split turned out to need no template support at all.** The tile's own bytes are
everything except the inner group, and the inner group is the mount — synthesised, as agreed. The
reason it costs nothing: a tile's content is a REGISTRY fact, hence a literal, and
`Dashboard.validate` already refuses a live slot on a container with no `self`. So the case that
would have forced a template split is the case the existing rule already rejects, and a card that
wants a live title declares a `self` exactly as `Tabs` does.

Three things it needed, each silent when wrong:

- **`Member.entitiesOf` must NOT descend into a nested set.** Its members are tracked as members;
  descending would wake the whole tile on any bulb inside it, re-rendering and re-supplying
  everything the inner members had just patched for themselves.
- **`affectedDynamics` reads `memberSources`, not the static index.** A nested set is not in the
  static index — it hangs off a member, which is the dynamic half — so selecting from the index
  meant the inner set synced, its members moved, and nothing recorded it. That was the bug: correct
  ids, correct HTML, zero patches.
- **`hasChildOf` became `holdsAnyOf`** — named ids rather than an id prefix, since an inner
  member's id starts with the outer gid and a container would otherwise look established on the
  strength of its grandchildren.

What is NOT authorable yet is the motivating example, "a tile per ROOM": `q.from` takes
`List<hass.Entity>` and an area is not an entity. That is the `Candidate` marker supertype the spike
designed, and it is the remaining piece of (b). A set nested inside an ENTITY's tile works today.

*Superseded — kept for the reasoning:*

**(b), a nested set, needs the `self`/`mount` split first.** The tile's own rendering would contain
the inner members' bytes, and both would be logged — breaking "no node logs a fragment containing
another", the invariant `DynamicGroupSuite` already pins. So a tile holding a set is shaped like a
bake host: its card is the `self` (one patch target, for the title), the nested set is the sibling
`mount`. Two more things fall out, both cheap once that is decided:

- **Ids nest positionally through the set, by key through the member**: `c_1` → `c_1_area_stue` →
  `c_1_area_stue_2` (the child index — a hole cannot move) → `c_1_area_stue_2_light_taklys`.
  Everything is static, so the whole tree of sets is enumerable at renderer construction.
- **Ownership is a MAP, not a parsed id** — already done, ahead of (b), because it is the better
  answer to the same problem. `memberAt` used to find a container with
  `keys.find(gid => id.startsWith(gid + "_"))`; a candidate set's members are all knowable at
  construction, so `memberOwner` answers exactly and the id is never parsed. That is what a
  longest-prefix match would only have approximated: a prefix test cannot tell `c_1_light_a_b`
  (set `c_1`, entity `light.a_b`) from a member of a set called `c_1_light_a`, and once sets nest
  it cannot tell an inner member from an outer one. The prefix search survives for query groups
  ALONE, whose members are any entity in the house and so cannot be enumerated — and it retires
  with them in phase 5.

Inner members are ordinary graph nodes once registered, so a bulb inside a tile patches its own
element — which is the whole point of nesting rather than re-rendering the tile.

One more id-parsing site is left to deal with when (b) lands: `FragmentLog.hasChildOf(gid)` also
tests `startsWith(gid + "_")`, and an INNER member's entry would answer for its outer set — so the
outer set would look established when only the inner one is. Same fix, same reason.

Mixed orderings are constrained: registry keys may only be the LEAST significant positions. A
registry key that outranked a live one would have to reach the runtime as a `reg:` reference, and
the runtime holds no registry table — so that combination throws at build time, naming the fix,
rather than ordering by the wrong thing. Registry keys after every live one are free: pre-sorting
the candidates supplies them, because the runtime's sort is stable over candidate order.

**Phase 4 — `If` takes a candidate set.** Fixes `holds` by construction, retires `Quantifier`,
collapses the two predicate languages into one. Must precede Phase 5.

**Phase 5 — retire the dynamic-group query half.** Delete `syncMembers`, the full-map matching,
`hass.SELF`, the free predicate constructors. Only safe once nothing authored uses them AND Phase 4
has taken `If` off them.

**Phase 6 — the attribute schema.** Capability-derived, in the dump; turns the unknown-name warning
into a hard error. Independent of 1–5; slot it wherever convenient.

**Phase 7 — docs.** ADR 0003/0004 rewritten, ADR 0007 cost claims qualified,
`architecture-rendering-pipeline.md` §4b and §3's DYNAMICS row. The repo rule says these move with
the code, so in practice each phase carries its own slice of this rather than deferring it here.

**Rollback shape.** Phases 1–3 add a node kind the old renderer never emits, so reverting is
deleting the new path. Phase 5 is the first irreversible one — after it, the old dynamic groups are
gone. Everything before it can stop indefinitely without leaving the tree in a half-state.

**After the plan: seed the changelog with the membership a swap already knows.** Found while
writing `SetNodeSuite`; deferred deliberately, on the grounds that a restart or a dashboard edit
does not have to be byte-perfect.

`Patches.recordDynamic` chooses a wholesale mount fill over a per-member delta when
`!base.hasChildOf(gid)` — the shared changelog holds no `<gid>_<entity>` entry to patch against.
A fresh log has none, so the FIRST membership change in each container fills its mount and, in
doing so, writes the entries that make every later change a delta. The log rotates per renderer
swap, not per connection, so the cost is one extra mount fill per container per restart or
dashboard edit. Self-healing after one frame, and identical for the old dynamic groups — the set
node neither introduced it nor makes it worse.

The client is not the reason. Anyone who painted the body demonstrably holds every member; it is
the SHARED log that cannot vouch for a baseline. So the fix is to seed it at swap with the
membership the renderer could already derive — which is why it is not a one-liner: it means
materialising membership at swap time from the current snapshot, and "the recorder is the only
writer" (`architecture-rendering-pipeline.md` §4b) is exactly the invariant that guards against a
derived value becoming a frame's "before". Wrong there is silent. Worth doing on its own, against
the recorder, with its own tests — not folded into a phase.

## First slice: prove the runtime before porting the rest

**The spike proves the BUILD side only.** Pkl → wire format is heavily tested; wire → DOM patches
is entirely unexercised. Presence-via-clauses, `Gone`/`Placed` driven by a presence projection,
aggregates counting present members, `limit`'s third member state, and the claim that a frame
costs O(Δ) are all DESIGNED and none are demonstrated.

So the first move is not "port the format". It is one thin VERTICAL slice — the simplest set (S1:
lights in a room, shown while on) carried all the way from Pkl through the real `Renderer` to real
SSE patches — chosen because it exercises the two runtime claims everything else rests on:

- a member appears and disappears as its clause's condition flips, via `Placed`/`Gone` rather than
  a hidden element (P7)
- a frame still costs the changed entities, not the candidate count

Everything else — aggregates, ordering, composite, limit — layers onto a path that slice has
already proven. Porting the whole format first and discovering the runtime cannot consume it
cleanly is the failure mode this ordering exists to avoid.

## The spike is scaffolding, not a parallel implementation

`src/test/pkl/dynamics/{wire,query,fixtures}.pkl` exist so the design can be argued against
running code. They are NOT a second implementation to maintain, and leaving them as one would be
this plan's own failure mode: two parallel definitions of the same thing, drifting.

So the machinery moves onto the real code progressively:

- **`query.pkl` moves to `lib/query.pkl`** and becomes the shipped authoring namespace, imported
  as `@fh-dashboard/query.pkl`. It already knows nothing about cards, so nothing else has to move
  with it.
- **`wire.pkl` dissolves.** Its node classes become two things: the Scala wire model in
  `model/Dashboard.scala`, and the `SetNode`/`SetMember`/`SetClause` classes in `components.pkl`
  (which owns the `LayoutNode` hierarchy — `DynamicGroup` sits beside them). Its predicate
  hierarchy dissolves entirely into the EXISTING `components.Predicate`, so there is one predicate
  language rather than two that agree. Its fold moves into `lib/query.pkl`, unchanged in shape.
- **Delete the spike at the END OF PHASE 3**, not before, and only after checking the shipped
  syntax against it — the scenarios it pins are the acceptance criteria for phases 2 and 3, and
  they are worth more as a checklist than as an early cleanup. Until then it is a proposal with a
  shelf life, not a second implementation to maintain: nothing imports it.
- **`fixtures.pkl` shrinks to the test home only.** Every scenario that can run against the real
  generated `@fh-home` dump should, so the tests exercise the actual typed entities and the actual
  capability data. Fixtures survive only for cases the test home cannot produce — a deliberately
  mixed-availability set, an entity with a capability nobody owns — and each one that stays should
  say why.

The end state is: no `dynamics/` directory, scenarios running against the real dump, and the
worked examples still asserting the same wire properties. Until then, treat anything under
`dynamics/` as a proposal with a shelf life.

# Closing out

## Settled as non-issues

Kept so they are not re-opened.

**Cross-set interleaving is not a limitation.** Parked earlier as "two sets are two DOM regions",
but `from` takes any `List<Candidate>`: concatenate the sources into ONE set and dispatch with
`.cases(...)`. The only thing genuinely impossible is interleaving two SEPARATELY AUTHORED sets in
different parts of the layout, which is obvious rather than a flaw.

**Unsatisfiable conjunctions are out of scope** (`state == on AND state == off`). An obvious
authoring bug when it happens, and the rabbit hole gets deep fast — partial orders, attribute
ranges, cross-entity terms. Not worth the machinery.

## Nothing is undesigned

Every design question raised in this plan is answered and spiked. What remains is implementation,
listed in "What we keep, delete, and fix" and "The spike is scaffolding". The one sequencing note:
`If` currently holds the only uses of the free predicate constructors, so migrate it before
deleting them from `components.pkl`.

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
- The emitted structure is a function of the AUTHORING ALONE — the same authoring yields the same
  shapes and vars regardless of how many entities share a shape (the type rule, not a diff).
- A member whose clauses point at different shapes carries each shape's own literals; the fallback
  never renders with blanks (S18–S22).
- Two sets over the same entity keep independent members, and every nested set is reachable by
  exactly one `(member, hole)` route — so `(set, entity)` is derivable without an id on the wire
  (C11–C13).
- Do not run the pkl suites concurrently with `dashboardServe` — shared dump cache, fake timeouts.

# Appendix

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

