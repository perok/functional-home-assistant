# What the STATIC TYPES on lib/ actually buy, and what they cost.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/annotations.test.ncl
#
# The compile-time half of the answer is in typecheck-claims.sh — by definition
# it cannot be asserted here, since a typecheck-only error evaluates fine. What
# this file pins is the eval-time behaviour and the shape of the call sites.
{
  the_union_parameter_is_an_adt_not_dyn
    | doc m%"
      `slider` takes an entity OR a colourTemp axis. That was the one signature
      that wanted `Dyn`; it is a tagged ADT, so `nickel typecheck` checks the
      `match` is exhaustive and the body dispatches instead of sniffing for a
      field with `std.record.has_field`.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      [(c.slider ('Entity home.entities.light_plug)).body.card,
       (c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp)).body.card]
      # => ["slider", "slider"]
      ```
    "%
    = null,

  an_unknown_tag_is_rejected_eagerly
    | doc m%"
      Tag checking is eager, unlike a record contract's field checks. This is
      the concrete argument for the ADT over `std.contract.any_of`: the untagged
      union only complains once something forces a field, and against OPEN
      alternatives — which entity contracts must be — it blames the first
      alternative rather than saying the value matched none.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      c.slider ('Wrong home.entities.light_plug)
      # => error
      ```
    "%
    = null,

  the_adt_costs_point_free_composition
    | doc m%"
      The honest cost. `c.toggle` is entity -> node, so it can be handed to
      `case_of`/`otherwise` directly. `c.slider` cannot: its argument is tagged,
      so a dashboard writes the wrapper lambda. Pkl needs neither, because its
      union is untagged and dispatched with `on is`.

      ```nickel
      let c = import "lib/components.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set =
        q.pipe.from home.lights
        |> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => (c.slider ('Entity e) | Dyn))
        |> q.pipe.build
      in
      (std.array.at 0 set.members."light.plug".cases).node.body.card
      # => "slider"
      ```
    "%
    = null,

  a_typed_value_needs_a_contract_to_enter_a_dyn_position
    | doc m%"
      There is no implicit upcast to `Dyn`. A layout tree's `children` is `Array
      Dyn` (no recursive types), so putting a node in one takes `| Dyn` — a
      CONTRACT application, the documented "typechecker off here". A static
      ascription `: Dyn` is rejected outright
      (`typecheck/dyn-needs-contract-application.ncl`).

      This is the sharp edge of the whole design: every `| Dyn` in a dashboard
      is a place static checking stops.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      std.array.length [(c.toggle home.entities.light_plug | Dyn)]
      # => 1
      ```
    "%
    = null,

  the_static_side_costs_verbosity
    | doc m%"
      Types cannot be named, so every signature spells its structure out and a
      query step rebuilds its state field by field (neither `&` nor
      `std.record.update` is available). The library roughly doubled in size
      against the contract version for the same behaviour.

      The behaviour really is the same, which is what this asserts:

      ```nickel
      let c = import "lib/components.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set =
        q.pipe.from home.lights
        |> q.pipe.where (q.expr.eq (q.expr.prop "domain") "light")
        |> q.pipe.otherwise c.toggle
        |> q.pipe.build
      in
      [std.array.length set.candidates, std.array.length set.where]
      # => [2, 1]
      ```
    "%
    = null,
}
