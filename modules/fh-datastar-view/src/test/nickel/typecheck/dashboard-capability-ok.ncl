# EXPECT: typecheck PASSES -- the control: this light HAS colourTemp.
let home = import "../home.ncl" in
let c = import "../lib/components.ncl" in
({ x = (c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp)).body.card } : { x : String })
