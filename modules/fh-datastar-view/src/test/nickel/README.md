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

nickel test *.test.ncl        # 52 assertions
./typecheck-claims.sh         # 12 claims about what `nickel typecheck` catches
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
| `lib/hass.ncl` | schema — analogue of `lib/hass.pkl` |
| `lib/core.ncl` | `Cell` / `Node` / `Placeable` — the shared node vocabulary |
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
| `probe.ncl` + `lsp-probe.py` | editor evidence; `probe.ncl` does not evaluate |

`lib/core.ncl` exists for the reason ADR 0015 splits `lib/core/` from
`lib/components/`: without it, `query` would have to name the type `components`
produces, and the only way to dodge that dependency is to write `Dyn`.

## Types: the library should be static types, not contracts

Nickel has **static types** (`:`) and **contracts** (`|`). They do not mix —
naming a record type moves it from the first into the second, and a record
contract in a type position stays opaque.

**Static typing is the stronger side and it crosses module boundaries.**
`nickel typecheck` catches, before any evaluation:

- a wrong argument at a call site, **through two imports** — a statically
  annotated library called with a value from a statically annotated dump
  (`typecheck/import-carries-its-type.ncl`, `import-catches-a-bad-call.ncl`)
- **a non-exhaustive `match` on an enum** (`typecheck/adt-exhaustive.ncl`)

It has ADTs, `forall`, and row polymorphism over records and enums. **Row
polymorphism is what makes it practical here**: a parameter written
`forall a. { entity_id : String; a }` accepts any entity that has those fields,
whatever else it carries — so per-entity dump types and static component
signatures fit together without inlining each entity's shape.

An import carries its type when the imported **module** is annotated at module
level. An unannotated module has apparent type `Dyn`, and the documented bridge
is `exp | Type` where Type is a record **type** (fields with `:`, no values, no
other metadata). A record *contract* in that position does not work — that
distinction cost me a wrong conclusion, and `typecheck/dyn-bridge.ncl` now pins
it.

So: **write `lib/` in static types, and keep contracts for what they actually
are — runtime validation of things the type system cannot state** (the
slidable-domain predicate; the generated dump's per-entity shape, if codegen
cannot emit static types).

`lib/` in this spike is **still contract-annotated** — converting it is the next
step. That is why the sections below still describe the contract world's costs.

### The real limits

Three, all measured, and none of them fatal:

| | |
|---|---|
| **No type alias** | Naming a record type makes it a contract. Signatures inline their structure; a module-level annotation block keeps that to one place per module, and row polymorphism keeps parameters short. |
| **No recursive type** | Follows from the above plus `let` not being recursive. A layout tree is recursive, so `children : Array Dyn` is **forced** — the one `Dyn` the language requires rather than permits. A recursive *contract* works fine as a record field. |
| **`&` is untyped** | Merge is `Dyn -> Dyn -> Dyn`, so the amend idiom cannot appear in static code. Rebuilding the record instead does typecheck, and forces a better shape: separate placement from the node body, and one `fullWidth` works on cards and query set nodes alike, polymorphic in the body (`typecheck/merge-is-untyped-workaround.ncl`). |

### What a contract-annotated library costs

Which is what `lib/` is today, and the argument for converting it:

- **`nickel typecheck` says nothing about it.** `typecheck/lib-typechecks-clean.ncl`
  passes while containing two wrong calls. Green means the tool did not look.
- **Completion does not follow calls.** With static types it does, through
  imports included — measured.

### No `Dyn` in `lib/` — and what that cost

`Dyn` is a hole, not a signature, so there is none in `lib/`. Two positions
needed real work to remove it:

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

### What the annotations bought

- **Hover works** — `Dyn` becomes a real signature (`Placeable -> Placeable`).
- **A wrong argument fails at the call**, naming the annotation, rather than
  producing a set node the browser would choke on. Eval-time, when forced.
- **One real bug, immediately**: annotating the layout helpers `Node -> Node`
  broke `dashboard.ncl`, because a query's `set` node has a cell but no `card`.
  Hence the weaker `Placeable`.

Two traps found while annotating, both about trusting a hover:

- An annotation on a `let` does **not** survive `include` into the exported
  record — hover says `Dyn`. Record fields keep theirs. That is why
  `lib/components.ncl` is one record rather than a chain of `let`s.
- After the **first parse error** in a file, hover degrades to `Dyn` for
  everything downstream.

