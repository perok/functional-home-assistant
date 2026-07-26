# Plan (later): resume an SSE reconnect instead of repainting the whole body

**Status: in progress** — the transport mechanism the whole design rests on is now
verified end-to-end in a real browser (see "Evidence" below), not just read out of
the pinned build.

## Why

Every SSE `(re)connect` currently pushes `initialRepaint`: the dashboard's entire
body as one `datastar-patch-elements`. It exists for a good reason (`Server.scala`,
`sseStream`) — a client whose stream was down missed every patch in the meantime,
and the server has no idea what its DOM still holds, so the only safe move is to
send everything.

The cost is paid on the case that happens most on mobile. Datastar's
`openWhenHidden` defaults to false, and its handler aborts the fetch on any
`visibilitychange`, refetching when the document becomes visible again — so
**backgrounding a phone tab closes the stream and returning reopens it**. Every
glance at the dashboard is a full-body render, transfer, and morph, even when
nothing changed while the phone was in a pocket.

Two smaller reasons: a full-body morph is more disruptive than targeted patches
(focus, scroll anchoring, in-flight CSS transitions), and the transfer is the
largest single payload the runtime ever sends.

Be honest about the size of the win: on a fast LAN this is invisible. This is a
mobile/slow-link optimization plus a DOM-stability improvement, not a fix for a
bug.

## The shape: the client carries a cursor, the server stays stateless

A resume needs one question answered — *what does this client already have?* The
cheap way to answer it is to make the client carry the answer, so the server keeps
no per-client memory between connections.

The cursor is a pair:

```
(rendererGen, storeVersion)
```

- **`storeVersion`** — a monotonic counter the [[StateStore]] owns, bumped on every
  applied batch. Each entity records the version at which it last changed, so "what
  changed since V" is a scan for `> V`. Batch granularity is a STRUCTURAL guarantee,
  not a hope: a coalesced HA frame arrives as one fs2 chunk and is applied in one
  `ref.modify` (`applyEntities`), so one version covers one HA event-loop tick and the
  scan is bounded by frames rather than entities.
- **`lastRemoval`** — a single version stamp: "some entity vanished at V". A cursor
  older than it means repaint. Removals need no per-id map and no tombstone eviction
  rule, because the codebase already committed to treating them coarsely: an entity
  appearing or vanishing changes what the dashboards were BUILT from, so it
  re-evaluates every entry (`StateStore`'s `Ingest.Remove`,
  `ServerApp.watchRegistryEvents`). A removal therefore trips `rendererGen` and
  repaints before any tombstone would be consulted. The one stamp exists only because
  not every `r` frame has a registry event behind it (non-registry entities, a YAML
  platform reload), so it is the cheap backstop for the case the renderer generation
  misses.
- **`rendererGen`** — identifies the compiled dashboard. Everything in the DOM that
  is *not* derived from entity state (a `DumpRefresh`, an edit, an entry rebuild)
  changes this and nothing else, so a mismatch means "give up and repaint".

On resume the server intersects "changed since V" with the entities this dashboard
renders, re-renders those nodes, and pushes only those. On any doubt — no cursor,
unparseable cursor, `rendererGen` mismatch — it falls back to today's full repaint.
**The full repaint stays the default**; resume is the narrow, provable case.

### Why a store version and not HA's `last_updated`

The obvious cursor is the timestamp HA already gives us, since
`EntityState.lastUpdated` is parsed at ingest and sitting right there. It was the
first design and it is wrong for three reasons:

1. **Removals have no timestamp.** An `r` frame is a bare list of entity ids.
   Stamping a tombstone with the server clock and comparing it against HA epoch
   values puts two clocks in one ordering, and skew silently drops or duplicates
   deletions.
2. **`merge` can keep a stale timestamp.** A delta carrying neither `lu` nor `lc`
   inherits `prev.lastUpdated`, so a real change becomes invisible to a
   timestamp cursor. HA core does stamp `last_updated` on every state write, so
   this is unlikely — but it is our fallback's behaviour, not a guarantee HA gives
   us, and the failure is silent and permanent.
3. **It overloads a field with a job it isn't doing.** `lastUpdated` exists for the
   recency guard (`EntityState.stale`), which protects against a reconnect's full
   set clobbering a fresher delta. That is a different question from "has this
   client seen it".

A store-owned counter has none of these properties: it is monotonic by
construction, needs no clock, covers removals and changes with one mechanism, and
is bumped by the act of applying a change regardless of what HA said about it.

## How the cursor travels: already on the wire

Datastar sends all non-`_`-prefixed signals with every backend action; for GET it
serializes them into a `datastar` query param (`U.set("datastar", F)` in the pinned
build, with `exclude: /(^|\.)_/`). The runtime already uses this for `conn`
(`Server.ConnSignal`).

So the server pushes the cursor as a signal alongside each patch batch, and it
comes back on the next connect for free:

- **First connect after a page load** — signals are fresh, no cursor ⇒ full render,
  exactly as today.
- **Retry / visibility refetch** — the page never reloaded, the signal survives in
  memory ⇒ the cursor names precisely what the DOM holds.

A page reload correctly reads as the first case (the body is server-rendered fresh
anyway), and two tabs carry independent cursors. No SSE `id:` field and no
`last-event-id` handling is required. (`last-event-id` would also work — the build
records it and replays it as a header, surviving both the retry loop and the
visibility refetch — but it is a second channel for something the signal already
carries.)

