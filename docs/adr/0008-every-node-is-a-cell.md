# ADR 0008 — Every node is a cell: backend-owned layout wrappers + the `fh-` layout contract

- **Status:** Accepted
- **Date:** 2026-07-12
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
   the old conditional: every node is addressable, and every
   direct child of a container is a real `.fh-cell` box. The wrapper stays
   backend-owned (a single string concatenation around the rendered template;
   ids are pure tree-path strings computed before templating — no HTML
   parsing anywhere).
2. **`CardDef.wrapAsCell` is the escape hatch** (default `true`). A card sets
   it `false` only when its template root must remain a *direct* child of a
   framework-structural parent. The one opt-out is the `tab` card: BeerCSS
   styles tab bars via the structural selector `.tabs > a`. Everything that
   rides on the wrapper is unusable on such a card, and would fail *silently*
   at render time — so `Dashboard.validate` rejects the three shapes loudly:
   live-entity slots (an unwrapped node has nothing to patch, so its pushed
   patches could never match), authored `cell` params (no wrapper to carry
   the classes), and use as a dynamic-group case (per-entity children are
   always wrapped — they ARE the patch targets).
3. **`.fh-cell` is the real layout box.** `display:contents` is gone; the
   themes lay the wrapper itself out (`.fh-row>.fh-cell{flex:1 1 0;…}`, the
   grid rules below). This kills the col-vs-bare-card asymmetry: all row
   children share space uniformly, all grid children are grid cells.
4. **Node-level `cell` wire field.** `Cell(classes: List[String])` is optional
   on `LayoutNode.Component`, `LayoutNode.SetNode` (the container root), and
   each clause node (the per-member wrapper); the renderer appends the
   classes to the wrapper's class attribute. `Dashboard.validate` rejects any
   class that is not a plain token (`[A-Za-z0-9_-]+`) — the values are
   string-interpolated into markup. An object (not a bare list) so it can grow
   HA-`grid_options`-style fields (rows, dense) without a wire break. Case
   `cell`s are static wire data, so in-place morphs, membership inserts, and
   whole-group repaints re-emit byte-identical wrappers.
5. **The `fh-` layout contract: three container behaviours.** The class
   vocabulary is theme-agnostic, and each container speaks exactly one sizing
   language — the fix for the original bug, where `.fh-cell` tried to speak
   both grid-spans and flex-share at once and `columns()`/`fullWidth()` went
   silently inert inside a flexbox row:
   - **`.fh-grid`** (a `Grid` container) and **`.fh-group`** (a dynamic group's
     member flow) are the 12-column *sizing* system: a cell's width is its
     `columns(n)` span (default **4** — a third, HA's `grid_options.columns`
     default card size), `fullWidth()` = 12. This is built on **flexbox, not
     CSS grid**, so a PARTIAL row (spans not summing to 12) group-centers while
     a FULL row fills edge-to-edge — the HA-sections feel. The span basis is
     `calc((100% + gap) * n/12 - gap)`: the `+gap`/`-gap` cancels the
     inter-cell gaps so any set of spans summing to 12 fits one row exactly
     (CSS grid gets this free but can't center partial rows; flexbox centers
     but needs the gap-cancelling calc — we want the centering). Centering is
     the **default**; `Grid.centered(false)` emits the `.fh-start` marker to
     left-pack. On phones (`max-width:640px`) every grid cell collapses to full
     width.
   - **`.fh-row`** is a horizontal flex line whose children SHARE the width
     equally (`flex:1 1 0`).
   - **`.fh-col`** is a vertical flex stack whose children FILL the width.

   So `columns(n)`/`fullWidth()` are **grid-only** affordances — inside a
   row/column you don't size children, they share/fill; rows and columns nest
   freely into each other and into grid cells. Whether a cell fills its share at
   all is a SEPARATE question from how wide that share is: `.fh-hug`
   (`LayoutNode.hug()`) shrinks a cell to its content, so a run of them packs at
   the container's own gap. Filling is the default because it is what a card
   wants; hugging is what makes a row of pills read as a row of pills instead of
   short labels rattling around in equal boxes. `--fh-gap` is the spacing knob;
   `core/css.pkl` carries all of this as `layoutCss`, which `entry.pkl` puts on
   the dashboard itself and the renderer emits ahead of any theme (ADR 0020) —
   so a theme inherits the layout system rather than having to interpolate it,
   and overrides it only if it means to replace the whole thing. The
   default-span selectors
   use `:where()` so any authored `.fh-cols-<n>` wins on specificity.
