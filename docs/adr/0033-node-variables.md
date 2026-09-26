# ADR 0033 — A node variable: declared by a node, read by name, chosen per viewer

A node **declares** a named value, a descendant's query **reads** it by name, and a viewer's
choice **writes** it for that viewer's session. Resolution is up the ancestor chain, at build time.
Issue [#209](https://github.com/perok/functional-home-assistant/issues/209); it replaces the
`settable` draft of [#210](https://github.com/perok/functional-home-assistant/issues/210).

The first user is a chart's window: `c.windowChooser` declares `window`, and every
`c.historyChart(s).chosen()` beneath it reads it, so pressing `7d` redraws them all.

## Context

The window is a per-viewer choice the SERVER must know before it renders: the first HTML is
complete (architecture §0), so a chart cannot be a hole filled after load. The value has to reach
`queriesForPage` before the walk starts.

The property worth keeping is the one architecture §6's barrier leans on: `QuerySnapshot` is total
over the queries a render reads, which is only expressible while that set is **known before the
walk**. A reference matched by string convention in the browser, or buried in a CEL string, could
not be enumerated there. A declared reference can.

## Decision

**Declaration.** `LayoutNode.Component.vars: Map[String, String]` — a name and the value it holds
before anyone chooses. In Pkl, `vars { ["window"] = "24h" }`. Nothing else: no type, no list of
allowed values (below).

**Reference.** A query parameter is a `Ref` — `Literal(value)` or `Var(name)`. On the wire a bare
string is a literal and `{"var": "window"}` a reference, the rule `SlotSource`'s decoder already
used, so slots that do not use this did not move. `QueryTemplate` is the authored query,
references unresolved; `SlotAsk` is a slot's ask as the TREE states it; `SlotRead` is that ask
resolved for one render, and is still what every cache keys on.

**Resolution** is one walk at validate time carrying a scope stack. A `Var` resolves to the nearest
declaring ancestor; a container that declares nothing is transparent; a nested declaration of the
same name shadows. No declarer is a **build error** naming the node and the variable. The walk
yields the declared edge and its inverse, `Renderer.readersOf` — the exact set a write re-renders.

- **A surface is its own scope root.** A baked surface can be swapped into a host, and inheriting
  from wherever it is shown would let one content resolve differently per host.
- **A variable read inside a candidate set is refused**, because a member's id is minted at run
  time and has no scope entry. A plain query inside a set still works. A test holds this, so
  lifting it is deliberate.

**The value is per session, addressed to the DECLARER** — `Map[(NodeId, String), String]`. Keying
by declarer is what makes a shadow safe from the write side: choosing on an outer panel cannot
move a chart that declares its own. The declared value fills everything not chosen, so the
environment is total over declarations and no reader handles a missing one. It travels INSIDE
`QuerySnapshot`, so the answers and the values they were fetched for are one value, and a lookup
against a different environment is not representable.

**It arrives two ways and passes one check.** `POST /sse/var/:slug/:declarer/:name/:value` while
the page is live, and `v.<declarer>.<name>` on the page URL, which survives a refresh and is
recorded on the session because a pull has no request to read it off again. Both go through
`Renderer.refusals`: every declared reader must still parse what it would then ask, and read only
an entity the dashboard names or one of its queries names at its declared values — ADR 0023's
bound on the read side, because a `Ref` is legal on any parameter and a variable fed to `entity`
would otherwise chart the lock from a `Public` dashboard. A refused write is ADR 0024's 200 of
signals; a refused URL is a 400 before any session exists. A choice naming no declaration is
inert, not an error — a stale link after a rename, treated as `SurfaceGraph.openPopup` treats a
gone surface. `v.` is its own prefix rather than `ui.`: a `ui.` entry is a bake branch that
`SurfaceGraph` narrows, a variable is a value its reader narrows.

**A write re-renders the readers this viewer is shown, then commits** (ADR 0025): the press writes
`_var_<declarer>__<name>__pending`, the server commits `_var_<declarer>__<name>` after the repaints,
and agreement ends the ask. The committed values are seeded by the document's shell and ride the
opening frame again, both total over declarations at this viewer's values, so a forgotten session
corrects a stale control and a control never shows the declared value over a linked choice.

**The control renders its own bar.** A button must name the declaring node in its route and its
signal, and a template can only spell its own id, so `c.windowChooser` is one component that
declares `window` and renders the four `Window` values from one `Listing<Window>`. Neither it nor
`chosen()` takes the variable's name, so the pair cannot drift.

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

**Resolving by node id.** Authors do not know ids — they are position-derived (ADR 0022), which is
why `@@NODE_ID@@` exists at all.

**Letting CEL read variables.** A transform is opaque to static analysis, so a reference inside one
could not be enumerated before the walk — the property this exists to keep.

**Ordering the walk.** #209 predicted a declared edge would need topological ordering. It does not:
a value is ambient session state, in hand before the walk, never computed by it.

## Consequences

- **The render key gained nothing.** Only a query parameter can read a variable, and two viewers on
  different windows already produce different `SlotRead`s, which the render key and both caches
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
- **A node that spells its own id from a CHILD.** `@@NODE_ID@@` means "the id of the node whose
  class wrote this token", but `DashboardBuild.hoistInlineSurfaces` splices it only for a node
  carrying `inlineSurfaces`. It cannot become unconditional — a `TabButton` inside `Tabs` writes it
  meaning the tabs' id — so it needs an explicit "I own the tokens in my subtree" marker. That is
  what a generic chooser over child buttons (sketched in `core/variable.pkl`) waits on.
- **Declaring on the tree ROOT, so a control need not contain its readers.** Today a bar in a
  header cannot steer charts in a sibling column. A root declaration would, addressed through a
  reserved segment the server resolves to "this tree's root" (the root is `c` only until an author
  names it, and `s_<sid>__c` in a surface). Not built: composition covers the case that exists.
- **A global namespace**, if the root declaration ever reads badly, is Pkl sugar over it — never a
  second resolution rule.
- **Whether the bake selection becomes a variable.** Same kind of fact — session state, committed
  by the server, mirrored to the URL — and "it is client state" is the plausible wrong reason to
  leave it. The real one is blast radius: ADRs 0005, 0007 and 0025 and the tab bar, for no new
  capability. Revisit once node variables have more than one user.
