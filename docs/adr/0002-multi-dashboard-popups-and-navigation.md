# ADR 0002 — Multiple dashboards, popup surfaces, and in-place navigation

- **Status:** Accepted
- **Date:** 2026-06-23
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The dashboard served exactly one jsonnet entry at `GET /`, over a single global
SSE stream opened on page load that re-renders and pushes every affected node on
every state change. Three needs arose together:

1. **Multiple dashboards**, each addressable by an **id slug**.
2. **Popups** opened by clicking ordinary components (`button`, `entityCard`, …),
   where the content is updated live **only while the popup is open**.
3. **Cards that navigate to another dashboard.**

The governing constraints from the existing design and from the user:

- Reuse the existing components — a popup/navigate is just another *action* on a
  normal click, not a new widget.
- Reuse the **single** SSE stream; do not open a stream per popup.
- The backend owns popup state, **per connection** (so two clients can have
  different popups open).
- Keep the module's **phase discipline**: jsonnet does pure build-time composition
  and never sees live values; the renderer fills slots at runtime.

## Decision drivers

- One mechanism that serves popups now and **tabs** later (the user explicitly
  wanted the abstraction to make sense for tabs).
- Correct laziness: a closed popup costs **no** render and **no** push.
- Minimise client/server coupling — lean on Datastar's documented primitives
  (patch modes, signal round-tripping) rather than bespoke protocol.

## Decisions

### 1. Dashboard = one `*.jsonnet` entry; slug = filename

`ServerApp` globs the dashboards dir; every `*.jsonnet` is a dashboard whose
**slug is its filename** (`dashboard.jsonnet` → `dashboard`, the default `/`).
`*.libsonnet` stay libraries. A `Renderer` is built per slug and served at
`/d/:slug`; `Dashboard.slug` carries the id in the model.

Because a connection shows **one** dashboard's DOM at a time (popups reset on
navigate; the diff cache is per-connection and cleared on swap), **node ids are
not slug-prefixed** — `pathId` stays unique within its dashboard. The slug is a
dashboard-level identifier (routes, navigate target, renderer-map key), not a
per-node prefix.

### 2. Surfaces — a general lazily-activated subtree

A **surface** (`model.Surface`) is a named layout subtree registered in
`Dashboard.surfaces`, rendered on demand:

- `content` — its own layout tree (same node vocabulary as `card`).
- `group` — optional exclusivity group: opening a surface closes any open sibling
  sharing the group. Absent ⇒ stackable. (Exclusivity is the basis for **tabs**.)
- `mount` — optional target container id; default is the overlay `#popups` mount.
  (A tab panel would point at an inline mount.)

Surface nodes are indexed separately with **namespaced ids** (`s_<id>__…`) so the
change-loop only touches *open* surfaces (`surfaceComponentsFor`/
`surfaceDynamicIds`). `renderSurface` wraps the content in a `<dialog open>`
carrying the surface root id (`s_<id>`) and a wrapper-supplied close control wired
to the (backend-known) id — so inline popups close without the author knowing the
generated id.

### 3. Per-connection sessions over the reused SSE stream

Each SSE connection mints a `conn` id (server-side) and pushes it as the first
`datastar-patch-signals` event. Datastar then sends `conn` among the signals on
every action `@post`, correlating the POST to its stream. A `Session` (keyed by
`conn` in `Sessions`) holds:

- a **mutable** `slug` (so navigate can re-point the connection),
- the set of **open** surface ids,
- a **control** queue the action handlers push patches into (merged into the
  stream), and
- a **per-connection** last-rendered diff cache (popup content differs per
  client; this also fixes the latent multi-client miss of the old shared cache).

The change-loop renders the main page's affected nodes plus, for each open
surface, that surface's nodes. A closed surface is absent from the set, so it is
never rendered or pushed.

### 4. Unified click slot = a whole Datastar expression

A component's click target is a single `onclick` slot holding the **entire**
Datastar expression (spliced server-side as literal text into
`data-on:click="{{{onclick}}}"`), produced by a JSONata transform:

- service call → `@post('/sse/action/<domain>/<service>/<entity_id>')`
- popup → `@post('/sse/surface/open|close/<id>')`
- navigate → `@post('/sse/navigate/<slug>'); history.pushState(null,'','/d/<slug>')`

