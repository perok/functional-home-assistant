# ADR 0012 — Each session renders what it is owed

- **Status:** Accepted
- **Date:** 2026-08-04
- **Scope:** `modules/fh-datastar-view` (the SSE runtime)

## Context

ADR 0002 split live rendering in two: nodes whose HTML was a pure function of entity
state were rendered once per slug, and anything that could differ per client was
rendered again per connection. That split was a cost model, and it bought a duplicated
pipeline — two selection passes, two caches, and a class of bug where a fragment reached
one and not the other.

The first attempt to close it kept the shared pass and made a *tag* decide who saw each
patch: one render per slug, addressed per client, with a "variant" dimension for the one
thing that genuinely could not be rendered once (a card reading its own selection).
That worked, and it cost a second vocabulary — deferred renders, memoised verdicts,
per-variant log entries — to describe what one sentence describes instead.

## The decision

**The publisher records what moved; each session renders what it is owed.** A state
change is planned and written to a per-slug changelog once, with nothing rendered and
nothing pushed. Each connection wakes on a doorbell and runs `Patches.resume` from its
own position, against its own `holds` and its own open surfaces. A live tick and a
reconnect are the same operation with a different cursor (ADR 0011).

So there is no audience tag on a patch and no per-client filter at the wire edge: a
patch exists only because the session that will send it asked for it.

**"Does this client already have these bytes?" is a per-session question, and only
that.** `Session.holds` is a node-to-digest map seeded by the DOCUMENT that created the
session — the page render is the first and largest thing that puts fragments in this
client's DOM, and the only place that knows what they were. It is written where bytes
are SENT to this client, never from what another client was told and never from what a
shared structure believes. The changelog holds no content at all: node to the version it
last moved at, plus membership mutations.

**N viewers still cost one render, via a cache rather than a shared pass.** `RenderCache`
is per slug, single-flight (`MapRef` of `Deferred`), keyed by node id and holding the
renderer that produced it plus what that render READ (`Renderer.renderInputs`). N sessions
woken by one ring of the doorbell render each node once between them. The renderer is in
the key because a dashboard edit changes the markup while the entity versions it reads
stay exactly where they were.

**One generation per node.** `RenderInputs` is entity versions and nothing else, and those
CHURN — every frame moves one, so the generation for the previous version is dead the
moment it is replaced.

Not every entity the node READS, though: only those whose movement can change its BYTES
(`LayoutNode.Component.liveEntitiesAsBytes`). A slot that travels as a signal is absent from
the patch form entirely (ADR 0017), so its value moving cannot move these bytes, and keying
on it would throw away a generation whose re-render is identical. The reverse index still
wants the wider list — a signal has to make its node a candidate or no frame is ever computed
for it — so the two lists are both real and neither derives the other. Getting them the wrong
way round fails asymmetrically, and only one way is loud: the wide list in the key wastes a
render, the narrow list in the reverse index silently stops signal frames.

**The narrowing is per ENTITY, and that is not far enough.** The key holds
`contentVersion`, which `StateStore.update` stamps whenever an entity's content moved *at
all* — a brightness change included. So the exclusion above only bites where an entity
reaches a node **exclusively** through signal slots. One byte-reading slot re-admits it, and
from then on every signal-only change to that entity moves the key, misses the cache, and
re-renders the node to discover its bytes are identical.

The shipped `entityCard` has exactly one such slot: the name, which reads `friendly_name`.
Measured, one client and a twenty-entity tick (`RenderBench.resumeSignals` against
`resumeSignalsPure`, the same dashboard with the name held as a literal):

| | us/op | B/op |
|---|---:|---:|
| shipped card — name reads `friendly_name` | 261.7 | 442,296 |
| same card, name as a literal | 101.4 | 138,575 |

**2.6x the time and 3.2x the bytes, for one slot.** That is the largest single cost on the
live path — an order of magnitude more than encoding the frame.

