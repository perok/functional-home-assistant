# EXPECT: typecheck PASSES.
# The documented way to use a dynamic value inside a static block: `exp | Type`,
# where Type is a record TYPE (fields with `:`, no values, no other metadata) --
# NOT a record contract, which stays opaque to the typechecker.
#
# So even an unannotated dump is usable; it costs one ascription at the boundary.
let home = import "../home.ncl" in
let f : { entity_id : String, domain : String, friendly_name : String } -> String
  = fun e => e.entity_id
in
({
  out = f (home.entities.light_plug | { entity_id : String, domain : String, friendly_name : String }),
} : { out : String })