And one about composing contracts: `RecordContract & customContract` looks right
and fails with "non mergeable terms" the moment anything forces it.
`std.contract.all_of` is the combinator that composes.

## The editor: what to test yourself

`./lsp-probe.py --claims` asks `nls` directly rather than relying on "no popup
appeared". Completion, at six cursor sites in `probe.ncl`:

```
q.                                        -> [builder, expr, pipe]
q.pipe.                                   -> [build, case_of, from, otherwise, where]
home.entities.light_hue_bibliotek.        -> [area_id, colourTemp, domain, entity_id,
                                              friendly_name, supported_color_modes]
home.entities.light_plug.                 -> [domain, entity_id, friendly_name]
(q.builder.from home.lights).             -> []
(c.toggle home.entities.light_plug).      -> []
```

1. **Per-entity typing works.** `light_plug` has no `colourTemp`, and completion
   does not offer it.
2. **Completion is empty after any call** — so the component library's return
   values are invisible. Not a builder-style problem and not a missing
   annotation: everything in `lib/` is annotated and hover reports real
   signatures at the same positions.

   It is the contract/static split. With **static types** completion follows
   calls, including through an import — `(l.mk "x").` returns
   `['card', 'entity_id', 'readout']` where `l` is an annotated module. This is
   the strongest single argument for converting `lib/` to static types.

Given (2) as things stand, prefer the pipe style: same capability, fewer parens,
identical tooling support.

Hover, asked of `dashboard.ncl` (a file that parses):

```
q.pipe.where   -> expr.Expr -> fold.State -> fold.State
c.fullWidth    -> Placeable -> Placeable
c.slider       -> SliderAxis -> core.Node
c.button       -> String -> String -> core.Node
```

## The two query styles

```nickel
# pipe
q.pipe.from home.lights
|> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
|> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e))
|> q.pipe.otherwise c.toggle
|> q.pipe.build
|> c.fullWidth

# builder — `build` is a terminal FIELD, so it needs no ()
c.fullWidth
  ((((q.builder.from home.lights).where (q.expr.eq (q.expr.prop "domain") "light"))
    .case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e)))
    .otherwise c.toggle).build
```

The builder style works — records are recursive in Nickel, so `mk` can call
itself as a sibling field, and the function fields never break JSON export
because only `.build` is ever forced. Its cost is parens: application is `f x`
with no method syntax, so every step has to be wrapped before the next `.`.

Both fold through the same `lib/query/fold.ncl`, so `query.test.ncl :: agree`
("both styles build the same node") is a real test rather than a tautology.

Pkl's `q.from(x).where(…).build()` is neither: it gets method syntax for free.

## Why `home.ncl` looks the way it does

Codegen emits **one contract per entity**, next to the entity, declaring exactly
the fields that entity has:

```nickel
E_light_plug | not_exported = { entity_id | String, domain | String, friendly_name | String },
```

Applying a single shared `hass.LightEntity` contract also works, and is the
obvious thing to write — but completion is driven by the **contract**, not the
value, so it then offers `colourTemp` and `area_id` on a light that has neither.
At ~1069 entities that is the per-entity capability typing being deleted.
Per-entity contracts recover all three properties Pkl's nominal classes give
(per-entity type, narrowing by presence, omission beats null), without classes
or subtyping, and upcasting to the wide list stays free.

## Traps, pinned

`traps.test.ncl` is the honest answer to "is Nickel more straightforward than
Pkl?". It is not — it has the same class of variable-capture trap, relocated,
and it fires more often. Each is a passing test:

- `field = param` inside a record is **self-reference**, not the parameter —
  records are recursive by default. Fix: `include param`. Hit four times in
  ~150 lines.
- The punning shorthand `{ toggle }` is worse: it declares an *undefined field*
  rather than referring to the outer binding, and surfaces much later.
- `let` is **not** recursive, so Pkl's self-referencing `const e_x = new { … owner = e_x }`
  has no direct translation — the dump has to be one recursive record, and so
  does `lib/core.ncl`, whose `Node.children | Array Node` refers to a sibling.
- `&` is symmetric merge, **not** amend: it requires agreement and does not
  override, so every overridable field needs `| default`.
- A cyclic field must be `not_exported` (Pkl's `hidden`) or export does not
  terminate. And `not_exported` is honoured by `nickel export` but **not** by
  `std.serialize` — same value, one terminates and one hangs.

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
