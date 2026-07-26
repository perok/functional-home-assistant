# Plan: resume an SSE reconnect instead of repainting the whole body

**Status: in progress.** The transport the design rests on is verified end-to-end in a
real browser (see "Evidence"), not inferred from reading the pinned Datastar build.
One increment is committed (a store version counter) and is about to be reduced — see
"Work", step 0.

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

Three values, all client-carried, all riding the signal channel that already works:

| value | question it answers | on mismatch |
|---|---|---|
| `dashboardHash` | is this still the same compiled dashboard? | full page **reload** |
| `storeVersion` | how far behind is this client's state? | body **repaint** |
| (server-side) fragment log | which fragments changed since then? | — |

The server keeps **no per-client memory** between connections. The client carries the
cursor; the server keeps one versioned log per slug, shared by every client.

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
//   cache:      Map[nodeId, Fragment]
//   tombstones: Map[nodeId, Long]      // version at which the node was pruned
```

A resume with cursor V is then: push every `Fragment` whose `version > V`, and a
`remove` patch for every tombstone `> V`. Three consequences:

1. **No re-rendering on resume.** The HTML is already in the cache. An entity-level
   cursor plus a re-render (the earlier draft of this plan) would redo work the server
   had already done and discarded.
2. **Group-member departure is handled by construction.** A member leaving a dynamic
   group is a pruned cache entry; recorded as a tombstone it replays as a `remove`. No
   wholesale group repaint, no per-client member tracking — see "Alternatives", (c).
3. **Only the LATEST html per node is kept, which is correct.** A client that missed
   five changes to one card wants the fifth, not a replay of all five. Truncating to
   latest is the semantics, not a compromise.

**The invariant that makes it sound:** an entry's version means "this fragment last
changed at store version N". A node with *no* entry has not changed since server start,
and every client's body — server-rendered from current state at page load — already has
it. So absence correctly reads as "you are up to date", and there is no bootstrapping
hole.

**Truncation needs a watermark.** Tombstones cannot grow forever, so the log carries
the oldest version for which it is COMPLETE. Trim below it; a cursor below it falls back
to a full repaint. One knob, provable, and unlike the rejected per-client mirror it
bounds one small shared structure rather than N client-sized ones.

**Hot-path cost is a point lookup, unchanged.** The live path only ever does
`cache.get(nodeId)` + compare HTML: O(1) before, O(1) after, one small allocation per
changed node. The `version > V` **scan is resume-only** (once per reconnect, a few
hundred entries — microseconds), so it needs no index. Two rules keep it that way: a
named `Fragment` type rather than a `(String, Long)` tuple whose second element's
meaning lives in a comment, and NOT splitting into parallel `Map[nodeId, String]` +
`Map[nodeId, Long]` (two structures to hold in step, two lookups per write — the "one
mechanism, not two" smell). The resume must `.get` the map once and scan it OUTSIDE the
`Ref.modify`, so a reconnect never serializes against the live diff path.

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
byte-identical. A hash is stable across restarts.

That produces a deliberate asymmetry between the two remedies, and clients want both:

- **cursor invalid, hash matches** (e.g. after a server restart: the store clock reset,
  so `since > version`) ⇒ body repaint, no page reload.
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

0. **Reduce the committed step 1.** `a9dbc10` added `StoreState.changedAt` and
   `lastRemoval`; the fragment log subsumes both. Delete them, keep `version` as the
   clock, and drop `StateStore.changedSince`/`Since` with their tests. Deleting is
   right even though they are written and green — carrying them would leave two
   mechanisms answering one question.
1. **Version the fragment cache.** `Map[nodeId, Fragment]` + tombstones + watermark,
   written where the caches are already updated (`Patches.diff` and the per-slug/
   per-session passes). The store version reaches the diff via the published
   `StateChange`/`DiffRequest`, which is created in the same `ref.modify` that bumps it.
2. **Emit a tombstone at every prune site.** `repaintGroup` and `flipStateGroup`
   currently delete child cache entries with no record. Invisible today because
   `initialRepaint` covers it; under resume, a prune with no tombstone leaves an away
   client holding a stale node **permanently**. This is the main implementation risk and
   it is a small, enumerable set of call sites.
3. **Hash the evaluated dashboard** where renderers are built/swapped, and push it as a
   signal.
4. **Resume path in `sseStream`:** with a matching hash and a cursor at/above the
   watermark, skip `initialRepaint` and push the log's `> V` fragments and tombstones.
   Any doubt — no cursor, unparseable, hash mismatch, below watermark, unknown slug —
   falls back to today's full repaint. **The full repaint stays the default**; resume is
   the narrow, provable case.
5. **Per-session fragments** (open surfaces, bake owners) die with the connection and
   have no cross-connection log. Render those fresh for the new session — they are small
   and the open set is rebuilt from cookies anyway.

Notably absent, and this is the point: no session survives the disconnect, no grace
window, no reaper, no per-client mirror.

## What must be proven before this is worth merging

1. ~~The cursor signal really does ride the retry URL.~~ **Done** — see "Evidence".
2. **A resumed DOM is identical to a repainted one.** The failure mode is silent and
   ugly: the server believes the browser is current, suppresses the patch, and the user
   sees stale values indefinitely. Drive a drop/resume and assert the resumed DOM equals
   the repainted one.
3. **A member LEAVING a dynamic group across a disconnect is corrected.** The tombstone
   path is what makes this work, and it is the case that fails silently if any prune site
   is missed. Note the trap: membership can change from a pure content change (an
   attribute crossing a predicate) with nothing added or removed anywhere, so testing
   only entity add/remove would miss it.
4. **A restart repaints but does not reload** (hash stable, cursor invalid), and an edit
   reloads (hash differs).

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
