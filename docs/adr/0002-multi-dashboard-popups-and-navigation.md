# ADR 0002 — Multiple dashboards, popups, and navigation

- **Status:** Accepted
- **Date:** 2026-06-23 (consolidated 2026-07-04; navigation became a real page
  load 2026-07-27)
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

The dashboard originally served exactly one entry at `GET /`, over a
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

### Popups: a variable on the page

A popup is a library layer on ordinary nodes slots (`components/popup.pkl`);
nothing in `core/`, on the wire or in Scala says "popup":

- **The page declares `popups`**, a node variable holding the id of the open
  popup, with no value while none is open (ADR 0033), in history (ADR 0005). Its
  URL parameter is `v.popups`. `validate` refuses the name on any other node,
  where it would hide the page's from every opener below.
- **A popup is a node with an id**: `c.view.popup("help", body)` opens the node
  `help`, a `popup` card around `body`; the library derives its own
  (`moreinfo_<domain>_<object id>`, the lock's `confirm_open_…`, the colour
  `wheel_…`). The tap is a `SetVar` choosing that id, and carries the node in a
  hidden property, so the wire has only the call.
- **`entry.pkl` collects every popup a tap can open** from the dashboard's card,
  looking inside each popup for the ones it opens, and fills the page card's
  `popups` with one match over the variable, a case per id. Equal content under
  one id is one case: two cards for one entity share its more-info. Different
  content under one id is a Pkl error. A popup holding its own opener is a
  reference, since Pkl is lazy, so the walk stops at an id it has.
- **The dialog is a card.** The `<dialog>`, its ✕, Escape and the backdrop are
  the `popup` card's template (`components/base/popup.pkl`); the theme styles
  `.fh-popup` as a class contract.
- **A close is going back**: every choice is a history entry, so the ✕ is
  `history.back()`, and Back's popstate post clears or restores the variable
  (ADR 0005).

So a popup is decided as any pick over a variable is (ADR 0033, the pipeline
doc): a case no viewer shows costs nothing, opening one is a variable write
planned against the session's `holds` (ADR 0012), a refresh renders it in the
first HTML, and its content starts with the page's scope. One slot shows one
case, so **one popup is on screen at a time**; a popup may open another, and
Back returns to the first. A popup outlives its opener: a set member leaving or
a tab switching away is a patch of its own host.

A tab panel and an if/else branch are cases of a nodes slot's choice the same
way (ADR 0007, ADR 0033): the first paint renders the picked case into its
slot's hole, so the selected panel is in the initial HTML with no round-trip and
no flash, and a later live switch is byte-identical.

### Per-connection sessions over the one SSE stream

Each SSE connection mints a `conn` id and pushes it as the first
`datastar-patch-signals` event; Datastar then sends `conn` among the signals on
every action `@post`, correlating the POST to its stream. A `Session` (keyed by
`conn` in `Sessions`) holds its `slug` — **fixed**, since another dashboard is
another document and therefore another connection — the viewer's variables, and a **control** queue the action handlers push patches into.

**Live entity patches are rendered once per slug, for everyone.** One background
subscription to the state stream per dashboard (`Server.sharedPatchPublishers`,
run by `Server.withSite`) selects what a change touches, renders it, diffs
against a per-slug `FragmentLog`, and publishes the changed fragments on a
per-slug topic. The topic is **one multiplexed** stream of slug-tagged events, so
a connection subscribes once and drops every tag but its own; that (rather than a
topic per slug) is what lets `push` mint a slug after a connection has opened.
N viewers of one slug cost one render, not N — including viewers of an open
popup or a selected tab, which is what distinguishes this from the earlier
design.

**Who may see a patch is decided by the session that sends it.** Nothing is broadcast:
the recorder writes a changelog and each connection pulls what it is owed, against its
own screen and its own record of what its DOM holds (ADR 0012). So a patch carries no
audience tag and passes no filter at the wire edge — it exists because the session that
will send it asked for it. A choice over state needs no special handling either:
their selection is server truth, identical for every viewer (ADR 0007), so a node inside
an `If` branch nested in a tab panel is visible exactly when that tab panel is.

