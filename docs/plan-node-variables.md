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

That third map is the selection dimension the issue warned about, and it is why phase 0 exists.

## Open calls — these want a decision before phase 1

1. **Scoped or global namespace?** Ancestor-chain scoping is what the issue specifies, and it
   gives shadowing (one chart on a different window inside a panel that sets the rest). A
   **dashboard-global** namespace is markedly cheaper — no scope stack, no ancestry walk, a flat
   key — and still gives "one control, three charts", which is the case that motivated this. What
   it loses is the nested override and any notion of lifetime. Recommendation: **scoped**, because
   the resolution walk is ~30 lines over a tree we already walk and the flat version is the string
   convention with a schema attached; but it is a real fork and it is cheap to take the other one.
2. **Does a declaration carry a DOMAIN?** Recommendation: **yes, optional.** `window` is a closed
   set; carrying it means a bad write is refused generically at the boundary and the provider's
   parser never sees one, it matches how an untrusted tab index is already narrowed, and it is
   what would let a control be GENERATED from the declaration rather than listing the four windows
   twice. `domain = null` stays available for free text.
3. **Does the bake selection become a variable?** Explicitly **not in this stack** — see "What
   this does not do".

## Phases

**0 — cash the prediction.** `RenderCacheContentionSuite` holds one render per frame however many
viewers and tabs. Add viewers on divergent `SlotRead`s and find out what actually happens. ADR
0031 predicts, from reading `RenderCache`'s source and not from a measurement, that their keys are
unordered so each install evicts the other — one render per pull each, never wrong bytes. The
number decides whether the cache needs to bucket per variable value again, which is what it did
before `vars` was removed (architecture §9).

Two falsified comments belong with this phase because the query-slot changeset wrote them:
`RenderCache`'s scaladoc still says "`RenderInputs` is entity versions and nothing else" and "a
SELECTION is not part of the key"; architecture §6 still says the page and patch paths pass
`Fragments.none`, a value that no longer exists.

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
not CEL — none of which the code can state). Architecture §6's barrier-precondition block changes
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
