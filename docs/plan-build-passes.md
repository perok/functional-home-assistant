# Plan — build passes, named as a compiler names them (#615)

The build is a compiler front end: it turns a dashboard's JSON into `Dashboard.Validated`, once
per evaluation and never while rendering. Its passes are not named, and two of them change a
node in place, so one type stands for "before" and "after" and the step has to run, safely, in
three places. This plan removes those two in-place passes instead of typing them.

## The passes

Named as compilers name them. `docs/terminology.md` gets one entry with the whole list.

| Pass | What it does here | Where |
|---|---|---|
| **decode** (a compiler's parse) | JSON → `Dashboard`; a rule about one value is part of its type (an iron refinement, `ChartStyle.Size`) | circe decoders |
| **rewrite** | the slug is applied | `DashboardBuild.decode` |
| **lower** | sugar → core form: a live cell class or property becomes a slot | Pkl, before emit (below) |
| **bind** | connects each name a slot's CEL reads to its definition (a literal, a tally or a condition) and type. It does not fix the value | lazily, on the node (below) |
| **check** | the rules across fields and nodes | `validate` |
| **compile** | CEL against the names and types its binding gave it; queries parsed | `compileTransforms`, `parseQueries` |

The output of bind and compile is what CEL itself calls the **environment**: names and types,
fixed at build time. The values are the **activation**, computed on every render
(`Conditions.resolve`), as a function's arguments are at each call.

"Bind" is a compiler's word and keeps that meaning in this project. It is not a card's
**binding** (`docs/terminology.md`: where a value lands, `signalBind`, `{{{slot__bind}}}`), and the
terminology entry says so.

"Fold" goes. In compilers it means constant folding, and it named two passes at once.

## 1. Lower moves to Pkl

Today Pkl writes `cell.classWhen` / `cell.properties`, and Scala's `foldNode` copies them into
`slots` as `cell.class:<cls>` / `cell.style:<prop>`. Pkl writes them into `slots` itself:

- `Node.slots` gets a default holding the live entries, built from `liveClasses` /
  `liveProperties`. Every card amends `slots { … }`, so they stay there. The two places that
  replace `slots` outright, the slider's head and text (`slots = headSlots`, `slots = textSlots`),
  become amends.
- A constraint on `slots` refuses a replacement that drops a live entry, so a component author's
  `slots = new { … }` fails the build rather than losing a class.
- A live class or property on a candidate set fails in `classWhen` / `cssProperty`, as a condition
  on a set already does. `validate`'s two set messages go.
- `Cell` is `{classes}` on both sides. Authors keep `.classWhen` / `.cssProperty`.

## 2. Bind is computed on the node, not run as a pass

`SlotSource.values` goes. A component pairs each slot with the expression values its CEL names,
lazily and once per node:

```scala
/** A slot and the node's expression values its CEL reads: its environment. */
final case class BoundSlot(source: SlotSource, values: Map[String, ExprValue]):
  lazy val celKey: Option[Transform.CelKey] = …
  lazy val valueKey: String = …          // signal names, the once-cache
  def valueEntities: List[String] = …    // the reverse index

// LayoutNode.Component
lazy val bound: Map[String, BoundSlot] = …   // by Cel.identifiers, as attachValues does
```

A value is derived from the decoded node, so nothing can see a slot before binding, or bind it
twice. `Dashboard.folded`, `LayoutNode.foldNode` and `attachValues` are deleted. A pass that
binds names on demand is how Roslyn's binder works too.

The renderer, `liveEntities` and `validate` read `c.bound` where they need a slot's values. The
renderer reads `bound.celKey` and builds no key per render. That rebuild was measured and the
benchmark could not see it, but none of its fixtures has expression values, so it never ran the
list, sort and hash a valued slot pays. It is strictly more work, and this removes it at no cost.

## 3. One pipeline

`DashboardBuild.decode` decodes and rewrites; `validated` then runs check → compile, and is the
only producer of `Validated`. With lower in Pkl and bind on the node, there is no step left that
has to run before the checks, so nothing runs one twice.

`Renderer`'s constructor takes `Validated`. `Renderer.create` (tests and benchmarks) goes through
`validated` too, and throws on a dashboard the build refuses, so no test renders a dashboard
production cannot have. `Transforms.from`, the compile that skipped the checks, is deleted.

## 4. Not changing

- **`valuesNamedIn` stays.** Values belong to one node (ADR 0034 rejected looking names up the
  ancestors), so a structural card such as the slider hands each part the values that part reads,
  and Pkl cannot run the CEL parser. Scala's "read by no expression" check catches a wrong regex.
- **No bound programs in the renderer's plan.** The key built once is the fix; holding the
  compiled program too would save one map lookup per slot, unmeasured.

## Tests

Edits to existing tests, each needing the maintainer's OK:

- `FoldSuite` is deleted: what it tests is deleted.
- `WireShapeSuite`: `values` leaves `Slot`'s Scala-only set; `Cell` checks `classes` only.
- Snapshots regenerated: live entries move from `cell` into `slots`.
- Fixtures `validate` refuses are made valid (14 tests in 9 suites, three bench fixtures): a card
  registered, a baked region declared and selected, a chart slot read `onRender`, a signal slot's
  bind placed. Three tests exercised a refused combination and change what they cover:
  ChartSlotSuite's escaped chart hole is deleted (`QueryRenderInputsSuite` pins its build error);
  SignalSlotSuite places `__read` only for `once`; RenderInputsSuite's bake owner reads its
  entity as a signal.

New: a Pkl test that a replaced `slots` dropping a live entry fails, and that a set refuses one.

## Docs, in the same commits

- ADR 0034 "Attached after decode" rewritten to section 2.
- `docs/architecture-rendering-pipeline.md`: the `foldNode` paragraph.
- `docs/terminology.md`: the passes, bind versus binding, environment and activation.
- Root `CLAUDE.md`, "Parse, don't validate": a rule about one value is part of its type and is
  checked at decode; a rule across fields or nodes stays in `validate`.

## Done when

- `validated` is the one function from a decoded `Dashboard` to `Validated`, and no pass runs
  outside the build path, tests and benchmarks included.
- `SlotSource` has no `values` field, and `Cell` has no live entries.
- The renderer builds no compile key per render.
- The docs above describe the passes, and this plan is deleted.