**Fixed** (`Renderer.byteSlotValues` + a fourth branch in `RenderCache.apply`): not by predicting
the inputs more finely but by **comparing the resolved byte-slot VALUES** before rendering — identical values mean identical bytes, so the entry's bytes are
reused and re-stamped without mustache, wrapper or digest. It needs no static analysis, so CEL
and `Transform.Simple` go through the same path, and on the shipped card there is exactly one
byte slot to resolve (the name, an `AttrOrId` off ADR 0028's fast tier) — the signal slots are
evaluated on a signals tick regardless. Note the values must NOT become the key:
`RenderInputs.isAtLeast` is a partial order over versions and is what stops a straggler
displacing fresher bytes, so this is a pre-check that skips the render, not a new key.
`resumeSignals` went 261.7 µs / 442 kB to 101.7 µs / 151 kB, landing on `resumeSignalsPure`'s
105.9 µs — the shipped card now costs what a card the key already protected costs. The saving is
one render per tick per SLUG, not per client: the clients behind the first were already sharing its
render. `RenderBench`'s `resumeSignals` / `resumeSignalsPure` pair is where those numbers come from.

The cache used to hold one generation per SELECTION as well, because a node could be both cached and the
owner of a bake group: its own bytes then carried the viewer's chosen tab, so two viewers
on two tabs were owed different bytes for one node and evicted each other every frame.
That shape no longer exists. A bake owner holds its content in REGIONS, which makes it
structure, and structure is never a patch target and so never cached — the live part is an
ordinary leaf beside it, whose bytes mention no selection at all.

So the contention did not move, it went: `RenderCacheContentionSuite` measures the same two
viewers on two tabs at ONE render a frame where the old shape needed two, and the
structural owner at none.

**A straggler never displaces the current generation.** Sessions pull in parallel and read
the store when they get there, so they do not all render from one snapshot — and an
install refuses when the generation present is at or ahead of the caller's on every entity
that can change its bytes (`RenderInputs.isAtLeast`). The straggler renders and is served;
the map keeps the newer bytes. Without it, three sessions racing (newest, straggler, newest) cost three
renders, the third re-rendering what the first had already produced. Accepted cost: a
CLUSTER of stragglers at one older version stops sharing with itself. The newest snapshot
is what more arrivals are coming for, so it is what the single slot should hold.

**Variance stopped being a concept.** A node whose own markup reads its own selection —
`{{bakeIndex}}` in a tab bar, the no-JS selection display — is now just a node: the session renders
it with its own `uiState`, and the key needs no selection in it — such a node holds regions,
which makes it structure, and structure is never cached. No deferral, no memo, no per-variant
log entry, no classification. This is the
part the shared pass had to invent machinery for, and the part that costs nothing once
the renderer runs where the viewer is.

**A fragment is a node's OWN html, never the composed html.** The composed form welds a
host to its children, which is what the leaf/structure split exists to prevent (ADR 0008).
STRUCTURAL nodes — any card holding regions, and a set root — are neither log keys nor
patch targets; what they hold is addressable in its own right. It is also why structure is
the one thing NOT cached: its bytes carry its children, so it has no sound key.

A structural node may still carry SIGNAL slots, and that is not an exception to the
above: a signal is seeded on the node's `.fh-cell` wrapper and updated by a frame
addressed by name, so it never becomes bytes in the element and never needs the node
patched (ADR 0017).

**Visibility is a chain, not a membership test.** A client can see a node when every user
surface above it is selected AND every state surface above it is active. `open` alone is
not that: a selection is reported for every bake group whether or not it is on screen.
The chain gates what is recorded at all, and prunes what a resume owes — including
mutations, since a `Gone`/`Placed` inside a surface a client does not have open would
patch an id its DOM lacks.

**A fill is one operation, whoever chose the member.** A tab switch, a popup open and an
`If` flip all evict a host's occupants, render the arriving surface, and overwrite the
host. The model already said so: both kinds of member are surfaces with
`bakeInto`/`bakeAs`/`bakeIndex`, and `Renderer.hostId` derives a group's host from its
members' `Surface.hostId`. What differs is the SELECTOR (a client's `ui_<gid>` signal vs
a condition over entity state) and where the claim lands: a flip records a `Mutation`,
because it is server truth every client must be replayed; a tab switch claims into the
SESSION (`Patches.hostFill`), because one client switching a tab says nothing about
anyone else's DOM.

