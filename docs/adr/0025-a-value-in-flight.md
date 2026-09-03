# ADR 0025 — A value in flight: what was asked for is not what is showing

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** `runtime/SurfaceGraph.scala` (`committedSelection`,
  `committedSelections`), `runtime/Server.scala` (`swapHost`,
  `openingSignals`), `runtime/Datastar.scala` (the no-null rule),
  `lib/core/tap.pkl`, `lib/components/surface.pkl`,
  `lib/components/slider.pkl`
- **Closes:** ADR 0024's open question. **Uses:** ADR 0005's `ui_<id>` signal
  and URL mirror, which this makes honest.

## Context

A tap used to do two independent things:

```
@post('sse/surface/<slug>/open/<id>');  $ui_<group> = '<id>'
```

The POST asks the server for the fragment. The assignment records the choice
client-side, and the URL mirror follows it (ADR 0005). Only the POST can fail,
so the signal and the DOM could disagree — the URL claiming a panel the page
did not have. ADR 0024 made a tap impossible to *lose*; it did not make the two
halves one fact.

There is a second, quieter disagreement with nothing to do with failure. **Two
taps in flight race.** Click tab A, then tab B: the client's signal ends up B,
because that is the assignment it ran last. The DOM ends up with whichever
`swapHost` served last, and nothing sequences the two POSTs. So the highlight
could say B while the panel showed A, with every request succeeding.

## The decision

**Split the fact in two, and let each side own the half it can know.**

| signal | written by | means |
|---|---|---|
| `ui_<group>` | the SERVER only | what this client's DOM *is* showing |
| `_<group>__pending` | the client, on tap | what it has *asked* to show |

Anything that displays a selection reads `$_<group>__pending \|\| $ui_<group>`,
so the tap still feels instant — the pending value drives the highlight from the
moment of the press. The committed signal, and therefore the URL, is never
written speculatively, which is what makes it incapable of lying. **Nothing is
ever rolled back, because nothing wrong was ever committed.**

`SurfaceGraph.committedSelection` is the one place that says what a swap makes
true, in the two value shapes `resolveActive` and `openPopup` read back out — a
member INDEX for a bake group, a surface id (or `""`) for the popup host. It
answers `None` where the client has no say at all: a state-activated group is
server truth every viewer shares.

**Two signals, not three.** An earlier draft added a `_<group>__busy` from
`data-indicator`. "A request is in flight" is just `pending != ''` — once you
know WHAT was asked for, the boolean is derivable, and the extra signal is a
second copy of the same fact.

### Clearing is where the design earns its keep

**Success clears it by CATCHING UP.** The server sends only `ui_<group>`;
pending empties itself once the truth equals it:

```
data-effect="$_g__pending !== '' && $ui_g == $_g__pending && ($_g__pending = '')"
```

No coordination and no clear in the frame — and deriving it is what makes
CONCURRENT taps correct. A server-sent clear would wipe pending on whichever
response landed first, briefly showing tab A while tab B was still in flight.
Comparing against the committed value means pending survives until the tap it
names is the one that won.

That effect reads and writes the same signal, which is the shape that loops.
It settles because the assignment falsifies its own guard, and
`DatastarMorphContractSuite` MEASURES that rather than assuming it: it counts
the runs, drives the overtaken-tap race through a real browser, and asserts the
count stops growing.

**Failure ends it two ways, and neither is a timeout.** A pending value dies
when it is *refused*, or when *nothing can answer it*:

- **Refused** — the server answered, and the answer was no. It says so in
  signals on a 200 (ADR 0024), clearing the pending value itself: the group id
  rides in the action's query string precisely so it can. This is the only one
  of the three that ends ONE ask, because it is the only one that knows which.
- **Unanswerable** — the commit rides the SSE stream, so a stream that is DOWN
  is the exact statement that no commit is coming. The shell's `_sse` counter,
  already maintained for the offline banner, is the whole test.
- **Unreadable** — a response arrived that was not 200, so Datastar dropped its
  body unread and nothing in it can clear anything. A proxy, an auth redirect, a
  route that is gone.

