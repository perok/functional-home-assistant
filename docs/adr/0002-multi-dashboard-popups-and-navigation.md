# ADR 0002 — Multiple dashboards, popup surfaces, and in-place navigation

- **Status:** Accepted
- **Date:** 2026-06-23 (consolidated 2026-07-04)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The dashboard originally served exactly one jsonnet entry at `GET /`, over a
single SSE stream that re-renders and pushes every affected node on every state
change. Three needs arose together:

1. **Multiple dashboards**, each addressable by an id slug.
2. **Popups** opened by clicking ordinary components, updated live **only while
   open**.
3. **Cards that navigate to another dashboard.**

Constraints: reuse the existing components (a popup/navigate is just another
*action* on a normal click); reuse the **single** SSE stream; the backend owns
popup state **per connection**; keep phase discipline (authoring is build-time
composition); and one mechanism that serves popups *and* tabs. Correct
laziness: a closed popup costs no render and no push.

## The design

### Dashboards: slug = filename

Every top-level entry file in the dashboards dir is a dashboard whose slug is
its filename (`dashboard.jsonnet` → `dashboard`, the default `/`; `.pkl`
entries work identically — ADR 0006). A `Renderer` is built per slug and served
at `/d/:slug`. A connection shows one dashboard's DOM at a time, so node ids
are unique within a dashboard and **not slug-prefixed**.

### Surfaces: lazily-activated subtrees

A **surface** (`model.Surface`) is a named layout subtree registered in
`Dashboard.surfaces`, rendered on demand and streamed only while a connection
has it open. Its fields are `(content, bakeInto, bakeAs, bakeIndex,
defaultOpen)`:

- **Every surface is chrome-less** — `renderSurface` returns bare content. A
  popup's `<dialog>` is a plain `popup` *container card* composed into the
  surface's content by the authoring layer (`c.popup` / the `openPopup`
  builders), not backend chrome; the theme styles `.popup` as a class contract.
- **The host is derived, not authored** — `Surface.hostId` is
  `{bakeInto}_{bakeAs}` for a baked tab panel (enforcing the
  `id="{{bakeInto}}_{{bakeAs}}"` host convention the `tabs` card template
  honours) and the theme's popup mount (`Dashboard.PopupHostId`, `#popups`)
  otherwise. The host is both the live-patch target and the eviction group.
- **`bakeInto`/`bakeAs`/`bakeIndex`/`defaultOpen`** drive first-paint baking:
  the component whose id equals `bakeInto` receives the selected member's
  rendered content under the template var `bakeAs`, so the default (or
  cookie-restored — ADR 0005) panel is in the initial HTML with no round-trip
  and no flash. Baked HTML and a later live switch are byte-identical.
- Surface node ids are namespaced (`s_<id>__…`, `LayoutNode.surfacePrefix`) so
  they never collide with the main page.

**Popups do not stack** (one open at a time). The lost capability — two popups
open at once — was unused and is recoverable via a second overlay host; giving
it up is what lets every surface be chrome-less and open/switch/close collapse
into one primitive.

### One primitive: `swapHost`

Open, switch, and close are the same operation (`Server.swapHost`): evict
whatever surface(s) occupy a host, set the new occupant, inner-patch the host —
or patch it to an empty `<div>` for a close (`POST /sse/popup/close`; the
transient dialog simply disappears). A tab switch and a popup open are
`swapHost(host, Some(id))`; no server state tracks "is a popup open" beyond the
session's open set.

### Per-connection sessions over the one SSE stream

Each SSE connection mints a `conn` id and pushes it as the first
`datastar-patch-signals` event; Datastar then sends `conn` among the signals on
every action `@post`, correlating the POST to its stream. A `Session` (keyed by
`conn` in `Sessions`) holds a mutable `slug` (navigate re-points it), the set
of **open** surface ids, a **control** queue the action handlers push patches
into, and a last-rendered diff cache so only fragments whose HTML actually
changed are pushed.

