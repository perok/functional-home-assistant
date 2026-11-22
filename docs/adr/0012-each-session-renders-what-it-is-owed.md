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
that.** `Session.holds` maps a node to the byte-slot values this client's bytes for it were
rendered from (below), seeded by the DOCUMENT that created the
session — the page render is the first and largest thing that puts fragments in this
client's DOM, and the only place that knows what they were. It is written where bytes
are SENT to this client, never from what another client was told and never from what a
shared structure believes. The changelog holds no content at all: an id to the version it
last moved at, which says where to look (ADR 0011).

A set's or a pick's host also keeps what it shows: the `Ix` of each root it holds, in the
order sent (`Held.shows`, one int per member, a case and a clause each their own `Ix`). The
structural patches carry what that list needs: `Patch.Fill` (inner, sets it), `Place`
(before an anchor, or last; an anchor the list lacks makes it unknown) and `Leave` (drops the
key's subtree and takes it out), so one value gives both the bytes and the change to
`holds`. A pull plans each host it looks at against that list (`Patches.planHost`): the keys
decided now, a fill where no list is held, else leaves and places. A session minted for a
forgotten page has no
lists, so its hosts are filled the first time a pull looks at them.

**N viewers still cost one render, via a cache rather than a shared pass.** `RenderCache`
is per slug, keyed by the node's `Ix` and holding the renderer that produced it plus the byte-slot
values it was rendered from. N sessions woken by one ring of the doorbell render each node once between
them. The renderer is in the key because a dashboard edit changes the markup while the values stay
exactly where they were.

**A memo, not single-flight.** A miss renders and installs; sessions that miss together each
render. Single-flight (a `MapRef` of `Deferred`s, the render uncancelable) cost about 1.5 kB per
missed node, against about 3.2 kB for a second render, and sessions woken by one ring rarely miss
together: `RenderCacheContentionSuite` still counts one render a frame across viewers. Escape
analysis off, a byte tick (`resumeMorphs`) went 246,472 -> 216,727 B/op for one client and
1,120,489 -> 1,055,833 for ten (`resumeMorphsFanout`); no cache at all was 205,797 and
1,628,427. In time (escape analysis on), 114 -> 86 µs and 420 -> 359 µs. A race costs a render,
never wrong bytes, and a failed render installs nothing.

**The key is the values.** A node's patch form is a function of its renderer, its id and the
resolved values of its BYTE slots (`Renderer.byteSlotValues`, the value after the slot's whole
chain: read, CEL, default, formatting). Everything else it reads is fixed per renderer (template,
constants, `dashboardSlug`), and a fetch answer or a node variable reaches the bytes only through a
slot (ADR 0031, ADR 0033). Signal slots are left out: the patch form does not carry their values
(ADR 0017). Every clause of a candidate mints the same ids, and the clause picks the card and the
host id, so both `holds` and the cache key by the node's `Ix`, one per clause, not by its id. The live
path renders from the values it compared: `Renderer.renderPatch` paints the patch form from the
`ByteValues` alone, so a miss resolves nothing twice and the bytes are a function of the key by
construction. So one value answers both questions:

- **A session asks its own `holds` first.** `Patches.morph` returns nothing when the live values
  equal the held ones, before any render or cache lookup; otherwise it sends, and records the new
  values. The page walk records each node's values off the same paint (`ByteValuesPropertySuite`
  pins the walk and the live path to the same values, and equal values to equal bytes; the
  timeline pairs in `ByteSlotKeySuite` pin it through a real store).
- **The cache is keyed by the same map.** Viewers on one value share the entry; a viewer on
  another (a different chart window, a variable) replaces it in place, one entry per node however
  many values are posted.

**`holds` never outlives its renderer.** A session's `holds` is stamped with what painted it
(`SessionState.painter`: the renderer's generation, which carries its head and style hashes and
its popup spellings), and every step compares the stamp first (`Server.swapAnswer`): a pull, a
press, the opening. Under another renderer the step answers as a swap — a reload when the head's
unpatchable part moved or the page goes to or from the error page, else a repaint that replaces
`holds` — and never diffs, so values held under one template are never taken for another's
bytes (`ResumeSuite`: an edit that moves only markup reaches the client; a pull or a press between
the swap and the repaint gets the repaint, and the press is refused rather than run, ADR 0024). The stamp is the swap's one mechanism: `install` rings
the doorbell at the same version so an idle session steps, and no separate watcher repaints. It
keeps what the swap reads of the renderer it replaces, since the new one cannot read a popup it
lacks, and nothing that would pin that dashboard. A session told to reload sends nothing more
until the next swap.

