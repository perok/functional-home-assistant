# Nickel spike — a runnable version of the Pkl authoring layer

**This is design experimentation, not a second implementation.** Nothing here is
wired into the build, the server, or CI. It exists so the "should the dashboard
authoring language be Nickel instead of Pkl?" question can be answered by
running things rather than by argument.

It is a deliberately small subset of a real `pkl home`: the schema, a component
library (slider, subsliders, button, toggle, layout), a generated-dump analogue,
two query-builder styles, and a dashboard written against them.

## Run it

```bash
cd modules/fh-datastar-view/src/test/nickel

nickel test *.test.ncl        # 49 assertions
./typecheck-claims.sh         # 17 claims about what `nickel typecheck` catches
./lsp-probe.py --claims       # editor claims: completion + hover
nickel export dashboard.ncl   # the pipe-style dashboard as JSON
```

`nickel test` runs the fenced ```nickel blocks inside `| doc` metadata. `# => x`
asserts the value; `# => error: substring` asserts the failure. Every claim in
this README is executable.

**You need all three commands.** They catch disjoint sets of mistakes, and the
gap between them is the main finding below: a typecheck-only error evaluates
fine, so `nickel test` is green while the claim is false.

Requires `nickel` and `nls` on PATH (1.17.0 here). The Pkl equivalents live in
`../pkl/*.test.pkl` and run with `pkl test`; the two suites are independent.

## What is here

| File | |
|---|---|
| `lib/hass.ncl` | the ONE contract left: a runtime domain predicate |
| `lib/core.ncl` | placement (`{ cell, body }`) and the layout helpers |
| `lib/components.ncl` | slider / sliderGroup / button / toggle / columns / fullWidth |
| `lib/query.ncl` | **both** query styles, in separate namespaces |
| `lib/query/expr.ncl` | predicates (`prop`, `state`, `eq`, `lt`), shared |
| `lib/query/fold.ncl` | builder state → wire node, shared by both styles |
| `lib/query/pipe.ncl` | style 1 — plain functions chained with `\|>` |
| `lib/query/builder.ncl` | style 2 — record of functions, `.where(…).build` |
| `home.ncl` | the generated dump analogue |
| `dashboard.ncl` / `dashboard-builder.ncl` | the same dashboard in each style |
| `typesystem.test.ncl` | what the type system can and cannot express |
| `typecheck/` + `typecheck-claims.sh` | claims that only `nickel typecheck` can check |
| `probe.ncl` / `probe-static.ncl` + `lsp-probe.py` | editor evidence; probe **-static** for anything after a call |

`lib/core.ncl` splits placement from the node body for a reason the type system
forced — see below. It also plays the role ADR 0015 gives `lib/core/`: the
vocabulary a component author builds on.

## Types: the library is static types; contracts are runtime validation

Nickel has **static types** (`:`) and **contracts** (`|`). They do not mix —
naming a record type moves it from the first into the second, and a record
contract in a type position stays opaque. `lib/` is written in static types.
`lib/hass.ncl` holds the one contract left, and it earns its place: "this
entity's domain is one the slider can drive" is a fact about a value, not a
shape, so no type can state it.

**What that buys, measured:**

- **A capability mistake in a dashboard is a compile error.**
  `home.entities.light_plug.colourTemp` fails `nickel typecheck` with "this
  record lacks `colourTemp`", before anything evaluates
  (`typecheck/dashboard-capability.ncl`). Pkl's per-entity class guarantee,
  with a compile step behind it.
- **A non-exhaustive `match`** on the slider's tagged union is a typecheck
  error (`typecheck/adt-exhaustive.ncl`).
- **Completion follows function returns, across imports, two levels deep**, and
  hover prints full signatures, instantiating `forall`s.

Three things make it work, and each was a trap first:

| | |
|---|---|
| **Module annotation must be OUTERMOST** | `(let … in {…}) : T`, not `let … in ({…} : T)` — the latter parses as the annotation being inside the `let`, leaving the module's apparent type `Dyn`. Nothing warns; only *importers* degrade, and they degrade to "not checked", which looks like success. |
| **Row polymorphism on every entity parameter** | `forall a. { entity_id : String; a }` accepts any entity the dump generated, extra fields and all. Without it a component would have to name each entity's shape — impossible, since types cannot be named. |
| **`\| Dyn` to enter a dynamic position** | There is no implicit upcast. A static `: Dyn` is rejected; the contract application `\| Dyn` is the documented "typechecker off here". |

### The limits that survive

