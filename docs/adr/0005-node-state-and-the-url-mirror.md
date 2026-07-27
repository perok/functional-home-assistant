# ADR 0005 — Node-scoped UI state and the URL mirror

- **Status:** Accepted
- **Date:** 2026-06-30 (consolidated 2026-07-04; persistence tier replaced 2026-07-27)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The module's design pressure has been to keep the Scala backend
**presentation-agnostic**: no hardcoded HTML, card names, or URL literals (ADR
0001/0002/0004). A question arose: should there be an abstraction *like slots*
that creates **interactive state attached to a node** — optionally persisted —
that templates and actions read and mutate?

Node-scoped interactive state already exists, informally, composed in the
authoring layer: a tabs group's active index and `_val_<id>` (a slider's bound
position). Both compose the signal name as `<name>_<id>` from the
backend-supplied stable node id (`{{id}}`). The backend holds no *authoring*
signal-name literals — so a state abstraction would not remove backend
literals; the real questions are *naming discipline* and *persistence*.

### Where the state has to be readable

| moment | what the server has |
|---|---|
| first-paint GET (and a refresh) | the URL, cookies — **no signals** |
| SSE (re)connect | signals (Datastar re-serializes the store on every retry) |
| action POST | signals (the JSON body) |

Datastar round-trips the whole signal store on every request it issues, and
that includes a **retry**, so a reconnecting client's signals are current, not
page-load stale — the same mechanism the SSE-resume cursor relies on
(`docs/plan-sse-resume.md`, proof point 1, verified in a browser). What signals
cannot do is inform the **first paint**: the initial GET is issued by the
browser, not by Datastar, and carries no signal payload.

So the state needs a second carrier, and there are three candidates the server
sees on that GET: a cookie, the URL, and `Referer`.

## Decisions

### 1. Node-scoped UI state is a recognised concept, and it stays template-owned

The concept is named (the read-write twin of slots: slots are static inputs
filled at render; *state* is a named, node-scoped value mutated by
interaction) but its realization stays as-is: the signal name is
`<state>_<id>`, composed in the authoring layer from the backend-derived
stable id. The backend must not regress into holding *authoring* signal-name
literals; the `ui_` prefix below is a different thing — a framework protocol
name, like `conn` and `popup`.

### 2. Signals are the live carrier; the URL is their mirror

- **The signal is the truth.** A bake group's selection is `ui_<id>`, an
  ordinary unprefixed Datastar signal, so it rides every request the client
  makes — the SSE reconnect included. `Server.uiStateOf` reads it, and the
  server therefore always knows what a connection is showing without keeping
  per-client state between connections.
- **The URL mirrors it**, via `history.replaceState` from the page shell's
  `fhUrl(key, value)` helper (`Server.UrlSyncScript`), as `?ui.<id>=<value>`.
  That is a hand-rolled `data-query-string` — the Pro plugin that would do this
  for us and which we don't have. The reverse direction needs no script: the
  page is server-rendered, so the server reads its own GET's query and bakes
  the value into the `data-signals` seed it already emits.

The URL earns the job the cookie used to hold because it is the only carrier
that is **per document**. A cookie is per-origin: two browser tabs on the same
dashboard would overwrite each other's selection, and one tab's popup would
land in the other's host on its next reconnect. A URL is also shareable and
deep-linkable, which a cookie can never be. `Referer` would technically work
(same-origin fetches send the full URL, and it tracks `replaceState`), but it
is strippable by policy or extension and the failure is silent — the server
would bake the default tab and morph the user back to it.

`replaceState`, never `pushState`: this is view state, not navigation. Back
should leave the dashboard, not step back through tab clicks. (The Datastar tao
warns against "custom history management"; the target of that warning is faking
navigation, not keeping a URL honest about the view it names.)

Tiering discipline (do not blur it):

- **entity truth → server `StateStore`.** Never mirror a value that reflects an
  entity (a slider's `_val_<id>` follows brightness — not UI state, not
  mirrored; `_`-prefixed so it does not even ride requests).
- **ephemeral UI → `_`-prefixed signals.** Mid-drag, this-document-only.
- **must-survive-and-inform-first-paint UI → an unprefixed signal + its URL
  param**, and only that.

Server in-memory per-connection state is explicitly not this tier: `conn` is
minted fresh per stream, giving no continuity across a reload.

### 3. The declared-`state` sugar is deferred

A component declaring `state: ['tab']` with auto-namespacing/seeding is **not
built**: only two consumers exist (`ui_`, `_val_`), the sugar's value is naming
discipline rather than capability, and a general node-state bucket invites
persisting things that belong on the server or in transient signals.
**Trigger to revisit:** a *third* node-scoped-state component (candidates
below) — then build the sugar and consolidate the URL mirror behind it.

### 4. The uses — the active tab, and the open popup

Both are keyed by the id the server already knows, and both are untrusted
input, clamped at the boundary:

- **Active tab.** `ui_<bakeInto>` = the active surface index, mirrored to
  `ui.<bakeInto>`. Each tab button's click sets the signal (pure authoring
  composition); the panel host's `data-effect` writes the URL.
  `Renderer.resolveActive` parses and **clamps** the index to a real member of
  the bake group, falling back to the `defaultOpen` member, and logs a warning
  on a malformed value — so a hand-edited URL can never bake a non-existent
  surface. The restore is flash-free because the GET bakes the selected surface
  directly, and the SSE connect seeds the open set with it so it streams live
  from the first paint.
- **Open popup.** `popup` (one at a time, so one string), server-pushed from
  the only place that changes the host (`Server.swapHost`), mirrored to
  `?popup=<id>`. A claim naming a surface this dashboard does not host is
  ignored. This reverses the original decision that popups are transient and
  "must not resurrect on reload": if you have a dialog open and you refresh,
  you expect it back — and on a phone, backgrounding the tab is how you read a
  notification, not how you dismiss a dialog.

## Other candidates this tier serves

Same shape — node-scoped, client-mutated, survives reload, informs first
paint (the 3rd is the trigger for decision 3's sugar):

- **Collapsible/expanded sections** — nearly identical to tabs.
- **Dynamic-group client-side filter/sort** — persist the selection.
- **Theme light/dark override**; **last-viewed dashboard** — page-level, same
  tier.

Explicit **non-candidate**: slider/value positions (the entity is truth).

## Consequences

- **Fewer bytes per request, not more.** The old shape paid twice: an
  unprefixed `tab_<id>` signal on every Datastar request *and* an `fhui_<id>`
  cookie on every request to the origin. There is now one carrier; the URL
  costs nothing per request because it is never sent.
- The read path is small and bounded to the HTTP layer (`uiStateOf` = query
  params ∪ signals, signals winning as the live value); the write path is one
  inline helper on the page shell plus one `data-effect` per group.
- **Datastar specifics (verified against v1.0.2):** `data-query-string` and
  `data-persist` are Pro; the free bundle has neither, and `data-persist`
  targets storage the server never sees anyway. Re-verify on upgrade — if
  `data-query-string` becomes available, it replaces `UrlSyncScript` exactly.
- The tao's "Restrained Signal Usage" sanctions a tab index as an appropriate
  signal — `ui_<id>` is not an anti-pattern; the URL mirror is the orthogonal
  persistence layer.
