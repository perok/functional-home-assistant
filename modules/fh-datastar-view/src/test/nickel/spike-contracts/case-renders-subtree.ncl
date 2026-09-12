# The one thing the contract design can express and the folded one cannot: a
# query case that renders a SUBTREE rather than a leaf.
#
# In the folded design a query accumulates, and neither `map` nor `@` can
# produce rank-2 elements (../spike-rank2/produce-polymorphic-*.ncl), so cases
# are restricted to flat `Body` values -- which is why `core.groupLeaves` exists
# there at all. Nothing restricts it here.
let core = import "lib3/core.ncl" in
let c = import "lib3/comp.ncl" in
let q = import "lib3/query.ncl" in
let e = import "lib3/expr.ncl" in
let home = import "../home.ncl" in
(q.from home.lights
 |> q.case_of (e.eq e.state (e.str "on"))
      (fun x => core.group core.grid [c.toggle x, c.button "More" "openPopup()"])
 |> q.otherwise c.toggle
 |> q.build) | core.Node
