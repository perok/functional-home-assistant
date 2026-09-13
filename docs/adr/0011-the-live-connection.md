# ADR 0011 — The live connection: resume, health, and what may never be dropped

- **Status:** Accepted
- **Date:** 2026-07-27
- **Scope:** `modules/fh-datastar-view` (the SSE runtime) and `modules/ha-api`
  (the HA WebSocket transport)

## Context

Two connections carry the dashboard, and they fail differently:

- **browser ↔ server**, one SSE stream per open page, pushing HTML fragments;
- **server ↔ Home Assistant**, one WebSocket, feeding `StateStore`.

Every SSE *(re)connect* used to push the dashboard's whole body as one
`datastar-patch-elements`. That cost lands on the case that happens most, because
Datastar's `openWhenHidden` defaults to `false` for GET and its handler aborts the
fetch on `visibilitychange`: **backgrounding a phone tab closes the stream and
returning reopens it**. Every glance at the dashboard was a full-body render,
transfer and morph, even when nothing had changed in the meantime.

Re-diffing after a reconnect cannot help, and the reason was the whole design at the
time. The live path narrowed work twice — a reverse index picked the nodes an entity
touches, then a cache suppressed byte-identical re-renders — but that cache was a
**broadcast log, not a per-client mirror**: one render per slug, diffed against one
cache shared by everyone viewing it. Changes a backgrounded phone missed were still
absorbed by it — they *were* broadcast, to whoever was listening. So from the cache's
point of view that DOM was already current, and what this client had missed existed
nowhere on the server.

Everything here is about supplying that missing information, and about the two
connections' health being observable without either end guessing.

**What has since changed, and what has not.** The rendering pass is no longer shared:
each session pulls what it is owed and renders it (ADR 0012), so the server does now
keep a per-client record — `Session.holds`, what this client's DOM contains. That
record is an OPTIMISATION and never the mechanism: the client's cursor remains the
truth about what it holds, everything below still works with `holds` empty, and losing
a session costs bytes rather than correctness. The design that follows is unchanged by
it; what changed is that the question "does this client already have these bytes?" now
has somewhere exact to be asked.

Be honest about the size of the win: on a fast LAN it is invisible. It is a
mobile/slow-link optimisation plus a DOM-stability improvement, not a bug fix.

## The design

Three statements everything below rests on. The first is structural and belongs
to ADR 0008; the other two are this ADR's.

> **1. A node's patch carries its own rendering and never the contents of a
> *region*.** A region is filled by other nodes, each addressable on its own.

> **2. Everything that changes a client's DOM goes through the log.** Every path:
> the live diff, a host fill, a flip, a resume, a repaint.

> **3. The log records WHEN each node last changed, never WHAT it contains.**
> Content is always rendered now.

(1) is what makes a container patchable at all: a tabs host can tick its header
without touching the panel, because the fragment structurally cannot contain it.
(2) is the safety property — a path that puts HTML on screen without telling the
log leaves a stale baseline, and the next genuine change is silently suppressed
against it. (3) is what makes (2) cheap enough to honour everywhere, since a path
unsure of its own bytes can drop the entry instead of recording a wrong one.

The standing constraint over all three: **a complete update is a fallback, never
a design choice.** Deltas first; wholesale re-send only where the delta genuinely
cannot be computed, and then at the smallest granularity that works — refill one
group, not the body.

### The cursor: four client-carried values, under one `_` namespace

They live nested under `_cursor`, and the prefix is load-bearing rather than cosmetic:
Datastar's default request filter is `exclude: /(^|\.)_/`, so nesting them there keeps
them off every request the page makes and the SSE GET asks for them back with an explicit
`filterSignals` include. Before that they were four top-level signals, which meant every
action POST carried a cursor for a server that never looks at one.

Two things about that, both silent when wrong and both read off the pinned bundle rather
than the docs. `include` and `exclude` are **ANDed**, so an include alone does not defeat
the default — the SSE options neutralise the exclude with `(?!)`, which makes that include
the WHOLE of what a reconnect can tell the server. And nested signals **merge** rather than
replace (`Nt` keeps an existing object and recurses per key), which is what lets a live
batch patch `{_cursor:{storeVersion}}` without wiping the three fields it does not mention.

`Server.cursorOf` reads all four as ONE decode, so a store that arrives short a field is a
`Left` rather than an absence — `cursorAnomaly` reports it instead of falling silently back
to the document's frozen query params, which serves correct output forever at a cost
nothing reveals.

The client carries what it holds; the server keeps one versioned log per slug, shared
by every client. A session's own record (`holds`, `position`) sits alongside it and can
be discarded at any time — see the context note above.

| value | question it answers | on mismatch |
|---|---|---|
| `headHash` | does the browser's UNPATCHABLE `<head>` still match? | full page **reload** |
| `styleHash` | and the patchable rest of it (theme CSS, `<title>`)? | two **head patches** |
| `logId` | is this cursor even comparable? | body **repaint** |
| `storeVersion` | how far behind is this client — and did it APPLY what we last announced (`Session.told`)? | body **repaint** |
| *(server-side)* fragment log | what changed since — and is it still retained? | body **repaint** |

They ride the channel that already works: Datastar sends every non-`_`-prefixed
signal with each backend action, serialising them into a `datastar` query param on
GET. So the server pushes cursor + hashes alongside each patch batch and they come
back on the next connect for free — no SSE `id:`, no `last-event-id` handling. A
page reload correctly reads as a first connect (its body is server-rendered fresh),
and two tabs carry independent cursors.

None of the four may be `_`-prefixed, which is a deliberate exception worth the
comment at the emit site: `_` is exactly the convention for per-connection client
state (`_sse`, `_reload`, `_val_*`), and these *are* per-connection client state —
but the prefix is what excludes a signal from the URL, and riding the URL is their
entire purpose.

