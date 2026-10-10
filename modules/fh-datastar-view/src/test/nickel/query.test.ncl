# Both query styles, side by side. Analogue of src/test/pkl/query.test.pkl.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/query.test.ncl
#
# Each doc block is a test; `# => x` asserts the value, `# => error: msg`
# asserts a substring of the failure.
{
  agree
    | doc m%"
      The two styles build the SAME node. This is the load-bearing test: if it
      passes, the choice between `|>` and `.where` is purely about how the call
      site reads, not about what rides the wire.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let pred = q.expr.eq (q.expr.prop "domain") "light" in
      let piped =
        q.pipe.from home.lights
        |> q.pipe.where pred
        |> q.pipe.build
      in
      let built = ((q.builder.from home.lights).where pred).build in
      piped == built
      # => true
      ```
    "%
    = null,

  candidates_are_ids
    | doc m%"
      Candidate selection happens at BUILD time, so the wire carries entity ids,
      never the whole dump record.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      (q.pipe.from home.lights |> q.pipe.build).candidates
      # => ["light.hue_bibliotek", "light.plug"]
      ```
    "%
    = null,

  members_are_per_entity
    | doc m%"
      One member entry per candidate, keyed by id — that is what lets a single
      light swap its own card without touching the others.

      ```nickel
      let c = import "lib/components.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set = q.pipe.from home.lights |> q.pipe.otherwise c.toggle |> q.pipe.build in
      std.record.fields set.members
      # => ["light.hue_bibliotek", "light.plug"]
      ```
    "%
    = null,

  case_render_is_applied_per_member
    | doc m%"
      `case_of` stores a RENDER FUNCTION and the fold applies it to each
      candidate, so every member carries its own already-rendered node.

      ```nickel
      let c = import "lib/components.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set =
        q.pipe.from home.lights
        |> q.pipe.case_of (q.expr.eq q.expr.state "on") c.toggle
        |> q.pipe.build
      in
      (std.array.at 0 set.members."light.plug".cases).node.body.slots.entity_id
      # => "light.plug"
      ```
    "%
    = null,

  filters_ride_the_wire
    | doc m%"
      A LIVE condition stays a predicate on the node; it is not resolved here.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set = q.pipe.from home.lights |> q.pipe.where (q.expr.lt q.expr.state 20) |> q.pipe.build in
      std.array.at 0 set.where
      # => { kind = "cmp", op = "lt", lhs = { kind = "prop", name = "state" }, rhs = 20 }
      ```
    "%
    = null,

  builder_build_needs_no_parens
    | doc m%"
      `build` is a terminal FIELD in the builder style, not a call.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      (q.builder.from home.lights).build.kind
      # => "set"
      ```
    "%
    = null,

  builder_function_fields_do_not_serialize
    | doc m%"
      The intermediate builder record holds FUNCTIONS, which would be a JSON
      export error — but laziness means only the forced `.build` is ever
      evaluated, so the style is safe.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      std.record.fields (q.builder.from home.lights)
      # => ["build", "case_of", "otherwise", "where"]
      ```
    "%
    = null,
}
