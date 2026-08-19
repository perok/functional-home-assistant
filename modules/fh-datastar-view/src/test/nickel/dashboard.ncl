# What a dashboard author writes — pipe style. Compare against
# src/main/resources/dashboards/site_default.pkl, which this mirrors.
let c = import "lib/components.ncl" in
let q = import "lib/query.ncl" in
let home = import "home.ncl" in
{
  title = "Home",
  card = {
    kind = "grid",
    children = [
      # Lights that are on get a slider; the rest a toggle.
      #
      # `c.slider` cannot be passed point-free the way `c.toggle` can: its
      # argument is a tagged union, so it needs the `'Entity` wrapper. That is
      # the price of removing `Dyn` from its signature — see the README.
      q.pipe.from home.lights
      |> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
      |> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e))
      |> q.pipe.otherwise c.toggle
      |> q.pipe.build
      |> c.fullWidth,

      # A colour-temperature slider on a light that HAS the capability. No
      # guard and no `!!`: the per-entity contract in home.ncl proved it.
      c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp),

      c.button "Close" "closePopup()",
    ],
  },
}
