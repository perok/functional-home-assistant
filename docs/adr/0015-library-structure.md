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
            light · lock · moreinfo · history · bar · progress · tap ·
            defaults
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
a card of its own: `c.progress` is the entity tile of the countdown or the
percentage it is given, with a bar in its `features` region. It finds the
sensors beside it on that sensor's device itself, by `defaults.progress`
(ADR 0013). It registers no template, so a page showing it beside other tiles
carries one tile card.

While nothing runs the card stays and its bar leaves: the bar sits in a `c.iff` on whether it
runs, so it is gone (its empty host's cell collapses) and reads no history, and the countdown has
no value (`valueWhile`, ADR 0034). The card is then the size of a plain tile. Whether it runs
(`running`, `true` when nothing can tell, so it is never null) is decided by the
numbers: the countdown above 0, else a percentage between 0 and 100. A status
overrules them only through `defaults.progress.idleStates`, a list of IDLE words, shipped empty:
a missing idle word shows a bar at 0, where a missing running word would hide a running
appliance, and the words differ per integration. A battery is never found as a bar; one put on
the card with `.percent(battery)` always shows, charging or not.

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
- `buttons` is a list of taps and nodes, each tap a pill in its own look
  (`core/tap.pkl`'s `icon`/`label`/`lit`), so the base needs no HA to show
  one. A `nodes.SubjectButton` is one the slider fills in from its subject entity
  (`c.its`, below): `EntitySlider` resolves them into `shownButtons`, so the base never
  names an entity, and refuses one left unresolved.
- What makes a slider "about this entity" stays HA: `SlideAxis`, `domainAxes`,
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

`c.tap.*` (what a click does in HA), `c.view.*` (what a click only shows),
`c.its.*` (below), `c.light.*` (a domain's controls), `c.recipes.*`, and
`c.defaults` (below).
The everyday cards stay flat — `c.entityCard`, `c.entitySlider`, `c.button` — because
those are the names an author wants first, and a namespace in front of them buys
nothing. `c.light` is the shape the next modelled domain follows (`c.cover.*`),
which is what makes the grouping worth having rather than decorative.

### `c.its`: what the card's own entity offers

A component about an entity that holds a list of buttons takes them in two
forms. A BUILDING BLOCK names its target — `c.tap.toggle(l)`,
`c.view.colourWheel(l.colour)` — and works anywhere, on any target. A `c.its`
item names none and means "this card's own entity", its subject entity: the
card holding it fills it in.

```pkl
c.entitySlider(light).buttons(List(c.its.toggle, c.its.mainControl, c.its.colourWheel))
```

- **Named for the relation, not the component.** `c.<component><Property>.thing`
  (`c.entitySliderButton.toggle`) was the first proposal; it would give every
  component that grows a buttons list its own copy of the same items to learn
  and document. One namespace serves every one.
- **One the entity cannot do shows nothing.** The wheel is only on a light with
  colour, the main control switch only with two or more, so one list serves a
  loop over mixed lights with no `ifNonNull`. The cost is ADR 0013's editor
  check on a single named light: `c.its.colourWheel` on a light without colour
  is no wheel, where `c.view.colourWheel(l.colour)` is an editor error. That is
  why the building blocks stay.
- **Not the entity-first builder ADR 0013 rejects.** Nothing is bound to an
  entity in the author's scope; the card resolves a value it was handed.
- **Only meaningful on a card about an entity.** An item is neither a tap nor a node, so a
  `c.button` or a card refuses it by type.

`components/its.pkl` holds the items. Each is an `ItsButton` (a
`core/node.pkl` `SubjectButton`) with `resolve(h)`: the building block on what
the card hands it, or null where that cannot do it. The `Holder` is the card's
subject entity and whatever else a card knows that an item may need: a light's
slider adds the main controls it shows, so `mainControl` builds its own
`c.select` from them, and shows nothing on a card that hands none. The one
asymmetry left is the slider READING `mainControl`'s options, since they also
decide which heads it has.

**The control names are each domain's.** `its.MainControl` is a union of typealiases a domain's
`hass/` file declares (`hassLight.Control`, re-exported as `hass.LightControl`), each name an
`Axis` fixes for itself, so `.options(List("colour"))` is checked in the editor. A domain with
main controls adds its alias to the union; the labels and icons stay in `its.pkl`, being looks.

**Adding an item:** a class in `its.pkl` whose `resolve` returns an existing
building block from the `Holder`, and a `const` value for it. An item needing
something no card hands yet adds a field to `Holder`, defaulted to empty, and
the card that knows it fills it.

**Adding a card that takes them:** give its base card a `buttons` list typed
to accept `nodes.SubjectButton`, and in its HA subclass map each `ItsButton`
through `resolve(new Holder { entity = … })` before the base sees it, as
`EntitySlider.shownButtons` does. The items come for free.

### Your components: the workspace's `components.pkl` and `defaults`

A dashboard author reaches the facade through their own workspace's
`components.pkl` (seeded with the starter site, then theirs: ADR 0010), which amends the library's
and sets `defaults`. Every file imports that one:

```pkl
// components.pkl
amends "@fh-dashboard/components.pkl"

defaults {
  light { opensOn = List("colourTemp", "colour") }
}

// site.pkl, kitchen.pkl, …
import "components.pkl" as c
```

Importing `@fh-dashboard/components.pkl` directly still works and gets the
library's defaults. Neither the library nor the dump can import the workspace,
and Pkl has no implicit context, so a value every card must see reaches a card
only by late binding on the module the workspace amends, then by being handed
over. The rules every default follows:

- **One class, grouped by card family.** `components/defaults.pkl` declares
  `Defaults` with a group per family (`light: Light`, `progress: ProgressRules`),
  each default named after the builder it feeds: `defaults.light.opensOn` is
  `.opensOn(…)`'s. A group may hold rules rather than one value:
  `defaults.progress` has a section per role, which `c.progress` reads. It imports
  no card module, so they all can import it. A default takes names, never values
  about one entity, since it applies to every entity.
- **A card that reads a default carries the whole object** (`hidden defaults:
  Defaults = new {}`) and works its property out from it, next to the builder
  that overrides it. Carrying the whole object lets it hand the object to what
  it builds itself.
- **The facade and the recipes hand it over.** The facade's function for such a
  card sets `defaults` from the facade's own `hidden defaults`, which is what
  the workspace amends; the constructor-as-value calls that function. `c.recipes`
  is an instance of `Recipes` given `defaults = module.defaults`, for the cards
  a recipe builds.
- **One card overrides it** with its builder or an amend, which come after.
- **What sees only the library's:** the library import, a family module called
  directly, and `new c.EntitySlider { … }` (`defaults = c.defaults` passes them).

Shapes not taken:

- **The components as a property of `site.pkl`** (`site.c`): nested code cannot
  see an inherited property and pkl-lsp does not flag it (ADR 0006's gotchas;
  `docs/experiments.md`). An import is in scope at every depth.
- **One facade property per default, applied by the facade's function:** it
  reaches only cards built through `c.`, since the library's own sections call
  the family modules directly, and it puts a default's logic away from its
  builder.
- **Replacing a facade function from the workspace file:** amends cannot
  redefine a method ("Method needs a `local` modifier"), and `extends` breaks
  completion (below).
- **Passing defaults down the card tree** from the site: it would reach `new`
  too, but every container kind would have to pass them on.

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
- **Through the workspace's `components.pkl`** (an amending module, imported as
  `c`): the whole facade completes after `c.` at any nesting depth, a namespace
  that is a class INSTANCE (`c.recipes: recipesMod.Recipes`) completes like a
  module re-export, and inside `defaults { }` completion offers the groups, then
  a group's defaults; a misspelt default is "Unresolved property", a name
  outside a union "Type mismatch". Class names after `new c.` do not complete,
  through the library import either. Measured with the library imported by
  path: pkl-lsp loads a package-form project through the `pkl` CLI, which the
  environment that measured it did not have.

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
  card can now look like a shipped one without copying its expressions.

  The icon TABLES themselves are not in that tier, and the split is what the
  audience rule means in practice: `iconFor`/`stateIconFor` are what a component
  author calls, while `hass/icons.pkl`'s per-device-class, per-domain and
  per-state glyph maps are vendored HA facts nobody reads directly. What decides
  the tier is who reads it — and for a vendored table the reader is whoever
  re-syncs against a new HA release, which is why every such table is under
  `hass/` where that person is already looking.
- **This is a breaking rename** (alpha, and taken deliberately): the seven tap
  constructors moved under `c.tap`, `lightControls` under
  `c.light`, `floorView` under `c.recipes`. Existing user dashboards fail at
  build with a "cannot find" naming the old name.
- The wire format is unchanged — the checked-in wire snapshots pass untouched,
  which is the evidence that this was a move and not a redesign.
