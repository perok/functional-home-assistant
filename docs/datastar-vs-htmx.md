# Datastar vs htmx for `fh-datastar-view`

> Evaluation only — no source changed. Scope: should this module swap its frontend runtime
> **Datastar** (`v1.0.2`, pinned at `Server.DatastarCdn`) for **htmx 4**?
> Tracked in [issue #149](https://github.com/perok/functional-home-assistant/issues/149).

## Verdict

**Stay on Datastar.** Not because htmx is worse — htmx 4 closed most of the gaps an earlier version
of this document held against it — but because the thing that would be traded away, a signal store
the server can patch by name, is the one primitive this design is built on, and htmx has no
equivalent that is not a regression or a second library.

The reason the question got asked is **the Datastar Pro license**, and that reason does not survive
contact with the terms. See below: it constrains nothing we do.

## The license, since it is why this is open

Datastar Pro is genuinely unusable for this project. The terms are explicit:

> Redistribution, sublicensing, or making the software available to third parties in any form,
> outside of an "end product", is strictly prohibited. Making the software available in a public
> repo is a form of redistribution, and is strictly prohibited. Adding the software to an
> open-source project is a violation of the license.

For a public repo whose point is a reusable platform, that is fatal, and no reading rescues it.

**But Datastar core is MIT, and Pro is garnish.** The Pro surface is `data-persist`,
`data-query-string`, `data-replace-url`, `data-view-transition`, `data-animate`, `data-on-raf`,
`data-on-resize`, `data-match-media`, `data-custom-validity`, `data-scroll-into-view`,
`@clipboard()`, `@fit()`, `@intl()`, plus a bundler, an inspector, a component API and a CSS
framework. Nothing there is load-bearing. Nothing in the runtime uses it. `shell.ts` already
hand-rolls the one Pro attribute that was wanted (`data-query-string`) in four lines, and ADR 0005
records why.

So the paywall costs us **one thing**: the Inspector, for debugging signals and frames. That is a
real annoyance and a fair signal about the project's direction — it is reasonable to dislike
building on a runtime whose debugging story is behind a license we cannot buy. It is not, on its
own, an architectural reason to migrate. A home-grown debug panel is cheap, and gets cheaper once
[#134](https://github.com/perok/functional-home-assistant/issues/134) collapses the signals into one
store.

## What htmx 4 changed

htmx 4.0.0 went GA on 2026-08-28. Three of the four objections an htmx-2 evaluation would raise are
now weaker or gone:

- **Morphing is core.** `hx-swap="innerMorph"` / `"outerMorph"` are built in. The idiomorph
  extension is no longer a dependency.
- **SSE is a maintained core extension** (`hx-sse`), rewritten on `fetch()` + `ReadableStream`
  rather than `EventSource`, with real reconnect/backoff config and `Last-Event-ID` replay.
- **Multi-target updates got better ergonomics** — `<hx-partial>` and `hx-swap-oob` are extracted
  before the normal swap, and `hx-targets` exists.

So "four cooperating libraries" is no longer the fair characterisation. The honest count today is
**htmx + Alpine**, versus one Datastar bundle.

What did *not* change is the thing that matters.

## The blocking issue: there is no value-level patch

ADR 0017's mechanism is that **a value moves without its node being patched**. A signal slot's value
is not in the node's bytes at all — the element carries only its binding — so the digest stands
still, the morph is suppressed, and one `datastar-patch-signals` frame carries the values for a
whole batch.

htmx 4 SSE swaps **elements**. There is no attribute- or value-level patch primitive anywhere in the
protocol. Every mechanism it has is "replace this DOM subtree." To keep ADR 0017's property you
build the value channel yourself, on top of `hx-sse`'s named-event hook.

That is buildable and not large — `hx-sse` dispatches `htmx.trigger(element, msg.event, {data, id})`,
so a named `signals` event merged into a client store is a handful of lines. It is a fair point that
our Datastar surface is small and already isolated: `Datastar.scala` is 183 lines and is the only
file that speaks the wire protocol, slot bindings all come from `Datastar.binding` driven by the
renderer-side `SignalBind` enum, and the hand-written attributes live in ~17 `template =` sites
across 8 Pkl files. This is not an 18k-line port.

The question is what you put the values *into*, and that is where htmx's two answers both cost
something.

### `hx-live` is not a signal store, and would be a regression

Read at the `v4.0.0` tag rather than from the docs — `src/ext/hx-live.js`:

```js
let fns = new Set();                     // every binding on the page, flat
observer = new MutationObserver(recomputeBound);
observer.observe(document.documentElement,
    { childList: true, subtree: true, attributes: true, characterData: true });

function schedule() { ... queueMicrotask(() => { fns.forEach(f => f()); ... }) }
```

- **No dependency tracking.** `fns.forEach(f => f())` re-runs *every* binding on the page on every
  recompute. Datastar re-runs only the effects that read the changed signal. The 16ms warning in
  `schedule()` is measuring that whole-page pass — the author knows it is O(page).
- **Any mutation anywhere triggers it**, via a document-wide observer including `attributes` and
  `subtree`. On a dashboard that morphs nodes continuously, incidental full passes are constant.
- **No memoisation on reads.** `readData` does `getAttribute` + `JSON.parse` per read, so a store
  held in a `data-*` attribute is re-parsed once per binding per tick.
- **No two-way binding primitive**, so the slider's `data-bind` has no counterpart.

Its own documentation states the model plainly: *"The DOM is the source of truth."* That is the
opposite of what ADR 0017 needs.

### Alpine works, and costs a second library

htmx ships `hx-alpine-compat`, which is a fair indication of the intended answer. Alpine (MIT) has
`Alpine.store()` — one global store, exactly the shape [#134](https://github.com/perok/functional-home-assistant/issues/134)
wants — plus `Alpine.reactive()`/`effect()` (Proxy-based, fine-grained, the same granularity class
as Datastar), `$store` readable from any element, and `x-model` for the slider.

This is the only htmx configuration that does not regress. It costs a second runtime and a second
idiom, and it means hand-building the value channel that `datastar-patch-signals` provides.

## Per-capability mapping

| # | What we rely on | Datastar today | htmx 4 equivalent | Verdict |
|---|---|---|---|---|
| 1 | **Value moves without patching its node** (ADR 0017, `Datastar.patchSignals`) | `datastar-patch-signals`; digest unchanged, morph suppressed, one frame per batch | none — no value-level primitive. Build it: named SSE event → merge into an Alpine store | **htmx worse** (the load-bearing one) |
| 2 | **One SSE stream, push to many ids by id** (`Patches.scala`, `Renderer`) | `datastar-patch-elements` with `selector` + `mode`; payload stays pure content | `hx-swap-oob` / `<hx-partial>` extracted before the normal swap — swap metadata moves *into* the HTML | **htmx worse** (control literals re-enter the markup) |
| 3 | **Patch modes** outer / inner / before / append / remove (`PatchMode`) | a wire field; `remove` deletes by selector with no body | an attribute on each fragment root the renderer injects | **htmx worse** (same reason as 2) |
| 4 | **Morph-preserve** of open `<dialog>`, focus, mid-drag slider | morph is the default swap | `innerMorph` / `outerMorph`, core in htmx 4 | **tie** — was htmx's weakness, now is not |
| 5 | **Client-only reactive UI** — `_<id>__slide` drag bind, `ui_<id>` tab highlight | `data-bind`, `data-class`, zero round-trip | `hx-live` regresses (see above); Alpine `x-model` / `:class` works | **htmx worse** (needs a 2nd library) |
| 6 | **Per-connection correlation** — `conn` round-tripped on every action | Datastar sends the signal store on every POST, so `conn` rides free (`connOf`) | send it explicitly via `hx-vals='js:{...}'` from the store | **tie** — more code, but explicit beats implicit |
| 7 | **Reconnect state** — `_cursor.*`, `ui_*` survive a reconnect via the `datastar` query param (`Server.signalsOf`, `uiFromSignals`) | automatic, and the `_` prefix filter is what keeps it from bloating every request | nothing automatic; the resume protocol needs rebuilding on `hx-vals` | **htmx worse**, and the least-understood cost |
| 8 | **Seed-if-absent across a morph** (`data-signals__ifmissing`) | needed because signals are seeded from markup that gets re-morphed | **problem disappears** — a JS-owned store is not re-seeded by a swap | **htmx better** |
| 9 | **Request-in-flight indicator** (`data-indicator`) | a named signal per `@post` | `hx-pending` / `hx-browser-indicator`, core extensions | **tie** |
| 10 | **Fetch-lifecycle hooks** for drag rollback (`data-on:datastar-fetch__document`) | a Datastar CustomEvent on `document` | htmx's event model (`htmx:response:error`, `htmx:after:request`) | **tie** |
| 11 | **Decoupled command/query** — POST returns nothing, result arrives over SSE | `@post(...)` + `NoContent` | `hx-post` + `hx-swap="none"`; htmx's wheelhouse | **tie** |
| 12 | **Server-side laziness / dynamic groups** (`Session.open`, `renderDynamic`) | library-agnostic | identical | **tie** |
| 13 | **Navigation** — dashboards are real pages (ADR 0002) | the browser owns history | same; `hx-boost` would add SPA swapping we do not want | **tie** |
| 14 | **URL mirror** (ADR 0005) | hand-rolled `replaceState` (`data-query-string` is Pro) | `hx-replace-url` is core | **htmx marginally better** |

Rows 8 and 14 are genuine wins for htmx, and row 4 is a gap that closed. Rows 1, 2, 3, 5 and 7 are
what keeps the verdict.

## What a swap would cost

- **Rebuild the value channel** that `datastar-patch-signals` provides — bounded, but it is the
  mechanism five ADRs are written around.
- **Rebuild the resume protocol.** `Server.signalsOf`, `uiFromSignals`, `connOf` and `_cursor.*` all
  assume the client sends its whole store unasked. htmx sends nothing by default. This is the least
  well understood cost in this document and would need a spike before any estimate is trusted.
- **Re-encode swap semantics into HTML** as `hx-swap-oob` attributes the renderer injects.
- **Add Alpine** and rewrite the client-only signals in it.
- **Rewrite the wire-format tests.** `DatastarMorphContractSuite`, `SignalSlotSuite`,
  `SurfaceTapSuite`, `ResumePatchesSuite` and `AckedResumeSuite` pin bytes, plus six Playwright
  smoke suites.
- **Rewrite the ADR anchors** naming Datastar primitives, and the `datastar` skill.

And the timing argument, which is temporary but real: htmx 4.0.0 is days old, npm `latest` is still
`2.0.10`, and `hx-live` has no production soak. Migrating a working system onto day-one software,
for a license constraint that does not bind, is not a trade worth making now.

## What would flip this

Any of:

1. **Datastar core stops being MIT, or stalls.** The license is the whole reason core is safe; a
   change there removes the argument in one step.
2. **A capability lands behind the Pro paywall that the architecture actually needs** — as opposed
   to the current list, which is garnish. Watch what moves into Pro, not what is already there.
3. **The debugging cost compounds.** If a home-grown inspector proves inadequate and the Pro one is
   the only real option, that is a slow, legitimate squeeze.
4. **Issue [#133](https://github.com/perok/functional-home-assistant/issues/133) becomes real work.**
   A morph-only client profile wants a protocol subset, and that reasoning is partly framework
   independent — worth re-reading this document then.

Revisit when one of those is true, or in six months once htmx 4 has soaked, whichever comes first —
and revisit with measurements from a three-card spike, not with argument. #134's store is the
prerequisite that makes such a spike cheap, which is why it is being built first regardless; see
[`plan-entity-signal-store.md`](plan-entity-signal-store.md).
