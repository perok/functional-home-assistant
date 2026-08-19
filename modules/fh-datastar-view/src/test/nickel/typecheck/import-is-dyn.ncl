# EXPECT: typecheck FAILS -- an `import` is `Dyn` inside a static block.
# The decisive limitation: a statically-typed library cannot be called with a
# value from a generated dump, which is the entire use case.
let home = import "../home.ncl" in
let f : { entity_id : String } -> String = fun e => e.entity_id in
({ out = f home.entities.light_plug } : { out : String })
