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
never touch — `Slot`, `Cell`, `CardDef`, `NODE_ID`, `cardsIn`, `sliderSpec`,
`alwaysHolds`, and the 14-class predicate AST. A name you have to know to skip is
a name that costs something.

**Layering.** `query.pkl` imported `components.pkl` for exactly one reason: 35
references to `Predicate`/`Cmp`/`Set*`/`Sort*` and 5 to `Node`. The query
language depended on the card library to reach a wire shape neither of them owns.

## The decision

Three tiers, by AUDIENCE rather than by kind:

```
core/       node · slot · icon · tap · surface · predicate · — writing a COMPONENT
            badge
            css.pkl — the base stylesheet every dashboard gets (ADR 0020)
layout.pkl  Row/Column/Grid                                  — the boxes you compose into
components.pkl + components/   entity · control · slider ·   — writing a DASHBOARD
            light · lock · moreinfo · history · bar · progress · tap
  components/base/  button · onoff · tile · bar · slider ·   — the same, knowing no HA
                    text · surface
recipes.pkl floorView …                                      — whole sections, opinionated
internal/   dump-base.pkl                                    — generator ↔ generated dump
hass.pkl + hass/  light.pkl                                  — the domain schema
```

`components.pkl` is a **facade**: it declares no cards, re-exporting the everyday
names from the family modules. Its `modules` list and the reflected `cards`
index name the families, because reflection sees only classes a module
DECLARES — never inherited or re-exported ones — so a facade cannot stand in
for them. A dashboard's own registry reads its nodes instead (ADR 0006,
decision 7).

### Base components, and the HA layer on top

Inside the dashboard tier, `components/base/` holds the cards that know nothing
about Home Assistant. They take strings, taps and READINGS (a slot naming the
entity it reads), and never import a `hass` module; `BaseComponentsSuite`
enforces that. `components/` itself is the HA layer.

An HA component is a THIN subclass of a base one: it holds `entity`, and assigns
the base's inputs from it (`label`, `tapAction`, `subject`, an entity card's
`title`/`reading`/`glyph`, and a slider's `position`/`fill`/`commit`
and the rest). Its own inputs keep the meaning they have
relative to the entity — `value("brightness")` names an attribute — which is why
the base's are named differently. It declares no card,
template or slot of its own, so it is exactly the base card an author could have
built by hand, and `components.test.pkl` checks that equality. A subclass rather
than a function, because a Pkl function has no default arguments and its result
forgets the entity. Every builder after it (`c.entityCard(l).value("brightness")`)
would then have to name the entity again. A card with no one subject is the
exception: a progress bar reads a part and a whole, often two entities, so
`components/bar.pkl` is functions returning the base `ProgressBar`, with nothing
to remember.

A recipe that is a familiar card with features is a subclass of that card, not
a card of its own: `c.progress` is the remaining-time sensor's entity tile, its
countdown the reading, with a bar in its `features` region. It registers no
template, so a page showing it beside other tiles carries one tile card.

`secondary` is the one input both tiers share, under HA's own name for the line
below a card's main text. It is the BASE's — a literal, an `Expr` or a reading
— and the HA cards inherit it rather than shadowing it, so a String is the text
on every card and an attribute is asked for by name with `c.attr(name)`. One
word meaning one thing beat keeping the HA cards' shorthand, under which a bare
String was an attribute name on an entity card and the text on a button.

`subject` is the base's one concession: an optional entity id, placed as the
subject slot, which an entity-less reading (`c.expr(…)`) falls back to. Only the
HA layer sets it.

`disabled` is a base input on every pressable card — the button, the tile and
the slider — taking any boolean input (a reading, an `Expr` over the node's
values, or a condition; ADR 0034). It is ORed with the tap's own refusal
(`tapDisabled`) inside the shared refusal helpers of `core/tap.pkl`, not in each
card's template: the click guard, the `fh-disabled` look and the form-control
`disabled` all read one `refused` expression, so a card that places them
honours `disabled` with nothing of its own. A tile, an `<article>`, gets the look
and a refused click; a slider's range is disabled and its commit refuses.

**The slider** is the one split that was not mechanical. The base `Slider`
(`components/base/slider.pkl`, `c.Slider`) takes a track, not an entity, and
`EntitySlider` (`c.entitySlider`) fills it:

- `position`, `fill` and `reading` are THREE readings, not one the base derives
  the rest from. A transform is a CEL string or a `Simple` shape and neither
  composes, so deriving `fill` from an arbitrary reading would mean splicing CEL
  or reaching into one `Simple` shape. The HA layer builds all three from one
  attribute name, which it can.
- The drag commits through `commit`, a `Call` with a key and no value — the
  drag supplies it (ADR 0016). As a plain call it refuses only while its target
  is unavailable, so a cover that is `opening` still takes a new position.
- `press` is the toggle-only variant on the SAME card: set, the track is one
  button and there is no input. A second card would make a group's rows two
  cards for one look. `EntitySlider` sets it for a light that cannot dim.
- `leadingActions` is how the HA `tapAction` shorthand stays first without an
  author's `actions` replacing it.
- What makes a slider "about this entity" stays HA: `SlideAxis`, `sliderSpec`,
  the RGB and kelvin fills, the `"state"`/`"percent"` readout names and the
  `valueExpr`/`percentExpr` splice surface.
- `sliderHead` and `sliderText` do not DECLARE `entity_id`: a declared slot must
  be on every node, and a base slider may have no subject. Every HA slider still
  carries it.

The facade's names do not move: `c.button` and `c.entityButton` are found where
they always were. The registry's are the BASE card's, named for the look like
its class: `Tile` registers `tile` and `Switch` registers `switch`, and an
`EntityCard` or a `Toggle` is a node of that card.

`entry.pkl` stays at the package root: every dashboard's first line is
`amends "@fh-dashboard/entry.pkl"`, and `internal/entry.pkl` would say the
opposite of what is true. `site.pkl` (ADR 0021) sits beside it for the same
reason — it is what the workspace's own `site.pkl` amends, the entry point to
the entry points, and the two are the only modules an author names without
having gone looking for the library.

### Grouped where grouping reads better

`c.tap.*` (what a click does), `c.light.*` (a domain's controls), `c.recipes.*`.
The everyday cards stay flat — `c.entityCard`, `c.entitySlider`, `c.button` — because
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
  `labelSlot`/`valueSlot`/`secondarySlot` (`core/slot.pkl`), icon resolution
  (`core/icon.pkl`), `noSignals` (`core/tap.pkl`). That is a gain: a third-party
  card can now look like a shipped one without copying JSONata.

  The icon TABLES themselves are not in that tier, and the split is what the
  audience rule means in practice: `iconFor`/`stateIconFor` are what a component
  author calls, while `hass/icons.pkl`'s per-device-class, per-domain and
  per-state glyph maps are vendored HA facts nobody reads directly. What decides
  the tier is who reads it — and for a vendored table the reader is whoever
  re-syncs against a new HA release, which is why every such table is under
  `hass/` where that person is already looking.
- **This is a breaking rename** (alpha, and taken deliberately): the seven tap
  constructors moved under `c.tap`, `lightControls`/`effectPills` under
  `c.light`, `floorView` under `c.recipes`. Existing user dashboards fail at
  build with a "cannot find" naming the old name.
- The wire format is unchanged — the checked-in wire snapshots pass untouched,
  which is the evidence that this was a move and not a redesign.
