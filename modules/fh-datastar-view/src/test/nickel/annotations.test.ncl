# What the type annotations on lib/query and lib/components actually buy.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/annotations.test.ncl
#
# They are CONTRACTS (`|`), not static types (`:`), and that is forced: naming a
# record type moves it into the contract world, and a static annotation cannot
# then mention it. Everything below is about what changes as a result.
{
  annotations_catch_a_wrong_argument
    | doc m%"
      `where` is annotated `Expr -> State -> State`, so handing it a bare string
      fails at the call rather than producing a set node with garbage in
      `where` that only the browser would notice.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      (q.pipe.from home.lights |> q.pipe.where "domain == light" |> q.pipe.build).where
      # => error: contract broken by the caller
      ```
    "%
    = null,

  but_only_when_the_result_is_forced
    | doc m%"
      The same wrong call, never forced, is silent. A function contract is
      checked when the value flows, not when the call is written — so this is
      not a compile-time guarantee, it is a better runtime failure.

      ```nickel
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let bad = q.pipe.from home.lights |> q.pipe.where "domain == light" in
      "no error yet"
      # => "no error yet"
      ```
    "%
    = null,

  typecheck_still_says_nothing
    | doc m%"
      Worth being blunt about: annotating everything did NOT move any of this
      into `nickel typecheck`, which still exits 0 on the whole directory
      including the broken call above. Contracts are an eval-time mechanism.
      The annotations buy hover and a better error, not a compiler.

      ```nickel
      let q = import "lib/query.ncl" in
      std.is_record q.pipe
      # => true
      ```
    "%
    = null,

  the_union_parameter_is_an_adt_not_dyn
    | doc m%"
      `slider` takes an entity OR a colourTemp axis. That was the one signature
      that needed `Dyn`; it is a tagged ADT now, so both calls are checked on
      the way in and the body dispatches with `match` rather than sniffing for
      a field with `std.record.has_field`.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      [(c.slider ('Entity home.entities.light_plug)).card,
       (c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp)).card]
      # => ["slider", "slider"]
      ```
    "%
    = null,

  an_unknown_tag_is_rejected_eagerly
    | doc m%"
      Tag checking is EAGER, unlike a record contract's field checks. This is
      the concrete argument for the ADT over `std.contract.any_of`: the untagged
      union only complains once something forces a field, and then blames the
      FIRST alternative rather than saying "matched none of them".

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      c.slider ('Wrong home.entities.light_plug)
      # => error: tag not in the enum type
      ```
    "%
    = null,

  the_adt_costs_point_free_composition
    | doc m%"
      The honest cost. `c.toggle` is `Entity -> Node`, so it can be handed to
      `case_of`/`otherwise` directly. `c.slider` cannot: its argument is tagged,
      so a dashboard has to write the wrapper lambda. Pkl needs neither, because
      its union is untagged and dispatched with `on is`.

      ```nickel
      let c = import "lib/components.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set =
        q.pipe.from home.lights
        |> q.pipe.case_of (q.expr.eq q.expr.state "on") (fun e => c.slider ('Entity e))
        |> q.pipe.build
      in
      (std.array.at 0 set.members."light.plug".cases).node.card
      # => "slider"
      ```
    "%
    = null,
}
