# ADR 0005 — Node-scoped UI state and the cookie persistence tier

- **Status:** Accepted
- **Date:** 2026-06-30
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The module's design pressure has been to keep the Scala backend
**presentation-agnostic**: no hardcoded HTML, card names, or URL literals — all
chrome, host HTML, and URL construction live in the jsonnet card library (see
ADR 0001/0002/0004). A question arose while finishing that work: should we add an
abstraction *like slots* that, instead of filling a static template hole, creates
**interactive state attached to a node** in the dashboard graph — optionally
persisted (e.g. in a cookie store) — that the template and actions can read and
mutate?

That question conflates two things; this ADR separates and decides them.

### What exists today

Node-scoped interactive state is already real, just informal. There are exactly
two instances, both hand-rolled in jsonnet:

- `tab_<id>` — a tabs group's active-tab index (`components.libsonnet`, the `tabs`
  card: `data-signals="{ tab_{{id}}: 0 }"` + each tab button's
  `data-class="{active: $tab_<id> == i}"`).
- `val_<id>` — a slider's bound position (`data-signals="{ val_{{id}}: … }"` +
  `data-bind`).

Both compose the signal name as `<name>_<id>` from the backend-supplied **stable
node id** (`{{id}}`, derived by `LayoutNode.pathId`). The backend holds **no**
signal-name literals — state naming is already template-owned, the slot model
working as intended. So a "state abstraction" would **not remove** backend
literals; that goal is already met for state. The real questions are *naming
discipline* and *persistence*.

### The three tiers of state

| tier | source of truth | survives reload? | **server sees it on the first-paint GET?** |
|---|---|---|---|
| entity state (`StateStore`) | Home Assistant, server | n/a | yes |
| Datastar signals (`tab_<id>`, `val_<id>`) | client DOM | **no** | **no** |
| cookie | client, persisted | yes | **yes** |

The decisive column is the last one. Datastar already round-trips the **whole
signal store** to the server on every action `@post` (`Server.withSession` parses
the signals, including `conn`, from the POST body) — so the server can read client
state *on actions*. But the **initial GET carries no signal body**, so first paint
cannot know any prior client state. The consequence is visible: a tabs group
always resets to tab 0 on reload and on in-place navigate, because the active tab
lives only in a DOM signal that the GET never sees.

A **cookie is the only client store the server receives on the GET**
(localStorage / sessionStorage — and thus Datastar's `data-persist`, which targets
them — are not sent to the server at all). So the cookie is the one tier that can
both survive reload *and* inform the server's first paint.

## Decisions

### 1. Node-scoped UI state is a recognised concept, and it stays template-owned

We name the concept (the read-write twin of slots: slots are static inputs filled
once at render; *state* is a named, node-scoped value mutated by interaction) but
keep its current realization: the signal name is `<state>_<id>`, composed in
jsonnet from the backend-derived stable id. The backend must **not** regress into
holding signal-name literals.

### 2. The cookie is the persistence tier for UI state that must survive reload AND inform first paint

For any per-node UI state that (a) should survive reload / in-place navigate and
(b) should be correct on first paint with **no flash**, the value is mirrored into
a cookie. The server reads the cookie on the GET (and on the SSE connect and on
navigate) and uses it to drive the existing first-paint **bake** machinery
(`Surface.bakeInto` / `bakeAs`, ADR 0002 Update 2026-06-26) — baking the *actual*
prior state instead of a fixed default. This closes a loop that is impossible with
signals alone, and reuses a mechanism we already have rather than adding a
protocol.

Tiering discipline (do not blur it — the Datastar model deliberately splits these):

- **entity truth → server `StateStore`.** Never persist a value that reflects an
  entity (e.g. a slider's `val_<id>` follows brightness — it is not UI state and
  is **not** cookie-persisted).
- **ephemeral UI → Datastar signals.** Mid-drag, this-session-only, no-restore.
- **must-survive-and-inform-first-paint UI → the cookie**, and *only* that slice.

Server in-memory per-connection state is explicitly **not** this tier: `conn` is
minted fresh per SSE stream, so it gives no continuity across a reload, and it is
lost on restart. The cookie is chosen for durability **and** GET-readability.

### 3. We do NOT build the declared-`state` sugar now

The appealing form — a component declaring `state: ['tab']` next to `slots: [...]`
and the framework auto-namespacing `tab_<id>`, emitting the seed, and letting
templates/actions refer to the bare name — is **deferred**, for concrete reasons:

- **Only two consumers** today (`tab_`, `val_`). Abstracting on two data points is
  premature; the `<name>_<id>` convention is adequate and already collision-safe
  via the unique `{{id}}`.
- **The sugar's value is naming discipline, not capability.** It removes the risk
  of hand-rolled `<thing>_{{id}}` drift/typos — worth doing only once that risk is
  paid for by a third hand-rolled consumer.
- **It risks a third source of truth.** A general node-state bucket invites
  persisting things that belong on the server or in transient signals,
  reintroducing the sync surface Datastar's model was designed to avoid.

**Trigger to revisit:** the moment a *third* node-scoped-state component appears
(see "Other scenarios" below), build the sugar **and** consolidate persistence to
a single JSON cookie written through a small theme-provided helper (see
Consequences). Until then, the first use (tabs) carries its own minimal cookie
wiring composed in jsonnet.

### 4. First concrete use — persist the active tab (planned separately)

The which-tab fix is the first application and is specified in
[`docs/plan-tab-state-persistence.md`](../plan-tab-state-persistence.md). Shape:

