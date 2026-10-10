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
| the FIRST SSE connect | still no signals (see below) — so the URL, forwarded |
| an SSE RE-connect | signals (Datastar re-serializes the store on every retry) |
| action POST | signals (the JSON body) |

Datastar round-trips the whole signal store on every request it issues, and
that includes a **retry**, so a reconnecting client's signals are current, not
page-load stale — the same mechanism the SSE-resume cursor relies on
(`docs/adr/0011-the-live-connection.md`, proof point 1, verified in a browser). What signals
cannot do is inform the **first paint**: the initial GET is issued by the
browser, not by Datastar, and carries no signal payload.

Nor can they inform the **first SSE connect**, which is the trap: `data-init`
fires from the `<body>` before Datastar has merged the `data-signals` seeds on
its descendants, so that one request arrives signal-less. The connect then
repaints the body — computed from the DEFAULT selection — over a first paint
that was correct, and the `data-effect` mirror dutifully writes the default back
to the URL. A deep link visibly rewrote itself to `?ui.<id>=0`. So the page shell
forwards its restore state (`Server.Restore`) on the `data-init` URL as ordinary
query params; signals still win wherever both name the same fact, which is every
request after that one. Browser-proven in `UiSmokeSuite` (the assertion is
ordered after an unrelated state change, so it sees the repaint, not just the
first paint).

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
name, like `conn`.

### 2. Signals are the live carrier; the URL is their mirror

- **The signal is the truth.** The open popup is `ui_popups`, an ordinary
  unprefixed Datastar signal, so it rides every request the client makes — the
  SSE reconnect included — and `Server.popupOf` reads it. A tab bar's
  selection is a node variable instead (decision 4): the session holds it, and
  its committed signal `_var_<id>__tab` rides only the SSE GET, which is all a
  forgotten session needs to adopt it (ADR 0033).
- **Only the server writes it** (ADR 0025). A tap records what it ASKED for in
  a separate pending signal; the committed one moves when the swap that serves
  the tap moves it. That is what makes "the signal is the truth" a fact rather than a
  hope: the URL below mirrors a value no client ever asserted on its own.
