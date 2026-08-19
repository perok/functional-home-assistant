# The fold both builder styles share: builder state -> the `set` node that rides
# the wire. Keeping it in one place is what makes "the two styles agree" a real
# test rather than a tautology about two copies of the same code.
#
# It also owns the types both styles are annotated against. No `Dyn`: `Render`
# can name `core.Node` because the node vocabulary lives in its own module.
let hass = import "../hass.ncl" in
let core = import "../core.ncl" in
let expr = import "expr.ncl" in
{
  Render = hass.Entity -> core.Node,

  Case = { when | expr.Expr, render | Render },

  # `'None` until `otherwise` is called. An ADT rather than a nullable field,
  # so "no fallback" is a state rather than a value the fold has to re-test.
  Fallback = [| 'None, 'Render Render |],

  State = {
    candidates | Array hass.Entity,
    filters | Array expr.Expr,
    cases | Array Case,
    fallback | Fallback,
  },

  # One entry per candidate. `fallback` is OMITTED when there is none, rather
  # than serialized as null.
  Member = { cases | Array { when | expr.Expr, node | core.Node }, fallback | core.Node | optional },

  Set = {
    kind | String,
    cell | core.Cell,
    candidates | Array String,
    where | Array expr.Expr,
    members | { _ : Member },
  },

  emit | State -> Set = fun st => {
    kind = "set",
    cell | default = {},
    candidates = std.array.map (fun e => e.entity_id) st.candidates,
    where = st.filters,
    members =
      std.array.fold_left
        (fun acc e =>
          std.record.insert
            e.entity_id
            (
              { cases = std.array.map (fun c => { when = c.when, node = c.render e }) st.cases }
              & (st.fallback |> match { 'None => {}, 'Render g => { fallback = g e } })
            )
            acc)
        {}
        st.candidates,
  },
}
