# Plan: a region is a slot whose value is nodes

A region today is a string that three places must spell alike: the card declares it
(`regions = new Mapping { ["features"] = new nodes.Region {} }`), the template places it
(`{{{features}}}`), and the node fills it (`children = new Mapping { ["features"] = … }`). A baked
region adds a fourth (`bakeAs = "panel"`) and a fifth, `fill = "baked"` on the region. That is the
shape slots had before #657.

What fills a region is also written three different ways, and only two of them in Pkl:

| What fills it | Authored as | Host markup written by |
|---|---|---|
| Fixed nodes | `children { … }`, or `children = new Mapping { [name] = … }` | the card's template |
| One of several branches, picked by a condition or a node variable (`c.iff`, `c.tabs`, `c.select`, the slider's controls) | `inlineSurfaces` with `bakeInto`/`bakeAs`/`bakeIndex`, and `fill = "baked"` on the region | the card's root (#687) |
| One node per present candidate (`q.from(…).render(…).build()`) | `pred.SetNode`, a layout node that is not a card | **Scala**: `Renderer.groupOpen`, `<div class="fh-cell fh-group" id="…">` |

The set's root is the last markup Scala writes outside the page shell. The runtime has two paths
for "something picks what fills a host": `SurfaceGraph` bakes a choice into a host, and
`MemberGraph` places a set's members. A variable is an expression source (`Value.Var`) for value
slots, `cellClassAtRender` and a fetch window's `Choice.Chosen.by`, but a bake member still reads
it as `Activation.Var`, and two Datastar strings are spelled in Pkl from `Variable.below`:
`TabButton.active` and the URL `Variable.choose` posts, around an `@@VAR:<name>@@` declarer token.

**State:** direction agreed in chat (2026-11-10); the prework is on main (below); the R steps are
not built.

## What this builds on

All on main:
- **The card writes its root** (#687): the library writes each card's root from `tag`/`classes`/
  `attrs` with the `{{{cell}}}` and `{{{cellClasses}}}` writer holes; `layoutCell = false` opts out
  of being a layout box; `cell.layout` carries the sizing. A root carries its node's id, is never
  re-rendered on a structure card, and a set's member is its card's root, so `<li>`/`<tr>` members
  work once the set's own root is a card.
- **A variable is a source** (#688): `Value.Var`, read in a leaf's value slots with the render
  cache split by the values read; `Choice[A] = Fixed(value) | Chosen(by: ValueExpr, cases:
  List[Case[A]], otherwise)` with `Case(is: Lit, value)` (`Dashboard.scala:249`), so far only for
  fetch windows, whose keys `validate` types by `by` (`Choice.KeyTypes`); `cellClassAtRender`
  lighting the open tab in the first page.
- **A popup close writes no markup** (#684): a close and `Server.orphanedPopup` are a `remove` on
  `#popups > *`.
- **Markup from Pkl** (#685) and **goldens compared with ids normalised**
  (`sbt 'dashboardGoldensVs <rev>'`, #686), which R3 and R4 use.
- **Declared calls** (#702): a tap names a `CallDecl` by id; the body is `{posted, seen}`, sent as a
  `payload`, so a call's post carries no `conn` and needs no session. `validate` refuses a call
  field that reads a variable ("a call is decided once for every viewer").
- **Sets of areas and floors** (#704): `SetNode.of: IdKind` and `Value.Present.of`. An area has no
  state, so its member's guard runs with no subject and names what it reads (`validate` refuses a
  guard or order term that reads the member); `MemberGraph.movedBy` skips candidates that are not
  entities.
- **Pkl 0.33** (#678, #694): builders return `this`, so a chain keeps its receiver's class.
  **pkl-lsp** support is recorded in `docs/pkl-editor-support.md` with `PklEditorSupportSuite`
  (#698, #701).

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
all three. `slotMod.slot(…)`, `bool(…)`, `textRaw(…)`, `number(…)` keep their names and return a
`ValueSlot`.

`CardDef.regions` and `nodes.Region` go: a card's nodes slots are its handles, as its value slots
are. `children { … }` stays the sugar for the default nodes slot, `children`.

A `VarSlot` exists because a card's template is shared by every node of the card, and one card
serves several variables (`TabButton` serves `c.tabs`' `tab` and `c.select(v)`'s `v`), so the
template names its own handle and each node says which variable it means. The compile resolves
each node's variable to its declarer once, as it resolves a `Value.Var`, and refuses one with no
declarer above, as the `@@VAR:…@@` token does today; the renderer fills `{{{chooses.chosen}}}`
from that, as cheaply as `{{id}}`. A `VarSlot` only spells signal names, so it is not a reader: a
write nothing reads is still refused.

`Variable` keeps what is about the variable: its name and declaration (`vars { v.defaultingTo(x) }`),
its reads (`v.value`, `v.valueIs(x)`), and its write (`v.choose(x)`). `Variable.below`, `own` and
`Spelling` go once nothing spells a string from them: `TabButton.active` goes in R1, but
`v.choose` spells its pending signal, group and URL from `below` (`core/variable.pkl:90`), so it
keeps them until its tap stops spelling a URL (open question 2).

### 2. Placement and the one host rule

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
  the element a patch goes into (`docs/terminology.md`).

The build refuses a live fill in a slot that is not its card's whole template.

### 3. Fills, and the one choice type

`Choice[A]` becomes the model's one choice, for windows and for nodes:

```scala
enum Fill:                                     // a nodes slot's fill
  case Nodes(choice: Choice[List[LayoutNode]])   // Fixed: today's children; Chosen: a choice
  case Set(candidates: List[String],
           members: Map[String, Choice[LayoutNode]],   // one node: its root is the key's element
           orderBy: List[SortTerm] = Nil, limit: Option[Int] = None,
           of: IdKind = IdKind.Entity)
```

Deciding is evaluate `by`, take the case whose `is` equals it, else `otherwise`, else nothing.

- **`Choice` is data; deciding is the compile's.** `Ready` holds `by`'s compiled run beside a map
  from key to each case's ready value, as it holds a slot's run beside its slot.
- **What `A` may be:** data the build checks whole and that never depends on the key at render,
  with a decoder and a `Ready` form: a `Window`, a node, a list of nodes. Only `by` is an
  expression, so `A` needs no `ValueType`, and `validate` walks every case (`Choice.all`), as it
  walks every window today.
- **Keys are typed by `by`'s type**, the check windows already have (`Choice.KeyTypes`: `Text`,
  `Bool` or `Whole`), moved off the window so nodes share it, its messages no longer saying
  "window".
- **A chain of conditions** uses one new step, `IndexOfTrue`, a sibling of `AnyOf`: over a list of
  bools, the index of the first that holds, or no value. `c.iff(p).then(a).elif(q).then(b).else(x)`
  is `by = List(Bool, [p, q]) |> IndexOfTrue`, `cases = [0 → a, 1 → b]`, `otherwise = x`; a plain
  `iff(p)` is `by = p`, `cases = [true → a]`.
- **A set's member is the same shape**, once per candidate: today's `SetMember(clauses: [when → node])`
  is `by = List(Bool, [when₁, …]) |> IndexOfTrue` (a single guard is `by = when`), an unnamed read
  in a guard reading the candidate, and an unguarded member is `Fixed`. No case holding and no
  `otherwise` is today's "not rendered". The presence fold stays in Pkl (`query.pkl` `buildSet`
  folds presence into each clause), so each member's `by` is what is left for that candidate.
  Whether equal guards are shared is open question 3.
- **A fetch window** stays `Choice[Window]`, as built.

The author's side:

```pkl
slots {
  features.of(new Listing { a; b })                                             // fixed nodes
  branch.of(nodes.iff(c.isOn(lamp), a).elif(c.isOn(fan), b).`else`(x))           // conditions
  panel.of(nodes.match(mode.state, new Mapping { ["heat"] = h; ["cool"] = k }, off)) // one value
  members.of(q.from(lights).where(…).orderBy(…).render(c.light))                // a set
}
```

- `c.iff(p).then(…).`else`(…)` stays the author's card and gains `.elif`, each returning `this`
  (#694); else-if no longer nests a host per level. Tabs, `c.select` and the slider's controls
  build `nodes.match` over their variable; popups are a `nodes.match` over `popups` (§7).
- **A set** is the slot's whole fill: members are placed relative to each other, so a fixed
  sibling in the same host would need anchors.
- `choose`/`each` and the separate `fills` block do not exist; `inlineSurfaces`, `bakeInto`,
  `bakeAs`, `bakeIndex` and `Region.fill` go once nothing builds them by hand (step R3).

### 4. Variables in decisions (R4)

- **The tab's live highlight** is spelled with a `VarSlot` from R1
  (`String(\(chooses.chosen)) === '\(choiceSlot)'`), and `TabButton.active` goes.
- **A guard** is `Choice.Chosen`'s `by` (§3). The bake groups' `Activation.Var` and `VarAt` are
  replaced in R4, where the pick replaces bake groups; R3's choices over a variable lower to them
  in the build until then.

A guard may read a variable and entity state together: a state change (through the reverse
index) or the variable's write wakes it, and it is decided per viewer, a cost only such a guard
pays.

**What `validate` refuses today, for this work to lift.** A variable read (`v.value`,
`v.valueIs`) is allowed only in a leaf's value slots, `cellClassAtRender` and a window's `by`;
each refusal below is a stopgap, not a rule to keep:
- **Shared decisions** (clauses, state surfaces, `valueWhile`, order keys): decided per viewer
  once the pick replaces bake groups (R4), and `Activation.Var`/`VarAt` and the lowering of
  `c.iff(v.valueIs(x))` to them go.
- **Call fields** (#702, a set a call targets included): a call is evaluated at the press, so
  reading a variable needs the viewer's session there, and a call's post carries no `conn`. Lifted
  only with open question 2.
- **Signal slots** (`cellClassWhen`, `cellStyle`, any display signal): a plain `v.value` or
  `v.valueIs(x)` can bind the client's own committed signal (`_var_<declarer>__<name>`), with no
  server frame, as the tab bar does; any other expression needs CEL lowered to a Datastar
  expression, which does not exist. Stays refused until a card needs it.
- **A structural card's own bytes**: structure is never cached, so it is a render and a patch per
  viewer on each write; no dashboard needs it yet.

### 5. Sets

- `.of(set)` places the members straight into its card's root, with no wrapper of its own. A
  member is its card's root, so `tag = "ul"` over members with `tag = "li"` is valid HTML.
- `.build()` is sugar for a `group` with `fh-group` among its classes: one slot, filled with
  `.of(set)`, and today's bytes (`<div class="fh-cell fh-group" id="…">`), so it keeps working for a
  set among fixed siblings.
- A wrapper of the author's own is any card whose template is one nodes slot.
- **A set of areas or floors** (#704) is a `Fill.Set` with its `of`: its members render like any
  other, keyed by the area's id; its guards read no member, so only the entities they name move
  it, as `MemberGraph.movedBy` decides today. The pick keeps that rule.

### 6. The wire

Pkl lowers `slots` to one map, as it lowers them to `templateSlots` today, each slot carrying its
kind:

```scala
slots: Map[String, Slot]
enum Slot:                         // decoded by `kind`
  case Value(slot: TemplateSlot)   // "kind": "value", today's TemplateSlot fields beside it
  case Nodes(fill: Fill)           // "kind": "nodes" (§3)
  case Var(variable: String)       // "kind": "var", resolved at compile
```

- One map is one namespace, as a template's names are: a value slot and a nodes slot cannot share
  a name, with no rule to say so.
- The decoder only picks the case by `kind`. `Ready`'s compile splits the map by kind once, so the
  evaluator, the walk and the variable resolution each get a typed map of their own, and no
  consumer matches on the union.
- Each Pkl slot class carries its `kind`, which `WireShapeSuite` pairs with the Scala case.
- Cell slots keep using `TemplateSlot` inside `Cell`, whose maps are their placement.

`LayoutNode.SetNode` goes, and a set is a `Component` whose nodes slot holds a `Fill.Set`.

### 7. One runtime path

A host is filled by one mechanism, a **pick**: keys, each with its `Choice`, an order and a limit.
A `Fill.Nodes` with a `Chosen` is one key; a `Set` is one key per candidate.
- **Deciding** happens on every send, as a selection is resolved per send today, with nothing
  stored between sends: each key's `by` is evaluated where its sources live, by the evaluator
  slots use, and its case looked up. A `by` that is a `Var` is a read and a lookup, however many
  cases it has. A state-only `by` is decided once per version, in the renderer's `Shared`
  (the pipeline doc, §1).
- **Fetch laziness:** a case's fetches are read only once it is chosen, the rule a reading's
  `valueWhile` already has (`Fetching.Gate`); the pick reuses it.
- **Patching:** one planner for arrive, leave, switch and move: `Before`/`Append`/`Remove` per
  key, and a switch replaces what the key shows. A set's key is its member's root,
  `<host>_<key>`; nodes inside get ids per case.
- **Popups** are one `nodes.match(popups, …)` on the root, in the chrome's `#popups` (the one host
  that is not a card's root; `validate` already checks the chrome writes it): one case per popup
  on the dashboard, nothing otherwise. An open is a write, a lookup and one patch; a close is the
  pick's ordinary leave, which is the `remove` on `#popups > *` a close already sends (#684).
  `Selections.popup`, `openPopup`, the popup case in `committedSelection` and the
  `/sse/surface/…/open` and `/sse/popup/…/close` routes go; the build collects every popup in the
  tree into the match, as the inline-surface hoist collects them today, and the back button
  (`Server.scala:1917`, which spells both routes) moves to the variable's URL mirror. Step 0
  checks this works.
- `SurfaceGraph`'s bake groups, `MemberGraph`'s special nesting (`WalkAt.InMember`, `innerSetId`,
  `groupAt`) and the inline-surface hoist go into the one path.

### 8. Markup Scala stops writing

The rule: Scala renders markup Pkl wrote and fills only values it owns. What is left is the set's
root (§5, `Renderer.groupOpen`). What stays in Scala, as the contract: the page shell, the root
`<div data-signals…>` with its seeds, toast, reload and back-button handlers, the error page, and
the default `<main id="dashboard">` chrome.

## Steps

Stacked branches, each its own PR on the one before. The local workspaces outside the repo
(`dashboard-local-dev-server/`, the HA box's) are migrated only when the maintainer asks.

0. **The popup-variable spike**, on a throwaway branch: opening a popup through the variable's
   write (`setVar`) instead of `/sse/surface/open`, that route re-minting a forgotten `conn` as the
   surface routes do (ADR 0024), the back button through the URL mirror, `ui_popups` holding at
   most one id, and a match over every popup reproducing today's behaviour for popups declared
   inside a set's member or a choice's case, including the member leaving while its popup is open.
   The findings go into this plan, or into `docs/experiments.md` if the answer is no.
1. **R1, slot classes.**
   - `ValueSlot`/`NodesSlot`/`VarSlot`; `CardDef.regions` and `nodes.Region` go; the
     `slotMod.Slot` type references in the library become `ValueSlot` where they use a value
     member (one bulk edit), and the library's `nodes` import alias is renamed so it no longer reads
     like `slotMod.nodes` (one bulk edit).
   - The one `slots` wire map (§6), `templateSlots` gone into it, with `Fill.Nodes(Fixed)`; wire
     snapshots change once. Every card that declares a region moves: `layout.pkl` (Grid, Row,
     Column), `core/surface.pkl` (Popup), `components/base/surface.pkl` (Tabs, If),
     `components/select.pkl`, `components/base/slider.pkl`, `components/base/tile.pkl`.
     `TabButton` spells its highlight with a `VarSlot`, and `active` goes.
   - Goldens byte-identical. Pkl facts: a typo is an error, an unplaced nodes slot is an error, a
     variable slot with no declarer above is an error. `PklEditorSupportSuite` cases for what R1
     bets the editor follows: completion on a `slotMod.nodes(…)` handle, and `panel.bind` reported
     on a `NodesSlot`.
2. **R2, a set is a fill.**
   - `Fill.Set` with members as `Choice[LayoutNode]` and its `of`, `IndexOfTrue`, `.of(set)`, the
     `group` card; `SetNode` goes from Pkl, the wire, `Ready`, `Validate` and `Renderer`, and
     `groupOpen` goes.
   - Goldens byte-identical, area sets (`UseCaseSuite`'s rendered rooms) included. Bench
     `publishSetOrdered`, `leaderRow`, `tickRender`, and `validated` timing. Pkl fact: a set beside
     other content is an error.
3. **R3, choices.**
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
     `group`. The view tests are the check, and `dashboardGoldensVs` shows nothing but ids moved. A
     URL naming a variable declared inside a panel (`v.<id>.x`) stops matching.
4. **R4, one runtime path.**
   - A test of one guard reading a variable and a state, with two viewers on different values.
   - The pick, its planner and its ids (§7), popups included; deciding per send; `Activation.Var`,
     `VarAt`, `SurfaceGraph`'s bake groups and `MemberGraph`'s nesting cases go; the shared
     decisions' variable refusals (§4) go.
   - Ids move again; the same normalised comparison, then the goldens are regenerated.
   - Bench everything R2 measured, plus a popup open and close on the largest test dashboard.
5. **R5, markup.** A test that no Scala source writes markup outside the contract in §8.

Docs go in each step's commit: ADRs 0002 (popups), 0003 (sets), 0005/0025/0033 (the popup
variable, the variable slot, `below`/`own` gone), 0007 (choices), 0008 (the set's root is a card),
0012 (structure, `bakeIndex`, deciding per send), 0017, 0020 (theme classes on the root), 0022
(ids), 0034/0035 (`Choice` for nodes, `IndexOfTrue`); the pipeline doc §1a and §3–§4b;
terminology (region, host, member, clause, plus **nodes slot**, **variable slot**, **fill**,
**pick** and **group**; bake, bake group, `bakeInto`, `bakeAs` and `bakeIndex` go in R4);
`writing-components.md` (the one host rule, the three slot kinds) and `writing-dashboards.md`
(`elif`, `nodes.match`, `.of(set)`); the module `CLAUDE.md` lines on `.fh-group`, the If and
variables. This plan is deleted when the work lands.

## Later

- **Dashboards as regions.** A site-level frame holds a shared node tree (a tab bar of
  dashboards) and one nodes slot, picked by a variable whose URL mirror is the path (`/d/<slug>`)
  rather than a query parameter. A load of a path renders that dashboard whole, as a refresh
  restores a tab; a press inside the frame swaps only the slot over the open stream and pushes the
  path; only the chosen dashboard is rendered and fetched. Needs R4's one pick, a path mirror for
  a variable, and a site-level tree above the dashboards. It reopens ADR 0002's "navigation is a
  real page load": the slug becomes a variable rather than the mutable per-session slug that ADR
  rejected, and a link stays an `<a href>`, but a dashboard with another theme or `<head>` still
  needs a page load. And each dashboard is its own renderer and publisher (ADR 0021) with its own
  access rule and call allowlist (ADR 0023), so a swap changes the session's renderer and re-checks
  access, the renderer swap round 12 of this design named. Its own plan, after R4.

## Open questions

1. **Names**, free to change while v0: the `core/node.pkl` alias, `group` (it shares a word with
   the bake group until R4), `pick`, `IndexOfTrue`, `Slot`'s kinds.
2. **One post for every tap**: every tap kind (call, `setVar`, popup open and close) posted by one
   `tapMod.post` to one route with one body (`conn` beside `posted`/`seen`), naming a declared tap
   by id, so `v.choose` no longer spells a URL. Before R1 (it lets `below`/`Spelling` and the
   `@@VAR` token go, gives step 0 its route, and lets R4 lift the call-field refusal), or later,
   with those kept.
3. **Set guards repeat per candidate**: on the wire, `members` holds one `by` per candidate, the
   same expression where the fold left the same residual. Keep that, sharing in the compile what
   is equal, or group candidates by an equal guard on the wire.
4. **The select's dropdown label over a variable** is a bake group only so the first HTML shows the
   chosen option (`select.pkl:15`). A value slot reading the variable could show it, but it sits
   on a structure card, which refuses a live slot written as bytes; settle it in R3.
5. **Not built until a card needs it:** a cell class whose name an expression computes
   (`cellClassFrom(state.match(…))`, expanded in Pkl to one bool per literal arm), and `Step.Match`
   with typed keys like `Case.is`.
