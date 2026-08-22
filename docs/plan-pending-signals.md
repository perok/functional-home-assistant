# Plan — a value in flight: pending signals instead of optimistic writes

**Status: LANDED for selections, as [ADR 0025](adr/0025-a-value-in-flight.md).**
Read the ADR for what the code does; this is the derivation that produced it,
kept for the reasoning it records (the 4xx spike, the channel table, the
"selections are the anomaly" framing).

**Two things here are now known wrong**, and the ADR says so:

- **Buttons were the wrong first step**, not the smallest one. A service tap has
  no committed value to catch up to, so pending cannot clear there and does NOT
  subsume `busy`. ADR 0019 stands.
- **The slider needs two values, not three.** For a continuous value "asked
  for" IS "showing", so pending collapses into the existing fetch-scoped guard;
  what it needs is a client-owned `slide` half so the drag stops writing the
  server's own slots. ADR 0025 has the shape.
- **Failure ends an ask two ways, and neither is a timeout.** The
  `datastar-fetch` error event is dispatched from `onopen`, so it covers a
  refusal (a status the server sent) and never a request that got no response.
  That second case is not a separate failure — a POST vanishes because the
  transport died, and that is the same transport the commit rides, so `_sse`
  states it exactly. A connect then restates the selections.

## The problem

A tap does two independent things:

```
@post('sse/surface/<slug>/open/<id>');  $ui_popups = '<id>'
```

The POST asks the server for the fragment. The assignment records the choice
client-side, and the URL mirror follows it (ADR 0005). Only the POST can fail,
so the signal and the DOM can disagree — the URL claims a popup the page does
not have. ADR 0024 made the tap impossible to *lose*; it did not make the two
halves one fact.

There is a second, quieter disagreement that has nothing to do with failure.
**Two taps in flight race.** Click tab A, then tab B: the client's signal ends
up B, because that is the assignment it ran last. The DOM ends up with whichever
`swapHost` offered to `control` last, and nothing sequences the two POSTs — they
are separate fibers. So the highlight can say B while the panel shows A, with
every request succeeding.

## The shape

**Split the fact in two, and let each side own the half it can actually know.**

| signal | written by | means |
|---|---|---|
| `ui_<group>` | the SERVER only | what this client's DOM *is* showing |
| `_<group>__pending` | the client, on tap | what it has *asked* to show |

**Two signals, not three.** An earlier draft added a `_<group>__busy` from
`data-indicator`, which is redundant: "a request is in flight" is just
`pending != ''`. `busy` is what you need when the only thing you know is
whether a fetch is open; once you know WHAT was asked for, the boolean is
derivable and the extra signal is a second copy of the same fact.

Anything that displays a selection reads `$_<group>__pending || $ui_<group>`, so
the tap still feels instant — the pending value drives the highlight from the
moment of the press. The committed signal, and therefore the URL mirror, is
never written speculatively, which is the property that makes it incapable of
lying. **Nothing is ever rolled back, because nothing wrong was ever committed.**

```
data-indicator="_g__busy"
data-on:click="$_g__pending = '<id>'; @post('sse/surface/<slug>/open/<id>')"
```

Clearing is where the design earns its keep:

- **Success clears it by CATCHING UP.** The server sends only `ui_g`; pending
  clears itself once the truth equals it:

  ```
  data-effect="$_g__pending !== '' && $ui_g == $_g__pending && ($_g__pending = '')"
  ```

  No coordination, no clear in the frame — and it is what makes CONCURRENT taps
  correct. A server-sent clear would wipe pending on whichever response landed
  first, briefly showing tab A while tab B is still in flight. Comparing to the
  committed value instead means pending survives until the tap it names is the
  one that won.
- **Failure clears it on the client**, off the `datastar-fetch` error event the
  shell already listens to. No bytes, and no flicker, because nothing was
  committed to flicker back from.

**Spike this before building on it.** A `data-effect` that reads and writes the
same signal is self-referential, which is exactly the shape that loops. It
should settle (the assignment falsifies the guard), but "should" is not what
this repo accepts about Datastar semantics — `DatastarMorphContractSuite` is
where the answer belongs, next to the 4xx one.

