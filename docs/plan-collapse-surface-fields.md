# Plan: collapse the `Surface` field set — adopt ADR 0002 options A + B (fh-datastar-view)

Adopts the two un-adopted options recorded in
[ADR 0002, Update 2026-07-01](adr/0002-multi-dashboard-popups-and-navigation.md#update--2026-07-01-two-open-questions-on-collapsing-the-surface-field-set-not-adopted):

- **A** — make **all** surfaces chrome-less: the chrome wrapper lives in a
  template-owned host, not a per-surface card rendered by the backend.
- **B** — **derive** a surface's host id from `bakeInto`/`bakeAs` and **delete
  the `mount` field**.

Together they shrink `Surface` from **8 fields to 5**, delete the per-surface
chrome path, and make popup and tab *literally the same primitive* (a
lazily-activated surface that inner-replaces into a host, evicting its host
siblings), differing only by their host's CSS.

## The product decision this entails (flagged for review)

Option A is only reachable by **dropping stacked popups** (ADR 0002 §A: "the
*only* way to make popups chrome-less with a template-owned host is to drop
stacked popups"). After this change a connection shows **at most one** popup at a
time — opening a second evicts the first, exactly as tab panels already evict
each other. The 2026-06-25c mount-unification already noted stacking is "rarely
used"; adopting A commits to giving it up. **If you want to keep stacked popups,
stop here** — A cannot be done without this trade, and B is gated on A (they
share the "one host per mount" premise).

## End-state model

```scala
case class Surface(
    content: LayoutNode,
    bakeInto: Option[String] = None, // host Component id (first-paint bake target); None ⇒ the popup overlay host
    bakeAs: Option[String] = None,   // template var the bake fills (e.g. "panel"); host id is {{bakeInto}}_{{bakeAs}}
    bakeIndex: Option[Int] = None,   // position within the bakeInto group (cookie-selectable, ADR 0005)
    defaultOpen: Boolean = false     // shown from first paint (a tab default; popups never bake)
) derives ConfiguredDecoder
```

Gone: `mount` (derived), `chrome` (all surfaces chrome-less), `stack` (popups no
longer stack → open is uniformly inner-replace-and-evict).

**Host id is derived, single source of truth:**

```scala
// on Surface — the live-patch target id AND the eviction group
def hostId: String = (bakeInto, bakeAs) match
  case (Some(into), Some(as)) => s"${into}_${as}"   // a tab panel: <tabsId>_panel
  case _                      => Dashboard.PopupHostId // the popup overlay: "popups-body"
```

This *enforces* the host-naming convention the ADR names: the host element's id
**must** be `{{bakeInto}}_{{bakeAs}}` (the `tabs` card already renders
`<div id="{{id}}_panel">` with `bakeAs = "panel"`, so `hostId == <id>_panel`
matches — no tab behaviour change). It is field *composition*, not the id-suffix
*parsing* the ADRs forbid.

## Design

### The theme owns the dashboard chrome (A, generalized)

Making popups chrome-less removes the per-surface `<dialog>` + ✕ the backend used
to generate; the dialog becomes a **single static host** popup content
inner-replaces into. But that host is the *last* presentation literal in
`renderPage` (`<main id="dashboard">…</main><div id="popups">`) — so rather than a
narrow `overlay` field, the whole **dashboard chrome moves into the theme**. This
finishes the presentation-as-data arc: the backend holds **zero** frame HTML.

- `Theme` gains a `chrome: String` — a Mustache template with a single
  `{{{body}}}` hole, owning the visible frame: the `#dashboard` swap target,
  *where* the popup host sits, and any header/nav a theme wants. Authored in
  `theme.libsonnet` (so all frame HTML stays in jsonnet, per the module's
  no-literals-in-Scala discipline).
- **The theme is self-contained — the popup host is inlined in `chrome`.** The
  `<dialog>` + ✕ + close-`@post` + `#popups-body` are written directly in the
  theme's `chrome` string; the theme imports **no** component library (a theme is
  presentation and must not depend on the widget library):
  ```jsonnet
  chrome: |||
    <main class="container" id="dashboard">{{{body}}}</main>
    <dialog id="popups" open class="popup">
      <button class="popup-close" data-on:click="@post('/sse/popup/close')">✕</button>
      <div id="popups-body"></div>
    </dialog>
  |||
  ```
  (An earlier iteration exported the host as a `c.popupHost()` component builder
  the theme composed, but that inverted the dependency theme → components;
  inlining keeps the theme a leaf. Trade: the close-URL lives in the theme — fine
  with a single theme; the one-place definition just moved to the theme.) A theme
  with no popups drops the `<dialog>`.
- `renderPage(states, uiState)` = execute `theme.chrome` with `body =
  renderBody(states, uiState)` (compiled once, like `themeStyle`). **`renderBody`
  is unchanged** — it is still `themeStyle + rendered card`, the payload navigate
  inner-patches into `#dashboard`. Clean split: `renderBody` = *inside*
  `#dashboard`; `chrome` = the frame *around* it, which persists across navigate.
- **The document shell stays in `Server.page()`** (visible-frame boundary,
  chosen 2026-07-02): `<head>`, the Datastar `<script>`, `<body
  data-init="@get('/sse/dashboard/<slug>/patch')">`, the `popstate` handler, and
  the `theme.stylesheets` `<link>`s — Datastar bootstrap + per-request slug
  wiring, genuinely framework plumbing, not theme.
- **Contract (the theme creator's responsibility):** `theme.chrome` MUST contain
  an element `id="dashboard"` wrapping `{{{body}}}` (the navigate/reload swap
  target) and the popup host if the dashboard uses popups. Same load-bearing kind
  of convention the `tabs` card already carries (`id="{{id}}_panel"`).
  `Dashboard.validate` gains a guardrail — if `theme.chrome` is non-empty and
  lacks `id="dashboard"`, it is a hard error (fail loudly, never silently break
  navigation).
- **Fallback:** empty `theme.chrome` ⇒ the backend uses a minimal default
  `<main class="container" id="dashboard">{{{body}}}</main>` (the one irreducible
  frame literal, no popup host — for a popup-less dashboard that ships no theme).
  A theme wanting popups provides the full chrome above.
- Visibility is CSS-only (theme, no signal, no server state): the dialog hides
  when its body is empty — `dialog.popup:has(#popups-body:empty){display:none}`.
  Open patches content in (not empty ⇒ shown); close patches it empty (⇒ hidden).
  No flash on first paint (the body ships empty).

`Surface.hostId` returns `Dashboard.PopupHostId = "popups-body"` for a popup — the
one shell id the backend knows, replacing today's `surf.mount.getOrElse("popups")`
constant (it moves from `"popups"` to the inner region `"popups-body"`, so the ✕
persists across content swaps).

**Rejected alternatives:** (a) a dedicated `Dashboard.overlay` string for just the
popup host — superseded by theme-owned chrome, which removes `#dashboard` too, not
only `#popups`. (b) The theme owning the *whole document* (`<head>`/`data-init`) —
declined 2026-07-02: that shell is Datastar bootstrap + per-request slug wiring,
not dashboard frame; it stays in `Server.page()`.

### Open, switch, and close are one operation — `swapHost`

Opening a popup, switching a tab, and closing a popup are the **same host-swap**:
*evict whatever surfaces occupy this host, set the new occupant, inner-patch it
in.* Close is just the degenerate case where the new occupant is empty (a tab bar
has no "no tab" button, so a tab host is never swapped to empty; a popup host can
be — that UI asymmetry is the only difference). So one helper covers all three:

```scala
// evict the host's current occupants, set the new one, patch it in
private def swapHost(session, renderer, host: String, newSurface: Option[String]): IO[Unit] =
  for {
    open <- session.open.get
    evict = open.filter(sid => sid != newSurface.orNull &&
                                renderer.surface(sid).exists(_.hostId == host))
    _ <- session.open.set(open -- evict ++ newSurface.toSet)
    states <- stateStore.snapshot
    _ <- newSurface match {
      case Some(sid) => renderer.renderSurface(sid, states).traverse_(html =>
                          session.control.offer(Datastar.patch(html, PatchMode.Inner, Some("#" + host))))
      case None      => session.control.offer(
                          Datastar.patch(s"""<div id="$host"></div>""", PatchMode.Outer, None))
    }
  } yield ()
```

- **Open a popup / switch a tab:** `swapHost(surf.hostId, Some(id))` — the single
  path for both. The `stack` branch is deleted (popups no longer append).
- **Close a popup:** new route `POST /sse/popup/close` →
  `swapHost(Dashboard.PopupHostId, None)`. No per-surface id needed (at most one
  popup is open), so the old `POST /sse/surface/close/:id` +
  `removeElement("#s_<id>")` path is removed.
- **Navigate:** its popup reset patches `<div id="popups-body"></div>` (was
  `<div id="popups"></div>`); the `#popups` dialog shell itself persists (it is
  outside the `#dashboard` body that navigate inner-swaps).

### `renderSurface` returns bare content always

With every surface chrome-less, `renderSurface` drops `renderChrome`/`s.chrome`
and returns `render(content, …)` directly. `renderChrome`, `Renderer.surfaceRootId`,
and `LayoutNode.surfaceRootId` (the old `s_<id>` chrome root / close selector)
are deleted; `surfacePrefix` stays (it still namespaces a surface's inner node
ids). The `popup` chrome card is deleted from the library.

## Changes by file

### `model/Dashboard.scala`
- `Surface`: delete `mount`, `chrome`, `stack`; add the `hostId` method; keep
  `content`/`bakeInto`/`bakeAs`/`bakeIndex`/`defaultOpen`. Refresh the scaladoc
  (drop the `mount`/`chrome`/`stack` paragraphs; document `hostId` + the
  `{{bakeInto}}_{{bakeAs}}` convention).
- `Theme`: add `chrome: String = ""` (the frame template, `{{{body}}}` hole).
  Refresh the `Theme` scaladoc.
- `Dashboard`: add companion `val PopupHostId = "popups-body"`.
- `validate`: add the guardrail — non-empty `theme.chrome` lacking `id="dashboard"`
  is a hard error.

### `runtime/Renderer.scala`
- `renderPage`: execute the compiled `theme.chrome` with `body = renderBody(...)`;
  fall back to `<main class="container" id="dashboard">{{{body}}}</main>` when
  `chrome` is empty. Delete the `<main id="dashboard">…</main><div id="popups">`
  literal. `renderBody` is unchanged. Compile `theme.chrome` once at construction
  (alongside `themeStyle`).
- `renderSurface`: return the bare `render(content, …)`; delete `renderChrome`
  and the `s.chrome` branch.
- Delete `Renderer.surfaceRootId` and its `object Renderer` delegate; keep
  `surfacePrefix`/`sanitize`. The bake block in `render(Component)` and
  `bakeGroup`/`resolveActive`/`selectedSurfaces` are **unchanged** (they key on
  `bakeInto`, which survives).

### `runtime/Server.scala`
- Route table: delete `POST /sse/surface/close/:id`; add
  `POST /sse/popup/close`.
- Add the `swapHost(session, renderer, host, newSurface)` helper (above).
  `openSurface(id)` becomes `swapHost(surf.hostId, Some(id))`; the `popup/close`
  route becomes `swapHost(Dashboard.PopupHostId, None)`. Delete the old
  `openSurface` `stack` branch and the `surf.mount.getOrElse("popups")` line.
- `navigate`: reset patch → `<div id="popups-body"></div>`.
- `uiStateOf`/cookie path unchanged.

### `build/DashboardBuild.scala`
- `surfaceOf` field list: remove `"mount"`, `"chrome"`, `"stack"`; keep
  `"bakeInto"`, `"bakeAs"`, `"bakeIndex"`, `"defaultOpen"`.

### `resources/dashboards/components.libsonnet`
- `openPopup` builder: drop the `mount` param; inline surfaces set **only**
  `{ content }` (no `mount`/`chrome`/`stack` — `hostId` derives to the popup
  host). Registered-id form unchanged.
- `tabs` builder: inline surfaces drop `mount`/`chrome`/`stack`; keep
  `bakeInto`/`bakeAs`/`bakeIndex`/`defaultOpen`. The tab click's `surface/open`
  and cookie write are unchanged.
- Delete the old `popup` `_components` chrome card (per-surface `<dialog id=s_…>`)
  and its `cards` exposure; `c.openPopup` keeps delegating to the (opener-only)
  builder. The `<dialog>` host is **not** a component export — it is inlined in
  the theme's `chrome` (below), so `components.libsonnet` holds no popup-host
  fragment.
- `closePopup` builder: repoint from the deleted `/sse/surface/close/:id` to the
  id-less `POST /sse/popup/close` (keep an accepted-but-ignored `id` arg so
  existing call sites compile).
- `openPopup` builder: drop the `mount` param (already noted above).

### `theme.libsonnet`
- `chrome:` **inlines** the frame + the popup `<dialog>` host directly (the theme
  imports no component library):
  `'<main class="container" id="dashboard">{{{body}}}</main>' + '<dialog id="popups" open class="popup">…✕…<div id="popups-body"></div></dialog>'`.
  The ✕/close-`@post('/sse/popup/close')` live here, once. A theme with no popups
  drops the `<dialog>`.
- CSS: migrate the popup rules onto `dialog.popup`; add
  `dialog.popup:has(#popups-body:empty){display:none}`. Remove any
  `.tab-panel-content` / per-surface-root rules that referenced the deleted
  chrome.
- Dashboards themselves need **no change** for the overlay (they inherit
  `theme.chrome`); they change only if they used `openPopup(…, mount=…)`.

## Phasing (each phase = one self-contained, green commit)

The gate after every phase is `sbt fh-datastar-view/testFull` (needs no live
HA). `sbt dashboardBuild` (live HA) + the in-browser walk-through are
**best-effort**: run if `192.168.1.174:8123` is reachable, else note as pending
— never block.

1. **Adopt A — popups chrome-less, non-stacking, theme-owned chrome.** The
   behavioural core. `Theme.chrome` field + `renderPage` executes it (with the
   `#dashboard` fallback) + the `validate` guardrail; `theme.libsonnet` `chrome:`
   inlining the frame + the popup `<dialog>` host (✕ + close-URL) + popup CSS.
   **Flip the `Surface` defaults**
   `chrome: "" `, `stack: false` (was `"popup"`/`true`) so *all* popups —
   registered (in `dashboard.jsonnet`) and inline — become chrome-less /
   non-stacking with **no per-surface edit and no dashboard-file change**; the
   fields still exist (deleted in phase 3), and `tabs`' explicit `chrome:''`/
   `stack:false` become redundant-but-harmless. Add the `swapHost` helper **keyed
   on the existing `surf.mount`** (default `popups-body`); `openSurface` =
   `swapHost(host,
   Some(id))`, the new `POST /sse/popup/close` route = `swapHost(popups-body,
   None)`; the old `openSurface` `stack`/append branch and `surface/close/:id` +
   `removeElement` are gone; navigate resets `#popups-body`. `renderSurface`
   needs **no change** (the `chrome:''` bare path already exists). Tests: popup
   open now inner-patches `#popups-body` (not append `#popups`); `popup/close`
   clears it; a second popup replaces the first; `renderPage` frames `renderBody`
   in `theme.chrome` (with `#dashboard` + popup host); the empty-chrome fallback;
   the `validate` guardrail; stacking test removed.
2. **Adopt B — derive the host, delete `mount`.** Add `Surface.hostId` +
   `Dashboard.PopupHostId`; change `swapHost`'s host source from `surf.mount…` to
   `surf.hostId` (the one place it is read now); delete the `mount` field, the
   jsonnet `mount:` keys, and `"mount"` from `surfaceOf`. Behaviour-identical
   (`hostId` reproduces the phase-1 constants: `<id>_panel` for tabs,
   `popups-body` for popups). Tests: `hostId` derivation; eviction still groups
   correctly.
3. **Collapse `chrome` + `stack`; delete the per-surface chrome path.**
   `renderSurface` returns bare content (delete `renderChrome`, `surfaceRootId`);
   delete `chrome`/`stack` from `Surface`, `surfaceOf`, and the jsonnet builders;
   delete the `popup` chrome card. (`swapHost` is already the single uniform path
   from phase 1 — nothing to change in the open logic here.) `Surface` now has
   its final 5 fields. Tests: drop chrome/stack fixtures; assert bare surface
   content.
4. **Close-out.** Append a dated `## Update — 2026-07-02: options A + B adopted;
   the theme owns the dashboard chrome` to ADR 0002 (supersedes the 2026-07-01
   "not adopted" note: `Surface` collapses to `(content, bakeInto, bakeAs,
   bakeIndex, defaultOpen)`; popups no longer stack; host derived; the
   `#dashboard` frame + popup host move from a `renderPage` literal into
   `theme.chrome`, with the document shell staying in `Server.page()`). Refresh
   the `docs/adr/README.md` 0002 line and the memory status note. Note in-browser
   verify pending if not yet done.

## Verification

- `sbt fh-datastar-view/testFull` after each phase — the gate.
- `sbt dashboardBuild` against live HA — surfaces hoist with the reduced field
  set; **best-effort, skip + note if HA unreachable**.
- `sbt dashboardServe`, in a browser: a popup opens (dialog shows), a second
  popup **replaces** the first (no stack), ✕ closes it (dialog hides); tabs still
  switch + persist across reload/navigate (ADR 0005 unaffected); navigate clears
  the popup and the dialog shell survives.

## Execution with agents (sonnet 5, medium think)

Run the four phases as **separate commits**, each a **fresh** agent
(`subagent_type: general-purpose`, `model: "sonnet"`) so no phase inherits a
warm-but-stale context. In each agent prompt: instruct **medium-depth thinking**
(the Agent tool exposes only a coarse model selector — medium-think is a prompt
instruction, not a flag), hand it *this* plan's phase, and require:

- **Explicit-file staging only** — `git add <exact paths>`, never `git add -A`;
  the working tree carries concurrent not-mine changes (TODO.md, the `*etasje`
  dashboards, ha-api/home files) that must stay untouched and uncommitted. If a
  phase must edit a file that also has a pre-existing not-mine hunk (e.g.
  `Server.scala`'s stray TODO, or `dashboard.jsonnet`), `git stash push -- <file>`
  around the commit and `stash pop` after (the pattern used in the tab-state
  series); never disturb an unrelated stash.
- **The gate**: `sbt fh-datastar-view/testFull` green before committing; tests
  live *with* the phase that introduces the behaviour.
- **The scalafmt workaround**: after `sbt scalafmt`, if `JsonnetBuild.scala` was
  only cosmetically rewrapped, `git checkout HEAD -- <its path>`.
- **Commit trailers** as configured; do not push.
- One phase per agent; the parent reviews each commit before launching the next
  (do not run the four in parallel — 2 depends on 1, 3 on 2).

## Out of scope

- The declared-`state` sugar / single-JSON-cookie consolidation (deferred, ADR
  0005 Decision 3) — untouched.
- Re-introducing stacked popups (a second overlay host) — explicitly given up by
  option A; recoverable later by adding a second `overlay` host if ever needed.
