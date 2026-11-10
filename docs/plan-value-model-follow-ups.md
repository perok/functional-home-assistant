# Plan: what the value model left open

The open items of the slot-values stack (#657 → #667 → #658 → #666 → #669 → #670 → #672), whose
plans (`plan-slot-values.md`, `plan-one-compile.md`) are built and deleted; their decisions are
in ADRs 0028, 0034 and 0035. Nothing here is agreed yet: each item needs a design round before it
is built, and the order below is a proposal.

**State:** the small items are built (`value-model-small`); the design rounds are not started.

## Design rounds

- **An input is an `Expr`:** if `count()` and `p.history.of` answered a `slotMod.Expr`, an input
  would be an `Expr` or a literal or entity shorthand, and `wireInput` would lose its tally and
  fetch cases. With no conversion left, the wire field could take the author's name.

- **Typing answers a typed expression, and the compile reads it:** `ValueExpr.typed` keeps only the
  root's type and a flat list of CEL keys, so `Evaluator.build` takes the CEL programs as an
  iterator in that order, and compiles each nested expression (a CEL's input, a list item, a guard,
  an order term) by typing it again; conditions and node values are typed again too. And
  `Ready.Compile` can still fail after `validate` passed. Instead, typing answers a tree with each
  node's type, each CEL's key and program, whether it reads a fetch, and its entities. `validate`'s
  expression rules read that tree, and the compile lowers it and cannot fail, as a slot's run
  already sits beside its slot in `Ready.Slot`. With `validate`'s expression errors read off that
  tree, one error type (node, message, the CEL at fault) can replace the node-prefixed strings.
  Spike on `ValueExpr` and `Evaluator` first. The
  per-call match in `Steps.formatWith` and `compare` could then be picked at compile time, if a
  bench shows it costs anything.
- **Keys derived, not written:** eight functions (`ValueExpr.key`/`inputKey`, `Value.sourceKey`/
  `key`, `Step.key`, `Lit.key`, `SortTerm.key`, `ValueExpr.inputsKey`) spell an expression as the
  string that decides whether two slots share a signal, a once-cache entry or a `valueWhile`
  decision. A field added to a case and not to its key makes two expressions share one signal, and
  nothing fails. A derived circe `Encoder` written with `noSpacesSortKeys` holds every field; the
  signal name hashes it as it hashes the key today. Every signal hash changes once (goldens), and a
  plain state read keeps its `state` name.
- **The stage copies by chimney:** `Ready.Compile` copies the wire's fields by hand (15 of them for
  `Ready.Dashboard`). A spike compiled a chimney version of the `Dashboard` and `Component` copies:
  `intoPartial` with `enableMethodAccessors` copies the fields by name, givens map nodes, slots,
  surfaces and cells (regions recurse through the node given), and
  `enableUnusedFieldPolicyCheck(FailOnIgnoredSourceVal)` failed the build listing exactly the
  fields the stage drops, so a field added on either side fails to compile. A field read inside a
  computed one must be marked unused. Chimney's string errors lose nothing: `Dashboard.validated`
  already keeps only a compile failure's message. After the typed expressions the compile cannot
  fail, so the copy is a total `into`, and a failure there is `FHError.internal`. One tree typed by
  its stage (`Node[E]`, GHC's trees that grow) does not fit: four leaf
  kinds change between the stages (slots, conditions, orders, call targets), so it needs a type
  parameter per kind on every node class. ADR 0035's "Two models, not one" bullet gives a reason
  against chimney that the spike answers; it is rewritten with the change.
- **One traversal per step:** `Step.about`, `parts` and `operands` are three matches over the same
  operands; one traversal gives all three. With derived keys, a new step is its case, its entry in
  that traversal, `ValueType.after`, `Steps.formatWith`, its Pkl class and its `WireShapeSuite`
  pair. `Step.label` could name the wire kind instead, which changes the messages.
- **One way to say what a reading reads:** a `Reading` takes its entity read from its hidden
  `entityId`, `part`, `fetch`, `reads` and `bypassUnavailable`, or from `source` set directly,
  which then silently wins. Picked: `source` alone. The entity fields stay `EntityRead`'s, and a
  helper re-points a built reading at another entity where `(r) { entityId = … }` does today
  (about eight sites). Rejected: a `fixed` `source` beside one hidden override, which keeps two
  inputs under new names.
- **A test that a run answers its type:** every test dashboard's compiled runs, over the test
  house, each answering a value of its declared type. It catches typing and the evaluator drifting
  apart, which values held as `Any` would otherwise hide.
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
- **A cache, or one render per change set:** a leaf's patch is shared through `RenderCache`
  (one generation per node, single-flight, keyed by what the render read), because each session
  pulls on its own fiber at its own store version (ADR 0012). Most viewers are awake at once, so
  rendering each changed node once per recorded change set and handing the bytes to every session
  at that version may be simpler. Stragglers, resumes and per-viewer visibility are what the cache
  absorbs today; check what each would cost first. Measure with `RenderCacheContentionSuite` and
  `tickRender`.
- **The tap call design:** a declared call id in place of the URL a template spells out. A
  `number` slot type (slot-views point 4) waits on it.
