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

### The fragment log

The per-slug diff cache carries a version, turning "what did we last broadcast"
into "when did each fragment last change":

```scala
case class Stamp(version: Long, millis: Long)      // logical clock + wall clock
case class Fragment(html: String, version: Long)

enum Mutation(val at: Stamp):                      // last STRUCTURAL fact about a node
  case Gone(stamp: Stamp)                          // its element was deleted
  case Placed(gid: String, entityId: String, stamp: Stamp)   // belongs at its current position

case class FragmentLog(id: String, fragments: …, mutations: …, horizon: Long)
def since(v: Long): Option[Resume]                 // None => cannot be told, repaint
```

`fragments` answers *what does this node contain*; `mutations` answers *where is
this node* — the changes not expressible as a morph of an element the client already
has. A resume pushes every `Fragment` at `>= V`, then applies every `Mutation` at
`>= V`. Three consequences: **no re-rendering on resume at all** (the HTML is
already cached); membership changes cost patches proportional to what moved, not to
group size; and only the LATEST HTML per node is kept, which is the semantics rather
than a compromise — a client that missed five ticks on one card wants the fifth.

**`>=`, not `>`.** The cursor is pushed alongside a patch batch and one store
version can produce several batches (one `StateChange` per entity, each diffed
separately), so a client can hold V having seen only part of it. Re-sending the whole
of V is idempotent and cheap; missing half of it would be silent and permanent.

**The invariant that makes absence safe:** an entry's version means "this fragment
last changed at store version N", so a node with *no* entry has not changed since
the log was created — and every client's body, server-rendered from current state at
page load, already has it. Absence correctly reads as "you are up to date"; there is
no bootstrapping hole.

**Three levels of collapse.** Per node (both maps are id-keyed, latest-wins, so a
thousand alternating ticks leave one entry). Across the two maps (a node with a
current mutation is emitted as that mutation and not *also* as a morph, but the
insert carries the fragment's latest HTML). And across the tree —
`coveredByAncestor` drops anything an ancestor's cached HTML already embeds, which
correctness never rested on (version order makes the ancestor win anyway) but which
is exactly the cost this design exists to remove.

> Level 3 is the one worth a warning, because getting it wrong is invisible. The
> predicate must be a STRICT ancestor test: a version that also matches the node
> itself makes every fragment suppress its own emission, so a resume silently sends
> nothing, with no error anywhere. There is a test named for that alone.

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
evicted. A cursor below it is refused and repaints. Age rather than an entry cap
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

Two properties worth keeping: the horizon is enforced **by the type** — `since`
returns `Option[Resume]` rather than exposing a `resumable` predicate a caller is
trusted to check first, because forgetting it leaves a client holding a ghost element
with nothing observable at the moment of the mistake. And eviction is per-log and
therefore per-slug, and a log dies on renderer swap anyway, so there is no reaper and
no cross-connection bookkeeping.

> **FUTURE — retention by live cursors.** The age bound is a blunt stand-in. The
> precise rule is to truncate below the OLDEST cursor any live connection holds:
> `Sessions` is already keyed by `conn`, so each could report its last-sent version
> and the log could evict everything below their minimum. Two caveats, the second
> being why the age bound must survive rather than be replaced: it reintroduces
> per-connection server state (acceptable only because it would serve RETENTION,
> never correctness — which is what separates it from the rejected per-client
> mirror), and a wedged connection would pin the log open indefinitely. The real
> rule is `min(live cursors)` **clamped** by the age bound, not one or the other.

**Hot-path cost is unchanged:** the live path only does `log.html(nodeId)` +
compare, O(1) before and after. The `version >= V` scan is resume-only — once per
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

**Per-session fragments are painted fresh, not resumed.** The shared log covers only
what the shared pass renders; a tab panel's contents are per-session and their only
diff cache died with the previous connection. So on the resume path each per-session
ROOT (`Renderer.sessionOwnedMainIds`) is re-rendered and morphed, after the resumed
fragments so it wins over any shared ancestor in the same batch. The repaint path
needs none of this — `renderBody` already bakes those subtrees with this client's
`uiState`.

**A popup the client still has open is restored, not closed.** Its host lives in
`theme.chrome`, outside `#dashboard`, so neither resume nor repaint reaches it; the
client names it in the `popup` signal and it is re-rendered fresh into the host. Only
a claim this dashboard no longer recognises resets the host.

### The first connect carries no signals

`data-init` fires from `<body>` before Datastar has merged the descendants'
`data-signals`, so that one request arrives signal-less. A signals-only read would
render the DEFAULT tab and its repaint would morph the correct first paint away,
dragging the URL mirror down with it. So `Server.Restore` puts the page's view state
(bake selections, open popup) on the `data-init` URL as ordinary query params; every
later request carries live signals, which win wherever both name the same fact. See
ADR 0005 for where per-session state lives.

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

**A variant-keyed log for the per-session pass.** Per-session fragments are painted
fresh on resume, so a tabbed dashboard re-sends its visible panel on every reconnect
(measured: 1762 B of a 9602 B body on a 3-tab demo). Correctness is not at stake,
only payload — and, more interestingly, client-side state: a repaint of an
interactive node resets whatever the browser was holding in it, and this is the one
place we re-send unconditionally without knowing anything changed.

The shape it should take is NOT the obvious one. A **surviving per-client cache**
(keep `Session` alive past the drop, reattach by `conn`) brings back the
TTL/reaper/cap of the rejected alternatives below, plus a handoff race they never
faced: the dropped stream's finalizer can run *after* the new stream attaches, so two
connections briefly share one session — and a `Session` owns a control `Queue` that
each stream *takes* from, so popup patches would go to whichever tab won the race.
A **variant-keyed log** is better: the per-session HTML is not a function of the
client but of `(selected members, open popup)`. Two phones on the same tab render
byte-identical HTML today, separately, into separate logs. Keyed by variant it is
shared exactly as the slug log is — a reconnecting client resumes its variant with
the same cursor, no per-client state exists, and a missing variant degrades to the
fresh paint we already do. It would also delete the duplicate rendering across
clients on the same tab, a standing cost of ADR 0002's shared/per-session split.

**`data-signals__ifmissing`** is the cheaper half of the same problem: a repaint
re-runs the seeds inside the HTML it sends, so a morphed `data-signals="{ ui_x: 0 }"`
overwrites what the client holds. The modifier makes a seed initialize-only. It is
orthogonal to the log — the log stops us re-sending, `__ifmissing` makes re-sending
harmless — and it is **confirmed present in the pinned v1.0.2 bundle**: the signals
plugin reads `mods.has("ifmissing")` and passes it as `ifMissing` to the merge.

## Rejected along the way (still guarding the design)

- **A server-side mirror of each client's DOM.** Per-client server state instead of a
  client-carried value, and the bookkeeping is most of the work. It also runs into the
  shared/per-session split: `lastRendered` covers only per-session fragments, while
  shared fragments are diffed once per slug against a cache that by construction
  cannot say what any individual browser received. Recording broadcasts per session
  adds tens of KB per client.
- **Persisting the session id across the reconnect.** Cheaper than it looks, now that
  `conn` is known to survive the refetch — but the value that must outlive the
  connection is the *cache*, so the grace window/reaper/cap come back unchanged, it
  answers truthfully only for the minority of the body that is per-session, and it
  would not remove the cursor. (If ever built: an unknown `conn` must degrade to a
  fresh session, not an error.)
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
