# Plan: feedback on action calls (await-spinning)

**Status: Phase 1 APPLIED and green.** Frontend-only feedback for discrete tap
actions: prevent spam-clicking an action card, show a busy spinner / progress
cursor while the call is in flight, and surface a transport failure with a
toast. The slider's value COMMIT gets the same treatment under its own
`_<id>__busy_change` signal — the second guarded element on a node, which the
per-element invariant's element-suffixed extension point existed for.
Deliberately **no backend change in the core scope**: the pieces that
would need one (the real HA error message, a per-command timeout) are deferred
to "Phase 2" at the end, and expected-state verification is recommended against.
A follow-on requirement — **a rejected action reverts client-mutated state to
the server's truth** — is designed below ("Failed-action revert", not yet
applied).

## Goal

Today a tap fires `POST /sse/action/<domain>/<service>/<entity_id>` and the
card gives back **nothing**: no indication the call is running, nothing stops a
second click from firing another call, and a rejected call (HA returns
`success:false`) only shows up as… nothing, unless the user happens to notice
the state never changed. This plan gives the UI the three things a tap is
missing:

1. **Anti-spam** — a second click while the action is in flight is a no-op.
2. **Spinner / progress cursor** — the card visibly says "working" while the
   action POST is outstanding.
3. **Failure toast** — a `status >= 400` response to an action POST shows a
   transient "Command failed (…)" message.

## What happens today (verified)

The whole chain, so the plan rests on facts rather than assumptions:

- **Route**: `POST /sse/action/:domain/:service/:entityId`
  → `Server.callService` (`Server.scala:1290`) → `api.callService(...).attempt`.
  `Right` → `NoContent` (204); `Left` → `BadRequest` with
  `{success:false, error}`. The POST is no-content by design — the resulting
  state change flows back over the persistent SSE stream.
- **Error detection exists at the transport level.** HA's WS `call_service`
  returns a `result` frame; a rejected service (unknown service, bad target,
  disabled entity) arrives as `result {success:false, error}` and
  `CommandResponse.AsResult.decodeMessage` raises a `WSHAError`
  (`ha-api/.../protocol/client.scala:110-123`). That is the `Left`, hence the
  400, hence Datastar's `datastar-fetch:error` on the element. A service that
  HA *accepts* returns 204 even if the entity never reaches the state the user
  meant — see "What we are NOT doing".
- **No per-command timeout.** `HAWSApiLowLevel.sendCommand` blocks on the
  route's next frame until HA replies or the connection dies
  (`HAWSApiLowLevel.scala:397-406`). A hung-but-alive connection only clears
  via the keepalive (idle ping → unanswered ping → `die` closes every route):
  up to ~40s worst case. The spinner therefore *always* clears, but a hung
  call can spin it for a while — that ceiling is what the keepalive already
  guarantees, and it is the Phase-2 optional timeout's motivation.

## Datastar primitives the plan rests on (verified against the pinned v1.0.2 bundle)

- **`data-indicator:<name>`** sets `$<name> = true` on `started` and clears
  it on `finished` (`i++` / `i--` counter, so two overlapping fetches keep it
  true). Crucially, `finished` is dispatched in a `finally` — on success AND on
  error — so the indicator **always clears**. One indicator per element
  (`requirement: "exclusive"` = key XOR value, verified against the pinned
  bundle — NOT `{key:"denied"}` as this doc once guessed).
  - **CORRECTION (from the browser-debug session): name it via the VALUE form,
    never the KEYED form, when the signal contains `__`.** The pinned v1.0.2
    bundle's attribute parser (`hn`) splits a namespaced attribute's key on
    `__` (its mod separator): `data-indicator:_c_3__busy` parses to key `_c_3`
    + mod `busy`, so the indicator writes `_c_3` while the class/guard read
    `_c_3__busy` — busy never shows and the guard never gates. The value string
    is not split, so `data-indicator="_c_3__busy"` arms exactly `_c_3__busy`
    (verified: the ControlSmokeSuite busy test fails on the keyed form, passes
    on the value form). The vendored docs' `data-indicator:fetching` keyed form
    is fine only for names with no `__`.
