# Plan: resume an SSE reconnect instead of repainting the whole body

**Status: in progress.** The transport the design rests on is verified end-to-end in a
real browser (see "Evidence"), not inferred from reading the pinned Datastar build.
The server-side half is built and committed (`9e3eead` … `f70689d`), including the pure
resume core. Building it corrected this document five times, and the corrections are kept
rather than tidied away because each was found by a sharper question than the one before:
the tombstone framing (wrong twice, in opposite directions), the missing log identity, the
"nothing grows so no eviction" claim, and the subtree a resume sent twice. Remaining:
steps 3–5, the client-visible half.

## Why

Every SSE `(re)connect` pushes `initialRepaint`: the dashboard's entire body as one
`datastar-patch-elements` (`Server.scala`, `sseStream`). The cost is paid on the case
that happens most on mobile — Datastar's `openWhenHidden` defaults to false and its
handler aborts the fetch on any `visibilitychange`, refetching when the document
becomes visible, so **backgrounding a phone tab closes the stream and returning
reopens it**. Every glance at the dashboard is a full-body render, transfer, and morph,
even when nothing changed while the phone was in a pocket.

Two smaller reasons: a full-body morph is more disruptive than targeted patches (focus,
scroll anchoring, in-flight CSS transitions), and it is the largest single payload the
runtime ever sends.

Be honest about the size of the win: on a fast LAN this is invisible. It is a
mobile/slow-link optimization plus a DOM-stability improvement, not a bug fix.

### What `initialRepaint` is actually compensating for

The live path narrows work in two independent stages, and understanding which one is
missing information is the whole design:

1. **A reverse index picks the nodes.** `Renderer.componentsFor(entityId)`
   (`mainIndex.byEntity`) maps an entity to the node ids bound to it;
   `Patches.diffRequest` adds the dynamic groups whose predicate the change could move
   it in or out of, plus this session's open-surface nodes. Only those are rendered
   (`renderNodeById`). Work per change is proportional to how many cards mention that
   entity — not to dashboard size. There is **no whole-dashboard recompute**.
2. **A cache decides whether the render is worth sending.** Per node id, the
   last-pushed HTML; a byte-identical re-render is suppressed. Not redundant with stage
   1 — an entity's `last_updated` moves, an attribute changes that this card does not
   display, or a value rounds to the same text through its JSONata transform.

The catch is that stage 2's cache is a **broadcast log, not a per-client mirror**:
`sharedPatchPublishers` renders once per slug and diffs against one cache shared by
every client viewing it. So changes a backgrounded phone misses are still absorbed by
that cache — they *were* broadcast, to whoever was listening. Re-diffing after the
reconnect therefore yields **nothing**: from the cache's point of view the DOM is
already current. That is why the full repaint is the only safe move today — the
information about what this client missed does not exist anywhere on the server.

Everything below is about supplying that missing information without keeping per-client
state.

## The shape

Three client-carried values, all riding the signal channel that already works, plus one
server-side structure:

| value | question it answers | on mismatch |
|---|---|---|
| `dashboardHash` | is this still the same compiled dashboard? | full page **reload** |
| `logId` | is this cursor even comparable? | body **repaint** |
| `storeVersion` | how far behind is this client's state? | body **repaint** |
| (server-side) fragment log | what changed since then — and is it still retained? | body **repaint** |

The server keeps **no per-client memory** between connections. The client carries the
cursor; the server keeps one versioned log per slug, shared by every client.

`logId` was missing from the first draft of this design and is not optional. A version
number is meaningless outside the log that issued it: a restarted server resets the
counter to 0, and a renderer hot-swap mints a fresh cache — in both cases a client
holding version 500 would be compared against a log where 500 names nothing. The log
carries a UUID minted with it, and a cursor quoting a different one is rejected outright
rather than reasoned about. This is a strictly different question from `dashboardHash`,
which is stable across restarts BY DESIGN: `logId` decides *is this cursor comparable*,
`dashboardHash` decides *does the browser need a full page reload*. Two questions, two
values; neither can answer the other's.

