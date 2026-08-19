# STYLE 1 — plain functions, chained with `|>`.
#
#   q.pipe.from home.lights
#   |> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
#   |> q.pipe.build
#
# The state argument comes LAST in every step, so partial application makes each
# step a `|>`-able function. That is also what the annotations say out loud:
# every step is `... -> State -> State`.
let hass = import "../hass.ncl" in
let expr = import "expr.ncl" in
let fold = import "fold.ncl" in
{
  from | Array hass.Entity -> fold.State = fun entities => {
    candidates = entities,
    filters = [],
    cases = [],
    fallback = 'None,
  },

  # `q & { filters = ... }` would CONFLICT: Nickel merge is symmetric and refuses
  # two definitions of one field. Pkl's amend would just override.
  where | expr.Expr -> fold.State -> fold.State =
    fun pred q => std.record.update "filters" (q.filters @ [pred]) q,

  case_of | expr.Expr -> fold.Render -> fold.State -> fold.State =
    fun pred render q =>
      std.record.update "cases" (q.cases @ [{ when = pred, include render }]) q,

  otherwise | fold.Render -> fold.State -> fold.State =
    fun render q => std.record.update "fallback" ('Render render) q,

  build | fold.State -> fold.Set = fun q => fold.emit q,
}