This is why reuse "just works": `c.button(eo, action=c.openPopup('x'))` /
`c.entityCard(eo, tap=c.navigate('y'))` need no new template. The slider keeps its
own in-template URL assembly because it needs the per-instance Mustache `{{id}}`
signal and is never a popup trigger.

### 5. In-place navigation; URL handled client-side

Navigation chosen as an **in-place swap over the same stream**: the navigate
handler re-points the session's slug, resets its popups + diff cache, clears the
`#popups` mount, and `inner`-patches the body of the stable `#dashboard` shell.

Datastar v1 has **no native URL/redirect SSE event** (only `patch-elements` /
`patch-signals`). Since `data-on` expressions are plain JS, the URL is updated
**client-side** via `history.pushState` in the navigate trigger expression. This
is also the correct split: a forward navigate does `pushState` + posts the swap,
whereas a Back/Forward `popstate` (a `data-on:popstate__window` handler in the
shell) only re-posts the swap for the slug already in the URL — a distinction only
the client can make, so the server stays out of URL handling. `/d/:slug`
deep-loads directly.

### 6. Inline surfaces hoisted to the registry at build time — a *generic* pass

Authoring is primarily a **top-level registry** (`surfaces: { id: { content } }`)
referenced by `openPopup('id')`. An **inline** form (`openPopup(c.column([...]))`)
is also allowed; jsonnet can't mint a stable id or mutate the registry, so a
build-phase pass (`DashboardBuild.hoistInlineSurfaces`, sibling to
`normalizeChildren`) does it.

The pass is deliberately **generic** — it knows nothing about popups, tabs,
buttons, signals, or onclick wiring. A node carries an `inlineSurfaces: {
<localKey>: { content, group?, mount? }, … }` map; for each marker-bearing node
the pass (1) mints a stable `idBase` from position, (2) splices that id into every
`@@NODE@@` token in the node's subtree, and (3) lifts each surface to
`surfaces["<idBase>_<localKey>"]`. The *trigger* — which template, the click
expression, any highlight — is composed entirely in **jsonnet**, which references
the id it can't mint as `@@NODE@@_<localKey>` (the builders embed the token; see
`NodeIdToken`). The hoist only borrows ids and lifts content; it does **not** build
onclick strings. The runtime model is then always the registry form.

This is the single mechanism behind both inline popups and tabs (decision 7) — no
per-feature build-pass special-casing.

### 7. Tabs as a thin layer over surfaces

Tabs are the payoff of modelling popups as a general *surface* abstraction
(decision 3). A tab group is **N surfaces sharing one exclusivity `group` and one
inline `mount`** (the panel container), plus a generated tab bar. No new runtime
state: the per-connection open-set already gives "show one at a time, update only
the visible one", and group eviction already implements switching.

Two small *runtime* additions make it tabs rather than popups:

- **Mount-aware rendering.** `Renderer.renderSurface` wraps an inline-mounted
  surface in a plain `<div id="s_<id>" class="tab-panel-content">` (no `<dialog>`,
  no ✕); `Server.openSurface` `inner`-patches the named `mount` (replace in place)
  instead of `append`-ing to `#popups` (stack). Switching is thus the existing
  open path with a different patch mode.
- **Default panel baked inline.** The `tabs` container carries an `initial`
  surface id; `Renderer` renders that surface's content into the panel at page-
  render time (with the surface's own `s_<id>__…` ids, so the baked HTML matches a
  later switch-back), and exposes `defaultOpenSurfaces`. The SSE handler seeds the
  session's open-set with them on connect / navigate / reload, so the default tab
  is live from the first paint with no round-trip and no empty-panel flash.

Everything *else* is plain composition in jsonnet — **no tabs logic in the
backend**. `c.tabs([{ label, content }, …])` builds, per tab, a normal
`c.button` whose click both opens the panel and sets a per-group signal to the
active surface id; the same signal drives the button's **`active` highlight**
(`data-class`, client-side, zero round-trip) and, seeded to the first id, the
default panel. Each panel rides the generic `inlineSurfaces` marker (decision 6),
referencing its id as `@@NODE@@_<i>`. A tab is therefore **just a `button` with an
`active` expression** — `tabButton` was folded into `button` (an optional `active`
param adds the `tab` class + the `data-class` highlight; absent ⇒ a plain button),
the same consolidation as `actionButton`→`button`. No `Surface`/model change —
`group` + `mount` already carry it.

## Consequences

