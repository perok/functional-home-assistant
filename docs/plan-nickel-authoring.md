# Plan: Nickel as the dashboard authoring language

**Status: exploration. Nothing here is implemented, and no decision has been
taken.** ADR 0006 (Pkl) still stands. This document describes the dashboard
structure as it exists in Nickel in the runnable spike at
`modules/fh-datastar-view/src/test/nickel/`, and the language constraints that
forced its shape. It is a current-state design sketch, not a migration plan —
iterate by changing the spike and this document together, and let the commits
carry the history.

Integration is deliberately out of scope: JVM interop, packaging, LSP wiring and
JSON parity with the renderer are all solvable-if-we-decide-to and none of them
inform the language question.

## The structure

Five layers, mirroring the Pkl library's split by audience (ADR 0015).

```
lib/hass.ncl        schema                     — what an entity is
lib/core.ncl        Cell / Node / Placeable    — the node vocabulary
lib/components.ncl  slider, toggle, button, …  — what a dashboard author calls
lib/query.ncl       { expr, pipe, builder }    — the candidate-set surface
home.ncl            generated dump             — what codegen emits
dashboard.ncl       the site                   — what a user writes
```

`lib/core.ncl` is not a Pkl-shaped habit carried over. Without it the query
builder would have to name the type the component library produces, and the only
way to avoid that dependency in Nickel is to write `Dyn` — a hole rather than a
signature. Splitting the node vocabulary out is what keeps every signature real.

### The dump: one contract per entity

Codegen emits, beside each entity, a contract naming exactly the fields that
entity has:

```nickel
E_light_plug | not_exported = {
  entity_id | String, domain | String, friendly_name | String,
},
E_light_hue_bibliotek | not_exported = {
  entity_id | String, domain | String, friendly_name | String, area_id | String,
  supported_color_modes | Array hass.ColorMode,
  colourTemp | hass.ColourTemp,        # PRESENT: declared, not optional
},

entities = {
  light_plug = { … } | E_light_plug,
  light_hue_bibliotek = { … } | E_light_hue_bibliotek,
},
lights = [entities.light_hue_bibliotek, entities.light_plug],
```

This is ADR 0013's design expressed without classes or nominal subtyping, and it
reproduces all three properties that matter:

- **per-entity type** — `light_plug.colourTemp` is an error, pointing at the
  record that lacks it
- **narrowing by presence** — `light_hue_bibliotek.colourTemp.min_kelvin` needs
  no guard and no `!!`
- **omission beats null** — an absent capability is absent, not `null`
- and **upcast stays free**: `home.lights | Array hass.LightEntity` passes, so
  generic code over the wide list still works

The alternative — one shared `hass.LightEntity` contract — also evaluates, and
is the obvious thing to write. It must not be used: editor completion is driven
by the **contract**, not the value, so it offers `colourTemp` on a light that
has none. At ~1069 entities that deletes the capability typing.

Two mechanical constraints codegen has to respect:

- Entity contracts must be **open** (`..`), or an entity carrying one extra
  attribute is rejected.