| | |
|---|---|
| **No type alias** | Signatures inline their structure. One module-level annotation block per module plus row polymorphism keeps it bounded, but the library roughly doubled in size. |
| **No recursive type** | There is no name to recurse through. A layout tree is recursive, so `children : Array Dyn` is **forced**. `let rec` gives recursive *contracts* — records and ADTs both — so the recursion is expressible, just invisible to the typechecker. |
| **`&` is untyped** | Merge is `Dyn -> Dyn -> Dyn`, and `std.record.update` is a Dictionary op that refuses a concrete record. Every update is an explicit rebuild — the most visible tax, and what makes `lib/query/pipe.ncl` verbose. |

**The sharp edge**: every `| Dyn` is a hole where checking *and* tooling stop —
hover inside one reports `Dyn`. Because the layout tree cannot be typed, a
dashboard's `children` list is exactly such a hole. Library internals and each
individual call keep their types; the tree assembling them does not.

It is also why the **builder query style now loses outright**: its `Chain` is a
recursive type, so `lib/query/builder.ncl` cannot be annotated at all and is
`Dyn` to every importer. The pipe style has no such problem.

### Where `Dyn` remains, and why

Only where the language forces it: the recursive `children`, and a comparison's
right-hand side (a String for a state, a Number for a battery level — static
types have no untagged scalar union, and the polymorphic workaround dies on
`Array` needing one element type). Both are noted at their definitions.

One position needed real work to remove it:

**`slider` takes an entity OR a colour-temperature axis.** Nickel has an
untagged union (`std.contract.any_of`), but it is unusable here: entity
contracts must be **open** (a generated entity carries whatever attributes it
has), and against open alternatives `any_of` commits to the first one and then
blames its missing field instead of reporting that the value matched none. So
the union is a tagged **ADT**:

```nickel
SliderAxis = [| 'Entity SlidableEntity, 'Axis hass.ColourTemp |],
slider | SliderAxis -> core.Node = match {
  'Entity entity => …,
  'Axis axis => …,
}
```

That buys an **eager** check — a mistagged or untagged argument fails at the
call, not deep in the body — and a `match` instead of sniffing with
`std.record.has_field`.

It costs two things, both real:

- **A tag at every call site**: `c.slider ('Axis light.colourTemp)`. Pkl writes
  the union directly and dispatches with `on is hass.ColourTemp`.
- **Point-free composition**: `c.toggle` can be passed straight to
  `q.pipe.otherwise`; `c.slider` cannot, and a dashboard writes
  `(fun e => c.slider ('Entity e))`.

**A comparison's right-hand side** is a String for a state and a Number for a
battery level. That one stays an untagged `any_of`, because it has to serialize
as a bare JSON scalar and a tagged ADT would put a tag on the wire. Its
alternatives are closed scalars, which is the case `any_of` handles correctly.

### Traps found while converting

- The **`field = param` self-reference** fired twice more here (six times total
  in this directory): `body = body` and `lhs = lhs` inside a record refer to the
  field, not the parameter. `include` fixes it where the value is unwrapped;
  where it is not (`lhs = (l | Dyn)`), the parameter has to be renamed.
- An annotation on a `let` does **not** survive `include` into an exported
  record — hover says `Dyn`. Record fields keep theirs.
- After the **first parse error** in a file, nls degrades to `Dyn` for
  everything downstream. This produced a wrong conclusion twice; it is why
  `probe-static.ncl` exists alongside the deliberately-unparseable `probe.ncl`.
- `RecordContract & customContract` looks right and fails with "non mergeable
  terms" the moment anything forces it. `std.contract.all_of` composes.
- Annotating the layout helpers `Node -> Node` broke `dashboard.ncl`, because a
  query's `set` node has a cell but no `card`. That is what drove placement out
  of the node body.

## The editor: what to test yourself

`./lsp-probe.py --claims` asks `nls` directly rather than relying on "no popup
appeared". Completion:

```
q.                                        -> [builder, expr, pipe]
q.pipe.                                   -> [build, case_of, from, otherwise, where]
home.entities.light_hue_bibliotek.        -> [area_id, colourTemp, domain, entity_id,
                                              friendly_name, supported_color_modes]
home.entities.light_plug.                 -> [domain, entity_id, friendly_name]
(c.toggle …).                             -> [body, cell]
(c.toggle …).body.                        -> [card, children, kind, readout, slots]
(core.fullWidth …).                       -> [body, cell]
…colourTemp.                              -> [max_kelvin, min_kelvin, owner]
```

1. **Per-entity typing works** — `light_plug` has no `colourTemp`, and
   completion does not offer it.
2. **Completion follows calls**, across imports and through a `forall`. The
   library's return values are discoverable.