**The last two are page-wide facts, so they are one rule on the page**
(`Server.PendingSweep`), not a copy on every tab bar. Neither knows or needs to
know which group asked: a dead stream ends every outstanding ask by definition,
and the per-group version reached for exactly these same two shell-owned signals
to say so — it was one rule already, spelled once per group. `@setAll('',
{include:/__pending$/})` states it once. The bundle's `setAll` PEEKS while it
writes, so the sweep neither takes a dependency on every pending signal nor
retriggers itself.

Busy signals are deliberately not swept with them. `finished` is dispatched in
the bundle's `finally` and the indicator plugin clears on a counter, so a busy
state cannot outlive its fetch — a sweep would be guarding against something
that cannot happen.

Between them they are total: a request either gets an answer (refused, or the
commit arrives), or gets a response nothing can read, or gets nothing at all —
and the last case is a dead transport, which is the same transport the answer
would have ridden. There is no case where a request vanishes while its stream
stays healthy, and a deadline, which is what an earlier draft used, would only
have been a worse-informed guess at exactly this.

**So a connect restates the selections.** `SurfaceGraph.committedSelections`
gives the whole `ui_*` picture from a session's open set, and `openingSignals`
carries it with the cursor on every connect. Without it, `_sse` clearing pending
would drop the display back on whatever the last frame it received said — and a
swap is two writes (the patch, then the signal), so a stream dying between them
leaves a DOM holding one panel and a signal naming another. It rides *inside*
the cursor's frame rather than beside it: `SessionLifecycleSuite` states the
opening block as one event, on purpose, because an opening block that grows is
how re-sending creeps back in.

### Why not clear on the fetch simply ending

The obvious move — let the fetch's END clear pending, success or not — is worse
than either. The action POST returns as soon as `swapHost` has QUEUED the patch,
so the 204 can beat the frame carrying it: the highlight would snap back to the
old tab and jump forward when the patch landed. A flicker on the HAPPY path is
worse than the bug being fixed. `data-indicator` cannot rescue it either — it counts
concurrent fetches, which is the right primitive when a boolean is all you have,
but it cannot say WHICH tap is outstanding, and that is the thing the display
needs.

### What a spike settled: an error's body is not a channel

The first design reported failures in the action's own 4xx body — the SSE stream
carries state, the error response carries why there is none. Reading the pinned
bundle supported it: `onopen` dispatches the error event on `status >= 400` and
then neither throws nor returns, so `onmessage` looks reachable.