- **Fetch events** (`datastar-fetch` on `document`, `detail = {type, el,
  argsRaw}`): `started` / `finished` / `error` / `retrying` / `retries-failed`.
  `error` fires on `onopen` when `status >= 400`, and `argsRaw` carries only
  `{status}` — **not the response body**, so the backend's `error` message is
  unreachable client-side today (that is Phase 2).
- **Expression guards**: the click expression is compiled to JS with the
  signal proxy `$` and `@post(...)` transpiled textually
  (`@([A-Za-z_$][\w$]*)\(` → `__action("$1",evt,`). So
  `$busy ? '' : @post('...')` is a valid guard: while `$busy` is truthy the
  action is never invoked.
- **`data-class:<name>="$expr"`** toggles a class on the expression.
- This project already consumes `datastar-fetch` events for the `_sse` banner
  (`Server.page`, `Server.scala:1772`); the main stream is a `@get` on
  `<body>`, which is the discriminator the failure toast needs (below).

## Design

### The busy signal

One per actionable node, named **`_<nodeId>__busy`**:

- `<nodeId>` is the backend-minted node id, in every template's scope as
  `{{id}}` (a renderer-*injected* name — `Dashboard.validate`'s injected set
  includes `id`; `Renderer.structuralVars` supplies it to every render form).
  The `.fh-cell` wrapper already owns that id; this is a *different* attribute,
  so no conflict.
- The `_` prefix is the project's client-only convention (ADR 0017 signals,
  `_reload`, `_sse`): Datastar's default `filterSignals` excludes `_`-prefixed
  signals from requests, and the taps already send `noSignals` anyway. The
  busy signal never reaches the server.
- It reuses the ADR 0017 `_<nodeId>__<slotName>` spelling, but it is **not** a
  signal slot: the indicator plugin creates the signal itself
  (`R([[s,false]])` on apply) and drives it; nothing seeds it, nothing renders
  its value.

Per-node (not per-card) uniqueness is the whole point: two entity cards have
two `_<id>__busy` signals, so one card spinning does not disable its sibling.

**One guarded tap per node — and why that invariant is load-bearing.** The
indicator plugin's counter is PER ELEMENT (`i++`/`i--` lives in the plugin
instance), but it WRITES a named signal. If two elements on one node shared the
name, element A finishing would run `busy = (A.i > 0) = false` even while B's
fetch was still in flight — the second spinner would clear early. So the name
is unique exactly when at most one guarded element per node uses it. Current
cards satisfy this: the light's effect pills are SEPARATE child nodes, each
with its own `{{id}}` (`light.pkl:26-33`), so per-pill independence falls out.
The slider is the one node with TWO guarded elements, and the second takes the
element-suffixed name the helpers were built for: the power button owns
`_<id>__busy`, the range input's value-commit owns `_<id>__busy_change`
(`tap.pkl`'s `busyGuardChange`/`busyAttrsChange`/`busyClassChange` — one-line
additions to `tap.pkl`, exactly the extension point documented here).

### TapAction carries the intent

`lib/core/tap.pkl`'s `TapAction` gains `busy: Boolean`:

- `true` on the service taps — `service`, `serviceValue`, `toggle`,
  `stateService` (and so `byDomain`, which routes through them).
