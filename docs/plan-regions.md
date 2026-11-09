# Plan: a region is a slot whose value is nodes

A region today is a string that three places must spell alike: the card declares it
(`regions = new Mapping { ["features"] = new nodes.Region {} }`), the template places it
(`{{{features}}}`), and the node fills it (`children = new Mapping { ["features"] = … }`). A baked
region adds a fourth (`bakeAs = "panel"`) and a fifth, the host id the template must remember to
write (`id="{{hostId}}"`). That is the shape slots had before #657.

What fills a region is also written three different ways, and only two of them in Pkl:

| What fills it | Authored as | Host markup written by |
|---|---|---|
| Fixed nodes | `children { … }`, or `children = new Mapping { [name] = … }` | the card's template |
| One of several branches, picked by a condition or a node variable (`c.iff`, `c.tabs`, `c.select`, the slider's controls) | `inlineSurfaces` with `bakeInto`/`bakeAs`/`bakeIndex`, and `fill = "baked"` on the region | the card's template, by convention (`id="{{hostId}}"`) |
| One node per present candidate (`q.from(…).render(…).build()`) | `pred.SetNode`, a layout node that is not a card | **Scala**: `Renderer.groupOpen`, `<div class="fh-cell fh-group" id="…">` |

The popup host is a fourth: `theme.chrome` writes it, and Scala writes it again as a literal
`<div id="popups"></div>` when the last popup closes (`Server.scala:1056`, `Server.orphanedPopup`).
The runtime has two paths for "something picks what fills a host": `SurfaceGraph` bakes a choice
into a host, and `MemberGraph` places a set's members.

**State:** direction agreed in chat (2026-11-09); not built.

## The idea

A region is a slot whose value is nodes. The differences from a value slot are the type's own
rules, as a value slot's sink already depends on its `type` and `signal`:

| | `ValueSlot` (today's `Slot`) | `NodesSlot` |
|---|---|---|
| Declared | `slotMod.slot("label")` | `slotMod.nodes("features")` |
| Placed | `\(label)`, `label.raw`, `label.bind`, sections | `\(features)` (a raw hole), or `\(panel.host)`; once, outside every section |
| Filled | `label.of(literal \| reading)` in the node's `slots {}` | `features.of(nodes \| choice \| set)` in the same `slots {}` |
| Live form | `signal`: a change is a frame; the template must place `bind` | `hosted`: a change is a host patch; the template must place `host` |
| Steps | yes | none: nothing meaningful exists to run on nodes |
| Identity | none | each node has its own id (`LayoutNode.Step(slot, index)`) and its own patch |
| Card | leaf, if no slot is nodes | a card with a nodes slot is structure (ADR 0012): its other live slots are signals |

The leaf/structure rule stays as it is: a nodes slot's content never comes back through its card's
patch.

## Design

### 1. Two slot classes

`core/slot.pkl` gets an abstract `Slot` holding the name. `ValueSlot` is today's `Slot` with all its
members (`bind`, `raw`, `jsValue`, `ifSet`, …); `NodesSlot` has only what nodes need, so
`panel.bind` is a Pkl error at the line, not a build check. `Listing<slotMod.Slot>` takes both.
`slotMod.slot(…)`, `bool(…)`, `textRaw(…)` keep their names and return a `ValueSlot`.

`CardDef.regions` and `nodes.Region` go: a card's nodes slots are its handles, as its value slots
are. `children { … }` stays the sugar for the default nodes slot, `children`.

### 2. Placement and hosts

```pkl
const local features = slotMod.nodes("features")                          // never moves
const local panel = slotMod.nodes("panel").hosted("div", "fh-tab-panel")  // its content moves
const local members = slotMod.nodes("members").hostedByCell()

template = "<div class=\"fh-col\">\(features)\(panel.host)</div>"
```

- `\(features)` prints `{{{features}}}`.
- `\(panel.host)` prints `<div id="{{panel.hostId}}" class="fh-tab-panel">{{{panel}}}</div>`. The
  handle writes the element, so the id cannot be forgotten and the hole is its only content. The id
  is injected per slot, as `{{label.bind}}` is, and still derived in Scala (`Surface.hostId`,
  `<node>_<slot>`), so the per-node `hostId` variable goes.
- `hostedByCell()` makes the node's own `.fh-cell` the host. Its `host` prints only
  `{{{members}}}`, and the build refuses a template that is anything but that, a string comparison
  that needs no HTML parser. The cell carries the node's id, seed and bindings (ADR 0008) and is
  never re-rendered on a structure card, so an `Inner`/`Before`/`Append` into it leaves those
  intact. It is what a set does today without a name: `Renderer.hostId` falls back to the node's
  own id "which only a candidate set uses".
- The host's shape reaches the wire with the slot: `Host = Element(tag, classes) | Cell`. That is
  what lets Scala render a host it did not write (§7).

The build refuses a live fill (§3) in a slot that is not hosted, and a hosted slot placed without
`host`.

### 3. Fills, all through `of`

As a value slot's `of` takes a literal or a reading, the argument's type says whether the fill is
fixed or live:

```pkl
slots {
  features.of(new Listing { a; b })                               // fixed nodes
  panel.of(nodes.iff(tab.valueIs("0"), a).elif(tab.valueIs("1"), b))  // the first clause that holds
  branch.of(nodes.iff(c.isOn(l), x).`else`(y))
  members.of(q.from(lights).where(…).orderBy(…).render(c.light))  // one node per present candidate
}
```

- **A choice** is flat clauses: one host and one decision, the first guard that holds wins, an
  `else` is a clause with no guard, and none holding is empty. `c.iff(p).then(…).`else`(…)` stays
  the author's card and gains `.elif`; else-if no longer nests a host per level. Tabs, `c.select`
  and the slider's controls build the same chain over their variable through a shortcut.
- **A guard is an expression.** A state guard is a `slotMod.Expr`; a variable guard is
  `v.valueIs(x)`, which after step V is an expression too (§4).
- **A set** is the slot's whole fill: members are placed relative to each other, so a fixed
  sibling in the same host would need anchors.
- `choose`/`each` and the separate `fills` block do not exist; `inlineSurfaces`, `bakeInto`,
  `bakeAs`, `bakeIndex` and `Region.fill` go once nothing builds them by hand (step R3).

### 4. A variable read is a ValueExpr source (step V)

Today a variable is read two ways, neither of them an expression: `Choice` (`Fixed | Chosen(by,
options)`) picks a fetch window, and `ValueIs` / `Activation.Var(name, VarAt)` picks a bake member.
Shared refuses either (`SlotAsk.decision` reads "at no viewer's variables"); the viewer's values
are resolved per send (`Viewing`).

V adds a variable read as a ValueExpr source, resolved up the ancestors as a variable is today.
`valueIs`, a tab's index, a select's value and an `Otherwise` lower to expressions (`tab == "1"`;
`Otherwise` is no guard), and `Activation.Var` and `VarAt` go. Where a guard is decided follows
from what it reads, as a fetch read is routed today: one that reads a variable per viewer, any
other once per version in `Shared`.

`validate` refuses, at first, a variable read outside a guard (it would make a card's bytes differ
per viewer) and a guard that reads both a variable and entity state. Lifting either is a later
change to `validate` and to where the decision runs, not to the model.

### 5. Sets: with or without a wrapper

- `.of(set)` in any hosted slot places the members straight into that host, with no wrapper. It
  matters where the parent is a semantic element: a wrapper `<div>` is invalid in a `<ul>` or a
  `<tbody>`.
- `.build()` is sugar for a `group` card in `core/`: one slot `members.hostedByCell()`, filled
  with `.of(set)`, `fh-group` among its cell classes. Its bytes are today's, so `.build()` keeps
  working for a set among fixed siblings, and the cell builders (`.build().fullWidth()`) still
  apply.
- A wrapper of the author's own is any card with a hosted slot: `myCard { slots { rows.of(set) } }`,
  or `children = set` on a layout card whose default slot is hosted.

### 6. The wire

Pkl splits `slots` by class at lowering, as it lowers them to `templateSlots` today:
- `templateSlots: Map[String, TemplateSlot]`, unchanged (cell slots keep using `TemplateSlot`);
- the nodes slots: `Map[String, NodesSlot(fill: Fill, host: Option[Host])]`, with
  `Fill = Nodes(list) | Choice(clauses) | Set(candidates, members, orderBy, limit)`, each clause a
  guard (`Option[ValueExpr]`) and its nodes.

No union type: the two kinds share only the name, and no consumer handles both (values go to the
evaluator, nodes to the walk). `LayoutNode.SetNode` goes, and a set is a `Component`.

### 7. One runtime path

A host is filled by one mechanism, a **pick**: keys, the clauses each key may show, an order and a
limit. A choice is one key; a set is one key per candidate.
- **Deciding:** each key's first holding clause, by the evaluator slots use (`Ready.Condition`),
  in `Shared` or per viewer by what its guard reads (§4). Two guards with the same expression
  share one decision.
- **Fetch laziness:** a clause's fetches are read only once its guard holds, the rule a reading's
  `valueWhile` already has (`Fetching.Gate`); the pick reuses it.
- **Patching:** one planner for arrive, leave, switch and move: `Before`/`Append`/`Remove` per
  key, an `Inner` on the key's element for a switch. A key's element is `<host>_<key>`; nodes
  inside get ids per clause.
- **Popups** are one pick on the chrome's host over a variable on the root, `popups`: one clause
  per popup on the dashboard, guard `popups == "<id>"`, empty otherwise. `ui_popups` already holds
  at most one surface id per viewer (`SurfaceGraph.selectionsIn`), and a tab's selection already
  commits as its variable (ADR 0025), so `Selections.popup`, `openPopup` and the popup case in
  `committedSelection` go. The one gathering left is the build collecting every popup in the tree
  into that pick. The back button (`popstate` closes a popup) moves to the variable's URL mirror.
- `SurfaceGraph`'s bake groups, `MemberGraph`'s special nesting (`WalkAt.InMember`, `innerSetId`,
  `groupAt`) and the inline-surface hoist go into the one path.

### 8. Markup Scala stops writing

The rule: Scala renders markup Pkl wrote and fills only values it owns.
- **The popup host.** A close renders the chrome's host from its `Host` (§2) with nothing in it,
  not a literal, so a theme's attributes on it survive.
- **The failed-fetch label.** The build compiles `core/text.pkl`'s label like any card template,
  and the runtime renders it with the tone and text. `Fetches.label` and `FailureLabelSuite` go.
- **The offline banners** (`Server.scala:1925–1930`) are a theme template. The signals they read
  (`_sse`, `haDown`) are Pkl constants, so a theme cannot misspell one; the text becomes the
  theme's to translate.

What stays in Scala, as the contract: the `.fh-cell` wrapper (ADR 0008), the page shell, the root
`<div data-signals…>` with its seeds, toast, reload and back-button handlers, the error page, and
the default `<main id="dashboard">` chrome.

## Steps

Stacked branches, each its own PR on the one before.

1. **V, a variable as a source.**
   - ValueExpr gains the variable read; `valueIs`, tab index, select value and `Otherwise` lower to
     guards; `Activation.Var` and `VarAt` go.
   - A guard reading a variable is decided per viewer; `validate` refuses the two cases in §4.
   - Wire snapshots change at every variable guard; goldens do not.
   - Bench a tab flip and a select change: allocation first, `-XX:-DoEscapeAnalysis` both sides.
2. **R1, slot classes and hosts.**
   - `ValueSlot`/`NodesSlot`, `hosted(tag, classes)`, `hostedByCell()`, `host`, the per-slot
     `hostId`. `CardDef.regions` and `nodes.Region` go; the 86 `slotMod.Slot` type references in
     17 library files become `ValueSlot` where they use a value member (one bulk edit).
   - The nodes-slot wire map with `Fill.Nodes` and `Host`. Every card that declares a region moves:
     `layout.pkl` (Grid, Row, Column), `core/surface.pkl` (Popup), `components/base/surface.pkl`
     (Tabs, If), `components/select.pkl`, `components/base/slider.pkl`, `components/base/tile.pkl`.
   - Goldens byte-identical except the If's host, the one template writing `class` before `id`.
   - Pkl facts: a typo is an error, a hosted slot placed without `host` is an error, an unplaced
     nodes slot is an error, `hostedByCell` with any other template is an error.
3. **R2, a set is a fill.**
   - `Fill.Set`, `.of(set)`, the `group` card; `SetNode` goes from Pkl, the wire, `Ready`,
     `Validate` and `Renderer`, and `groupOpen` goes.
   - Goldens byte-identical. Bench `publishSetOrdered`, `leaderRow`, `tickRender`, and `validated`
     timing.
4. **R3, choices.**
   - `Fill.Choice`, `nodes.iff`/`elif`/`else`, `c.iff(…).elif`; Tabs, If, select and the slider's
     controls on it. `inlineSurfaces` stays only for popups; `Region.fill` and the bake fields go.
   - The runtime still bakes: the wire's choice lowers to today's bake groups inside the build.
5. **R4, one runtime path.**
   - The pick, its planner and its ids (§7), popups included; `SurfaceGraph`'s bake groups and
     `MemberGraph`'s nesting cases go.
   - Ids move. The view tests are the check; the goldens are compared once with ids normalised, to
     show nothing else moved, and then regenerated.
   - Bench everything R2 and V measured, plus a popup open and close.
6. **R5, markup.** The popup host from its `Host`, the fetch label from Pkl, the banners from the
   theme. A test that no Scala source writes a host or region element.

Docs go in each step's commit: ADRs 0002 (popups), 0003 (sets), 0005/0025/0033 (the popup
variable), 0007 (choices), 0008 (the cell as a host), 0012 (structure), 0034/0035 (the variable
source); the pipeline doc §1a and §3–§4b; terminology (region, host, bake, member, clause, plus
**nodes slot**, **fill** and **pick**); `writing-components.md` and `writing-dashboards.md`
(`elif`, `.of(set)`); the module `CLAUDE.md` line on `.fh-group`. This plan is deleted when the
maintainer says so.

## Open questions

1. **Names.** The nodes-slot wire map (`regions` kept, or `nodeSlots`), `hostedByCell`, `group`,
   `pick`, `Fill`'s cases.
2. **The If on its cell.** An If could host its branch in its own cell and drop the `fh-ifhost`
   div. It changes bytes and the If's CSS; not planned.
3. **The popup variable** (spike in R4): opening through the variable's write instead of
   `/sse/surface/open`, and the back button through the URL mirror.