The call sites are probed in `probe-static.ncl`, which parses. That matters:
measuring them in `probe.ncl`, which does not, returns `[]` and looks like a
language limitation. It is not — it is nls's parse-error recovery.

Hover prints whole signatures, instantiating the `forall`:

```
c.toggle        -> { entity_id : String, friendly_name : String, domain : String }
                   -> { cell : {…}, body : { kind : String, card : String, … } }
core.fullWidth  -> { cell : {…}, body : {…} } -> { cell : {…}, body : {…} }
```

…and collapses to `Dyn` in exactly two places, both included as controls: inside
a `| Dyn` region, and after a parse error.

## The two query styles

```nickel
# pipe
q.pipe.from home.lights
|> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
|> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e))
|> q.pipe.otherwise c.toggle
|> q.pipe.build
|> (fun set => core.fullWidth (core.place set))

# builder — `build` is a terminal FIELD, so it needs no ()
core.fullWidth
  (core.place
   ((((q.builder.from home.lights).where (q.expr.eq (q.expr.prop "domain") "light"))
    .case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e)))
    .otherwise c.toggle).build
```

The builder style works — `let rec` lets `mk` call itself, and the function
fields never break JSON export because only `.build` is ever forced. It has two
costs now, and the second is decisive: parens (application is `f x`, so every
step needs wrapping), and **it cannot be statically typed at all**, because
`Chain` is a recursive type. `lib/query/builder.ncl` is therefore `Dyn` to every
importer, and a dashboard written in that style gets no compile-time checking.

Both fold through the same `lib/query/fold.ncl`, so `query.test.ncl :: agree`
("both styles build the same node") is a real test rather than a tautology.

Pkl's `q.from(x).where(…).build()` is neither: it gets method syntax for free.

## Why `home.ncl` looks the way it does

Codegen gives each entity **its own type**, naming exactly the fields it has:

```nickel
light_plug : { entity_id : String, domain : String, friendly_name : String },
light_hue_bibliotek : { …, colourTemp : { min_kelvin : Number, … } },
```

That reproduces all three properties Pkl's nominal classes give — per-entity
type, narrowing by presence, omission beats null — without classes or
subtyping, and adds the one Pkl has: it is checked at compile time.

An earlier version of this spike used one *contract* per entity, which also
works at eval. Types are strictly better here, and the two cannot be mixed on
one value anyway. A single shared `LightEntity` would be worse than either: it
offers `colourTemp` on a light that has none.

`lights` carries the **common projection**, because an `Array` needs one element
type. That is the right answer rather than a limitation — a query over every
light should only see what every light has — and it is the same upcast Pkl gets
from subtyping, written out because codegen writes it.

## Traps, pinned

`traps.test.ncl` is the honest answer to "is Nickel more straightforward than
Pkl?". It is not — it has the same class of variable-capture trap, relocated,
and it fires more often. Each is a passing test:

- `field = param` inside a record is **self-reference**, not the parameter —
  records are recursive by default. Fix: `include param`, or rename the
  parameter where the value is wrapped. **Six occurrences** across this
  directory; comfortably the most repeated mistake here.
- The punning shorthand `{ toggle }` is worse: it declares an *undefined field*
  rather than referring to the outer binding, and surfaces much later.
- plain `let` is **not** recursive — but `let rec` is (nickel-lang/nickel#525),
  and it gives recursive records AND recursive ADTs. So recursion is a solved
  problem in the *contract* world; it is recursive **types** that cannot be
  written, because there is no way to name one.
- `&` is symmetric merge, **not** amend: it requires agreement and does not
  override, so every overridable field needs `| default`.
- A cyclic field must be `not_exported` (Pkl's `hidden`) or export does not
  terminate. And `not_exported` is honoured by `nickel export` but **not** by
  `std.serialize` — same value, one terminates and one hangs. (The static dump
  sidesteps this by giving `colourTemp.owner` a projection rather than a
  back-pointer.)

One more worth knowing: **laziness is a wash, not an argument.** A contract
violation on one field does not fail a read of another — and Pkl behaves
identically, which was measured separately. Neither language catches this
earlier than the other.

## Not covered

`import*`. Nickel has no dynamic, interpolated, or glob import in any form, and
`site_default.pkl` documents `import*` as the mechanism by which dropping a
`kitchen.dashboard.pkl` file adds a dashboard with no line to add —
`ServerApp.scala` watches the directory specifically because of it. On Nickel
that workflow has to be rebuilt in Scala rather than expressed in the language.

Also out of scope by design: JVM interop, packaging, LSP wiring, JSON parity
with the real renderer.