- `false` on `navigate` (full document load — no in-flight UI wanted), and on
  the surface taps `openPopup`/`openPopupInline`/`closePopup` (instant local
  POSTs; a busy state there would fight the popup itself). The slider's
  continuous value drag (`data-on:input`) is **not** a tap and is **not**
  guarded by `TapAction` — but its COMMIT is: the slider's own `data-on:change`
  carries the same busy pieces under `_<id>__busy_change` (below, "The slider's
  value commit").

**The opt-out is the flag itself.** `busy: Boolean = false` on any `TapAction`
means that tap neither guards nor spins — it renders byte-identical to today.
That covers both directions an author might want:

- a tap that must be repeatable on rapid clicks (double-tap means something),
  or that must not visually spin;
- the answer to "are we disabling doing both at the same time?": **no** —
  nothing in this design blocks one action because another is in flight. Each
  guarded element is independent; the light's effect pills are separate nodes,
  and on the slider the power button and the range input's commit each guard
  themselves (`_<id>__busy` vs `_<id>__busy_change`) without either blocking
  the other. The only suppression is same-element: a second click on the button,
  or a second value commit while one is in flight.

What the flag does NOT opt into is **device-level mutual exclusion** — "don't
fire the effect pill while the power pill is spinning". That is a different
feature (a per-node shared counter, hand-rolled in shell.ts since the bundle's
indicator counters are per-element), and it is out of scope; the invariant
above is what makes per-element the safe default.

### Two Pkl helpers keep the convention in one place

The signal *name* appears in three template spots; embedding the literal in
every card would give the name six homes. So `tap.pkl` exposes two `const`
strings and the templates splice them via Pkl interpolation `\(…)` (the
`{{id}}` stays a Mustache token inside them, rendered by the engine):

```pkl
const busyGuard: String = #"$_{{id}}__busy ? '' : "#
const busyAttrs: String =
  #"data-indicator="_{{id}}__busy" data-class:fh-busy="$_{{id}}__busy""#
```

The indicator uses the **value form** — `data-indicator="_c_3__busy"`, not
`data-indicator:_c_3__busy` — because the pinned bundle's parser splits the
attribute *key* on `__` but not the value (see the primitives section). The
keyed form would arm a differently-named signal and busy would never show.

### Cards splice them, gated on a `busy` slot

Each tappable card (entity card, button, pill, toggle, slider's power button)
adds a literal slot when its tap is busy and wraps its click attribute:

```pkl
when (tapAction != null && tapAction!!.busy) { ["busy"] = "1" }
```

and its template becomes:

```
data-on:click="{{#busy}}\(tapMod.busyGuard){{/busy}}{{{onclick}}}"
{{#busy}}\(tapMod.busyAttrs){{/busy}}
```

`busy` is an **optional** template var exactly like the existing `tappable` /
`onclick` — not added to any card's declared `slots` list, so validate does not
require it. When absent the section is falsy and the card renders byte-identical
to today. When present:

- `data-indicator="_{{id}}__busy"` — busy=true while the `@post` is in flight,
  cleared by the `finally` `finished` on success and error alike.
- `data-class:fh-busy="$_{{id}}__busy"` — the theme toggles the `fh-busy`
  class (spinner/cursor, below).
- the guard `$_{{id}}__busy ? '' : …` — a second click during the fetch
  evaluates to `''` and never calls `@post`.

The guard is the functional anti-spam; the class + indicator are the visuals.
No `data-attr:disabled` is needed on a CLICK element (meaningless on the entity
card's `<article>` and redundant elsewhere — the guard covers every root
element uniformly). The slider's range input is the one exception, and it earns
it: see below.

### The slider's value commit (the second guarded element on a node)

A slider paints live on `data-on:input` but only COMMITS on release — its
`data-on:change` is the value POST. Dragging back on while that POST is still
in flight would fire a second commit HA would happily process, so the commit
gets the same busy treatment under its own signal. `tap.pkl` exposes three
splices for it (the element-suffixed extension point the per-node invariant
names):

```pkl
const busyGuardChange: String = #"$_{{id}}__busy_change ? '' : "#
const busyAttrsChange: String =
  #"data-indicator="_{{id}}__busy_change" data-attr:disabled="$_{{id}}__busy_change""#
const busyClassChange: String = #"data-class:fh-busy="$_{{id}}__busy_change""#
```

spliced in `slider.pkl`'s self template:

```
<div class="slider max" … \(tapMod.busyClassChange)>
  <input … \(tapMod.busyAttrsChange)
    data-on:change="\(tapMod.busyGuardChange)@post(…)" />
```

- **The name is separate from the power button's** (`_<id>__busy_change` vs
  `_<id>__busy`), because both are elements of the SAME node and a shared name
  would let one's `finished` clear the other's in-flight busy — the per-element
  invariant, applied.
- **`data-attr:disabled` is safe and load-bearing here** (unlike on a click
  element): busy can only become true on RELEASE, never mid-drag, so disabling
  the input freezes the control between drags and the browser fires no
  `input`/`change` at all. The indicator drives a boolean, so the attr plugin's
  `true`→`setAttribute` / `false`→`removeAttribute` toggle is exact. The guard
  remains the belt for a programmatic dispatch (verified by a smoke test that
  dispatches `change` while a held POST is in flight).
- **The busy visual rides on the track wrapper** (`.slider.max`, the element
  that owns the thumb and the painted fill) rather than the native input alone,
  plus `cursor: progress` overrides the slider's own `ew-resize` — the theme's
  `.slider.max.fh-busy` rules in `theme-beer.pkl`.
- The drag itself (`data-on:input`) stays unguarded: it is client-side paint,
  and the disabled input already stops it during a commit.

### When is busy removed

Exactly three paths, and only one of them is "something else overriding":

1. **Its own fetch finishes.** `finished` fires in a `finally` in the pinned
   bundle — after success, after `error`, after retries are exhausted — and
   decrements the element's counter. This is the normal clear.
2. **A node re-render replaces the element.** The live patch for a node is an
   outer replace of its rendering (`renderNodeById` re-renders the node and
   morphs the target element by id), and the indicator plugin's apply runs
   fresh on the new element, resetting the signal to `false`. (An inner patch —
   a value span ticking — does not touch the guarded element's attributes, so
   it does NOT clear busy; only a replace, or a full repaint, does.) So yes —
   a `state_changed` on the entity (which is the whole point of the action)
   replaces the card and clears the busy marker even while the POST is
   technically in flight. In practice this is the NORMAL clear, not the
   exception: HA writes the entity's state DURING service execution and sends
   the call's result frame only after the service completes, so our own
   `state_changed` usually beats our own fetch `finished` — the state change
   IS the success.
3. **Another element on the same signal.** This CANNOT happen here, and that is
   the reason for the per-element invariant: a unique signal per guarded
   element means no sibling's `started`/`finished` can touch it. The naive
   "one shared signal per node" would have exactly this bug — element A's
   `finished` would clear B's in-flight busy.

### The cross-dashboard case (researched)

The "something else" of path 2 can also be a change from ANOTHER client — a
second dashboard, HA's own frontend, an automation, a wall switch. The plan's
"benign" claim got challenged on exactly this, so the behavior was researched
against HA's docs and source:

- **HA serializes state writes; the race resolves to last-writer-wins.** HA
  runs a single-threaded asyncio event loop, and state-machine writes are
  synchronous — a write never suspends, so two tasks cannot interleave within
  one. Two concurrent `call_service`s on one entity both succeed; the entity
  ends at whichever write reached the state machine last. Note "last" is
  write-order, not call-order: a service task can await device I/O, letting a
  later call write first. Nothing is lost or corrupted, and this is HA-native —
  HA's own frontend in two tabs behaves identically. No client can change it,
  and it is not a bug of ours to fix.
- **"Is this event newer than ours" is answerable exactly — by context, not
  timestamp.** Every `state_changed` carries the `context` of whatever
  triggered it. A change initiated through our WS `call_service` carries that
  call's `context.id` (and `user_id` — populated for frontend-initiated
  changes); another client's change carries a DIFFERENT id. That is a precise
  "was it ours" test. The state's `last_updated` timestamp (microsecond
  precision) is only the weaker fallback. We currently drop the `c` field of
  the compressed feed (`EntitiesEvent.scala:44-50`); Phase 2 item 3 decodes
  it.
