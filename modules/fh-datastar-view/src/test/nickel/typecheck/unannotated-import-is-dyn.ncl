# EXPECT: typecheck FAILS -- "Found an expression of type `Dyn`".
#
# The limit of import-carries-its-type.ncl: an import only carries a type if the
# imported MODULE is annotated. `lib/query/builder.ncl` cannot be (its `Chain`
# is a recursive type, which Nickel cannot express), so its apparent type is
# `Dyn` and a static block cannot reach into it.
#
# This is why the builder query style is the one that loses, and it is a
# property of that module rather than of imports.
let b = import "../lib/query/builder.ncl" in
((b.from [] : { build : Dyn }))