**`logId` is not optional.** A version number is meaningless outside the log that
issued it: a restarted server resets the counter, a renderer hot-swap mints a fresh
cache, and in both cases a client holding version 500 would be compared against a
log where 500 names nothing. The log carries a UUID minted with it and a cursor
quoting a different one is rejected outright rather than reasoned about. It also
subsumes "is this even the same dashboard", which is why `headHash` does *not* gate
the resume — a dashboard change arrives via a renderer swap, and a swap rotates the
log id, so the cursor is already rejected before any hash is consulted. `logId`
decides *is this comparable*; `headHash` decides *does the browser need a reload*.
Neither can answer the other's question, and `headHash` is stable across restarts
BY DESIGN — which is precisely why it cannot double as `logId`.

**`storeVersion` is a clock and nothing more:** a monotonic counter `StateStore`
owns, bumped once per applied batch that changed anything. Batch granularity is
structural, not hopeful — a coalesced HA frame arrives as one fs2 chunk and is
applied in one `ref.modify`, so one version covers one HA event-loop tick.

*Not* HA's `last_updated`, which was the first design and is wrong three ways:
removals have no timestamp (an `r` frame is a bare list of ids, so stamping with the
server clock puts two clocks in one ordering and skew silently drops deletions);
`StateStore.merge` can inherit a stale timestamp when a delta carries neither `lu`
nor `lc`, making a real change invisible to a timestamp cursor; and it overloads a
field that exists for the recency guard (`EntityState.stale`), which answers a
different question from "has this client seen it".

### The change ledger

The per-slug log carries a version, turning "what did we last broadcast" into
"when did each node last change":

```scala
enum MemberKey:                                    // what occupies a host, and how to render it
  case Entity(id: String)                          //   a dynamic group's member
  case Surface(id: String)                         //   a state group's branch

enum Mutation(val version: Long, val container: NodeId):  // last STRUCTURAL fact about a node
  case Gone(in: NodeId, at: Long)                         // its element was deleted
  case Placed(in: NodeId, member: MemberKey, at: Long)    // belongs at its current position

case class FragmentLog(
  id,
  fragments:    Map[NodeId, Long],                 // node -> the version it last moved at
  mutations:    Map[NodeId, Mutation],
  horizon:      Map[NodeId, Long],                 // per container: membership history is
                                                   //   complete only from here up
  completeFrom: Long                               // the whole log: complete only from here up
)
def since(v: Long): Resume                         // TOTAL — see "the horizon is per container"
def reaches(v: Long): Boolean                      // false across a stretch nobody watched
case class Resume(nodes: List[NodeId], moved: …, refill: List[NodeId])
```

**There is no content in the changelog at all** — not the bytes, not a digest of them.
`fragments` holds a version and nothing else, so the log answers only *when did this
node last move*. What a given client HOLDS is that client's own record (`Session.holds`,
ADR 0012), which is the only place that question can be answered exactly.

`fragments` answers *when did this node's content last change*; `mutations`
answers *where is this node* — the changes not expressible as a morph of an
element the client already has. A resume renders every node at `>= V` from the
CURRENT snapshot and morphs it, then applies every mutation at `>= V`.

**What is a log key.** A fragment is a node's **own** html — never the composed html that
includes its children. The composed form welds host to children and makes them inseparable, which
is the thing statement (1) exists to prevent, arriving through the log instead of through a patch.

For a LEAF the own html is the whole rendering — a leaf holds no regions, so there is
nothing else it could be. Two shapes have no own html and are therefore **neither log keys
nor morph targets**:

- **structure** (`Column`/`Row`/`Grid`/`If`/`Tabs`/`Slider` — any card declaring a region),
  whose element contains what it holds;
- a **candidate set root**, which composes its members and whose members are keyed
  individually.

Excluding them loses nothing, because what they hold is addressable in its own right. It is
also not a separate rule: `validate` rejects a live BYTES slot on structure *because it has
no patch target*.

The reason it matters is that the log is per SLUG. Rendering structure by id would render
its whole subtree, hosts included, so its bytes would depend on which member each
descendant host has selected — and whichever a shared structure recorded would be wrong for
somebody.

**A node whose own html differs between viewers needs no special treatment.** Since the
session renders what it is owed, it renders with its own `uiState` and records the digest
in its own `holds` — no variant key, no second entry, nothing shared to disagree about.
The rule that makes it safe is statement (1): a node's own rendering contains no region's
contents, its own or a child's.

**The log records WHEN, never WHAT.** Content is always rendered now. Three things
follow. The log cannot go stale against the renderer, because it stores nothing a
renderer swap could invalidate. The snapshot a resume renders from is by definition at
least as fresh as anything the log could have kept, so a client that missed five ticks
gets the fifth and not the first. And — the property worth the most — **a path unsure of
what it put in a client's DOM can simply drop the entry**, because an absent entry reads
as "unknown, send it". The worst outcome of dropping is a redundant re-send; the worst
outcome of a wrong entry is a suppressed change, which is silent and permanent.
Everything that mutates the DOM without knowing its own bytes exactly (a host fill, a
branch placement) uses that escape.

**The version and the digest answer different questions, and the split survived the flip
into the sessions.** A node's `fragments` version says *this node moved at V* — the
changelog's whole content, and what `since(cursor)` reads to decide what a returning
client is owed. A digest says *these are the bytes it has*, and lives per session
(`Session.holds`). A consequence worth stating because it looks like an optimisation and
is not: a node re-rendered to the same bytes must NOT have its version advanced. That
would make the version mean "when did we last look", `since` would over-report, and
returning clients would be sent morphs for nodes that never changed.

Digests are compared, never inspected, and are held as hex rather than
`Array[Byte]` — array equality is by reference, so the map would have quietly
never matched.

