# ADR 0005 — Node-scoped UI state and the cookie persistence tier

- **Status:** Accepted
- **Date:** 2026-06-30 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The module's design pressure has been to keep the Scala backend
**presentation-agnostic**: no hardcoded HTML, card names, or URL literals (ADR
0001/0002/0004). A question arose: should there be an abstraction *like slots*
that creates **interactive state attached to a node** — optionally persisted —
that templates and actions read and mutate?

Node-scoped interactive state already exists, informally, composed in the
authoring layer: `tab_<id>` (a tabs group's active index, seeded via
`data-signals`, driving each button's `data-class` highlight) and `val_<id>`
(a slider's bound position). Both compose the signal name as `<name>_<id>`
from the backend-supplied stable node id (`{{id}}`). The backend holds **no**
signal-name literals — so a state abstraction would not remove backend
literals; the real questions are *naming discipline* and *persistence*.

### The three tiers of state

| tier | source of truth | survives reload? | **server sees it on the first-paint GET?** |
|---|---|---|---|
| entity state (`StateStore`) | Home Assistant, server | n/a | yes |
| Datastar signals (`tab_<id>`, `val_<id>`) | client DOM | **no** | **no** |
| cookie | client, persisted | yes | **yes** |

The decisive column is the last one. Datastar round-trips the whole signal
store on every action `@post`, but the **initial GET carries no signal body** —
first paint cannot know prior client state from signals. A **cookie is the
only client store the server receives on the GET** (localStorage /
sessionStorage — and Datastar's `data-persist`, which targets them — are never
sent), so it is the one tier that can both survive reload *and* inform the
server's first paint.

## Decisions

### 1. Node-scoped UI state is a recognised concept, and it stays template-owned

The concept is named (the read-write twin of slots: slots are static inputs
filled at render; *state* is a named, node-scoped value mutated by
interaction) but its realization stays as-is: the signal name is
`<state>_<id>`, composed in the authoring layer from the backend-derived
stable id. The backend must not regress into holding signal-name literals.

### 2. The cookie is the persistence tier — for exactly one slice of state

Per-node UI state that (a) should survive reload / in-place navigate and (b)
must be correct on first paint with no flash is mirrored into a cookie; the
server reads it on the GET (and SSE connect, and navigate) and drives the
existing first-paint **bake** machinery (`bakeInto`/`bakeAs`, ADR 0002) with
it — baking the *actual* prior state instead of a fixed default.

Tiering discipline (do not blur it):

- **entity truth → server `StateStore`.** Never persist a value that reflects
  an entity (a slider's `val_<id>` follows brightness — not UI state, not
  cookie-persisted).
- **ephemeral UI → Datastar signals.** Mid-drag, this-session-only.
- **must-survive-and-inform-first-paint UI → the cookie**, and *only* that.

Server in-memory per-connection state is explicitly not this tier: `conn` is
minted fresh per stream, giving no continuity across a reload.

### 3. The declared-`state` sugar is deferred

A component declaring `state: ['tab']` with auto-namespacing/seeding is **not
built**: only two consumers exist (`tab_`, `val_`), the sugar's value is naming
discipline rather than capability, and a general node-state bucket invites
persisting things that belong on the server or in transient signals.
**Trigger to revisit:** a *third* node-scoped-state component (candidates
below) — then build the sugar and consolidate to a single JSON cookie written
through a theme-provided helper (a `theme.scripts`, symmetric with
`theme.styles`).

### 4. The first use — the active tab — is implemented

The cookie maps a bake-target component id → active surface index
(`fhui_<id>=<i>`), keyed by the id the server already knows (`bakeInto`):

- Each tab button's click expression writes the cookie inline (pure authoring
  composition — no Scala, no JS helper) alongside setting `$tab_<id>`.
- `Server.uiStateOf(req)` reads all `fhui_` cookies as an opaque
  `id -> rawValue` map on the **GET**, the **SSE connect**, and **navigate**;
  it threads into the otherwise-pure renderer (`renderPage`/`renderBody`), so
  the renderer stays state-free.
- **The cookie is untrusted** ("don't trust frontend state"):
  `Renderer.resolveActive` parses and **clamps** the index to a real member of
  the bake group, falling back to the `defaultOpen` member; a malformed value
  is logged as a warning. The restore is flash-free because the GET bakes the
  cookie-selected surface directly, and the SSE connect seeds the open set with
  it so it streams live from the first paint.

## Other candidates this tier serves

Same shape — node-scoped, client-mutated, survives reload, informs first
paint (the 2nd/3rd is the trigger for decision 3's sugar):

- **Collapsible/expanded sections** — nearly identical to tabs.
- **Dynamic-group client-side filter/sort** — persist the selection.
- **Theme light/dark override**; **last-viewed dashboard** — page-level, same
  tier.

Explicit **non-candidates**: slider/value positions (entity is truth), open
popups (transient; must not resurrect on reload).

## Consequences

- The cookie-read path is small and bounded to the HTTP layer; no new client
  protocol (the write is inline JS in a `data-on:click`, the read is plain
  http4s).
- **Datastar specifics (verified against v1.0.2 docs):** Datastar offers no
  free mechanism the server can read on a plain first-paint GET — signals ride
  only on requests Datastar itself issues; `data-persist` (Pro) targets
  storage the server never sees; `data-query-string` (Pro) is the only
  sanctioned alternative but is paid and entangles with our custom in-place
  navigation. Cookies are orthogonal standard HTTP — the one free store the
  server sees on the GET. Hand-rolled deliberately; re-verify on upgrade.
- The tao's "Restrained Signal Usage" sanctions a tab index as an appropriate
  signal — `tab_<id>` is not an anti-pattern; persistence is the orthogonal
  layer.
