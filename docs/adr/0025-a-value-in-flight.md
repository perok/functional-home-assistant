# ADR 0025 — A value in flight: what was asked for is not what is showing

- **Status:** Accepted
- **Date:** 2026-08-22
- **Scope:** `runtime/SurfaceGraph.scala` (`committedSelection`),
  `runtime/Server.scala` (`swapHost`), `lib/core/tap.pkl`,
  `lib/components/surface.pkl`
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

**Failure clears it on a DEADLINE, not a status.** This is the part the pinned
bundle decided rather than the design. Datastar dispatches its `error` type
from `onopen`, so it fires for an HTTP 4xx and *not* for a request that never
got a response at all — an aborted or dropped POST rethrows before then, leaving
only the `finished` its `finally` emits. Keying on `error` alone would cover
exactly the failures a working network produces and leave a dead one stuck
forever.

So `tap.pkl`'s `pendingFail` fires on anything that is not the START of a fetch,
`pendingFailMs` (2s) later, and clears only if the commit still has not landed.
The delay is what makes it safe to be that blunt: a 204 routinely beats the SSE
frame carrying its own patch, so an undelayed clear on `finished` would snap the
highlight back on the HAPPY path.

### Why not clear on `finished` outright

The obvious move — let the fetch's end clear pending — is the flicker above. The
action POST returns as soon as `swapHost` has QUEUED the patch, so the 204 can
beat the frame carrying it: the highlight would snap back to the old tab and
jump forward when the patch landed. A flicker on the happy path is worse than
the bug being fixed. `data-indicator` cannot rescue it either — it counts
concurrent fetches, which is the right primitive when a boolean is all you have,
but it cannot say WHICH tap is outstanding, and that is the thing the display
needs.

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
- `UiSmokeSuite` owns the property in a browser: a tap the server never answers
  leaves the URL where it was and does not leave the bar highlighting a panel
  that never arrived. The same tap unblocked does everything, so it cannot pass
  vacuously.
- The popup host gained a committed signal it did not have. Before, the client
  set `ui_popups` and a close cleared it; now the swap does both, which is why
  the close path commits `""` rather than simply sending no frame.
- A slower link now shows the pending highlight for longer rather than showing a
  committed one that might be wrong. If that reads worse than the old immediate
  assignment, the DISPLAY rule is what to revisit — the state model would still
  be right.
