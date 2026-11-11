# Plan: a variable is a source

Part of the regions work (`docs/plan-regions.md` on branch `regions`, step V), done ahead of it,
on top of `docs/plan-card-root.md` (the tab button needs its cell).

A variable is read today in ways that are not expressions: a fetch window's
`Choice.Chosen(by: String, options)` (`Dashboard.scala:237`), a bake member's `Activation.Var`,
and a Datastar string a card builds in Pkl (`TabButton.active`). And a page's raw HTML never shows
the open tab: the tab bar is lit only by the client (`data-class:fh-chosen`, `surface.pkl:45`).

**State:** not built.

## Design

### A source

`Value` gains `Var(name)`, beside `Literal`, `Entity`, `Fetch`, `Present`, `List` and `Cel`,
resolved at compile up the ancestors to its declarer, as `VarGraph` resolves a variable today. In
Pkl, `v.value` (text) and `v.valueIs(x)` (bool, sugar for `value == x`) are `slotMod.Expr`s. The
declaration says where the value lives; a write (`setVar`) is its trigger, as a state change is an
entity's.

- **An expression is decided where its most local source lives:** one reading only `Entity`,
  `Fetch` and literals once per version, shared by every viewer; one reading a `Var` per viewer.
- **A value slot may read a variable** (`label.of("Showing " + window.value)`). Its bytes then
  differ per viewer, so its render key carries the values it read: `RenderInputs` gains them, and
  `RenderCache` keeps one generation per node and set of values read, the bucketing ADR 0012 once
  had (`RenderInputs.vars`). Viewers on different values cost one render per value instead of
  evicting each other, bounded by the variable's values. A write re-renders the variable's readers
  for that viewer, as a chart over a chosen window is re-rendered today.

### Where a variable read may stand

A leaf's value slots and `cellClassAtRender` only. `validate` refuses a `Var` in any shared
decision (clauses, state surfaces, `valueWhile`, order keys, set calls), in any signal slot
(`cellClassWhen` and `cellStyle` included, since a display signal is shared by name), and in a
structural card's own byte slots. `c.iff(v.valueIs(x))` still lowers to `Activation.Var`. The
regions work lifts these (`docs/plan-regions.md` §5 lists what it owes).

### One choice type

`Choice[A]` becomes the model's one choice, with `by` an expression and typed keys:

```scala
enum Choice[+A]:
  case Fixed(value: A)
  case Chosen(by: ValueExpr, cases: List[Case[A]], otherwise: Option[A] = None)
case class Case[+A](is: Lit, value: A)
```

- Deciding: evaluate `by`, take the case whose `is` equals it, else `otherwise`, else nothing.
  Nothing is no value, with no special case: a fetch slot asks nothing and its reading is null,
  which the slot's own null handling (its `default`) shows. A `by` reading entity state adds those
  entities to the node's reads, so a state change wakes it and its render key carries them, as any
  entity read does.
- `Choice` is data; deciding is the compile's: `by` needs the evaluator, so `resolve(env)` leaves
  `Choice`, and `Ready` holds `by`'s compiled run beside a map from key to each case's value.
- `A` is data the build checks whole and that never depends on the key at render (here a
  `Window`; the regions work adds nodes). Only `by` is an expression, so `A` needs no `ValueType`.
- Keys are typed by `by`'s type, checked in `validate` against `ValueExpr.typed`: `Text`, `Bool` or
  `Whole`; never `Real`, `Dyn` or `List`. On the wire `cases` is a list of `{ "is": <scalar>, … }`,
  since a JSON object's keys are strings and decoding them by another field's type would derive in
  the decoder.
- A fetch window: `p.history.chosen(v, table)` is `Chosen(by = Var(v), cases = table)`, with no
  `otherwise`, so a key the table lacks is still refused at the write. `Choice.variable` becomes
  the variables `by` reads. Since `by` is any expression, an entity's state can pick a window too,
  decided once per version.

### The open tab in the first page

`cellClassAtRender(cls, expr)` writes `cell.atRender`: evaluated for the viewer in the document
form and written into `class`, with no signal, no frame and no binding, so patches are the same
for every viewer. The tab and select buttons use it,
`.cellClassAtRender("fh-chosen", v.valueIs(choice))`, so a page's raw HTML lights the open tab
without scripts (what `{{bakeIndex}}` was for, ADR 0012). Their live highlight stays the
browser's own `data-class:fh-chosen` over the pending-or-committed expression, instant on a press
with the spinner until the commit (ADRs 0019, 0025): a patch carrying the class could arrive after
a second press and light two tabs.

### Not here

Guards over variables (`Activation.Var`, `VarAt`) stay until the regions work replaces bake groups;
the `@@VAR:…@@` token and `TabButton.active` stay until its variable slot.

## Steps

1. `Value.Var`, `v.value`/`v.valueIs`, the most-local-source rule, value slots reading a variable.
2. `RenderInputs` with the values read, `RenderCache` bucketed by them. `RenderCacheContentionSuite`
   stays at 1.0 renders a frame for nodes reading no variable; a new case holds one render per
   value for a node that reads one.
3. `Choice[A]` with `by: ValueExpr`, `Case`s and `otherwise`; the fetch window on it; wire
   snapshots regenerated deliberately.
4. `cellClassAtRender`; the tab and select buttons lit in the first page. Goldens change at the
   buttons only.

Bench a tab flip, a select change and a chart over a chosen window, before and after: allocation
first, with `-XX:-DoEscapeAnalysis` on both sides, `VarEnvBench` as the per-request baseline.

## Docs

ADRs 0012 (the cache's buckets, `bakeIndex`'s job), 0033 (variables as a source), 0034/0035
(`Var`, `Choice`); `docs/architecture-rendering-pipeline.md` (the cache key, per-viewer values);
`docs/terminology.md`; `docs/writing-components.md` (`v.value`, `v.valueIs`,
`cellClassAtRender`).

## Done when

`sbt 'fh-datastar-view/testOnly * -- --exclude-tags=Slow'` is green, the contention suite holds both
numbers, and the bench shows no regression for nodes that read no variable.