### `storeVersion` — a clock, nothing more

A monotonic counter [[StateStore]] owns, bumped once per applied batch that changed
anything. Batch granularity is a STRUCTURAL guarantee, not a hope: a coalesced HA frame
arrives as one fs2 chunk and is applied in one `ref.modify` (`applyEntities`), so one
version covers one HA event-loop tick.

Its only job is to **stamp fragments** (below). It deliberately does NOT carry a
per-entity `changedAt` map or a removal stamp: with a versioned fragment log those are
dead weight, because the log answers the same question one layer closer to the wire.

#### Why a store counter and not HA's `last_updated`

The obvious clock is the timestamp HA already gives us (`EntityState.lastUpdated`,
parsed at ingest). It was the first design and it is wrong for three reasons:

1. **Removals have no timestamp.** An `r` frame is a bare list of entity ids. Stamping
   with the server clock and comparing against HA epoch values puts two clocks in one
   ordering, and skew silently drops or duplicates deletions.
2. **`merge` can keep a stale timestamp.** A delta carrying neither `lu` nor `lc`
   inherits `prev.lastUpdated`, so a real change becomes invisible to a timestamp
   cursor. HA core does stamp `last_updated` on every state write, so this is unlikely —
   but it is our fallback's behaviour, not a guarantee HA gives us, and the failure is
   silent and permanent.
3. **It overloads a field with a job it isn't doing.** `lastUpdated` exists for the
   recency guard (`EntityState.stale`), which stops a reconnect's full set clobbering a
   fresher delta. That is a different question from "has this client seen it".

A store-owned counter is monotonic by construction, needs no clock, and is bumped by
the act of applying a change regardless of what HA said about it.

### The fragment log — the server-side half

The per-slug diff cache gains a version, turning it from "what did we last broadcast"
into "when did each fragment last change":

```scala
case class Stamp(version: Long, millis: Long)   // logical clock + wall clock
case class Fragment(html: String, version: Long)

enum Mutation(val version: Long, val millis: Long):   // last STRUCTURAL fact about a node
  case Gone(at: Long, wall: Long)                     // its element was deleted
  case Placed(gid: String, entityId: String, at: Long, wall: Long)  // belongs at its current position

case class FragmentLog(
    id: String,                        // this log's identity; see `logId` above
    fragments: Map[String, Fragment],  // nodeId -> latest html + when it changed
    mutations: Map[String, Mutation],  // nodeId -> where it is
    horizon: Long                      // oldest version `mutations` is COMPLETE for
)

def since(v: Long): Option[Resume]     // None => cannot be told, repaint
```

`fragments` answers *what does this node contain*; `mutations` answers *where is
this node* — the changes that cannot be expressed as a morph of an element the
client already has. A resume with cursor V pushes every `Fragment` at `>= V`,
then applies every `Mutation` at `>= V`. Three consequences:

1. **No re-rendering on resume at all.** The HTML is already in the cache. An
   entity-level cursor plus a re-render (the earlier draft of this plan) would redo work
   the server had already done and discarded.
2. **Every membership change costs patches proportional to what moved,** not to group
   size — see the next section, which is where this design changed shape twice.
3. **Only the LATEST html per node is kept, which is correct.** A client that missed
   five changes to one card wants the fifth, not a replay of all five. Truncating to
   latest is the semantics, not a compromise.

**Why `>=` and not `>`.** The cursor is pushed alongside a patch batch, and one store
version can produce several batches — a coalesced HA frame applies as one `ref.modify`
but publishes one `StateChange` per entity, each diffed separately. So a client can hold
version V having seen only part of V. Re-sending the whole of V is idempotent (every
patch is a morph or a fresh render) and costs one extra batch; missing half of V would
be silent and permanent.