**`>=`, not `>`.** The cursor is pushed alongside a patch batch and one store
version can produce several batches (one `StateChange` per entity, each diffed
separately), so a client can hold V having seen only part of it. Re-sending the
whole of V is idempotent and cheap; missing half of it would be silent and
permanent. A DOCUMENT's cursor is the exception and needs `> V`: the page was
rendered from one snapshot, so it has all of V by construction, and `>= V` would
hand it back everything it already contains. The two are told apart by where the
cursor came from — signals mean a reconnect, plain query params a first load.

**The invariant that makes absence safe:** an entry's version means "this node
last changed at store version N", so a node with *no* entry has not changed since
the log was created — and every client's body, server-rendered from current state
at page load, already has it. Absence correctly reads as "you are up to date";
there is no bootstrapping hole.

**Three levels of collapse.** Per node (both maps are id-keyed, latest-wins, so a
thousand alternating ticks leave one entry). Across the two maps (a node with a
current mutation is emitted as that mutation and not *also* as a morph). And
across the tree — `coveredByMutation` drops anything a mutation or a refill is
already re-supplying wholesale.

> Level 3 is the one worth a warning, because getting it wrong is invisible. The
> predicate must be a STRICT ancestor test: a version that also matches the node
> itself makes every fragment suppress its own emission, so a resume silently
> sends nothing, with no error anywhere. There is a test named for that alone.
>
> It is keyed on MUTATIONS rather than on fragments, and that is not
> interchangeable. A fragment says the node's content changed; a mutation says
> its whole element is being re-supplied, which is the only thing that entitles
> the resume to drop what is under it.

What cannot collapse: a node that goes A→B→A across the absence yields a morph to
`A` byte-identical to what the client holds. Detecting that needs per-client DOM
knowledge — the thing this design refuses to keep — and idiomorph treats it as a
no-op.

> **FOLLOW-UP — why is structure a separate map at all?** `fragments` and
> `mutations` are both keyed by node id and together encode one state machine:
> a node is present with some content, or gone, or placed. The invariant that
> "gone" and "has content" cannot both hold is maintained BY HAND (`removed`
> drops the fragment), which is exactly the shape this design collapsed one
> level down when parallel `tombstones`/`arrivals` maps became a single
> `Mutation` sum. The same argument applies here and has not been examined.
>
> What keeps them apart today is pruning: `mutations` are dropped below the floor
> and `fragments` are not (they describe nodes that currently exist, so there is
> no history in them to drop), which a single map would need a per-case rule for.
> Whether that is worse than two maps with a hand-held invariant is the open
> question.

### Pruning: `fragments` self-limits, `mutations` does not

Keying by node id is what keeps the log small: a node has one latest content and one
latest structural fact however often it churned, so a hyperactive sensor cannot flood
it. For `fragments` that is sufficient — they describe nodes that currently EXIST,
and both `removed` and `invalidateWhere` drop entries.

`mutations` is the exception. A `Gone` for a member that left and never returned has
nothing to evict it, so the map accumulates one entry per entity that has EVER been a
member of any group — bounded by entity count rather than dashboard size, and growing
with elapsed time rather than complexity. A `dynamic` group over "every light that is
on" will, over a week, name every light in the house.

So mutations are **pruned below the floor** — the lowest `position` any live session
holds (`Sessions.floor`) — and the log carries a **`horizon`**: the oldest version
`mutations` is complete for, raised past everything dropped, **per container**, because
one group being pruned says nothing about any other. A cursor below a container's
horizon does not repaint the page; it puts that container in `Resume.refill` and the
client is sent that ONE host's contents wholesale.

The floor is exact where the rule it replaced was a guess. A mutation below it cannot
appear in any resume any session will ever run, so keeping it buys nothing; a mutation
above it may still be owed. The rule it replaced was a wall clock (keep an hour), which
answered "how long might a client be away" with a number rather than with the answer.
Dropping a mutation still raises the horizon, because a CLIENT cursor is NOT bounded by
the floor: a client returning after its session was reaped can present anything, and
must get that host refilled rather than silence.

**Nothing in the log reads a clock.** A version orders everything and is the only clock
any correctness argument rests on. The wall clock that used to age mutations out is gone
with the rule that needed it, which also retired the two-field `Stamp` that existed to
keep the two apart. The only thing wall time still decides anywhere in the runtime is
how long a session lingers after its stream ends — and that decides how cheap a
reconnect is, never what is correct.

**A slug nobody is watching records nothing**, which is the other half of the same
question. The recorder reads the session set it already reads for visibility; with none,
it writes one number (`completeFrom`) and drops the history that number makes
unreachable. A client returning across such a stretch is answered by `reaches` — false,
so it repaints. That gate is safe only because a document registers its session BEFORE
reading the snapshot it renders from: a frame that decided to skip did so before that
read, so any skipped version is one that page already contains.

Two properties worth keeping. **`since` is TOTAL**: it returns a `Resume` rather
than an `Option`, because there is no longer a cursor it cannot answer. What used
to be a refusal is now an entry in `refill`, so incompleteness is expressed as
data at the smallest granularity that works — one host, not the whole body. That
is the standing rule this ADR shares with the design it came from: *a complete
update is a fallback, never a design choice.* The failure mode it replaced was a
phone foregrounding after an hour and being served its entire dashboard because
one dynamic group had aged out.

And pruning is per-log and therefore per-slug, and a log dies on renderer swap anyway,
so there is no reaper and no cross-connection bookkeeping.

The caveat the floor comes with, stated plainly: a session that stops advancing pins the
log open. What bounds it is the session's own lifetime — a stream that ends starts a
linger, and a linger that expires drops the session, which releases the floor. So the
clamp the age bound used to provide is now the reaper, in the one place where "how long
do we keep this" is genuinely a wall-clock question.

