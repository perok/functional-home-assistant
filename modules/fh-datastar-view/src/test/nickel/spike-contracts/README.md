# The contract design — Nickel used the way Nickel is meant to be used

The other two designs in this directory are written in **static types** (`:`).
This one is written in **contracts** (`|`), which is what the manual recommends
for configuration data and what the ecosystem actually does — nickel-kubernetes
generates contracts from the Kubernetes JSON schemas and never types a manifest.

It exists to answer one question: **are we fighting the language?** Partly yes.
Everything the folded design had to invent disappears here.

## What disappears

**The recursive type problem is not a problem.** A contract may refer to itself,
because a record's fields see their siblings:

```nickel
Node = { body | Body, children | Array Node | default = [] },
```

Three lines, in place of the Böhm–Berarducci fold, the inlined algebra at every
signature, and the flat node table the fold forced (`../spike-rank2/README.md`).

With it go all four of the folded design's limits:

| folded design | here |
|---|---|
| an ADT cannot carry a tree | `expr.ncl` is a recursive ADT with recursive payloads |
| `map`/`@` cannot produce polymorphic elements | ordinary array code |
| a query case renders a LEAF only | it renders a subtree (`case-renders-subtree.ncl`) |
| the carrier must be non-recursive, or nickel **stack-overflows** | nested JSON, which is what the renderer wants |

Measured, against the same dashboard:

| | `lib/` | `lib2/` folded | `lib3/` contracts |
|---|---|---|---|
| library lines | 415 | 479 | **176** |
| `Dyn` in the library | 71 | 0 | **0** |
| `Dyn` in the dashboard | 6 | 0 | **0** |
| the query, all files | 225 | 110 | **50** |

The query is the sharpest single comparison, because all three write the same 32
lines of code: `lib2/query.ncl` carries 64 lines of written type above them,
`lib3/query.ncl` carries six.

## What it costs — the whole cost, in one row

**Nothing is checked until something forces it, and the editor says nothing.**

```
                              nickel typecheck   nls underlines it
wrong entity, 2 levels deep
  ../spike-rank2/bad2.ncl        error              yes
  ./bad3.ncl                     exit 0             NO
missing capability
  ../typecheck/dashboard-…       error              yes
  ./capability.ncl               exit 0             NO
```

Both rows are asserted by `./claims.sh`, the editor half through
`../lsp-probe.py --diagnostics`, which reports what `nls` actually publishes.
`nls` typechecks; it does not evaluate, so a contract that will fail is
invisible while you write it.

The errors themselves are as good as the static ones once they arrive — they
name the requirement and the offending record, with both source spans. It is
purely a question of *when*: `nickel export`, not the editor.

## Why there is no middle design

The manual's advice — types for functions, contracts for data — reads like a
recipe for this domain, since components are functions and the tree is data. It
does not compose, and both halves are fixtures here:

- `hybrid-type-mentions-contract.ncl` — the moment a static signature mentions
  the tree contract: *"Static types and contracts are not compatible"*.
- `hybrid-dyn-tree.ncl` — so type the tree combinators `Dyn -> Dyn` instead.
  Component calls stay checked, which is the good half, but `Dyn` is not a top
  type: every child needs an explicit `| Dyn`, and so does every library return.
  `hybrid-dyn-tree-without.ncl` shows they cannot just be deleted.

That second one *is* `../dashboard.ncl` and its six `| Dyn`. The three designs
in this directory are therefore the three available points, not three tastes:
`| Dyn` noise, the fold, or eval-time.

## What is unchanged by the choice

The `field = param` self-reference trap fired again while writing this
(`query.ncl`, twice, `include render`), bringing it to eight occurrences across
the directory. It is a property of Nickel records, not of a typing style, and no
design here escapes it.

Per-entity dump typing is also unchanged: `../home.ncl` is shared, and
`light_plug.colourTemp` is genuinely absent in every design. Only the moment of
complaint moves.

## Running it

```bash
./claims.sh                  # 15 claims; the editor ones take ~10s each
nickel export dash3.ncl      # the dashboard, as nested JSON
```

## Files

| | |
|---|---|
| `lib3/core.ncl` | `Body`, the recursive `Node`, `Entity`, layout helpers |
| `lib3/comp.ncl` | components, returning whole `Node`s again |
| `lib3/expr.ncl` | the AST as a recursive ADT; `toText` and `depth` as plain recursion |
| `lib3/query.ncl` | pipe-style candidate sets; cases may render subtrees |
| `dash3.ncl` | the same dashboard as `../dashboard.ncl` and `../spike-rank2/dash2.ncl` |
| `bad3.ncl`, `capability.ncl` | the two mistakes that now wait for eval |
| `case-renders-subtree.ncl` | the folded design's restriction, absent |
| `hybrid-*.ncl` | why the middle design does not exist |
