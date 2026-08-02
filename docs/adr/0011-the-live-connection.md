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

Re-diffing after a reconnect cannot help, and the reason is the whole design. The
live path already narrows work twice — a reverse index picks the nodes an entity
touches, then a cache suppresses byte-identical re-renders — but that cache is a
**broadcast log, not a per-client mirror**: `sharedPatchPublishers` renders once per
slug and diffs against one cache shared by everyone viewing it. Changes a
backgrounded phone missed were still absorbed by it — they *were* broadcast, to
whoever was listening. So from the cache's point of view that DOM is already
current, and the information about what this client missed exists nowhere on the
server.

Everything here is about supplying that missing information **without keeping
per-client state**, and about the two connections' health being observable without
either end guessing.

Be honest about the size of the win: on a fast LAN it is invisible. It is a
mobile/slow-link optimisation plus a DOM-stability improvement, not a bug fix.

## The design

Three statements everything below rests on. The first is structural and belongs
to ADR 0008; the other two are this ADR's.

> **1. A node's patch carries its own rendering and never the contents of a
> *mount*.** A mount is filled independently; anything else the card composes — a
> tab bar's buttons — rides with it.

> **2. Everything that changes a client's DOM goes through the log.** Every path:
> the live diff, a mount fill, a flip, a resume, a repaint.

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

### The cursor: four client-carried values

The client carries what it holds; the server keeps one versioned log per slug,
shared by every client, and **no per-client memory between connections**.

| value | question it answers | on mismatch |
|---|---|---|
| `headHash` | does the browser's UNPATCHABLE `<head>` still match? | full page **reload** |
| `styleHash` | and the patchable rest of it (theme CSS, `<title>`)? | two **head patches** |
| `logId` | is this cursor even comparable? | body **repaint** |
| `storeVersion` | how far behind is this client? | body **repaint** |
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
case class Stamp(version: Long, millis: Long)      // logical clock + wall clock
case class Fragment(digest: Digest, version: Long) // a fingerprint, never the bytes

enum MemberKey:                                    // what occupies a mount, and how to render it
  case Entity(id: String)                          //   a dynamic group's member
  case Surface(id: String)                         //   a state group's branch

enum Mutation(val at: Stamp, val container: NodeId):   // last STRUCTURAL fact about a node
  case Gone(in: NodeId, stamp: Stamp)                  // its element was deleted
  case Placed(in: NodeId, member: MemberKey, stamp: Stamp)  // belongs at its current position

