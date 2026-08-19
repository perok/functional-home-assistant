# STYLE 2 — record of functions, so the chain reads left to right without pipes.
#
#   ((q.builder.from home.lights).where pred).build
#
# Works because records are RECURSIVE in Nickel, so `mk` can call itself as a
# sibling field; a `let` could not. `build` is a terminal FIELD, so it needs no
# `()`, and the function fields never break serialization because only `.build`
# is ever forced. The `Chain` contract is recursive for the same reason.
#
# The cost is parens: application is `f x` with no method syntax, so every step
# must be wrapped before the next `.`.
let hass = import "../hass.ncl" in
let expr = import "expr.ncl" in
let fold = import "fold.ncl" in
{
  Chain = {
    where | expr.Expr -> Chain,
    case_of | expr.Expr -> fold.Render -> Chain,
    otherwise | fold.Render -> Chain,
    build | fold.Set,
  },

  mk | fold.State -> Chain = fun st => {
    where = fun pred => mk (std.record.update "filters" (st.filters @ [pred]) st),
    case_of = fun pred render =>
      mk (std.record.update "cases" (st.cases @ [{ when = pred, include render }]) st),
    otherwise = fun render => mk (std.record.update "fallback" ('Render render) st),
    build = fold.emit st,
  },

  from | Array hass.Entity -> Chain = fun entities =>
    mk { candidates = entities, filters = [], cases = [], fallback = 'None },
}