- The `owner` back-pointer must be `not_exported` (Pkl's `hidden`), or export
  does not terminate. `not_exported` is honoured by `nickel export` but **not**
  by `std.serialize`, so nothing downstream may serialize the dump from inside
  Nickel.

### Components

```nickel
slider     | SliderAxis -> core.Node
sliderGroup| SliderAxis -> Array SliderAxis -> core.Node
button     | String -> String -> core.Node
toggle     | hass.Entity -> core.Node
columns    | Number -> core.Placeable -> core.Placeable
fullWidth  | core.Placeable -> core.Placeable
```

`Placeable` is weaker than `Node` deliberately: a query's `set` node carries a
cell but no `card`, so `Node -> Node` is wrong and fails on a real dashboard.

`SliderAxis` is a **tagged ADT** — see "The union problem" below.

### Query: two styles, one fold

Both styles live in `lib/query.ncl` under separate namespaces and fold through
the same `lib/query/fold.ncl`, so "the two agree" is a real test rather than two
copies of the same code agreeing with themselves.

```nickel
# pipe (recommended)
q.pipe.from home.lights
|> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
|> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e))
|> q.pipe.otherwise c.toggle
|> q.pipe.build
|> c.fullWidth

# builder — `build` is a terminal FIELD, so no ()
((( q.builder.from home.lights).where pred).otherwise c.toggle).build
```

The builder style works (records are recursive, so `mk` calls itself as a
sibling field) but costs nested parens, because application is `f x` with no
method syntax. It buys no discoverability — see below — so the pipe style is the
recommended form. Pkl's `q.from(x).where(…).build()` is neither: it gets method
syntax for free.

`Fallback` is an ADT (`[| 'None, 'Render … |]`) rather than a nullable field, and
the emitted member omits `fallback` entirely when there is none.

## The type system, and which half to build on

Nickel has **static types** (`:`) and **contracts** (`|`). They do not compose,
and naming a record type moves it from the first into the second — so the choice
of which to write a library in is a real fork, not a style preference.

**Static typing is the stronger side, and it crosses module boundaries.**
`nickel typecheck` catches, before evaluation: a wrong argument at a call site
**through two imports** (a statically annotated library called with a value from
a statically annotated dump), and a **non-exhaustive `match` on an enum**. It has
ADTs, `forall`, and row polymorphism over records and enums. `nls` completion
follows function returns, through imports included.

Row polymorphism is what makes it practical here: a parameter written
`forall a. { entity_id : String; a }` accepts any entity that has those fields
whatever else it carries, so per-entity dump types and static component
signatures compose without inlining each entity's shape.

An import carries its type when the imported **module** is annotated at module
level. An unannotated module has apparent type `Dyn`, and the documented bridge
is `exp | Type` where Type is a record **type** — fields with `:`, no values, no
other metadata. A record *contract* in that position does not work; it stays
opaque. So even an unannotated dump is reachable, at one ascription per boundary.

**So the library should be static types, and contracts should be kept for what
they actually are: runtime validation of what the types cannot state** — the
slidable-domain predicate, and the generated dump's per-entity shape if codegen
cannot emit static types.

### The real limits

| | |
|---|---|
| No type alias | Naming a record type makes it a contract. Signatures inline their structure; one module-level annotation block per module plus row polymorphism keeps this bounded. |
| No recursive type | Follows from the above plus `let` not being recursive. A layout tree is recursive, so `children : Array Dyn` is **forced** — the one `Dyn` the language requires rather than permits. A recursive *contract* works fine as a record field. |
| `&` is untyped | Merge is `Dyn -> Dyn -> Dyn`. Rebuilding the record instead typechecks, and forces a better shape: separate placement from the node body, so one polymorphic `fullWidth` covers cards and query set nodes alike. |

### What the current contract-annotated `lib/` costs

The spike's `lib/` is still contracts, which is why converting it is the next
step:

- **`nickel typecheck` says nothing about it.** A file containing two wrong
  calls typechecks clean. Green means the tool did not look.
- **Completion does not follow calls**, so component return values are invisible
  in the editor. Not a missing-annotation problem — everything is annotated and
  hover reports real signatures at the same positions.

### The union problem

`slider` takes an entity **or** a colour-temperature axis. Nickel has an untagged
union (`std.contract.any_of`), but it is unusable here: entity contracts must be
open, and against open alternatives `any_of` commits to the first one and blames
its missing field instead of reporting that the value matched none.

So the union is a tagged ADT:

```nickel
SliderAxis = [| 'Entity SlidableEntity, 'Axis hass.ColourTemp |],
slider | SliderAxis -> core.Node = match { 'Entity e => …, 'Axis a => … },
```

Gains: an **eager** check (a mistagged or untagged argument fails at the call),
and `match` instead of sniffing with `std.record.has_field`.

Costs, both real and both visible at the call site:

- a tag on every call: `c.slider ('Axis light.colourTemp)`
- **no point-free composition**: `c.toggle` can be handed to `q.pipe.otherwise`
  directly, `c.slider` cannot, so a dashboard writes `(fun e => c.slider ('Entity e))`

Pkl pays neither: its union is untagged and dispatched with `on is hass.ColourTemp`.

A comparison's right-hand side (String for a state, Number for a battery level)
stays an untagged `any_of`, because it must serialize as a bare JSON scalar and a
tag would ride the wire. Its alternatives are closed scalars, the case `any_of`
handles correctly.

## Known blockers, unresolved

- **No dynamic import, in any form** — not interpolated, not glob, not a
  function. `site_default.pkl` documents `import*` as how dropping a
  `kitchen.dashboard.pkl` adds a dashboard with no line to add, and
  `ServerApp.scala` watches the directory specifically because of it. On Nickel
  that workflow moves into Scala.
- **Typed read-back into Scala is lost** — Nickel emits JSON, so decoding
  returns to circe.
- **A Rust core, not a JVM library** — pkl-core is in-process; Nickel means a
  subprocess or FFI.

## Where Nickel is genuinely better

- **Error messages.** Every one carries the definition site, the use site, and
  often a fix hint.
- **ADTs with exhaustiveness checking** — the only compile-time guarantee found
  anywhere in the spike, even if the library cannot reach it.
- **Merge priorities** (`default` / `force`): symmetric, explicit conflicts, vs.
  Pkl amending's last-writer-wins.
- **Compiles to WASM**, so a fully client-side editor is possible; Pkl
  (Truffle/GraalVM) cannot.

## Where it is worse, for this codebase

- The same class of variable-capture trap as Pkl, relocated and firing more
  often: `field = param` inside a record is self-reference (fix: `include`), and
  the punning shorthand `{ toggle }` silently declares an undefined field.
- `let` is not recursive, so the dump and the node vocabulary must each be one
  record.
- Merge is not amend: it requires agreement and does not override, so every
  overridable field needs `| default`.
- No method syntax, so a fluent builder costs parens.

## Evidence

Everything above is executable, in `modules/fh-datastar-view/src/test/nickel/`:

```bash
nickel test *.test.ncl        # 52 assertions
./typecheck-claims.sh         # 12 claims about what `nickel typecheck` catches
./lsp-probe.py --claims       # editor completion + hover, asked of nls directly
```

The three catch disjoint sets of mistakes — a typecheck-only error evaluates
fine, so `nickel test` is green while the claim is false. That gap is why the
suite is split three ways rather than being one command.

## Next

1. **Convert `lib/` from contracts to static types.** That is where the
   typechecker and editor completion come from, and the spike currently
   demonstrates the cost of not doing it rather than the benefit of doing it.
   Contracts stay for genuine runtime validation only.
2. Re-measure completion and `nickel typecheck` against the converted library —
   the claims in `typecheck-claims.sh` are written to be re-run.

Beyond that, the seam: `fh.view.build.SourceEval` is already the
authoring-language boundary, and giving it a second implementation is worth
doing on its own merits (it makes the renderer testable against a fake),
independent of whether Nickel is ever the second one.
