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

**A card declares the guard; the renderer writes it.** A component author sets
`busy = true` on the tap and places two holes — `{{{fh_guard_click}}}` in the
click expression, `{{{fh_guard}}}` beside it — and the renderer fills them with
the indicator, the node id, the refusal handler, the re-click guard and the
looks (`Renderer.guardAttrs`/`guardClick`). Both are always present and render
empty when the tap is not guarded, so there is no `{{#busy}}` section and no
signal name in any template.

That is a contract question, not a tidiness one: third parties write components
here, and the previous shape asked each of them to splice four pieces in the
right places and to know that the signal names must match what the server
patches on a refusal (ADR 0024). A contract nobody places is a contract nobody
can misplace.

The holes are on the CONTROL rather than attributes on the enclosing `.fh-cell`,
which was tried first and does not work: `data-indicator` keys on
`evt.detail.el === el` — the element that MADE the fetch — so an indicator on
the cell arms a signal nothing ever sets. Moving the click to the cell would fix
the identity and grow every control's hit area to its whole grid cell.

The spinner is the one piece a card still places, because its class is the
theme's (`busySpin`) and it must sit on the element hosting the glyph, which
only the template knows.

**A rejection clears the guard and says so on the control.** `finished` fires on
a failed fetch too, so a refusal can never leave a control stuck — but that also
made a refused action look exactly like a successful one, the dim gone and the
control sitting there as if nothing had been asked. So the node keeps a second
signal, `_<id>__error`: set on refusal, cleared when the same control starts
another action. It is what gives an action an ASSERTABLE outcome — "not busy and
not error" is a state of the thing that was pressed, rather than something
unrelated on the page that happens to move afterwards.

The server is its main writer, and names the node from the id the tap sends
(`?node=`, read off `data-fh-node` at click time): a refused action answers 200
carrying signals (ADR 0024), so the message HA gave lands on the control and in
`.fh-toast`. `shell.ts` keeps the `datastar-fetch:error` listener for the
remainder a signal patch cannot reach — a response that is not 200, where the
bundle drops the body unread and only a status survives.

## Consequences

**The busy look was hardcoded to BeerCSS's classes; ADR 0020 unhardcoded it.**
The spinner is an SVG mask that morphs itself, and CSS has no way to lend one
class another's rules — so the class *names* had to come from somewhere, and
`tap.pkl` named BeerCSS's `.shape` + `.loading-indicator` directly, in the core
kit that is supposed to be framework-agnostic. They now come from the theme
(`Theme.classes["busySpin"]`, spliced into the templates when the card registry
is built); a theme that names none gets `fh-busy-spin` and a plain ring. What
remains true either way: the reduced-motion story is whatever the named look
allows (BeerCSS's `<animate>` elements are unreachable from CSS, so that theme
drops the mask for a static disc), and `.shape>i{filter:invert(1)}` — an
unconditional BeerCSS rule — is why the classes must be *absent* at rest rather
than styled inert.

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
