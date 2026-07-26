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

## The key finding: the discriminator is already on the wire

A resume is only safe if the server can distinguish two cases that hit the same
endpoint with the same URL:

1. **Resumed stream** — same page, same DOM, stream dropped and retried.
2. **Fresh page load** — new DOM rendered server-side, nothing to resume onto.

Datastar sends all non-`_`-prefixed signals with every backend action; for GET it
serializes them into a `datastar` query param (`U.set("datastar", F)` in the pinned
build, with `exclude: /(^|\.)_/`). The runtime already mints a `conn` id on connect
and pushes it to the client as the `conn` signal (`Server.ConnSignal`) — not
underscore-prefixed, therefore included.

So:

- **First connect after a page load**: signals are fresh, no `conn` yet ⇒ absent
  from the query param ⇒ full render, exactly as today.
- **Retry / visibility refetch**: the page never reloaded, the signal survives in
  memory ⇒ `conn` is present and names the session the server was already serving.

A page reload correctly reads as case 2, and two tabs get distinct `conn`s. No SSE
`id:` field and no `last-event-id` handling is required. (`last-event-id` would
work too — the build does record it and replay it as a header, surviving both the
retry loop and the visibility refetch — but it duplicates a discriminator we
already have.)

## The blocker: the session does not survive today

`sseStream` mints `conn <- IO.randomUUID` and calls `Session.create` on **every**
connect, and `.onFinalize(sessions.deregister(conn))` drops the old one when the
stream dies. A retry arrives at least a second later, so by then the session — and
its `lastRendered` mirror — is gone.

This is the substance of the work, not the diffing.

## The second complication: the mirror is split

`Session.lastRendered` is **not** a mirror of the whole DOM. Per `Sessions.scala`
it covers only per-session fragments (open surfaces, bake-group owners). Shared
main-page fragments are diffed once per slug in `sharedPatchPublishers` /
`sharedPatches`, against a cache shared by all clients — which by construction
cannot say what any individual browser received, since a client that was
disconnected missed the broadcast that the shared cache already recorded as sent.

So a resume needs per-client knowledge of the shared fragments too.

## Design

### 1. Sessions outlive their stream (briefly)

Replace the immediate `deregister` with a grace period: on stream finalize, mark
the session detached and start a timer; a resume within the window reattaches it,
otherwise a reaper drops it.

- Window: comfortably longer than Datastar's early retries, shorter than "the user
  closed the tab". Datastar's backoff is 1s doubling to a 30s cap over 10
  consecutive failures (~3 minutes total), so ~2 minutes is the honest match for
  "still trying"; a visibility refetch can be much later, and that case simply
  falls back to a full repaint.
- Cap the number of detached sessions and evict oldest-first, so a crawler or a
  flapping client cannot grow the map without bound.
- `Session` gains explicit state rather than a pair of flags — one detached-at
  timestamp is enough, but if a second flag appears, sum-type it.

### 2. The mirror covers everything the client actually received

When a shared patch is broadcast, each receiving session records `nodeId -> html`
into its own mirror. Rendering stays once-per-slug; only the record is per session.

Memory: one string map per live client. A large dashboard is on the order of
100 nodes × a few hundred bytes ⇒ tens of KB per client. Fine for a home
dashboard; note it explicitly in the type's doc so it is a known cost rather than a
surprise.

### 3. Resume = diff, not repaint

On connect with a `conn` that resolves to a detached session:

- reattach it (keep `lastRendered`, `open`, `slug`),
- skip `initialRepaint`,
- render the current snapshot and diff against the mirror — `Patches.diff` already
  does exactly this — emitting only fragments whose HTML actually changed.

Anything else (no `conn`, unknown `conn`, expired session, empty mirror) takes
today's path unchanged. **The full repaint stays the default**; resume is the
narrow, provable case.

### 4. Bonus, nearly free

The SSE `retry:` field sets Datastar's retry interval client-side (the parser
assigns it to both the current and base interval). If the reconnect story is being
touched anyway, the server can tune client backoff without touching the `@get`
attribute.

## What must be proven before this is worth merging

1. **The `conn` signal really does ride the retry URL.** Verified by reading the
   pinned build, NOT observed end-to-end. Confirm against a live server by
   dropping the connection and logging the query param — the whole design rests on
   this one fact.
2. **A resumed diff produces a DOM identical to a full repaint.** The failure mode
   is silent and ugly: the mirror claims the browser holds HTML it does not, so the
   patch is suppressed and the user sees stale values indefinitely. Worth a test
   that drives a drop/resume and asserts the resumed DOM equals the repainted one.
3. **Nodes that changed structurally, not just in content.** Dynamic-group
   membership and tab selection change which nodes exist, not only their HTML;
   check a membership change across a disconnect specifically.

## Alternative considered: a per-slug replay log

Give shared patches a monotonic sequence, keep a bounded ring buffer of
`(seq, nodeId, html)`, and on resume replay everything after the client's last
seen seq, coalescing by node; fall back to a repaint if the seq was evicted.

Bounded globally rather than per client, which is its main attraction. Rejected as
the primary design because it is a second mechanism describing the same fact the
per-session mirror already holds, and it still needs the per-session half for open
surfaces — so it adds a structure without removing one. Revisit if per-client
mirror memory ever actually bites.

## Not in scope

The HA→server side. `subscribe_entities` has no "since" parameter, so a reconnect
always reopens with the full entity set — and that is already free, because
`StateStore` publishes only entities whose content actually changed
(`EntityState.sameContent`). There is nothing to win there.