- New `runtime/Sessions.scala`; `Server` takes a per-slug renderer map + default
  slug + `Sessions`; `Datastar` gains patch `mode`/`selector` + `removeElement`;
  `Renderer` gains `renderBody`, the `#dashboard`/`#popups` shell, and surface
  indices/render. `Transforms.from` now also compiles surface slot transforms.
- Open/close/navigate are pure backend state transitions whose patches ride the
  one SSE stream; closed popups are free.
- **Tabs** landed on this foundation (decision 7): mount-aware `renderSurface` /
  `openSurface`, baked default panel + `defaultOpenSurfaces` seeding, a client-side
  active signal, and `c.tabs(...)` composed entirely in jsonnet over the generic
  `inlineSurfaces` hoist (decision 6). `tabButton` was folded into `button`.
- **Not yet covered:** a nav-menu UI between dashboards; live actuation against
  real devices and the in-browser Datastar specifics (multi-stream, `__window`
  popstate, tab active-class, append/inner/remove) — verified by tests, not yet
  in-browser.
- Datastar specifics relied upon (patch modes `append`/`inner`/`remove`; signals
  round-tripping `conn`; `data-on:…__window`; client `history` access) are pinned
  to v1.0.2 — re-verify on upgrade.

## Update — 2026-06-25: the backend is fully card-name-agnostic

Decision 7 computed the default-open tab panels (`defaultOpenSurfaces`) by
scanning the layout for `c.card == "tabs"` — the **only** place the backend
hardcoded a card name. It now collects the `initial` (default-surface) param
from **any** component that declares one, keying on declared **structural data**
rather than a card name — consistent with decision 6's "the hoist knows nothing
about popups, tabs, buttons." Behaviour is identical (only the `tabs` container
declares `initial`, and the panel-baking at render time already keyed on
`initial`, not the card name). The runtime is now driven entirely by structural
fields/params (`mount`/`group`/`content`/`initial`), never a card name — so a new
container kind that wants a baked default panel needs no backend change.

## Update — 2026-06-25b: surface chrome in the card library; tabs de-specialized; one id story; `initial` → `defaultOpen`

Three changes finish moving surface/tab handling out of the backend.

**Surface chrome lives in the card library, not Scala.** `renderSurface` no
longer emits hardcoded `<dialog>`/panel HTML. Two backend-only cards —
`popup` (the overlay `<dialog>` + a wrapper-supplied close control) and
`tabPanel` (the chromeless inline wrapper) — carry the chrome; the renderer
picks one by the surface's `mount` (inline ⇒ `tabPanel`, overlay ⇒ `popup`),
renders the surface content as the card's single `children`, and injects only the
backend-known vars (`id`, `closeAction`). A re-skin of a popup is now a template
edit. **Panel-baking uses the same path**: a tabs container bakes its default
panel by calling `renderSurface` (chrome + surface-id prefix included), so the
first paint and a later switch-back produce byte-identical HTML.

