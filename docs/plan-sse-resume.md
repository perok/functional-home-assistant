# Plan: resume an SSE reconnect instead of repainting the whole body

**Status: in progress.** The transport the design rests on is verified end-to-end in a
real browser (see "Evidence"), not inferred from reading the pinned Datastar build.
The server-side half is built: steps 0–2 are committed (`9e3eead`), which is where the
mechanism became concrete and three of this document's assumptions turned out to be
wrong in the simplifying direction — no tombstones, no watermark, plus a log identity
the design was missing. Remaining: steps 3–5, the client-visible half.

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
| (server-side) fragment log | which fragments changed since then? | — |

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
case class Fragment(html: String, version: Long)
case class FragmentLog(
    id: String,                        // this log's identity; see `logId` above
    fragments: Map[String, Fragment],  // nodeId -> latest html + when it changed
    structural: Map[String, Long]      // dynamic groupId -> when its MEMBERSHIP moved
)
```

A resume with cursor V is then: push every `Fragment` whose `version >= V`, and
re-render every group in `structural` at `>= V`. Three consequences:

1. **Almost no re-rendering on resume.** The HTML is already in the cache. An
   entity-level cursor plus a re-render (the earlier draft of this plan) would redo work
   the server had already done and discarded. The one exception is the `structural`
   groups, below — bounded by how many groups actually churned.
2. **Group-member departure is handled by construction** — see the next section, which
   is where this design changed shape.
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

**No watermark, because nothing grows.** An earlier draft called for one: tombstones
recorded per event accumulate without bound, so the log would have had to carry the
oldest version for which it is complete and trim below it. Keying by **node id and group
id** instead makes both maps bounded by DASHBOARD SIZE — a node can only have one latest
fragment, a group only one latest membership change. Nothing to trim, no knob, no
below-the-watermark fallback path to get wrong.

**Hot-path cost is a point lookup, unchanged.** The live path only ever does
`log.html(nodeId)` + compare: O(1) before, O(1) after, one small allocation per changed
node. The `version >= V` **scan is resume-only** (once per reconnect, a few hundred
entries — microseconds), so it needs no index. Two rules keep it that way: a named
`Fragment` type rather than a `(String, Long)` tuple whose second element's meaning
lives in a comment, and NOT splitting into parallel `Map[nodeId, String]` +
`Map[nodeId, Long]` (two structures to hold in step, two lookups per write — the "one
mechanism, not two" smell). The resume must `.get` the log once and scan it OUTSIDE the
`Ref.modify`, so a reconnect never serializes against the live diff path.

### Removals: why there are no tombstones

This was the plan's stated main risk ("emit a tombstone at every prune site") and the
reading of the code that produced it was simply wrong. The sites that delete cache
entries — `repaintGroup`, `flipStateGroup`, and the per-entity membership path — are
**not all DOM removals**, and the one that is cannot be replayed anyway.

**The prune sites are cache INVALIDATIONS, not removals.** `repaintGroup` and
`flipStateGroup` each drop their children's entries *while morphing an ancestor whose
fresh HTML re-supplies those children*. The prune exists so a later diff cannot suppress
a member fragment against a pre-repaint entry — it says nothing about the DOM. A
tombstone here would be actively harmful: it would replay as a `remove` of an element the
ancestor's morph had just legitimately restored.

**The one real removal is always a dynamic-group child.** Exactly one site in the runtime
emits `Patch.Remove` (`Patches.renderMembershipChange`, the per-entity path), and its
selector is always `#{gid}_{entity}` — `Renderer.dynamicChildId`, a child *inside* the
group root `#gid`, which is also where the matching `insert` appends. So the complete set
of elements the server can ever delete is "some children of some dynamic group".

**But that removal can't be replayed, so tracking the child ids would not help.** The
`remove` patches come paired with `insert` patches positioned `before` a DOM neighbour —
and by the time an absent client returns, that anchor may itself be gone. Replaying the
pair is unsound in the add direction no matter how precisely the removals are recorded.

So membership changes are recorded per GROUP (`structural`) and the group is **re-rendered
from current state** on resume. A coarser key than the child ids, and strictly more
robust: the fresh HTML omits whatever left and contains whatever arrived, in the right
order, and the morph reconciles the client's DOM to it whatever state that DOM was in.
Correct without knowing what the client held — which is the property the whole design is
after. It is the one place resume renders rather than replays, and it is bounded by the
number of groups whose membership actually moved (usually zero).

Two removals that look like exceptions and are not: an entity vanishing from HA triggers
a registry re-evaluation and therefore a renderer swap, which mints a new `logId` and
rejects the cursor outright; and a popup close is a per-session control patch that dies
with the connection (step 5).

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
2. ~~**Emit a tombstone at every prune site.**~~ **Done** (`9e3eead`), by establishing
   that no tombstone is needed anywhere — see "Removals: why there are no tombstones".
   Membership changes are recorded per group in `structural` instead. The risk this step
   was flagged for was real but misattributed: it lived in a misreading of the prune
   sites, not in the sites themselves.
3. **Hash the evaluated dashboard** where renderers are built/swapped, and push it as a
   signal alongside `logId` and `storeVersion`.
4. **Resume path in `sseStream`:** with a matching hash and a cursor quoting the current
   `logId`, skip `initialRepaint` and instead push the log's `>= V` fragments (in version
   order) plus a fresh render of each `>= V` structural group. Any doubt — no cursor,
   unparseable, hash mismatch, `logId` mismatch, cursor above the current version,
   unknown slug — falls back to today's full repaint. **The full repaint stays the
   default**; resume is the narrow, provable case.
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
3. **A member LEAVING a dynamic group across a disconnect is corrected.** The
   `structural` re-render is what makes this work. Note the trap: membership can change
   from a pure content change (an attribute crossing a predicate) with nothing added or
   removed anywhere, so testing only entity add/remove would miss it. Test both the
   per-entity path (small churn on an established group) and the wholesale path — they
   record the group differently.
4. **A restart repaints but does not reload** (hash stable, `logId` differs), and an edit
   reloads (hash differs).
5. **A parent and a child changing at different versions resume in the right order.** The
   silent failure is a stale container reverting a fresh child; only ordering prevents it,
   so it needs a test that would fail if `since` stopped sorting.

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

**(e) Tombstoning removed node ids** rather than marking the group structural. The
natural reading of "replay what the client missed", and what this plan called for until
the code was read. Rejected on two independent grounds, either of which is sufficient:
the prune sites it would hook are cache invalidations rather than DOM removals (so it
would delete live elements), and the one true removal site pairs its `remove` patches with
position-dependent `insert`s that cannot be replayed at all. Recording the group instead
is coarser and strictly more robust — full reasoning under "Removals: why there are no
tombstones". Worth keeping here because the tombstone framing is the obvious one and will
be re-proposed by anyone who reads only the section above it.

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
