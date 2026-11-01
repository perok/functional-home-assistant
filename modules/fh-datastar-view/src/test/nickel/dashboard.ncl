# What a dashboard author writes — pipe style. Compare against
# src/main/resources/dashboards/site_default.pkl, which this mirrors.
#
# The whole file is inside ONE static annotation. That is what buys the
# compile-time checking: a wrong call here is a `nickel typecheck` error before
# anything evaluates, and `nls` completes through the library's return types.
# Drop the annotation and the file still works — it just stops being checked.
let c = import "lib/components.ncl" in
let core = import "lib/core.ncl" in
let q = import "lib/query.ncl" in
let home = import "home.ncl" in
({
  title = "Home",
  card = {
    kind = "grid",
    children = [
      # Lights that are on get a slider; the rest a toggle.
      #
      # `c.slider` needs the `'Entity` tag, so it cannot be passed point-free
      # the way `c.toggle` can. That is the price of the tagged union — see the
      # README.
      (q.pipe.from home.lights
       |> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
       |> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => (c.slider ('Entity e) | Dyn))
       |> q.pipe.otherwise (fun e => (c.toggle e | Dyn))
       |> q.pipe.build
       |> (fun set => core.fullWidth (core.place set))
       | Dyn),

      # A colour-temperature slider on a light that HAS the capability. No
      # guard and no `!!`: the per-entity TYPE in home.ncl proved it, and
      # `home.entities.light_plug.colourTemp` is a typecheck error.
      (c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp) | Dyn),

      (c.button "Close" "closePopup()" | Dyn),
    ],
  },
} : { title : String, card : { kind : String, children : Array Dyn } })
