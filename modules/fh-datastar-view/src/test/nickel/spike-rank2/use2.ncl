# A three-deep tree, authored across two imports, fully typed, zero `Dyn`.
#
# The annotation is on `.root : String` rather than on the tree: any concrete
# type keeps the file in a STATIC context, which is all that is needed for the
# calls inside to be checked -- and it avoids restating the algebra.
let core = import "lib2/core.ncl" in
let c = import "lib2/comp.ncl" in
let render = import "lib2/render.ncl" in
let e : { entity_id : String, domain : String, friendly_name : String }
  = { entity_id = "light.plug", domain = "light", friendly_name = "Plug" }
in
((render.render
   (core.group c.grid [
     core.leaf (core.fullWidth (c.toggle e)),
     core.group c.grid [core.leaf (c.toggle e)],
   ])
   "root").root
  : String)