Compared as `SlotValue`s, not their text, since a section reads `false` and `"false"`
differently. Unequal values usually mean new bytes but need not: a slot read only as a section's
test renders the same for any non-empty value. Then the session sends one morph to the bytes the
client already has (the DOM does not change) and records the new values, so it happens once
(`ByteSlotKeySuite`). Comparing digests of the bytes would suppress that frame, at the price of
rendering and hashing every node whose values moved.

Measured against the previous key (entity and fetch versions, ordered against stragglers, with a
separate value pre-check, and a 128-bit digest of the patch bytes in `holds`), escape analysis off,
B/op, then time (escape analysis on, two forks):

| Bench | versions + digests | values | µs before -> after |
|---|---:|---:|---|
| `page` | 1,677,274 | 1,398,282 | 605 -> 538 |
| `pageWalkStream` | 1,429,203 | 1,050,098 | 829 -> 607 |
| `pageSet` | 1,659,010 | 1,321,522 | 652 -> 576 |
| `resumeMorphs` (1 client) | 202,327 | 170,416 | 94 -> 87 |
| `resumeMorphsFanout` (10) | 777,625 | 650,745 | 277 -> 228 |
| `resumeMembers` | 231,354 | 135,419 | 97 -> 59 |

The page walk no longer hashes each node or renders a node with a signal slot a second time in
patch form; the live path no longer builds a version key or hashes the bytes it sends.

The cache used to hold one generation per SELECTION as well, because a node could be both cached and the
host of a choice: its own bytes then carried the viewer's chosen tab, so two viewers
on two tabs were owed different bytes for one node and evicted each other every frame.
That shape no longer exists. A choice fills a nodes slot, which makes its host
structure, and structure is never a patch target and so never cached — the live part is an
ordinary leaf beside it, whose bytes mention no selection at all.

So the contention did not move, it went: `RenderCacheContentionSuite` measures the same two
viewers on two tabs at ONE render a frame where the old shape needed two, and the
structural owner at none.

**A straggler needs no rule.** Sessions pull in parallel and read the store when they get
there, so they do not all render from one snapshot. Bytes are a function of their key, so a
straggler installing its own values costs the next session on the newer values a render, never
wrong bytes (`RenderCacheContentionSuite`).

**Variance stopped being a concept.** A node showing a viewer's choice is now just a node: the
session renders it with its own `Selections`, and the key needs no selection in it — the choice
fills a nodes slot, which makes the node structure, and structure is never cached; its own
markup shows the choice only through a cell class (below), written for the viewer in the
document form. No deferral, no memo, no per-variant log entry, no classification. This is the
part the shared pass had to invent machinery for, and the part that costs nothing once
the renderer runs where the viewer is.

**A fragment is a node's OWN html, never the composed html.** The composed form welds a
host to its children, which is what the leaf/structure split exists to prevent (ADR 0008).
STRUCTURAL nodes — any card holding regions, a set's root among them — are neither log keys nor
patch targets; what they hold is addressable in its own right. It is also why structure is
the one thing NOT cached: its bytes carry its children, so it has no sound key.

A structural node may still carry SIGNAL slots, and that is not an exception to the
above: a signal is seeded on the node's root (its `.fh-cell`) and updated by a frame
addressed by name, so it never becomes bytes in the element and never needs the node
patched (ADR 0017).

**Visibility is a chain, not a membership test.** A client can see a node when every user
surface above it is selected AND every state surface above it is active. `open` alone is
not that: a selection is reported for every pick over a variable whether or not it is on screen.
The chain gates what is recorded at all, and prunes what a resume owes — including
the hosts it plans, since a leave or a place inside a surface a client does not have open would
patch an id its DOM lacks.

