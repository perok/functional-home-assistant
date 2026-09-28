# The mistake the old design could not catch: `plug` has no `friendly_name`,
# and the call is TWO levels down inside the tree. In ../dashboard.ncl every
# child sits behind `| Dyn`, so the equivalent error reaches evaluation.
#
# `nickel typecheck` reports it here, pointing at both the call and the
# signature that requires the field. Asserted by ./claims.sh.
let core = import "lib2/core.ncl" in
let c = import "lib2/comp.ncl" in
let render = import "lib2/render.ncl" in
let plug : { entity_id : String, domain : String }
  = { entity_id = "light.plug", domain = "light" }
in
((render.render
   (core.group c.grid [core.group c.grid [core.leaf (c.toggle plug)]])
   "root").root
  : String)
