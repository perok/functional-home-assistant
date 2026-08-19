# Probe sites for lsp-probe.py, on the folded design. This file PARSES and
# typechecks on purpose: nls degrades after the first parse error, so a probe in
# a broken file reports a language limit that isn't there.
#
# Each entry is a complete expression; the cursor goes right after a `.`.
let c = import "lib2/comp.ncl" in
let core = import "lib2/core.ncl" in
let q = import "lib2/query.ncl" in
let e = import "lib2/expr.ncl" in
let home = import "../home.ncl" in
({
  a = (c.toggle home.entities.light_plug).card,
  b = (core.fullWidth (c.toggle home.entities.light_plug)).cell.classes,
  d = home.entities.light_hue_bibliotek.colourTemp.min_kelvin,
  f = e.toText (e.eq (e.prop "domain") (e.str "light")),
  g = (q.from home.lights).where,
} : {
  a : String,
  b : Array String,
  d : Number,
  f : String,
  g : Array String,
})
