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
enumerable.** `Fragments` is total over the queries a render reads, which is only expressible
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
case class VarDecl(default: String, domain: Option[List[String]] = None)
// LayoutNode.Component.vars: Map[String, VarDecl]
```

In Pkl, typed at the authoring end and stringly on the wire — the same division ADR 0031 settled
for query params: `vars { ["window"] = varMod.oneOf(Window.all, default = "24h") }`.

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

Untrusted, narrowed the way `SurfaceGraph.resolveActive` already narrows a tab index: a value
survives only if the declaration admits it, else the default wins and the anomaly is reported.
Defaults fill everything absent, so the map is total over declarations by construction and no
reader handles a missing one.

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
2. **A declaration carries an optional DOMAIN** — the set of values the variable may hold, as a
   `List[String]`: `["1h","24h","7d","30d"]`. It buys three things: a write outside it is refused
   at the HTTP boundary so `HistoryQuery.parse` never sees a bad value; a stale URL falls back to
   the default instead of erroring, which is the narrowing `SurfaceGraph.resolveActive` already
   does for a tab index it does not recognise; and the control can be GENERATED from the
   declaration rather than listing the windows once in the buttons and again in the chart.
   `domain = null` for free text. A list of strings and not a type — see "Future work".
3. **The bake selection does not become a variable** — see "What this does not do".

## Future work — these belong in the ADR phase 5 writes, not in this stack

Three things that are deliberately out of scope here and should be recorded where a later reader
finds them, rather than rediscovered.

- **A domain wants to be a TYPE, not a list of strings.** `["1h","24h","7d","30d"]` is an enum
  spelled as data, and the same is true of any variable worth declaring — a number with a range, a
  boolean, an entity id. The authoring end already has this: `Window` is a real Pkl union, and the
  list is what survives the trip to the wire. What a typed domain would buy is validation that
  says *what kind of thing is wrong* rather than "not one of these four", and a control generated
  from the type rather than from a list. What it costs is a type language on the wire, which is a
  much larger decision than this work needs. Ship the list; investigate the types.
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
node re-renders is a mustache splice of SVG it already has: `renderNodeById` takes `Fragments`,
not a `QueryResolver`, so a node render cannot fetch and cannot draw. The two expensive levels
are cached by `SlotRead` and by version — `BucketCache` for the fetch, `ChartStage` for the
drawing — and neither is keyed by node, so neither is touched by this eviction. Bucketing the
render cache per read would buy back a string build and cost the bound that `size == 1` asserts.
So: **no bucketing in phase 2**, and reopen only if a profile puts a chart node's paint somewhere
it shows.

The two falsified comments this phase was also carrying landed on #381 instead, where the
changeset that wrote them lives.

**1 — declaration and reference, build time only.** `VarDecl`, `Ref`, the resolution walk, the
build error, the Pkl surface. Every reference resolves to its DEFAULT; nothing moves at runtime.
Pure, and testable without a server.

**2 — the value reaches a render.** Session `vars`, narrowing, defaults; template resolution in
`queriesForPage`/`queriesForSurface`; `RenderInputs` gains the third map if phase 0 says so. The
URL mirror restores it on refresh (ADR 0005). Still no writer — the URL is the only way to move
one, which is a complete and testable product.

**3 — the write path.** A `setVar` tap; the server validates against the domain, commits, and
patches exactly the declared readers. Pending/committed per ADR 0025.

**4 — the window control.** The first real user: window buttons in the more-info popup, the chart
beneath them reading `window`, the active button highlighted through a signal slot.

**5 — docs.** An ADR (this needs one: where domain validation lives, why the ancestor chain, why
not CEL — none of which the code can state, plus "Future work" above verbatim). Architecture §6's
barrier-precondition block changes
from "protected by #209" to "satisfied, and here is how". `terminology.md` gains *variable*,
*declarer*, *reference*, *shadow*. Close #210. Delete this file.

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