That also dissolves the one case that resists a shared rendering on its face — a flip
placing a branch whose subtree holds a client-selected member (tabs inside an `If`). The session
renders it with its own selections when it pulls, so it arrives as one complete patch
with that viewer's panel already inside it, and there is no deferred render and no memo
keyed by what the render reads.

Almost nothing else can vary anyway: only a LEAF is a patch target and a leaf holds no
regions (ADR 0008), so only a render that CREATES a subtree can differ per viewer.

This replaced a **shared/per-session split**, in which open popups and the hosts
of choices were re-rendered once per connection against a per-session diff cache, and then a
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

A component's click target is a declared call, which `tapMod.post` spells in the
card's template, or a single `onclick` slot holding the **entire** Datastar
expression (spliced as literal text into `data-on:click="{{{onclick}}}"`):

- service call, or a choice of a node variable (a tab, a popup) →
  `@post('sse/call/<slug>/<callId>?node=<id>', {payload: …})` (ADR 0023)
- a popup's close → `history.back()`

Every route names its dashboard, so the call can be bounded by what that
dashboard references (ADR 0023).

This is why reuse "just works": `c.button("Help", c.view.popup("help", body))`
needs no new template.

**Going somewhere is the exception, and it is not an expression at all.** A `TapAction`
that navigates carries an `href` (`d/<slug>`, relative so `<base href>` resolves
it under ingress), and a card whose root can be an `<a>` must prefer it — the
`button` and `pill` templates branch on `{{#href}}` and emit an `<a href="…">`
instead of a scripted `<button>`. A link the
browser understands is worth the branch: middle-click, open-in-new-tab, the
status-bar preview, and a click that works before Datastar has loaded. (BeerCSS
styles buttons as `:is(button,.button)`, so the anchor form is visually
identical.) The `TapAction` also carries the equivalent `onclick`
(`window.location.assign(new URL('d/<slug>', document.baseURI))`) for cards whose
root element cannot be an anchor — `tile`'s `<article>` — so one authored
`c.view.navigate('x')` renders correctly wherever it is dropped.

A link to the dashboard already open carries `fh-current-dashboard` and
`aria-current="page"`, and stays a link. A module does not know its own slug,
so the build cannot decide which link that is: the tap names its target
(`page`), and the card reads `dashboard_slug == '<page>'` in a slot read once
per renderer, which is one dashboard. That costs no signal, and the class is a
literal token, so a theme's class rules reach it.

### Navigation is a real page load

Going to another dashboard is an ordinary document load of `/d/:slug` — an
`<a href>` where the card can be one, `location.assign` where it cannot (above).
The browser owns the history entry; navigation pushes nothing of its own, and
there is no `/sse/navigate` route. (The only `pushState` and `popstate` are a
history variable's, the page's `popups` among them, ADR 0005.)

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

Remaining gap: only `button` and `pill` render the anchor form. A navigating `tile`
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
inlined as `Server.ShellScript`) saves `scrollY` to `sessionStorage` on
`pagehide` and re-applies it as the **last thing in `<body>`**, with
`history.scrollRestoration='manual'` so the browser's own (zero) restore cannot
land on top of it. Last in the body is what makes it
invisible: the body is server-rendered and the stylesheets are render-blocking,
so the document has its height before the closing script runs and the offset is
set before the first paint.

`sessionStorage` and not the URL mirror, deliberately — see ADR 0005's tiering.
Per tab and per slug, so two tabs on one dashboard do not drag each other, and a
link somebody shares does not land them mid-page.

### Tabs are pure composition

