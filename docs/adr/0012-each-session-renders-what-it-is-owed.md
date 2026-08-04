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
is per slug, single-flight (`MapRef` of `Deferred`), keyed by node id and holding ONE
generation — the renderer that produced it plus what that render READ
(`Renderer.renderInputs`). N sessions woken by one ring of the doorbell render each node
once between them. The renderer is in the key because a dashboard edit changes the markup
while the entity versions it reads stay exactly where they were.

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
- **Keying the render cache by `(nodeId, inputs)`.** Unbounded: the recording pass selects
  exactly the nodes binding an entity that just moved, so every batch mints new keys and
  old ones are never asked for again. One generation per node is bounded by the
  dashboard, needs no timer and no sweep.
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