**The invariant that makes it sound:** an entry's version means "this fragment last
changed at store version N". A node with *no* entry has not changed since the log was
created, and every client's body — server-rendered from current state at page load —
already has it. So absence correctly reads as "you are up to date", and there is no
bootstrapping hole.

### Only the latest meaningful change: three levels of collapse

A resume should carry each visible change once, and the log collapses redundancy at three
levels. The first two fall out of the data structure; the third had to be asked for.

1. **Per node.** Both maps are keyed by node id with latest-wins, so a thousand alternating
   ticks on one element leave exactly one entry (tested). Fragments also only advance on a
   REAL html change, because the live diff never stores a byte-identical re-render.
2. **Across the two maps.** A node with a current `Mutation` is emitted as that mutation and
   NOT also as a morph — but the insert carries the fragment's LATEST html, so a `Placed` at
   v=20 followed by a content tick at v=30 is one insert containing the v=30 content. It is
   cursor-sensitive in the right direction: a client whose cursor is 25 was present for the
   insert, so it correctly gets only the morph.
3. **Across the tree** (`coveredByAncestor`). A node whose ancestor is being sent with a
   version `>=` its own is dropped entirely — fragments and mutations alike — since the
   ancestor's cached html embeds the whole subtree. Correctness never rested on this
   (ascending version order makes the newer ancestor land last and win), but re-sending a
   subtree is exactly the cost this plan exists to remove.

Level 3 is the one worth a warning, because getting it wrong is invisible. The predicate must
be a STRICT ancestor test: a version that also matches the node itself makes every fragment
suppress its own emission, so a resume silently sends nothing at all, with no error anywhere.
There is a test named for that alone. `since` owns the rule for both maps so there is one
place it can be wrong, and placements pass the CHILD node id — which reaches the group and
every container above it — rather than the group id.

What does not collapse, and cannot: a node that changes A→B→A across the absence yields a
morph to `A` byte-identical to what the client already holds. Detecting that needs per-client
DOM knowledge, the thing this design refuses to keep, and idiomorph treats it as a no-op.

### Eviction: `fragments` self-limits, `mutations` does not

Keying by **node id** rather than by event is what keeps the log small — a node has one
latest content and one latest structural fact however many times it churned, so a
hyperactive sensor cannot flood it. That much was the point of the earlier "no watermark"
claim, and for `fragments` it is sufficient: they describe nodes that currently EXIST, and
both `removed` and `invalidateWhere` drop entries, so the map tracks the live dashboard.

`mutations` is the exception, and the claim was wrong about it. A `Mutation.Gone` for a
member that left and never returned has nothing to evict it, so the map accumulates one
entry per entity that has EVER been a member of any group. That is bounded by ENTITY COUNT,
not dashboard size — and it grows with elapsed TIME rather than with dashboard complexity.
The failure is undramatic but real: a `dynamic` group over "every light that is on" will,
over a week, name every light in the house, and those entries name versions no live client
could still be sitting on.

So mutations are **aged out** — `FragmentLog.Retention`, currently 1 hour — and the log
carries a **`horizon`**: the oldest version for which `mutations` is complete, raised past
everything evicted. A cursor below the horizon is refused and repaints.

**Why age and not an entry cap.** The real question is "how long can a client be away and
still be worth resuming?" — a backgrounded phone tab is minutes to hours, and past that its
connection is long dead. Aging makes the retained set mean exactly that, where a count cap
is only a proxy for it. And a cap is redundant anyway: the id-keyed map already bounds burst
churn, since re-touching a node replaces its entry rather than adding one. One knob instead
of two.

**Retention needs a wall clock, which is why `Stamp` carries two.** `version` orders
everything and is the only clock any correctness argument rests on; `millis` only ages
mutations out. They are never compared to each other and never used to order the same thing,
so this does NOT reintroduce the two-clocks-in-one-ordering problem that ruled out HA's
`last_updated` as the cursor (above). A clock step — NTP, a host waking from suspend — can
widen or narrow a retention window but cannot corrupt a cursor comparison. The clock is read
by the caller once per diff and passed in, so the log stays pure.