**It does not work.** `DatastarMorphContractSuite` runs both halves through the
same route with the same body, and the 4xx frames never reach the signal store.
Nor is that an accident of the bundle — Datastar's own essay
[I'm a teapot](https://data-star.dev/essays/im_a_teapot) states the rule
(*"3xx we redirect, 2xx we merge the HTML fragment, and anything else throws an
error"*) and argues a status is the wrong place for anything a user should see.

So three channels remain, and recovery is entirely client-side — which is
*simpler* than the design that motivated the spike, since clearing a pending
signal needs no server bytes at all:

| channel | carries |
|---|---|
| the SSE stream | state — the truth about this client's DOM |
| the HTTP status | that something is not right (the shell's toast, ADR 0019) |
| `data-indicator` | in-flight, self-clearing, counting |

One wart follows: the `FHError` messages those routes return are **unreachable by
the browser**. The status arrives and the toast fires, but the body is dropped
with every other non-2xx frame — so those messages serve tests, logs and `curl`,
not users. Not a reason to remove them; a reason not to invest in their wording.

### Selections were the anomaly, not a new category

Worth stating because it is what made this small. A signal slot (ADR 0017) is
ALREADY server-written on every frame, so a card bound to one self-corrects for
free — a toggle flips optimistically and the next frame confirms or reverts it,
and `control.pkl` has said so all along. `ui_*` selections were the ONE family
where the server never wrote back, which is exactly why they were the family with
the bug. The work was not "invent a mechanism" but "let selections join the one
the rest of the app already follows", plus a pending VALUE for the case a
two-way binding cannot express: a tab index or a surface id has no input element
to bind to.

## Why pending and `busy` share a LOOK but never a name

They wear the same classes — `fh-disabled`, `fh-loading`, and the theme's
`busySpin` bind key — because "this control is mid-something" is one fact.
`tap.pkl`'s `inFlightClass(sig)` / `guardOn(sig)` / `busySpinner(sig)` are
parameterised on the signal for exactly that, which also collapsed the six
hardcoded `busy*` / `busy*Change` constants into three functions.

The SIGNALS stay separate, for three reasons and any one would do:

- **They are keyed differently.** A group's pending is shared by every anchor in
  its bar, which is what makes one selection one value. ADR 0019's invariant is
  the opposite: a busy signal must never be shared, or a sibling's `finished`
  clears an in-flight guard.
- **They have different writers.** `data-indicator` writes booleans; a pending
  value is written by the click expression. Both on one name is two writers
  fighting over one slot.
- **A pending signal rests at `''`, and `''` is not "unset".** `data-attr`
  treats it as HTML's boolean-attribute spelling and SETS the attribute, so a
  binding reading a resting pending signal must spell the predicate
  (`$sig !== ''`). Resting at `null` instead is not available either: assigning
  null DELETES the signal and orphans every binding on it, silently.

Both of those are properties of the pinned bundle rather than decisions of this
ADR, and they constrain every card, not just these. They live in the **`datastar`
skill** with the rest of the measured-bundle corrections — including the
`Datastar.signalsJson` rule they imply (**no signals frame may ever carry a JSON
null**) — because that is where someone writing an unrelated card will look, and
this ADR is not.

## What this does NOT do: buttons

The design that produced this ADR put buttons FIRST, on the reasoning that
migrating ADR 0019's `busy` to pending would be the smallest change and would
prove pending subsumes it. **Building it showed the opposite, and ADR 0019
stands unchanged.**

Pending's clearing rule is "the committed value catches up". A tab has a
committed value — `ui_<group>`, which this ADR makes the server write. **A
service button has none.** `c.tap.service("light/turn_on")` commits an ENTITY
STATE, not a selection, and `c.tap.toggle` does not even name a target. So a
button's pending could only clear on `finished` — which is what `busy` already
does, at which point the migration is a rename that adds a value nobody reads.

What would unblock it is a target the button can name and an observable the
server writes for it: an entity-bound signal slot (ADR 0017) already is one, so
a control with a `checked`/`state` slot and a known target state could commit
against it. That is a real design, and it is not this one — it needs the tap to
carry a target, which `toggle` and most `serviceValue` calls do not have.

The honest consequence: `busy` is NOT superseded. It remains the in-flight
mechanism for service taps, `pending` is the mechanism for selections, and they
are two mechanisms because the two taps differ in a way that matters — one has a
committed value to catch up to and the other does not.

## The deeper reason only the server may write a committed signal

The URL is the visible half, but not the load-bearing one. A session's `holds`
records what THIS client's DOM has — digest and signal values — and every later
diff is computed against it. **A client that writes a server-owned signal makes
`holds` false**, and the server then computes "nothing owed" and never corrects
it. That is why the rule is "only the server writes", not "the client should
avoid writing": the alternative is not a cosmetic disagreement, it is a
connection whose future diffs are all wrong.

It is also why the slider below is not optional cleanup.

## The slider: two values, not three

The plan named the slider drag as "the same shape at a different scale". It is
not: a selection's pending state has no counterpart here, because for a
CONTINUOUS value "what I asked for" and "what I am showing" are the same number
— the finger's. Direct manipulation means the finger is the truth while it is
down, and there is no ask until release.

What the slider needed instead was the split this ADR is about, with the third
state left out:

| signal | written by | means |
|---|---|---|
| `_<id>__value` (+ `__fill`, `__state`) | the SERVER only | last-correct: where the device actually is |
| `_<id>__slide` (+ twins) | the client, during the gesture | what the control is showing |
| — | | there is no third: for a continuous value, "asked for" IS "showing" |

The drag no longer writes the POSITION the server owns. **Two signals are forced,
not preferred**, and the reason is sharper than tidiness: `data-on-signal-patch`
fires on every write to a name, local ones included, so a card cannot tell the
server's write from its own drag on ONE signal. "Where the device is" and
"where the control is" need two names to be distinguishable at all.

The consequence is that the old slider was broken rather than merely
optimistic. Its drag wrote the server's own `value`/`fill`/`state` slots, so the
session's `holds` — what the server believes that DOM has — went stale. A commit
that FAILED then produced no correcting frame, because the device never moved
and the server's diff therefore said "nothing owed". The thumb stayed where the
finger left it, indefinitely.

Reconciliation is two imperative handlers rather than effects, so no
dependency-tracking subtleties arise:

- **the server speaks** (`data-on-signal-patch` filtered to `_<id>__value`) —
  adopt it. Confirmation and CORRECTION are the same line; HA clamping a
  brightness is not a failure and needs no case of its own.
- **refused, or `_sse` down** — adopt it again. That is the rollback, and it is
  the same statement: "show what the device last said". `fill` and `state` are
  RECOMPUTED from the restored position rather than shadowed, because they are
  functions of it — and the formula is the one the drag already uses.

`busy_change` is untouched. Its job is the re-commit guard, it is fetch-scoped,
and a pending signal would duplicate it: for a continuous value the guard is all
"in flight" needs to mean.

Three wrinkles worth knowing.

The input cannot fall through the way a tab's highlight does — `data-bind` is
two-way to ONE signal — so `_slide` holds the display value and the handlers COPY
into it, where a selection's pending merely clears.

**`fill` and `state` are still written by the drag**, and only `value` moved.
They are server-owned signal slots, so the staleness described above is real for
them: while a gesture is in flight the server's `holds` disagrees with the DOM on
both. What repairs it is not ownership but `rollbackToCommitted`, which
RECOMPUTES both from the restored position on either failure path — which is why
that handler recomputes rather than merely restoring `_slide`, and why deleting
it does not just leave the thumb wrong, it leaves the fill wrong too. Giving them
client-owned halves as `value` has is the consistent finish; it is not done.

**`value`'s `data-attr:value` does nothing, ever** — it is a placement, not a
binding. ADR 0017 requires every signal slot to place its bind somewhere, and
this signal is read only by the two handlers, so it has no DOM home; it lands on
the attribute the value would naturally have had. An earlier draft of this ADR
claimed the content attribute positions an untouched control and goes inert once
touched. The first half is false in this markup: `data-bind` sets `.value`
through the IDL on its first pass, raising the dirty-value flag before any user
interaction, so the attribute never positions the thumb even once.
`DatastarMorphContractSuite` measures it, with the unbound input as the control
that keeps the assertion honest. **The real finding is a gap in ADR 0017**: a
signal slot read only by expressions has nowhere to go, and the model has no way
to say so.

`UiSmokeSuite` pins it with a REFUSED commit: the thumb moves (so the assertion
cannot pass vacuously), then returns, and the fill returns with it. Removing the
restore makes it fail.

## Open questions

A live list, not a backlog — delete an entry when it is answered.

**One card still reads `_sse` by literal name**, and it is the slider's
dead-stream rollback. The tabs half is gone — that rule moved to the page shell,
where the counter already lives — so what is left is the single case that could
not follow it, for a reason worth stating rather than working around:

- The sweep can only SET a constant, and this fix is arithmetic: recompute the
  thumb, the fill and the readout from the committed value.
- "The reconnect will restate truth" is false here, uniquely. `holds` says this
  DOM already has the correct `_<id>__value`, and it does — what is wrong is
  `_<id>__slide`, which is client-owned and the server does not track. So the
  server correctly sends nothing on reconnect and the thumb keeps lying.

That makes it a genuine exception rather than a pattern, which is the important
part: it should not be read as licence for a component author to reach for the
transport counter. If the in-flight contract moves onto the renderer-emitted
cell, this is the one piece that stays hand-wired, because it is intra-node —
the state lives on one element INSIDE a node.

**The slider's dead-stream rollback is untested.** `UiSmokeSuite` drives the
slider's REFUSAL path (a real 404) and the tabs' dead-stream path (a blocked
`/sse/**`), but not the slider's dead-stream path — the one that fires when a
phone loses signal mid-drag. Two cards, two ways an ask can end, three of the
four covered. The gap exists because the mechanism "was already tested" on the
other card, which is the way this kind of hole always opens.

**The drag GESTURE has never been looked at in a browser.** Every assertion
about the slider here is functional — a value commits, a refusal rolls back —
and the `ComponentVisualSuite` baselines render the card AT REST, so they passed
untouched and say nothing about the thing that changed. What is unconfirmed is
that dragging still paints smoothly now that the input is bound to a different
signal (`_slide`) from the one the server writes, and that a server correction
mid-gesture does not fight the finger. ADR 0006 asks for browser sign-off on
visual changes; this one has not had it (`sbt dashboardServe`).

**`fill` and `state` are still drag-written server slots.** See the slider
section: `value` moved to a client-owned half and they did not, so `holds` still
goes stale for them mid-gesture and it is `rollbackToCommitted`'s recomputation
that repairs it. Finishing the split is the consistent end of this ADR.

**ADR 0017 has no way to say "this signal has no DOM home".** The slider's
committed `value` is read only by expressions, and the requirement that every
signal slot place a bind pushed it onto an attribute where it does nothing. A
slot kind meaning "expression-only" would say what is true; today a reader has to
be told.

**A stale document's tap could self-heal instead of toasting.** ADR 0024's
refusals are now 200 carrying signals, so the toast at least names what went
wrong. The STALE DOCUMENT case could do better still — a tap naming a surface id
this build renamed gets a message the user cannot act on. Answered as 200 plus a
`_reload` frame
(ADR 0018's mechanism, and `Server.reloadPatch` already exists) the tap would
reload and land on the popup it asked for, because the URL already carries the
selection (ADR 0005).

It sits at the bottom of the list because two of the three cases correct
themselves:

| case | what it is | how often |
|---|---|---|
| unknown surface id | the document predates a rebuild that renamed ids | rare and transient: a live page is REPAINTED with the new ids on the swap (verified — a pushed dashboard updated a pill's `onclick` before it could be clicked), and a page that missed the repaint reloads on reconnect via the head hash. The window is the milliseconds between rebuild and repaint |
| slug nobody serves | the dashboard was deleted, or failed to build, while a page was open | rare, and already owned by ADR 0018: a failed slug gets the error page and a reload repaint |
| `conn` on another dashboard | no honest client produces it | effectively never |

So the reload is POLISH on the rarest path, not a correction: a tap that reloads
the whole page on a transient condition is a worse failure than one that says
"failed".

## Consequences

- The wire format changed: a tab's `onclick` assigns pending before its POST,
  its `active` expression reads both signals, the tabs bar carries the seed +
  the two clears, and the popup taps carry no client assignment at all. The
  `PklBuildSuite` snapshots record it.
- `SurfaceGraphSuite` owns the pure half as a ROUND TRIP — whatever
  `committedSelection` says must read back through `resolveActive`/`openPopup`
  as the member it named — because the two ends were written apart and a value
  shape only one understood would restore the original bug.
- `SurfaceTapSuite` owns the server half: a swap emits the commit, on open AND
  on close, with the request body carrying no ui-state at all, so the selection
  can only have come from the swap.
- `UiSmokeSuite` owns the property in a browser, once per way an ask can end: a
  tap the server REFUSES (a real 404, the status ADR 0024 created) leaves the
  URL where it was and does not leave the bar highlighting a panel that never
  arrived — with the same tap unblocked doing everything, so it cannot pass
  vacuously; and a tap whose transport dies highlights while the question is
  open and stops when the banner says the connection is gone.
- The popup host gained a committed signal it did not have. Before, the client
  set `ui_popups` and a close cleared it; now the swap does both, which is why
  the close path commits `""` rather than simply sending no frame.
- A slower link now shows the pending highlight for longer rather than showing a
  committed one that might be wrong. If that reads worse than the old immediate
  assignment, the DISPLAY rule is what to revisit — the state model would still
  be right.
