# Plan — node variables: declare a selection, read it by name

Issue [#209](https://github.com/perok/functional-home-assistant/issues/209). Closes
[#210](https://github.com/perok/functional-home-assistant/issues/210) rather than doing it.

A node **declares** a variable, a descendant **reads** it, an action **writes** it, and resolution
is by NAME up the ancestor chain. The first real reader is a chart's `window`: a control in the
more-info popup that moves every chart beneath it from 24h to 7d.

## Why this and not the obvious thing

The window is a per-viewer choice that the SERVER must know before it renders, because
architecture §0 says the first HTML is complete — a chart cannot be an empty hole with a fetch
behind it. So the value has to reach `queriesForPage` before the walk starts. Two cheaper answers
were considered and priced in "Alternatives" below; both fail on that sentence.

There is also a property worth having for its own sake, and it is the one the architecture doc
already leans on: **a declared reference is what keeps a render's input set statically
enumerable.** `QuerySnapshot` is total over the queries a render reads, which is only expressible
while that set is known before the walk. A reference matched by string convention in the browser
cannot be known there. §6's barrier-precondition block names this issue as what protects it.

## The one thing the issue predicted that turns out not to be needed

The issue's third comment says a declared edge "can be topologically ordered before the walk".
**No ordering is required.** A variable's value never comes from the walk — it is ambient session
state, written by an action and read by every render afterwards. The barrier holds because the
value is already in hand, not because anything was sequenced. The declaration earns its place for
the other two reasons (an enumerable input set, and a build error instead of a token in the DOM),
and the plan should not build machinery for a hazard that does not arise.

## The shape

### Declaration

```scala
// LayoutNode.Component.vars: Map[String, String]  — name -> the value it holds
//                                                    before anybody chooses
```

In Pkl, written directly, with no wrapper to learn: `vars { ["window"] = "24h" }`. A declaration
is a name and a value and nothing else — see decision 2 for why there is no set of allowed values
beside it.

### Reference

A query param stops being a `String`:

```scala
enum Ref:
  case Literal(value: String)
  case Var(name: String)
```

Wire spelling reuses the rule `SlotSource`'s decoder already carries — **a bare string is a
literal**, an object (`{"var": "window"}`) is a reference. No discriminator, so no churn through
`PklBuildSuite`'s byte-identity snapshots for the slots that do not use this.

**This is where the permission question dissolves.** The dropped draft needed an opt-in
`settable: Listing<String>` so a control could move a chart's `window` but not its `entity` —
substituting the entity is "chart a sensor this dashboard never showed", an access question
(ADR 0023). With references, `["entity"] = "sensor.t"` is a `Ref.Literal` and **there is nowhere
for a write to land**. Only a declared variable is writable. Unspellable, not rejected.

### Resolution

One walk at validate time carrying a scope stack of declarations. Each `Ref.Var` resolves to the
nearest declaring ancestor; an intermediate container that declares nothing is transparent; a
nested declaration of the same name shadows. No declarer → **build error naming the node and the
variable**, where today an unresolved `@@NODE_ID@@` renders literally into the DOM.

It produces two maps, and the second is the whole payoff:

- `(readerNodeId, varName) -> declarerNodeId` — the declared edge;
- its inverse, `declarerNodeId -> readers`, which is the **exact invalidation set for a write**.

### Value

Per session, keyed by declarer so a shadow is a different key:

```scala
vars: Map[(NodeId, String), String]
```

Untrusted. A choice naming a variable nothing declares matches no scope and is simply never read
— inert rather than an error, the shape `SurfaceGraph.openPopup` already uses for a surface id
this dashboard no longer has. The declared value fills everything a viewer has not chosen, so the
environment is total over declarations by construction and no reader handles a missing one.

### What changes on the query path

`SlotRead` **does not move**. It stays the resolved key — a query plus its stage — which is what
`RenderInputs` carries and what the two caches key on. What becomes late-bound is how you get one:
`SlotShape.Query` carries a TEMPLATE (provider + `Map[String, Ref]` + stage), and
`queriesForPage`/`queriesForSurface` resolve each template against this session's bindings into a
concrete `SlotRead` before the barrier fires.

So two viewers on different windows produce different `SlotRead`s, which the render key already
distinguishes and the fetch cache already separates. **The query reader kind needs no new
dimension anywhere.**

### The three reader kinds, and only one of them is expensive

ADR 0017's split stays: a variable unifies where the NAME comes from, not how a reader consumes
it.

| kind | what a write does | cost |
|---|---|---|
| signal slot (a tab highlight, a button's active class) | push a value, no re-render | one frame |
| plain slot (text that mentions the value) | re-render that node | one node |
| **query slot** (the chart) | **re-resolve the query, then re-render** | a fetch, then a drawing |

The third is new and is compatible with §0 because a write is an UPDATE, not a first paint: the
new chart may land after the press, with ADR 0025's pending/committed keeping the press instant
while the fetch is in flight.

### Where the render key goes

- **Query readers:** nothing. Covered above.
- **Plain slot readers:** their bytes mention the value, so `RenderInputs` must carry the
  variables that node reads — `Map[(NodeId, String), String]`, and different values are
  **unordered** rather than one being behind the other, exactly as differently-shaped query maps
  already are.
- **Signal slot readers:** nothing, by the same rule that keeps a signal slot's entity out of the
  key — its value is not in the patch form.

That third map is the selection dimension the issue warned about. Phase 0 priced it: two viewers
on unordered keys evict each other, one render per pull each, never wrong bytes — and the render
they pay for is a mustache splice, not a fetch or a drawing. No bucketing.

## Decided

1. **Scoped, and a global namespace needs no second mechanism.** A "global" variable IS a scoped
   one declared on the ROOT node — every node is its descendant, so every reference resolves to
   it. If the flat spelling is ever wanted it is Pkl sugar that writes a root declaration, not a
   second resolution rule. Scoped is strictly the more general of the two, so there is nothing to
   opt into later and nothing given up by starting here.
2. **A declaration is a NAME and a value** — `vars { ["window"] = "24h" }` — and carries no set of
   allowed values. This went the other way twice before landing here, so the reasoning is worth
   keeping. A `domain: List[String]` bought two things: a generic refusal at the write, and a
   control that could be built from the declaration. The first is redundant — the write already
   refuses a value no declared reader can parse, and the reader is the authority a domain could
   only copy. The second is real and belongs in **Pkl**, where one function emits the declaration
   and the buttons from one list so they cannot drift; on the wire it is a second place for "what
   is legal" to live, and five buttons against a four-value domain is one silently dead button
   that the field itself introduced. It was also a half type system — a `List[String]` that
   cannot say "a number in this range" or "an entity id" — which is the shape to avoid rather
   than to extend. See "Future work" for what a real one would have to be.
3. **The bake selection does not become a variable** — see "What this does not do".

## Future work — these belong in the ADR phase 5 writes, not in this stack

Five things that are deliberately out of scope here and should be recorded where a later reader
finds them, rather than rediscovered.

- **A variable declared with NO value — "nothing selected yet".** A real UI state (an unapplied
  filter, a comparison entity not yet picked), and deliberately not built, because no reader can
  use it today: `history` takes `entity` and `window` and both are required, so an absent one is
  a build error and nothing else. The thing that WOULD make it worth building is a provider with
  an optional parameter, where absence means "your default, not one I restated".

  Two findings to save whoever picks it up. **Null cannot spell it**: the JSON renderer's
  `omitNullProperties` drops a null Mapping ENTRY, so `["compare"] = null` declares nothing at all
  and every reference to it becomes "no ancestor declares" (measured; ADR 0006 carries it). The
  spelling that works is the bare-string-or-object rule `SlotSource` and `Ref` already use —
  `"24h"` for a value, `{}` for unset. And `""` is not the same question: `core/stage.pkl` already
  distinguishes absent from empty for exactly this reason, so do not fold them.

- **If a variable ever needs a TYPE, it needs a real one — and it must be named as such.** The
  temptation is a `domain: List[String]` beside the declaration, which was built and then removed
  here: it is an enum spelled as data, and it cannot say the other things a variable might be — a
  number in a range, a boolean, an entity id, a duration. A half type system is worse than none,
  because it looks extensible and is not, and because it duplicates what the reader already
  decides.

  So the bar for revisiting: a named concept (a `ValueType`, not a "domain") that covers more than
  string enums, with one answer for where it is checked and one for how a control is derived from
  it. Until then the closed set lives in the **Pkl** that emits both the declaration and its
  control — which is composition doing the job a wire field was doing badly, and which needs
  nothing from the model.
- **A node that can spell its own id from a CHILD.** `@@NODE_ID@@` already means "the id of the
  node whose class wrote this token", and `DashboardBuild.hoistInlineSurfaces` already splices it
  — but only for a node that carries `inlineSurfaces`, which is an accident of where the pass
  lives rather than a decision. It cannot simply become unconditional: a `TabButton` nested inside
  a `Tabs` writes the token meaning the TABS' id, so making every node a splice point would
  resolve it to the button's. What it needs is an explicit marker meaning "I own the tokens in my
  subtree". Worth doing when a second component wants it; until then `c.windowChooser` renders its
  own bar and the generic chooser in `core/variable.pkl`'s docs stays a sketch.

- **Declaring on the PAGE ROOT, so a control need not contain its readers.** The shipped shape
  makes the chooser the declarer, which forces the charts to sit beneath it — so a bar in a header
  steering charts in a sibling column is unwritable. A declaration on the tree root would remove
  that entirely: every node is its descendant, and the root's id is a value a control can address
  without the `@@NODE_ID@@` splice above, because it is not the control's own id it would need.

  Two things to settle before building it, neither hard: the root id is `c` only until an author
  sets one on the root node, and inside a surface the root is `s_<sid>__c` — so a control must
  address it through a reserved segment the server resolves to "this tree's root" rather than by
  spelling `c`. And it is one window per page by construction, which is the right default and not
  a general answer.

  Deliberately NOT built now: the composition shape below does the same job for the case that
  exists (more-info), and a rule about a magic scope is worth adding only once something wants it
  that composition cannot reach.

- **A global namespace, if the root declaration ever reads badly.** It is Pkl sugar over a
  declaration on the root node, never a second resolution rule — recorded so nobody builds one.
- **Whether the bake selection becomes a variable.** It is the same KIND of fact: `ui_<gid>` lives
  in the session, only the server writes the committed value
  (`SurfaceGraph.committedSelection`, after the swap actually happened), and the `ui_*` signals
  are a mirror the server pushes — ADR 0025 exists precisely because the CLIENT used to assert it
  and a POST that never landed left the URL claiming a panel the DOM did not have. So "that is
  pure client state" is the plausible wrong reason to leave it alone, and the real one is blast
  radius: ADRs 0005, 0007 and 0025 and the one shipped interactive control, for no new capability.
  Revisit once node variables have a real user.

**One name to avoid.** `Renderer` already calls a card's mustache context "vars", and `theme.pkl`
calls CSS custom properties the same. The field can be `vars`, but prose and any new type must say
*node variable* — a bare "vars" in a comment here will read as one of the other two.

## Phases

**0 — cash the prediction. DONE, and it confirms.** `RenderCacheSuite` now holds it directly
rather than through the live world, because today every viewer of a node reads the same query —
divergent reads are not reachable end-to-end until phase 2, and the claim is about
`RenderCache`'s install rule, which is reachable now. Measured: two windows over one sensor are
unordered in both directions, alternating asks render **every time** (4 of 4), each caller is
served its own span, and the node still holds exactly one generation. The sharing itself is
intact — three viewers on one window are one render.

**The eviction does not need bucketing, and the reason is structural.** What an evicted chart
node re-renders is a mustache splice of SVG it already has: `renderNodeById` takes `QuerySnapshot`,
not a `QueryResolver`, so a node render cannot fetch and cannot draw. The two expensive levels
are cached by `SlotRead` and by version — `BucketCache` for the fetch, `ChartStage` for the
drawing — and neither is keyed by node, so neither is touched by this eviction. Bucketing the
render cache per read would buy back a string build and cost the bound that `size == 1` asserts.
So: **no bucketing in phase 2**, and reopen only if a profile puts a chart node's paint somewhere
it shows.

The two falsified comments this phase was also carrying landed on #381 instead, where the
changeset that wrote them lives.

**1 — declaration and reference, build time only. DONE.** `VarDecl`, `Ref`, `QueryTemplate`,
`SlotAsk`, the scope walk, the build errors, `core/variable.pkl`. Every reference resolves to its
DEFAULT, so nothing a viewer does moves one yet.

Four things it settled that the design above had not:

- **`SlotAsk` is the type the plan called a template**, and `SlotRead` did not move — the ask is
  the tree's, the read is this render's. `QuerySnapshot`, `RenderInputs` and both caches were
  untouched.
- **Totality belongs at the WRITE, not in the build** — and this one was got wrong first. The
  first cut enumerated each variable's domain so the prepared request map would already hold
  whatever a viewer later picked, and made a domain REQUIRED on anything a query parameter reads
  to keep that possible. That is a build-time proof of something the build does not decide: a
  value is untrusted input arriving per session, and forcing every author to list values they may
  not have was the price of pretending otherwise. The build now parses the DECLARED values, which
  is a real build-time fact, and phase 3's write boundary resolves each declared reader's ask with
  a proposed value and refuses one that would not parse. Pulling that thread is what removed the
  domain field entirely — see decision 2.
- **A surface is its own scope root**, because a baked one can be swapped into a host and
  inheriting from wherever it is shown would let one content resolve differently per host.
- **A variable read from inside a candidate set is refused, for now.** A member's id is minted at
  run time, so it has no scope entry. Narrow — a query slot inside a set still works — and held
  by a test so lifting it is deliberate.

**2 — the value comes from the SESSION. DONE.** A viewer's choices now overlay the declared
values, resolved per render rather than per renderer, so two viewers of one dashboard hold
different `SlotRead`s — which is what makes phase 0's measurement reachable end to end.

**The environment travels INSIDE `QuerySnapshot`**, and that is the decision worth knowing. The
alternative was a `Choices` type replacing `uiState` on every render signature — about 110 call
sites, most of them tests. What settled it is that all three reader kinds resolve in the same
place: a plain-slot reader (kind 1) resolves in `resolveSlotValue`, which already receives
`QuerySnapshot`, so a separate channel would need threading to 110 sites to reach exactly where this
one already is. It also buys an invariant rather than only convenience — the answers and the
values they were fetched FOR are one value, so looking up a read resolved against a different
environment is not representable. It cost one real defect immediately: a test handing answers
without their environment now fails loudly instead of silently resolving to the wrong window.

The rest, as built:

- **`NodePlan` holds the node's ID, not its values.** The trap flagged above is closed by
  construction: a plan is memoised across sessions, so it carries an address and the values come
  from the per-render snapshot.
- **A choice is addressed to the DECLARER** (`(NodeId, name)`), not to the reader. That is what
  makes a shadow safe from the write side — choosing on an outer panel cannot move a chart that
  declares its own — and `Renderer.InScope` carries the declarer beside the declared value so the
  overlay knows where to look.
- **The URL restores it** — `v.<declarer>.<name>` query params, read by `Server.varChoicesOf` at
  the same point `uiStateOf` reads the bake selections, and recorded on the session because a PULL
  has no request to read them off again. The route (`POST /sse/var/…`) writes the session; nothing
  writes `v.` back into the URL yet, so a refresh falls back to the declared value until the URL
  mirror lands.
- **A separate prefix from `ui.`, deliberately.** They travel the same way and are the same KIND
  of fact, but a `ui.` entry is a bake branch narrowed by `SurfaceGraph` against that group's
  members, and a variable is a value narrowed by whoever reads it. Sharing the map would make
  `SurfaceGraph` see entries it must ignore and leave `committedSelections` answering half a
  question.
- **An unmatched choice is inert, not an error** — a stale URL naming a node that was renamed
  matches no scope and is simply never read, the shape `SurfaceGraph.openPopup` already uses.

`RenderInputs` gained nothing: a query reader's `SlotRead` already differs, which is the whole
point of the ask/read split. The third map is for a plain-slot reader, which phase 2 does not
build.

Two things found on the way, neither caused by this work. `modules/benchmarks` had not compiled
since the query snapshot became a required argument — fixed here, because the benches are the
measurement tool this work leans on. And `Fragments` was renamed to `QuerySnapshot` (its element `Fragment` to `Staged`,
whose `html` became `value`, since passthrough yields JSON) — the old names collided with the
FRAGMENT this pipeline already means, a node’s own HTML.

**3 — the write path. DONE, and pulled forward as a vertical slice.** Two phases had produced
nothing observable and the shape was validated only by its own tests, so phase 4's first real user
was brought up alongside the write rather than after it. `POST /sse/var/:slug/:node/:name/:value`
sets a variable for one viewer and re-renders exactly the nodes that read it (`Renderer.readersOf`,
the declared edge inverted). A refused value raises and `withSession` turns it into ADR 0024's
200-of-signals.

**The slice immediately found a defect no unit test could.** `Validated.queries` holds what the
BUILD parsed — every read at the DECLARED values — so a viewer choosing `7d` asks something that
map has never seen, and `QuerySnapshot.resolve` raised "no parsed request" on the very first write.
That map is a memo, not a totality proof, and it stopped being one the moment a value could be
chosen at render time. It now falls back to parsing, and only a read that fails BOTH is a wiring
bug. What keeps it sound is the write boundary refusing anything a declared reader cannot parse —
which is the argument for moving totality there, cashed rather than asserted.

**4 — the control itself. DONE.** `c.windowChooser` declares `window` and renders the bar;
`c.historyChart(s).chosen()` reads it. Neither takes the name as an argument, so the pair cannot
drift, and two choosers on one page stay independent through the ancestor chain alone.

ADR 0025 came with it, as predicted: the press writes only `_var_<declarer>__<name>__pending`, the
server commits `_var_<declarer>__<name>` after the repaints, and agreeing with pending ends the
ask. The opening frame is TOTAL over declarations rather than over the session's choices — the
case that forces it is a FORGOTTEN session, where a control still highlighting last session's
window would otherwise disagree with the chart beside it for the whole connection.

**One thing the implementation decided that the plan had not.** The bar lives in the chooser's own
template, not in child button nodes. A button has to name the declaring node in the route it posts
to and in the signal it reads, and the only id a template can spell is its own; `@@NODE_ID@@`
would give a child its parent's id, but `DashboardBuild.hoistInlineSurfaces` splices it only for a
node that also carries inline surfaces. So the generic "declare a variable and a control over the
same list" component named in `core/variable.pkl` is still future work, and it needs that splice
to become something a node can ask for on its own. What ships instead is one component with the
four `Window` values fixed — the one list this library keeps beside a typealias, where
`Listing<Window>` makes offering a non-window impossible and omission is the only drift left.

**5 — docs. What remains.** An ADR, which this needs: why resolution is up the ancestor chain, why
a reference is not a CEL expression, why a declaration carries no set of allowed values, and why
totality lives at the write rather than in the build — none of which the code can state, plus
"Future work" above verbatim (now four entries, the id-owner splice being the new one), since a
plan gets deleted and a decision only findable there is lost. Close #210. Delete this file.

Architecture §6 is already updated — it gained the write/commit paragraph and the authoring note
in the same commit as the control, per the repo's rule. Its barrier-precondition block needed no
flip: it states a standing invariant ("anything that lets one node read another's computed value
must declare the edge"), not a pending guard.

`terminology.md` is already done — it gained *node variable*, *declarer*, *reference*, *shadow*
and the *ask* / *read* split in phase 1, because a change that coins a term updates it in the same
commit.

## What this does not do

- **It does not migrate the bake selection.** `ui_<gid>`, `SurfaceGraph`, `committedSelection`,
  the pending/committed tab machinery and the URL mirror stay exactly as they are. They are
  arguably the same fact as a variable — per-session named state, server-committed, mirrored,
  URL-restored — and unifying them is a real candidate afterwards. It is not this stack: the blast
  radius covers ADRs 0005, 0007 and 0025 and the one shipped interactive control, for no new
  capability.
- **It does not let CEL read variables.** A transform is opaque to static analysis, so a slot
  whose reference lived inside a CEL string could not be enumerated before the walk — which is the
  one property this whole change exists to keep.
- **It does not add a second write mechanism for query params.** The `settable` /
  `queryParam(node, slot, param, value)` draft is dead and this replaces it.

## Alternatives, and what each costs

**Do it in the browser.** The window control writes a client signal and POSTs for a patched chart.
No declaration, no render-key change, no ancestry. It fails on the first paint: the server renders
the document and must already know the window, so either the default always wins on a refresh
(dropping ADR 0005's URL mirror for this one value) or the page ships a hole and fills it, which
§0 forbids. It is also the second mechanism the dropped draft was rejected for.

**Make the window a bake group.** Four surfaces, one chart each, a tab bar selecting among them.
Zero new machinery — the tab bar already ships. The cost is in `SurfaceGraph.bakedSurfaces`: a
page resolves every baked surface whether or not it is showing, because a bake swap renders from
state alone and cannot fetch. So a page open is four fetches and four drawings for the one chart
anybody sees, ~30 ms of serialised GraalJS each (ADR 0032), and a control over three charts is
twelve surfaces. The code comment on `bakedSurfaces` already records this as the reason window
selection became a variable.

**Resolve by node id instead of by name.** `varRef("c_2", "window")`. Authors do not know node
ids — they are position-derived (ADR 0022), which is why `@@NODE_ID@@` exists at all.

**A global namespace.** Live option, not a rejection — see open call 1.
