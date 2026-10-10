# ADR 0033 — A node variable: declared by a node, read by name, chosen per viewer

A node **declares** a named value, a descendant's query **reads** it by name, and a viewer's
choice **writes** it for that viewer's session. Resolution is up the ancestor chain, at build time.
Issue [#209](https://github.com/perok/functional-home-assistant/issues/209); it replaces the
`settable` draft of [#210](https://github.com/perok/functional-home-assistant/issues/210).

Two users: a chart's window — `c.windowChooser` declares `window`, and every
`c.historyChart(s).chosen()` beneath it reads it, so pressing `7d` redraws them all — and a tab
bar's open tab, which `c.tabs` declares as `tab` and its panel's bake group is selected by.

## Context

The window is a per-viewer choice the SERVER must know before it renders: the first HTML is
complete (architecture §0), so a chart cannot be a hole filled after load. The value has to reach
`queriesForPage` before the walk starts.

The property worth keeping is the one architecture §6's barrier leans on: `QuerySnapshot` is total
over the queries a render reads, which is only expressible while that set is **known before the
walk**. A reference matched by string convention in the browser, or buried in a CEL string, could
not be enumerated there. A declared reference can.

## Decision

### The rules, in one place

1. A node declares variables: a name and the value it holds before anyone chooses.
2. A read resolves by name to the nearest node above it declaring that name, once, at build time.
3. A node that declares nothing is transparent.
4. A nested declaration of the same name shadows the outer one, for its own subtree only.
5. A node sees its own declarations.
6. The page and each popup start with an empty scope. A tab panel or an `If` branch starts with
   the scope of the one node it is baked into.
7. A candidate set's members read the scope at the set. A node inside a clause cannot declare.
8. A read with no declarer is a build error naming the node and the variable.
9. A value is per viewer, kept on the session and addressed by declarer and name, so a write to
   an inner declaration cannot move a reader of an outer one.
10. Only a viewer's choice writes a value — `POST /sse/var`, a `v.` link, or what a reconnect
    carries — and every reader must accept it, or the write is refused.

Rules 2–6 are lexical scoping, as in Scheme, ML or Pkl itself: where a node stands in the tree
decides, never where it is shown. Rule 6 is what keeps that true for surfaces — a popup can be
opened from many places, so inheriting from its opener would be dynamic scoping.

**Declaration.** `LayoutNode.Component.vars: Map[String, String]` — a name and the value it holds
before anyone chooses. In Pkl, `vars = new Mapping { ["window"] = "24h" }` — assigned, because a null-defaulted
`Mapping?` cannot be amended (ADR 0034). Nothing else: no type, no list of
allowed values (below).

**Reference.** A query parameter is a `Ref` — `Literal(value)` or `Var(name)`. On the wire a bare
string is a literal and `{"var": "window"}` a reference, the rule `SlotSource`'s decoder already
used, so slots that do not use this did not move. `QueryTemplate` is the authored query,
references unresolved; `SlotAsk` is a slot's ask as the TREE states it; `SlotRead` is that ask
resolved for one render, and is still what every cache keys on.

**Resolution** is one walk at validate time carrying a scope stack. A `Var` resolves to the nearest
declaring ancestor; a container that declares nothing is transparent; a nested declaration of the
same name shadows. No declarer is a **build error** naming the node and the variable. The walk
yields the declared edge and its inverse, `VarGraph.readersOf` — the exact set a write re-renders.

- **A popup is its own scope root; an owned surface inherits.** A popup can be opened from many
  places, and inheriting from one would let its content resolve differently per opener. A tab
  panel or an `If` branch is baked into exactly one node (`bakeInto`), so it starts with that
  node's scope (`Dashboard.varScopes`, which the renderer and validation read; the hoist applies
  the same rule to tokens). A chooser above a tab bar reaches the charts in its panels, and a
  nested bar's own declaration shadows the outer one. A write still re-renders only the readers
  this viewer is shown, so a hidden panel's chart is fetched at the new value when it opens.
- **A candidate set's members read the scope at the set.** The candidates and each member's id
  (`LayoutNode.memberSegment`: set id plus entity id) are fixed at build time, and a member's
  children render under the member's id, so a member is an ordinary reader: `VarGraph` gives each the
  scope of the indexed set above it, a set's reads are resolved per viewer for
  every clause (the snapshot is built before the walk picks one), and a write re-renders the
  present members this viewer is shown. A member's render key carries its whole subtree's reads,
  or a chart nested in a clause would keep its old window. A declaration inside a clause is
  refused: it would be every member's own choice, which nothing needs.

**Naming the declarer.** A node below a declaration sometimes needs the declarer's ID, not its
value: a button posting to it, or a highlight reading its committed signal. `Variable.declarer`
is a token, `@@VAR:<name>@@`, that the hoist splices by the same rule (nearest declarer, a popup
its own root, none is a build error naming the node). It is spliced into each node's OWN fields with
the scope at that node, never across a subtree as `@@NODE_ID@@` is, or a shadow would hand its
children the outer declarer. A candidate set's clause may use one to name a declarer above the set.
It is a build token rather than something the renderer resolves because
a route or a signal name is a string inside a slot, which the renderer never parses; the check for
unresolved tokens stays behind it, so a missed splice fails the build instead of reaching the DOM.

**One typed value on each side, never a bare name.** A component holds a variable as a
`varMod.Variable`: its `ref` for a query parameter, `declarer` for a node below, its signals and URL
param spelled from the declarer's own template (`own`) or from below it (`below`), the `selects`
activation of a panel, `declare(value)` for the node's `vars`, and `choose(value)` for a guarded
tap. So a component writes the name once, and a misspelt signal cannot exist. The server's half
is `VarKey(declarer, name)`, which owns the same spellings where the server commits, seeds,
adopts a reconnect's values and reads a link, and `VarGraph`, beside `SurfaceGraph` and
`MemberGraph`, owns scopes (set members included), a viewer's environment, readers and the panels
a write selects. `VarSignalNamesSuite` reads a real page's names and holds the two halves equal.

**The value is per session, addressed to the DECLARER** — `Map[(NodeId, String), String]`. Keying
by declarer is what makes a shadow safe from the write side: choosing on an outer panel cannot
move a chart that declares its own. The declared value fills everything not chosen, so the
environment is total over declarations and no reader handles a missing one. It travels INSIDE
`QuerySnapshot`, so the answers and the values they were fetched for are one value, and a lookup
against a different environment is not representable.

**It arrives three ways and passes one check.** `POST /sse/var/:slug/:declarer/:name/:value` while
the page is live, and `v.<declarer>.<name>` on the page URL, which survives a refresh and is
recorded on the session because a pull has no request to read it off again. The third is a
reconnect: the SSE GET carries the committed values (not their pending asks), and a session this
process forgot (a restart, a reap) adopts them, where it would otherwise reset every bar to its
declared value. Each is adopted on its own, so one stale value costs only its own variable. A live
session's own choices win over them, since a commit can be lost with its stream. They are read by exact signal name per declaration (`Server.carriedVars`), never by
parsing `_var_<declarer>__<name>`: a declarer's id can itself contain `__` (`s_<sid>__c`). All go through
`Renderer.refusals`: every declared reader must still parse what it would then ask, and read only
an entity the dashboard names or one of its queries names at its declared values — ADR 0023's
bound on the read side, because a `Ref` is legal on any parameter and a variable fed to `entity`
would otherwise chart the lock from a `Public` dashboard. A refused write is ADR 0024's 200 of
signals; a refused URL is a 400 before any session exists; a refused carry is dropped with a
warning, leaving the declared values. A choice naming no declaration is
inert, not an error — a stale link after a rename, treated as `SurfaceGraph.openPopup` treats a
gone surface, and an old `ui.<id>` tab link is not read at all. `ui.` is now only the popup's.

**A write re-renders the readers this viewer is shown, then commits** (ADR 0025): the press writes
`_var_<declarer>__<name>__pending`, the server commits `_var_<declarer>__<name>` after the repaints,
and agreement ends the ask. The committed values are seeded by the document's shell and ride the
opening frame again, both total over declarations at this viewer's values, so a stale control is
corrected and a control never shows the declared value over a linked choice.

**The control is a node per value.** `c.windowChooser` declares `window` and builds its bar from
one `Listing<Window>`, each value a `tab` node whose tap is `Variable.choose`: a guarded `Click`
naming the declarer by token in its route and its pending signal. Its name and value are typed as
plain tokens, since they travel in a route segment and a JS string. A button per node is what lets
each have its own busy signal (ADR 0019). Neither the chooser nor `chosen()` takes the variable's
name, so the pair cannot drift.

**A tab bar is a variable.** `c.tabs` declares `tab`, the open member's index (`"0"`), and its
panel's surfaces are `Activation.Var("tab")`: the member whose `bakeIndex` is the variable as seen
from the bar. Its buttons are `choose(i)` on that variable, so a tab press is a variable write whose
reader is the bake group (`VarGraph.panelsSelectedBy`): the write swaps the panel this viewer is
shown and commits after it, and a value that is not a member index of every panel it selects is
refused like an unparseable window. A tab panel cannot be opened directly; the open route refuses
it, since the panel would move without the variable. `validate` requires the variable in scope at
the bar and its declared value to be a member index, and refuses a `User`-activated member with
a `bakeInto`: a host is filled by a choice the server knows, a variable or a condition (ADR 0007),
and `User` is a popup's. Several bars, and a bar nested in another's panel, are kept apart by
being different declarers. Every path that bakes takes a bar's member from the session's
variables, so a stale `ui.<id>` param cannot pick a tab.

A highlight reads the committed `_var_<declarer>__<name>`, which the server writes for every
declaration whoever reads it. So a SIGNAL reader needs no slot kind of its own: it is a name the
token supplies, and a press re-renders the variable's readers and never the bar.

## Why not

**A list of allowed values on the declaration.** Built twice and removed. It bought a generic
refusal at the write — redundant, since the write already asks every reader, and the reader is
the authority a list could only copy — and a control derivable from the declaration, which is real
and belongs in **Pkl**, where one function emits the declaration and its buttons from one list. On
the wire it is a second place for "what is legal" to live, and five buttons against a four-value
list is a dead button the field introduced. It was also a half type system: a `List[String]`
cannot say "a number in a range" or "an entity id".

**Totality in the build.** The first cut enumerated each variable's values so the build's parsed
request map would hold whatever a viewer later picked, which forced every author to list values
they might not have. A value is untrusted input per session; the build now parses the DECLARED
values, which it does decide, and the write refuses what would not parse. `Validated.queries` is a
memo, not a totality proof — a read it has not seen is parsed on the spot.

**Doing it in the browser.** A client signal and a POST for a patched chart: no declaration, no
render-key change. It fails on the first paint — the default wins on every refresh, or the page
ships a hole — and it is the second mechanism #210's draft was rejected for.

**Making the window a bake group.** Zero new machinery, since tab bars ship. But a control over
three charts is twelve surfaces with each chart written four times, and one window cannot steer
charts outside its panel.

**Letting the author name a tab bar's variable.** It would let a button elsewhere in a panel switch
tabs, which the declarer token makes possible. Fixed, as the chooser's `window` is, the
declaration and its writers cannot drift; naming it can come when a control needs it.

**A tab's label as the value.** It would survive reordering the tabs, but a label can carry a
space or a `'`, and the value travels in a route segment and a JS string. The index is what
`bakeIndex` already says.

**Translating old `ui.<id>` tab links.** Cheap, but a second spelling to keep for as long as any
link exists. An old link lands on the declared tab, which is what a stale `v.` link does.

**Resolving by node id.** Authors do not know ids — they are position-derived (ADR 0022), which is
why `@@NODE_ID@@` exists at all.

**Letting CEL read variables.** A transform is opaque to static analysis, so a reference inside one
could not be enumerated before the walk — the property this exists to keep.

**Ordering the walk.** #209 predicted a declared edge would need topological ordering. It does not:
a value is ambient session state, in hand before the walk, never computed by it.

## Consequences

- **The render key gained nothing.** A query parameter reads a variable as a `SlotRead`, and a
  bake group's selection is structure, which is never cached. Two viewers on different windows
  already produce different `SlotRead`s, which the render key and both caches
  distinguish. Measured: two windows over one sensor are unordered in `RenderCache`, so
  alternating viewers re-render each time — a mustache splice of SVG already drawn, since
  `renderNodeById` takes a `QuerySnapshot` and cannot fetch or draw. No bucketing; reopen only if
  a profile puts a chart node's paint somewhere it shows.
- **A write is an update, not a first paint**, so a new chart may land after the press; ADR 0025's
  pending value keeps the press instant meanwhile.
- **"vars" is taken twice already** — a card's mustache context in `Renderer`, CSS custom properties
  in `theme.pkl`. The field is `vars`, but prose and types say *node variable*
  (`docs/terminology.md`).

## Future work, recorded so it is not rediscovered

- **A variable with NO value — "nothing selected yet".** A real UI state, not built because no
  reader can use it: `history`'s parameters are all required. A provider with an optional
  parameter is what would make it worth building. **Null cannot spell it** — `omitNullProperties`
  drops a null Mapping entry, so `["compare"] = null` declares nothing (ADR 0006). Spell it as the
  bare-string-or-object rule already does: `"24h"` a value, `{}` unset. `""` is a different
  question; `core/stage.pkl` already keeps absent and empty apart.
- **A type, if a variable ever needs one, must be a real one** — a named `ValueType` covering more
  than string enums, with one answer for where it is checked and one for how a control is derived.
  Until then the closed set lives in the Pkl that emits the declaration and its control.
- **A child that spells a parent's id OUTSIDE a variable.** `@@NODE_ID@@` means "the id of the
  node whose class wrote this token", and `DashboardBuild.hoistInlineSurfaces` splices it only for a
  node carrying `inlineSurfaces`. It cannot become unconditional — a `TabButton` inside `Tabs`
  writes it meaning the tabs' id. A child naming its DECLARER is `Variable.declarer`; an explicit "I
  own the tokens in my subtree" marker is wanted only by a parent that declares nothing.
- **A plain-slot reader** — a slot whose rendered value is a variable, re-rendered on a write. No
  component needs one. When one does, it is a slot shape and nothing on the write path: the write
  already re-renders whatever `readersOf` returns.
- **Declaring on the tree ROOT, so a control need not contain its readers.** Today a bar in a
  header cannot steer charts in a sibling column. A root declaration would, addressed through a
  reserved segment the server resolves to "this tree's root" (the root is `c` only until an author
  names it, and `s_<sid>__c` in a surface). Not built: composition covers the case that exists. It
  is also the last step for selections: the popup host lives in `theme.chrome`, outside every
  node, so only a root declaration lets it become a variable and retire `ui_popups`,
  `Server.popupOf` and `Selections.popup`, the one selection a request still carries (ADR 0005).
- **A global namespace**, if the root declaration ever reads badly, is Pkl sugar over it — never a
  second resolution rule.