### How long a tab may be away, and what each step out costs

Three nested windows decide what a returning client is answered with. Only the first is a
wall clock; the others fall out of what is still recorded.

| Away for | What is still true | The answer it gets |
|---|---|---|
| **< `LingerWindow` (2 min)** | its session survives (`holds`, `told`, `position`) | targeted resume — only what moved; or a repaint if its cursor is behind `told` |
| **beyond that, while another viewer holds the slug** | session reaped; a fresh one is minted with empty `holds`, and the changelog still `reaches` its cursor | resume off the changelog, nothing suppressed — a fatter first patch |
| **beyond that, with no viewer left** | the slug recorded nothing while unwatched (`skipped` moves `completeFrom`) | body repaint |

Each step is correct and progressively more expensive, which is the ladder this ADR is
built on. Note the second row needs *another* viewer: on a single-viewer dashboard — the
common home — the last tab leaving stops the recording, so returning past the linger is
always a repaint, however long the log would otherwise have been kept.

**Why 2 minutes and not 10.** The tempting read is that a longer window is nearly free and
saves repaints. It is not free, and the reason is that the cost and the benefit are the
same mechanism: a lingering session keeps its slug RECORDING (it is in the set
`recordFrame` reads) and pins `Sessions.floor` so nothing can be pruned. That retention is
exactly what makes the cheap return possible — you cannot keep the window and drop the
cost, and a version that stopped recording for lingering sessions would hand every
returner a repaint anyway, which is the thing the window exists to avoid.

So the question is really *how long is it worth keeping a slug recording for a viewer who
has left*, and 2 minutes answers it for what the constant was sized for: a wifi handover,
a lid, a phone waking. Ten minutes pays five times the retention, per absent viewer per
slug, to widen a band whose only failure is one body render over an already-open stream —
the same work every page load does, and morphed rather than reloaded, so scroll and open
dialogs survive it. A deliberate multi-minute absence is precisely the case where a
repaint is the right answer.

Raise it if a real deployment shows returns clustering in the 2–10 minute band and the
repaint being felt. It is a plain constant, not a policy, and nothing about correctness
moves with it.

**Cost:** the `version >= V` scan is per pull, over a map bounded by the dashboard's node
count, and the log is read once OUTSIDE any `Ref.modify` so a pull never serialises
against the recorder. The record and the prune happen in ONE update, so no reader ever
sees a state where the frame has landed but the history it makes prunable has not been
dropped.

### Structural changes: one mutation per node

The distinction that matters is not which sites touch the cache — it is **which
patches can be replayed**.

Most prune sites are cache **invalidations**, not removals: `repaintGroup` and
`flipStateGroup` drop their children's entries *while morphing an ancestor whose
fresh HTML re-supplies those children*. The prune only stops a later diff suppressing
a member fragment against a pre-repaint entry; it says nothing about the DOM. A
tombstone there would be actively harmful — it would replay as a `remove` of an
element the ancestor's morph had just legitimately restored.

Every real removal is a dynamic-group child: exactly one site emits `Patch.Remove`,
and its selector is always `#{gid}_{entity}`.

- A `remove` is **idempotent** — Datastar resolves selectors with
  `querySelectorAll`, so removing an absent id matches nothing. It replays verbatim.
- An `insert` is **position-dependent**: `before` a neighbour that may itself be gone
  by the time an absent client returns.

So the insert is made **self-repairing** rather than replayable: `Mutation.Placed`
emits `remove` AND `insert` for its own node, which is correct whatever the client's
DOM holds — absent, present in the right place, or present in the wrong one. "Put
this element here" needs no knowledge of where it was. That also collapses two
records into one for a modelling reason rather than an optimisation: a node cannot be
both gone and present, so parallel `tombstones`/`arrivals` maps made an invalid state
representable — and that state is exactly what a leave-then-rejoin produced. One sum
type with latest-wins makes the rejoin ordinary (`Placed` replaces `Gone`).

**Ordering.** Morphs go first, ascending by version, because a container's cached
HTML embeds its children's — a parent rendered at v=25 applied after a child at v=30
would revert it. Placements go last, **descending by current position**, so each
insert's anchor provably exists: it is either a member the client already had or one
placed a moment ago. Ascending fails, because a node's anchor can be a later node not
yet inserted.

**One anchor rule serves both paths.** `Patches.insertInto` computes "before the
nearest member ordered after this one that the client's DOM can anchor on, else
append into the group root", and the live add path and the resume replay differ only
in which siblings qualify (pre-change members / any current member). It reads the
order out of `Renderer.dynamicMembers` rather than comparing entity ids, so an
author-chosen member sort works on both paths — the live path used to compare ids
directly and silently required id-sorted membership.

**The subtree-authority invariant:** a node's fresh HTML is authoritative for
everything under it, so stamping it supersedes that subtree's mutations. Enforced in
`invalidateWhere`, which every prune site uses while setting the subtree root in the
same operation.

Two removals that look like exceptions and are not: an entity vanishing from HA
triggers a registry re-evaluation and therefore a renderer swap, which mints a new
`logId`; and a popup close is a per-session control patch that dies with the
connection.

> **Note for later — positional changes on the LIVE path.** Member order is
> ascending by entity id today, so a member cannot change position without a
> membership change. When sorting becomes author-controlled, a reorder with an
> unchanged member SET lands in `renderMembershipChange` with empty `added`/`removed`
> and falls through to a whole-group repaint. Correct, but the heavy path. The
> optimisation is `n` minus the longest increasing subsequence of target positions,
> the standard list-reconciliation result; each moved member emits remove + insert and
> records `Placed`, so **resume needs no change and no new mutation kind**. Watch for
> a sort key derived from live state (brightness, `last_changed`): it makes reorders
> fire on ordinary value ticks, so the in-place-morph fast path would have to test
> position before assuming a content-only update. A STATIC key only reorders on
> membership change and is much cheaper — worth offering that shape first.

