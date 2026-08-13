# Datastar vs htmx for `fh-datastar-view`

> Evaluation only — no source was changed. Scope: should this module swap its
> frontend runtime library **Datastar** (`v1.0.2`) for **htmx** (2.x)?

## Verdict

**Stay on Datastar.** For *this* design, Datastar is the better fit and a swap
would add libraries and seams without buying us anything the architecture needs.
htmx is the more mature, larger-ecosystem project, and the one thing it would have
genuinely improved — navigation, which used to fight Datastar's own guidance via
the `history.pushState` anti-pattern — has since been fixed inside Datastar
instead: dashboards are real pages (ADR 0002). Nothing left here is a reason to
migrate a module whose runtime (`Server`/`Datastar`/`Renderer`), five ADRs, and
~50 tests are all built on Datastar primitives.

The decision turns on three things, in order of weight:

1. **Our central pattern is Datastar's first-class primitive and only htmx's
   convention.** We hold one persistent SSE stream and, on each HA `state_changed`,
   push fragments to *arbitrarily many* node ids with an explicit
   **selector + mode (+ built-in morph)** — `datastar-patch-elements`. htmx's
   SSE extension is element-scoped (a listener swaps *itself*); reaching our
   pattern means embedding `hx-swap-oob` directives *into the fragment HTML* plus
   the idiomorph extension. That re-pollutes the HTML with swap semantics —
   precisely the "no presentation/control literals in the backend" discipline the
   ADRs spent five documents protecting.
2. **We rely on zero-round-trip client state that htmx does not have.** The tab
   active-highlight (`data-class` over `tab_<id>`) and the slider's live value
   (`data-bind` over `val_<id>`) are client signals (ADR 0005). htmx has *no*
   reactive client state by design, so matching them means adding **Alpine.js** —
   a second library and a second mental model. Net more complex, not less.
3. **One batteries-included bundle vs four cooperating libraries.** Datastar is
   hypermedia + signals + SSE + morph in a single `<script>`. The htmx-equivalent
   of today's behaviour is **htmx + sse extension + idiomorph + Alpine** — each
   individually mature, but a strictly larger and more seam-prone whole.

## Per-capability mapping

| # | What we rely on (code / ADR) | Datastar today | htmx equivalent | Verdict |
|---|---|---|---|---|
| 1 | **One SSE stream, push to many ids by id** on each state change (`Server.changedPatches`, `Renderer.componentsFor`/`renderNodeById`) | `datastar-patch-elements` with `selector` + `mode`; pure-content fragment, swap metadata lives in SSE `data:` lines (`Datastar.patch`) | SSE ext listener is self/descendant-scoped; to hit arbitrary ids you push a payload of `hx-swap-oob` fragments through a `hx-swap="none"` receiver. Works, but swap directives move *into* the HTML | **htmx worse** (convention + HTML pollution vs protocol) |
| 2 | **Patch modes** inner / outer / append / prepend / remove (`PatchMode`, `openSurface`, `navigate`, `removeElement`) | 8 modes as a wire field; `mode remove` deletes by selector with no body | `hx-swap-oob` = `true`/`innerHTML`/`beforeend:#popups`/`delete` etc., encoded as an *attribute on each fragment root* | **htmx worse** (mode becomes an HTML attribute the renderer must inject) |
| 3 | **Morph-preserve** of open `<dialog>`, focused input, mid-drag slider during a repaint | morph is the default swap | needs the **idiomorph** extension + `hx-swap="morph"` config | **htmx worse** (extra lib/config for a default) |
| 4 | **Per-connection correlation** — mint `conn`, round-trip it on every action POST (`sseStream`, `withSession`, `Sessions`) | server pushes `conn` as a signal; Datastar sends the whole signal store on every `@post`, so `conn` rides free (`connOf`) | no signals; correlate via a **cookie** set on the SSE connect (EventSource sends cookies automatically) or `hx-vals`/`hx-headers` per action | **htmx worse** (a cookie is per-origin, so two browser tabs would fight over it — the reason ADR 0005 dropped cookies entirely) |
| 5 | **Decoupled command/query** — action POST returns no content, result arrives later over SSE (`callService` → `NoContent`) | `@post(...)` + `NoContent`; this is the CQRS tenet in the tao | `hx-post` + `hx-swap="none"`; htmx's wheelhouse | **tie** (both clean) |
| 6 | **Client-only reactive UI** — tab highlight (`data-class`), slider bind (`data-bind`), tab signal seed (`data-signals`) (ADR 0005, `lib/components.pkl` `tabs`/`slider`) | built in, zero round-trip | **none in htmx** → add **Alpine.js** (`x-data`/`:class`/`x-model`), or push the highlight from the server (a round-trip for a pure UI effect) | **htmx worse** (needs a 2nd library) |
| 7 | **Lazy surfaces / popups / tabs** — render+push only while open (`Session.open`, `surfaceComponentsFor`, `renderSurface`) | server-side laziness; lib-agnostic. Open = `append`/`inner` patch, close = `remove` | same server logic; open = OOB `beforeend`/`innerHTML`, close = OOB `delete` | **tie** (server owns it) |
| 8 | **Dynamic groups** — query-filtered re-render (`renderDynamic`, `affectedDynamicIds`, ADR 0003/0004) | server-side; lib-agnostic | identical server-side; push via same OOB mechanism | **tie** |
| 9 | **URL first-paint mirror** (ADR 0005) | hand-rolled `replaceState` helper (`data-query-string` is Pro); the read side is plain query params, library-neutral | identical; htmx has `hx-replace-url` for the write half | **htmx marginally better** (one built-in attribute vs. 4 lines) |
| 10 | **Navigation between dashboards** — an ordinary document load of `/d/:slug` (ADR 0002) | the browser owns history; no library involved, and the `pushState` anti-pattern is gone | same — `hx-boost` would add SPA-style swapping we deliberately do not want | **tie** |