- **The URL mirrors it**, via `history.replaceState` from the
  `data-fh-url="['<param>', $signal]"` attribute (`src/js/datastar.ts`, built
  into the page's Datastar module, `Server.DatastarScript`), as
  `?ui.popups=<id>` or `?v.<id>.tab=<n>`.
  That is a hand-rolled `data-query-string` — the Pro plugin that would do this
  for us and which we don't have. The reverse direction needs no script: the
  page is server-rendered, so the server reads its own GET's query and bakes
  the value into the `data-signals` seed it already emits.
- **A param lives as long as the element that mirrors it** (issue #411).
  `data-fh-url` is a Datastar attribute, bundled with the vendored Datastar
  into one module, so its cleanup is Datastar's: when the last element
  mirroring a param leaves the document, the param goes. Closing a popup takes
  its inner mirrors with it, and nothing else would ever have cleared what they
  wrote. Counting per param rather than per element is what lets a morph
  replace a host in either order. Only the URL forgets: the session
  still holds a window chosen in the popup, so reopening it shows that window
  again.

The URL earns the job the cookie used to hold because it is the only carrier
that is **per document**. A cookie is per-origin: two browser tabs on the same
dashboard would overwrite each other's selection, and one tab's popup would
land in the other's host on its next reconnect. A URL is also shareable and
deep-linkable, which a cookie can never be. `Referer` would technically work
(same-origin fetches send the full URL, and it tracks `replaceState`), but it
is strippable by policy or extension and the failure is silent — the server
would bake the default tab and morph the user back to it.

`replaceState` for tabs and windows: this is view state, not navigation. Back
should leave the dashboard, not step back through tab clicks. (The Datastar tao
warns against "custom history management"; the target of that warning is faking
navigation, not keeping a URL honest about the view it names.)

**A popup is the exception: each one is a history entry.** A popup that offers
more detail opens another, and with one popup at a time (ADR 0002) the only way
out of the second was out of both. So the popup's mirror carries `__history`
and PUSHES each popup the server opens; the browser's history is the stack, and
the server keeps showing one.

- **Closing is going back.** The ✕, Escape, a tap on the backdrop and
  `c.tap.closePopup` are all `history.back()`, to the popup before or to the
  dashboard. The backdrop also keeps a tap beside the popup from reaching the
  page under it, which the non-modal dialog alone did not.
- **One `popstate` listener** (`Server.page`) asks the server to show what the
  URL now names: open that popup, or close. So Forward reopens.
- **A value the URL already names is not pushed.** That is what tells a
  server-confirmed open from one that Back or Forward asked for, with no flag.
  The entry is pushed on the server's commit, not on the tap, so a refused open
  leaves none.
- **A page that loads with a popup open** (a shared link, a refresh) gets a
  dashboard entry inserted beneath it, so a close never leaves the dashboard.
  A refresh of an entry the mirror pushed keeps its marker in `history.state`
  and inserts nothing.
- Nothing resets on a close. The session still holds a window chosen in the
  popup, and the entry going back to restores the URL it had.

Tiering discipline (do not blur it):

- **entity truth → server `StateStore`.** Never mirror a value that reflects an
  entity (a slider's `_val_<id>` follows brightness — not UI state, not
  mirrored; `_`-prefixed so it does not even ride requests).
- **ephemeral UI → `_`-prefixed signals.** Mid-drag, this-document-only.
- **must-survive-and-inform-first-paint UI → an unprefixed signal + its URL
  param**, and only that.

- **must-survive-a-load but must NOT be in a link → `sessionStorage`, keyed by
  slug.** The scroll offset (`fhScroll` in `src/js/shell.ts`, ADR 0002) is the
  case: it has to cross the document load that navigation is made of, but it is
  not the view a URL names — mirroring it would rewrite the URL on every scroll
  frame and make a shared link land somebody else mid-page. Per tab, like the
  `conn` handoff that already uses this storage. Reach for this tier only when
  the value fails the URL test on BOTH counts (not shareable, not first-paint
  input); anything the server must know at render time belongs in the URL.

Server in-memory per-connection state is explicitly not this tier: `conn` is
minted fresh per stream, giving no continuity across a reload.

### 3. Declared state is a node variable

A component declaring a named, node-scoped choice is a **node variable** (ADR
0033): declared on the node, namespaced by its id, seeded by the page,
committed by the server and mirrored as `v.<id>.<name>`. Its third consumer
(after a tab bar and a window chooser) needs nothing new. It holds a viewer's
CHOICE only: an entity's value stays on the server and mid-gesture state in a
`_`-prefixed signal (`_val_<id>`), which is why it is not a general node-state
bucket.

### 4. The uses — the active tab, and the open popup

Both are keyed by the id the server already knows, and both are untrusted
input, clamped at the boundary:

- **Active tab.** A node variable (ADR 0033): the bar declares `tab`, the
  open member's index, and its panel's bake group is selected by it
  (`Activation.Var`). A press writes it, as a window choice does; the server
  swaps the panel and commits `_var_<id>__tab`, which the panel host mirrors to
  `v.<id>.tab`. A value is untrusted and refused unless it is a member index of
  every panel it selects (`Renderer.refusals`), so a hand-edited URL can never
  bake a non-existent surface; an old `ui.<id>` link is not read and lands on
  the declared tab. The restore is flash-free because the GET bakes the
  selected surface directly, and the SSE connect seeds the open set from the
  session's variables so it streams live from the first paint.
- **Open popup.** `ui_<PopupHostId>`, mirrored to `ui.<PopupHostId>`,
  committed by the open/close swaps (ADR 0025). It carries no pending twin, because nothing on the page DISPLAYS a
  popup selection — the dialog itself is what the swap patches in, so there is
  no highlight to keep instant. Only the VALUE differs in kind — a surface id rather than
  a member index — because the popup host is not a bake group: any registered
  surface can appear there, one at a time. `Renderer.openPopup` clamps it,
  ignoring a claim naming a surface this dashboard does not host, which is the
  popup's equivalent of `resolveActive`'s index clamp.

  It is not a node variable because no node can declare it: the popup host
  lives in `theme.chrome`, outside every node, so the page shell declares and
  mirrors it. ADR 0033's root declaration is what would let it become one.

  The signal is authoritative **whenever it is present, `""` included** — that is
  how a client says "I closed it" — and only its absence (the one signal-less
  connect above) falls back to the param, so a stale URL cannot resurrect a
  dismissed dialog on every retry. That is the ordinary signals-beat-query
  precedence, not a popup rule. This reverses the original decision that popups are transient and
  "must not resurrect on reload": if you have a dialog open and you refresh,
  you expect it back — and on a phone, backgrounding the tab is how you read a
  notification, not how you dismiss a dialog.

## Other candidates this tier serves

Same shape — node-scoped, client-mutated, survives reload, informs first
paint, so each is a node variable (decision 3):

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
- The read path is small and bounded to the HTTP layer (`popupOf` = the
  popup's signal, else its query param; `varChoicesOf` for `v.`); the write path is
  one `data-fh-url` attribute per selection.
- **Datastar specifics (verified against v1.0.4):** `data-query-string` and
  `data-persist` are Pro; the free bundle has neither, and `data-persist`
  targets storage the server never sees anyway. Re-verify on upgrade — if
  `data-query-string` becomes available, it replaces `data-fh-url`, except for
  the popup's history entries.
- The tao's "Restrained Signal Usage" sanctions a tab index as an appropriate
  signal — `_var_<id>__tab` and `ui_popups` are not an anti-pattern; the URL
  mirror is the orthogonal persistence layer.
