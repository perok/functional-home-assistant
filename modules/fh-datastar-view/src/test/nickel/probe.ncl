# Cursor fodder for lsp-probe.py --claims. Each line below is a probe site; the
# trailing `.` is where the cursor goes. This file does NOT evaluate — it is not
# meant to. Add your own line at the bottom and probe it.
let c = import "lib/components.ncl" in
let q = import "lib/query.ncl" in
let home = import "home.ncl" in
{
  a = q.,
  b = q.pipe.where,
  c = home.entities.light_hue_bibliotek.,
  d = home.entities.light_plug.,
  e = (q.builder.from home.lights).,
  f = (c.toggle home.entities.light_plug).,
  g = c.slider,
}