case class FragmentLog(id, fragments: …, mutations: …, horizon: Map[NodeId, Long])
def since(v: Long): Resume                         // TOTAL — see "the horizon is per container"
case class Resume(nodes: Set[NodeId], moved: …, refill: Set[NodeId])
```

`fragments` answers *when did this node's content last change*; `mutations`
answers *where is this node* — the changes not expressible as a morph of an
element the client already has. A resume renders every node at `>= V` from the
CURRENT snapshot and morphs it, then applies every mutation at `>= V`.

**What is a log key.** A fragment is a node's **own** html — never the composed html that
includes its children. The composed form welds host to children and makes them inseparable, which
is the thing statement (1) exists to prevent, arriving through the log instead of through a patch.

For a card with a `self` the own html is that `self`; for a leaf it is the whole rendering. Two
shapes have no own html and are therefore **neither log keys nor morph targets**:

- a **bare container** (`Column`/`Row`/`Grid`/`If` — a mount and no `self`), which renders as a
  constant wrapper around a hole;
- a **dynamic group root**, which composes its members and whose members are keyed individually.

Excluding them loses nothing, because their children are addressable in their own right. It is
also not a new rule: `validate` already rejects a live-entity slot on a bare container *because it
has no patch target*.

The reason it matters is that the log is per SLUG. Rendering a bare container by id renders its
whole subtree, mounts included, so its bytes depend on which member each descendant mount has
selected — and whichever the log holds is wrong for somebody.

**Variance, where it exists, is local.** The only thing that can make a node's own html differ
between viewers is its own group's selection (`bakeIndex` — that node's variant id). Never a
descendant's, since own html excludes mount contents. So a variant-bearing node keys
`(nodeId, bakeIndex)`: one entry per member, no product over the subtree. The rule to keep true is
that a node's own rendering contains no mount — its own, or a child's.

**The log records WHEN, never WHAT.** A `Fragment` holds a digest, not HTML, and
content is always rendered now. Three things follow. The log cannot go stale
against the renderer, because it stores nothing a renderer swap could
invalidate. The snapshot a resume renders from is by definition at least as
fresh as anything the log could have kept, so a client that missed five ticks
gets the fifth and not the first. And — the property worth the most — **a path
unsure of what it put in a client's DOM can simply drop the entry**, because an
absent entry reads as "unknown, send it". The worst outcome of dropping is a
redundant re-send; the worst outcome of a wrong entry is a suppressed change,
which is silent and permanent. Everything that mutates the DOM without knowing
its own bytes exactly (a mount fill, a per-viewer branch placement) uses that
escape rather than recording a digest that would be true for one client only.

**Two fields, two jobs, and they are not interchangeable.** `version` serves the RESUME path —
`since(cursor)` uses it to decide what a returning client is owed, and nothing on the live path
reads it. `digest` serves the LIVE path — re-render, compare, skip when the bytes are identical —
and its only purpose is to not re-send. A consequence worth stating because it looks like an
optimisation and is not: a node re-rendered to the same bytes must NOT have its version advanced.
That would make it mean "when did we last look", `since` would over-report, and returning clients
would be sent morphs for nodes that never changed.

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

### Eviction: `fragments` self-limits, `mutations` does not

Keying by node id is what keeps the log small: a node has one latest content and one
latest structural fact however often it churned, so a hyperactive sensor cannot flood
it. For `fragments` that is sufficient — they describe nodes that currently EXIST,
and both `removed` and `invalidateWhere` drop entries.

`mutations` is the exception. A `Gone` for a member that left and never returned has
nothing to evict it, so the map accumulates one entry per entity that has EVER been a
member of any group — bounded by entity count rather than dashboard size, and growing
with elapsed time rather than complexity. A `dynamic` group over "every light that is
on" will, over a week, name every light in the house.

So mutations are **aged out** (`FragmentLog.Retention`, 1 hour) and the log carries a
**`horizon`**: the oldest version `mutations` is complete for, raised past everything
evicted — **per container**, because one group ageing out says nothing about any
other. A cursor below a container's horizon does not repaint the page; it puts
that container in `Resume.refill` and the client is sent that ONE mount's
contents wholesale. Age rather than an entry cap
because the real question is "how long can a client be away and still be worth
resuming" — minutes to hours for a backgrounded tab — where a count is only a proxy;
and a cap is redundant anyway, since the id-keyed map already bounds burst churn.

**Retention is why `Stamp` carries two clocks.** `version` orders everything and is
the only clock any correctness argument rests on; `millis` only ages mutations out.
They are never compared to each other and never order the same thing, so this does
not reintroduce the two-clocks-in-one-ordering problem that ruled out `last_updated`
above. A clock step (NTP, a host waking from suspend) can widen or narrow a retention
window but cannot corrupt a cursor comparison. The caller reads the clock once per
diff and passes it in, so the log stays pure.

Two properties worth keeping. **`since` is TOTAL**: it returns a `Resume` rather
than an `Option`, because there is no longer a cursor it cannot answer. What used
to be a refusal is now an entry in `refill`, so incompleteness is expressed as
data at the smallest granularity that works — one mount, not the whole body. That
is the standing rule this ADR shares with the design it came from: *a complete
update is a fallback, never a design choice.* The failure mode it replaced was a
phone foregrounding after an hour and being served its entire dashboard because
one dynamic group had aged out.

And eviction is per-log and therefore per-slug, and a log dies on renderer swap
anyway, so there is no reaper and no cross-connection bookkeeping.

> **FUTURE — retention by live cursors.** The age bound is a blunt stand-in. The
> precise rule is to truncate below the OLDEST cursor any live connection holds:
> `Sessions` is already keyed by `conn`, so each could report its last-sent version
> and the log could evict everything below their minimum. Two caveats, the second
> being why the age bound must survive rather than be replaced: it reintroduces
> per-connection server state (acceptable only because it would serve RETENTION,
> never correctness — which is what separates it from the rejected per-client
> mirror), and a wedged connection would pin the log open indefinitely. The real
> rule is `min(live cursors)` **clamped** by the age bound, not one or the other.

**Hot-path cost is unchanged:** the live path only does `log.holds(nodeId, html)`
— one digest compare — O(1) before and after. The `version >= V` scan is resume-only — once per
reconnect over a few hundred entries — so it needs no index, and it must read the log
once OUTSIDE the `Ref.modify` so a reconnect never serialises against the live diff.

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
- **`headHash`** — `<link>`ed stylesheets, module scripts, `theme.chrome`. None can
  be patched honestly: a `<link>` can be added but not un-applied, a module script
  cannot be re-run, and the chrome is the frame the body patch lands INSIDE.

`headPatches` is orthogonal to the resume/repaint decision, prepended to whichever
outcome applies, so a re-themed dashboard costs a client its stylesheet rather than
its scroll position and its open popup. Crossing to another dashboard needs none of
this — it is a page load (ADR 0002).

`Server.openingPatches` picks in this order: **unpatchable head differs** ⇒ full page
reload and nothing else (the page is about to re-render itself); **cursor comparable**
⇒ resume; **anything else** ⇒ body repaint. The repaint is the default and every doubt
falls back to it — no cursor, an unparseable one, one from a log this server no longer
has, one from the future, or one the log can no longer speak for. A repaint is always
correct and merely expensive; a wrong resume is silently stale forever.

The reload is a **signal** (`_reload`, turned into `window.location.reload()` by one
`data-effect`), not a patched `<script>`: it reuses the channel already carrying the
cursor and keeps client behaviour in the page shell where the rest of it lives.

**The resume rule: one rule, one candidate set, one snapshot.** The candidates are
the nodes the cursor names (`version >= V`) UNION the nodes of the surfaces this
client has open — the second set because the cursor alone cannot name them: a
surface's nodes may not have changed since the client's cursor and still be absent
from its DOM. Each candidate is rendered once from the current snapshot and sent
when its version is at or past the cursor OR its digest differs from what the log
holds, with a MISSING entry counting as "send". Nothing is special-cased by kind.

That subsumes what used to be three branches. There is no per-session repaint
step: a tab panel is simply a surface in `open`, so its nodes are candidates like
any others. And a popup needs no branch either — its nodes are in `open`, and a
body repaint replaces `#dashboard` only, while the popup host lives in
`theme.chrome` outside it, so an open dialog is never disturbed.

