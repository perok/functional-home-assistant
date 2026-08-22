# ADR 0024 — A tap is a request the server can answer; the DOM should stand on its own

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** `runtime/Server.scala` (the surface routes + `withSession`),
  `lib/core/tap.pkl`, `lib/core/surface.pkl`, `lib/components/surface.pkl`
- **Uses:** ADR 0023's `$dashboardSlug` / `{{dashboardSlug}}` pair, for the same
  reason and by the same mechanism.

## Context

Opening a popup or switching a tab is two independent things: a POST that asks
the server for the fragment, and a client-side signal assignment that records
the choice (`$ui_popups = '…'`, mirrored into the URL by `fhUrl`).

Only the first can fail, and it did so silently. The surface routes named no
dashboard, so the only thing that could say which dashboard a tap was for was
its `conn` — and a `conn` this process does not have answered `NoContent`:

```scala
sessions.get(conn).flatMap {
  case None => NoContent() // stale/unknown connection
```

A session outlives its stream by `LingerWindow` (2 minutes) and is then reaped,
so a page left idle — a phone with the screen off, a laptop asleep — sits there
looking perfectly alive while the server has forgotten it. Every tap on such a
page did nothing at all: no patch, no error, and 204 is a *success* status, so
not even the shell's `datastar-fetch` toast fired. The signal half still ran, so
the URL claimed a popup the DOM did not have. What the user experienced was
"I have to tap twice, or tap once and refresh" — the second tap working because
Datastar's stream had quietly reconnected in between.

## The decision

**1. The surface routes carry the slug, like an action does.**

```
POST /sse/surface/<slug>/open/<id>
POST /sse/popup/<slug>/close
```

It earns its place twice: the gate can check the rule the way every other route
does (before, the requirement had to be derived from the session, so a request
with no session was necessarily un-gated), and — the reason this ADR exists —
a connection the server has forgotten can be re-established, because somebody
names the dashboard it belongs to.

**2. An unknown `conn` MINTS a session instead of dropping the tap.** This is
what the stream route already does (`adoptOrMint`); the action path was the one
place that dropped instead — one mechanism, not two. The patch queues in the
fresh session's `control` and is delivered the moment the reconnecting stream
adopts it, so the first tap lands. An abandoned mint is reaped on the same
adoption window a document's is: this is either a page whose stream is on its
way back, or a page that is gone.

A `conn` held by a session viewing a *different* dashboard is refused (409), not
resolved: re-registering would unroute that live page, and no honest client can
produce it, since a `conn` is minted per document and a document is one slug.

**3. A tap that cannot be served says so.** 204 was indistinguishable from
success. Anything left that a tap cannot do answers 4xx, which the shell already
turns into a toast (ADR 0019).

### Why the slug rides in a transform

The tap's URL is built by a JSONata transform (`$dashboardSlug`), the popup's ✕
by its card template (`{{dashboardSlug}}`) — the two spellings ADR 0023
introduced. A literal slot value is used verbatim: no Mustache pass, no binding,
so a constant string could not carry the slug at all.

The transform needs no entity, which is what lets a pill with no entity of its
own open a popup: a slot with no entity resolves against an empty `EntityState`
(`Renderer.resolveSlot`), and `$dashboardSlug` is bound independently of it.

## The standing principle this is the first instalment of

**The HTML we send should be as usable as possible without JavaScript.** Signals
and SSE are how the page stays *live* and how it gets its effects; they should
not be the only way it *works*. A tap whose entire meaning lives in a signal
assignment cannot fail visibly, cannot be linked to, and cannot degrade — which
is exactly the failure above.

This is **not enforced today**, and this ADR does not claim it is. The dashboard
is a live document and parts of it (a drag, a live value) are JS by nature.
It is a direction to hold while designing:

- Prefer a real `<a href>` over a click handler where the tap goes somewhere —
  `c.tap.navigate` already does this, and gets middle-click, open-in-new-tab and
  the status bar for free.
- Prefer state the URL can carry over state only a signal knows. The tab and
  popup selections are already mirrored (ADR 0005), which is why a reload lands
  where the tap meant to.
- Prefer a server round trip that returns markup over one that returns nothing
  and leaves the client to assume.
- Keep the document form of a node self-sufficient: it already renders values
  inline and seeds its signals (ADR 0017), so a JS-less browser gets a correct,
  static page — that property is worth protecting when adding slots.

The obvious next instalment is the optimistic half of a surface tap: the server
patches the DOM but never sends the matching `ui_*` signal, so the client sets
it in parallel and the URL can disagree with the screen. Making the server's
frame carry both would make "what is showing wins" true by construction. Not
done here — it changes what every tap costs, and the failure it guards is now
rare rather than routine.

## Consequences

- The wire format changed: every surface-open/close `onclick` is a transform
  rather than a constant, and the popup card's template names the slug. The
  `PklBuildSuite` snapshots record it.
- `SurfaceTapSuite` owns the two properties: a tap on a forgotten connection
  still opens the popup, and a tap naming another dashboard's connection is
  refused without disturbing the page that owns it.
- A dashboard renamed by `fh push --slug` keeps working, because the slug is
  bound at render time rather than baked at build time — the same reason ADR
  0023 gives.
