# EXPECT: typecheck FAILS -- "this record lacks `entity_id`".
# The same two imports, called wrongly. This is real cross-module static
# checking, caught before evaluation.
let lib = import "static/lib.ncl" in
({ out = (lib.toggle { domain = "light" }).entity_id } : { out : String })