6. **Authoring follows HA naming.** Layout builders live on the Pkl
   `LayoutNode` base, so components and dynamic groups share them: `columns(n)`
   (HA `grid_options.columns`), `fullWidth()` (HA `columns: full`), `hug()`
   (shrink to content), and the `cellClass("…")` escape hatch. They append to the node's `cell.classes`
   (never amending the null default) and return the base `LayoutNode` type —
   chain them AFTER card-specific builders (`c.entityCard(e).tapAction(…).columns(3)`).
   `caseOf` copies a render fn's `cell` onto the emitted `Case`. The `Grid`
   class (`card = "fhgrid"`) is the default top-level container and carries the
   `centered(on: Boolean)` toggle (group-centering, default on); `Row`/`Column`
   are the one-dimensional share/fill flows that nest inside it.

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

### The cell is the layout item, and for a leaf it is the patch target too

"Every node is a cell" means two things, and only one of them is about every
node:

- **the cell is the layout box** — the flex/grid item, carrying the authored
  `cell` classes. True for every node without exception, structure included.
- **the cell is the Datastar morph target** — true for a LEAF, and for nothing
  else. A structural card's element contains the regions it holds, so a patch
  aimed at it would carry their bytes back with it (ADR 0012).

So the mapping is one function, in one direction only:

> `elementId(nodeId)` = `nodeId`, always. Whether a node may be a patch target
> at all is `CardDef.isStructure`, asked of the card.

**Structure is not a patch target and not a log key.** Its `.fh-cell` is a
constant wrapper around holes; rendering it by id would render its whole
subtree, which is its children's business and not its own. `Dashboard.validate`
says the same from the other side — it rejects a live BYTES slot on structure
*because there is nothing to patch* — and the log follows the same rule (ADR
0011, "what is a log key"). Candidate-set roots are in the same class, for the
same reason.

**A structural card may read `bakeIndex`.** It is the node's own selected index,
and the shipped tab bar seeds a signal from it. What a structural card may not
do is show a live value as BYTES: it holds content, so it would have to be
patched for those bytes to move, and it cannot be. A card wanting its own markup
to change puts that markup in a region, as a node — a slider's head is a card of
its own for exactly this reason — or carries the value as a SIGNAL slot (ADR
0017), which never becomes bytes in its element.

**The log key is always the node id.** A bake host's `<nodeId>_<bakeAs>` is a
rendering detail derived from it and nothing maps back, so a DOM id can never
enter the log, the reverse index, or a cursor (ADR 0022).

A bake-group owner (`Tabs`, `If`) gets a cell like anything else. Being a layout
item and being a patch target are different jobs, and denying it the first
because it fails the second is what used to drop `.columns(n)` on a `Tabs`
silently.

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
- `wrapAsCell = false` means exactly one thing: *my root must not be wrapped in
  a layout box*. It does not imply "never a patch target" — that is decided by
  card shape (`CardDef.isStructure`).
- DOM depth grows by one `div` per node; inert in flow terms everywhere but
  the layout containers, where it is the point.
- The dynamic-group root's classes (`fh-cell fh-group`) are renderer policy,
  not a template — unlike static containers, a group's member flow has no
  card seam. A group wanting a non-grid flow says so via `cellClass` (an
  entry-appended rule ties the `.fh-group` display rule on specificity and
  wins by order; the span defaults are `:where()`-wrapped and always lose).
  If that proves too coarse, the same-depth generalization is an optional
  container-card reference on `Dynamic` — deferred until a real dashboard
  needs it.

## Verification

`fh-datastar-view/testFull` green (132 tests): renderer wrapper semantics
(universal wrap, `wrapAsCell = false` bare render, cell classes on all three
wrapper kinds incl. the per-member in-place path), builder-vs-property JSON
identity, the `Grid.centered(false)` → `.fh-start` marker (default emits no
class), `caseOf` cell copy, validate's token rejection, and the wire snapshots
(regenerated deliberately for the flex-based grid CSS — span basis, group
centering, mobile collapse — and the demo's grid tree). **Visual verification
in the browser (`sbt dashboardServe` — grid flow, partial-row centering,
row-share/column-fill nesting, the phone collapse, light/dark) is still
pending**; per ADR 0006 that check cannot be done from the terminal.