## Reasoning

### The wire protocol is the deciding architectural fit

`Server.scala` and `Datastar.scala` are written *around* a server-push protocol:
`datastar-patch-elements` carries `selector` + `mode` as SSE `data:` lines while
the `elements` payload stays **pure content** (`Datastar.patch`/`removeElement`,
`PatchMode`). That is exactly aligned with the module's prime directive — visible
all over the ADRs — that the **backend holds no presentation or control literals**;
chrome, mounts, and even close-URLs live in jsonnet templates, and *where/how* a
fragment lands is SSE-event metadata, not HTML.

htmx inverts this. Its SSE extension swaps the received content into the
*listening* element; to update many independent ids from one stream you embed
`hx-swap-oob` (and, for non-default placement, `beforeend:#popups` / `delete`)
**on the fragment roots themselves**. So the swap mode/selector that Datastar keeps
*outside* the HTML would move back *into* HTML the renderer emits or the templates
carry. We would be re-introducing the exact control-literal coupling the design
worked five ADRs to remove. It is doable — htmx OOB is a proven pattern and the
SSE extension routes received content through the normal swap pipeline (so OOB
fragments are honoured) — but it is a convention bolted onto a request/response
core, where Datastar offers a purpose-built push protocol.

### Signals are load-bearing for our UX, and htmx has none

ADR 0005 deliberately tiers state: entity truth → server `StateStore`; **ephemeral
UI → Datastar signals**; must-survive-and-inform-first-paint → an unprefixed
signal mirrored to the URL. Two signals do real work *with no round-trip*:
`ui_<id>` (active-tab highlight via
`data-class`) and `_val_<id>` (slider position via `data-bind`, so the dragged
number tracks the thumb before the server confirms). The tao sanctions exactly
this ("Restrained Signal Usage" lists a tab index as appropriate local UI state).

htmx has no equivalent. Replacing these means either (a) adding Alpine.js for
`x-data`/`:class`/`x-model` — a second runtime and idiom, the opposite of
simplification — or (b) doing the highlight server-side, turning a pure CSS toggle
into an SSE round-trip and making the slider feel laggy. Neither is a win, and ADR
0005's URL mirror was explicitly designed to *complement* signals (carry the
slice that must survive a reload into the first paint), not to stand in for them.

