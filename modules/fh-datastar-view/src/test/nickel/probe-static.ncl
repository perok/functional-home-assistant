# Probe sites in a file that PARSES and typechecks. This matters: nls degrades
# after the first parse error, so measuring completion in probe.ncl (which is
# deliberately unparseable) gives a false negative for anything below its first
# broken line.
#
# Each line is a complete expression; the probe cursor goes right after a `.`.
let c = import "lib/components.ncl" in
let core = import "lib/core.ncl" in
let q = import "lib/query.ncl" in
let home = import "home.ncl" in
({
  a = (c.toggle home.entities.light_plug).cell,
  b = (c.toggle home.entities.light_plug).body.card,
  d = (core.fullWidth (c.toggle home.entities.light_plug)).cell.classes,
  e = home.entities.light_hue_bibliotek.colourTemp.min_kelvin,
} : {
  a : { classes : Array String },
  b : String,
  d : Array String,
  e : Number,
})