Live entity patches are split by what they depend on. Main-page nodes that do
**not** own a bake group are a pure function of entity state, so they are
rendered **once per slug**: one background subscription to the state stream
per dashboard (`Server.sharedPatchPublishers`, run by `Server.resource`)
re-renders the affected nodes (reverse index + query-affected dynamic groups),
diffs against a **per-slug** cache, and publishes the changed fragments on a
per-slug topic — N viewers of one slug cost one render, not N. A connection
subscribes to every slug's topic and keeps only its *current* slug's events,
so navigate just re-points the filter (a dropped-or-duplicate fragment around
the navigate moment is harmless: navigate does a full body repaint, and
Datastar morphs are idempotent). Only what truly differs per client stays in
the per-session change loop with the session's own diff cache: each open
surface's nodes (a closed surface is never rendered) and bake-group-owner
nodes (their HTML bakes the client's cookie-selected member). On a live-reload
hot-swap the shared pass re-arms with the new renderer and a fresh per-slug
cache; a change dropped in the brief swap window is repaired by the full body
repaint every connection does on reload.

### One click slot, whole Datastar expressions

A component's click target is a single `onclick` slot holding the **entire**
Datastar expression (spliced as literal text into
`data-on:click="{{{onclick}}}"`):

- service call → `@post('/sse/action/<domain>/<service>/<entity_id>')`
- popup → `@post('/sse/surface/open/<id>')` / `@post('/sse/popup/close')`
- navigate → `@post('/sse/navigate/<slug>'); history.pushState(null,'','/d/<slug>')`

This is why reuse "just works": `c.button(eo, action=c.openPopup('x'))` needs
no new template.

### In-place navigation; URL handled client-side

Navigate is an in-place swap over the same stream: re-point the session's slug,
reset its popups + diff cache, clear the popup host, inner-patch the body into
the stable `#dashboard` container. Datastar v1 has no URL/redirect SSE event,
so the URL is updated client-side (`history.pushState` in the trigger); a
Back/Forward `popstate` handler re-posts the swap for the slug already in the
URL — a distinction only the client can make. `/d/:slug` deep-loads directly.

### The generic hoist: inline surfaces + `@@NODE_ID@@`

Authoring is primarily a top-level `surfaces` registry referenced by
`openPopup('id')`; an **inline** form (`openPopupInline` / per-tab content) is
hoisted at build time (`DashboardBuild.hoistInlineSurfaces`), because the
authoring language can't mint stable ids or mutate the registry. The pass is
deliberately **generic** — it knows nothing about popups, tabs, buttons, or
onclick wiring. A node carries an `inlineSurfaces: { <localKey>: {content, …} }`
marker; the pass mints the node's position-derived id, splices it into every
`@@NODE_ID@@` token in the subtree (the builders embed the token —
`DashboardBuild.NodeIdToken`), and lifts each surface to
`surfaces["<idBase>_<localKey>"]`. **One id story**: the id scheme (`pathId`,
`surfaceRootId`, `surfacePrefix`, `sanitize`) lives in `LayoutNode`, shared by
hoist and renderer, so a node's build-time id namespace equals its render-time
`{{id}}`.

### Tabs are pure composition

A tab group is N surfaces baked into one `tabs` card: the card's template owns
the button bar and the panel host (`<div id="{{id}}_panel">{{{panel}}}</div>`);
each tab's content rides the generic inline-surface hoist with
`bakeInto`/`bakeAs`/`bakeIndex`; the bar buttons open their panel surface
(eviction via the shared host) and set a per-group active signal that drives
the highlight client-side. **No tabs logic in the backend** — the runtime reads
only structural surface fields, never a card name. The active tab persists via
the cookie tier (ADR 0005).

### The theme owns the chrome

`Theme.chrome` is a Mustache frame template with a single `{{{body}}}` hole,
owning the `#dashboard` swap target and the popup host (`<div id="popups">`),
inlined in the theme (a theme imports no component library — it is
presentation, a leaf). The backend holds **zero** frame HTML; an empty chrome
falls back to a minimal `<main id="dashboard">` frame. `Dashboard.validate`
fails loudly if a non-empty chrome lacks `id="dashboard"`. The document shell
(`<head>`, Datastar `<script>`, `data-init`, `popstate`, stylesheet links)
stays in `Server.page()` — Datastar bootstrap and per-request wiring, not
dashboard frame.

## Rejected along the way (still guarding the design)

- **A `Mount` layout node** (backend-rendered host element + `MountKind`
  heuristic): pushed presentation back into Scala (hardcoded host HTML, a
  kind→card-name binding). The host belongs in a card template; presentation is
  data.
- **`children` as a multi-hole slot** (render all tab panels, `data-show` the
  active one): defeats surface laziness — hidden panels would receive SSE
  patches.
- **Stacked popups + per-surface `chrome`/`stack`/`mount` fields**: the fields
  co-varied; deriving the host and dropping stacking collapsed `Surface` from 8
  fields to 5 and unified open/switch/close. The derivations are safe only
  because the constraining assumptions (popups don't stack; a baked surface's
  host follows the `{{id}}_{{bakeAs}}` convention) are guaranteed, not
  inferred.
- **A theme-composed `c.popupHost()` component**: inverted the layering (theme
  → components); the host is inlined in the theme instead.

## Consequences

- Open/close/navigate are pure backend state transitions whose patches ride the
  one SSE stream; closed surfaces are free.
- Datastar specifics relied upon (patch modes, signal round-tripping of `conn`,
  `data-on:…__window`, client `history` access) are pinned to **v1.0.2** —
  re-verify on upgrade.
- Not covered: a nav-menu UI between dashboards.