### The head splits in two

What a patch genuinely cannot repair is `<head>`, so the hash is scoped to exactly
that — 12 hex of SHA-256 over the DECODED model (blind to key order and formatting),
excluding `<base href>` because that is per-REQUEST (the ingress prefix), not
per-dashboard. Not over the Pkl source, which is simultaneously too sensitive (a
comment in a theme) and not sensitive enough (a change reaching the theme through an
import would need the whole import graph hashed). A hash rather than a counter
because a counter does not survive a restart, and an add-on restart on an HA update
must not refresh every browser when the theme is byte-identical.

- **`styleHash`** — theme tokens, `tokensDark`, inline `styles`, `title`: exactly
  what `Renderer.themeStyleTag` + the `<title>` render, so a mismatch is repaired by
  `Server.headPatches` — two element patches, no reload.
- **`headHash`** — `<link>`ed stylesheets (the deferred ones too — deferring changes
  when a sheet applies, not that the head names it), the theme's scripts (module
  `src`s and inline bodies), `theme.chrome`, and the `<meta name="theme-color">` pair.
  None can be patched honestly: a `<link>` can be added but not un-applied, a script
  cannot be un-run, the chrome is the frame the body patch lands INSIDE, and a
  `<meta>` is markup rather than CSS — which is why exactly those two token VALUES sit
  here while every other token patches with `styleHash`.

`headPatches` is orthogonal to the resume/repaint decision, prepended to whichever
outcome applies, so a re-themed dashboard costs a client its stylesheet rather than
its scroll position and its open popup. Crossing to another dashboard needs none of
this — it is a page load (ADR 0002).

**A connection may only claim what it can prove it sent.** The store runs ahead of the
changelog — the recorder writes it on its own fiber — so there is a window in which
`store.version` names a change `since` cannot see. An opening RESUME therefore claims
the doorbell's value, read BEFORE the log, and a REPAINT claims the snapshot's, because
it painted all of it. Claiming the store's version for a resume is silent staleness of
the precise kind this ADR exists to prevent: the client is told it is current through a
change it never received, and the pull that would have carried it is then skipped
(`version <= position`), so it is lost until that entity next moves. Erring low is free
— the next pull re-offers, and `holds` suppresses whatever the client already has.

`Server.openingPatches` picks in this order: **unpatchable head differs** ⇒ full page
reload and nothing else (the page is about to re-render itself); **cursor comparable**
⇒ resume; **anything else** ⇒ body repaint. The repaint is the default and every doubt
falls back to it — no cursor, an unparseable one, one from a log this server no longer
has, one from the future, or one the log can no longer speak for. A repaint is always
correct and merely expensive; a wrong resume is silently stale forever.

**...and a session may only be resumed against a record the client has ACKNOWLEDGED.**
`holds` records what was *sent*, which is not the same claim as what was *applied*. A
stream that breaks mid-batch, or a tab frozen while its socket keeps filling, leaves a
session claiming digests that DOM never received — and because a resume answers from
`holds`, every later one then computes "nothing owed". The tab is stale until its user
reloads, and nothing reports it. This shipped, and the report was "a backgrounded tab
never catches up".

The cursor is the ack, and it needs no new channel: it is server-set, but it rides
**last** in its batch (`Server.pull`), so a client echoing version V demonstrably applied
everything in front of it. It is measured against `Session.told` — the newest version
this client was ever *announced*, written wherever a cursor signal goes on its wire and
seeded by the document, which renders one into the page. Behind `told` ⇒ bytes we claimed
were lost ⇒ `holds` is unproven ⇒ repaint.

Two properties make this cheap rather than ruinous, and both are load-bearing:

- **It cannot fire on an ordinary tab switch.** A visibility refetch closes the stream;
  while it is closed nothing is sent, so `told` cannot move and the returning client's
  echo still matches it. It resumes with what it missed, as before.
- **The yardstick is `told`, never `position`.** A pull that owes this client nothing
  advances the position silently and announces nothing (a batch with no bytes carries no
  cursor), so the echo legitimately trails the position on any dashboard where an
  unrendered entity ticks — which is every real one. Gating on `position` would repaint
  almost every reconnect.

An ack is only ever available at reconnect: Datastar serialises signals into a request,
and the SSE GET is the only request that carries them (action POSTs send none). That is
sufficient, because for the DOM to fall behind what we claimed, bytes must have gone
unapplied — which means the stream broke, which means Datastar reconnects and we are
asked. A frozen tab whose socket was never closed lost nothing: its buffered bytes are
still there when it thaws. Loss implies a break implies an ack.

A continuous ack was rejected: a POST per batch per client doubles the request count and
still cannot see the failure, because a client that stops applying also stops acking, so
the server observes only silence — and reading silence needs a timeout, which is the beat
heuristic this ADR retired. An ack channel only reports on clients that are fine.

The reload is a **signal** (`_reload`, turned into `window.location.reload()` by one
`data-effect`), not a patched `<script>`: it reuses the channel already carrying the
cursor and keeps client behaviour in the page shell where the rest of it lives.

**The resume rule: one rule, one candidate set, one snapshot.** The candidates are
the nodes the cursor names (`version >= V`) UNION the nodes of the surfaces this
client has open — the second set because the cursor alone cannot name them: a
surface's nodes may not have changed since the client's cursor and still be absent
from its DOM. Each candidate is rendered once from the current snapshot and sent unless
this SESSION's `holds` already names those exact bytes, with a missing entry counting as
"send" — and with `holds` consulted at all only once the client's cursor has proved it
applied what we last announced (above). Nothing is special-cased by kind. This is the same call a live tick makes — a
tick is a resume from `position + 1` — so there is one path to be right about rather
than two that must agree.

