# bad2.ncl with the trailing annotation REMOVED, and nothing else changed.
#
# `nickel typecheck` exits 0. The same wrong entity that bad2.ncl catches is
# invisible here, because typing in Nickel is opt-in per expression: outside an
# annotated block nothing is checked, and forgetting the annotation FAILS OPEN.
# There is no warning to notice.
#
# This is why every dashboard ends in `: _` (dash2.ncl). Asserted by ./claims.sh
# as an exit-0 claim -- the failure mode is that it starts passing.
let core = import "lib2/core.ncl" in
let c = import "lib2/comp.ncl" in
let render = import "lib2/render.ncl" in
let plug : { entity_id : String, domain : String }
  = { entity_id = "light.plug", domain = "light" }
in
(render.render
  (core.group c.grid [core.group c.grid [core.leaf (c.toggle plug)]])
  "root").root
