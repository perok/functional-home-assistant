# ADR 0002 — Multiple dashboards, popup surfaces, and navigation

- **Status:** Accepted
- **Date:** 2026-06-23 (consolidated 2026-07-04; navigation became a real page
  load 2026-07-27)
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
*action* on a normal click); reuse the **single** SSE stream *within* a
dashboard; the backend owns popup state **per connection**; keep phase
discipline (authoring is build-time composition); and one mechanism that serves
popups *and* tabs. Correct laziness: a closed popup costs no render and no push.

## The design

### Dashboards: slug = the entrypoint's key

Every dashboard is a key in the workspace's one entrypoint, `site.pkl`, and
that key is its slug (ADR 0021). A `Renderer` is built per slug and served at
`/d/:slug`; which slug `/` serves is the site's own `default`. A connection shows exactly one dashboard for its whole lifetime,
so node ids are unique within a dashboard and **not slug-prefixed**.

### Surfaces: lazily-activated subtrees

A **surface** (`model.Surface`) is a named layout subtree registered in
`Dashboard.surfaces`, rendered on demand and streamed only while it is
*active* — for a **user-activated** surface, while a connection has it open;
for a **state-activated** one (an if/else branch — ADR 0007), while its
condition over live entity state selects it. Its fields are `(content,
bakeInto, bakeAs, bakeIndex, activation)`, where `activation` is the sum
`User(defaultOpen) | State(condition)`:

- **Every surface is chrome-less** — `renderSurface` returns bare content. A
  popup's `<dialog>` is a plain `popup` *container card* composed into the
  surface's content by the authoring layer (the `PopupSurface` mapping default
  wraps registered surfaces; `openPopupInline` wraps inline ones), not backend
  chrome; the theme styles `.popup` as a class contract.
- **The host is derived, not authored** — `Surface.hostId` is
  `{bakeInto}_{bakeAs}` for a baked tab panel (enforcing the
  `id="{{bakeInto}}_{{bakeAs}}"` host convention the `tabs` card template
  honours) and the theme's popup mount (`Dashboard.PopupHostId`, `#popups`)
  otherwise. The host is both the live-patch target and the eviction group.
- **`bakeInto`/`bakeAs`/`bakeIndex`/`activation`** drive first-paint baking:
  the component whose id equals `bakeInto` receives the selected member's
  rendered content under the template var `bakeAs`, so the selected panel is
  in the initial HTML with no round-trip and no flash. How the member is
  selected is the group's activation mode: user-activated groups take the
  `defaultOpen` (or URL-restored — ADR 0005) member; state-activated
  groups take the first member whose condition holds (ADR 0007). Baked HTML
  and a later live switch are byte-identical.
- Surface node ids are namespaced (`s_<id>__…`, `LayoutNode.surfacePrefix`) so
  they never collide with the main page.

**Popups do not stack** (one open at a time). The lost capability — two popups
open at once — was unused and is recoverable via a second overlay host; giving
it up is what lets every surface be chrome-less and open/switch/close collapse
into one primitive.

### One primitive: `swapHost` (within a dashboard)

Open, switch, and close are the same operation (`Server.swapHost`): evict
whatever surface(s) occupy a host, set the new occupant, inner-patch the host —
or patch it to an empty `<div>` for a close (`POST /sse/popup/close`; the
transient dialog simply disappears). A tab switch and a popup open are
`swapHost(host, Some(id))`; no server state tracks "is a popup open" beyond the
session's open set, and no signal is pushed back for it — the tap that asked for
the swap sets `ui_<hostId>` itself, exactly as a tab button sets its own. The
popup host is a selection like any other; only its VALUE is unusual, naming a
surface id rather than a member index, because any registered surface can appear
there and only one at a time. Crossing to ANOTHER dashboard is not one of these — it is a
document load (below).

### Per-connection sessions over the one SSE stream

Each SSE connection mints a `conn` id and pushes it as the first
`datastar-patch-signals` event; Datastar then sends `conn` among the signals on
every action `@post`, correlating the POST to its stream. A `Session` (keyed by
`conn` in `Sessions`) holds its `slug` — **fixed**, since another dashboard is
another document and therefore another connection — the set of **open** surface
ids, and a **control** queue the action handlers push patches into.