Two design notes worth keeping:

- **The horizon is enforced by the type, not by a convention.** `since` returns
  `Option[Resume]` — `None` means repaint — rather than exposing a `resumable` predicate a
  caller is trusted to check first. Forgetting that check would leave a client holding a
  ghost element indefinitely, with nothing observable at the moment of the mistake, which is
  precisely the class of bug this whole plan is trying not to introduce.
- **Eviction is per-log and therefore per-slug**, and a log dies on renderer swap anyway, so
  there is no reaper and no cross-connection bookkeeping — the property that made the
  per-client alternatives (a) and (b) unattractive.

**FUTURE — retention by live cursors.** The age bound is a blunt stand-in. The precise rule
is to truncate below the OLDEST cursor any live connection still holds: `Sessions` is already
keyed by `conn`, so each could report its last-sent version and the log could evict
everything below their minimum — retaining exactly what is still reachable and no more. Two
caveats, and the second is the reason the age bound must survive rather than be replaced.
It reintroduces per-connection server state, which this design otherwise avoids — acceptable
here only because it would serve RETENTION, never correctness, which is what distinguishes it
from the rejected per-client mirror in (a)/(b). And a wedged or hung connection would pin the
log open indefinitely, so the real rule is `min(live cursors)` **clamped** by the age bound,
not one or the other.

**Hot-path cost is a point lookup, unchanged.** The live path only ever does
`log.html(nodeId)` + compare: O(1) before, O(1) after, one small allocation per changed
node. The `version >= V` **scan is resume-only** (once per reconnect, a few hundred
entries — microseconds), so it needs no index. Two rules keep it that way: a named
`Fragment` type rather than a `(String, Long)` tuple whose second element's meaning
lives in a comment, and NOT splitting into parallel `Map[nodeId, String]` +
`Map[nodeId, Long]` (two structures to hold in step, two lookups per write — the "one
mechanism, not two" smell). The resume must `.get` the log once and scan it OUTSIDE the
`Ref.modify`, so a reconnect never serializes against the live diff path.

### Structural changes: one mutation per node

This section changed three times, and the sequence is instructive because each wrong
version was wrong in a *different* way. First: "emit a tombstone at every prune site",
named as the main implementation risk. Then, on reading the code: "no tombstones anywhere,
re-render the group instead". Then: tombstones for departures, re-render for arrivals. Each
was corrected by asking a sharper question than the last, and the final shape is smaller
than all three.

The distinction that actually matters is **not** which sites touch the cache — it is which
patches can be REPLAYED.

**Most prune sites are cache INVALIDATIONS, not removals.** `repaintGroup` and
`flipStateGroup` each drop their children's entries *while morphing an ancestor whose
fresh HTML re-supplies those children*. The prune exists so a later diff cannot suppress
a member fragment against a pre-repaint entry — it says nothing about the DOM. A
tombstone here would be actively harmful: it would replay as a `remove` of an element the
ancestor's morph had just legitimately restored.

**Every real removal is a dynamic-group child.** Exactly one site in the runtime emits
`Patch.Remove` (`Patches.renderMembershipChange`, the per-entity path), and its selector
is always `#{gid}_{entity}` — `Renderer.dynamicChildId`, a child *inside* the group root
`#gid`, which is also where the matching `insert` appends. So the complete set of elements
the server can ever delete is "some children of some dynamic group".

**A `remove` replays; an `insert` does not.**

- A `remove` is **idempotent** — Datastar resolves the selector with `querySelectorAll`,
  so removing an absent id matches nothing (the live per-entity path already depends on
  this). It replays verbatim, needing no knowledge of the client's DOM.
- An `insert` is **position-dependent**: `before` a DOM neighbour that may itself be gone
  by the time an absent client returns. Recomputing the anchor needs the client's DOM
  ordering — the one thing this design refuses to track.

The resolution is to make the insert **self-repairing** rather than trying to replay it:
`Mutation.Placed` emits `remove` AND `insert` for the node, which is correct whatever the
client's DOM holds — element absent, present in the right place, or present in the wrong
one. "Put this element here" needs no knowledge of where it was.

That collapses the two records into one, and the reason is a modelling one rather than an
optimization: a node cannot be both gone and present, so parallel `tombstones`/`arrivals`
maps made an invalid state representable — and that state is exactly what a
leave-then-rejoin produced. One sum type with latest-wins makes the rejoin ordinary
(`Placed` replaces `Gone`) instead of a case to handle. Both collapse directions are
tested.

**The one ordering rule left** is inherent rather than bookkeeping: placements are emitted
**descending by current position**, so each insert's anchor provably exists — it is either
a member the client already had, or one placed a moment ago. Ascending fails, because a
node's anchor can be a later node not yet inserted. Morphs go first (ascending by version,
since a container's cached HTML embeds its children's).

