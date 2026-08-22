# Plan — a value in flight: pending signals instead of optimistic writes

**Status: designed, not implemented.** Nothing described here exists in the
sources. It graduates to an ADR when it lands, superseding ADR 0019's `busy`
half and closing ADR 0024's open question.

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
| `_<group>__busy` | `data-indicator` | whether a request is in flight |

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

- **Success clears it from the server**, in the same frame that commits:
  `{ui_g: 1, _g__pending: ''}`. One write, so the commit and the clear cannot
  separate — and because it rides the patch, the two-taps race resolves: the
  last patch applied is the last signal set, whatever order the POSTs took.
- **Failure clears it on the client**, off the `datastar-fetch` error event the
  shell already listens to. No bytes, and no flicker, because nothing was
  committed to flicker back from.

### Why not clear on `finished`

The obvious move — let `data-indicator` clear pending when the fetch ends — is
wrong, and the bundle says why. `finished` is dispatched from a `finally`, so it
fires on success too, and the action POST returns as soon as `swapHost` has
QUEUED the patch. The 204 can therefore beat the SSE frame carrying it: the
highlight would snap back to the old tab and jump forward when the patch lands.
A flicker on the happy path is worse than the bug being fixed.

The indicator is still worth having for the *look* (it counts concurrent
fetches, so it stays true until the last one lands) — it just must not own the
pending VALUE's lifetime.

## What a spike settled, against expectation

The first draft of this design reported failures in the action's own 4xx body:
the SSE stream carries state, the error response carries why there is none.
Clean separation, and reading the pinned v1.0.2 bundle supported it — `onopen`
dispatches the error event on `status >= 400` and then neither throws nor
returns, so `onmessage` looks reachable.

**It does not work.** `DatastarMorphContractSuite`'s "an action's datastar
frames are applied on 2xx and DROPPED on 4xx" runs both halves through the same
route with the same body, and the 4xx frames never reach the signal store. The
mechanism was not chased further; the behaviour is what the design has to live
with.

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

1. **Tabs**, which have the visible race and the simplest display rule.
2. **Popups**, which share the machinery once tabs prove it.
3. Delete ADR 0019's `busy`/`busyVisual` flags in favour of pending, and rewrite
   0019 in place.
4. **The slider drag**, which is the reason this is a mechanism and not a fix.

Steps 1–2 are what would close ADR 0024's open question and the matching entry
in `docs/architecture-rendering-pipeline.md`.

## What would falsify this

- If the highlight driven by `$_g__pending || $ui_g` reads worse than today's
  immediate assignment on a slow link, the display rule is wrong even though the
  state model is right.
- If a committed signal arriving without its pending clear (a patch dropped, a
  reconnect mid-tap) leaves a selection stuck, the clear needs to be derivable
  from the committed value rather than sent alongside it.
