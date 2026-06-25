# Plan: unify popups and tabs on a first-class *mount point* (fh-datastar-view)

> Status: approved direction (option **b**, full unification); **pre-implementation**.
> Durable record lives in [ADR 0002 Update — 2026-06-25c](adr/0002-multi-dashboard-popups-and-navigation.md).
> Delete this file once implemented + folded into the ADR.

## The insight

A **popup** and a **tab panel** are the *same* thing — a lazily-activated `Surface`,
rendered and streamed **only while open**, evicted on switch. The model already
encodes the *sole* difference as `Surface.mount`:

| | mount target | insertion | chrome | exclusivity |
|---|---|---|---|---|
| popup | page-level `#popups` | append (stack) | `<dialog>` + ✕ | optional `group` |
| tab   | a node **deep in the layout** | inner (replace) | bare | one per mount |

Insertion mode and chrome already *derive from* the mount (see `Server.openSurface`'s
`if (surf.mount.isDefined)` and `Renderer.renderSurface`'s `mount` branch). So the
mount node is the only real difference; everything else is a consequence of it.

Today the tab's mount is **baked into a special `tabs` card** (`<div id="{{mount}}">{{{panel}}}</div>`
with tabs-only `mount`/`panel`/`sig`/`initial` slots, plus mount-matched panel-baking
inside `Renderer.render`). That special-casing is the smell. The fix: make the mount
target a **first-class layout node** — the inline analogue of `#popups` — so a tab group
is just `column([ row-of-buttons, mountNode ])`, panels stay lazy surfaces, and the
`tabs` card disappears.

## Rejected alternative: "children is a slot" / named children groups

The earlier idea (multiple named children holes so panel content moves into `children`)
is **superseded** by mount-as-node and **not** pursued:

- It would have to render *all* panels and `data-show`-hide the inactive ones — exactly
  the wasted client patches we want to avoid (hidden panels still receive SSE updates).
  Surfaces stay lazy: only the open panel renders/streams.
- With the mount node, the tabs layout already fits the **existing single `children`
  hole** (`column([row, mount])`). No slot-ADT, no `Map[String, Children]`, no change to
  the slot model (ADR 0001/0004 untouched).

## Model (`model/Dashboard.scala`)

```scala
enum MountKind:           // how a mount hosts its surfaces
  case Overlay            // stack as dialogs (append, `popup` chrome) — the page #popups
  case Inline             // replace in place (inner, `tabPanel` chrome) — a tab panel

case class LayoutNode.Mount(
    kind: MountKind,
    signals: Option[String] = None  // optional `data-signals` seed (the tab-active signal)
) extends LayoutNode
```

- **`Mount` has no `id`** — it renders its own positional `pathId` like every other node
  (one id story). Surfaces address it via the *existing* hoist token: the tabs builder
  owns the column it emits, so it knows the mount's child index `k` and writes the
  surfaces' `mount` as `@@NODE_ID@@ + '_k'`, which the hoist splices to the mount's
  `pathId` (`idBase == pathId`, since the hoist threads `${idBase}_$i` per child). **The
  hoist is unchanged** — no explicit id, no labeled tokens.
- **`Surface` drops `group`** → `(content, mount, defaultOpen)`. Exclusivity is now
  entirely a function of **mount kind**: inline mounts *replace* (so a tab group is
  exclusive per-mount, and two tab groups are independent automatically); overlays
  *append* (stack). The only lost capability is "exclusive overlays", which we don't use
  and is recoverable via a second overlay mount.
- `defaultOpen` stays a generic surface flag ("open from first paint") — now meaningful
  for overlays too (a popup open on load), not just tabs.
- To avoid the mount's positional `pathId` (`<col>_k`) string-sharing a tab surface
  registry key, key the tab surfaces `t0/t1/…` (not `0/1/…`).
- `validate`: `Mount` has no card ref / slots — walk it for nested surfaces only. Drop
  the tabs-only `injected` vars (`panel`; `entity_id` stays for dynamic). `id`/`panel`
  in `injectedStatic` revisited (panel-baking leaves the Component path).

## Renderer (`runtime/Renderer.scala`)

- **Mount index** `id -> MountKind`, collected from `Mount` nodes across main + surfaces,
  plus the built-in `"popups" -> Overlay`. Expose `mountKind(id): MountKind` for `Server`.
- **`render` handles `Mount`**: emit `<div id="<idPrefix+pathId>"{{ data-signals }}>` whose
  initial inner content is the **baked default-open surface(s) targeting this mount** —
  looked up by `surfaces.collectFirst { defaultOpen && mount == <this pathId> }` (`inner`
  for Inline → the one default panel; `append` for Overlay → any default-open popups). This
  is the current first-paint baking, **relocated from the `tabs` Component** to the mount
  node. The mount's element id is its `pathId`, which the surfaces already reference.
- **Delete** the `c.slots.get("mount") … collectFirst { defaultOpen && mount }` baking
  block in `render` and the `panel` injected var.
- **`renderSurface`** picks chrome by `mountKind(surf.mount.getOrElse("popups"))` (Overlay
  → `popup`, Inline → `tabPanel`) instead of `mount.isEmpty`.
