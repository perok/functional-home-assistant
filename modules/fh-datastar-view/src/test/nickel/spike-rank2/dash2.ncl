# The same dashboard as ../dashboard.ncl, on the folded tree.
#
# The difference worth looking at is what ISN'T here: no `| Dyn` on any child,
# no `| Dyn` around the query, no `Array Dyn` in the annotation. Every call in
# this file is checked, including the ones nested inside the grid.
#
# `c.toggle` also goes point-free again -- the tag is only on `c.slider`, whose
# ADT survives because its payloads are entities rather than trees.
let core = import "lib2/core.ncl" in
let c = import "lib2/comp.ncl" in
let q = import "lib2/query.ncl" in
let e = import "lib2/expr.ncl" in
let render = import "lib2/render.ncl" in
let home = import "../home.ncl" in
(render.render
  (core.group c.grid [
    # Lights that are on get a slider; the rest a toggle. `otherwise` is the
    # last case, not a separate field.
    (q.from home.lights
     |> q.where (e.eq (e.prop "domain") (e.str "light"))
     |> q.case_of (e.eq e.state (e.str "on")) (fun x => c.slider ('Entity x))
     |> q.otherwise c.toggle
     |> q.build),

    # A colour-temperature slider on a light that HAS the capability. Reading it
    # off `light_plug` instead is a typecheck error, as before.
    core.leaf (core.fullWidth (c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp))),

    # A slider with sub-sliders: a real nested tree, checked to the leaves.
    c.sliderGroup
      ('Entity home.entities.light_hue_bibliotek)
      [('Entity home.entities.light_plug)],

    core.leaf (c.button "Close" "closePopup()"),
  ])
  "root")
  # The gate, not a claim about the shape. Typing is opt-in per expression:
  # DELETE this and the file typechecks green while checking nothing, with no
  # warning (gate-none.ncl). `_` is enough to open the typed block -- the
  # renderer's output type is pinned once, in lib2/render.ncl, where it belongs.
  : _
