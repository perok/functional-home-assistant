# ADR 0019 — An action in flight: the guard is the feature, the spinner is the afterthought

- **Status:** Accepted
- **Date:** 2026-08-17
- **Scope:** `lib/core/tap.pkl`, `lib/theme.pkl` + `lib/theme-beer.pkl`,
  `lib/components/{entity,control,slider}.pkl`, `src/js/shell.ts`
- **Refines:** ADR 0016, which decided *what* a tap does. This one decides what
  happens between the tap and the answer.

## Context

A tap POSTs a service call and the resulting state change comes back over the
SSE stream, typically in well under a second. Two things were wrong with saying
nothing in that window. A second tap during it produced a second call — HA
happily executes both, and for a toggle that means the user's two taps cancel
out. And when the call was slow, or the network was bad, the dashboard looked
broken rather than busy.

## Decision

**A guarded tap owns a per-element signal, and the guard is what matters.**
`TapAction.busy` arms a Datastar `data-indicator` on the tapped element, naming
a signal `_<nodeId>__busy` that is true exactly while that element's POST is in
flight. The click expression is prefixed with `$_<id>__busy ? '' :`, so a
re-click during the window evaluates to nothing. That is the whole anti-spam
mechanism; everything below is presentation.

**The signal is per ELEMENT, never per node.** A slider has two guarded
elements — the track (which commits on release) and the power button — so the
track uses `_<id>__busy_change`. Sharing one name would let either element's
`finished` clear the other's in-flight guard, silently reopening the spam
window it exists to close.

**Two statements, two timings.** "Your tap landed and this control is inert" is
the answer to the tap and must be immediate: `fh-disabled` and `fh-loading` bind
straight to the busy signal. "This is taking a while" is a different statement
and is gated at 300ms — below that, nothing appears at all.

**The gate is a derived signal, not CSS.** This is the part worth recording,
because the obvious implementation is wrong and we shipped it first. Delaying
a *class's* effect with `animation-delay` cannot work: a class carries layout as
well as paint, so the element's box changed the instant the class landed, and
the only property a delay can hold back is `opacity` — which dims the element
*and the glyph inside it*, so a fast action blinked its own icon out and back.
The gate therefore lives in `tap.pkl`, as a `data-on-signal-patch` handler with
a `__delay` modifier that copies the busy signal into `_<id>__busy_slow` after
the threshold. A fast action is already false by then, so the class is never
added and there is nothing to hide. The CSS is then an ordinary description of a
spinner with no timing in it, and the classes' presence already means "we
decided to show this".

Two facts make that handler safe, both verified against the pinned Datastar
v1.0.2 bundle rather than the docs: `datastar-signal-patch` is dispatched by the
signal store's own proxy setter, so it fires on *local* writes (the indicator's)
and not only on server patches; and the `-filter` companion is mandatory, since
unfiltered every HA state patch on the page would arm a timer on every guarded
element.

**A rejection clears the guard and shows a toast.** `finished` fires on a failed
fetch too, so an error can never leave a control stuck. `shell.ts` turns
`datastar-fetch:error` into `.fh-toast`.

## Consequences

**The busy look is hardcoded to BeerCSS's classes, and that is a known debt.**
The spinner is BeerCSS's own `.shape` + `.loading-indicator` — an SVG mask that
morphs itself — and `tap.pkl` names those two classes directly, in the *core*
authoring kit, which is supposed to be framework-agnostic. The theme contract
owns `fh-disabled`/`fh-loading` properly; the spinner skipped that layer.
Consequences we are accepting for now: a second theme cannot change what a busy
control looks like without shipping BeerCSS's class names, the reduced-motion
story is stuck with what an SVG-animated mask allows (its `<animate>` elements
are unreachable from CSS, so we drop the mask for a static disc), and
`.shape>i{filter:invert(1)}` — an unconditional BeerCSS rule — is why the
classes must be *absent* at rest rather than styled inert. Decoupling this into
an `fh-`-prefixed contract like the other two classes is open work.

**A hung call has no floor.** The guard clears when the fetch settles, and a
dead network means it never settles, so a control can sit inert until reload.
Tracked with the rest of the action lifecycle work.

**Verification of the outcome is not in scope here.** HA's `call_service` result
says the service ran without raising, not that the entity reached the state the
user meant, and this ADR deliberately stops at that acceptance boundary. Where
that boundary is too conservative — a boolean intent like "we want it on", where
the expected end state *is* knowable at call time — is a separate decision.

## Alternatives rejected

- **A `shell.ts` promotion step** (add a `busy` class immediately, promote it to
  `busy-slow` on a timer in JS). More moving parts than a declarative handler,
  and it puts per-element timers in the one file every page inlines.
- **One signal per node instead of per element.** Fewer signals, but it breaks
  the invariant above the moment a card has two guarded controls.
- **Disabling the element instead of guarding the expression.** Done for the
  slider input (`data-attr:disabled`), where it is safe because busy can only
  begin on release. Not general: a disabled button loses focus and fires no
  events at all, which is a worse answer than an inert one for a card.
