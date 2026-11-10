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

Scala writes markup in two more places: the `.fh-cell` wrapper around every node
(`Renderer.cellOpen`, ADR 0008), and the literal `<div id="popups"></div>` when the last popup
closes (`Server.scala:1056`, `Server.orphanedPopup`). The runtime has two paths for "something
picks what fills a host": `SurfaceGraph` bakes a choice into a host, and `MemberGraph` places a
set's members.

**State:** direction agreed in chat (2026-11-10); not built.

## The idea

A region is a slot whose value is nodes. The differences from a value slot are the type's own
rules, as a value slot's sink already depends on its `type` and `signal`:

| | `ValueSlot` (today's `Slot`) | `NodesSlot` |
|---|---|---|
| Declared | `slotMod.slot("label")` | `slotMod.nodes("features")` |
| Placed | `\(label)`, `label.raw`, `label.bind`, sections | `\(features)`, a raw hole; once, outside every section |
| Filled | `label.of(literal \| reading)` in the node's `slots {}` | `features.of(nodes \| choice \| set)` in the same `slots {}` |
| Live form | `signal`: a change is a frame; the template must place `bind` | a choice or a set: a change is a patch into the card's root, so the hole must be the card's whole template |
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

### 2. The card writes its root (step C)

The `.fh-cell` wrapper moves from Scala into the card's own root element, which the library writes
from three `CardDef` fields:

```pkl
cardDef { tag = "a"; classes = "fh-tab"; attrs = #"data-on:click="…""#; template = "\(label)" }
```

Pkl lowers it to the wire's one template,
`<a class="fh-cell{{cellClasses}} fh-tab"{{{cell}}} data-on:click="…">{{label}}</a>`. The server
fills `cellClasses` (the node's `cell` classes) and `cell` (the id, the document form's
`data-signals` seed, the cell bindings and the URL mirror), the values `wrapperInto` writes today,
as it injects `{{id}}`. The template is the root's content.

- Every ADR 0008 guarantee holds: the library writes the root, so the id cannot be forgotten, the
  node has exactly one root, and every root carries `fh-cell`. What ADR 0008 rejected (an id the
  author places on the root) stays rejected.
- The build refuses `id=`, `class=` or `data-signals` in `attrs`, a substring check on the field. A
  second `class` or `data-signals` on one element is silently dropped by the browser.
- `layoutCell = false` replaces `wrapAsCell`: the root keeps `cell` and loses the `fh-cell` class,
  for a root that must not be a layout box. The tab anchors need it: in a `.fh-col` they would
  inherit `--fh-cell-width:100%`. The build refuses the cell builders (`columns(n)`, …) on such a
  card; `wrapAsCell`'s other two refusals go, since the root still has its id.
- A card that sets no `tag` gets a `div` root with no classes and no attributes, which is today's
  wrapper byte for byte. So C lands with the goldens unchanged except the tab anchors, and cards
  in a user's workspace keep working.
- A card that moves its own root into the fields loses a `div`, and a set's members can be `<li>`
  or `<tr>`: today a `<div class="fh-cell">` between `<tbody>` and `<tr>` is moved out of the table
  by the HTML parser.

### 3. Placement and the one host rule

```pkl
const local bar = slotMod.nodes("bar")
const local panel = slotMod.nodes("panel")
cardDef { classes = "fh-col"; template = "<div class=\"fh-tabs\">\(bar)</div>\(panel)" }
slots {
  bar.of(buttons)
  panel.of(new Listing { nodes.group("div", "fh-tab-panel", nodes.iff(tab.valueIs("0"), a).elif(tab.valueIs("1"), b)) })
}
```

- `\(features)` prints `{{{features}}}`.
- **A live fill (a choice or a set) must be its card's whole template**, a string comparison in the
  build (`template == "\(x)"`). The card's root is then the host: it is never re-rendered (the
  card is structure), and a patch goes inside it, so its id, seed and bindings survive. The host's
  id is the node's id.
- **A live fill beside other content goes in a `group`**, a card in `core/` whose template is its
  one slot, taking a tag, classes and `layoutCell`. Tabs, the slider's controls and the select
  menu build one internally; their authors write what they write today.
