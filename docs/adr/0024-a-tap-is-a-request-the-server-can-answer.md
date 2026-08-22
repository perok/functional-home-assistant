# ADR 0024 — A tap is a request the server can answer; the DOM should stand on its own

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** `runtime/Server.scala` (the surface routes, `withSession`,
  `sessionFor`, `openSurface`), `runtime/Sessions.scala` (doc only),
  `model/Dashboard.scala` (doc only), `lib/core/tap.pkl`, `lib/core/surface.pkl`,
  `lib/components/surface.pkl`
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

**This is not a new resource vector, and the comparison is worth writing down**
because "an unauthenticated POST now allocates server state" is the first thing
to ask. `Access.Public` does permit an anonymous caller, so on a public
dashboard anyone can mint by tapping with a fresh `conn`. But that same caller
can already `GET /d/<slug>` in a loop, and a document render creates and
registers a session too — after doing a full page render, so it is strictly the
more expensive of the two. Both are bounded by the same `AdoptionWindow`, which
is what `reapAfter` is for. The tap adds no capability, only a cheaper way to
use one that was always there.

**What it costs when the stream does not come back.** The mint is reaped after
the adoption window and the queued patch dies with it, so a page whose stream
is gone for good lands back on "tap again" — the behaviour before this change,
reached after 10 seconds instead of immediately. The fix therefore has no worse
case than the bug it replaces, which is why it needs no retry of its own.

**3. A tap that cannot be served says so.** 204 was indistinguishable from
success. Anything left that a tap cannot do answers 4xx, which the shell already
turns into a toast (ADR 0019).

Datastar's own guidance argues against this shape — *"if you get a client error
or server error when you control both sides then it's a bug"*, answer 200 and
render the error ([I'm a teapot](https://data-star.dev/essays/im_a_teapot)) —
and it is right about the case that matters most here: a tap naming a surface
this build renamed is a stale DOCUMENT, and a toast saying "failed" is not
something the user can act on. A 200 carrying a reload would land them on the
popup they asked for, since the URL already names it.

That is a better answer and it is not this ADR's, because it is a behaviour
change rather than the removal of a silence; `docs/plan-pending-signals.md`
carries it. What is decided here is only that a tap must not be able to fail
without saying anything, which 4xx achieves today. Note the same essay is why an
error BODY cannot carry the correction — non-2xx frames are dropped, pinned by
`DatastarMorphContractSuite`.

This makes `withSession` obey a rule it used to be the exception to: ADR 0018's
seam table already says `rendererFor` answers `None -> 404 for non-HTML
consumers`, and this route was the one that answered 204 instead.

An unknown surface id is therefore LOUD on a tap and SILENT on a restore, which
is deliberate rather than inconsistent. `Renderer.openPopup` clamps an unknown
id out of a URL (ADR 0005) because nobody asked for it just now — it is a stale
bookmark, and the right answer is the page without it. A tap is a request a
person made a moment ago, so leaving it unanswered is the failure this ADR
exists to remove.

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
it in parallel and the URL can disagree with the screen. It is designed in
`docs/plan-pending-signals.md` — a pending signal the client writes and a
committed one only the server writes, which also resolves a race two taps in
flight have today. Not done here: it changes what every tap does, and it
supersedes ADR 0019's `busy` rather than sitting beside it.

## Consequences

- The wire format changed: every surface-open/close `onclick` is a transform
  rather than a constant, and the popup card's template names the slug. The
  `PklBuildSuite` snapshots record it.
- `SurfaceTapSuite` owns the three properties: a tap on a forgotten connection
  still opens the popup (driven through the real reap, not a synthetic unknown
  id), a tap on a surface this build no longer has says so, and a tap naming
  another dashboard's connection is refused without disturbing the page that
  owns it.
- A `Session` now has three creators rather than one. `Sessions.scala`'s own doc
  used to say the document is the only one; it already had two (a stream mints
  on a bookmarked SSE URL), and this adds the third. What the doc was really
  protecting — that `holds` only ever records bytes THIS client was sent — is
  untouched: a minted session starts empty, so the resume that follows re-sends
  rather than under-sends.
- A dashboard renamed by `fh push --slug` keeps working, because the slug is
  bound at render time rather than baked at build time — the same reason ADR
  0023 gives.