**Why one scalar cursor is enough despite per-client visibility.** A client may be sent nothing at
all for a change inside a tab it is not looking at, and still have its cursor advanced past that
change. That is sound because of an invariant every reveal path holds:

> **Every reveal is an unconditional complete fill.** `swapHost` renders the arriving surface whole
> and inner-patches the host; a flip's `Varying` inserts the whole branch. Neither consults a
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

So both broadcast topics are subscribed **unbounded** (`Server`'s shared patch topic,
`StateStore.changes`), and for a second reason as well: `Topic.publish1` sends to each
subscriber's channel in turn and blocks on a full one, so a bounded subscription lets
one slow browser stall the publisher for *everyone*. On the shared topic — one
multiplexed topic across all slugs — that is every viewer of every dashboard; on
`StateStore.changes` it is worse, because blocking there stalls `HaFeed.pump` and the
store stops updating at all.

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

**Subscribe before reading the snapshot.** `openingPatches` and the shared
subscription are nested in that order inside the stream, so a change published
between them is queued for the connection rather than published to nobody. Erring
the other way is safe: a change caught by both arrives once in the opening paint and
once as a patch, and a patch is an idempotent morph.

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

## Not in scope: the HA → server side

`subscribe_entities` has no "since" parameter, so a reconnect always reopens with the
full entity set — and that is already free, because `StateStore` publishes only
entities whose content actually changed (`EntityState.sameContent`). The reconnect gap
is lossless BY CONSTRUCTION rather than repaired after the fact, and that is recorded
at the three places which guarantee it: `HaFeed.runConnection`, `StateStore`'s
`Ingest.Remove`, and `ServerApp.watchRegistryEvents`. Re-deriving is strictly stronger
than replaying, since it also covers changes no event was ever seen for. So the
browser↔server resume has no HA↔server counterpart to build, and should not grow one.

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
3. **A cursor older than the retention window repaints rather than resuming.**
   Unit-covered via the horizon; what a browser adds is that the repaint restores a
   correct DOM from an arbitrarily stale one.

