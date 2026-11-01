# The same dashboard as ../spike-rank2/dash2.ncl and ../dashboard.ncl, written
# against contracts.
#
# It reads like the folded one -- no `| Dyn`, `c.toggle` point-free -- and there
# is no trailing annotation to remember, because the `| core.Node` at the end is
# a contract that CHECKS rather than a gate that switches checking on. Forgetting
# it loses validation loudly at eval, not silently.
#
# What is gone is when the checking happens: nothing below is examined until
# something forces it. `nickel typecheck` on this file is a no-op.
let core = import "lib3/core.ncl" in
let c = import "lib3/comp.ncl" in
let q = import "lib3/query.ncl" in
let e = import "lib3/expr.ncl" in
let home = import "../home.ncl" in
(core.group core.grid [
  # Lights that are on get a slider; the rest a toggle.
  (q.from home.lights
   |> q.where (e.eq (e.prop "domain") (e.str "light"))
   |> q.case_of (e.eq e.state (e.str "on")) (fun x => c.slider ('Entity x))
   |> q.otherwise c.toggle
   |> q.build),

  # A colour-temperature slider on a light that HAS the capability. Reading it
  # off `light_plug` instead is an EVAL error here, not a typecheck error.
  core.leaf (core.fullWidth { kind = "slider", card = "slider", slots = { label = "colour temperature" } }),
  c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp),

  # A slider with sub-sliders: an ordinary nested record, no encoding involved.
  c.sliderGroup
    ('Entity home.entities.light_hue_bibliotek)
    [('Entity home.entities.light_plug)],

  c.button "Close" "closePopup()",
]) | core.Node
