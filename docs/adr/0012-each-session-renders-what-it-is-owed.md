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
thing that genuinely could not be rendered once (a `self` reading its own selection).
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

**One generation per SELECTION, not per node** — the two halves of `RenderInputs` do not
behave alike. Entity versions churn (every frame moves one, so the previous generation is
dead on arrival) and are replaced in place. The resolved selection does not: it ranges
over a bake group's members, a finite set the dashboard fixes. Bucketing on it is what
lets viewers on different tabs stop evicting each other while viewers behind each tab
still share, so the cost of a node is the number of distinct selections in flight — the
floor, since those viewers are owed different bytes. Measured before it: 3+3 viewers
across two tabs cost ~3.5 renders a frame against that floor of 2, trending toward one
render per viewer. `RenderCacheContentionSuite` holds the floor and the bound both.

**A straggler never displaces the current generation.** Sessions pull in parallel and read
the store when they get there, so they do not all render from one snapshot — and an
install refuses when the generation present is at or ahead of the caller's on every entity
it reads (`RenderInputs.isAtLeast`). The straggler renders and is served; the map keeps the
newer bytes. Without it, three sessions racing (newest, straggler, newest) cost three
renders, the third re-rendering what the first had already produced. Accepted cost: a
CLUSTER of stragglers at one older version stops sharing with itself. The newest snapshot
is what more arrivals are coming for, so it is what the single slot should hold.

**Variance stopped being a concept.** A node whose own markup reads its own selection —
`{{bakeIndex}}` in a `self`, the no-JS tab bar — is now just a node: the session renders
it with its own `uiState`, and the cache key already carries the selection that render
reads. No deferral, no memo, no per-variant log entry, no classification. This is the
part the shared pass had to invent machinery for, and the part that costs nothing once
the renderer runs where the viewer is.

**A fragment is a node's OWN html, never the composed html.** The composed form welds a
host to its children, which is what the self/mount split exists to prevent (ADR 0008).
Nodes with no markup of their own — a bare container, a dynamic group root, anything
whose children carry a mount — are neither log keys nor patch targets; their children are
addressable in their own right. It is also why a composed surface mount is the one thing
NOT cached: its bytes carry its children, so it has no sound key.

**Visibility is a chain, not a membership test.** A client can see a node when every user
surface above it is selected AND every state surface above it is active. `open` alone is
not that: a selection is reported for every bake group whether or not it is on screen.
The chain gates what is recorded at all, and prunes what a resume owes — including
mutations, since a `Gone`/`Placed` inside a surface a client does not have open would
patch an id its DOM lacks.

**A fill is one operation, whoever chose the member.** A tab switch, a popup open and an
`If` flip all evict a host's occupants, render the arriving surface, and overwrite the
mount. The model already said so: both kinds of member are surfaces with
`bakeInto`/`bakeAs`/`bakeIndex`, and `Renderer.mountId` derives a group's mount from its
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

`{{bakeIndex}}` in a `self` is the only way a selection is visible without scripts — a
tab click is then an `<a href="?ui.<host>=N">` and the answer is a fresh document.
Supporting it used to cost four concepts and a branch in the diff path. It now costs
nothing: the renderer already takes a `uiState`, and the cache key already includes what
the render read. **The `validate` rule that would reject `{{bakeIndex}}` in a `self` is
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

- **A hollow mount plus a per-connection fill.** The first attempt at serving viewers on
  different tabs from one render: insert the branch with its mount EMPTY, then let each
  connection fill it. Two DOM updates for one change, and a rendering "for nobody" that
  leaked a blank tab index into live markup — a mount carries client-dependent
  ATTRIBUTES, not merely children.
- **Baking whatever the connected clients agree on.** Would have removed the hollow mount
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
  nobody will ask for. Bucketing on the SELECTION (`RenderInputs.vars`) fixes the same
  problem exactly, because that is the only half of the key that differs between two
  viewers looking at one snapshot — and it does not churn, so a node holds one live
  generation per member of its bake group and no dead ones.
- **Rotating the cache on a renderer swap** instead of naming the renderer in each entry.
  Leaves a window where a pull that read the previous renderer writes its bytes into the
  fresh map — the same bug with a smaller target.

## Open

**Should a `self` splice children at all?** Today it may: a tab bar's buttons are the
card's own chrome, generated by the card rather than nested by an author, so no mount can
appear among them. The alternative is that everything a card holds goes through a mount,
which would make the rule unnecessary rather than merely satisfied. What would decide it:
whether any card ever wants AUTHOR-supplied children inside a `self`. Until then the rule
is checked, not trusted.
