# Plan: what the value model left open

The open items of the slot-values stack (#657 → #667 → #658 → #666 → #669 → #670 → #672), whose
plans (`plan-slot-values.md`, `plan-one-compile.md`) are built and deleted; their decisions are
in ADRs 0028, 0034 and 0035. Nothing here is agreed yet: each item needs a design round before it
is built, and the order below is a proposal.

**State:** not started.

## Small

- **`EntityState.none` is test-only** (`StateStore.scala`): its callers are `ConditionsSuite` and
  `SimpleReadsSuite`, so it moves to the testkit.
- **One name for named inputs:** Pkl `values`, the wire's `inputs`, Scala's `values` and the docs'
  "expression values" are one thing. A rename, and the wire moves with it.

## Design rounds

- **What the type tells `validate`:** strict `Dyn` and an `Option` type, ADR 0035's open
  questions, assessed together.
- **Type variables:** a step typed over any `T` (`Count` takes `List(T)`, a `First` that keeps its
  items' type), so a list's item type could stay open until a step fixes it. `ValueType.List(of)`
  is parameterised, but every type is concrete.
- **A runtime error isolated to its node:** an error kind beside `FHError.internal`, so a failure
  while one node renders fails that node, not the session's step (today a throw there drops the
  step's frames and keeps the old state). A CEL failure would become that error instead of a
  `"cel error: …"` string carried as a value, which is the one way a step meets an input its type
  rules out (`First`, `AllOf` or `AnyOf` over a non-list).
- **The tap call design:** a declared call id in place of the URL a template spells out. A
  `number` slot type (slot-views point 4) waits on it.
- **One machinery for "a value picks what fills a region":** a state `c.iff` is a bake group
  chosen by `Activation.State` (`SurfaceGraph`, the flip); a set member picks its first holding
  clause and the set its present members (`MemberGraph`, a Scala-written `fh-group` wrapper).
  Both are "the first branch that holds renders", and tabs and `c.iff` over a variable are the
  same with a per-viewer value. With every decision a value expression, a region filled by a value
  (the branch a bool or a variable picks, or one node per id of a list) could serve all of them,
  and `SetNode` would become a card. ADR 0007's "a member container is a per-entity
  comprehension, not a gate" is the argument to answer. Goes with the regions round.
