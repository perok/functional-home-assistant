# The same dashboard in builder style, for side-by-side reading. The parens are
# the point: `f x` application means every step needs wrapping before the `.`.
let c = import "lib/components.ncl" in
let q = import "lib/query.ncl" in
let home = import "home.ncl" in
{
  title = "Home",
  card = {
    kind = "grid",
    children = [
      c.fullWidth
        ((((q.builder.from home.lights).where (q.expr.eq (q.expr.prop "domain") "light"))
          .case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e)))
          .otherwise c.toggle).build,

      c.button "Close" "closePopup()",
    ],
  },
}
