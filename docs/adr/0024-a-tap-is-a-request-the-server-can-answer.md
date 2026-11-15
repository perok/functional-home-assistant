# ADR 0024 — A tap is a request the server can answer; the DOM should stand on its own

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** `runtime/Server.scala` (the surface routes, `withSession`,
  `sessionFor`, `openSurface`), `runtime/Sessions.scala` (doc only),
  `model/Dashboard.scala` (doc only), `lib/core/tap.pkl`, `lib/core/surface.pkl`,
  `lib/components/base/surface.pkl`
- **Uses:** ADR 0023's `dashboard_slug` / `{{dashboardSlug}}` pair, for the same
  reason and by the same mechanism.

## Context

Opening a popup or switching a tab is two independent things: a POST that asks
the server for the fragment, and a client-side signal assignment that records
the choice (`$ui_popups = '…'`, mirrored into the URL by `data-fh-url`; that half is
now the server's, see ADR 0025).

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

A choice of a node variable is a declared call (`POST /sse/call/<slug>/<callId>`,
ADR 0023), and every call's body is a `payload` that the bundle sends in place
of the signals, so `tapMod.post` puts the page's `conn` and popup in it:
`{conn, ui_popups, posted, seen}`. A service call ignores both.

It lets the gate check the rule the way every other route does: before, the
requirement had to be derived from the session, so a request with no session
was necessarily un-gated.

**2. Only the stream makes a session; a tap on an unknown `conn` is refused.**
A session is forgotten when its stream has been gone for `LingerWindow` (a phone
asleep, a laptop shut) or the process restarted. The page keeps retrying its
stream, and the stream route rebuilds the session from what its request carries
(`adoptOrMint`): the same `conn`, the viewer's committed choices (ADR 0033), and
the cursor it resumes or repaints from. A tap carries only its `conn` and the
open popup, never the viewer's other choices or the tab panels the DOM shows,
so a session minted from a tap rendered its write against declared values, and
after a reap the reconnect resumed rather than repainted, leaving those renders
standing. So a tap whose `conn` this process does not hold answers the refusal
below (`Server.NotConnectedMessage`, its pending ask cleared), and the press
after the reconnect lands. A tap racing a wake or a restart has to be repeated,
but it is told so, and silence was the bug.

A `conn` held by a session viewing a *different* dashboard is refused too, not
resolved: re-registering would unroute that live page, and no honest client can
produce it, since a `conn` is minted per document and a document is one slug.

**3. A tap that cannot be served says so — as page state, not as a status.**
204 was indistinguishable from success. Every refusal now answers **200 carrying
`datastar-patch-signals`**: HA rejecting the service call, a call this dashboard
does not declare or the pressed node does not place, a press whose node showed
the call otherwise (ADR 0023's `seen`), a posted value of the wrong type, a
surface id this build no longer has, a `conn` this process does not hold or
another slug holds. One function answers all of them (`Server.actionRefused`), so
"an action reports its refusal" is a rule rather than a habit each route
follows.

The request WAS served: the route ran, reached its answer, and produced one.
What failed is the OPERATION, which is a fact about the page — so it travels as
page state, the same way every other fact about the page does. This is Datastar's
own argument (*"if you get a client error or server error when you control both
sides then it's a bug"* — [I'm a teapot](https://data-star.dev/essays/im_a_teapot)),
and the pinned bundle leaves no alternative anyway: it parses a response body
`if (M !== 200) { … return }`, so a 4xx body is dropped unread and a bare status
is everything a client can learn from one. A 4xx could therefore never carry the
message HA gave; the toast could only repeat a number back at the user.

Three signals, each the state of one thing the refusal touched — the control
that was pressed (`_<node>__error`), a choice's pending ask (cleared), and the
shell's toast (`_toast`, now HA's own words). The node is the client's claim
about itself, in the query string, and nothing is authorized off it: it says
which control to paint, and a wrong one paints the wrong control on the caller's
own page. The ask is the server's own: the declaration the choice resolves to at
that node (ADR 0033).

A status still exists for what is genuinely not an answer — an auth redirect, a
route that is gone, a proxy in the way. Those reach the client as Datastar's
`error` event with only `{status}`, and the two client-side halves
(`tap.pkl`'s `failedOn` and `pendingFail`) exist for exactly that remainder.
Note that the same bundle behaviour is why an error BODY cannot carry a
correction — non-2xx frames are dropped, pinned by `DatastarMorphContractSuite`.

This makes `withSession` obey a rule it used to be the exception to: ADR 0018's
seam table already says `rendererFor` answers `None -> 404 for non-HTML
consumers`, and this route was the one that answered 204 instead.

An unknown surface id is therefore LOUD on a tap and SILENT on a restore, which
is deliberate rather than inconsistent. `Renderer.openPopup` clamps an unknown
id out of a URL (ADR 0005) because nobody asked for it just now — it is a stale
bookmark, and the right answer is the page without it. A tap is a request a
person made a moment ago, so leaving it unanswered is the failure this ADR
exists to remove.

A call over a set that finds no candidate present at the press (ADR 0023) is not
a refusal but a success: 204, with no call to HA. Nothing to act on is already
the state the press asked for (an "all off" with everything off), and a refusal
would paint the control as failed for doing what it says.

A refused `seen` names the field and both values (`service: "lock/lock" shown,
"lock/unlock" now`), so the toast says what moved. One effect of it: Datastar
retries a press whose answer was lost. If HA had acted, the retry finds the state
moved and is refused, so the viewer sees a refusal for a press that worked. A
call whose service is a literal has no `seen`, so its retry acts again, as it
always did. Not built: a success
answer ("Turned off 3 lights"). The toast is this ADR's error channel, so one
would need a neutral look and a signal of its own; meanwhile the label says what
a press will do (ADR 0034) and the state change shows what it did.

### Why the slug rides in a transform

The tap's URL is built by a CEL transform (`dashboard_slug`), a card
template's by Mustache (`{{dashboardSlug}}`) — the two spellings ADR 0023
introduced. A popup's close builds none: it is `history.back()`, and the page's
`popstate` listener posts the close (ADR 0005). A literal slot value is used verbatim: no Mustache pass, no binding,
so a constant string could not carry the slug at all.

The transform needs no entity, which is what lets a pill with no entity of its
own open a popup: a slot with no entity resolves against an empty `EntityState`
(`Renderer.resolveSlot`), and `dashboard_slug` is bound independently of it.

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
  `c.view.navigate` already does this, and gets middle-click, open-in-new-tab and
  the status bar for free.
- Prefer state the URL can carry over state only a signal knows. The tab and
  popup selections are already mirrored (ADR 0005), which is why a reload lands
  where the tap meant to.
- Prefer a server round trip that returns markup over one that returns nothing
  and leaves the client to assume.
- Keep the document form of a node self-sufficient: it already renders values
  inline and seeds its signals (ADR 0017), so a JS-less browser gets a correct,
  static page — that property is worth protecting when adding slots.

The next instalment was the optimistic half of a surface tap: the server patched
the DOM but never sent the matching `ui_*` signal, so the client set it in
parallel and the URL could disagree with the screen. **ADR 0025 landed it** — a
pending signal the client writes and a committed one only the server writes,
which also resolves the race two taps in flight had.

## Consequences

- The wire format changed: every surface-open/close `onclick` is a transform
  rather than a constant, and the popup card's template names the slug. The
  `PklBuildSuite` snapshots record it.
- `SurfaceTapSuite` owns the three properties: a tap on a forgotten connection
  still opens the popup (driven through the real reap, not a synthetic unknown
  id), a tap on a surface this build no longer has says so, and a tap naming
  another dashboard's connection is refused without disturbing the page that
  owns it.
- A `Session` has two creators: the document, and a stream naming a `conn`
  this process does not hold. A tap creates none. `holds` only ever records
  bytes THIS client was sent: a stream-minted session starts empty, so the
  resume that follows re-sends rather than under-sends.
- A dashboard renamed by `fh push --slug` keeps working, because the slug is
  bound at render time rather than baked at build time — the same reason ADR
  0023 gives.