### Why not clear on `finished`

The obvious move — let `data-indicator` clear pending when the fetch ends — is
wrong, and the bundle says why. `finished` is dispatched from a `finally`, so it
fires on success too, and the action POST returns as soon as `swapHost` has
QUEUED the patch. The 204 can therefore beat the SSE frame carrying it: the
highlight would snap back to the old tab and jump forward when the patch lands.
A flicker on the happy path is worse than the bug being fixed.

`data-indicator` is not needed for this at all, then. It counts concurrent
fetches, which is the right primitive when a boolean is all you have — but it
cannot say WHICH tap is outstanding, and that is the thing the display needs.

## What a spike settled, against expectation

The first draft of this design reported failures in the action's own 4xx body:
the SSE stream carries state, the error response carries why there is none.
Clean separation, and reading the pinned v1.0.2 bundle supported it — `onopen`
dispatches the error event on `status >= 400` and then neither throws nor
returns, so `onmessage` looks reachable.

**It does not work.** `DatastarMorphContractSuite`'s "an action's datastar
frames are applied on 2xx and DROPPED on 4xx" runs both halves through the same
route with the same body, and the 4xx frames never reach the signal store.

It is not an accident of the bundle either. Datastar's own essay
[I'm a teapot](https://data-star.dev/essays/im_a_teapot) states the rule —
*"If it's a 3xx we redirect, 2xx we merge the HTML fragment, and anything else
throws an error"* — and goes further, arguing that a status is the wrong place
to put anything a user should see: answer 200 and render the error, because
*"if you get a client error or server error when you control both sides then
it's a bug, and you should be fixing it."*

So an error's body is not a channel, and the three that remain are:

| channel | carries |
|---|---|
| the SSE stream | state — the truth about this client's DOM |
| the HTTP status | that something is not right (the shell's toast, ADR 0019) |
| `data-indicator` | in-flight, self-clearing, counting |

Recovery is therefore entirely client-side, which is *simpler* than the version
that motivated the spike: clearing a pending signal needs no server bytes at
all. The one thing still out of reach is the server's error TEXT — ADR 0019
already records that as unreachable via `argsRaw`, and this confirms the body
does not rescue it.

### The status decision this reopens

ADR 0024 turned two silent 204s into 4xx statuses, so the shell's toast fires
instead of a tap vanishing. Against the essay's advice that is arguably the
wrong shape, and the case worth revisiting is the STALE DOCUMENT — a tap naming
a surface id this build renamed. Today it is a 404 and a "Command failed (404)"
toast, which tells the user nothing they can act on.

Answered as 200 plus a `_reload` frame, the same mechanism the error page's
recovery stream already uses (ADR 0018), that tap would instead RELOAD the page
— and land on the popup it asked for, because the URL already carries the
selection (ADR 0005). The failure becomes a self-heal rather than a toast.

**How often does any of this happen? Rarely, and two of the three correct
themselves** — which is the honest reason this sits at the BOTTOM of the list
rather than reading as the essay demanding a rewrite:

| case | what it is | how often |
|---|---|---|
| unknown surface id | the document predates a rebuild that renamed ids | rare and transient: a live page is REPAINTED with the new ids on the swap (verified — a pushed dashboard updated a pill's `onclick` before it could be clicked), and a page that missed the repaint reloads on reconnect via the head hash. The window is the milliseconds between rebuild and repaint |
| slug nobody serves | the dashboard was deleted, or failed to build, while a page was open | rare, and already owned by ADR 0018: a failed slug gets the error page and a reload repaint |
| `conn` on another dashboard | no honest client produces it | effectively never — this one really is "a bug", the one use the essay grants a 4xx |

So the reload is POLISH on the rarest path, not a correction. Worth doing when
the machinery is already in hand (`Server.reloadPatch` exists and every page
carries the `_reload` effect, so it is a one-line answer); not worth doing on
its own. A tap that reloads the whole page on a transient condition is a worse
failure than one that says "failed".

One wart to note either way: the `FHError` messages those routes return are
**unreachable by the browser**. The status arrives and the toast fires, but the
body is dropped with every other non-2xx frame — so those messages serve tests,
logs and `curl`, not users. Not a reason to remove them; a reason not to invest
in their wording.

## Selections are the anomaly — the rest of the app already works this way

Worth stating before any of the above is built, because it makes the plan
smaller than it looks. A signal slot (ADR 0017) is ALREADY server-written on
every frame, so a card bound to one self-corrects for free. The toggle is the
clearest case, and its own note in `control.pkl` says so:

> "a click now moves the signal immediately, so the switch flips optimistically
> and the frame that follows either confirms it or puts it back."

That is this plan's model, shipped: the client may write optimistically because
the server writes the same signal authoritatively straight after. It holds for
every entity-bound slot — a toggle's `checked`, a slider's position, a value
readout.

`ui_*` selections are the ONE family where the server never writes back, which
is exactly why they are the family with the bug. So the work is not "invent a
mechanism"; it is "let selections join the one the rest of the app already
follows", plus a pending VALUE for the case a two-way binding cannot express —
a tab index or a surface id has no input element to bind to.

## What buttons and toggles actually need

They are not a separate customer with a separate design:

- **A toggle** already flips optimistically and is already corrected by the
  server, via its two-way `checked` slot. Nothing here changes that.
- **A service button** already has ADR 0019's `busy` — the re-click guard plus
  the dim. What it lacks is the SPINNER when it has no icon to spin, which is
  what the earlier "add a wave or a spinner" question was really about (BeerCSS
  already gives every `.chip`/`.button` its press wave, so that half is done).
- What pending ADDS for both is a value where `busy` has only a boolean: "this
  is what I asked for", so a control can show the target rather than just
  dimming. For a toggle that is redundant with the two-way binding; for a
  button whose outcome is a state it is not.

One nuance the migration must not flatten: `busy` also GUARDS (`$_id__busy ? ''
: <onclick>` swallows a re-click). That is right for a service button, where a
double tap means two service calls, and wrong for a tab, where a second tap is
a change of mind that should win. So the guard stays a per-tap policy — the
existing `busy` flag on `TapAction` — rather than becoming implied by pending
being set.

So buttons are not a cleanup at the end — migrating `busy` to pending is what
PROVES pending subsumes it, and it is the smallest place to prove it.

## Why this is one mechanism, not a popup fix

It subsumes ADR 0019's `busy`, which is the degenerate case: a boolean where
this carries a value. That also dissolves a wart — `busy` is switched OFF for
surface taps today, with the recorded reason that "a busy state would fight the
popup itself". Under this design it does not fight it: the pending state is what
DRIVES the display, so the in-flight affordance and the selection stop being two
features that argue.

The named next customer is the slider drag, which has the same shape at a
different scale: a value the user is moving, a commit the server owns, and a
window where the two differ.

It also passes the repo's own test for splitting state (root `CLAUDE.md`,
"sum-type the state — but only when the flags are the SAME fact"): *what is
showing* and *what was asked for* are two facts, so two signals is right here,
where merging them is what produced the bug.

Both new signals are `_`-prefixed because the server never reads them off a
request — the target is already in the URL path — which is the existing
signal-cost convention.

## Order of work

1. **Buttons** — migrate ADR 0019's `busy`/`busyVisual` to pending and rewrite
   0019 in place. Smallest change, no new display rule, and it is what proves
   pending subsumes busy before anything else depends on that claim. Picks up
   the missing spinner for a control with no icon.
2. **Tabs**, which have the visible race and the simplest selection display.
3. **Popups**, which share the machinery once tabs prove it.
4. **The slider drag**, which is the reason this is a mechanism and not a fix.

Toggles need no step of their own: their two-way `checked` slot already has the
property, and pending would only duplicate it.

Steps 1–2 are what would close ADR 0024's open question and the matching entry
in `docs/architecture-rendering-pipeline.md`.

## What would falsify this

- If the highlight driven by `$_g__pending || $ui_g` reads worse than today's
  immediate assignment on a slow link, the display rule is wrong even though the
  state model is right.
- If a committed signal arriving without its pending clear (a patch dropped, a
  reconnect mid-tap) leaves a selection stuck, the clear needs to be derivable
  from the committed value rather than sent alongside it.
