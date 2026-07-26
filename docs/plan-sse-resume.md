# Plan (later): resume an SSE reconnect instead of repainting the whole body

**Status: not scheduled** — designed and evidence-checked against the pinned
Datastar build, no code written.

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
  applied batch. Each entity records the version at which it last changed, and each
  REMOVED entity leaves a tombstone recording the version at which it went away.
  "What changed since V" is then a scan of both maps for `> V`.
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

## Work

1. **`StateStore` gains a version.** A counter bumped per applied batch; a
   `changedAt: Map[String, Long]` beside the state map, and a `removedAt` tombstone
   map. Both are written in the existing single `ref.modify` in `update`, so this
   costs no extra traversal and no new synchronization. Tombstones need an eviction
   rule — oldest-beyond-N, or drop on renderer change, since a `rendererGen` bump
   makes them moot.
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

1. **The cursor signal really does ride the retry URL.** Verified by reading the
   pinned build, NOT observed end-to-end. Confirm against a live server by dropping
   the connection and logging the query param — the whole design rests on this one
   fact.
2. **A resumed diff produces a DOM identical to a full repaint.** The failure mode
   is silent and ugly: the server believes the browser is current, suppresses the
   patch, and the user sees stale values indefinitely. Worth a test that drives a
   drop/resume and asserts the resumed DOM equals the repainted one.
3. **Structural change, not just content.** Dynamic-group membership changes which
   nodes exist, not only their HTML. Check a membership change and an entity
   removal across a disconnect specifically — these are the cases where "re-render
   the changed entities" is not obviously the same as "re-render everything".

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