The cursor must NOT be `_`-prefixed, which is a deliberate exception worth a comment
at the emit site: `_` is exactly the convention for per-connection client state
(`_sse`, the SSE-down banner), and the cursor IS per-connection client state — but it
is the one such value that has to survive into the reconnect URL, which is precisely
what the prefix suppresses.

### Evidence: the mechanism, observed in a browser

Verified by `ResumeSpikeSuite` (a Playwright smoke test) against the runtime as it
stands, with no production change — because `conn` is ALREADY a server-pushed-only
signal, patched via `patchSignals` on connect and never declared in `data-signals`.
Driving Datastar's real visibility path (override `document.hidden`, dispatch
`visibilitychange`, restore) yields:

```
request 0 @49ms:   /sse/dashboard/home/patch?datastar={}
request 1 @1095ms: /sse/dashboard/home/patch?datastar={"haDown":false,"conn":"c4cfa822-…"}
```

Three facts, all load-bearing, all now observed rather than inferred:

1. **The visibility refetch happens** — request 1 lands one second after the
   backgrounding, i.e. on the restore, on the same page with no reload.
2. **A server-pushed-only signal survives it and rides the URL.** `conn` was never
   declared client-side; it exists only because the server patched it. That is
   exactly the cursor's lifecycle.
3. **`_`-prefixed signals are excluded.** `_sse` is in the store at that moment and
   absent from the param.

Two sequencing traps cost a false negative each while establishing this, and both
would recreate themselves in any test built on it:

- Backgrounding the tab too early. `TestServer.awaitLive` gates on the SERVER's
  subscriber count, which can be satisfied *before* the browser has applied
  anything — the first spike hid the tab 2ms before the browser's first request left,
  so `conn` had never arrived and its absence looked like a design failure. Gate on an
  applied patch (emit a value, assert it in the DOM) instead.
- Reading the count of requests without timestamps: two requests at page load look
  identical to load-plus-refetch. Record when each fired relative to the toggle.

Also worth knowing: `BrowserContext.setOffline(true)` does NOT tear down an
established SSE stream in Chromium (the first attempt at this spike observed no
reconnect at all for 25s). The visibility path is the one to drive.

## Work

1. **`StateStore` gains a version.** A counter bumped per applied batch, a
   `changedAt: Map[String, Long]` beside the state map, and a single `lastRemoval`
   stamp. All are written in the existing single `ref.modify` in `update`, so this
   costs no extra traversal and no new synchronization — and no eviction policy,
   since there is no per-id tombstone map to bound.
2. **A renderer generation.** The dump already has a content version
   (`fh-home@1.0.0-g<hash>`), but renderers also change on an entry edit, so this
   wants its own counter incremented wherever a `SignallingRef[IO, Renderer]` is
   swapped.
3. **Emit the cursor** as a signal with each patch batch, and parse it on connect.
4. **Resume path in `sseStream`**: with a valid cursor, skip `initialRepaint`,
   render the changed set, push only those fragments.

Notably absent, and this is the point: no session survives the disconnect, no grace
window, no reaper, no per-client mirror, no eviction cap. The previous draft of this
plan needed all of it.

## What must be proven before this is worth merging

1. ~~The cursor signal really does ride the retry URL.~~ **Done** — see "Evidence"
   above.
2. **A resumed diff produces a DOM identical to a full repaint.** The failure mode
   is silent and ugly: the server believes the browser is current, suppresses the
   patch, and the user sees stale values indefinitely. Worth a test that drives a
   drop/resume and asserts the resumed DOM equals the repainted one.
3. **Group membership driven by CONTENT, not just removals.** This is now the
   riskiest unknown, and it is not the removal case (`lastRemoval` and `rendererGen`
   cover that). A dynamic group's membership can change because an attribute crossed
   a predicate — no entity added, none removed, nothing structural in the store, and
   yet the set of nodes that should exist is different. So the resume path is a THIRD
   case beside "member tick" and "post-reload repaint": it must run the same
   query-affected-group delta machinery (`Patches`, `Server.MaxChurnFraction`) over
   the changed set, not merely re-render the nodes whose entities moved. Test a
   membership change across a disconnect specifically.

## Alternative considered: a server-side mirror of each client's DOM

Keep `Session.lastRendered` alive across the drop (grace window + reaper + cap),
extend it to cover shared per-slug fragments as well as per-session ones, and on
resume diff the current render against it.

Rejected: it answers the same question with per-client server state instead of a
client-carried value, and the bookkeeping is most of the work. It also runs into
`Sessions.scala`'s split — `lastRendered` covers only per-session fragments, while
shared main-page fragments are diffed once per slug against a cache shared by all
clients, which by construction cannot say what any individual browser received.
Recording broadcasts per session to fix that adds tens of KB per client. The cursor
needs none of it.

## Not in scope

The HA→server side. `subscribe_entities` has no "since" parameter, so a reconnect
always reopens with the full entity set — and that is already free, because
`StateStore` publishes only entities whose content actually changed
(`EntityState.sameContent`). There is nothing to win there.

That is not just an argument in this document any more: the reconnect gap being
lossless BY CONSTRUCTION (rather than repaired after the fact) is recorded at the
three places that guarantee it — `HaFeed.runConnection`, `StateStore`'s
`Ingest.Remove`, and `ServerApp.watchRegistryEvents`. Re-deriving is strictly
stronger than replaying, since it also covers changes no event was ever seen for. So
the browser↔server resume designed here has no HA↔server counterpart to build, and
should not grow one.
