# EXPECT: typecheck FAILS -- `light_plug` has no `colourTemp`.
# The payoff of a statically typed dump + library: an author's capability
# mistake is caught before evaluation, in the dashboard itself. This is the
# thing Pkl's per-entity classes give, now with a compile step behind it.
let home = import "../home.ncl" in
let c = import "../lib/components.ncl" in
({ x = (c.slider ('Axis home.entities.light_plug.colourTemp)).body.card } : { x : String })
