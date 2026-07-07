# Plan (later): investigate the in-place-navigation vs. history-management tension

**Status: not scheduled** — investigation only, no code yet.

## Why

ADR 0002 decision 5 navigates between dashboards as an **in-place body swap over
the single SSE stream**, updating the URL **client-side** with
`history.pushState` (forward navigate) + a `data-on:popstate__window` re-sync. The
Datastar "tao" (see `docs/reference/datastar/patterns/tao.md`, "Anti-Patterns →
Custom History Management") names manual `history.pushState` as an anti-pattern and
prescribes standard navigation instead:

> WRONG: `history.pushState({}, '', '/new-page'); loadPage()`
> RIGHT: `<a href="/new-page">` — or let the backend redirect.

So our design knowingly diverges from the framework's guidance. This plan is to
decide whether that divergence is justified or should be revised — **not** to
change anything yet.

## The crux

Our reason for the in-place swap was concrete: keep **one** persistent SSE stream
and **one** per-connection session (popups, diff cache, open-set) alive across a
navigate, rather than tearing it down and re-handshaking on every page change. A
plain `<a href>` / full GET would drop the stream and the session. The question is
whether that benefit is real enough to keep paying the "custom history" cost.

## Questions to answer

1. **What does a full-navigation baseline actually cost us?** If navigating via
   `<a href="/d/slug">` (a normal document load) re-opens the SSE stream and
   re-seeds the session from scratch (state is re-fetched from `StateStore`
   anyway, and tabs would re-bake from the cookie once
   [`plan-tab-state-persistence`](plan-tab-state-persistence.md) lands) — how much
   is genuinely lost? Measure: reconnect latency, re-render cost, any visible
   flash. The cookie tier may make a full reload nearly indistinguishable from the
   in-place swap, which would weaken the case for `pushState`.
2. **Is there a Datastar-sanctioned middle path?** Check whether v1.0.2 (or a
   later pin) offers a blessed navigation primitive — e.g. `data-replace-url` /
   `data-query-string` (Pro), a backend "redirect" SSE convention, or guidance on
   SSE-surviving navigation — that gets URL correctness without hand-rolled
   `pushState`/`popstate`. Cross-check the official guide, not just the skill files
   (the skill `tao.md` is a summary; confirm specifics on data-star.dev).
3. **How load-bearing is the single stream, really?** Browsers reconnect SSE
   automatically and cheaply; our per-connection state (open popups, diff cache) is
   reconstructible. Enumerate what would actually break under full navigation and
   whether each is recoverable from the cookie/`StateStore`.
4. **Accessibility / correctness.** The tao prefers real `<a>` elements (focus,
   middle-click, open-in-new-tab, screen readers). Our button-driven navigate
   loses these. Weigh that against the SSE-continuity benefit.

## Possible outcomes (decide, don't pre-judge)

- **Keep it, document the divergence.** If the single-stream benefit is real, add a
  dated `## Update —` to ADR 0002 that *acknowledges* the tao anti-pattern and
  records the deliberate, reasoned exception (with the measurements from Q1/Q3).
- **Adopt real anchors.** If a full reload is cheap once the cookie tier restores
  first paint, switch navigation to `<a href="/d/:slug">` and delete the
  `pushState`/`popstate` machinery — simpler, accessible, on-grain. Re-establish
  the SSE stream + session on the new page load.
- **Hybrid.** Real `<a>` for cross-dashboard navigation (rare, full reload fine);
  keep the in-place SSE swaps only for intra-dashboard surface/popup updates (which
  are not navigations at all and raise no history concern).

## Out of scope / dependencies

- Do nothing until [`plan-tab-state-persistence`](plan-tab-state-persistence.md)
  lands — the cookie tier materially changes the cost of a full reload (Q1) and
  thus the whole trade-off.
- Whatever is decided, fold it into ADR 0002 as a dated `## Update —` (keep the
  record current per the ADR convention).
