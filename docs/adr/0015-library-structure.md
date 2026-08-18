# ADR 0015 — Library structure: a core kit, the shipped components, and a facade

- **Status:** Accepted
- **Date:** 2026-08-14
- **Scope:** `modules/fh-datastar-view/src/main/resources/dashboards/lib`
- **Refines:** ADR 0006, which chose Pkl as the authoring language and put the
  library under `lib/`. That one owns the language; this one owns the shape of
  what is written in it.

## Context

`components.pkl` had grown to 1821 lines holding four unrelated things: the card
contract a component author extends, the shipped cards a dashboard author
composes, the slot/tap/surface plumbing, and the query wire AST that only
`query.pkl` builds. Two costs followed.

**Discovery.** Typing `c.` offered ~93 names, about 30 of which an author must
never touch — `Slot`, `Cell`, `CardDef`, `NODE_ID`, `cardsOf`, `sliderSpec`,
`alwaysHolds`, and the 14-class predicate AST. A name you have to know to skip is
a name that costs something.

**Layering.** `query.pkl` imported `components.pkl` for exactly one reason: 35
references to `Predicate`/`Cmp`/`Set*`/`Sort*` and 5 to `Node`. The query
language depended on the card library to reach a wire shape neither of them owns.

## The decision

Three tiers, by AUDIENCE rather than by kind:

```
core/       node · slot · icon · tap · surface · predicate   — writing a COMPONENT
            css.pkl — the base stylesheet every dashboard gets (ADR 0020)
layout.pkl  Row/Column/Grid                                  — the boxes you compose into
components.pkl + components/   text · entity · control ·     — writing a DASHBOARD
            slider · surface · light · moreinfo
recipes.pkl floorView …                                      — whole sections, opinionated
internal/   dump-base.pkl                                    — generator ↔ generated dump
hass.pkl + hass/  light.pkl                                  — the domain schema
```

`components.pkl` is a **facade**: it declares no cards, re-exporting the everyday
names from the family modules. `entry.pkl` seeds `componentModules` from
`components.modules`, because reflection sees only classes a module DECLARES —
never inherited or re-exported ones — so a facade cannot stand in for the
families in the card registry.

`entry.pkl` stays at the package root: every dashboard's first line is
`amends "@fh-dashboard/entry.pkl"`, and `internal/entry.pkl` would say the
opposite of what is true. `site.pkl` (ADR 0021) sits beside it for the same
reason — it is what the workspace's own `site.pkl` amends, the entry point to
the entry points, and the two are the only modules an author names without
having gone looking for the library.

### Grouped where grouping reads better

`c.tap.*` (what a click does), `c.light.*` (a domain's controls), `c.recipes.*`.
The everyday cards stay flat — `c.entityCard`, `c.slider`, `c.button` — because
those are the names an author wants first, and a namespace in front of them buys
nothing. `c.light` is the shape the next modelled domain follows (`c.cover.*`),
which is what makes the grouping worth having rather than decorative.

## What the editor forced

Measured against pkl-lsp 0.8.0 driven over JSON-RPC, not assumed. Full write-up
and repro: `docs/issue-report-2-pkl-lsp-extends-completion.md`.

- **Every re-export carries an explicit type.** `hidden tap = tapMod` evaluates
  fine and completes to NOTHING (`unknown`); `hidden tap: tapMod = tapMod`
  completes fully. Untyped re-exports are therefore banned here.
- **The facade must never use `extends`.** A module with an `extends` clause
  completes its own top level correctly and then returns stdlib-only results for
  completion THROUGH any of its properties — which would have killed every
  namespace. `extends` was the obvious way to re-export flatly with no
  boilerplate; it is unusable for that here.
- **A re-exported function must be a real method.** Pkl keeps methods and
  properties in separate namespaces, so `c.entityCard(e)` needs a declared
  `function`, not a function-valued property. Hence ~14 one-line delegations in
  the facade — and the constructor-as-VALUE properties (`hidden entityCard:
  (hass.Entity) -> EntityCard`) beside them, which is what a query's
  `render(c.entityCard)` position takes.
- What an author sees of a signature: completion labels carry **types and arity
  but no parameter names**; hover carries the full signature with names, the doc
  comment, and a jump to the definition — identically through a namespace.
  `signatureHelp` is not implemented by pkl-lsp at all, so there is no hint while
  typing arguments. Keep arity low and doc-comment every exported function.

Note that pkl-intellij is a **separate** implementation (a native PSI plugin, not
an LSP client), so none of this transfers to IntelliJ automatically. The families
therefore stay directly importable (`import "@fh-dashboard/components/light.pkl"`),
which resolves through the import machinery rather than through type inference
and so cannot depend on either tool's cleverness.

## Consequences

- Cyclic module imports are load-bearing and legal in Pkl: `Node.inlineSurfaces`
  and `SurfaceDef.content` are mutually recursive, and `core/node.pkl` ↔
  `core/surface.pkl` import each other. Verified before relying on it.
- `Popup` lives in `core/surface.pkl`, not with the shipped cards: the surface
  mechanism itself wraps with it (`PopupSurface`, `openPopupInline`), so putting
  it in `components/` would point a kernel module at the component tier.
- Shared helpers had to become public for the families to reach them —
  `labelSlot`/`valueSlot`/`secondarySlot` (`core/slot.pkl`), the icon tables
  (`core/icon.pkl`), `noSignals` (`core/tap.pkl`). That is a gain: a third-party
  card can now look like a shipped one without copying JSONata.
- **This is a breaking rename** (alpha, and taken deliberately): the seven tap
  constructors moved under `c.tap`, `lightControls`/`effectPills` under
  `c.light`, `floorView` under `c.recipes`. Existing user dashboards fail at
  build with a "cannot find" naming the old name.
- The wire format is unchanged — the checked-in wire snapshots pass untouched,
  which is the evidence that this was a move and not a redesign.