That subsumes what used to be three branches. There is no per-session repaint
step: a tab panel is simply a surface in `open`, so its nodes are candidates like
any others. And a popup needs no branch either — its nodes are in `open`, and a
body repaint replaces `#dashboard` only, while the popup host lives in
`theme.chrome` outside it, so an open dialog is never disturbed.

**Why one scalar cursor is enough despite per-client visibility.** A client may be sent nothing at
all for a change inside a tab it is not looking at, and still have its cursor advanced past that
change. That is sound because of an invariant every reveal path holds:

> **Every reveal is an unconditional complete fill.** A host swap renders the arriving surface
> whole and inner-patches the host; a flip's placement inserts the whole branch. Neither consults a
> version, and nothing incremental ever depends on what a client missed while a subtree was hidden.

So "I have everything through V" needs to be true only of what the client can SEE; anything it
later reveals arrives in full. If a reveal ever became incremental — "send only what changed in
this tab since you last looked" — one cursor would stop being sufficient and this design would need
one per subtree.

**The one thing still worth its own branch** is a popup claim this dashboard
cannot serve — renamed, removed, or belonging to another dashboard. That dialog is
in nobody's open set, so no rule reconciles it, and without a host reset it would
sit on screen forever.

### The first connect carries no signals

`data-init` fires from `<body>` before Datastar has merged the descendants'
`data-signals`, so that one request arrives signal-less. A signals-only read would
render the DEFAULT tab and its repaint would morph the correct first paint away,
dragging the URL mirror down with it. So `Server.Restore` puts the page's view state
(bake selections, the open popup among them) on the `data-init` URL as ordinary
query params; every later request carries live signals, which win wherever both
name the same fact. The open popup is not a separate carrier: it is
`ui.<PopupHostId>`, the same selection mechanism as a tab. See ADR 0005 for where
per-connection state lives.

### Nothing may be dropped from a stream that carries a cursor

The cursor rides the same SSE stream as the patches, and that makes the queueing
policy a correctness question rather than a capacity one. Drop a patch while keeping
a later cursor and the client claims a version whose changes it never applied — and
`since` will never re-send them, because those fragments are stamped below the
cursor. Stale forever, with nothing observable at the time of the mistake.

**The asymmetry is the whole rule, and it is easy to read backwards.** Dropping a PATCH
while keeping a later cursor is fatal: the client claims a version whose changes it never
applied, and `since` will never re-send them. Dropping the CURSOR while keeping the
patches is safe, and is a live option — a pull that owes a client nothing need not send
the signal that says so; it can ride the keepalive instead. The server's `position`
advances either way, and the two are not the same number.

What bounds the safe direction is what pruning can actually do. `position` feeds the
floor, so a server running ahead of its client prunes a little more than that client's
real state warrants — but `FragmentLog.pruned` drops only MUTATIONS and raises
per-container horizons, `fragments` is untouched, and `completeFrom` (which forces a full
repaint) moves only in `skipped`, which requires zero sessions on the slug. So a client
reconnecting on a stale-low cursor gets at worst one container refilled, never staleness
and never a page repaint. Note what is not claimed: that a client at the older cursor is
provably equivalent to one at the newer. A pull owes nothing partly through `holds`
suppression, and `holds` dies with the session. It is a bound on the damage, not a proof
of equality — and the bound is what makes it a free choice.

`StateStore.changes` is therefore subscribed **unbounded**, and for a second reason as
well: `Topic.publish1` sends to each subscriber's channel in turn and blocks on a full
one, so a bounded subscription there would let one stalled recorder stop `HaFeed.pump`
and with it the store.

The patch side no longer has a topic to reason about. Nothing is pushed: the recorder
rings a per-slug `SignallingRef` and each session pulls (ADR 0012), so a slow client
holds up only itself. Coalescing is the RIGHT behaviour there rather than a loss —
`.discrete` collapses versions that land while a session is rendering into one pull, and
that pull is computed against the current snapshot, which is what a slow client should
get.

**The tab hands its session over, it does not reuse it.** A reload is a complete
re-render, so the fresh document's paint is the authoritative statement about that
DOM and the previous session's `holds` describes something that no longer exists —
there is nothing in it worth adopting. What the old id is good for is retirement:
`sessionStorage` (per TAB, and surviving a reload, where a cookie would be
per-browser and make two tabs fight over one session) carries it onto the next
connect as `?prev=`, and the server drops that session unless a stream is still
holding it. Without it a superseded session waits out its adoption window holding
an old `position`, and the changelog floor is the LOWEST position, so a burst of
reloads keeps the log un-prunable for as long as they last.

Never reusing the id is what keeps the rest simple: two tabs can never land on one
session, so `holds` always describes exactly one DOM and the displacement rule keeps
its narrow job (a reconnect racing its predecessor's teardown). The not-Held guard
covers the one case the browser creates on its own — a DUPLICATED tab inherits
`sessionStorage`, so the predecessor it names may be very much alive.

What is bounded is the **connection**, not the queue: ember gives every socket write
an idle timeout (60s by default), so a peer that stops reading is torn down and its
subscriptions released with it. A dedicated stall watchdog was tried and removed —
it duplicated that, buying 30s versus 60s of queue growth for a Ref, a Deferred, a
timer and a parameter.

**`{retry:'always'}` on the `data-init` `@get`.** Verified against the pinned v1.0.2
bundle rather than the docs: after the SSE body is consumed it retries only on
`retry === "always"`, so under the default `auto` a 200 whose body simply ends is
"finished" and the client sits there forever. This stream is never supposed to end,
so any end is a reason to reconnect — a property worth having outright rather than
re-derived per kind of end. It also stops a non-200 (a slug since deleted) leaving a
frozen page with no indication: the retries run out and the "connection lost" banner
appears.

**There is no subscription to nest around any more.** The old ordering rule —
subscribe, then read the snapshot, so a change landing between the two is queued rather
than published to nobody — is gone with the topic: a `SignallingRef` hands a new watcher
its current value, so a frame recorded before this stream existed still wakes it. Erring
the other way remains safe anyway: a change caught by both the opening paint and a pull
arrives twice, and a patch is an idempotent morph.

### Keepalives, and the two health concepts

The two connections are **two facts**, and neither end may infer the other's:

1. **server ↔ HA** is server-owned, so the server PUSHES it as the `haDown` signal
   (from `HaFeed.healthy`, which is `connection.isDefined`). The banner renders
   `data-show` off it; no client-side inference.
2. **browser ↔ server** only the browser can observe, so it stays client-side, bound
   directly to Datastar's `datastar-fetch` lifecycle event (`error`/`retrying`/
   `retries-failed`). Transport takes priority, since a dead transport also freezes
   `haDown`.

