# ADR 0035 — The value language: one evaluator, typed reads, one absent value

A `ValueExpr` is an s-expression written as a pipeline: `source |> f |> g` is `(g (f source))`,
Clojure's `->`. `state |> Compare(eq, 21)` is `(compare = (state x) 21)`, and `List[a, b] |>
AllOf` is `(and a b)`. Every reading in a dashboard is one — a slot, a condition (a clause, a
guard, a state surface, `c.iff`), an order term, a CEL source's named input — and **one
evaluator** runs them all (`fh.view.runtime.Evaluator`), so a reading means the same thing
wherever it stands.

Because it is an s-expression, the literature on small expression languages applies as is and is
the first place to look before inventing a rule: the evaluator is SICP §4.1.7's `analyze`,
absence is SQL's `NULL` and Clojure's `nil`, and a comparison picks its meaning by type as CEL's
checker picks an overload.

## Values

Text, whole, real, bool, list, entity, series, markup, and `null`, the one absent value.

**Reads are typed.** A state is text; an attribute is the JSON's own value (`Long`, `Double`,
`String`, `Boolean`, a list, a map), never its printed text, so `attr(x)` reads `21` on a card and
in a condition alike (`Steps.read`). A typed read of one attribute converts only that one
until something has built the entity's whole attribute map (`EntityState.attribute`).

**A list source declares its item type** (`itemType`; Pkl's `ListRead` is the and/or of
conditions, so `bool`), and each item is held to it. The type is the list's own, never read off its items or
the step after it, so an empty list is typed as any other. Its size is the runtime's: `AllOf`
over none holds, `AnyOf` over none does not, `Count` is 0.

**`null` comes from two places only.** An entity read: an entity the store lacks, an attribute the
entity does not report, a JSON `null`. And a step with no result: `First` over an empty list,
`Percent`, `Fill` and `Duration` over text that is not a number, CEL returning `null`. A `once`
read is of *which entity this is* (a service picked by its domain), which it is whether or not
the store holds it, so it reads the entity's identity and never gives `null`.

**HA's no-value states, `unavailable` and `unknown`, are text, not `null`.** HA keeps them as
states and tests them with `has_value()`, and the library matches them as text (`activeOf`'s off
arms, the tap's refusal). Steps get the read as it is. They are one named set,
`EntityState.unavailableStates`, which two boundaries read: the bypass and an order key.

## Strictness: the implicit `Option`

**Every step is strict** in its input and its operands: over `null` it does not run, and gives
`null` (a Postgres `STRICT` function; Clojure's `some->`). That is the lift to `Option` — every
reading is, in effect, an `Option`, and the compiler is its `map` — kept implicit: an entity can
leave HA at any time, so every read would be an `Option` and the type would tell `validate`
nothing; and at runtime it is one `null` check per step with no allocation (an `Option` per
operand showed in `RenderBench` on the slider path).

**Three constructs see `null`, SQL's three:**

- `Match`: `null` is a value no case names, so it takes `otherwise` (`CASE x WHEN … ELSE`).
- and/or: Kleene's logic, as SQL's — `false` decides an and, `true` an or, else `null`. `Not` is
  strict (`NOT NULL` is `NULL`).
- A reading's `default`: at its end, `null` becomes the default (`COALESCE`, Clojure's `or`). It is
  the one way to lift `null` to a value. A mid-pipeline form (an `OrElse` step) waits for a card
  that needs one.

`WithUnit`'s unit operand is optional by its spelling, the one operand that is not strict.

A step does not guess a value for its card: `Percent` and `Fill` over what they cannot read give
`null`, and the `percent`/`fill` builders (`core/simple.pkl`) set `0 %` and `100%` as the
reading's `default`, so the card says what it looks like empty.

**CEL is strict too.** A `null` value never reaches a CEL step, since CEL over a whole entity that
is not there only errors. What is absent *inside* a value, an attribute, CEL's own machinery
handles (`has`, `? :`, optionals). A CEL source's named inputs reach it as CEL's `null`, and a
bool-typed input is the condition decided. A CEL result that is an optional
(`value.attr[?'u']`) is lifted at the step's boundary — `optional.none()` to `null`,
`optional.of(v)` to `v` — so the next step sees a value or `null`, never a wrapper.

## Comparison

`Compare` takes its meaning from the operand's type; only an attribute (`dyn`) waits for its
runtime value:

- **a number literal** compares on the number line, `eq` included, so `21` and `21.0` are equal
  (CEL's heterogeneous numeric equality, Scheme's `=`). A text side is parsed, and one that is not
  a number fails `eq` and every ordering, as HA's `numeric_state` ("`unavailable` doesn't pass");
  `ne` is `!eq`, as in CEL, where values of two types are unequal;
- **a text literal** compares as text, ordering numeric when both sides parse, else false;
- **a bool literal** is equality;
- **between two reads**, two numbers use the number line, anything else the text rule.

No other coercion: silent coercion is JS's `==`, where `"0" == 0` and `0 == ""` but `"0" != ""`.
Lisp names the comparison (`=` numeric, `string=` text); here the literal's type names it, so the
wire carries nothing extra. An order key compares by the same rule (`Evaluator.Keys`).

## Where a value leaves the language

Each boundary converts once:

- a **text slot** shows the value printed; with nothing to show, `null` or `""`, its `default`,
  else `""`;
- a **bool slot** shows `true` only for `true`; `null` or anything else shows its `default`, else
  `false`;
- a **condition** holds only on `true`, as SQL's `WHERE`;
- an **order key** sorts `null`, `""` and a no-value state last in either direction;
- a **CEL input** is CEL's `null`.

## The bypass

An entity read reads as is: the state's text, the word included, and its attributes as HA reports
them. `bypassUnavailable` is the one exception, and it makes sense in one place: a **text slot's
own source, reading an entity's state or the whole entity**. There a no-value state shows the
word and no step runs (`CASE WHEN state IN (…) THEN state ELSE <the reading> END`). It defaults
to `false`, how a read behaves everywhere else; the wire omits the default; `validate` refuses it
anywhere else (`ValueExpr.bypassError`) — an operand, a guard, a list item, a condition, an
order key, a CEL input, an attribute read, a bool slot. The library opts in on the builders that
show a state as text (`valueSlot`, the secondary line, the slider's readout).

An input type is not an output type: `q.entity(x).stateIs("off")` compares the state's text and
produces a bool, and over `unavailable` that comparison is meaningful (false), so it runs. The
trap this closes: with the bypass on by default, a bool slot from that condition got the string
`unavailable`, which a Mustache section and `attr:` take as true — `{{#off}}` rendered `OFF` on an
unavailable switch.

## The evaluator

- **One compiler, run once, at the build.** `Evaluator.compile` turns a `ValueExpr` into a
  closure `(subject, states, fetched) => Any` (SICP's `analyze`: the tree is walked once, at
  compile, never per call). `Dashboard.validated` walks the wire model into a second, compiled
  one (`Ready`, `Ready.Compile`), every expression beside its run, from `validate`'s own typing;
  the renderer, `MemberGraph`, `SurfaceGraph`, the order keys and set calls read only that tree,
  so nothing that runs can reach an expression with no run. An expression inside another (a CEL
  input, a guard, a list's item) compiles into its parent's run. One scope per dashboard
  (`Evaluator.Scope`) builds each CEL program the first time a `Cel.Key` asks; `Cel.asts`, the
  process-wide cache of checked trees, serves the re-validation a dump refresh does.
- **Two models, not one:** a class holding an expression or a node has a compiled twin, the rest
  are shared. One model parametrised by its expression type needed a cast between the stages;
  chimney's type-directed derivation has nowhere to pass a node's context; and compiling in the
  decoder would make a decoder fail on what is not a decoding error, outside the errors
  `validated` collects for the whole dashboard.
- **Early stops are picked at compile time:** `Present |> Count |> Compare(op, n)` counts until
  the answer is settled, `List |> AllOf/AnyOf` stops at the item that decides it, `First` is one
  pass with no sorted list.
- **One implementation per step.** The match on `Step` is exhaustive, so a step the evaluator does
  not run fails to compile. `Stage` steps are the renderer's, applied after the run.
- **`First` takes any list of ids**, its input type being the whole rule. It reads each id's
  entity as its order terms' subject.
- **What wakes a reading is what its reads name** (`ValueExpr.entities`): the source's entities,
  the operands', and those an order term names. No step adds any. A `Present` names its
  candidates, so `Present |> First` follows every candidate; a list of literal ids is text and
  names none.
- **Closures, not an opcode VM, Truffle nodes or a class per reading:** a reading is a few steps
  whose work lives in shared helpers, so a faster dispatch only shortens the gaps between them,
  2–4% of a render as profiled. The measurement, and what would reopen it, is in
  `docs/experiments.md` ("A faster dispatch for the value-language evaluator").
- **CEL is allowed wherever a value is read:** a clause, a guard, a state surface, an order term
  and a list's item compile it as a slot does, and a bool is the one rule where a bool is
  required (`ValueExpr.boolError`). The library's builders still write simple steps (ADR 0028);
  CEL in a guard is the author's choice, at about three times the allocation
  (`RenderBench.conditionCel` against `condition`). A CEL that fails reads as not holding.

## Open questions: what the type should tell `validate`

Two follow-ups, to assess together, since both ask how much of a reading's type `validate` should
hold an author to.

**Strict `Dyn`.** An attribute read and a CEL result are `dyn`, and `dyn` fits a text and a bool
slot alike; the boundaries above say what it does at runtime, so nothing renders wrongly, but a
list or a map attribute on a text slot, or a reading that does not end in a bool on a bool slot,
reaches the wall rather than failing the build. Strict, `dyn` would fit no slot: the text steps
(`Prefix` … `Duration`) still take it, so the readouts and `attrUnit` stand, and a reading that
ends in `dyn` (a bare `attr(…)`, CEL's `value.attr.x`) would need a conversion, CEL's
`string(…)` or a simple-tier step. Each `SlotType` would name the `ValueType` it takes and
`ValueType.fits` become conformance. Attribute types from the dump would spare most
conversions. It deletes `validate`'s one special case, `bareAttr`, which refuses a bare
attribute on a bool slot while the same value through CEL passes. The cost is the author's: CEL
in a text slot gains a `string(…)`, and the library builders, `writing-dashboards.md` and the
snapshots move.

**An `Option` type.** Absence stays implicit for now. Adding a type would look like this: `ValueType.Opt(t)`; CEL's
checker already types `value.attr[?'u']` as `optional_type(T)`, which `Cel.resultType` would
map to `Opt(T)`; a strict step over `Opt(T)` types as `Opt` of its result, so the lift is checked
rather than assumed; `default` (or an `OrElse` step) takes `Opt(T)` to `T`; and `ValueType.fits`
could refuse an `Opt` slot with no default — the one thing it buys: `validate` saying "this can be
empty and nothing says what to show". The cost: an entity can leave HA, so every entity read is
`Opt` unless absence stays outside the type, and then the type covers only CEL's optionals. To
be decided when a card needs it.

## Considered and rejected

- **Two evaluators, a condition interpreter beside the slot pipeline.** They disagreed: an
  attribute read `21.0` (its JSON text) in one and `21` in the other, an absent entity made a
  comparison false in one and read `""` in the other, and each skipped a step the other ran.
  Refusing the shapes where they differ would have kept both.
- **A stand-in state for an absent entity** (an empty `EntityState`). One absence had three
  spellings; a stand-in is Common Lisp's `NIL`, false, empty and absent at once, so `gethash`
  needs a second return value to tell them apart.
- **An error field per slot** for a reading whose entity has gone. An entity goes missing when HA
  drops it, and the dump refresh then rejects the new dump (`DumpRefresh.Rejected`, naming the
  dashboard), which is where to show it.
- **A type for a set's ids**, so `First` could tell ids from text. It would be a special case of
  its own; what wakes a reading is already decided by its reads.
