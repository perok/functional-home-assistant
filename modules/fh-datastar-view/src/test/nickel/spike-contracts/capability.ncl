# The capability mistake: a colour-temperature slider on a light that has no
# colour temperature.
#
# This is the case the whole comparison turns on. In the static designs it is a
# TYPECHECK error -- `../typecheck/dashboard-capability.ncl` and the folded
# dash2.ncl both refuse it before anything evaluates, and the editor underlines
# it as you type. Here the dump is still per-entity typed, so the field genuinely
# is absent, but nothing looks until evaluation forces it.
let c = import "lib3/comp.ncl" in
let home = import "../home.ncl" in
c.slider ('Axis home.entities.light_plug.colourTemp)