**One `Placed` skip lives in the resume**: an entity that is no longer a member. Unreachable
in practice — the latest mutation would then be `Gone` — and kept as a defence, since the
alternative is inserting an element the group's predicate says does not belong. The other
skip, an element an ancestor's HTML already contains, is not special to placements at all:
it is level 3 of the collapse above, applied to both maps inside `since`. Ancestry is a
string-prefix test, since ids are location-derived (`Dashboard.pathId`: `c`, `c_0`, `c_0_1`);
the trailing `_` is what keeps `c_1` from matching `c_10`.

**The subtree-authority invariant.** A node's fresh HTML is authoritative for everything
under it, so stamping it supersedes that subtree's mutations — a stale `Gone` would delete
a member the ancestor's HTML legitimately restored, and a stale `Placed` would insert one it
already contains. Enforced in `invalidateWhere`, which every prune site uses while setting
the subtree root in the same operation.

Two removals that look like exceptions and are not: an entity vanishing from HA triggers
a registry re-evaluation and therefore a renderer swap, which mints a new `logId` and
rejects the cursor outright; and a popup close is a per-session control patch that dies
with the connection (step 5).

#### Note for later: positional changes on the LIVE path

Member order is currently hardcoded ascending by entity id (`sortBy(_._1)`, in
`Renderer.renderDynamic` and `dynamicMembers`). When member sorting becomes
author-controlled, a member can change POSITION with no membership change — impossible
today, which the live path quietly assumes.

**No new mutation kind is needed.** `Mutation.Placed` already means "this element belongs at
its current position", and its remedy (remove + insert at the current anchor) is identical
for an arrival and a move — which is why the resume side is already done. A separate
`Mutation.Position` would only be warranted if the resume remedy differed, and it does not.
Note also that the descending-order argument assumes only that server and client agree on
SOME total order over members; nothing in it depends on that order being by entity id.

What *is* missing is on the live diff path, and it is a detection change, not a protocol
one:

- Today a reorder with an unchanged member SET lands in `renderMembershipChange` with
  `added`/`removed` both empty, so `churn == 0` and it falls through to `repaintGroup` — a
  whole-group morph. Correct, but the heavy path.
- The optimization is to recognise "same set, different order" and move the **minimal** set
  of members: that is `n` minus the longest increasing subsequence of target positions, the
  standard list-reconciliation result. Each moved member emits remove + insert and records
  `Mutation.Placed`, so resume needs no change.
- Watch for a sort key derived from live state (brightness, `last_changed`): it makes
  reorders fire on ordinary value ticks, so the in-place-morph fast path has to test
  position before assuming a content-only update. A STATIC key (`friendly_name`, an
  author-given order) only reorders on membership change and is much cheaper — worth
  offering that shape first.

### `dashboardHash` — is it still the same dashboard?

