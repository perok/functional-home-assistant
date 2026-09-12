let core = import "lib2/core.ncl" in
let c = import "lib2/comp.ncl" in
let rec Node = {
  cell | { classes | Array String },
  kind | String, card | String, readout | String,
  slots | { _ | String },
  children | Array Node | default = [],
} in
let render : (forall r.
    { node : { cell : { classes : Array String }, kind : String, card : String,
               readout : String, slots : { _ : String } } -> r,
      group : { cell : { classes : Array String }, kind : String, card : String,
                readout : String, slots : { _ : String } } -> Array r -> r }
    -> r) -> Node
  = fun t => t {
      node = fun b => { cell = b.cell, kind = b.kind, card = b.card,
                        readout = b.readout, slots = b.slots, children = [] },
      group = fun b kids => { cell = b.cell, kind = b.kind, card = b.card,
                              readout = b.readout, slots = b.slots, children = kids },
    }
in
let e : { entity_id : String, domain : String, friendly_name : String }
  = { entity_id = "light.plug", domain = "light", friendly_name = "Plug" } in
render (core.group c.grid [ core.leaf (core.fullWidth (c.toggle e)) ])
