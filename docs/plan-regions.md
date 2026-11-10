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
set's members. And a variable is read three ways, none of them an expression: a fetch window's
`Choice.Chosen(by: String, …)`, a bake member's `Activation.Var`, and a Datastar string a card
builds in Pkl around an `@@VAR:<name>@@` token (`TabButton.active`).

**State:** direction agreed in chat (2026-11-10); not built.

## The idea

A region is a slot whose value is nodes, and a variable a card's markup refers to is a slot too.
The differences between the three kinds are the kind's own rules:

| | `ValueSlot` (today's `Slot`) | `NodesSlot` | `VarSlot` |
|---|---|---|---|
| Declared | `slotMod.slot("label")` | `slotMod.nodes("features")` | `slotMod.variable("chooses")` |
| Placed | `\(label)`, `label.raw`, `label.bind`, sections | `\(features)`, a raw hole; once, outside every section | `\(chooses.chosen)`, `.committed`, `.pending`, `.urlParam` |
| Filled | `label.of(literal \| reading)` | `features.of(nodes \| choice \| set)` | `chooses.of(v)`, a `varMod.Variable` |
| Live form | `signal`: a change is a frame | a choice or a set: a change is a patch into the card's root | none: the browser reads the variable's own signals |
| Card | leaf, if no slot is nodes | structure (ADR 0012): its other live slots are signals | either |

The leaf/structure rule stays as it is: a nodes slot's content never comes back through its card's
patch.

## Design

### 1. Three slot classes

`core/slot.pkl` gets an abstract `Slot` holding the name. `ValueSlot` is today's `Slot` with all its
members (`bind`, `raw`, `jsValue`, `ifSet`, …); `NodesSlot` and `VarSlot` have only what their kind
needs, so `panel.bind` is a Pkl error at the line, not a build check. `Listing<slotMod.Slot>` takes
all three. `slotMod.slot(…)`, `bool(…)`, `textRaw(…)` keep their names and return a `ValueSlot`.

`CardDef.regions` and `nodes.Region` go: a card's nodes slots are its handles, as its value slots
are. `children { … }` stays the sugar for the default nodes slot, `children`.

A `VarSlot` exists because a card's template is shared by every node of the card, and one card
serves several variables (`TabButton` serves `c.tabs`' `tab` and `c.select(v)`'s `v`), so the
template names its own handle and each node says which variable it means. The compile resolves
each node's variable to its declarer once, as it resolves a `Value.Var` (§5), and refuses one with
no declarer above, as the `@@VAR:…@@` token does today; the renderer fills `{{{chooses.chosen}}}`
from that, as cheaply as `{{id}}`. A `VarSlot` only spells signal names, so it is not a reader: a
write nothing reads is still refused.

`Variable` keeps what is about the variable: its name and declaration (`vars { v.defaultingTo(x) }`),
its reads (`v.value`, `v.valueIs(x)`, expressions after V), and its write (`v.choose(x)`).
`Variable.below`, `own` and `Spelling` go; `v.choose` keeps its token until the tap call design
(`plan-value-model-follow-ups.md`) replaces the URL a template spells out.

### 2. The card writes its root (step C)

**Done ahead, on branch `card-root` (#687, `docs/plan-card-root.md`):** the `.fh-cell` wrapper
moves into each card's root, written by the library from `tag`/`classes`/`attrs` with the
`{{{cell}}}` and `{{{cellClasses}}}` writer holes; `layoutCell` and `cell.layout` replace
`wrapAsCell`. What this plan relies on: a card's root carries its node's id and is never
re-rendered on a structure card, and a set's member is its card's root (so `<li>`/`<tr>` members
work). Revisit against what landed there.

### 3. Placement and the one host rule

```pkl
const local bar = slotMod.nodes("bar")
const local panel = slotMod.nodes("panel")
cardDef { classes = "fh-col"; template = "<div class=\"fh-tabs\">\(bar)</div>\(panel)" }
slots {
  bar.of(buttons)
  panel.of(new Listing { nodes.group("div", "fh-tab-panel", nodes.match(tab.value, new Mapping { ["0"] = a; ["1"] = b })) })
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

### 4. Fills, and the one choice type

`Choice[A]` (`Dashboard.scala:237`) already picks a fetch window by a variable:
`Fixed(value) | Chosen(by: String, options: Map[String, A])`. It becomes the model's one choice,
for windows and for nodes, with `by` an expression and typed keys:

```scala
enum Choice[+A]:
  case Fixed(value: A)
  case Chosen(by: ValueExpr, cases: List[Case[A]], otherwise: Option[A] = None)
case class Case[+A](is: Lit, value: A)

enum Fill:                                     // a nodes slot's fill
  case Nodes(choice: Choice[List[LayoutNode]])   // Fixed: today's children; Chosen: a choice
  case Set(candidates: List[String],
           members: Map[String, Choice[LayoutNode]],   // one node: its root is the key's element
           orderBy: List[SortTerm] = Nil, limit: Option[Int] = None)
```

Deciding is evaluate `by`, take the case whose `is` equals it, else `otherwise`, else nothing.

- **`Choice` is data; deciding is the compile's.** `by` needs the evaluator, so `resolve(env)`
  leaves `Choice`: `Ready` holds `by`'s compiled run beside a map from key to each case's ready
  value, as it holds a slot's run beside its slot.
- **What `A` may be:** data the build checks whole and that never depends on the key at render,
  with a decoder and a `Ready` form: a `Window`, a node, a list of nodes. Only `by` is an
  expression, so `A` needs no `ValueType`, and `validate` walks every case (`Choice.all`), as it
  walks every window today.

- **Keys are typed by `by`'s type**, checked in `validate` against `ValueExpr.typed`: `Text`,
  `Bool` or `Whole`; never `Real` (no exact equality), `Dyn` or `List`. On the wire `cases` is a
  list of `{ "is": <scalar>, … }`, since a JSON object's keys are strings and decoding them by
  another field's type would derive in the decoder.
- **A chain of conditions** uses one new step, `IndexOfTrue`, a sibling of `AnyOf`: over a list of
  bools, the index of the first that holds, or no value. `c.iff(p).then(a).elif(q).then(b).else(x)`
  is `by = List(Bool, [p, q]) |> IndexOfTrue`, `cases = [0 → a, 1 → b]`, `otherwise = x`; a plain
  `iff(p)` is `by = p`, `cases = [true → a]`.
- **A set's member is the same shape**, once per candidate: today's `SetMember(clauses: [when → node])`
  is `by = List(Bool, [when₁, …]) |> IndexOfTrue` (a single guard is `by = when`), an unnamed read
  in a guard reading the candidate, and an unguarded member is `Fixed`. No case holding and no
  `otherwise` is today's "not rendered".
- **A fetch window** stays `Choice[Window]`: `p.history.chosen(v, table)` is
  `Chosen(by = Var(v), cases = table)`, with no `otherwise`, so a key the table lacks is still
  refused at the write. `Choice.variable` becomes the variables `by` reads. Since `by` is any
  expression, an entity's state can pick a window too, decided once per version (§5).

The author's side:

```pkl
slots {
  features.of(new Listing { a; b })                                             // fixed nodes
  branch.of(nodes.iff(c.isOn(lamp), a).elif(c.isOn(fan), b).`else`(x))           // conditions
  panel.of(nodes.match(mode.state, new Mapping { ["heat"] = h; ["cool"] = k }, off)) // one value
  members.of(q.from(lights).where(…).orderBy(…).render(c.light))                // a set
}
```

- `c.iff(p).then(…).`else`(…)` stays the author's card and gains `.elif`; else-if no longer nests
  a host per level. Tabs, `c.select` and the slider's controls build `nodes.match` over their
  variable; popups are a `nodes.match` over `popups` (§8).
- **A set** is the slot's whole fill: members are placed relative to each other, so a fixed
  sibling in the same host would need anchors.
- `choose`/`each` and the separate `fills` block do not exist; `inlineSurfaces`, `bakeInto`,
  `bakeAs`, `bakeIndex` and `Region.fill` go once nothing builds them by hand (step R3).

### 5. A variable is a source (step V; its guards in R4)

**Done ahead, on branch `variable-source` (#688, `docs/plan-variable-source.md`):** `Value.Var`,
the most-local-source rule, value slots reading a variable with the render cache split by the
values read, `Choice[A]` with `by: ValueExpr` (for fetch windows), and `cellClassAtRender` lighting
the open tab in the first page. Revisit this section against what landed there.

What stays here:
- **The tab's live highlight** is spelled with a `VarSlot` from R1
  (`String(\(chooses.chosen)) === '\(choiceSlot)'`), and `TabButton.active` goes.
- **A guard** is `Choice.Chosen`'s `by` (§4). The bake groups' `Activation.Var` and `VarAt` are
  replaced in R4, where the pick replaces bake groups; R3's choices over a variable lower to them
  in the build until then.

A guard may read a variable and entity state together: a state change (through the reverse
index) or the variable's write wakes it, and it is decided per viewer, a cost only such a guard
pays.

### 6. Sets

- `.of(set)` places the members straight into its card's root, with no wrapper of its own. With
  C, a member is its card's root, so `tag = "ul"` over members with `tag = "li"` is valid HTML.
- `.build()` is sugar for a `group` with `fh-group` among its classes: one slot, filled with
  `.of(set)`, and today's bytes (`<div class="fh-cell fh-group" id="…">`), so it keeps working for a
  set among fixed siblings.
- A wrapper of the author's own is any card whose template is one nodes slot.

### 7. The wire

Pkl lowers `slots` to one map, as it lowers them to `templateSlots` today, each slot carrying its
kind:

```scala
slots: Map[String, Slot]
enum Slot:                         // decoded by `kind`
  case Value(slot: TemplateSlot)   // "kind": "value", today's TemplateSlot fields beside it
  case Nodes(fill: Fill)           // "kind": "nodes" (§4)
  case Var(variable: String)       // "kind": "var", resolved at compile
```

- One map is one namespace, as a template's names are: a value slot and a nodes slot cannot share
  a name, with no rule to say so.
- The decoder only picks the case by `kind`. `Ready`'s compile splits the map by kind once, so the
  evaluator, the walk and the variable resolution each get a typed map of their own, and no
  consumer matches on the union.
- Each Pkl slot class carries its `kind`, which `WireShapeSuite` pairs with the Scala case.
- Cell slots keep using `TemplateSlot` inside `Cell`, whose maps are their placement.

`LayoutNode.SetNode` goes, and a set is a `Component`. `CardDef` loses `wrapAsCell` and gains `layoutCell`; `Cell` gains `layout` and
`atRender`; `tag`, `classes` and `attrs` do not reach the wire, since Pkl assembles them into the
template.

### 8. One runtime path

A host is filled by one mechanism, a **pick**: keys, each with its `Choice`, an order and a limit.
A `Fill.Nodes` with a `Chosen` is one key; a `Set` is one key per candidate.
- **Deciding** happens on every send, as a selection is resolved per send today, with nothing
  stored between sends: each key's `by` is evaluated where its sources live (§5), by the evaluator
  slots use, and its case looked up. A `by` that is a `Var` is a read and a lookup, however many
  cases it has. Two keys with the same `by` share one evaluation.
- **Fetch laziness:** a case's fetches are read only once it is chosen, the rule a reading's
  `valueWhile` already has (`Fetching.Gate`); the pick reuses it.
- **Patching:** one planner for arrive, leave, switch and move: `Before`/`Append`/`Remove` per
  key, and a switch replaces what the key shows. A set's key is its member's root,
  `<host>_<key>`; nodes inside get ids per case.
- **Popups** are one `nodes.match(popups, …)` on the root, in the chrome's `#popups` (the one host
  that is not a card's root; `validate` already checks the chrome writes it,
  `Dashboard.scala:1094`): one case per popup on the dashboard, nothing otherwise. An open is a
  write, a lookup and one patch; a close is the pick's ordinary leave, and `orphanedPopup` becomes
  a `remove` on `#popups > *` (the bundle's `remove` mode takes every match), so Scala writes no
  popup markup. `Selections.popup`, `openPopup` and the popup case in `committedSelection` go; the
  build collects every popup in the tree into the match, as the inline-surface hoist collects them
  today, and the back button moves to the variable's URL mirror. Step 0 checks this works.
- `SurfaceGraph`'s bake groups, `MemberGraph`'s special nesting (`WalkAt.InMember`, `innerSetId`,
  `groupAt`) and the inline-surface hoist go into the one path.

### 9. Markup Scala stops writing

The rule: Scala renders markup Pkl wrote and fills only values it owns.
- **The set's root** (§6), here.
- Done ahead: the cell wrapper (#687, `card-root`), the popup close's literal (#684,
  `popup-close`), the failed-fetch label and the offline banners (#685, `markup-from-pkl`).

What stays in Scala, as the contract: the page shell, the root `<div data-signals…>` with its
seeds, toast, reload and back-button handlers, the error page, and the default
`<main id="dashboard">` chrome.

## Steps

Stacked branches, each its own PR on the one before. The local workspaces outside the repo
(`dashboard-local-dev-server/`, the HA box's) are migrated only when the maintainer asks.

Done ahead of this plan, as their own plans and a stack off main: #684 `popup-close` →
#685 `markup-from-pkl` → #686 `golden-ids` (the id-normalised golden check R3 and R4 use) →
#687 `card-root` (C) → #688 `variable-source` (V). This plan's R steps stack on them. When they
have landed, revisit §2, §5, §9 and the steps below against what they built.

0. **The popup-variable spike**, on a throwaway branch: opening a popup through the variable's
   write (`setVar`) instead of `/sse/surface/open`, that route re-minting a forgotten `conn` as the
   surface routes do (ADR 0024), the back button through the URL mirror, `ui_popups` holding at
   most one id, and a match over every popup reproducing today's behaviour for popups declared
   inside a set's member or a choice's case, including the member leaving while its popup is open.
   The findings go into this plan, or into `docs/experiments.md` if the answer is no.
1. **C** — #687. 2. **V** — #688.
3. **R1, slot classes.**
   - `ValueSlot`/`NodesSlot`/`VarSlot`; `CardDef.regions` and `nodes.Region` go; the 86
     `slotMod.Slot` type references in 17 library files become `ValueSlot` where they use a value
     member (one bulk edit), and the library's `nodes` import alias is renamed so it no longer reads
     like `slotMod.nodes` (one bulk edit).
   - The one `slots` wire map (§7), `templateSlots` gone into it, with `Fill.Nodes(Fixed)`; wire
     snapshots change once. Every card that
     declares a region moves: `layout.pkl` (Grid, Row, Column), `core/surface.pkl` (Popup),
     `components/base/surface.pkl` (Tabs, If), `components/select.pkl`, `components/base/slider.pkl`,
     `components/base/tile.pkl`. `TabButton` spells its highlight with a `VarSlot`; `active`,
     `Variable.below`/`own` and `Spelling` go.
   - Goldens byte-identical. Pkl facts: a typo is an error, an unplaced nodes slot is an error, a
     variable slot with no declarer above is an error.
4. **R2, a set is a fill.**
   - `Fill.Set` with members as `Choice[LayoutNode]`, `IndexOfTrue`, `.of(set)`, the `group` card;
     `SetNode` goes from Pkl, the wire, `Ready`, `Validate` and `Renderer`, and `groupOpen` goes.
   - Goldens byte-identical. Bench `publishSetOrdered`, `leaderRow`, `tickRender`, and `validated`
     timing. Pkl fact: a set beside other content is an error.
5. **R3, choices.**
   - `Fill.Nodes(Chosen)`, `nodes.iff`/`elif`/`else`, `nodes.match`, `c.iff(…).elif`; Tabs, If,
     select and the slider's controls on it, the three that host beside other content through a
     `group`. `inlineSurfaces` stays only for popups; `Region.fill` and the bake fields,
     `bakeIndex` included, go.
   - The four suites building on `{{bakeIndex}}` (`LiveStreamSuite`, `RenderInputsSuite`,
     `RendererSuite`, `ServerHarness`) are rewritten; `LiveStreamSuite`'s server-rendered active
     tab moves to `cellClassAtRender` (agreed 2026-11-10).
   - The runtime still bakes: a choice lowers to today's bake groups inside the build, each baked
     into its card's root, a choice over a variable through today's `Activation.Var`.
   - Ids move: the If loses a `div`, and everything in a panel sits one path step deeper, under its
     `group`. The view tests are the check, and the goldens are compared with ids normalised to
     show nothing else moved. A URL naming a variable declared inside a panel (`v.<id>.x`) stops
     matching.
6. **R4, one runtime path.**
   - A test of one guard reading a variable and a state, with two viewers on different values.
   - The pick, its planner and its ids (§8), popups included; deciding per send; `Activation.Var`,
     `VarAt`, `SurfaceGraph`'s bake groups and `MemberGraph`'s nesting cases go; the popup
     literals go.
   - Ids move again; the same normalised comparison, then the goldens are regenerated.
   - Bench everything C, V and R2 measured, plus a popup open and close on the largest test
     dashboard.
7. **R5, markup.** A test that no Scala source writes markup outside the contract in §9 (the
   label and banners are #685).

Docs go in each step's commit: ADRs 0002 (popups), 0003 (sets), 0005/0025/0033 (the popup
variable, the variable slot, `below`/`own` gone), 0007 (choices), 0008 (the cell is the card's
root), 0012 (structure, `bakeIndex`, the cache's buckets), 0017, 0020 (theme classes on the root),
0022 (ids), 0034/0035 (`Var`, `Choice`, `IndexOfTrue`); the pipeline doc §1a and §3–§4b;
terminology (region, host, member, clause, cell, plus **nodes slot**, **variable slot**, **fill**,
**pick** and **group**; bake, bake group, `bakeInto`, `bakeAs` and `bakeIndex` go in R4);
`writing-components.md` (the root fields, `layoutCell`, the one host rule, the three slot kinds)
and `writing-dashboards.md` (`elif`, `nodes.match`, `.of(set)`); `theme.pkl`'s note on `fh-cell`;
the module `CLAUDE.md` lines on the cell, `.fh-group` and variables. This plan is deleted when the
maintainer says so.

## Open questions

1. **Names**, free to change while v0: the `core/node.pkl` alias, `group` (it shares a word with
   the bake group until R4), `layoutCell`, `atRender`, `pick`, `IndexOfTrue`, `Slot`'s kinds.
2. **The select's dropdown label over a variable** is a bake group only so the first HTML shows the
   chosen option (`select.pkl:15`). After V a value slot reading the variable could show it, but
   it sits on a structure card, which refuses a live slot written as bytes; settle it in R3.
3. **Not built until a card needs it:** a cell class whose name an expression computes
   (`cellClassFrom(state.match(…))`, expanded in Pkl to one bool per literal arm), and `Step.Match`
   with typed keys like `Case.is`.