### Maturity vs fit

htmx is older, bigger-ecosystem, and rock-stable; Datastar v1 is newer with a
smaller community, and we have pinned it to `v1.0.2` precisely because its SSE
event names and `data-*` syntax have shifted across releases (noted in
`Datastar.scala`, `Server.DatastarCdn`, and several ADRs). That is the strongest
generic argument *for* htmx. But "mature" here means assembling **htmx core + sse
extension + idiomorph + Alpine** to recover what one Datastar bundle already does.
Four mature parts compose into a less-cohesive whole than one cohesive part, and
each seam (SSE-ext↔OOB, OOB↔idiomorph, Alpine↔htmx event timing) is a place our
careful render/diff/morph invariants could fray.

### Where our design used to fight Datastar

One place, and the tao named it: **manual history management.** Navigation was
`@post('/sse/navigate/:slug')` + inline `history.pushState` + a
`data-on:popstate__window` handler, since Datastar v1 has no URL/redirect SSE
event. That is now gone — a dashboard change is an ordinary document load and the
browser owns the history entry (ADR 0002) — which was the localized fix inside
Datastar this section predicted, and it closes htmx's one genuine advantage here.

Still hand-rolled, and much smaller: the `?ui.<id>` / `?popup` URL mirror writes
`history.replaceState` from a 4-line helper, because `data-query-string` is Pro
(ADR 0005). That is keeping a URL honest about the view it names, not faking
navigation, so it is outside the anti-pattern the tao warns about.

## What a swap would cost

- **Add 2–3 libraries** (sse extension + idiomorph, and Alpine.js for client
  reactivity) where we ship one bundle today.
- **Re-encode swap semantics into HTML.** `Datastar.patch(mode, selector)` and
  `removeElement` become `hx-swap-oob` attributes the renderer injects onto
  fragment roots — re-coupling control to presentation against the ADR discipline.
- **Re-do client UI in Alpine.** `tabs`/`slider` in `components.libsonnet` drop
  `data-signals`/`data-bind`/`data-class` for `x-data`/`x-model`/`:class`; ADR
  0005's signal tier is rewritten.
- **Rewrite the runtime SSE layer.** `Datastar.scala`'s protocol framing, the
  `PatchMode` enum, and `Server`'s patch calls all change; the conn-signal
  correlation becomes a cookie/header scheme (`withSession`/`Sessions`).
- **Re-validate morph-preservation** (open dialog, focus, mid-drag slider) under
  idiomorph instead of Datastar's default morph; re-run/adjust the ~50 tests.
- **Rewrite ADR 0001–0005 anchors** that name Datastar primitives, plus the
  `datastar` skill's pinned-bundle and project-convention notes.

## What we'd gain / lose

**Gain**
- Native, idiomatic **navigation/history** (`hx-push-url`/`hx-boost`) — moot now
  that dashboards are real pages (ADR 0002).
- A **larger, more stable ecosystem** and a less version-fragile core API.
- `hx-replace-url` would replace the hand-rolled URL-mirror helper (ADR 0005).

**Lose**
- The **single-bundle, batteries-included** model (signals + SSE + morph + push
  protocol in one).
- **First-class server-push with selector + mode + morph** kept *out* of the HTML
  — replaced by in-HTML OOB conventions.
- **Zero-round-trip client UI** (tab highlight, slider bind) unless we adopt
  Alpine.
- The **sunk, coherent investment**: runtime, five ADRs, and ~50 tests already
  shaped to Datastar's primitives.

## Conditional

The only scenario that flips this: if the project decided to **drop live
client-reactive UI** (server-rendered tab highlight, no optimistic slider) *and*
prioritized ecosystem maturity above all, then **htmx + sse extension + idiomorph
(no Alpine)** could serve a purely server-driven dashboard cleanly, and the
navigation story would improve. That trades away UX niceties the current design
deliberately has. Absent that decision, **stay on Datastar**, and consider
addressing the navigation anti-pattern within Datastar as a small, separate change.