**A host is planned the same way, whoever chose the case.** A tab switch and an `If` flip
both decide a host's keys and plan them against the list the session holds
(`Patches.planHost`). What differs is who decides (a viewer's node variable vs `by` over
entity state, ADR 0007) and what reaches the planner: a flip logs its host and arriving
case, because it is server truth every client must be planned against, and the pull finds
them; a variable write plans the hosts its variable selects directly
(`Patches.written`) and logs nothing, because one client switching a tab says nothing
about anyone else's DOM. The popup host is still the exception until R5: `#popups` holds
no list, so an open fills it whole and evicts every popup's claims (`Patches.popupFill`).

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
- **The popup is not a special channel**: it is `ui_<hostId>`, committed by its own
  swaps as a tab's variable is by its write (ADR 0005).
- **A slug nobody is watching records nothing.** The gate is the session lookup the
  recorder already does for visibility; a client returning across such a stretch is
  repainted (`FragmentLog.reaches`).

## Why the no-JS path is worth its cost

A selection is visible without scripts in the case the page shows, and in a cell class lit by
a node variable (`cellClassWhen(cls, v.valueIs(x))`, ADR 0033), which is how a tab bar's
buttons light the open tab in the first HTML — a tab click is then an
`<a href="?v.<host>.tab=N">` and the answer is a fresh document. Supporting it used to cost four
concepts and a branch in the diff path. It now costs nothing: the renderer already takes the
viewer's `Selections`, a node whose markup reads the selection is structure, which is never
cached, and such a class is written for the viewer in the document form only, its binding
reading the variable's own signals, so a leaf's patch is the same for every viewer and no key
has to carry one. There is no longer a pipeline to simplify by dropping the no-JS goal.

## What it costs, honestly

This is a **correctness** change, not a performance fix, and it should not be argued for as one —
the measurements taken while designing it found no CPU or RAM problem to solve. The ledger:

| | Before | After |
|---|---|---|
| a slug nobody is watching | rendered every affected node, every frame | records a few map writes, or nothing at all |
| a slug with N viewers | one shared render pass + one shared log write | one record pass + N pulls, whose RENDERS are shared through the cache |
| sharing a render | within one batch | across batches — a laggard shares with a current session |
| knowing a render is unnecessary | render, then compare digests | compare the byte-slot values; no render |

So the shape of the cost moves from *constant regardless of viewers* to *proportional to viewers*
in the pull, while the renders behind those pulls stay shared. For a household that is a clear win,
because the idle case dominates; for fifty simultaneous tabs it would be a loss, and that is worth
stating rather than discovering. Only the client's own `holds` can say whether bytes are worth
sending, and it answers from values, without the bytes.

RAM: the changelog SHRINKS (it dropped digests, and then content entirely), `holds` duplicates per
session (tens of KB, now O(sessions), though immutable `Map`s applying the same updates from a
common ancestor share most of their structure), and the render cache is the new cost — bounded at
one entry per node, which is why it needs no timer.

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
  different tabs from one render: insert the branch with its tab panel EMPTY, then let
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
- **Keying the render cache by `(nodeId, values)` in full.** Unbounded: every value a node
  ever showed would keep an entry nobody asks for again. One entry per node, replaced in place.
- **Entity and fetch versions as the key, and a digest of the bytes in `holds`.** The previous
  design. A version moves on any change to its entity, a signal-only one included, so it needed
  a value pre-check beside it, a partial order so a straggler would not displace fresher bytes,
  and a hash of every node's bytes on every page and every send. The values answer all three
  (measured above).
- **Keeping N generations per node** to stop two viewers evicting each other. It fixes the
  right problem with the wrong bound: N-1 of those generations are stale values nobody will
  ask for. This was answered by bucketing on the SELECTION — and that half is now
  gone too: a choice's host holds its cases in a nodes slot, which makes it structure, which is
  never cached, so no node's bytes mention a selection any more, and the cache holds ONE generation
  per node. `RenderCacheContentionSuite` measures two viewers on two tabs at one render a frame
  where the old shape needed two. A slot reading a variable's value does not bring the buckets
  back: the value is in the key, so two values over one node replace each other's entry and each
  render, which costs only on ticks where that node's byte-slot values moved.
- **Rotating the cache on a renderer swap** instead of naming the renderer in each entry.
  Leaves a window where a pull that read the previous renderer writes its bytes into the
  fresh map — the same bug with a smaller target. So an entry names its renderer, and the swap
  ALSO empties the cache (`LiveSlug.install`): naming alone left every entry the new dashboard
  never renders again (a removed or renumbered node) holding the old one. The name is
  `Renderer.generation`, not the renderer, so a straggler's entry after the swap holds only its
  bytes.

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
