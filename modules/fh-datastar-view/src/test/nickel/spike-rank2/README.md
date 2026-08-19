# The folded design — removing `Dyn` instead of annotating around it

The sibling `lib/` reaches `Dyn` 71 times. Every one of them traces to the same
root: three recursive structures (the layout tree, the expression AST, the query
member map) and no way to write a recursive type, because naming a type in
Nickel moves it into the contract world.

**A recursive type is not needed.** Nickel supports **rank-2 polymorphism**, so
each structure can be encoded as its own fold (Böhm–Berarducci): a tree IS the
function that consumes an algebra, `r` marks every recursive position, and the
type that has to be *written* is not recursive.

```nickel
# instead of the unwritable  Node = { …, children : Array Node }
forall r. { node : Body -> r, group : Body -> Array r -> r, set : … -> r } -> r
```

Measured against `lib/` + `../dashboard.ncl`:

| | `lib/` | `lib2/` |
|---|---|---|
| `Dyn` in the library | 71 | **0** |
| `Dyn` in the dashboard | 6 | **0** |
| lines | 415 | 479 |

The remaining `Dyn` matches in this directory are all in comments about the old
design.

## What it buys

A mistake nested inside the tree is now a typecheck error naming both the call
and the signature that requires the field (`bad2.ncl`) — in `../dashboard.ncl`
every child sits behind `| Dyn`, so the same mistake reaches evaluation. Hover
inside the tree reports the real instantiated signature rather than `Dyn`, and
completion follows calls, the layout helpers, capabilities and the query state.

It is also a *smaller* model. The recursion was only ever in the tree structure,
but `lib/` smeared it across every node type — a button carried
`children : Array Dyn` that is always `[]`. Splitting a node's own data (the
flat `Body`) from the tree shape means components and layout helpers never
mention the tree at all:

```nickel
toggle : forall a. { entity_id : String, friendly_name : String; a } -> Body
fullWidth : Body -> Body     # was  forall a. { cell, body : a } -> { cell, body : a }
```

## What it costs

- **The algebra is inlined at every occurrence**, because types cannot be named.
  That is where the +64 lines went, and hover on a tree combinator prints ~40
  lines. It falls on the library, never on a dashboard author.
- **The carrier must be non-recursive.** `render.ncl` produces a FLAT node table
  (`children : Array String`, ids derived from the tree path). Instantiating `r`
  at a recursive contract does not merely fail — it **crashes the typechecker
  with a stack overflow** (`renderB.ncl`, kept as evidence; not run by
  `claims.sh` because it would abort the script). Worth reporting upstream: a
  missing occurs check.
- **A query case renders a leaf.** `map` and `@` cannot PRODUCE polymorphic
  elements — only an array literal can (`produce-polymorphic*.ncl`) — so a
  query, which accumulates, cannot accumulate trees. `core.groupLeaves` and the
  `Body`-valued query state are both consequences.
- **No ADT may carry a tree.** `match` opens the `forall` and nothing
  re-generalises it (`enum-rank2.ncl`), in both directions. This is why a
  query's fallback is the last CASE rather than a `[| 'None, 'Render … |]`
  payload — which is arguably the better model anyway (a fallback IS a case
  whose condition always holds), but it was forced, not chosen.
- **Wire format changes**: the emitted JSON is a flat node array, not a nested
  tree. Plausibly a gain for a fragment-patching renderer — the ids are
  structural and therefore stable across renders — but it is a change.

## Running it

```bash
./claims.sh                 # 15 claims about what typechecks and what does not
nickel export dash2.ncl     # the ported dashboard
../lsp-probe.py spike-rank2/probe2.ncl 12 42     # completion, run from ../
```

`claims.sh` exists because `nickel test` cannot carry these: a typecheck-only
error evaluates fine, so a green doctest says nothing about whether the
typechecker looked.

## Files

| | |
|---|---|
| `lib2/core.ncl` | `Body`, layout helpers, `leaf`/`group`/`groupLeaves`/`set` |
| `lib2/comp.ncl` | components, returning flat `Body` |
| `lib2/expr.ncl` | the expression AST, folded; two interpreters over one value |
| `lib2/query.ncl` | pipe-style candidate sets, monomorphic state |
| `lib2/render.ncl` | the one interpreter: tree → flat node table |
| `dash2.ncl` | `../dashboard.ncl` ported |
| `rank2.ncl` | rank-2 works at all, in ten lines |
| `use2.ncl` / `bad2.ncl` | a deep tree typechecks / a deep mistake is caught |
| `enum-rank2.ncl`, `produce-polymorphic*.ncl` | the limits, as fixtures |
| `renderB.ncl` | the typechecker crash |
| `probe2.ncl` | cursor sites for `../lsp-probe.py` |
