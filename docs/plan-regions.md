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
slots, a bool signal slot and a fetch window's `Choice.Chosen.by`, but a bake member still reads
it as `Activation.Var`.

**State:** direction agreed in chat (2026-11-10); the prework is on main (below); R1 is built
(`slot-classes`), R2 is built (`set-fill`), R3 onward not. The opening describes the shape before
R1.

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
- **Declared calls** (#702): a tap names a `CallDecl` by id, its body sent as a `payload`.
  `validate` refuses a call field that reads a variable ("a call is decided once for every
  viewer").
- **A call can set a variable** (#707): `CallDecl = Service | SetVar(variable, value:
  Option[VarValue])`, decoded by `kind`; `VarValue` refines `varMod.Value`'s token with iron, so a
  bad value is refused at decode. `v.choose(x)` is a `tapMod.SetVar`, a `Press` placed by
  `tapMod.post` like a service call, each press class carrying its own `decl`. The server resolves
  the declarer from the pressed node (`?node=`), and `validate` refuses a `SetVar` with no declarer
  above. Every call's body is `{conn, ui_popups, posted, seen}`; a `SetVar` runs in the viewer's
  session, and a `conn` the process does not hold is refused, not re-minted (ADR 0024 §2: only
  the stream makes a session). The click starts with `{{{tap.pending}}}`, the declarer's pending
  signal at that node, and a refusal clears it. Not built, and not needed (§7): `v.choosing(…)`,
  a value the press posts.
- **Set guards, measured** (#708): sharing equal guards' compile, and running a member-free guard
  once per decision, saved nothing (`docs/experiments.md`). Left: the benches `publishSetGuarded`
  and `publishSetNamedGuard`, and tests that each member is decided by its own state.
- **Sets of areas and floors** (#704): `SetNode.of: IdKind` and `Value.Present.of`. An area has no
  state, so its member's guard runs with no subject and names what it reads (`validate` refuses a
  guard or order term that reads the member); `MemberGraph.movedBy` skips candidates that are not
  entities.
- **Pkl 0.33** (#678, #694): builders return `this`, so a chain keeps its receiver's class.
  **pkl-lsp** support is recorded in `docs/pkl-editor-support.md` with `PklEditorSupportSuite`
  (#698, #701).

Below R1, its own PR stacked on this one (branch `var-signal-reads`):
- **A variable read in a bool signal slot**: `cellClassWhen(cls, v.valueIs(x))` binds the
  variable's own pending-or-committed signals at the node, on any card, and the document form
  writes it for the viewer (ADR 0033). `TabButton.active`, `cellClassAtRender`,
  `Variable.below`/`own`/`declarer`/`Spelling` and the `@@VAR` splice are gone, so a declarer is
  resolved in one place (`Dashboard.varScopes`), the one R5 changes.

## The idea

A region is a slot whose value is nodes. The differences between the two kinds are the kind's own
rules:

| | `Slot` (as today) | `NodesSlot` |
|---|---|---|
| Declared | `slotMod.slot("label")` | `slotMod.nodes("features")` |
| Placed | `\(label)`, `label.raw`, `label.bind`, sections | `\(features)`, a raw hole; once, outside every section |
| Filled | `label.of(literal \| reading)` | `features.of(nodes \| choice \| set)` |
| Live form | `signal`: a change is a frame | a choice or a set: a change is a patch into the card's root |
| Card | leaf, if no slot is nodes | structure (ADR 0012): its other live slots are signals |

The leaf/structure rule stays as it is: a nodes slot's content never comes back through its card's
patch.

## Design

### 1. Two slot classes

`Slot` keeps its name and all its members (`bind`, `raw`, `jsValue`, `ifSet`, …), so no component
that types a handle `slotMod.Slot` changes. `NodesSlot` has only what its kind needs, so
`panel.bind` is a Pkl error at the line, not a build check. The two share an abstract base holding
the name (`AnySlot`, name open), which a listing of both kinds is typed with.

`CardDef.regions` and `nodes.Region` go: a card's nodes slots are its handles, as its value slots
are. `children { … }` stays the sugar for the default nodes slot, `children`.

### 2. Placement and the one host rule

```pkl
const local bar = slotMod.nodes("bar")
const local panel = slotMod.nodes("panel")
cardDef { classes = "fh-col"; template = "<div class=\"fh-tabs\">\(bar)</div>\(panel)" }
slots {
  bar.of(buttons)
  panel.of(new Listing { nodeMod.group("div", "fh-tab-panel", nodeMod.match(tab.value, new Mapping { ["0"] = a; ["1"] = b })) })
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
  where today there are two. `.fh-cell:has(>.fh-ifhost:empty)` becomes `.fh-ifhost:empty`. From
  R4: until the pick, a baked branch is patched into an inner `#<node>_<slot>` element.
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
  bools, the index of the first that holds, or no value.
  `nodeMod.iff(p).then(a).elif(q).then(b).else(x)` is `by = List(Bool, [p, q]) |> IndexOfTrue`,
  `cases = [0 → a, 1 → b]`, `otherwise = x`; a plain `iff(p)` is `by = p`, `cases = [true → a]`.
  It reads `null` (an attribute the entity does not report, ADR 0035) as not holding and goes on,
  as a clause's `when` does today, and reads its items one at a time, stopping at the first
  `true`, as `AnyOf` does.
- **A set's member is the same shape**, once per candidate: today's `SetMember(clauses: [when → node])`
  is `by = List(Bool, [when₁, …]) |> IndexOfTrue` (a single guard is `by = when`), an unnamed read
  in a guard reading the candidate, and an unguarded member is `Fixed`. No case holding and no
  `otherwise` is today's "not rendered". The presence fold stays in Pkl (`query.pkl` `buildSet`
  folds presence into each clause), so each member's `by` is what is left for that candidate.
  The wire keeps one `by` per candidate, and each member's `by` is compiled and run on its own.
  Sharing equal guards' compile and running a member-free guard once per decision were both
  measured on today's sets (#708) and saved nothing: a guard's CEL program is already shared per
  scope, and what costs is re-deciding every member when an entity a guard names changes
  (`docs/experiments.md`; Later, "One guard for many members").
- **A fetch window** stays `Choice[Window]`, as built.

The author's side:

```pkl
slots {
  features.of(new Listing { a; b })                                             // fixed nodes
  branch.of(nodeMod.iff(c.isOn(lamp)).then(a).elif(c.isOn(fan)).then(b).`else`(x)) // conditions
  panel.of(nodeMod.match(mode.state, new Mapping { ["heat"] = h; ["cool"] = k }, off)) // one value
  members.of(q.from(lights).where(…).orderBy(…).render(c.light))                // a set
}
```

- One chain, today's: `nodeMod.iff(p).then(a)` gains `.elif(q).then(b)`, each step returning `this`
  (#694), and `c.iff` is a `group` holding one, with the same methods; else-if no longer nests a
  host per level. Tabs, `c.select` and the slider's controls
  build `nodeMod.match` over their variable; popups are a `nodeMod.match` over `popups` (§7).
- **A set** is the slot's whole fill: members are placed relative to each other, so a fixed
  sibling in the same host would need anchors.
- `choose`/`each` and the separate `fills` block do not exist; `inlineSurfaces`, `bakeInto`,
  `bakeAs`, `bakeIndex` and `Region.fill` go once nothing builds them by hand (step R3).

### 4. Variables in decisions (R4)

- **A guard** is `Choice.Chosen`'s `by` (§3). The bake groups' `Activation.Var` and `VarAt` are
  replaced in R4, where the pick replaces bake groups; R3's choices over a variable lower to them
  in the build until then.

A guard may read a variable and entity state together: a state change (through the reverse
index) or the variable's write wakes it, and it is decided per viewer, a cost only such a guard
pays.

**What `validate` refuses today, for this work to lift.** A variable read (`v.value`,
`v.valueIs`) is allowed only in a leaf's value slots, a bool signal slot as `v.valueIs(x)` and a
window's `by`;
each refusal below is a stopgap, not a rule to keep:
- **Shared decisions** (clauses, state surfaces, `valueWhile`, order keys): decided per viewer
  once the pick replaces bake groups (R4), and `Activation.Var`/`VarAt` and the lowering of
  `c.iff(v.valueIs(x))` to them go.
- **Call fields** (#702, a set a call targets included): a call is evaluated at the press, so
  reading a variable needs the viewer's session there. Every call's body carries `conn` (#707),
  so lifting it is the service case reading the presser's variables; it stays refused until a card
  needs it.
- **Signal slots** (`cellClassWhen`, `cellStyle`, any display signal): `v.valueIs(x)` in a bool
  one binds the variable's own signals (below R1); a plain `v.value` could the same way, and any
  other expression needs CEL lowered to a Datastar expression, which does not exist. Stays refused
  until a card needs it.
- **A structural card's own bytes**: structure is never cached, so it is a render and a patch per
  viewer on each write; no dashboard needs it yet.

### 5. Sets

- `.of(set)` places the members straight into its card's root, with no wrapper of its own. A
  member is its card's root, so `tag = "ul"` over members with `tag = "li"` is valid HTML.
- `.build()` is sugar for a `group` with `fh-group` among its classes: its one slot is the
  default `children`, filled with `.of(set)` (`.fill()` is the unbuilt chain's `Fill.Set`), and
  today's bytes (`<div class="fh-cell fh-group" id="…">`), so it keeps working for a set among
  fixed siblings. A group's card name is derived from its tag, classes and `layoutCell`
  (`group_<tag>_<classes>_<layoutCell>`), one template per root it writes.
- Compiled, a set is `Ready.Component.set: Option[SetFill]`, each member a `MemberChoice` picked
  by the raw value its guard reads (a `Lit.key` string per decide cost `publishSetNamedGuard` 17%
  more allocation). `validate` refuses a member that is itself a set (its root and its inner set
  would share an id), and an `otherwise` on a member.
- A wrapper of the author's own is any card whose template is one nodes slot.
- **A set of areas or floors** (#704) is a `Fill.Set` with its `of`: its members render like any
  other, keyed by the area's id; its guards read no member, so only the entities they name move
  it, as `MemberGraph.movedBy` decides today. The pick keeps that rule.

### 6. The wire

Pkl lowers `slots` to one map, `wireSlots` (Pkl's `slots` is the author's, as with `inputs` and
`wireInputs`; Scala's field is `slots`), each slot carrying its kind (built in R1):

```scala
slots: Map[String, Slot]
enum Slot:                         // decoded by `kind`
  case Value(slot: TemplateSlot)   // "kind": "Value", the TemplateSlot fields flat beside it
  case Nodes(fill: Fill)           // "kind": "Nodes" (§3)
```

- One map is one namespace, as a template's names are: a value slot and a nodes slot cannot share
  a name, with no rule to say so.
- The decoder only picks the case by `kind`. `Component` splits the map by kind once
  (`valueSlots`, `fills`, `regions`), so no consumer matches on the union.
- The card's own `regions` on the wire (each nodes slot's name, `fill: baked` for one a bake
  fills) stays until R4; Pkl builds it from the card's nodes slots, a baked one written
  `slotMod.nodes("panel").baked()`. R4 deletes both.
- Each Pkl slot class carries its `kind`, which `WireShapeSuite` pairs with the Scala case.
- Cell slots keep using `TemplateSlot` inside `Cell`, whose maps are their placement.

`LayoutNode.SetNode` goes, and a set is a `Component` whose nodes slot holds a `Fill.Set`.

### 7. One runtime path

A host is filled by one mechanism, a **pick**: keys, each with its `Choice`, an order and a limit.
A `Fill.Nodes` with a `Chosen` is one key; a `Set` is one key per candidate.
- **Deciding** happens on every send, as a selection is resolved per send today: each key's `by`
  is evaluated where its sources live, by the evaluator slots use, and its case looked up. A `by`
  that is a `Var` is a read and a lookup, however many cases it has. A state-only `by` is decided
  once per version, in the renderer's `Shared` (the pipeline doc, §1); one that reads a variable,
  per session.
- **A case no viewer shows costs nothing:** it is not rendered, fetched or logged, today's rule for
  a surface nobody has open, which matters once every popup is a case of one match. Its fetches are
  read only once it is chosen, the rule a reading's `valueWhile` already has (`Fetching.Gate`); the
  pick reuses it.
- **Patching: the log says which hosts to look at, `holds` says what the page has.** A decision
  per viewer cannot be logged once per version, so the record of what a page shows moves to the
  session:
  - The log keeps only `fragments`, which node or host changed at which version; `mutations`,
    `horizon` and the set refill go (ADR 0011). `completeFrom`, for a gap nobody watched, stays.
  - A session's `holds` keeps, beside each node's inputs and signals, each host's keys in the
    order sent. That is one reference per member per session, bounded by the static ids, where
    the log's `mutations` grew until pruned.
  - On a pull or a variable write, each touched host's decided keys are compared with its held
    ones, skipped when equal. One planner turns the difference into `Remove`/`Before`/`Append`
    per key, a switch replacing what a key shows, sent back to front so each anchor is on the
    page. The moves are everything outside a longest increasing run of held positions
    (`Patches.reordered`, today's search, run per session rather than once).
  - A key that leaves takes its nodes' entries and its inner hosts' key lists with it, so an
    absent entry always means "not on the page, send it whole". `holds` is trusted only when the
    cursor is at or above `told`, as today.
  - A set's key is its member's root, `<host>_<key>`; nodes inside get ids per case.
  - Not chosen: the log recording which members' order inputs changed, so only those are placed
    again, with no search. Cheaper, but right only if the log never misses one, and a write to a
    variable an order reads moves members it cannot name. Worth it only if the bench shows the
    search costs.
- **Popups** are one `nodeMod.match(popups, …)` on the root, in the chrome's `#popups` (the one host
  that is not a card's root; `validate` already checks the chrome writes it): one case per popup
  on the dashboard, nothing otherwise. What the spike (step 0) settled:
  - **The root declares `popups`, and a popup's content starts with the root's scope**: the root
    is above every place a popup opens from, so an opener inside a popup (the colour wheel in a
    more-info) resolves the variable. Today a popup is its own scope root (`Dashboard.varScopes`,
    `scopeOf`); after this every root variable reaches popups, a root window chooser included.
    The name is reserved: `validate` refuses an author's declaration of `popups`, which would hide
    the root's from every opener below it (ADR 0033 records a `root.` reference as the
    alternative, should an author ever need to reach past a nearer declaration).
  - **No value is `null`**, the value language's own (ADR 0035): `Match` takes `otherwise`,
    `valueIs` does not hold, and every step passes it on, whatever the variable's type. Scala
    holds it as no value (`Option`). The wire drops a `null` (ADR 0006), so a declaration's
    `default` is a value or an object, `Value | NoValue` (`varMod.noValue`, `v.startingEmpty()`),
    an object decoding as none and an absent `default` still an error; a typed variable's number
    or bool cannot collide with an object. In the browser it is `''` in the committed signal and
    an absent URL parameter, which no chosen value can be. The build declares `popups` with `{}`,
    and a clear reads the default again. ADR 0033's future-work entry on "no value" takes this
    answer, and its pointer to `core/stage.pkl`, which does not exist, goes.
  - **An open** is a `SetVar` call choosing the popup's id, a lookup and one patch. Inline ids are
    tokens already (`<set>_<entity>_self`, `s_<panel>__c_0_self`, spliced before decode); a
    registered `surfaces` key is typed `varMod.Value`. A close chooses nothing, the pick's
    ordinary leave, the `remove` on `#popups > *` a close already sends (#684).
  - **Back and Forward:** a declaration opts in to history (`.inHistory()`, name open): its URL
    mirror pushes an entry per choice (`data-fh-url__history`, which only `ui.popups` uses
    today). On a popstate the shell posts the page's query string once, and the server re-reads
    every `v.` parameter as a page load does, checks each value as a load does, and re-renders
    what moved. A variable kept out of the URL (`notInUrl()`) is left alone, since its absent
    parameter would read as its default. So Back returns the whole previous URL: a change made
    since then to a variable not in history (a window chooser) goes back too. A close stays
    `history.back()`, so the popup's ✕, backdrop and Escape are unchanged.
  - **Two history variables** need the mirror to mark the entries it made as its own, not by key,
    and to strip every history parameter from an entry it did not make before its first push.
    Today `history.state.fhPushed` names one key, so a link naming two builds entries where Back
    reopens one, and changing one while on the other's entry erases it there.
  - **The URL parameter is the variable's own**, `v.<root>.popups`, so a load seeds the variable
    as it does every other; the spike kept `ui.popups`, and a refresh left the variable empty with
    the popup shown. `?ui.popups=` links stop working (agreed). The call body's `ui_popups` goes,
    since the session holds the open popup.
  - **A popup outlives its opener**: a set member leaving, or a tab switching away, keeps its
    popup open and the variable holding it, as today.
  `c.view.openPopup` and `openPopupInline` build the calls; `tapMod.OpenSurface`'s click,
  `Selections.popup`, the popup case in `committedSelection`, `ui_popups`/`ui.popups`, the
  shell's `popupFromUrl` and the `/sse/surface/…/open` and `/sse/popup/…/close` routes go; the
  build collects every popup in the tree into the match, as the inline-surface hoist collects
  them today.
- `SurfaceGraph`'s bake groups and `MemberGraph`'s special nesting (`WalkAt.InMember`,
  `innerSetId`, `groupAt`) go into the one path in R4; `SurfaceGraph`'s popup half and the
  inline-surface hoist in R5.

### 8. Markup Scala stops writing

The rule: Scala renders markup Pkl wrote and fills only values it owns. What is left is the set's
root (§5, `Renderer.groupOpen`). What stays in Scala, as the contract: the page shell, the root
`<div data-signals…>` with its seeds, the toast and reload handlers, the error page, and the
default `<main id="dashboard">` chrome. The back-button handler becomes the shell's one popstate
post of the query string (§7).

## Steps

Stacked branches, each its own PR on the one before. The local workspaces outside the repo
(`dashboard-local-dev-server/`, the HA box's) are migrated only when the maintainer asks.

0. **The popup-variable spike**, done 2026-11-15 on the throwaway branch `spike-popup-variable`:
   popups opened and closed by choosing a root `popups` variable through today's swap, from a
   registered popup, a set member's and a tab panel's, with a refusal on a forgotten `conn`.
   What it settled is in §7.
1. **R1, slot classes**, branch `slot-classes` on `var-signal-reads`. **Built.**
   - `NodesSlot` and `AnySlot` beside `Slot`; `CardDef.regions` and `nodes.Region` gone from Pkl;
     the `node.pkl` alias is now `nodeMod`. `children { … }` fills `slotMod.childrenSlot`, implied
     where the template places it; children given to a card that places none is a Pkl error.
   - The one `wireSlots` map (§6) with `Fill.Nodes(Fixed)`; `validate` refuses a `Chosen` fill,
     which nothing places yet. Wire snapshots changed once; goldens byte-identical.
   - Completion on a nodes-slot handle works at expression level only (inside `slots { … }` it
     returns nothing; `docs/pkl-editor-support.md`).
2. **R2, a set is a fill**, branch `set-fill` on `slot-classes`. **Built.** Goldens
   byte-identical; set benches within 1% allocation of R1. `Choice` for nodes is documented in
   ADR 0033, `IndexOfTrue` in 0035. R4 can drop `MemberGraph`'s special case for set components
   in `Member.subtree` and `setsIn`, there only because a nested set's root id is `innerSetId`.
   - `Fill.Set` with members as `Choice[LayoutNode]` and its `of`, `IndexOfTrue`, `.of(set)`, the
     `group` card; `SetNode` goes from Pkl, the wire, `Ready`, `Validate` and `Renderer`, and
     `groupOpen` goes.
   - Goldens byte-identical, area sets (`UseCaseSuite`'s rendered rooms) included. Bench
     `publishSetOrdered`, `publishSetGuarded`, `publishSetNamedGuard`, `leaderRow`, `tickRender`,
     and `validated` timing. Pkl fact: a set beside other content is an error. A test that a
     member whose first clause reads an attribute its entity does not report shows its second.
3. **R3, choices in Pkl.**
   - `nodeMod.iff`/`elif`/`else`, `nodeMod.match` over a variable, `c.iff(…).elif`; Tabs, If, select
     and the slider's controls on it, the three that host beside other content through a `group`.
   - The builders write today's bake fields (`inlineSurfaces`, `bakeInto`/`bakeAs`/`bakeIndex`,
     the baked marker), so the runtime is untouched and no Scala is written for R4 to delete.
     A baked branch is patched into `#<bakeInto>_<bakeAs>` (`Surface.hostId`), never a card's
     root, so each choice keeps today's inner host element (`<div class="fh-ifhost"
     id="{{hostId}}">`); the root hosting it is R4's (agreed 2026-11-16).
   - Ids move: everything in a panel sits one path step deeper, under its `group`. The view tests are the check, and `dashboardGoldensVs` shows nothing but ids moved. A
     URL naming a variable declared inside a panel (`v.<id>.x`) stops matching.
4. **R4, one runtime path.**
   - `Fill.Nodes(Chosen)` replaces the bake fields on the wire, `inlineSurfaces` staying only for
     popups; `nodeMod.match` over a state reading. The four suites building on `{{bakeIndex}}`
     (`LiveStreamSuite`, `RenderInputsSuite`, `RendererSuite`, `ServerHarness`) are rewritten;
     `LiveStreamSuite`'s server-rendered active tab moves to the document form of the tab's
     `cellClassWhen` (agreed 2026-11-10 as `cellClassAtRender`, which `var-signal-reads` folds into
     it).
   - A test of one guard reading a variable and a state, with two viewers on different values.
   - The pick, its planner and its ids (§7); deciding per send; `Activation.Var`, `VarAt`,
     `SurfaceGraph`'s bake groups and `MemberGraph`'s nesting cases go; the shared decisions'
     variable refusals (§4) go. Popups stay on today's swap.
   - A test that a case no viewer shows costs nothing: a state change that only an unchosen tab
     panel or If branch reads renders and sends nothing.
   - The planner as §7 has it, the log down to `fragments`. Tests:
     - a model-based one: random state changes and variable writes over two sessions, each
       session's patches applied to a fake page and compared with a fresh full render;
     - a page returning within the linger, its position ahead of its cursor included, gets only
       the members that changed and no set repaint (`FragmentLogSuite`'s refill assertions are
       rewritten to this);
     - A → B → A sends A whole.
   - A live fill is hosted at its card's root (§2): the inner host elements R3 kept go, the If
     loses a `div` and `.fh-cell:has(>.fh-ifhost:empty)` becomes `.fh-ifhost:empty`.
   - Ids move again; the same normalised comparison, then the goldens are regenerated.
   - Bench everything R2 measured, `publishSetOrdered` also with many sessions, and the size of
     one session's `holds`. Then look at `validated`'s allocation: R2 left it ~6% above R1 on the
     starter (1.56 → 1.66 MB/op), the `group` card's template and each set validated as a card;
     build-time only, so left until the model stops moving.
5. **R5, popups on the pick.**
   - Popups as §7 has them, their taps `SetVar` calls on `popups`: the root's `popups`, its name
     reserved, a popup
     starting with the root's scope (`NodeVariablesSuite`'s "a SURFACE is its own scope root"
     becomes "a popup starts with the root's scope", agreed 2026-11-15), a default of no value
     (`varMod.noValue`, `{}` on the wire),
     `.inHistory()`, the popstate re-read of the URL and the mirror's own-entry marking, and the
     `v.<root>.popups` parameter. Also check why two of the spike's pulls sent nothing (a member
     leaving, a tab switch, both on a session with no stream).
   - Smoke tests (CI) for Back and Forward over a popup, over two history variables, and from a
     link naming both.
   - R4's costs-nothing test for a popup. Bench a popup open and close, and state changes with
     many popups unopened, on the largest test dashboard.
6. **R6, markup.** A test that no Scala source writes markup outside the contract in §8.

Docs go in each step's commit: ADRs 0002 (popups), 0003 (sets), 0005/0025/0033 (the popup
variable, a popup's scope, the reserved name, the history mirror), 0007
(choices), 0008 (the set's root is a card), 0016/0024 (a popup's open and close are `SetVar`
calls, and what a press's body carries),
0011 (the log keeps `fragments`, `holds` each host's keys), 0012 (structure, `bakeIndex`,
deciding per send), 0017, 0020 (theme classes on the root), 0022
(ids), 0034/0035 (`Choice` for nodes, `IndexOfTrue`); the pipeline doc §1a and §3–§4b;
terminology (region, host, member, clause, plus **nodes slot**, **fill**, **pick** and
**group**; bake, bake group, `bakeInto`, `bakeAs` and `bakeIndex` go in R4);
`writing-components.md` (the one host rule, the two slot kinds) and `writing-dashboards.md`
(`elif`, `nodeMod.match`, `.of(set)`); the module `CLAUDE.md` lines on `.fh-group`, the If,
variables and the tap routes. This plan is deleted when the work lands.

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
- **One guard for many members, on the wire.** `members` repeats an equal guard per candidate
  (about 3–8% of a set's JSON in `sets.json` and `starter.json`; the nodes are the rest).
  Candidates could be grouped by an equal guard (`guards: [{by, candidates}]`), at the cost of a
  member no longer being a `Choice`. The query API could make the choice explicit instead: today
  `where((e) -> q.entity(x).stateIs("on"))` reads no member yet lands in every member's guard; a
  condition on the set itself ("show the whole set while …", named other than the keyword
  `when`) would be one guard on the set, and `where` would stay per member. That is also the
  run-time half: decided once at set level, an unchanged answer touches no member, where today a
  change to the entity it names re-decides every one (`publishSetNamedGuard`, about 0.9 µs a
  member). Raise after R4, with a real dashboard's numbers.
- **Stable popup ids and variable URL names.** An inline popup is sugar for one in the root's
  collection, hoisted there by the build, so its id is the build's: its position, which moves when
  a card is inserted above it, and after R5 that id is the `popups` value a link or a held session
  names. Derive it from what the popup shows (a more-info's from its entity, merging equal ones),
  with an opt-in `.named(…)` for the rest. A variable's URL name, `v.<declarer id>.<name>`, moves
  the same way; an opt-in `.urlName(…)`, unique per dashboard, would keep tab links working while
  node ids stay derived.

## Open questions

1. **Names**, free to change while v0: `group` (it shares a word with
   the bake group until R4), `pick`, `IndexOfTrue`, the slot kinds and their base (`AnySlot`),
   `.inHistory()`.
2. **The select's dropdown label over a variable** is a bake group only so the first HTML shows the
   chosen option (`select.pkl:15`). A value slot reading the variable could show it, but it sits
   on a structure card, which refuses a live slot written as bytes; settle it in R3.
3. **Not built until a card needs it:** a cell class whose name an expression computes
   (`cellClassFrom(state.match(…))`, expanded in Pkl to one bool per literal arm), and `Step.Match`
   with typed keys like `Case.is`.