The hash of the **evaluated `{cards, card}` JSON**, not of the `.pkl` source. Source
hashing is simultaneously too sensitive and not sensitive enough: a comment or
formatting change in `lib/components.pkl` would refresh every browser for nothing, while
a change in the dump (`@fh-home` gaining an entity) or an imported lib module would not
register unless the whole `Analyzer.importGraph` is hashed — more inputs, more
fragility. The evaluated JSON is already established as *the contract* in this module
(the wire-format snapshots byte-compare it), captures every input that can affect output
(entry, lib, dump, theme), and is blind to everything that cannot. One hash of bytes we
already produce, once per evaluation, never per request; 12 hex chars of SHA-256, the
same idiom as `fh-home@1.0.0-g<hash>`.

**Why a hash and not a counter:** a counter does not survive a restart, so every add-on
restart on an HA update would refresh every browser even though the dashboards are
byte-identical. A hash is stable across restarts — which is exactly why it cannot double
as `logId`, whose whole job is to NOT survive one.

That produces a deliberate asymmetry between the two remedies, and clients want both:

- **cursor invalid, hash matches** (a server restart, or a renderer swap that changed
  nothing: `logId` differs) ⇒ body repaint, no page reload.
- **hash differs** (an edit, a push, a `DumpRefresh`) ⇒ full page reload, because the
  cards and templates themselves changed and `<head>` may have moved too (theme,
  stylesheets, scripts) — a body morph cannot fix that.

Connected clients are already handled by `reloadRepaints`; the hash exists for the
client that was AWAY during the reload and comes back with a stale everything.

## How the cursor travels: already on the wire

Datastar sends all non-`_`-prefixed signals with every backend action; for GET it
serializes them into a `datastar` query param (`U.set("datastar", F)` in the pinned
build, `exclude: /(^|\.)_/`). The runtime already relies on this for `conn`
(`Server.ConnSignal`).

So the server pushes cursor + hash as signals alongside each patch batch, and they come
back on the next connect for free:

- **First connect after a page load** — no cursor ⇒ full render, exactly as today.
- **Retry / visibility refetch** — the page never reloaded, the signals survive in
  memory ⇒ the cursor names precisely what the DOM holds.

A page reload correctly reads as the first case (the body is server-rendered fresh
anyway), and two tabs carry independent cursors. No SSE `id:` field and no
`last-event-id` handling is required. (`last-event-id` would also work — the build
records and replays it as a header — but it is a second channel for something the signal
already carries.)

Neither signal may be `_`-prefixed, a deliberate exception worth a comment at the emit
site: `_` is exactly the convention for per-connection client state (`_sse`, the
SSE-down banner), and these ARE per-connection client state — but they are the values
that must survive into the reconnect URL, which is precisely what the prefix suppresses.

### Evidence: the mechanism, observed in a browser

Verified by `ResumeSpikeSuite` (Playwright) against the runtime as it stands, with no
production change — because `conn` is ALREADY a server-pushed-only signal, patched via
`patchSignals` on connect and never declared in `data-signals`. Driving Datastar's real
visibility path (override `document.hidden`, dispatch `visibilitychange`, restore):

```
request 0 @49ms:   /sse/dashboard/home/patch?datastar={}
request 1 @1095ms: /sse/dashboard/home/patch?datastar={"haDown":false,"conn":"c4cfa822-…"}
```

Three load-bearing facts, now observed rather than inferred:

1. **The visibility refetch happens** — request 1 lands one second after the
   backgrounding, on the restore, same page, no reload.
2. **A server-pushed-only signal survives it and rides the URL.** `conn` was never
   declared client-side; it exists only because the server patched it. That is exactly
   the cursor's lifecycle.
3. **`_`-prefixed signals are excluded.** `_sse` is in the store at that moment and
   absent from the param.

Two sequencing traps cost a false negative each, and both would recreate themselves in
any test built on this:

- Backgrounding the tab too early. `TestServer.awaitLive` gates on the SERVER's
  subscriber count, which can be satisfied *before* the browser has applied anything —
  the first spike hid the tab 2ms before the browser's first request left, so `conn` had
  never arrived and its absence looked like a design failure. Gate on an applied patch
  (emit a value, assert it in the DOM).