**Live entity patches are rendered once per slug, for everyone.** One background
subscription to the state stream per dashboard (`Server.sharedPatchPublishers`,
run by `Server.resource`) selects what a change touches, renders it, diffs
against a per-slug `FragmentLog`, and publishes the changed fragments on a
per-slug topic. The topic is **one multiplexed** stream of slug-tagged events, so
a connection subscribes once and drops every tag but its own; that (rather than a
topic per slug) is what lets `push` mint a slug after a connection has opened.
N viewers of one slug cost one render, not N — including viewers of an open
popup or a selected tab, which is what distinguishes this from the earlier
design.

**Who may see a patch is decided by the session that sends it.** Nothing is broadcast:
the recorder writes a changelog and each connection pulls what it is owed, against its
own open set and its own record of what its DOM holds (ADR 0012). So a patch carries no
audience tag and passes no filter at the wire edge — it exists because the session that
will send it asked for it. State-activated surfaces need no special handling either:
their selection is server truth, identical for every viewer (ADR 0007), so a node inside
an `If` branch nested in a tab panel is visible exactly when that tab panel is.

That also dissolves the one case that used to resist a shared rendering — a flip placing
a branch whose subtree mounts a client-selected member (tabs inside an `If`). The session
renders it with its own selections when it pulls, so it arrives as one complete patch
with that viewer's panel already inside it, and there is no deferred render and no memo
keyed by what the render reads.

Almost nothing else can vary anyway: under the self/mount split (ADR 0008) a container
patches its `self`, which holds no mount, so only a render that CREATES a subtree can
differ per viewer.

This replaced a **shared/per-session split**, in which open surfaces and bake-group
owners were re-rendered once per connection against a per-session diff cache, and then a
**shared pass with an audience tag**, in which one render per slug was addressed to
whoever could see it. Both were cost models — "render shared what is cheap to share" —
and both bought a second vocabulary to describe who a rendering was for. What replaced
them is not a third: N viewers still cost one render of each changed node, but through a
per-slug render cache rather than through a shared pass, and the question "who is this
for" has one answer — the session doing the rendering.

On a live-reload hot-swap the recorder re-arms with the new renderer and a fresh
per-slug log; a change dropped in the brief swap window is repaired by the full body
repaint every connection does on reload.

### One click slot, whole Datastar expressions

A component's click target is a single `onclick` slot holding the **entire**
Datastar expression (spliced as literal text into
`data-on:click="{{{onclick}}}"`):

- service call → `@post('/sse/action/<domain>/<service>/<entity_id>')`
- popup → `@post('/sse/surface/open/<id>')` / `@post('/sse/popup/close')`

This is why reuse "just works": `c.button(eo, action=c.tap.openPopup('x'))` needs
no new template.

**Going somewhere is the exception, and it is not an expression at all.** A `TapAction`
that navigates carries an `href` (`d/<slug>`, relative so `<base href>` resolves
it under ingress), and a card whose root can be an `<a>` must prefer it — the
`button` template branches on `{{#href}}` and emits
`<a class="button card" href="…">` instead of a scripted `<button>`. A link the
browser understands is worth the branch: middle-click, open-in-new-tab, the
status-bar preview, and a click that works before Datastar has loaded. (BeerCSS
styles buttons as `:is(button,.button)`, so the anchor form is visually
identical.) The `TapAction` also carries the equivalent `onclick`
(`window.location.assign(new URL('d/<slug>', document.baseURI))`) for cards whose
root element cannot be an anchor — `entityCard`'s `<article>` — so one authored
`c.tap.navigate('x')` renders correctly wherever it is dropped.

### Navigation is a real page load

Going to another dashboard is an ordinary document load of `/d/:slug` — an
`<a href>` where the card can be one, `location.assign` where it cannot (above).
The browser owns the history entry; there is no `pushState`, no `popstate`
handler, and no `/sse/navigate` route.

