# STYLE 2 — record of functions, so the chain reads left to right without pipes.
#
#   ((q.builder.from home.lights).where pred).build
#
# `mk` is self-referential. In the contract version that worked because records
# are recursive; here it is `let rec`, which is clearer about the intent and
# which the static side needs anyway.
#
# `build` is a terminal FIELD, so it needs no `()`, and the function fields never
# break serialization because only `.build` is ever forced.
#
# THE TYPE IS THE PROBLEM. `Chain` is recursive — each step returns another
# Chain — and a recursive TYPE cannot be written in Nickel: there is no type
# alias, so there is no name to refer to. `let rec` binds a recursive CONTRACT
# fine, but a contract is opaque to the typechecker, which is the whole thing we
# are trying to keep.
#
# So this module is the one place that stays dynamically typed, and its return
# is `Dyn`. That is not a style choice — see typecheck/no-recursive-type.ncl and
# the README. The pipe style has no such problem and is the recommended form.
let fold = import "fold.ncl" in
let rec mk = fun st => {
  where = fun pred =>
    mk {
      candidates = st.candidates,
      filters = st.filters @ [pred],
      cases = st.cases,
      fallback = st.fallback,
    },

  case_of = fun pred render =>
    mk {
      candidates = st.candidates,
      filters = st.filters,
      cases = st.cases @ [{ when = pred, include render }],
      fallback = st.fallback,
    },

  otherwise = fun render =>
    mk {
      candidates = st.candidates,
      filters = st.filters,
      cases = st.cases,
      fallback = 'Render render,
    },

  build = fold.emit st,
}
in
{
  from = fun entities =>
    mk { candidates = entities, filters = [], cases = [], fallback = 'None },
}
