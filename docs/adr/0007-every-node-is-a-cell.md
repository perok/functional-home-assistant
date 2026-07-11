# ADR 0007 — Every node is a cell: backend-owned layout wrappers + the `fh-` layout contract

- **Status:** Accepted
- **Date:** 2026-07-11
- **Scope:** `modules/fh-datastar-view` (the Datastar dashboard)

## Context

Dashboard cells and components rendered at unaligned, inconsistent sizes. Two
structural causes:

1. **Wrapping was conditional.** The renderer wrapped a component in the id'd
   `.fh-cell` element only when it had live entities (the Datastar outer-morph
   target needs an id on the fragment root). Containers and static leaves
   emitted bare template HTML — so sibling row items were a mix of node kinds.
2. **The wrapper was `display:contents`** (layout-neutral), so the inner card
   became the flex item — and only direct `.fh-row > .fh-col` children carried
   the equal-share rule (`flex:1 1 0`). Bare cards sized to content. Which
   sibling got which behaviour depended on node type, not layout intent.

There was also no grid: `Row`/`Column` flexbox was the whole layout
vocabulary, with nothing like Home Assistant's "sections" model (a 12-column
grid per section, cards sized in `grid_options` columns) that HA users already
know.

## Decision

**Every node is a cell, and the backend owns the cell.**

1. **Universal wrapper.** `Renderer.render` wraps EVERY component — containers
   included, static and dynamic, main page and surfaces — in
   `<div class="fh-cell" id="…">` (`Renderer.scala`). One invariant replaces
   the old conditional: every node is an addressable morph target, and every
   direct child of a container is a real `.fh-cell` box. The wrapper stays
   backend-owned (a single string concatenation around the rendered template;
   ids are pure tree-path strings computed before templating — no HTML
   parsing anywhere).
2. **`CardDef.wrapAsCell` is the escape hatch** (default `true`). A card sets
   it `false` only when its template root must remain a *direct* child of a
   framework-structural parent. The one opt-out is the `tab` card: BeerCSS
   styles tab bars via the structural selector `.tabs > a`. A `wrapAsCell =
   false` card is never a morph target of its own and must not be used as a
   dynamic-group case (per-entity children are always wrapped — they ARE the
   patch targets).
3. **`.fh-cell` is the real layout box.** `display:contents` is gone; the
   themes lay the wrapper itself out (`.fh-row>.fh-cell{flex:1 1 0;…}`, the
   grid rules below). This kills the col-vs-bare-card asymmetry: all row
   children share space uniformly, all grid children are grid cells.
4. **Node-level `cell` wire field.** `Cell(classes: List[String])` is optional
   on `LayoutNode.Component`, `LayoutNode.Dynamic` (the group root), and
   `DynamicCase` (each per-entity member wrapper); the renderer appends the
   classes to the wrapper's class attribute. `Dashboard.validate` rejects any
   class that is not a plain token (`[A-Za-z0-9_-]+`) — the values are
   string-interpolated into markup. An object (not a bare list) so it can grow
   HA-`grid_options`-style fields (rows, dense) without a wire break. Case
   `cell`s are static wire data, so in-place morphs, membership inserts, and
   whole-group repaints re-emit byte-identical wrappers.
5. **The `fh-` layout contract, HA-sections-shaped.** The class vocabulary is
   theme-agnostic: `.fh-grid` (a `Grid` container) and `.fh-group` (a dynamic
   group's member flow) are 12-column grids whose cells default to **half the
   grid** — HA's default card size (`grid_options.columns: 6`); `.fh-cols-<n>`
   / `.fh-cols-full` override the span; `.fh-center` centers a cell's content;
   `--fh-gap` is the spacing knob. `theme.pkl` carries this CSS as a reusable
   `const layoutCss` that a theme interpolates at the top of its `styles`
   (theme-beer does) — a future theme reuses it or replaces the layout system
   wholesale, and the visual classes (`.card`, `.section`, …) remain the part
   each theme styles its own way. The default-span selectors use `:where()` so
   any authored single-class span rule wins on specificity. There is
   deliberately NO mobile-collapse media query: like an HA section, the
   12-column grid holds on phones (a 6-column card is half the screen — the
   HA tile look); use `fullWidth()` for cards that need the row.
6. **Authoring follows HA naming.** Layout builders live on the Pkl
   `LayoutNode` base, so components and dynamic groups share them:
   `columns(n)` (HA `grid_options.columns`), `fullWidth()` (HA `columns:
   full`), `centered()`, and the `cellClass("…")` escape hatch. They append to
   the node's `cell.classes` (never amending the null default) and return the
   base `LayoutNode` type — chain them AFTER card-specific builders
   (`c.entityCard(e).tap(…).columns(3)`). `caseOf` copies a render fn's `cell`
   onto the emitted `Case`. The `Grid` class (`card = "fhgrid"`) is the
   default top-level container; `Row`/`Column` remain for one-dimensional
   flows (e.g. a column of buttons occupying one grid cell).

### Rejected: template-owned id roots

The considered alternative — every card template carries `id="{{id}}"` on its
root (the var is already injected) and the renderer stops wrapping — was
rejected. It makes every template load-bearing for SSE correctness (a
forgotten id silently kills live patching, guarded only by a fragile regex
over multi-line template roots), it still needs per-template `{{cellClasses}}`
boilerplate to carry layout params, the renderer keeps its special cases
anyway (dynamic child ids, the group root), and the "all grid children are
cells" invariant would depend on every card's root class list instead of one
backend-emitted class. Same CSS work, weaker invariants, one less `<div>`.

### Also decided here

The Pico theme was deleted rather than kept in lockstep: BeerCSS
(`theme-beer.pkl`) is the default and only shipped theme. Nothing hardcodes
BeerCSS: `theme.pkl` is the contract, the `fh-` layout CSS is
framework-agnostic, and a new look is a sibling module exporting a
`theme: Theme` (see the theme-author notes in `theme.pkl`'s module doc).

## Consequences

- The wire format grew `CardDef.wrapAsCell`, `cell` on the three node shapes
  — old JSON decodes unchanged (defaults). The wire snapshots pin all of it.
- The backend model changed for a layout feature. That is the sanctioned
  exception to "authoring-layer work shouldn't touch `Dashboard.scala`": cell
  params are structural (the renderer owns the wrapper), not presentational.
- Every node now being id-addressable improves the editor overlay
  (`.fh-cell[id]` selects everything) and future per-node tooling.
- DOM depth grows by one `div` per node; inert in flow terms everywhere but
  the layout containers, where it is the point.

## Verification

`fh-datastar-view/testFull` green (110 tests): renderer wrapper semantics
(universal wrap, `wrapAsCell = false` bare render, cell classes on all three
wrapper kinds incl. the per-member in-place path), builder-vs-property JSON
identity, `caseOf` cell copy, validate's token rejection, and the wire
snapshots (regenerated deliberately for `wrapAsCell`, the `fhgrid` registry
entry, the layout CSS, and the demo's grid tree). **Visual verification in the
browser (`sbt dashboardServe` — tab bar styling, popup chrome over the new
wrapper, grid flow light/dark/narrow) is still pending**; per ADR 0006 that
check cannot be done from the terminal.