This replaced an in-place body swap over the surviving SSE stream. That design
existed to keep one stream and one session alive across a dashboard change, and
the reason it stopped paying is that **nothing in that session is worth
keeping**: entity state is re-seeded from `StateStore` on connect, the log is
reset by the swap anyway, and both the open popup and the selected tab restore
from the URL (ADR 0005) — so a full load re-derives the whole view with
no flash. What it cost was real: hand-rolled history management (the Datastar
tao's named anti-pattern), a mutable per-session slug that every render path had
to re-read, a `<head>` the body patch could not reach (so a differently-themed
target needed an explicit theme/title morph), and buttons that a browser cannot
middle-click or open in a new tab.

Remaining gap: only `button` renders the anchor form. A navigating `entityCard`
falls back to the scripted click, so it is not middle-clickable; promoting it
means wrapping its `<article>` in the template, not a backend change.

#### What a page load costs, and the one thing worth carrying

The argument above is that nothing in the old session is worth keeping, because
everything the view is made of re-derives: entity state from `StateStore`, the
open popup and the selected tab from the URL (ADR 0005). One thing does not
re-derive, and it is not view state the server knows — the **scroll offset**.
Returning to a long dashboard therefore landed at the top, every time.

The browser cannot fix this on its own here. A document holding a streaming
`fetch` — which every page does, that is the Datastar SSE `@get` in `data-init`
— is not back/forward-cache eligible, so even a back button re-loads the
document rather than restoring a live one; and a link back to the dashboard is a
forward navigation, which starts at the top by definition.

So the shell carries it: `fhScroll(slug)` (in `src/js/shell.ts`, bundled and
inlined as `Server.UrlSyncScript`) saves `scrollY` to `sessionStorage` on
`pagehide` and re-applies it as the **last thing in `<body>`**, with
`history.scrollRestoration='manual'` so the browser's own (zero) restore cannot
land on top of it. Last in the body is what makes it
invisible: the body is server-rendered and the stylesheets are render-blocking,
so the document has its height before the closing script runs and the offset is
set before the first paint.

`sessionStorage` and not the URL mirror, deliberately — see ADR 0005's tiering.
Per tab and per slug, so two tabs on one dashboard do not drag each other, and a
link somebody shares does not land them mid-page.

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
the signal + URL mirror (ADR 0005).

### The theme owns the chrome

`Theme.chrome` is a Mustache frame template with two holes, owning the
`#dashboard` swap target (`{{{body}}}`) and the popup host
(`<div id="popups">{{{popups}}}</div>`), inlined in the theme (a theme imports
no component library — it is presentation, a leaf).

The second hole is what makes a **restored** popup flash-free: a refresh
carrying `?popup=<id>` (ADR 0005) bakes the dialog into the served HTML, the
same way a selected tab panel is baked into its owner. Without it the dialog
cannot appear until the stream connects and patches `#popups`, so the dashboard
paints first and the dialog arrives late — most visible on a phone. The baked
HTML is the same `renderSurface` call the connect would patch, so the patch that
follows is a no-op morph rather than a second paint; a theme that omits the hole
still works, it just flashes. The theme keeps deciding WHERE the host lives —
the backend only fills a host it already addresses by id on every
open/switch/close.

The backend holds **zero** frame HTML; an empty chrome
falls back to a minimal `<main id="dashboard">` frame. `Dashboard.validate`
fails loudly if a non-empty chrome lacks `id="dashboard"`. The document shell
(`<head>`, Datastar `<script>`, `data-init`, the theme's
stylesheet `<link>`s and script `<script type="module">`s — `Theme.scripts`
carries JS a theme's CSS needs, e.g. BeerCSS's slider fill; behavior stays
Datastar's) stays in `Server.page()` — Datastar bootstrap and per-request
wiring, not dashboard frame.

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
- **A hollow mount plus a per-connection fill**: the first attempt at serving
  viewers on different tabs from one shared render — insert the branch with its
  tabs mount EMPTY, then have each connection fill its own. It works, and it was
  wrong twice over: two DOM updates for one change, and a rendering "for nobody"
  that promptly leaked a blank tab index into live markup, because a mount
  carries client-dependent ATTRIBUTES and not merely children. Having the session
  render the branch with its own selections, above, is the same idea done at the
  right boundary.
- **Baking whichever member the connected clients happen to agree on**: would
  have removed the hollow mount for the common case by reading the union of
  every session's open set. It makes the rendered bytes depend on the audience —
  the same dashboard in the same state producing different HTML depending on who
  is watching — for no gain over letting each session render its own.

## Consequences

- Open/close are pure backend state transitions whose patches ride the one SSE
  stream; closed surfaces are free. A dashboard change is outside that model
  entirely — it is a new document, a new stream, a new session.
- There is ONE render pipeline and one log per slug. What differs per client is
  a filter (which patches reach it) and, in one case, a render performed at the
  edge — never a second pass with its own cache.
- Datastar specifics relied upon (patch modes, signal round-tripping of `conn`)
  are pinned to **v1.0.2** — re-verify on upgrade.
- Not covered: a nav-menu UI between dashboards.
