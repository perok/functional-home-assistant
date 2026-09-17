# Pipe-style candidate sets. Same shape as ../../spike-rank2/lib2/query.ncl --
# 35 lines of code there too, under 64 lines of written type. Here the type is
# five lines of `State` and one contract per function.
#
# The difference that is not just line count: **a case may render a whole
# subtree.** The folded version's cases can only render a LEAF, because `map`
# and `@` cannot produce rank-2 elements, so a query -- which accumulates --
# cannot accumulate trees. That restriction comes from the encoding, not from
# the domain, and it is absent here: `case_of` takes any `Entity -> Node`.
let core = import "core.ncl" in
let expr = import "expr.ncl" in
{
  State = {
    candidates | Array core.Entity,
    where | Array String,
    # A function contract is checked on APPLICATION, so a case's renderer is
    # covered too -- no `Dyn` is needed anywhere in this design.
    cases | Array { when | String, render | core.Entity -> core.Node },
  },

  from | Array core.Entity -> State
    = fun entities => { candidates = entities, where = [], cases = [] },

  where | expr.Expr -> State -> State
    = fun pred q => std.record.update "where" (q.where @ [expr.toText pred]) q,

  case_of | expr.Expr -> (core.Entity -> core.Node) -> State -> State
    = fun pred render q =>
      std.record.update "cases" (q.cases @ [{ when = expr.toText pred, include render }]) q,

  # A fallback IS a case whose condition always holds.
  otherwise | (core.Entity -> core.Node) -> State -> State
    = fun render q =>
      std.record.update "cases" (q.cases @ [{ when = "true", include render }]) q,

  build | State -> core.Node
    = fun q =>
      { body = { kind = "set" },
        candidates = std.array.map (fun e => e.entity_id) q.candidates,
        where = q.where,
        members =
          std.array.fold_left
            (fun acc e =>
              std.record.insert
                e.entity_id
                (std.array.map (fun c => { when = c.when, node = c.render e }) q.cases)
                acc)
            {}
            q.candidates },
}
