# EXPECT: typecheck PASSES.
# An annotated import carries its static type. The typechecker sees through BOTH
# imports: the dump entity satisfies the library's row-polymorphic parameter,
# extra fields and all.
let lib = import "static/lib.ncl" in
let home = import "static/dump.ncl" in
({ out = (lib.toggle home.entities.light_plug).entity_id } : { out : String })