- Counting requests without timestamps: two requests at page load look identical to
  load-plus-refetch. Record when each fired relative to the toggle.

Also: `BrowserContext.setOffline(true)` does NOT tear down an established SSE stream in
Chromium (the first attempt observed no reconnect for 25s). The visibility path is the
one to drive.

## Work

0. ~~**Reduce the committed step 1.**~~ **Done** (`9e3eead`). `a9dbc10`'s
   `StoreState.changedAt`/`lastRemoval` and `StateStore.changedSince`/`Since` are gone
   with their tests; `version` remains as the clock. `StateStore.current` now hands out
   the snapshot and its version together, so a fragment cannot be stamped with a version
   its HTML does not reflect.
1. ~~**Version the fragment cache.**~~ **Done** (`9e3eead`). `FragmentLog` replaces
   `Map[nodeId, String]` in the per-slug pass and in `Session.lastRendered` (one diff
   contract for both), and the per-slug log is minted with a fresh `id` inside
   `publisherFor`'s `switchMap` — i.e. per renderer swap, which is exactly the scope a
   cursor is valid for.
2. ~~**Emit a tombstone at every prune site.**~~ **Done** (`9e3eead`, `09b61da`) — but at
   the one site that is a real DOM removal, not at every prune. See "Removals" above: a
   departure is tombstoned and replays verbatim; only an arrival marks the group
   `structural`. The risk this step was flagged for was real but misattributed — it lived
   in a misreading of the prune sites, not in the sites themselves. The real hazard turned
   out to be a rejoining member, closed by the subtree-authority invariant.
3. **Hash the evaluated dashboard** where renderers are built/swapped, and push it as a
   signal alongside `logId` and `storeVersion`.
4. **Resume path in `sseStream`.** The pure core is DONE — `Patches.resume` returns
   `Option[List[ServerSentEvent]]`, `None` meaning "repaint", with `ResumePatchesSuite`
   covering anchor selection and the skips. What remains is the wiring: with a
   matching hash and a cursor quoting the current `logId`, call it and push the result
   instead of `initialRepaint`. Any doubt — no cursor, unparseable, hash mismatch, `logId`
   mismatch, cursor above the current version or below the horizon, unknown slug — falls
   back to today's full repaint. **The full repaint stays the default**; resume is the
   narrow, provable case.
5. **Per-session fragments** (open surfaces, bake owners) die with the connection and
   have no cross-connection log — `Session.lastRendered` is a `FragmentLog` for uniformity
   but its versions are never resumed from. Render those fresh for the new session; they
   are small and the open set is rebuilt from cookies anyway.

**Version ordering is load-bearing** (step 4). A container's cached HTML embeds its
children's, so a parent fragment stamped v=25 applied AFTER a child stamped v=30 would
revert that child. Fragments must be emitted ascending by version. Structural groups are
immune (rendered from current state), which is why they can be emitted in any order.

Notably absent, and this is the point: no session survives the disconnect, no grace
window, no reaper, no per-client mirror.

## What must be proven before this is worth merging

1. ~~The cursor signal really does ride the retry URL.~~ **Done** — see "Evidence".
2. **A resumed DOM is identical to a repainted one.** The failure mode is silent and
   ugly: the server believes the browser is current, suppresses the patch, and the user
   sees stale values indefinitely. Drive a drop/resume and assert the resumed DOM equals
   the repainted one.
3. **A member LEAVING a dynamic group across a disconnect is corrected** — and by a
   `remove` patch, not a group morph, since that saving is the point. Note the trap:
   membership can change from a pure content change (an attribute crossing a predicate)
   with nothing added or removed anywhere, so testing only entity add/remove would miss
   it. Test the per-entity path (small churn on an established group) AND the wholesale
   path — they record the group differently. The rejoin cases are already covered by
   `FragmentLogSuite`; what is still unproven is that this holds through a real
   drop/resume in a browser.