- The If's root hosts its branch (`classes = "fh-ifhost"; template = "\(branch)"`): one element
  where today there are two. `.fh-cell:has(>.fh-ifhost:empty)` becomes `.fh-ifhost:empty`.
- Nothing about hosts reaches the wire, and "host" leaves the API: it stays the runtime's word for
  the element a patch goes into (`docs/terminology.md`). `hostId`, `hostedByCell` and `.host` do
  not exist.

The build refuses a live fill in a slot that is not its card's whole template.

### 4. Fills, all through `of`

As a value slot's `of` takes a literal or a reading, the argument's type says whether the fill is
fixed or live:

```pkl
slots {
  features.of(new Listing { a; b })                               // fixed nodes
  branch.of(nodes.iff(c.isOn(l), x).`else`(y))                    // the first clause that holds
  members.of(q.from(lights).where(…).orderBy(…).render(c.light))  // one node per present candidate
}
```

- **A choice** is flat clauses: one host and one decision, the first guard that holds wins, an
  `else` is a clause with no guard, and none holding is empty. `c.iff(p).then(…).`else`(…)` stays
  the author's card and gains `.elif`; else-if no longer nests a host per level. Tabs, `c.select`
  and the slider's controls build the same chain over their variable through a shortcut.
- **A guard is an expression.** A state guard is a `slotMod.Expr`; a variable guard is
  `v.valueIs(x)`, which after step V is an expression too (§5).
- **A set** is the slot's whole fill: members are placed relative to each other, so a fixed
  sibling in the same host would need anchors.
- `choose`/`each` and the separate `fills` block do not exist; `inlineSurfaces`, `bakeInto`,
  `bakeAs`, `bakeIndex` and `Region.fill` go once nothing builds them by hand (step R3).

### 5. A variable read is a ValueExpr source (step V)

Today a variable is read two ways, neither of them an expression: `Choice` (`Fixed | Chosen(by,
options)`) picks a fetch window, and `ValueIs` / `Activation.Var(name, VarAt)` picks a bake member.
Shared refuses either (`SlotAsk.decision` reads "at no viewer's variables"); the viewer's values
are resolved per send (`Viewing`).

V adds a variable read as a ValueExpr source, resolved up the ancestors as a variable is today.
`valueIs`, a tab's index, a select's value and an `Otherwise` lower to expressions (`tab == "1"`;
`Otherwise` is no guard), and `Activation.Var` and `VarAt` go.

- **A guard** is decided where what it reads is: one that reads a variable per viewer, any other
  once per version in `Shared`, as a fetch read is routed today.
- **A slot** may read a variable too. Its node's bytes then differ per viewer, so the node renders
  per viewer and its render key carries the values it read, as a chart whose window a variable
  picks already keys its bytes by the fetch it chose (`Renderer.renderInputs`).