Both banners ship hidden by an INLINE `display:none` — Datastar is a deferred module,
so the markup paints before `data-show` first runs — and the state assignment is
debounced 600ms, because navigating away aborts the stream and paints "Reconnecting…"
on the outgoing page for an instant.

**The SSE keepalive is a comment, not a signal.** `ServerSentEvent(comment = …)`
renders `: keepalive`, which any conforming parser skips, so it never reaches
Datastar's message handler, never touches the signal store, and never appears as an
event in devtools. Health needs no repeating anyway — it is pushed on connect and on
every transition. It exists for INTERMEDIARIES, which is the normal case rather than
the exception (ingress is nginx; the remote path adds a hop), and it is the *cheap*
option: letting an idle connection be reaped costs a handshake plus a GET carrying
every signal plus the opening patches, perhaps 1–2 KB once a minute, against ~2 KB an
hour for the comment.

> **FUTURE:** a direct LAN connection needs no keepalive and we could tell — the
> ingress hop announces itself (`X-Ingress-Path`, already read for `<base href>`) and
> a reverse proxy conventionally sets `X-Forwarded-*`, so it could be sent only to
> connections that arrived through a hop. Not done because the win is ~2 KB/hour and
> a wrong guess is a connection that silently drops once a minute — the failure
> nobody reports, because it still works.

**The HA keepalive is activity-driven.** The WS transport pings only after
`pingInterval` of silence, expressed as a `switchMap` over a `lastActivity` signal:
every received frame cancels the pending sleep and starts it over, so a busy
connection is never pinged and a dead socket — which produces no activity — has
nothing left to trigger it. Traffic arriving mid-ping cancels it, which is right:
that traffic is the liveness the ping was asking for.

**Reconnect is rate-limited, not backed off.** `HaFeed.superviseLoop` is
`repeatEval(runConnection.attempt).meteredStartImmediately(ReconnectDelay)`. The wait
is unconditional, which is the whole reason it cannot spin: a retry policy has to be
told which endings count, and the ending that reconnected instantly was the one
nobody thought to name — a peer that accepts, auths and closes politely, over and
over (measured at 91,070 connect cycles in two seconds before the fix). `fixedRate`
dampens missed ticks, so a connection that outlived the period reconnects at once
while a flapping one is held to one attempt per period: no lifetime to measure,
nothing to reset. Flat rather than escalating because this is one WebSocket to one
local instance where a failed attempt is a refused TCP connect — and escalation gets
the main case backwards, since an HA restart takes about as long as the delay needs to
reach its cap, leaving the dashboard dark for that long *after* the instance is ready.

**Connectivity is logged off the connection signal, not the end reason.** A boolean
alternates, so `changes` cannot swallow a real transition; keyed on the reason it
can, because that stream emits one element per connection END and two ends with the
same cause an hour apart are consecutive elements.

## No RESUME on the HA → server side

`subscribe_entities` has no "since" parameter, so a reconnect always reopens with the
full state of the subscribed set — and that is already free, because `StateStore`
publishes only entities whose content actually changed (`EntityState.sameContent`), and
`EntityState.stale` drops a re-sent state carrying the timestamp we already hold. The
reconnect gap is lossless BY CONSTRUCTION rather than repaired after the fact, and that
is recorded at the three places which guarantee it: `HaFeed.runConnection`,
`StateStore`'s `Ingest.Remove`, and `ServerApp.watchRegistryEvents`. Re-deriving is
strictly stronger than replaying, since it also covers changes no event was ever seen
for. So the browser↔server resume has no HA↔server counterpart to build, and should not
grow one.

What the upstream subscription DOES carry is a scope: it asks for the entities the
registered dashboards can be woken by, not the whole house, and rotates when that set
moves. That is ADR 0030, and it is orthogonal to this one — narrowing changes which
entities arrive, never whether a gap in them can be recovered.

## Still to prove in a browser

The mechanism is verified end to end against a live Home Assistant: a disconnect and
reconnect resumes, and changes made during the gap are synced back — which is the
silent-staleness failure the design exists to avoid. Three narrower properties are
covered at the unit level only. All three fail *visibly* rather than silently, which
is why they are hardening rather than gates:

1. **A member LEAVING a dynamic group across a disconnect is corrected by a `remove`
   patch, not a group morph** — that saving is the point. The trap: membership can
   change from a pure content change (an attribute crossing a predicate) with nothing
   added or removed anywhere, so testing only entity add/remove would miss it. Both
   paths need it — per-entity churn on an established group AND the wholesale path —
   because they record the group differently. Rejoins are covered by `FragmentLogSuite`.
2. **A parent and a child changing at different versions resume in the right order.**
   The silent failure is a stale container reverting a fresh child; only ordering
   prevents it. Unit-covered in `FragmentLogSuite`.