A tab group is one `tabs` card whose `panel` slot holds a match over its
variable, a case per tab: the card's template owns the button bar and places
the panel, a `group` whose root hosts the match (`groupMod.inner("div",
"fh-tab-panel", …)`); the bar declares the
variable picking the panel, and its buttons write it (ADR 0033). **No tabs logic in the
backend** — the runtime reads only structural fill fields and variables,
never a card name. The active tab persists via the session and the URL mirror
(ADR 0005).

### The page is a card, and the theme owns its frame

Every dashboard's tree hangs from the **page card** (`core/page.pkl`), which
`entry.pkl` builds around the author's `card`: the node `root`, with two nodes
slots. `body` holds the author's card, which keeps the id `c` unless it names
its own, so no id below it moved. `popups` holds the node `popups`, the group
whose match shows the open popup (above). `Theme.chrome` is the card's template: a frame
placing `{{{body}}}` and `{{{popups}}}`, inlined in the theme (a theme imports
no component library — it is presentation, a leaf). A frame missing either hole
fails the build, and `root` and `popups` are refused as an author's ids.

Because the host is a node, a **restored** popup is flash-free: a refresh
carrying `?v.popups=<id>` (ADR 0005) renders the dialog into `popups` in the
served HTML, the same way a selected tab panel is rendered into its owner, and a
repaint does the same (ADR 0011). The theme decides WHERE the host sits in the
frame.

The backend holds **zero** frame HTML: a tree with no page card, which only a
test builds in Scala, renders its root bare. The document shell
(`<head>`, Datastar `<script>`, `data-init`, the theme's
stylesheet `<link>`s and script `<script type="module">`s — `Theme.scripts`
carries JS a theme's CSS needs, e.g. BeerCSS's slider fill; behavior stays
Datastar's) stays in `Server.pageInto` — Datastar bootstrap and per-request
wiring, not dashboard frame.

## Rejected along the way (still guarding the design)

- **A dedicated host layout node** (backend-rendered host element + a kind
  heuristic): pushed presentation back into Scala (hardcoded host HTML, a
  kind→card-name binding). The host belongs in a card template; presentation is
  data.
- **`children` as a multi-hole slot** (render all tab panels, `data-show` the
  active one): defeats a case's laziness — hidden panels would receive SSE
  patches.
- **Stacked popups**: two dialogs on screen at once was unused; one slot showing
  one case is what lets a popup be an ordinary pick. History stacks them
  instead.
- **A popup registry and its own runtime path** (`surfaces`, an open/close
  route, a swap that evicted every popup's nodes, a build-time hoist minting
  inline popups' ids): a second mechanism beside the pick, with its own
  patching, its own scope rule and its own URL parameter. A variable and a
  match do the same job with the one path.
- **A theme-composed `c.popupHost()` component**: inverted the layering (theme
  → components); the host is inlined in the theme instead.
- **A hollow host plus a per-connection fill**: serving viewers on different tabs
  from one shared render by inserting the branch with its tabs host EMPTY, then
  having each connection fill its own. It works, and it is wrong twice over: two
  DOM updates for one change, and a rendering "for nobody" that leaks a blank tab
  index into live markup, because a host element carries client-dependent
  ATTRIBUTES and not merely children. Having the session render the branch with
  its own selections, above, is the same idea at the right boundary.
- **Baking whichever member the connected clients happen to agree on**: would
  remove the hollow host for the common case by reading the union of
  every session's open set. It makes the rendered bytes depend on the audience —
  the same dashboard in the same state producing different HTML depending on who
  is watching — for no gain over letting each session render its own.

## Consequences

- Open/close are variable writes whose patches ride the one SSE stream; closed
  popups are free. A dashboard change is outside that model
  entirely — it is a new document, a new stream, a new session.
- There is ONE render pipeline and one log per slug. What differs per client is
  a filter (which patches reach it) and, in one case, a render performed at the
  edge — never a second pass with its own cache.
- Datastar specifics relied upon (patch modes, signal round-tripping of `conn`)
  are pinned to **v1.0.4** — re-verify on upgrade.
- Not covered: a nav-menu UI between dashboards.
