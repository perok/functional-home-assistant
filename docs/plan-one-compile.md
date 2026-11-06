# Plan: one compile for every value expression

Branch `one-compile`, stacked on `pkl-reading` (#670). Discussed in chat on 2026-11-05 and
2026-11-06; it takes over items 5, 7, 8 and 9 of `plan-slot-values.md`'s review follow-ups.

**State:** O0 and O1's single compile entry point committed. O1's compiled model is in
(`model/Ready.scala`): the renderer and the member and surface graphs walk only it. Left in O1: a
slot's facts still come from its `BoundSlot`, not `Compiled`; a set call's reach is still the wire's
`Value.Present`, compiled at the press (`Scope.onDemand`); `compileTransforms`, `celKeys` and
`initialKeys` stay.

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
`listOf` and `inputType`'s shape list. ADR 0035 gives cost as the reason, but a guard runs when
its member changes, as a slot reading that member does, and slots run CEL. It surprises an
author: `disabled = c.expr("…")` takes a CEL bool, `c.iff(…)` cannot.

Two more things follow from the same gap:

- **Two definitions of a condition.** `isCondition` asks for a deciding step, `conditionError`
  for a bool type. They disagree on a `Match` whose arms are all bools and on `… |> First`.
- **Inputs merged per slot.** The wire puts a CEL's named inputs on its own step or source, but
  `BoundSlot.bindAt` merges every CEL's inputs and the node's values into the slot, so each CEL
  in a slot sees all of them.

And the runtime re-derives what wakes an expression in several walks (`entities`,
`operandEntities`, `BoundSlot.valueEntities`, the reverse index) that do not all follow the same
paths.

## The design

1. **Two models and one walk between them.** The wire model stays as it is: the decoder only
   decodes. `validated` walks it once, each node with its card's entity and declared values,
   into a second family of classes that hold `Compiled` where the wire holds a `ValueExpr`
   (`Ready.*`, name to pick). The renderer, `MemberGraph`, `SurfaceGraph`, the order keys and
   set calls take that family, so nothing that runs can reach an expression that has no run,
   and there is no lookup that could miss. A class with no expression in it (`Placement`,
   `Region`, `CardDef`, `Theme`, a declaration, …) is shared, not copied. Only the classes that
   hold an expression or a node have a twin, and each member lives once, on the stage that
   reads it: the node facts the runtime caches (`fetching`, `liveEntities`, a cell's entries)
   are plain lazy vals on the compiled class, as today, so a paint reads them as it does now.
   Not one parametrised model: the stages want different members, and sharing a class
   between them took a cast. Not chimney: compiling needs each node's context, which its
   type-directed derivation has no place for. Compiling stays in `validate`, not the decoder:
   it can fail (collected into the whole dashboard's errors) and needs the node's context.
   Inner expressions (guards, list items, CEL inputs) compile into their parent's run.
2. **`Compiled` is the expression made ready to run:**

   ```scala
   final case class Compiled(run: Evaluator.Run, out: ValueType,
                             wakes: Set[String], key: String, reads: Reads,
                             constant: Option[SlotValue], fetches: List[SlotAsk])
   ```

   The expression lives inside `run`: the closure holds its steps and their programs. Nothing
   outside it reads the `ValueExpr` again, so it is not a field. The rest are facts the runtime
   derives from the expression today, collected in the same walk: what it wakes on (flattened
   over its source, operands, items, guards, order terms and every input, recursively), its
   signal/cache key, `reads`, a constant value, its fetch asks. `BoundSlot` shrinks to where the
   value lands (signal, type), its compiled `valueWhile`, and the compiled value.
3. **Each unique CEL compiles once.** The walk memoises programs by `Cel.Key`: the source and the
   types it is checked against. `Cel.asts`, the process-wide cache of checked trees, stays for
   the re-validation a dump refresh does.
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
8. **Pkl: a condition is a value expression.** `c.iff`, `where`, set clauses, `orderBy`, the
   boolean inputs (`disabled`, `active`, `cellClassWhen`) and a card's `label`/`value` take
   `slotMod.ValueExpr`. `c.expr`/`c.exprOf` return a `Reading` (the existing `celReading`: the
   whole entity plus one CEL step), so the `Expr` class, `exprReading` and the `is Expr`
   conversions go, and `BooleanInput` is `Boolean|ValueExpr`. `c.iff` also keeps a plain
   `Boolean` and `v.valueIs(x)`. Pkl does not parse CEL, so a text CEL in a bool place is now
   refused by the build, not the editor.
9. **The wire changes in one place: a list source declares its item type** (`itemType`), so an
   empty list is typed as any other and its size is the runtime's (ADR 0035). Otherwise every
   position already holds a value expression, CEL already carries its inputs, and the Reading
   `c.expr` returns is what is emitted today. No snapshot or golden holds a list source, so they
   hold.

## Steps

- **O0 — a list's item type.** Design point 9's wire change; "a list has no items" goes.
- **O1 — the compiled model.** The compiled family and the walk (typing and compiling as one),
  `Compiled`, the memo; `validate`'s checks that read node facts run on the compiled tree;
  the renderer, `MemberGraph`, `SurfaceGraph`, the order keys and set calls take it. `Evaluator.plain`, the iterator pairing,
  `compileTransforms`, `celKeys`, `initialKeys` and the re-deriving wake walks go. CEL is still
  refused where it is today: no behaviour change.
- **O2 — a CEL's own inputs.** Inputs compile with the walk; the node's values are in scope by
  name; `bindAt`'s merge and `inputType`'s shape list go.
- **O3 — CEL in every place.** The refusals go; a bool is `out == Bool`; one bypass rule;
  `isCondition`, `conditionError` and `readError` go.
- **O4 — Pkl and docs.** Design point 8; `writing-components.md`, `writing-dashboards.md`,
  `terminology.md`, ADRs 0028, 0034 and 0035, the architecture map.

## Tests

New: CEL in a guard, a clause, a state surface, an order term, a list's item and an input's
input, each deciding as the same expression in a slot does and waking on what it reads; a CEL
that errors in a guard leaves the member absent; one program per unique key; a text CEL in
`c.iff` refused by the build. Existing tests change where the change makes sense (a moved
message, a constructor taking the parameter, an API that went); each step's summary lists what
changed and why.

## Checks

Per step: the suite with Slow excluded, a `tpolecatCiMode` compile, `scalafixAll --check`, the
snapshots and goldens unchanged. `RenderBench` (`condition`, `conditionTree`, `conditionCel`,
`conditionAttr`, `page`, `tickRender`) and `ValidateBench`, one round before and after O1 and
O3; three rounds for the PR's numbers.

## Not in this plan

- One name for named inputs: Pkl `values`, wire `inputs`, Scala `values`, "expression values".
- **Type variables:** a step typed over any `T` (`Count` takes `List(T)`, a `First` that keeps
  its items' type), so a list's item type could be left open until a step fixes it. Today
  `ValueType.List(of)` is parameterised but every type is concrete.
- **A runtime error isolated to its node:** an error kind beside `FHError.internal`, so a failure
  while one node renders fails that node, not the session's step (today a throw there drops the
  step's frames and keeps the old state). A CEL failure would become that error instead of a
  `"cel error: …"` string carried as a value, which is the one way a step meets an input its type
  rules out (`First`, `AllOf` or `AnyOf` over a non-list).