**`initial` is no longer a backend concept; `Surface.defaultOpen` is.** The
2026-06-25 update keyed default-open panels on the `initial` slot. That slot is
now purely a **client** signal seed (it initialises the active-tab highlight) the
backend never reads. "Shown from first paint" moved onto the surface itself:
`defaultOpen: Boolean`. `defaultOpenSurfaces` reads it straight off the registry
(no slot, no card name, no layout scan), and panel-baking matches the default-open
surface to its container by **`mount`** (the surface's `mount` equals the
container's `mount` slot). A tabs builder marks only its first inline surface
`defaultOpen: true`; the hoist's `surfaceOf` preserves the flag. The backend now
reads *no* slot to decide what to show — only surface-level structural fields
(`mount`/`group`/`content`/`defaultOpen`).

**One id story, shared by build and runtime.** The hoist no longer mints ids in a
separate `"inline…"` namespace. The id scheme — `pathId`, `surfaceRootId`,
`surfacePrefix`, `sanitize` — now lives in `LayoutNode` (the model); the renderer
delegates to it, and the hoist names a node's surfaces from the **same** scheme,
so a node's build-time id namespace equals its render-time `{{id}}`. The
build-time placeholder a builder splices was renamed **`@@NODE@@` → `@@NODE_ID@@`**
to say what it is: *this node's backend-assigned id*. The spike result stands —
jsonnet still can't mint per-instance ids, so the backend owns them (`pathId`) and
the hoist remains the build-time half of that one id system; only the *naming* was
unified, not the ownership.

## Update — 2026-06-25c: the unified primitive is the *mount point*; tabs are no longer special

> Status of implementation: **approved, in progress.** Plan:
> [`docs/plan-mount-unification.md`](../plan-mount-unification.md).

Supersedes **decision 7** (tabs as a thin layer with two *runtime* additions) and
the 2026-06-25 / 2026-06-25b tab-and-panel-baking updates. The surface model
(decision 2), per-connection sessions (3), the unified click slot (4), and the
generic hoist (6) all **stand** — this update only collapses the remaining
tabs-specific machinery.

**The realisation.** A popup and a tab panel are the *same* `Surface` (lazy,
streamed only while open, evicted on switch). The model already encoded the *only*
difference as `Surface.mount`; chrome (`<dialog>` vs bare) and insertion
(`append`/stack vs `inner`/replace) merely *derive* from it. So the mount target is
the one real difference — and it should be a **first-class layout node**, the
inline analogue of `#popups`, rather than a `<div>` baked into a special `tabs`
card.

**`Mount` is a layout node.** A new `LayoutNode.Mount(id, kind, signals?)` with
`MountKind { Overlay, Inline }` renders an addressable container that surfaces
target. The page-level `#popups` becomes simply the built-in **overlay** Mount, so
popup and tab are *literally one primitive* differing only by mount kind:

- **Overlay** (`#popups`) — `append`, `popup` chrome, stack.
- **Inline** (a tab panel placed deep in the layout) — `inner`/replace, `tabPanel`
  chrome; exclusivity is implied by the **shared mount** (replacing its content
  evicts the previous).

`Surface.group` is **removed** (`(content, mount, defaultOpen)`): exclusivity is now a
pure function of mount kind — inline replaces (a tab group is exclusive per-mount; two
tab groups are independent), overlays stack. The only lost capability is "exclusive
overlays" (one popup auto-closing another), which was unused and is recoverable via a
second overlay mount. `Mount` itself carries **no id** — it renders its positional
`pathId`, which surfaces address through the *unchanged* hoist token
(`@@NODE_ID@@ + '_<childIndex>'`, since `idBase == pathId`); no explicit id, no labeled
tokens, no hoist change.

`Renderer.renderSurface` and `Server.openSurface` now branch on `mountKind(mount)`
(a `Mount`-derived index, default `popups → Overlay`) instead of `mount.isEmpty`.

**First-paint baking moves to the Mount node.** The default-open surface is baked as
the Mount's initial inner content (the same `renderSurface` output a later switch
produces), replacing the tabs-Component `{{panel}}`/mount-match baking. `defaultOpen`
stays a generic surface flag — now also "a popup open on load." `defaultOpenSurfaces`
seeding (decision 7 / session) is unchanged.

**The `tabs` card is deleted.** `c.tabs([{label, content}, …])` is now *pure
composition* over existing primitives: `column([ row-of-buttons, mount node ])`,
its panels lifted by the **unchanged** generic hoist (decision 6). The bar buttons
open their panel surfaces (no `group`; mount-shared exclusivity) and set a
per-group active signal; that signal — the one genuinely tab-flavoured bit, with no
popup analogue — lives as a `data-signals` seed on the **mount node** and drives each
button's `active` highlight (client-side, as before). No `tabs`/`sig`/`initial`/`panel`/
`mount` slots, and **no tabs logic in the backend** beyond rendering a generic Mount.
(The seed is optional: `data-init` is confirmed to run on DOM patch — *not* a
non-existent `data-on:load` — so a baked panel can self-seed the active signal; pinned to
v1.0.2, re-verify on bump.)

**Rejected alternative — "children is a slot."** An earlier route made `children` a
slot with multiple named structural holes so panel *content* moved into `children`.
It was rejected: it implies rendering all panels and `data-show`-hiding the inactive
ones (wasted SSE patches to hidden panels — the opposite of surface laziness), and
the mount-node route already fits the **existing single `children` hole**
(`column([row, mount])`) with **no change to the slot model** (ADR 0001/0004 stand).

**Net.** The runtime reads only structural surface/mount fields
(`content`/`mount`/`defaultOpen`/`MountKind`) — never a card name and never a slot — to
decide what to show and how. Tabs cease to be a backend concept; they are a jsonnet
builder composing a styled button bar (reusing `button`'s `active`→`tab` styling and an
optional container `class` slot for `.tabbar`/`.tabs`) and an inline mount.