4. **A restart repaints but does not reload** (hash stable, `logId` differs), and an edit
   reloads (hash differs).
5. **A parent and a child changing at different versions resume in the right order.** The
   silent failure is a stale container reverting a fresh child; only ordering prevents it.
   Covered at the unit level (`FragmentLogSuite`); still unproven through a real browser.
6. **A cursor older than the retention window repaints rather than resuming.** Covered at
   the unit level via the horizon; what a browser test adds is that the repaint actually
   restores a correct DOM from an arbitrarily stale one.

## Alternatives considered

**(a) A server-side mirror of each client's DOM.** Keep `Session.lastRendered` alive
across the drop (grace window + reaper + cap) and diff against it on resume. Rejected:
per-client server state instead of a client-carried value, and the bookkeeping is most of
the work. It also runs into the shared/per-session split — `lastRendered` covers only
per-session fragments, while shared main-page fragments are diffed once per slug against
a cache shared by all clients, which by construction cannot say what any individual
browser received. Recording broadcasts per session adds tens of KB per client.

**(b) Persisting the session id across the reconnect.** Reconsidered *knowing* that
`conn` survives the refetch (proven above), which makes it far cheaper to build than when
(a) was rejected — no new transport, and `Sessions` is already keyed by `conn`. Still
does not pay: the value that must outlive the connection is the *cache*, so the grace
window/reaper/cap come back unchanged, and it answers truthfully only for the minority of
the body that is per-session. It would also not remove the cursor, leaving two mechanisms
where one does. (If ever built: an unknown `conn` must degrade to a fresh session, not an
error — same posture as an unparseable cursor.)

**(c) Entity-level cursor + re-render on resume.** The previous draft: resolve "which
entities changed since V" through `StateStore.changedAt`, map to nodes via the reverse
index, re-render, push. Superseded by the fragment log, which is strictly better —
no re-render (the HTML is already cached), and it fixes the departing-member hole that
this approach could only paper over by repainting every dynamic group, because a
predicate tested against *current* state cannot see a member that has left. Its
`changedAt`/`lastRemoval` are what step 0 deletes. A per-client `Map[gid, List[entityId]]`
of rendered member ids was also considered as a cheaper fix for that hole; the fragment
log makes it unnecessary.

**(d) Hashing the Pkl source** rather than the evaluated output — see `dashboardHash`
above for why that is both too sensitive and not sensitive enough.

**(e) Marking a group structural for EVERY membership change**, rather than only for
arrivals. Simpler — one mechanism instead of two — and it was briefly the design. Rejected
because it re-renders and re-transmits a whole group when a single member leaves, which is
the exact payload cost this plan exists to eliminate, on the exact link (slow mobile) that
motivates it. Departures are idempotently replayable, so paying a group morph for one is
pure waste. The two mechanisms are justified because they answer genuinely different
questions — see "Removals" — but note that this is the one place the design carries two
where one would do, and the burden is on the arrival case to stay narrow.

**(f) Tombstoning at every prune site** — the plan's original step 2. Rejected: most prune
sites are cache invalidations whose subtree is being re-supplied by an ancestor morph in
the same operation, so a tombstone there deletes a live element. Only the one site that
emits `Patch.Remove` gets one. Worth keeping on file because "prune the cache" and "the
element is gone" look like the same event and are not.

## Not in scope

The HA→server side. `subscribe_entities` has no "since" parameter, so a reconnect always
reopens with the full entity set — and that is already free, because `StateStore`
publishes only entities whose content actually changed (`EntityState.sameContent`).

That is not just an argument in this document: the reconnect gap being lossless BY
CONSTRUCTION (rather than repaired after the fact) is recorded at the three places that
guarantee it — `HaFeed.runConnection`, `StateStore`'s `Ingest.Remove`, and
`ServerApp.watchRegistryEvents`. Re-deriving is strictly stronger than replaying, since
it also covers changes no event was ever seen for. So the browser↔server resume designed
here has no HA↔server counterpart to build, and should not grow one.
