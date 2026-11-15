# ../spike-rank2/bad2.ncl, contract-style: the same wrong entity, two levels
# down inside the tree.
#
# The message is as good as the static one -- it names the requirement and the
# offending record. What differs is WHEN: `nickel typecheck` exits 0 here, the
# editor shows nothing (./claims.sh asserts both), and the error appears only
# when something forces the value.
let core = import "lib3/core.ncl" in
let c = import "lib3/comp.ncl" in
let plug = { entity_id = "light.plug", domain = "light" } in
(core.group core.grid [core.group core.grid [c.toggle plug]]) | core.Node