- The cookie maps a **bake-target component id → active surface index**
  (`fhui_<id>=<i>`), keyed by the id the server already knows (`bakeInto`), so no
  state-name knowledge is needed server-side and the sugar is not required.
- The tab button's click expression writes the cookie inline (pure jsonnet
  composition — no Scala, no JS helper) alongside setting `$tab_<id>`.
- The server reads request cookies on the **GET** (choose which surface to bake +
  seed `tab_<id>`), on the **SSE connect** (seed the open-set to the restored
  surface so it streams live), and on **navigate** (restore the target
  dashboard's tabs). Flash-free, because the GET bakes the restored tab directly.

## Other scenarios this tier serves

The same shape — node-scoped, client-mutated, survives reload, informs first paint
— covers more than tabs. These are candidates (the 2nd/3rd of them is the trigger
for Decision 3's sugar + single-cookie consolidation):

- **Collapsible / expanded sections** — a card's open/closed boolean; bake the
  open ones, persist the toggle. Nearly identical to tabs.
- **Dynamic-group client-side filter/sort** — a filter input or chip toggle over a
  `Dynamic` group; persist the predicate selection so the view returns as left.
- **Theme / light-dark override** — a manual override of the
  `prefers-color-scheme` default; page-level rather than node-level, same tier.
- **Last-viewed dashboard** — which slug to land on at `/`; page-level, same tier.

Explicit **non-candidates** (kept out by Decision 2's discipline): slider/value
positions (entity is truth), open popups (transient, must not resurrect on
reload).

## Consequences

- A small, well-bounded cookie-read path appears in the HTTP layer (`Server`): the
  GET, the SSE connect, and navigate read `req.cookies`; the parsed map is passed
  into the otherwise-pure renderer methods (`renderPage`/`renderBody`/`render`) so
  the renderer stays state-free. No new client protocol — the cookie write is
  inline JS in a `data-on:click`, the read is plain http4s.
- First-paint restore is **flash-free** because it reuses `bakeInto`/`bakeAs`; a
  surface group simply bakes the cookie-selected member instead of index 0.
- **Consolidation path (when the sugar lands):** replace per-key cookies
  (`fhui_<id>`) with one JSON cookie per dashboard, written through a tiny helper
  injected by the **theme** (a new `theme.scripts`, symmetric with
  `theme.styles`/`stylesheets`) so Scala stays literal-free. Not built now — per
  Decision 3, per-key cookies in jsonnet are sufficient for the single tabs
  consumer.
- **Datastar specifics (verified against the skill reference + the official
  [backend-requests guide](../reference/datastar/reference/sse.md), v1.0.2).**
  Datastar offers **no free mechanism the server can read on a plain first-paint
  GET**: signals ride a `datastar` query param on `@get` and a JSON body on
  `@post`, but only on requests *Datastar itself issues* — a browser reload of
  `/d/:slug` carries neither. `data-persist` (Pro) targets
  localStorage/sessionStorage, which the server never receives. `data-query-string`
  (Pro) syncs signals to the URL, which *is* server-readable — the only sanctioned
  alternative — but it is a paid feature, and for a **private** dashboard a
  shareable/bookmarkable URL adds little while entangling with our already-custom
  in-place navigation. Cookies are unmentioned by Datastar (orthogonal standard
  HTTP) and are the **one free store the server sees on the first-paint GET** — so
  the cookie tier is effectively the only free way to get flash-free restore. We
  hand-roll it deliberately. Pinned to v1.0.2 — re-verify on upgrade.
- **The cookie is client-supplied, so the server treats it as a hint, not truth**
  (the tao's "don't trust frontend state"): the read path **clamps** a restored
  index to a valid surface in its bake group, falling back to the `defaultOpen`
  member. The tao also *sanctions* the rest of the design — "Restrained Signal
  Usage" lists local UI state (a tab index) as an appropriate signal, so
  `tab_<id>` is not an anti-pattern; persistence is the orthogonal layer.

## Update — 2026-07-02: the active-tab cookie restore landed

The first use ([`plan-tab-state-persistence`](../plan-tab-state-persistence.md))
is implemented and green, in two phases on `datastar-todos`:

- **Phase 1 (`cd845e6`)** — model + renderer plumbing, behaviour-identical:
  `Surface.bakeIndex`; the pure `resolveActive` (parse + clamp an untrusted index,
  returning the chosen index and an optional warning) + `selectedSurfaces(uiState)`
  + `uiStateAnomalies`; `uiState` threaded (default empty) through
  `renderPage`/`renderBody`/`render`; the `tabs` panel seed became
  `tab_{{id}}: {{bakeIndex}}`.
- **Phase 2 (`e787aad`)** — cookie wiring: `Server.uiStateOf(req)` keeps `fhui_`
  cookies as opaque `id -> value`, read on the GET, SSE connect, and navigate;
  `defaultOpenSurfaces` deleted in favour of `selectedSurfaces(uiState)`; anomalies
  logged via `IO.println`; each `tabs` click writes `fhui_<id>=<i>`.

So an active tab now survives reload and in-place navigate, restored flash-free
because the server bakes the cookie-selected surface on the first-paint GET. A
malformed cookie falls back to index 0 and prints a warning. `testFull` = **56**
green. The declared-`state` sugar and single-JSON-cookie consolidation remain
deferred per Decision 3. **In-browser confirmation of the reload/navigate restore
is still pending** (renderer + server tests cover the logic).