- **Consequence for busy: accepted, and minimal.** A cross-dashboard change
  only shaves a few milliseconds off a clear that our own state change was
  going to trigger anyway, and its only effect is briefly reopening the same-
  element spam window — and a click there is a new intent on an entity someone
  else is also driving. Closing the early-clear exactly requires the Phase-2
  machinery (a re-render-surviving counter + context correlation); it is not
  worth complicating Phase 1 for.
- **"Cancel" is not a thing.** Once `call_service` is on the wire, HA runs it
  to completion (`blocking=True`; there is no abort message in the WS
  protocol), and aborting our HTTP fetch client-side does not stop it — the
  backend has already forwarded the call. The only "cancel" is a compensating
  call (the inverse service), resolved by last-writer-wins; and HA has no
  command to interrupt an in-progress light transition (a new `light.turn_on`
  usually aborts it and jumps, but that is device-dependent). A "cancel"
  button in the busy UI would therefore just fire the inverse service — which
  for a toggle is exactly what the next tap already does.

### The busy look (theme-owned)

`theme-beer.pkl` (and the `theme.pkl` contract's `styles`) styles `fh-busy`:
`cursor: progress` on the card, a slight opacity/scale drop, and (phase-2
polish) a small CSS spinner pseudo-element over the icon. Nothing in the
templates carries presentation.

### The failure toast (client-only)

The 400 body's `{success:false,error}` is unreachable from the `error` event
detail (only `status`), so the toast is generic in Phase 1:
"Command failed (status)". `src/js/shell.ts` gains a document-level
`datastar-fetch` listener:

- **Filter**: only `type === 'error'` **whose `el` sits under a
  `[data-on\:click]`** (the selector escapes the colon — `[data-on:click]`
  unescaped is an invalid CSS selector and `closest` throws `SyntaxError`,
  which was the Phase-1 toast bug) — the persistent stream's errors arrive with
  `el` = `<body>` (the `@get` target) and are already the `_sse`/`haDown`
  banners' job. Without the filter a stream outage would toast "Command failed"
  over the banner.
- **Visual**: a small `fh-toast` element (theme-styled, `role="status"`,
  auto-dismiss ~4s, later toasts replace earlier ones). This keeps
  presentation in the theme and behavior in the shell, matching the banner
  split.

## What we are NOT doing (expected-state verification)

Verifying that the entity *reached the state the user meant* is **not** part of
this plan. But the boundary needs to be stated honestly, per HA's actual
protocol — because our 204/400 is something we built, and HA is event-based:

- **HA's `call_service` result carries no state.** The official docs are
  explicit: "Right now there is no return value. The client can listen to
  `state_changed` events if it is interested in changed entities as a result of
  a call." `success: true` means the service function ran *without raising* —
  "the action is done executing" — nothing about the entity's outcome.
- **Our 204/400 is a faithful mapping of that result frame, and only of it.**
  204 = `success:true` (accepted, executed, no exception); 400 =
  `success:false` + `error {code, message}` (rejected). It is the acceptance
  boundary, which is exactly the right thing Phase 1 surfaces — a rejected
  call is a definite, actionable failure. It is NOT a statement that the state
  changed.
- **The correlation key HA provides is `context.id`.** A successful
  `call_service` result carries `context {id, ...}`, and the `state_changed`
  events it produces carry the SAME `context.id` (the WS docs' own example
  shows it). So "which changes did my call cause" is answerable from HA's
  protocol — and `HomeAssistantApi.callService` already returns the full
  `result` JSON, so the `context.id` is reachable backend-side at the moment of
  the call with no new protocol.
- **Expected-state verification was still rejected for the plan**, for the
  reasons in the original scope: no universal expected end state for
  `call_service` (only a per-domain table could say one), and a no-op call that
  correctly does nothing would be reported as "failed", which is worse than no
  feedback. But Phase 2's framing, when it happens, is the event stream (below)
  — not an HTTP round trip.

## Failed-action revert (new requirement — designed, not yet applied)

**Requirement:** when an action call is rejected, any *client-mutated* state
must revert to the server's truth.

**The scope is narrower than it sounds, because the UI is server-rendered.**
The only client-mutated signals are the slider's `_<id>__value` / `_<id>__fill`
(two-way `data-on:input` during a drag). A *discrete* tap (toggle, pill, power
button) never mutates client state — the card is rendered from the live store,
so a rejected toggle simply never re-renders and the card is *already* showing
the true state. There is nothing to revert there. So the revert requirement
boils down to one case: **a rejected slider POST leaves `_<id>__value` stuck at
the optimistic dragged value**, and — because the entity never changed — no
`state_changed` patch arrives to correct it; the stale signal persists until
the next full repaint.

**Design (backend-owned, reuses the live-stream machinery):** in
`Server.callService`, the `Left` branch already sends the 400. Additionally,
queue a **forced node replace** of the target entity's nodes on the session —
the same `renderNodeById`-style push a `state_changed` would have sent, driven
by the CURRENT store values. A node replace re-seeds every signal slot
(`_<id>__value` etc.) from the render, which IS the revert; it also re-arms the
busy indicator on the fresh element, closing the same-element spam window the
error reopened. Cost of this shape:

- It needs the entity→nodes mapping the live stream already maintains (the
  `state_changed` dispatcher), and a way to force a re-render even though the
  store value did not change.
- The 400 and the revert patch race the live stream; both carry the same truth,
  and Datastar morphs idempotently, so last-writer-wins is benign.
- It is per-session: only the dashboard that tapped gets the revert patch. That
  is exactly right — only it has the optimistic signal.

**Rejected alternative (client-only):** on `datastar-fetch:error` for a
`data-on:input` element, re-fetch the node's current render. There is no
per-node GET endpoint; the existing `sse/dashboard/<slug>/patch` repaints the
whole dashboard (heavy, fights the live stream), so this was dropped. A
client-side snapshot of "the value the node last rendered with" would require
shell.ts to track per-node last-rendered signal values — duplicating state the
server already owns. Server-owned truth on rejection is simpler and consistent
with how every other correction flows.

**Not yet applied.** It is a backend change (core scope was deliberately
frontend-only) and it needs the entity→nodes plumbing confirmed. Next
increment after Phase-1 sign-off.

## Phase 2 (deferred, out of the core scope)

Two or three backend touches, all building around HA's event protocol rather
than the HTTP boundary:

1. **The real error message.** On `Left`, besides the 400, push a
   `datastar-signals` frame on the session control queue carrying e.g.
   `ui_action_error = {message, entity_id}`; the client shows the actual
   rejection text (a `data-on:signal-patch` on the body, or an effect on the
   signal, clears it after showing). This is the only way to surface HA's
   message, since the fetch detail carries only `status`. Note the `WSHAError`
   we decode today keeps only `code` + `message` — HA's structured errors also
   carry `translation_key`/`translation_domain`/`translation_placeholders`,
   which we would have to start decoding to show localized text.
2. **A per-command timeout** on `callService` (`.timeout(...)` in
   `Server.callService`, or in `HomeAssistantApi.callService`) so a hung-but-
   alive connection clears the spinner with a "timed out" toast instead of
   riding the ~40s keepalive ceiling. Small, but rare to matter — HA normally
   answers every `call_service` with a result frame.
3. **Outcome verification off the event stream.** The plan's "NOT doing"
   section rejects it as a *default*; if a later phase wants it, the shape is
   dictated by HA's protocol: take the `context.id` from the `call_service`
   result, then watch the PERSISTENT event stream for a `state_changed` on the
   target entity whose `context.id` matches, within a timeout. Research notes
   that harden this: correlate by `context.id` (authoritative — a frontend
   call's changes carry its id and `user_id`; another client's carry a
   different one), NOT by event order or timestamp — HA writes the state during
   service execution and sends the result frame after the service returns, so
   the matching `state_changed` can arrive before or after the result, and
   `last_updated` (microsecond precision) is only a fallback for detecting
   "someone else wrote after ours". One gap found in research: our live store
   is fed by HA's compressed feed (`subscribe_entities`), whose `context` field
   (`c`) is deliberately dropped (`EntitiesEvent.scala:44-50` — "decode it if
   we ever want 'changed by'"). So this phase has a concrete choice: decode
   that `Context | string` union, or add a classic `state_changed` subscription
   alongside, or fall back to a plain state-diff on the target entity (simpler,
   and on a single-user dashboard rarely wrong, but it cannot tell "my call"
   from "something else changed it").

## Files touched (core scope)

- `resources/dashboards/lib/core/tap.pkl` — `busy` on `TapAction`,
  `busyGuard`/`busyAttrs`, the `busy` flags on the service constructors, and
  the slider commit's `busyGuardChange`/`busyAttrsChange`/`busyClassChange`.
- `resources/dashboards/lib/components/entity.pkl`, `control.pkl`,
  `slider.pkl` — `busy` slot + template splice on the tappable elements; the
  slider's self template also gains the commit guard on its range input.
- `resources/dashboards/lib/theme-beer.pkl` (and the `theme.pkl` contract if
  it documents `styles`) — `.fh-busy` / `.fh-toast`, and the
  `.slider.max.fh-busy` cursor overrides.
- `src/js/shell.ts` — the `datastar-fetch:error` toast listener (stays
  import-free; a document listener needs no imports).
- `docs/architecture-rendering-pipeline.md` — updated in the same commit, per
  the module rule (the card template conventions are pipeline-adjacent).

## Tests

- **Wire snapshots**: actionable cards now emit a `"busy":"1"` slot, and the
  slider's `self` template carries the commit splices → the
  `PklBuildSuite` snapshots drift. Regenerate deliberately with
  `sbt dashboardSnapshotsUpdate` and read the JSON diff.
- **Functional**: `PklDashboardBehaviourSuite` asserts the rendered HTML of an
  actionable card carries `data-indicator="_{{id}}__busy"` (VALUE form — the
  keyed form was the busy bug), the guard prefix `$_{{id}}__busy ? '' : `, and
  that a non-actionable / more-info card does not; and that `_<id>__busy` never
  appears in an action request body (it is `_`-prefixed and the taps already
  send `noSignals`). A second case asserts the slider's commit renders
  `data-indicator="_{{id}}__busy_change"`, `data-attr:disabled`, the guarded
  `data-on:change`, and the wrapper's `data-class:fh-busy`.
- **Browser-level**: the `ControlSmokeSuite` busy + toast cases (guarded by the
  busy → gated pill/card, fake configurable call delay and forced failures)
  verify the indicator clears and the toast appears. The new slider case drives
  a real commit (keyboard `End` → the value POST, held 2s), asserts the wrapper
  shows `fh-busy` and the input is disabled while it is in flight, dispatches a
  programmatic `change` (a disabled input cannot fire one natively) and asserts
  it is NOT a second call, then asserts busy and `disabled` clear when the held
  response lands.
- **`Dashboard.validate`**: no change expected (`busy` is optional like
  `tappable`; `{{id}}` is injected).
- **Frontend**: `tsc --noEmit` runs in the vite build, so shell.ts is covered;
  the visual needs a browser sign-off (`sbt dashboardServe`), per ADR 0006.

## Risks / notes

- **A cross-dashboard change can clear busy a few ms early** (another client's
  `state_changed` for the same entity replaces the card). Accepted: HA
  serializes writes to the entity (last-writer-wins, nothing lost), our own
  state change would clear the marker ms later anyway, and the only effect is
  briefly reopening the same-element click window — which is a new intent on an
  entity someone else is also driving. Closing it exactly needs the Phase-2
  machinery (context correlation + a re-render-surviving counter); not worth
  complicating Phase 1 for. (Details in "The cross-dashboard case".)
- **Toast duplicates the banner on a stream error?** No — the filter excludes
  `<body>` (stream) errors.
- A JS-less browser is unaffected: no Datastar, no indicator, and no click
  handler to guard anyway.
