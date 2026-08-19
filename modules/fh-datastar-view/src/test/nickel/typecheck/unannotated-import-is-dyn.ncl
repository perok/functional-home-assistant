# EXPECT: typecheck FAILS -- "Found an expression of type `Dyn`".
# The limit of the above: an import only carries a type if the imported MODULE
# is annotated. `../home.ncl` is contract-annotated, so its apparent type is
# `Dyn` and a static block cannot reach into it.
#
# This is a statement about that module, NOT about imports in general --
# see import-carries-its-type.ncl. Fixable two ways: annotate the module
# (static/dump.ncl), or bridge at the use site (dyn-bridge.ncl).
let home = import "../home.ncl" in
let f : { entity_id : String } -> String = fun e => e.entity_id in
({ out = f home.entities.light_plug } : { out : String })
