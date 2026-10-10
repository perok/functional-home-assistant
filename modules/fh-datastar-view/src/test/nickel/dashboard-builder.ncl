# The same dashboard in builder style, for side-by-side reading.
#
# NOT statically typed, and it cannot be: the builder's `Chain` is a recursive
# type (each step returns another Chain) and Nickel has no way to name a type,
# so there is no recursive type to write. See lib/query/builder.ncl.
#
# That is now the decisive argument between the two styles — not the parens.
let c = import "lib/components.ncl" in
let core = import "lib/core.ncl" in
let q = import "lib/query.ncl" in
let home = import "home.ncl" in
{
  title = "Home",
  card = {
    kind = "grid",
    children = [
      core.fullWidth
        (core.place
          ((((q.builder.from home.lights).where (q.expr.eq (q.expr.prop "domain") "light"))
            .case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e)))
            .otherwise c.toggle).build),

      c.button "Close" "closePopup()",
    ],
  },
}