## Deferred

**The per-viewer render memo, and a cache across batches.** The per-session pass
is gone (ADR 0002): everything is rendered once per slug and addressed by a tag,
so the payload this section used to be about — a tabbed dashboard re-sending its
visible panel on every reconnect — is no longer re-sent at all.

What survives of the idea is smaller and sharper. Exactly one render can differ
between viewers: a flip placing a branch whose subtree mounts a client-selected
member. It is performed per connection, un-memoised, so K viewers of such a flip
cost K renders.

The **batch-scoped memo** that fixes that is not a separate piece of work: it is
the other half of rendering lazily, which is the visibility work in
docs/adr/0012-one-pass-addressed-per-client.md (W13). Skipping a render only pays if you can decide
"nobody can see this" BEFORE rendering, and once you can, the memo is what stops
two viewers of the same variant rendering it twice. It needs no eviction policy —
it lives and dies with the published item, by ordinary reachability — and it must
NOT be keyed on the store version, which is a global counter: one humidity sensor
would invalidate every node on every dashboard.

Separately deferred, measurement-gated: a **cache across batches**,
`(state, node, variant) -> HTML`. That one wants `SoftReference`; a plain
`WeakReference` entry dies at the next GC regardless of memory pressure, so it
would cache essentially nothing.

**Advancing a client's cursor on quiet ticks.** A batch that emits nothing sends
no cursor, so a long quiet stretch leaves a client claiming an old version and a
later reconnect re-sends a superset — harmless, and deliberately the safe
direction to err in. The fix is a nudge: after N quiet batches, or on a timer,
push the cursor alone. Not free — every non-`_` signal rides back on every
subsequent request — so it is a trade against how chatty a given dashboard is,
not an obvious win.

**`data-signals__ifmissing`** — investigated and **ruled out for this design**, not
merely deferred. The modifier exists and behaves as documented; the constraint is
one Datastar does not spell out:

> `__ifmissing` initialises a signal only if nothing has REFERENCED it yet. A
> read creates the signal (as `""`), after which the seed correctly declines.

A tabs bar reads `$ui_<id>` (its active-tab highlight) and renders BEFORE the
panel that would seed it, so the seed never fires and the signal stays `""` —
which the URL mirror then faithfully writes, wiping a deep link's selection. That
was the symptom: correct on load, gone after the stream. Pinned by
`DatastarMorphContractSuite`.

Seeding from the BAR instead **would** work — `__ifmissing` declines only for a
signal something has already read, and a parent's seed reaches its children's
readers. And it would **not** cost digest suppression: variants have had entries
of their own since the per-variant `Fragment` (ADR 0012).

What rules it out is a third thing: `{{bakeIndex}}` in a `self` makes every tabs
node variant-bearing, and then EVERY path that renders one by id must know the
viewer. `Patches.resume` does not — it renders candidates through
`renderLogged(id, states)` with no selection, so it would hand a tab-1 client a
bar rendered at the default index. That is fixable (resume already carries the
viewer for its mount fills), but it is a wider change than the one thing
re-assertion still gets wrong: a tab click racing a patch already in flight.

## Rejected along the way (still guarding the design)

- **A server-side mirror of each client's DOM.** Per-client server state instead of a
  client-carried value, and the bookkeeping is most of the work — tens of KB per
  client, recorded on every broadcast. The one log per slug deliberately cannot say
  what any individual browser received; the cursor is how that question gets
  answered without keeping the answer.
- **Persisting the session id across the reconnect.** Cheaper than it looks, now that
  `conn` is known to survive the refetch — but the value that would have had to
  outlive the connection was a per-session *cache*, so the grace window/reaper/cap
  came back unchanged, and it would not have removed the cursor. Moot since that
  cache stopped existing: a `Session` now holds only its slug, its open set, and a
  control queue, none of which is worth carrying across a drop.
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
- A resume is bounded by retention: past `FragmentLog.Retention` a client repaints.
- The server holds no per-client state between connections, so scaling viewers costs
  one unbounded subscription and one variant render each — the latter being what the
  deferred variant log would collapse.
