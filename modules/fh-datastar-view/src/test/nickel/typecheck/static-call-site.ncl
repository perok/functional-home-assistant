# EXPECT: typecheck FAILS -- a wrong argument, caught BEFORE evaluation.
# This is what static typing buys, and it is real. It just cannot reach across
# a module boundary (see import-is-dyn.ncl).
let slider
  : [| 'Entity { entity_id : String } |] -> { card : String, entity_id : String }
  = match {
    'Entity e => { card = "slider", entity_id = e.entity_id },
  }
in
({ out = (slider ('Entity { entity_id = 5 })).entity_id } : { out : String })
