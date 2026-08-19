# What Nickel's type system can and cannot express — measured, not assumed.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/typesystem.test.ncl
#
# The short version: Nickel has TWO systems that do not mix — static types (`:`)
# and contracts (`|`) — and a dashboard library needs features that live on
# opposite sides. The static side has a real typechecker, ADTs with exhaustive
# `match`, and polymorphism. The contract side is the only one that survives a
# module boundary. `lib/` is therefore contracts, and everything the static side
# offers is unavailable to it.
#
# Each block below is self-contained so it can be read as evidence on its own.
{
  # ---- what the STATIC side gives you --------------------------------------

  static_typecheck_catches_a_wrong_argument
    | doc m%"
      Inside a static block, a bad call is caught by `nickel typecheck` BEFORE
      any evaluation — the only thing in this directory that is
      (`typecheck/static-call-site.ncl`).

      And it has to be, because EVAL misses it: the static annotation degrades
      to a contract, contracts are lazy, and nothing here forces `entity_id`.
      So the wrong call below evaluates happily. This is the argument for
      running `./typecheck-claims.sh` and not trusting a green `nickel test`
      alone — the two tools catch disjoint sets of mistakes.

      ```nickel
      let slider : { entity_id : String } -> { card : String }
        = fun e => { card = "slider" }
      in
      ({ out = (slider { entity_id = 5 }).card } : { out : String }).out
      # => "slider"
      ```
    "%
    = null,

  adt_match_is_exhaustiveness_checked
    | doc m%"
      A missing `match` arm on an enum is a REAL static error — `nickel
      typecheck` exits 1 with "missing row `Axis`" (see
      `typecheck/adt-exhaustive.ncl` and `./typecheck-claims.sh`).

      With a CONTRACT annotation, as `lib/` must use, the same mistake is caught
      only when that variant is actually reached:

      ```nickel
      let partial | [| 'Entity String, 'Axis Number |] -> String = match {
        'Entity e => e,
      } in
      partial ('Axis 1)
      # => error: unmatched pattern
      ```
    "%
    = null,

  adt_tags_are_checked_eagerly
    | doc m%"
      Unlike a record contract, whose field checks are lazy, an enum contract
      checks the tag as soon as the value is passed. This is why `c.slider`
      takes a tagged union rather than `std.contract.any_of`.

      ```nickel
      let f | [| 'A { x | Number }, 'B { y | Number } |] -> String = match {
        'A _ => "a",
        'B _ => "b",
      } in
      f ('C { x = 1 })
      # => error: tag not in the enum type
      ```
    "%
    = null,

  row_polymorphism_over_enums
    | doc m%"
      A function can accept the variants it knows and pass the rest through.

      ```nickel
      let port_of : forall a. [| 'http, 'ftp; a |] -> Number = match {
        'http => 80,
        'ftp => 21,
        _ => 8000,
      } in
      [port_of 'http, port_of 'gopher]
      # => [80, 8000]
      ```
    "%
    = null,

  # ---- why lib/ cannot use any of it ---------------------------------------

  a_static_type_cannot_be_named
    | doc m%"
      There is no type alias. Naming a record type moves it into the CONTRACT
      world, and the two are not compatible — so a static signature has to
      inline its whole structure, every time, and no two signatures can share
      one. That alone rules out a statically-typed library.

      This is a TYPECHECK-only error: at eval the alias is just a contract and
      passes, which is why the assertion below is `1` and the real claim lives
      in `typecheck/no-alias.ncl`. Note the shape of that trap — the mistake is
      invisible to `nickel test` and `nickel export` alike.

      ```nickel
      let Rec = { a : Number } in
      let f : Rec -> Number = fun r => r.a in
      f { a = 1 }
      # => 1
      ```
    "%
    = null,

  an_import_is_dyn_in_static_code
    | doc m%"
      The decisive one. A dashboard calls library functions with values from an
      imported dump, and an `import` is `Dyn` inside a static block — so the
      call does not typecheck (`typecheck/import-is-dyn.ncl`) and does not
      evaluate either, because the static type becomes a CLOSED record contract
      that the dump entity's extra fields violate.

      Four escape hatches were tried and all four fail: ascribing the value at
      the call site, ascribing the import binding, laundering it through a
      contract first, and a `_` wildcard. Static typing in Nickel is
      single-file, and a library is not a single file.

      ```nickel
      let home = import "home.ncl" in
      let f : { entity_id : String } -> String = fun e => e.entity_id in
      ({ out = f home.entities.light_plug } : { out : String }).out
      # => error: contract broken by the caller
      ```
    "%
    = null,

  merge_is_untyped
    | doc m%"
      `&` is typed `Dyn -> Dyn -> Dyn`, so the amend idiom the component library
      is built on is invisible to the typechecker. Even if the two problems
      above were solved, a statically-typed `fullWidth` could not be written
      with `&` — and `std.record.update` refuses a row-polymorphic record, so
      there is no typed way to extend a record of unknown shape.

      ```nickel
      let f : forall a. { cell : Number; a } -> { cell : Number; a }
        = fun node => node & { cell = 1 }
      in
      (f { cell = 0, card = "x" }).cell
      # => error
      ```
    "%
    = null,

  # ---- what the CONTRACT side gives you instead -----------------------------

  contracts_cross_module_boundaries
    | doc m%"
      The reason `lib/` is contracts: this is the same call that fails above,
      and it works.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      (c.toggle home.entities.light_plug).slots.entity_id
      # => "light.plug"
      ```
    "%
    = null,

  contracts_are_recursive_when_they_are_record_fields
    | doc m%"
      `core.Node.children | Array Node` refers to its own sibling. A `let` could
      not do this — `let` is not recursive — which is why `lib/core.ncl` is one
      record.

      ```nickel
      let core = import "lib/core.ncl" in
      let n | core.Node = { card = "a", children = [{ card = "b" }] } in
      (std.array.first n.children).card
      # => "b"
      ```
    "%
    = null,

  untagged_unions_work_only_when_the_alternatives_are_closed
    | doc m%"
      `std.contract.any_of` is the untagged union, and `lib/query/expr.ncl` uses
      it for `Literal` because a comparison's right-hand side must serialize as
      a bare scalar. With CLOSED alternatives it discriminates and says so:

      ```nickel
      let U = std.contract.any_of [{ a | Number }, { b | Number }] in
      let f | U -> String = fun x => std.serialize 'Json x in
      f { c = 1 }
      # => error: value didn't match any of the contracts
      ```
    "%
    = null,

  untagged_unions_break_on_open_alternatives
    | doc m%"
      …and entity contracts MUST be open, because a generated entity carries
      whatever attributes it has. Against open alternatives `any_of` commits to
      the FIRST one — the value structurally matches, since the fields are only
      checked when forced — and the eventual error blames that alternative's
      missing field instead of saying the value matched none of them.

      This is the concrete reason `c.slider` takes a tagged ADT rather than
      `any_of`: an entity union cannot be untagged and still report failure
      usefully.

      ```nickel
      let U = std.contract.any_of [{ a | Number, .. }, { b | Number, .. }] in
      let f | U -> String = fun x => std.serialize 'Json x in
      f { c = 1 }
      # => error: missing definition for `a`
      ```
    "%
    = null,

  contract_composition_needs_all_of_not_merge
    | doc m%"
      `RecordContract & customContract` looks right and type-checks, then fails
      with "non mergeable terms" the moment something forces it. `all_of` is the
      combinator that composes contracts.

      ```nickel
      let Positive = std.contract.from_predicate (fun x => x.n > 0) in
      let Ok = std.contract.all_of [{ n | Number }, Positive] in
      let v | Ok = { n = 1 } in
      v.n
      # => 1
      ```
    "%
    = null,
}