## Consequences

- **A frame costs one selection pass per slug, however many viewers it has**, and the
  renders it implies are shared through the cache rather than through a shared pass. Two
  viewers on different tabs of one node cost two renders, which is simply what is true.
- **The two mechanisms are gone**: no deferred render forced by the first connection to
  ask, no memoised verdict shared between connections holding the same selection. Both
  existed to make a shared pass survive per-client truth.
- **Patch shape may legitimately differ between clients**, and that is the point: what a
  client is owed is computed against what it has. The wire is unchanged for any single
  client, which is how the flip was verified (ADR 0009's suites over emitted SSE).
- **The popup is not a special channel**: it is `ui_<hostId>`, set by its own taps like
  any tab (ADR 0005).
- **A slug nobody is watching records nothing.** The gate is the session lookup the
  recorder already does for visibility; a client returning across such a stretch is
  repainted (`FragmentLog.reaches`).

## Why the no-JS path is worth its cost

`{{bakeIndex}}` is the only way a selection is visible without scripts — a
tab click is then an `<a href="?ui.<host>=N">` and the answer is a fresh document.
Supporting it used to cost four concepts and a branch in the diff path. It now costs
nothing: the renderer already takes a `uiState`, and a node whose markup reads the selection
is structure, which is never cached — so no key has to carry one. **The `validate` rule that would reject `{{bakeIndex}}` on a structural card is
still the thing to restore if the no-JS goal is ever dropped** — but there is no longer a
pipeline to simplify by dropping it.

## What it costs, honestly

This is a **correctness** change, not a performance fix, and it should not be argued for as one —
the measurements taken while designing it found no CPU or RAM problem to solve. The ledger:

| | Before | After |
|---|---|---|
| a slug nobody is watching | rendered every affected node, every frame | records a few map writes, or nothing at all |
| a slug with N viewers | one shared render pass + one shared log write | one record pass + N pulls, whose RENDERS are shared through the cache |
| sharing a render | within one batch | across batches — a laggard shares with a current session |
| knowing a render is unnecessary | render, then compare digests | unchanged: the digest still requires the bytes |

So the shape of the cost moves from *constant regardless of viewers* to *proportional to viewers*
in the pull, while the renders behind those pulls stay shared. For a household that is a clear win,
because the idle case dominates; for fifty simultaneous tabs it would be a loss, and that is worth
stating rather than discovering. The one thing that does NOT improve is suppression: only the
client's own `holds` can say whether bytes are worth sending, and there is no digest without the
bytes.

RAM: the changelog SHRINKS (it dropped digests, and then content entirely), `holds` duplicates per
session (tens of KB, now O(sessions), though immutable `Map`s applying the same updates from a
common ancestor share most of their structure), and the render cache is the new cost — bounded at
one generation per node per SELECTION, which is why it needs no timer.

The complexity is close to a wash in volume. What changes is its KIND: shared-state reasoning, where
a wrong answer is silent and affects everyone, became per-session bookkeeping, which is more code
but fails one client at a time and is visible when it does.

## Deferred — if viewer count ever matters

The per-session cost is one pull each per frame. Nothing here is worth building now — N is tabs in a
house — but the design should not paint itself out of it.

**The framing that makes it a choice rather than a tradeoff:** the unit of sharing is the
EQUIVALENCE CLASS of sessions that would receive the same bytes — same position, same visible
surfaces, same selections. The design this ADR replaced assumed exactly one class (everybody gets
one shared render); this one assumes N (one per session). Neither is right in general — a
household's tabs mostly fall into one or two classes — so **the class is the tunable**, and the two
designs are endpoints of one axis rather than rivals.

Cheapest first:

1. **Memoise the pull by class.** Key a per-frame memo on `(fromVersion, visibleSurfaces,
   selections)` and let sessions in the same class share one computed changeset. Four tabs on one
   dashboard with the same tab selected collapse to one pass. Costs nothing until used.
2. **Hoist the shared part of the pull.** The recorder already knows the changelog moved; it could
   compute what does not depend on a session — the slice since the OLDEST live position, the
   latest-wins collapse — once, leaving sessions only the cheap per-session filtering. Shares the
   work without needing classes at all.
3. **Baseline plus overlay**, if `holds` memory is ever the problem. Keep one per-slug map as "what
   a typical client has" and give each session only its DIVERGENCE from it — usually empty, and
   non-empty exactly where a shared answer would have been wrong. Recovers O(1)-in-viewers memory
   while keeping the per-client correctness that motivates this ADR.

## Deferred — what `conn` becomes when there is auth

`conn` is a session identifier with a real lifetime now, which is the shape auth wants: the session
belongs to a PRINCIPAL, and `conn` becomes a per-session token scoped to it.

What must not happen is `conn` becoming the user id. A session owns `holds` — a record of what ONE
DOM contains — and a user has many tabs. Give them one identity and either the displacement rule
throws them out of their own other tab, or they share a `holds` map that describes two different
DOMs, which is the silent-staleness failure this whole design exists to remove. The displacement
rule stands on its own for the same reason: two live streams on one session means two writers on
one `holds`.

The scopes are genuinely different, and that is the thing to hold on to: `holds` and `position` are
per-DOM and can never be keyed coarser than a tab, where the open set and ui-state are per-VIEWER
and could reasonably follow a user across tabs or devices. Merging them would fake one with the
other.

## Rejected along the way (still guarding the design)

- **A hollow host plus a per-connection fill.** The first attempt at serving viewers on
  different tabs from one render: insert the branch with its baked region EMPTY, then let
  each connection fill it. Two DOM updates for one change, and a rendering "for nobody"
  that leaked a blank tab index into live markup — a host carries client-dependent
  ATTRIBUTES, not merely children.
- **Baking whatever the connected clients agree on.** Would have removed the hollow host
  for the common case by reading the union of open sets. It makes rendered bytes depend
  on the audience — one dashboard, one state, different HTML depending on who is
  watching.
- **A tag on every patch, and a variant dimension in the log.** The previous decision
  here. It is correct and it is more machinery than the problem has: once the decision
  "is this worth sending?" is asked per session, the tag has nobody to hide a patch from
  and the variant has nobody to disagree with.
- **Keying the log by `(NodeId, variant)`.** Puts a non-node-id into the ledger and into
  `since`'s results. The variance belonged inside the entry — and then the entry stopped
  having content at all.
- **Keying the render cache by `(nodeId, inputs)` in full.** Unbounded: the recording pass
  selects exactly the nodes binding an entity that just moved, so every batch mints new
  ENTITY VERSIONS and old ones are never asked for again. What IS kept is the other half
  of that key — see below.
- **Keeping N generations per node** to stop two viewers evicting each other. It fixes the
  right problem with the wrong bound: N-1 of those generations are stale entity versions
  nobody will ask for. This was answered by bucketing on the SELECTION
  (`RenderInputs.vars`) — and that half is now gone too: a bake owner holds its content in
  REGIONS, which makes it structure, which is never cached, so no node's bytes mention a
  selection any more. `RenderInputs` is entity versions and nothing else, and the cache
  holds ONE generation per node. `RenderCacheContentionSuite` measures two viewers on two
  tabs at one render a frame where the old shape needed two.
- **Rotating the cache on a renderer swap** instead of naming the renderer in each entry.
  Leaves a window where a pull that read the previous renderer writes its bytes into the
  fresh map — the same bug with a smaller target.

## The rule is the card's shape

A card is a LEAF (no regions — its whole template is what a patch renders) or STRUCTURE
(regions — it holds content it does not own), and `CardDef.isStructure` is the whole
question. Nothing walks a template looking for holes, and nothing walks the tree asking
what a descendant carries.

That is what makes the guarantee unrepresentable rather than policed: every hole in a
template is filled by a NODE, each with an id and a patch of its own, so a patch at one
node cannot reach another's content. A tab bar's buttons are ordinary children in a
region; a slider's head is a card of its own beside the rows.

**Structure is never a patch target**, so a live BYTES slot on a structural card is a
build error — unless the value travels as a SIGNAL, which never becomes bytes in the
element and so needs no patch (ADR 0017).
