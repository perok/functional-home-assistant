# Plan: one compile for every value expression

Branch `one-compile`, stacked on `pkl-reading` (#670). Discussed in chat on 2026-11-05; it takes
over items 5, 8 and 9 of `plan-slot-values.md`'s review follow-ups.

**State:** plan only. The open questions below are answered before O1 starts.

## Why

There is one evaluator (`Evaluator.compile`), but CEL reaches it only in a slot:

1. `validate` type-checks each CEL and records its `Cel.Key`.
2. `Validated.compileTransforms` compiles the keys it collects from slots and node variables'
   start values into programs.
3. The renderer hands a slot's programs to `compile` as an iterator, in the order
   `ValueExpr.typed` listed the keys.
4. Everywhere else (a guard, a set clause, a state surface, an order term, a list's item)
   gets `Evaluator.plain`: `compile` with no programs and no inputs. A CEL there would fail as
   the plan is built.

So `validate` refuses CEL in those places, four times over: `conditionError`, `SortTerm.readError`,
`listOf` and `inputType`'s shape list. ADR 0035 gives cost as the reason ("they run per member
per change"), but a guard runs when its member changes, as a slot reading that member does, and
slots run CEL. Before Part C the old condition reader skipped CEL silently; the refusal replaced
that wrong answer with a gap. It is surprising to an author: `disabled = c.expr("…")` takes a
CEL bool, `c.iff(…)` cannot.

Two more things follow from the same gap:

- **Two definitions of a condition.** `isCondition` asks for a deciding step, `conditionError`
  for a bool type. They disagree on a `Match` whose arms are all bools (a bool, no deciding
  step) and on `… |> First` (a deciding step, text).
- **Inputs merged per slot.** The wire puts a CEL's named inputs on its own step or source, but
  `BoundSlot.bindAt` merges every CEL's inputs and the node's values into the slot, so each CEL
  in a slot sees all of them.

## The design

1. **`validate` outputs runs.** Every value expression, wherever it sits, is type-checked and
   compiled in one walk into `Compiled(run: Evaluator.Run, out: ValueType)`. A CEL's program is
   built inside that walk and held by the run; nothing outside the compile step sees a
   `Cel.Program` or a `Cel.Key`.
2. **Every place compiles the same way:** template and cell slots, `valueWhile`, node variables'
   start values, guards, set clauses, state surfaces, order terms, a list's items, and named
   inputs.
3. **Each unique CEL compiles once.** The walk memoises programs by `Cel.Key`: the source and the
   types it is checked against (`value`'s, and each of its inputs' by name). `Cel.asts`, the
   process-wide cache of checked trees, stays for the re-validation a dump refresh does.
4. **A CEL's inputs are its own.** Each CEL step and source keeps its `inputs`, each a value
   expression compiled the same way, recursively, so an input may be anything that compiles,
   CEL included. Its node's declared values are in scope by name, its own winning. The per-slot
   merge goes, and so does `inputType`'s list of allowed shapes: an input's type is its compiled
   type.
5. **A bool is a type.** Where a bool is required (a clause, a guard, a state surface, a bool
   slot through `ValueType.fits`), the check is `out == Bool`. `isCondition`, `conditionError`
   and `readError` go. An order term takes any type the order keys take.
6. **CEL is allowed everywhere.** The library's builders keep writing simple steps; CEL in a guard
   is the author's choice, at about three times the allocation (`RenderBench.conditionCel`
   against `condition`). A CEL error in a condition reads as not holding, as `null` does.
7. **One bypass rule** over every read: only a text slot's own read of a state or a whole entity
   takes `bypassUnavailable`. Its copies in `conditionError` and `readError` go.
8. **The wire does not change.** Every position already holds a value expression, and CEL
   already carries its inputs. The snapshots and goldens hold.

## Open questions

1. **How a consumer finds its run.** `Validated` holds `Map[ValueExpr, Compiled]`, keyed by the
   bound expression, so identical expressions share one; a plan looks its runs up when it is
   built, never at render. The alternative is a compiled model whose expression fields hold
   `Compiled`, which types more but rewrites `LayoutNode`. Recommended: the map.
2. **The Pkl spelling of a CEL condition.** `c.iff`, `where`, a clause and `orderBy` take a
   `pred.Predicate`; a CEL bool is a `slotMod.Expr`. Either those take `slotMod.ValueExpr`, or
   `Expr` becomes a predicate. Ties into folding `Expr` into `Reading` (item 7).
3. **Changed messages.** Errors now come from the compile walk, so some texts move. Tests that
   assert a substring of one are listed with O1 and O3, and changed only with a yes.

## Steps

- **O1 — runs from `validate`.** The compile walk (typing and compiling as one), `Compiled`, the
  memo, and `Validated`'s runs; the renderer, `MemberGraph`, `SurfaceGraph` and the order keys
  use them. `Evaluator.plain`, the iterator pairing, `compileTransforms`, `celKeys` and
  `initialKeys` go. CEL is still refused where it is today: no behaviour change.
- **O2 — a CEL's own inputs.** Inputs compile with the walk; the node's values are in scope by
  name; `bindAt`'s merge and `inputType`'s shape list go.
- **O3 — CEL in every place.** The refusals go; a bool is `out == Bool`; one bypass rule;
  `isCondition`, `conditionError` and `readError` go.
- **O4 — Pkl and docs.** The author spelling (open question 2), `writing-components.md`,
  `writing-dashboards.md`, `terminology.md`, ADRs 0028, 0034 and 0035, the architecture map.

## Tests

New: CEL in a guard, a clause, a state surface, an order term, a list's item and an input's
input, each deciding as the same expression in a slot does and waking on what it reads; a CEL
that errors in a guard leaves the member absent; one program per unique key. Existing tests stay
as they are, apart from the messages in open question 3.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`, the
snapshots and goldens unchanged. `RenderBench` (`condition`, `conditionTree`, `conditionCel`,
`conditionAttr`, `page`, `tickRender`) and `ValidateBench`, one round before and after O1 and
O3; three rounds for the PR's numbers.

## Not in this plan

- One name for named inputs: Pkl `values`, wire `inputs`, Scala `values`, "expression values".
- Pkl's `Expr` folded into `Reading` (item 7), unless open question 2 takes it in.
