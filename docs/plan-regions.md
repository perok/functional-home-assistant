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
(`slot-classes`), R2 is built (`set-fill`), R3 is built (`choices-in-pkl`), R4a and R4b are built
(`one-runtime-path`), R4c is built (`root-hosts`), R4d's D0.1 and D0.2 are built (`the-planner`),
the rest not. The opening describes the shape before
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
- `choose`/`each` and the separate `fills` block do not exist; `inlineSurfaces` is popups' alone,
  and the bake fields and `Region.fill` are gone (R4a, R4b).

### 4. Variables in decisions (R4)

- **A guard** is `Choice.Chosen`'s `by` (§3), decided by a pick (built in R4b).

A guard may read a variable and entity state together: a state change (through the reverse
index) or the variable's write wakes it, and it is decided per viewer, a cost only such a guard
pays.

**What `validate` refuses today, for this work to lift.** A variable read (`v.value`,
`v.valueIs`) is allowed only in a leaf's value slots, a bool signal slot as `v.valueIs(x)` and a
window's `by`;
each refusal below is a stopgap, not a rule to keep:
- **Shared decisions** (clauses, a choice over state, `valueWhile`, order keys) and a choice
  reading a variable and state together: decided per viewer once `holds` keeps what each host
  shows (R4d).
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
- The card's own `regions` on the wire is the list of its nodes slots' names, which Pkl builds
  from them (`fill: baked` and `.baked()` went in R4b).
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
  - A session's `holds` keeps, beside each node's inputs and signals, what each host shows, in
    the order sent: the `Ix` of each shown root, which is the key and its case in one int (a
    member's clauses and a pick's cases each have their own). That is one int per member per
    session, bounded by the index, where the log's `mutations` grew until pruned. `holds` is
    keyed by `Ix` and stamped with the renderer it was painted by (D2a).
  - On a pull or a variable write, each touched host's decided keys are compared with its held
    ones, skipped when equal. One planner turns the difference into `Remove`/`Before`/`Append`
    per key, a switch replacing what a key shows, sent back to front so each anchor is on the
    page. The moves are everything outside a longest increasing run of held positions
    (`Patches.reordered`, today's search, run per session rather than once).
  - A key that leaves takes its nodes' entries and its inner hosts' key lists with it, so an
    absent entry always means "not on the page, send it whole". `holds` is trusted only when the
    cursor is at or above `told`, as today.
  - A set's key is its member's root, `<host>_<key>`. Its clauses keep sharing DOM ids:
    `holds` and the render cache tell them apart by `Ix`, so no id changes.
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
- `SurfaceGraph`'s bake groups became picks in R4b; `MemberGraph`'s special nesting
  (`WalkAt.InMember`, `innerSetId`, `groupAt`) goes into the one path in R4c/R4d;
  `SurfaceGraph`'s popup half and the inline-surface hoist in R5.

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
3. **R3, choices in Pkl.** Built.
   - `core/choice.pkl`: `nodeMod.iff`/`elif`/`else`, `nodeMod.match` over a variable (with
     `.otherwise(x)`, Pkl having no default arguments), `c.iff(…).elif`; Tabs, If, the select's
     label and cycle and the slider's controls on it. A chain mixing a variable and state, and a
     match over state, are Pkl errors naming R4.
   - The builders write today's bake fields (a node derives `inlineSurfaces` from its nodes
     slots' choices), so the runtime is untouched. A baked branch is patched into
     `#<bakeInto>_<bakeAs>` (`Surface.hostId`), never a card's root, so each choice keeps its
     card's inner host element; no `group` (agreed 2026-11-16).
   - What moved: Tabs select by value (`"0"`, `"1"`) rather than index, and the select's and
     slider's surface keys are `t<n>`. No node id moved (`dashboardGoldensVs set-fill` passes).
   - `Variable.selects` and `AtIndex` are now used only by a `components.test.pkl` fact; they
     go in R4 with `Activation.Var`.
4. **R4, one runtime path.** Built in four green steps, each its own commit series:
   - **R4a, a choice on the wire.** `Fill.Nodes(Chosen)` replaces the bake fields a choice
     writes (`inlineSurfaces` for choices, `bakeInto`/`bakeAs`/`bakeIndex`, `.baked()` and the
     card's `regions`); the key types move off the window (§3); `IndexOfTrue` decides a chain.
     The runtime still serves a choice through `SurfaceGraph`'s bake group, its members derived
     in Scala from the compiled `Chosen` (one member per case plus the otherwise, active when the
     decided key is its key; case `i` is surface `<host>_t<i>`, the otherwise `<host>_else`). A
     case holding several nodes stays a `Row` in Pkl, so the wire holds one node per case. The
     hand-built bake surfaces (`bakeInto`/`bakeAs`/`bakeIndex`, `Activation.Var`/`State`,
     `VarAt`) stay in the Scala model for the ~20 suites and benches that build them; the four
     suites named below move to `Chosen` fills. Popups keep `inlineSurfaces`. A guard mixing a
     variable and state stays refused. The JSON hoist walks each case of a `Chosen`. Rendered
     bytes do not move: the goldens are the check.
   - **R4b, the pick.** Built. The mechanism changes, the behaviour does not: each choice host's `by` is
     evaluated once and its case looked up, a state-only `by` once per version in `Shared`, a
     variable one per session, replacing the bake group. A variable value no case names and no
     otherwise takes shows nothing, as a set member and a state chain do (today's group falls
     back to its first member; agreed 2026-11-18). The hand-built bake surfaces and their suites
     (~20, `RendererSuite` and `ServerHarness` among them) are rewritten here, once, onto
     `Chosen` fills, and `bakeInto`/`bakeAs`/`bakeIndex`, `Activation.Var`/`State`, `VarAt`,
     `Region.fill`, `.baked()` and `Dashboard.choiceSurfaces` go with the bake group. R4a left
     `validated` on `branches` 6.5% above R3 in allocation (deriving the surfaces) and a state
     group running `by` once per member; the pick removes both. Every variable-read refusal
     (§4: the mixed `by`, set guards, `valueWhile`, order keys) stays until R4d: lifting one
     means re-deciding per session after a state change, which needs the per-session record of
     what each host shows that R4d builds (agreed 2026-11-18).
     What R4b left: a case is still served as a surface (`Dashboard.picks` derives them,
     `lowered` puts them in `surfaces`, `Surface.InPick` places them), so `validate`, the
     scopes and the renderer's surface indexes walk them as before; R4c, which hosts a case at
     its card's root, removes that. `Selections.none` now shows no tab, so a test standing for a
     fresh viewer asks `surfaces.selections(None, vars.env(Map.empty))`, as a page load does
     (`RenderGoldenSuite.fresh`). So that nothing in production reaches "shows nothing", a
     session's values are re-checked once against each new renderer they meet
     (`Renderer.recheck`; agreed 2026-11-18): before, an edit removing a viewer's open tab left
     the value stale, and the group fell back to its first member.
   - **R4c, the root hosts**, branch `root-hosts` on `the-pick`. Built (2026-11-19). Agreed
     2026-11-18, in this order:
     1. `RenderGoldenSuite` becomes one test per fixture, both of its loops over
        `FixtureEntries.all`, before the goldens move: under the full parallel run it sits near
        its 30 s timeout.
     2. A live fill is hosted at its card's root (§2), and the inner host elements R3 kept go.
        Tabs' panel, the slider's controls and the select's cycles (`div`s) and menu label
        (`span`) each become a `group` with `layoutCell = false`, carrying today's tag and
        classes. The If's root hosts its branch (`classes = "fh-ifhost"; template =
        "\(branch)"`), and `.fh-cell:has(>.fh-ifhost:empty)` becomes `.fh-ifhost:empty`. A pick's
        host is its card's node id (`Pick.hostId` goes). The build refuses a live fill that is not
        its card's whole template; Pkl facts for the hosting.
     3. A case is a node, not a surface. Case `i` of a choice in slot `s` has the id a fixed node
        at index `i` of `s` would have, `<host>_<segment(s, i)>`, the otherwise index `n` after
        the cases: a slot holds one fill, so the two never meet, where a `t<i>` marker meets a slot
        named `t0`. Under a `group` that is `<group>_<i>`, so everything in a panel sits one path
        step deeper. Nodes in a case take `childId`. The node index records each node's pick and
        case, which the `s_…__` prefix said before; the duplicate-id check covers authored ids
        in cases. `Surface.InPick`, `lowered`, the build's case surface ids and
        `renderSurfaceTraced` for cases go; the surface indexes keep popups. A pick in a set
        member's clause keeps the clause in the pick's id (`<host>_k<c>`, #556), not in node ids.
        `dashboardGoldensVs the-pick` shows only ids and the goldens' case sections moved before
        the goldens are regenerated. A URL naming a variable declared inside a case (`v.<id>.x`)
        stops matching; no alias (Later, `.urlName(…)`).
     4. The costs-nothing test (below): cases becoming tree nodes is where a walk could start
        rendering one nobody chose.
     5. `nodeMod.match` over a state reading: the pick decides one already. A match reading a
        variable and state together waits for R4d.

     The select's label stays a `nodeMod.match`, in a `span` group: a value slot showing the
     variable would sit on a structure card, which refuses a live slot written as bytes.

     What R4c left:
     - Every golden section's bytes equal R4b's once ids and first-appearance numbering are
       erased; the goldens render every case in a section of its own.
     - The renderer's index walks every case and records each node's container (the innermost
       case, else the popup, else the page); `SurfaceGraph` keys a case by its node id. That is
       the record R4d's `holds` per host builds on.
     - Allocation against R4b (`-XX:-DoEscapeAnalysis`, one round): `publishSetOrdered`,
       `publishSetGuarded` and `publishSetNamedGuard` each +1,440 B/op, one constant, so a
       per-frame cost of the container lookups, not per member; `leaderRow`, `tickRender` and
       `validated` equal within error. Not chased: R4d replaces that planning.
     - Case ids carry no clause, so two clauses of one candidate whose cases each hold an inline
       popup now collide, which the hoist refuses as it does any two clauses' popups. No fixture
       does; R5 moves popups onto the pick.
     - Scala's `validate` refuses a choice on a card placing another region, as it does a set;
       the exact "whole template" comparison is Pkl's (the wire template carries the root).
     - ADRs' "state surface" still name a case by its old word.
   - **R4d, the planner**, branch `the-planner` on `pkl-fixtures` (F, #725). Agreed
     2026-11-19. D0 first, a refactor with no behaviour change, then D1, D2a, D2b, D3, D5, D6 (D4 merged into D3).
     - **D0.1, one tree index.** Today about eight walks mint ids themselves (`Dashboard.picks`,
       `Dashboard.varScopes`, `Validate.walk`/`walkIds`, `Renderer.Index`, `Member.subtree`,
       `MemberGraph`'s nested-set walk, the build's JSON hoist), and R4c added a second spelling,
       `LayoutNode.innerCaseId`. One `TreeIndex`, a `lazy val index` on the wire `Dashboard`,
       numbers every node in pre-order: the page's tree, then each popup's in id order, every
       clause of every candidate and every case included. A node is its number, an opaque `Ix`
       over `Int`. By `Ix`, in arrays, the index holds the node's DOM id, parent, step, tree,
       member context (member, clause, path), container, a case's pick and index, and `end`:
       `[ix, end(ix))` is its subtree, so whether one node is inside another is one comparison
       and a subtree is a range, not a walk. A DOM id names a place, not a node: two clauses of
       one member share theirs, which is what keeps a viewer's choice when the member moves to
       its other clause (`varScopes`). So `ids(id)` answers every `Ix` with that id, more than
       one only under clauses.
       `Ix` belongs to one renderer. What crosses a dashboard swap (`SessionState`, a URL, a
       signal, the log) keeps DOM ids, since a session can meet the new renderer without a
       repaint (`Server.checked`); the opt-in stable names (Later, `.urlName(…)`) are for those,
       never `Ix`.
       `Ready.Compile` walks the index beside the wire tree, minting nothing, and puts each
       node's `Ix` and id on its `Ready.Component`; the render walk reads them. Done when only the
       index (and the JSON hoist, until R5) calls `childId`/`childIdWithin`/`innerSetId`/
       `memberSegment`/`innerCaseId`; `WalkAt.InMember`, `innerSetId` and `groupAt` fold into
       it. The spike's comparison stays as the index's test: its ids are the ones the renderer
       writes, its containers the ones `SurfaceGraph` answers.
       Built. Two visible changes, both in `validate`: its messages name a member's nodes by
       their runtime id (where they said `<set>_<i>`), and an authored id on a set's host is
       accepted, where the old walk gave its members the host's id and refused it as repeated.
       `AuthoredIdSuite` holds the
       authored-id rules.
       Spike (2026-11-20, a throwaway suite): one index built from the wire tree works.
       - One walk of the WIRE tree (`Dashboard.picks`' shape: `childIdWithin`, members by
         `memberSegment`, a popup under its prefix) gave every id the runtime renders (body,
         every popup, every case) and the container `SurfaceGraph.rootOf` answers for every
         node, with no disagreement. Checked on the 12 test dashboards, the starter, and a
         snippet with a set nested in a member and an If in a member.
       - Ids are not unique per node: two clauses of one member mint the same ids for their
         content. Hence `Ix`.
       - The compile is 1:1 with wire nodes: members are compiled per candidate and clauses
         stay a `Vector` of nodes. So `Ready.Compile` can walk the index beside the wire tree
         and put each node's id on its `Ready.Component`. The render walk then reads it, where
         `tracedInto` concatenates every child's id on every render today. That is an
         allocation the D0 bench round should show going.
       - `validate` reads the index, so it is built from trees `validate` will refuse and
         records what it finds (a repeated authored id) rather than failing. That makes it a
         `lazy val index` on the wire `Dashboard`, read by `picks`, `varScopes`, `Validate` and
         `Ready.Compile`.
       - A popup's container is its bare id today (`"detail"`), which an authored node id can
         equal; D0.3 changes that spelling, not any node id.
     - **D0.2, the compiled node mirrors the wire's union.** `Ready.Component.content: Content =
       Fixed(regions) | Live(slot, LiveFill)`, `LiveFill = Pick(by, variable, byKey, cases,
       otherwise) | Set(SetFill)`; `isChoice`, `choices`, `set` and `SetFill.region` go, and
       `Ready.Pick`'s host, slot, prefix, within and case ids move to the index. Built.
     - **D0.3, containers are nodes.** A container is the root node of the page, of a popup's
       content, or of a case, and nothing about it says popup or panel. Inside a renderer it is
       that node's `Ix` (`TreeIndex.container`) and an open set is a `BitSet`. `SurfaceGraph`
       and the hot paths (`Patches.plan`'s `shown`, `visibleNode`, `selectionsIn`) read node
       `Ix`s, the entity reverse index included, so they allocate nothing per id; a `NodeId` is
       spelled only where bytes are written. `containerOfIndexed`, `caseAt` and `surfaceParent`
       become index lookups.
       Where a container crosses a swap (`SessionState.open`, `Mutation.container`, the
       recorder's `openSets`) it is spelled by its root's DOM id, read into `Ix` once per step:
       a popup is `s_detail__c`, not its key `detail`. Under clauses one id reads as one
       `Ix` per clause, which is what `caseAt` means today. One kind of name needs no prefix
       rule: `validate` already refuses an authored id equal to any other id in the index,
       popups' included.
       The wire does not move: `ui_popups`, its URL parameter and the open route keep the
       surface key, read into its root at the route. The popup branches that remain
       (`Selections.popup`, `committedSelection`, the popup host in `surfacesAt`) are what R5
       deletes, so D0.3 does not rewrite them first. Bench round with `-XX:-DoEscapeAnalysis`.
       Built. `Ready.Dashboard` carries the index and every compiled node its `Ix`; the graphs
       read containers off it, so `MemberGraph`'s `rootOfMember`/`rootOfSet`, `Member.root` and
       its case map went too. The visibility walk reads arrays by `Ix`. `selectionsIn`,
       `selectionsShown` and `committedSelections` still read the spelled set: they answer the
       wire's questions, and R5 removes them with `open`.
     - D0's gate: goldens byte-identical, suites green, `page`/`tickRender`/`publishSet*` equal
       or better. Render time and allocation come first; `validated` may pay for a structure
       that is strictly better (D0.1 + D0.2: `page` −5%, `publishSetOrdered` −8%, `validated`
       +0.2% for the index, accepted 2026-11-21). Authoring stays as §2 has it: a fill plus the
       whole-template rule, so an author's own card can be the host.
     - **D1, a model-based oracle.** Built: `OracleSuite`, `testkit/FakePage`,
       `FakeBrowser`. What it holds, whatever D2–D5 change: everything a viewer was sent,
       applied to its page in order, is the page a fresh render gives now. A red is a bug
       report, not a silent fix.
       - The real server, in process: `TestServer.resource`, one per run, so a shrunk run
         replays from the same state. `FakeBrowser` opens every stream through the real route
         (the opening block up to its cursor, a reload followed) and plays the connection loop
         by hand in between: `Server.pull`, then the session's backlog, which holds every
         step's frames in the order they ran.
       - Two viewers. Steps (hedgehog, up to 15; the size grows over the 50 runs, so a run
         averages about 4): an entity changes or leaves (and may come back), a viewer presses a
         choice or a popup its page shows, a stream drops and reconnects with what its page
         carries, every session is forgotten.
       - The fake page: the page GET in jsoup (a test-only dependency), `patch-elements` by
         mode, `patch-signals` merged into a signal map. A seed applies when its element
         arrives or its text changes, since the bundle's morph sets only an attribute that
         differs. A refresh is made at the URL its `data-fh-url` mirrors write; a reconnect
         carries the signals `data-init`'s filter lets through.
       - Equal: once the run settles, each page against a fresh GET at its own URL,
         `#dashboard` and the popup host node for node, every binding painted from the signal
         map (a pending-or-committed read as committed). Left out: the seeds themselves
         (folded into the map), whatever an expression the fake page cannot read paints, a
         binding on a signal some handler assigns (the fake page runs none; `_e.*` and a
         pending ask excepted), a `data-bind` control's `value` and `checked`. A patch with no
         target fails the run.
       - Dashboards: `sets`, `branches`, `taps`, `slider-groups`, `light-controls`, `charts`,
         and one in the suite with a set nested in a member and an If in a member; 50 runs
         each.
       - **Found and fixed: a forgotten page's stale signal.** A page whose session was
         forgotten (an idle tab past its linger, a restart) reconnects to a body repaint, and
         a seed that comes back with the text the element already carries is not applied
         again: a light goes off, every session is forgotten, it comes back on, and the page
         showed it off. `Patches.repaint` now also sends the body's signal values as a frame.
         The property finds it only in some seeds (a run with the fix disabled passed), so
         `OracleSuite` keeps the run that found it as a plain test. Open: a host fill
         (`Inner`) morphs onto members the page already has the same way; whether a live
         session can reach it (a value back at its seed text inside one pull that also fills)
         is not shown either way. D3 rewrites the fill, so check it there.
       - **Also found — fix any time, cheap.** `Server.hasSignals` reads a first connect as a
         reconnect: its `datastar` parameter is never `{}`, since the shell seeds `conn` and
         `ui_popups`, both in `SseInclude`. So every page load logs "reconnect carried a
         signal store with no readable cursor" and resumes from V instead of V + 1, a resend
         at most. Lands in D3, which reads the cursor at reconnect.
       - **Time: 16 s locally, aim 20 s on CI.** It was 28 s: each `charts` run's first chart
         started a GraalJS engine and ECharts (~270 ms), now one per suite through
         `ServerApp.Edges.chart`; and `TestServer.record` took 15.5 ms, `poll` sleeping 5 ms
         before each check, now 1.3 ms. What is left is ~25 ms per run (a boot, two loads, two
         fresh GETs); fewer, longer runs would cut it, kept at 50 × 15 for now.
     - Order agreed 2026-11-21: nothing merges before the whole lands, so no step builds what a
       later one replaces. The `Ix` key is decided now rather than measured in D6, D3 and D4 are
       one step, and nothing popup-specific is written before R5.
     - **D2a, `holds` and the render cache keyed by `Ix`; `holds` stamped.** BUILT (7bbddf2a,
       4b631ce0, 2da72f6f, 7c5464d8). Goldens byte-identical; non-Slow 1429 green.
       - `holds: Map[Ix, Held]`, and `RenderCache` keys by `Ix` + generation + `ByteValues`;
         `ByteValues.clause` is gone. The suites that pinned "a member's clause is in its key"
         pin it on `(Ix, ByteValues)`.
       - `Renderer.generation` is a `Renderer.Stamp` (head and style hashes, popup spellings),
         and `SessionState.painter` is `Unpainted | By(stamp) | Reloading(installed)`. Every
         step (pull, press, opening) compares it first (`Server.swapAnswer`): under another
         renderer a reload when the head moved or to/from the error page, else the repaint,
         never a diff. A session told to reload sends nothing more until the next swap; a press
         on the replaced page is repainted and refused (`DashboardChangedMessage`, ADR 0024). `install` rings the doorbell at the same
         version; `reloadRepaints` and `sameRenderer` are gone.
       - The check-first finding: the new renderer cannot read a popup it lacks (its spelling
         maps to no surface key there), so `orphanedPopup` would never fire. The stamp carries
         the painter's popup spellings, and the repaint reads `open` through it.
       - Bench (B/op, EA off, vs 4a2f793f): `page` −0.1%, `pageSet` −0.2%, `tickRender` −0.4%,
         `resumeMorphs` −0.2%, `resumeMorphsFanout` −0.5%, `publishSet`/`publishSetOrdered`
         equal; times within error.
     - **D2b, `holds` keeps what each host shows**, in the order sent (§7), written and not yet
       read. BUILT (0e9e2f61, c16033d2); two items open. Goldens byte-identical; non-Slow 1430
       green.
       - `Held.shows: Option[IArray[Ix]]` on the host node (one host per node, §2).
       - The structural patches name their host and key, so one value gives the bytes and the
         change to `holds`: `Patch.Fill(host, target, html, shows)` (inner), `Place(host,
         target, html, shown, before)` (before/append), `Leave(host, key: NodeId)` (remove,
         every clause of the key, since a caller cannot say which one the client holds).
         `applied`: a `Fill` drops what is under the host and sets its list; a `Place` inserts
         before its anchor, an anchor the list lacks making it `None`; a `Leave` drops each
         clause's subtree (by `[ix, end)`) and the key. `Patch.Remove` is gone; `Patch.Insert`
         and `invalidates` stay only for `#popups` (R5).
       - The `Trace` walk records each live hole's shown roots, so fills and placed members bring
         their inner hosts' lists.
       - Oracle: after every settle and at the end, each list a session holds is its host's
         children on the fake page, in order. Found: the oracle's runs reach no `Place` (every
         membership change in them fills), so `SetMembershipSuite` places a member between two.
       - Open: the oracle's "every host has a list, equal to a fresh page load's session's" fails
         after a forget, by design: a forgotten page resumes, and its new session's `holds` is
         empty (ADR 0011). Either the oracle exempts a rebuilt session, or D3 fills a host it has
         no list for.
       - Open: bench (B/op, EA off, vs 4457da90) `page` +1.7%, `pageSet` +1.3%, over the 1% gate:
         the lists' own arrays and the merge in `Trace.held`. `tickRender`, `publishSet`,
         `publishSetOrdered` equal; `resumeMorphs` +0.04%, `resumeMorphsFanout` +0.08%; times
         within error.
     - **D3, the planner, and the log down to `fragments`** (§7), one step: the planner makes
       the replay dead, so it goes here. Per session from the start (`Shared` decisions and the
       viewer's), and a variable write goes through it too, so a tab stops using `hostFill`.
       Goes: `MemberGraph.deltas`, the set refill, `mutations`, `horizon`, `MemberKey`,
       `NodeAncestry` and `MemberGraph.parentEdges` (containment is the index's range); the
       log keys by `Ix` (check first: Origin's doc says a write rotates the log, `TreeIndex`'s
       that the log outlives a swap). An A → B → A test sends A whole; `FragmentLogSuite`'s
       refill assertions become "a returning page, its position ahead of its cursor included,
       gets only the members that changed, no set repaint". D1's two open items land here: the
       host fill's seed skip, and `hasSignals` reading a first connect as a reconnect.
     - **D5, per-session decisions:** §4's variable-read refusals go (the mixed `by`, set guards,
       `valueWhile`, order keys), with a test of one guard reading a variable and a state, two
       viewers on different values. It adds deciders to D3's per-session path, no reshape.
     - **D6, benches** that span steps: everything R2 measured, `publishSetOrdered` also with
       many sessions, one session's `holds` size; then `validated`'s allocation (R2 left it ~6%
       above R1 on the starter, 1.56 → 1.66 MB/op). Each step keeps its own bench gate.
     - Docs in each step's commit. Folded in where cheap: the ADRs' "state surface" wording.
5. **R5, popups on the pick.**
   - **`#popups` is an ordinary node:** the build adds a `group` (`core/group.pkl`) with the
     authored id `popups`, holding the root's `nodeMod.match(popups, …)`; the chrome places it
     through a hole instead of writing `id="popups"` (ADR 0002, 0020: the chrome warning becomes
     a template check). It is its own root in the index, outside `#dashboard`, its scope
     starting at the page root. Goes: `PopupHostId` as a special case, `swapHost`'s popup branch,
     repaint's popup case, `Patch.Insert`, `invalidates`; the oracle's list check covers it.
   - **A popup's value is a name**, since every open becomes a history entry and an edit that
     moves a card must not break Back into its popup. The Pkl library derives it (a more-info's
     from its entity; equal popups merge) or the author names it (`.named(…)`), and the wire
     carries only the one generic name, whatever derived it. Settle first: two different
     more-info contents for one entity.
   - Popups as §7 has them, their taps `SetVar` calls on `popups`: the root's `popups`, its name
     reserved, a popup
     starting with the root's scope (`NodeVariablesSuite`'s "a SURFACE is its own scope root"
     becomes "a popup starts with the root's scope", agreed 2026-11-15), a default of no value
     (`varMod.noValue`, `{}` on the wire),
     `.inHistory()`, the popstate re-read of the URL and the mirror's own-entry marking, and the
     `v.<root>.popups` parameter. Also check why two of the spike's pulls sent nothing (a member
     leaving, a tab switch, both on a session with no stream).
   - **`SessionState.open` goes.** With the popup a variable, every container a viewer shows
     follows from its variables and the state, so each step computes D0.3's `BitSet` from them.
     Nothing spelled as a container then crosses a swap, only `VarKey`s, which `recheck` already
     checks. Check first: the recorder reads `openSets` outside any step, `Sessions.reveal` widens
     the open set before a fill, and `selectionsShown` gives a pick in a member's clause the
     variable's value only once that clause is shown.
   - **`defaultOpen`** becomes the `popups` default: that popup's id, not no value. Today
     `selectedSurfaces` adds every `defaultOpen` popup while `#popups` holds one, so what two of
     them mean is settled before this is built.
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
- **Variable URL names.** A variable's URL name, `v.<declarer id>.<name>`, moves when its
  declarer does (popup values are R5's). Check before adding `.urlName(…)`: the declarer is
  spelled by its node id, so an authored id on it already makes the URL position-free, and one
  name could be the DOM id, the URL prefix and a popup's name.

## Open questions

1. **Names**, free to change while v0: `group`, `pick`, `IndexOfTrue`, the slot kinds and their base (`AnySlot`),
   `.inHistory()`.
2. **Not built until a card needs it:** a cell class whose name an expression computes
   (`cellClassFrom(state.match(…))`, expanded in Pkl to one bool per literal arm), and `Step.Match`
   with typed keys like `Case.is`.