- `defaultOpenSurfaces` unchanged (still reads `Surface.defaultOpen`).

## Server (`runtime/Server.scala`)

- **`openSurface`** branches on `renderer.mountKind(mount)`:
  - **Inline**: evict open surfaces sharing this **mount** from the open-set (lookup via
    `surface(sid).mount == mount`; no `removeElement` — the `inner` patch overwrites), then
    `inner`-patch into `#<mount>`.
  - **Overlay**: no eviction (stack), `append` into `#<mount>` (`#popups`).
  - The old `group`-eviction block is **deleted** (group is gone; exclusivity = mount kind).
- **`renderPage`**: emit `#popups` by rendering an overlay `Mount("popups", Overlay)`
  (so default-open overlays bake in too) — replaces the hardcoded `<div id="popups">`.
  `#popups` stays a sibling of `#dashboard` so overlays survive a body swap.

## Components (`resources/dashboards/components.libsonnet`)

- **Delete** the `tabs` card (template + `sig`/`initial`/`mount` slots). Keep the
  `popup`/`tabPanel` **chrome** cards (`renderSurface` still wraps content in them).
- **Add a `mount` builder** emitting the `Mount` node (kind + optional signals — no id).
- **Container styling reuse.** Give `fhrow`/`fhcol` an optional **`class` literal slot**
  (`<div class="fh-row {{class}}">…`) so the tab bar and wrapper reuse existing containers
  *and* keep the `.tabbar`/`.tabs` CSS — no tab-specific cards. The tab **buttons** reuse
  the existing `button` builder unchanged: passing `active=` already emits `class="card tab"`
  + the `data-class` highlight, so per-tab styling is free.
- **Rewrite `c.tabs(tabs)`** as pure composition, no card. The mount is child `k` of the
  column, so surfaces address it as `@@NODE_ID@@ + '_k'` (= its pathId):
  ```jsonnet
  c.tabs(tabs):: c.column([
    c.row([ c.button(action=openPanel(i), label=tabs[i].label,
                     active='$tab_' + NODE_ID + " == " + i) for i in ... ], class='tabbar'),
    c.mount(kind='inline', signals='{ tab_' + NODE_ID + ': 0 }'),   // child index k=1
  ], class='tabs') + { inlineSurfaces: { ['t' + i]: {
        content: tabs[i].content,
        mount: NODE_ID + '_1',                    // the mount's pathId (col child 1)
        [if i==0 then 'defaultOpen']: true } for i in ... } }
  ```
  where `openPanel(i)` = `@post('/sse/surface/open/<sid ti>'); $tab_<id> = i`. The
  `inlineSurfaces` marker + `@@NODE_ID@@` anchor on the wrapping column (the hoist splices
  the column's `idBase` across the subtree — buttons, surface keys `t0/t1`, and the
  surfaces' `mount` = the Mount child's pathId all agree). No `group`; no `panel`/`initial`.
- The active-tab highlight signal `tab_<idBase>` lives on the **mount node** (`data-signals`),
  seeded to `0`, set by each button, read by each button's `active`. Composed in jsonnet,
  not a backend concept. (Future: the seed is **optional** — `data-init` is confirmed to
  run when an element is patched into the DOM, so a baked/opened panel root could carry
  `data-init="$tab_<id> = <i>"` and drive the highlight from the actually-open panel.
  There is **no** `data-on:load`; `data-init` is the primitive. Re-verify on a Datastar
  bump past v1.0.2.)

## Hoist (`build/DashboardBuild.scala`)

Essentially **unchanged** — `hoistInlineSurfaces` already splices `@@NODE_ID@@` across a
marker node's subtree and lifts surfaces. `surfaceOf` keeps `content`/`mount`/`group`/
`defaultOpen`. The Mount node's token `id` rides through the same splice. (If anything,
`group` drops out of tab surfaces.)

## Tests

- `RendererSuite`: a `Mount` node renders its `<div id>` + baked default surface; chrome
  picked by kind; the open path evicts by mount for inline / by group for overlay.
- `BuildPhaseSuite`: `c.tabs` evaluates → hoists → validates with a `Mount` child and
  surfaces whose `mount` equals the spliced mount id; no `tabs` card; first surface
  `defaultOpen`.
- `sbt fh-datastar-view/testFull`, then `sbt dashboardBuild`, then live `dashboardServe`
  (`/d/tabs` switches in place + lives from first paint; popups still open/close/stack).

## ADR

Append `## Update — 2026-06-25c` to **ADR 0002** superseding decision 7 + the 2026-06-25 /
2026-06-25b tab/panel-baking updates: tabs are no longer a card or a runtime special case;
the unified primitive is the **mount point** (`#popups` = the page-level overlay instance),
popup/tab differ only by mount kind, and the "children is a slot" route was considered and
rejected in favour of it. ADR 0001/0004 (slot model) unaffected.

## Phasing (each independently green: testFull + dashboardBuild)

1. Model: `Mount` + `MountKind`; narrow `group`; decoders/validate.
2. Renderer + Server: mount index, Mount render + baking relocation, chrome/insertion by
   kind, `#popups` as overlay Mount.
3. Components: delete `tabs` card, add `mount` builder, rewrite `c.tabs`.
4. Tests + ADR + verify.
