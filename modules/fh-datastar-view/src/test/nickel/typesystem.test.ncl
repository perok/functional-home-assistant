# What Nickel's type system can and cannot express — measured, not assumed.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/typesystem.test.ncl
#
# The short version: Nickel has two systems, static types (`:`) and contracts
# (`|`), and they do not mix — naming a record type moves it into the contract
# world. Static types are the stronger of the two AND they cross module
# boundaries (an annotated import carries its type), so a library should be
# written in them, with contracts kept for what they actually are: runtime
# validation of data the types cannot describe.
#
# `lib/` is now written in static types. `lib/hass.ncl` holds the one contract
# left, and it earns it: a domain predicate is a fact about a value, not a
# shape.
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

  # ---- the limits that survive -------------------------------------------

  a_static_type_cannot_be_named
    | doc m%"
      There is no type alias. Naming a record type moves it into the CONTRACT
      world, and the two are not compatible — so a static signature has to
      inline its whole structure, every time, and no two signatures can share
      one. Survivable — one module-level annotation block per module, plus row
      polymorphism for parameters — but it roughly doubled the library's size.

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

  an_UNANNOTATED_import_is_dyn
    | doc m%"
      An import carries a static type when the imported MODULE is annotated
      (`typecheck/import-carries-its-type.ncl`), and the typechecker then sees
      through both the library and the dump.

      The catch is subtler than "annotate the module": the annotation must be
      the OUTERMOST expression. `let x = … in ({…} : T)` — the natural way to
      put imports at the top — parses as the annotation being INSIDE the let,
      and the module's apparent type is `Dyn`. Nothing warns; only importers
      degrade, and they degrade to "not checked", which looks like success.
      See typecheck/module-annotation-must-be-outermost.ncl.

      Below, a closed record type meets an entity carrying extra fields — the
      eval-time face of the same boundary.

      ```nickel
      let home = import "home.ncl" in
      let f : { entity_id : String } -> String = fun e => e.entity_id in
      ({ out = f home.entities.light_plug } : { out : String }).out
      # => error: contract broken by the caller
      ```
    "%
    = null,

  the_documented_bridge_from_dyn_into_typed_code
    | doc m%"
      And the fix, straight from the manual: `exp | Type`, where Type is a
      record TYPE — fields with `:`, no values, no other metadata. A record
      CONTRACT in that position does NOT work; it stays opaque to the
      typechecker.

      So even an unannotated dump is usable from static code, at the cost of one
      ascription at the boundary (`typecheck/dyn-bridge.ncl` typechecks clean).

      ```nickel
      let home = import "home.ncl" in
      let f : { entity_id : String, domain : String, friendly_name : String } -> String
        = fun e => e.entity_id
      in
      f (home.entities.light_plug | { entity_id : String, domain : String, friendly_name : String })
      # => "light.plug"
      ```
    "%
    = null,

  row_polymorphism_absorbs_an_entity_s_extra_fields
    | doc m%"
      The reason a statically typed component library is practical at all: a
      parameter written `forall a. { entity_id : String; a }` accepts any entity
      that HAS those fields, whatever else it carries. Per-entity dump types and
      static component signatures fit together without inlining each entity's
      shape at every call.

      ```nickel
      let toggle : forall a. { entity_id : String; a } -> { card : String }
        = fun e => { card = "toggle" }
      in
      (toggle { entity_id = "light.plug", domain = "light", friendly_name = "Plug" }).card
      # => "toggle"
      ```
    "%
    = null,

  no_recursive_type_so_children_must_be_dyn
    | doc m%"
      The one place `Dyn` is genuinely forced rather than chosen. A layout tree
      is recursive — `children` holds more nodes — and a recursive TYPE cannot
      be written: there is no type alias, and `let` is not recursive, so the
      name is unbound (`typecheck/no-recursive-type.ncl`).

      A recursive CONTRACT is fine — as a record field, or with `let rec`, which
      also gives recursive ADTs. So the recursion is expressible; it is just
      invisible to the typechecker, which is the half that mattered.

      ```nickel
      let rec Node = { card | String, children | Array Node | default = [] } in
      let n | Node = { card = "a", children = [{ card = "b" }] } in
      (std.array.first n.children).card
      # => "b"
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

  recursive_adts_work_as_contracts
    | doc m%"
      `let rec` (shipped in nickel-lang/nickel#525) makes a recursive ADT
      contract straightforward. Recursive CONTRACTS are not the problem;
      recursive TYPES are, because there is no name to refer to.

      ```nickel
      let rec Tree = [| 'Leaf String, 'Branch { kids | Array Tree } |] in
      let t | Tree = 'Branch { kids = ['Leaf "x"] } in
      t |> match {
        'Branch b => std.array.first b.kids |> match { 'Leaf s => s, _ => "?" },
        'Leaf s => s,
      }
      # => "x"
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