- **The chosen button is lit at render.** The tab bar's and a select's buttons are lit only by the
  client today (`data-class:fh-chosen` over the pending-or-committed expression, `surface.pkl:45`),
  so a page without scripts shows no open tab. Each button gets `chosen.of(v.valueIs(choice))`, a
  bool slot whose document form writes `fh-chosen` and whose binding is that same expression, so a
  switch still sends nothing for the bar (ADR 0017's two forms). This is what `{{bakeIndex}}` was
  for (ADR 0012), and nothing reads it today.

`validate` refuses, at first, a guard that reads both a variable and entity state. Lifting it is a
later change to where the decision runs, not to the model.

### 6. Sets

- `.of(set)` places the members straight into its card's root, with no wrapper of its own. With
  C, a member is its card's root, so `tag = "ul"` over members with `tag = "li"` is valid HTML.
- `.build()` is sugar for a `group` with `fh-group` among its classes: one slot, filled with
  `.of(set)`, and today's bytes (`<div class="fh-cell fh-group" id="…">`), so it keeps working for a
  set among fixed siblings.
- A wrapper of the author's own is any card whose template is one nodes slot.

### 7. The wire

Pkl splits `slots` by class at lowering, as it lowers them to `templateSlots` today:
- `templateSlots: Map[String, TemplateSlot]`, unchanged (cell slots keep using `TemplateSlot`);
- the nodes slots: `Map[String, Fill]`, with
  `Fill = Nodes(list) | Choice(clauses) | Set(candidates, members, orderBy, limit)`, each clause a
  guard (`Option[ValueExpr]`) and its nodes.

No union type: the two kinds share only the name, and no consumer handles both (values go to the
evaluator, nodes to the walk). `LayoutNode.SetNode` goes, and a set is a `Component`. `CardDef`
loses `wrapAsCell` and gains `layoutCell`; `tag`, `classes` and `attrs` do not reach the wire,
since Pkl assembles them into the template.

### 8. One runtime path

A host is filled by one mechanism, a **pick**: keys, the clauses each key may show, an order and a
limit. A choice is one key; a set is one key per candidate.
- **Deciding:** each key's first holding clause, by the evaluator slots use (`Ready.Condition`),
  in `Shared` or per viewer by what its guard reads (§5). Two guards with the same expression
  share one decision.
- **Fetch laziness:** a clause's fetches are read only once its guard holds, the rule a reading's
  `valueWhile` already has (`Fetching.Gate`); the pick reuses it.
- **Patching:** one planner for arrive, leave, switch and move: `Before`/`Append`/`Remove` per
  key, and a switch replaces what the key shows. A set's key is its member's root,
  `<host>_<key>`; nodes inside get ids per clause.
- **Popups** are one pick over a variable on the root, `popups`, in the chrome's `#popups` (the
  one host that is not a card's root; `validate` already checks the chrome writes it,
  `Dashboard.scala:1094`): one clause per popup on the dashboard, guard `popups == "<id>"`, empty
  otherwise. A close is the pick's ordinary leave, and `orphanedPopup` becomes a `remove` on
  `#popups > *` (the bundle's `remove` mode takes every match), so Scala writes no popup markup.
  `Selections.popup`, `openPopup` and the popup case in `committedSelection` go; the build
  collects every popup in the tree into the pick, and the back button moves to the variable's URL
  mirror. Step 0 checks this works.
- `SurfaceGraph`'s bake groups, `MemberGraph`'s special nesting (`WalkAt.InMember`, `innerSetId`,
  `groupAt`) and the inline-surface hoist go into the one path.

### 9. Markup Scala stops writing

The rule: Scala renders markup Pkl wrote and fills only values it owns.
- **The cell wrapper** (§2) and **the set's root** (§6).
- **The popup host** (§8): nothing to write.
- **The failed-fetch label.** The build compiles `core/text.pkl`'s label like any card template,
  and the runtime renders it with the tone and text. `Fetches.label` and `FailureLabelSuite` go.
- **The offline banners** (`Server.scala:1925–1930`) are a theme template. The signals they read
  (`_sse`, `haDown`) are Pkl constants, so a theme cannot misspell one; the text becomes the
  theme's to translate.

What stays in Scala, as the contract: the page shell, the root `<div data-signals…>` with its
seeds, toast, reload and back-button handlers, the error page, and the default
`<main id="dashboard">` chrome.

## Steps

Stacked branches, each its own PR on the one before.

0. **The popup-variable spike**, on a throwaway branch, before V's design is fixed: opening a popup
   through the variable's write (`setVar`) instead of `/sse/surface/open`, that route re-minting a
   forgotten `conn` as the surface routes do (ADR 0024), the back button through the URL mirror,
   and `ui_popups` holding at most one id. The findings go into this plan, or into
   `docs/experiments.md` if the answer is no.
1. **V, a variable as a source.**
   - ValueExpr gains the variable read; `valueIs`, tab index, select value and `Otherwise` lower to
     guards; `Activation.Var` and `VarAt` go.
   - A guard or slot reading a variable is decided per viewer; a slot's reads join its render key.
   - The tab and select buttons lit at render (§5).
   - Wire snapshots change at every variable guard; goldens change at the buttons only.
   - Bench a tab flip and a select change: allocation first, `-XX:-DoEscapeAnalysis` both sides.
2. **C, the card writes its root.**
   - First commit: `tag`/`classes`/`attrs`, `cell`/`cellClasses`, `layoutCell` for `wrapAsCell`;
     `Renderer` stops wrapping (`cellOpen`). Goldens byte-identical except the tab anchors, which
     gain their id.
   - Then one commit per card family moving its root into the fields; each golden diff shows a
     `div` going. The view tests and CI's visual snapshots are the check, since the bytes move.
   - Bench `tickRender` and a page render, as V.
3. **R1, slot classes.**
   - `ValueSlot`/`NodesSlot`; `CardDef.regions` and `nodes.Region` go; the 86 `slotMod.Slot` type
     references in 17 library files become `ValueSlot` where they use a value member (one bulk
     edit), and the library's `nodes` import alias is renamed so it no longer reads like
     `slotMod.nodes` (one bulk edit).
   - The nodes-slot wire map with `Fill.Nodes`. Every card that declares a region moves:
     `layout.pkl` (Grid, Row, Column), `core/surface.pkl` (Popup), `components/base/surface.pkl`
     (Tabs, If), `components/select.pkl`, `components/base/slider.pkl`, `components/base/tile.pkl`.
   - Goldens byte-identical. Pkl facts: a typo is an error, an unplaced nodes slot is an error.
4. **R2, a set is a fill.**
   - `Fill.Set`, `.of(set)`, the `group` card; `SetNode` goes from Pkl, the wire, `Ready`,
     `Validate` and `Renderer`, and `groupOpen` goes.
   - Goldens byte-identical. Bench `publishSetOrdered`, `leaderRow`, `tickRender`, and `validated`
     timing. Pkl fact: a set beside other content is an error.
5. **R3, choices.**
   - `Fill.Choice`, `nodes.iff`/`elif`/`else`, `c.iff(…).elif`; Tabs, If, select and the slider's
     controls on it, the three that host beside other content through a `group`.
     `inlineSurfaces` stays only for popups; `Region.fill` and the bake fields, `bakeIndex`
     included, go.
   - The runtime still bakes: the wire's choice lowers to today's bake groups inside the build,
     each baked into its card's root.
   - Goldens move: the If loses a `div`, and the panels' ids become their group's node id.
6. **R4, one runtime path.**
   - The pick, its planner and its ids (§8), popups included; `SurfaceGraph`'s bake groups and
     `MemberGraph`'s nesting cases go; the popup literals go.
   - Ids move. The view tests are the check; the goldens are compared once with ids normalised, to
     show nothing else moved, and then regenerated.
   - Bench everything R2 and V measured, plus a popup open and close.
7. **R5, markup.** The fetch label from Pkl, the banners from the theme. A test that no Scala
   source writes markup outside the contract in §9.

Docs go in each step's commit: ADRs 0002 (popups), 0003 (sets), 0005/0025/0033 (the popup
variable), 0007 (choices), 0008 (the cell is the card's root), 0012 (structure, `bakeIndex`),
0017 (a variable-read slot's two forms), 0034/0035 (the variable source); the pipeline doc §1a and
§3–§4b; terminology (region, host, bake, member, clause, cell, plus **nodes slot**, **fill**,
**pick** and **group**); `writing-components.md` (the root fields, `layoutCell`, the one host
rule) and `writing-dashboards.md` (`elif`, `.of(set)`); `theme.pkl`'s note on `fh-cell`; the module
`CLAUDE.md` lines on the cell and `.fh-group`. This plan is deleted when the maintainer says so.

## Open questions

1. **Names**, free to change while v0: the `core/node.pkl` alias, `group`, `layoutCell`, `pick`,
   `Fill`'s cases, the nodes-slot wire map.
2. **The select's dropdown label over a variable** is a bake group only so the first HTML shows the
   chosen option (`select.pkl:15`). After V it could be a slot reading the variable, but it sits
   on a structure card, which refuses a live slot written as bytes; settle it in R3.
