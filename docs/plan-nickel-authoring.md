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
lib/hass.ncl        runtime validation         — the one contract left
lib/core.ncl        placement + layout helpers — { cell, body }
lib/components.ncl  slider, toggle, button, …  — what a dashboard author calls
lib/query.ncl       { expr, pipe, builder }    — the candidate-set surface
home.ncl            generated dump             — what codegen emits
dashboard.ncl       the site                   — what a user writes
```

**The library is written in STATIC TYPES.** Contracts are kept for what they
are — runtime validation of facts the type system cannot state — and exactly one
survives (`lib/hass.ncl`: "this domain is one the slider can drive").

`lib/core.ncl` separates PLACEMENT (`{ cell, body }`) from the node body. That
is not a Pkl habit carried over; the type system forced it. `&` is untyped, so a
statically typed layout helper cannot amend a node — it has to rebuild the
concrete half and leave the body behind a type variable. The result is better
than the merge version: one `fullWidth` covers component cards and query set
nodes alike.

### The dump: one type per entity

Codegen gives each entity its own type, naming exactly the fields it has, and
annotates the module so the type survives `import`:

```nickel
({
  entities = { light_plug = { … }, light_hue_bibliotek = { … } },
  lights = [ … ],
} : {
  entities : {
    light_plug : { entity_id : String, domain : String, friendly_name : String },
    light_hue_bibliotek : { …, colourTemp : { min_kelvin : Number, … } },
  },
  lights : Array { entity_id : String, domain : String, friendly_name : String },
})
```

This is ADR 0013's design, and it reproduces all three properties that matter —
per-entity type, narrowing by presence, omission beats null — plus one Pkl does
not have: **it is checked before evaluation**. `home.entities.light_plug.colourTemp`
in a dashboard is a `nickel typecheck` error reading "this record lacks
`colourTemp`".

`lights` carries the COMMON projection, because an `Array` needs one element
type. That is the right answer rather than a limitation: a query over every
light should only see what every light has, and per-entity capabilities stay
reachable through `entities.<name>`. Same upcast Pkl gets from subtyping, spelled
out because codegen writes it.

Mechanical constraints codegen must respect:

- **The module annotation must be the OUTERMOST expression.** `(let … in {…}) : T`,
  not `let … in ({…} : T)` — the latter parses as the annotation being inside the
  `let`, leaving the module's apparent type `Dyn`. Nothing warns, and only
  importers degrade, silently, to unchecked.
- Emit types, not contracts. They cannot be mixed on one value, and a contract
  is invisible to the typechecker.

### Components

Every entity parameter is **row-polymorphic**, which is what makes a statically
typed component library practical at all:

```nickel
toggle : forall a. { entity_id : String, friendly_name : String; a } -> <placed node>
fullWidth : forall a. { cell : { classes : Array String }, body : a }
         -> { cell : { classes : Array String }, body : a }
```

`forall a. { entity_id : String; a }` accepts any entity the dump generated,
extra fields and all. Without it a component would have to name each entity's
shape — impossible, since Nickel types cannot be named.

Types being unnameable is the main tax: each signature spells its structure out,
and the module-level annotation block keeps that to one place per module. The
library roughly doubled in size against the contract version.

The slider's parameter is a **tagged ADT** — see "The union problem" below —
whose `match` the typechecker checks for exhaustiveness.

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
|> (fun set => core.fullWidth (core.place set))

# builder — `build` is a terminal FIELD, so no ()
((( q.builder.from home.lights).where pred).otherwise c.toggle).build
```

The builder style works (`let rec` lets `mk` call itself) but **cannot be
statically typed**: its `Chain` is a recursive type, and Nickel has no way to
name a type, so there is no recursive type to write. `lib/query/builder.ncl` is
therefore `Dyn` to every importer, and a dashboard in that style gets no
compile-time checking at all. That, not the nested parens, is what settles the
choice. Pkl's `q.from(x).where(…).build()` pays neither price: it gets method
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

**So the library is static types, and contracts are kept for what they actually
are: runtime validation of what the types cannot state.** Exactly one survives —
the slidable-domain predicate. The dump emits types, not contracts; the two
cannot be mixed on one value, and a contract is invisible to the typechecker.

One subtlety that cost real time: the module annotation must be the OUTERMOST
expression. `let x = … in ({…} : T)` — the natural way to put imports at the
top — parses as the annotation being inside the `let`, so the module's apparent
type is `Dyn`. Nothing warns; only importers degrade, and they degrade to "not
checked", which looks like success.

### The real limits

| | |
|---|---|
| No type alias | Naming a record type makes it a contract. Signatures inline their structure; one module-level annotation block per module plus row polymorphism keeps this bounded. |
| No recursive type | There is no name to recurse through. A layout tree is recursive, so `children : Array Dyn` is **forced**. `let rec` (nickel#525) gives recursive *contracts* — records and ADTs both — so recursion is expressible, just invisible to the typechecker. |
| `&` is untyped | Merge is `Dyn -> Dyn -> Dyn`. Rebuilding the record instead typechecks, and forces a better shape: separate placement from the node body, so one polymorphic `fullWidth` covers cards and query set nodes alike. |

### The sharp edge

There is no implicit upcast to `Dyn`: entering a dynamic position takes the
CONTRACT application `| Dyn`, which is the documented "typechecker off here".
Since the layout tree cannot be typed, a dashboard's `children` list is exactly
such a position — so **every `| Dyn` is a hole where checking and tooling both
stop** (hover inside one reports `Dyn`).

Library internals and each individual call keep their types. The tree that
assembles them does not. That is the honest ceiling on what static typing buys
a dashboard author here, and it is the thing to weigh against Pkl.

### What a contract-annotated library would cost, for contrast

The first version of this spike was written that way:

- **`nickel typecheck` says nothing about it.** A file containing two wrong
  calls typechecks clean. Green means the tool did not look.
- **Completion does not follow calls**, so component return values are invisible
  in the editor.

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
nickel test *.test.ncl        # 49 assertions
./typecheck-claims.sh         # 17 claims about what `nickel typecheck` catches
./lsp-probe.py --claims       # editor completion + hover, asked of nls directly
```

The three catch disjoint sets of mistakes — a typecheck-only error evaluates
fine, so `nickel test` is green while the claim is false. That gap is why the
suite is split three ways rather than being one command.

## Next

1. **Decide whether the `| Dyn` holes in the layout tree are acceptable.** That
   is the one place the static design gives less than Pkl, and it is structural.
2. If they are: port a second, larger dashboard to see whether the signature
   verbosity stays bounded at realistic size.

Beyond that, the seam: `fh.view.build.SourceEval` is already the
authoring-language boundary, and giving it a second implementation is worth
doing on its own merits (it makes the renderer testable against a fake),
independent of whether Nickel is ever the second one.
