# ADR 0025 — A value in flight: what was asked for is not what is showing

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** `runtime/SurfaceGraph.scala` (`committedSelection`,
  `committedSelections`), `runtime/Server.scala` (`swapHost`,
  `openingSignals`), `lib/core/tap.pkl`, `lib/components/surface.pkl`
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

- **Refused** — the server sent a status, and it was 4xx (ADR 0024). Datastar
  dispatches its `error` type from `onopen`, so that event fires precisely when
  a response with a status arrived. `tap.pkl`'s `pendingFail` keys on it and
  nothing else.
- **Unanswerable** — the commit rides the SSE stream, so a stream that is DOWN
  is the exact statement that no commit is coming. `pendingClear`'s second
  disjunct is the shell's own `_sse` counter, already maintained for the
  offline banner.

Between them they are total, because the two are not independent: a POST that
gets no response at all failed because the transport did, and that is the same
transport the answer would have come on. There is no third case where a request
vanishes while its stream stays healthy — and a deadline, which is what an
earlier draft used, would only have been a worse-informed guess at exactly this.

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
- **`''` is not "unset" to `data-attr`.** Measured, not read: the bundle treats
  the empty string as HTML's boolean-attribute spelling and SETS the attribute.
  A slider whose `data-attr:disabled` read a pending signal resting at `''`
  would sit permanently disabled with nothing in flight. Any such binding must
  spell the predicate (`$sig !== ''`).

### `null` is not the way out, and is worth its own warning

The obvious escape from that last point is to rest pending at `null`, which
`data-attr` does treat as absent. **It is not available: a signal set to null is
dead.** Every later write to that name is ignored — no exception, no console
error, nothing in `onPageError`. The page just stops responding, and it presents
as "Datastar broke" rather than "we wrote a null".

`DatastarMorphContractSuite` measures it from both directions, because a
server-sent `{"s": null}` does the same thing as a client-side `$s = null`. The
consequence is wider than this ADR: **no signals frame may ever carry a JSON
null.** `Datastar.signalsJson` carries the rule. It is a rule and not a type
because `Patch.Signals` is deliberately `Json`-valued — the cursor rides in it
as a nested object — and every producer today builds values with
`Json.fromString`.

## What this does NOT do: buttons

`docs/plan-pending-signals.md` put buttons first, on the reasoning that
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

## And the slider is a third case, not "the same shape at a different scale"

The plan named the slider drag as the mechanism's next customer. Reading it says
otherwise, and for a reason worth recording before anyone tries.

A slider's value is `data-bind`-ed two-way, so the DRAG writes the committed
signal directly — and that is correct, not a bug. Direct manipulation means the
finger IS the truth while it is down; there is no "asked for" until release.
Inverting the binding does not help either: `data-bind` is two-way to ONE
signal, so a display of `pending || committed` would need the server's value
copied into pending whenever pending is clear — at which point pending means
"currently displayed" and is the bound signal again, with extra steps.

So the slider is the TOGGLE pattern this ADR already exempts: optimistic, and
corrected by the server's own write. What it genuinely lacks is the failure
case — a commit that is refused or never answered leaves the slider showing a
value the device never took, with nothing to restore. Fixing that needs a
shadow of the last committed value and a deliberate ROLLBACK onto it, which is
the one thing this ADR's design never does. Right for direct manipulation,
wrong for a selection; a different decision, and not made here.

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