3. **A cursor the log can no longer speak for repaints rather than resuming** — a
   container pruned past it, or a stretch this slug did not record at all. Unit-covered
   via the horizon and `reaches`; what a browser adds is that the repaint restores a
   correct DOM from an arbitrarily stale one.

## Deferred

~~**The per-viewer render memo.**~~ **Landed**, and as one mechanism rather than the two
this section anticipated. Every session renders for itself, and `RenderCache` (per slug,
single-flight, one generation per node per selection, keyed by the renderer plus what the
render READ)
is what keeps N sessions woken by one ring from rendering the same node N times. It is
deliberately NOT keyed on the store version — a global counter, so one humidity sensor
would invalidate every node on every dashboard — which is the one warning from the
original note that survived into the implementation. See ADR 0012.

~~**Advancing a client's cursor on quiet ticks.**~~ **Landed**, and it turned out to
be the resolution of a tension rather than a nudge. A pull that owes this client
nothing now sends nothing at all — no element patches and no cursor — and the
25-second keepalive carries the cursor instead, emitting it only when `position`
moved since that stream last said so. A quiet night is still comments.

That leaves `position` running ahead of the client by up to one interval, which is
safe for the reasons set out under "Nothing may be dropped" above: the client's
cursor is the authority at reconnect, and a stale-low one costs at most a container
refill. What it removes is a signal per client per frame on a busy dashboard, which
is the cost the original note was weighing.

It also cost a test seam, worth recording because the same trap is waiting for the
next person: `ServerSuite`'s `LiveWorld` used the cursor as its "the batch reached
me" marker, precisely because every connection got one whether or not it got
patches. With that gone, a client owed nothing waits forever. The replacement is
strictly better — gate on the SERVER having served the frame (`Sessions.floor`
reaching the store version, i.e. every session pulled it), then wait for arrival —
because it no longer infers completion from a wire artefact.

**`data-signals__ifmissing`** — **adopted**, after the spike that explained why it
had failed. The modifier behaves as documented; the constraint is one Datastar
does not spell out:

> `__ifmissing` initialises a signal only if nothing has REFERENCED it yet. A
> read creates the signal (as `""`), after which the seed correctly declines.

Seeding from the panel could never work, because the tabs BAR reads `$ui_<id>`
for its highlight and renders first — the signal stayed `""` and the URL mirror
faithfully wrote empty, which is how a deep link lost its selection. Seeding
from the BAR works: a parent's seed reaches its children's readers. Both halves
are pinned by `DatastarMorphContractSuite`.

It costs one prerequisite. A tabs node's template reads `{{bakeIndex}}`, so its bytes
depend on the selection and every path that renders it has to know the viewer. Every
render path therefore takes the viewer's `uiState`, which is also what makes any variant
machinery unnecessary (ADR 0012).

What it buys: a re-render can no longer overwrite the tab a client actually
chose, which closes the race between a tab click and a patch already in flight.

## Rejected along the way (still guarding the design)

- **A server-side mirror of each client's DOM *as the resume mechanism*.** This is the
  rejection worth reading carefully, because the shape it rejected now exists: a session
  DOES keep a node-to-digest record of what its client holds. What stays rejected is
  depending on it. The cursor is what a resume is computed from, everything works with
  that record empty, and losing it degrades a reconnect by one rung (redundant bytes)
  rather than breaking it. A mirror the design could not do without would have to be
  durable, reconciled, and correct across a server restart; a mirror it can throw away
  costs nothing to be wrong about.
- ~~**Persisting the session id across the reconnect.**~~ **Adopted**, once there was
  something worth carrying. The original rejection was about a per-session *cache* whose
  grace window/reaper/cap were most of the work for a value that did not remove the
  cursor — and it was written when a `Session` held only a slug, an open set and a
  control queue. Now it holds `holds` and `position`, so a reconnect inside the linger
  window is told what moved instead of being repainted, and the grace window/reaper the
  rejection worried about is one `Tenure` value with guarded transitions (ADR 0012).
- **An entity-level cursor + re-render on resume.** Superseded by the fragment log,
  which is strictly better: no re-render, and it fixes the departing-member hole this
  could only paper over by repainting every dynamic group, because a predicate tested
  against *current* state cannot see a member that has left.
- **Marking a group structural for EVERY membership change** rather than only for
  arrivals. Simpler, and briefly the design — rejected because it re-transmits a whole
  group when one member leaves, the exact cost this exists to remove, on the exact
  link that motivates it. This is the one place the design carries two mechanisms
  where one would do, so the burden is on the arrival case to stay narrow.
- **Tombstoning at every prune site.** Most prune sites are cache invalidations whose
  subtree is being re-supplied by an ancestor morph in the same operation, so a
  tombstone there deletes a live element. Worth keeping on file because "prune the
  cache" and "the element is gone" look like the same event and are not.
- **Exponential backoff on reconnect**, and **a bounded or dropping subscription**,
  and **a stall watchdog** — see the two connection sections above for why each was
  removed rather than tuned.

## Consequences

- Datastar specifics relied upon (signal round-tripping on GET, `_` exclusion, the
  `retry` mode's post-body semantics, `ifmissing`, `datastar-fetch` detail types,
  patching elements inside `<head>` by id) are pinned to **v1.0.2** and were read off
  the bundle rather than the docs. Re-verify on upgrade.
- A resume is bounded by what live sessions still need: below the floor, or across a
  stretch this slug did not record, a client repaints.
- The server holds per-client state — `holds`, `position`, an open set — for as long as
  a session lives, which is its stream plus a linger. None of it is required for
  correctness (see the rejection above), and none of it survives a restart.
- Scaling viewers costs one pull each per frame; the renders behind those pulls are
  shared through the per-slug render cache, so N viewers of one dashboard still cost one
  render of each changed node.
