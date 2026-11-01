# The Nickel traps this spike actually hit, pinned as executable evidence.
# These are the honest answer to "is Nickel more straightforward than Pkl?" —
# it has the same class of variable-capture trap, just relocated, and it fires
# more often.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/traps.test.ncl
{
  field_equals_param_is_self_reference
    | doc m%"
      Records are RECURSIVE by default, so `label = label` inside one refers to
      the field, not the parameter. Hit four separate times while writing this
      directory — the single most repeated mistake in the spike.

      ```nickel
      let button = fun label => { card = "button", label = label } in
      (button "Close").label
      # => error: infinite recursion
      ```
    "%
    = null,

  include_is_the_fix
    | doc m%"
      ```nickel
      let button = fun label => { card = "button", include label } in
      (button "Close").label
      # => "Close"
      ```
    "%
    = null,

  punning_declares_an_undefined_field
    | doc m%"
      Worse than the above, because it does not fail here: `{ toggle }` declares
      an UNDEFINED field rather than referring to the outer binding, and shows
      up much later as a missing-definition error at whatever forces it.

      ```nickel
      let toggle = fun e => { card = "toggle" } in
      let lib = { toggle } in
      (lib.toggle {}).card
      # => error: missing definition
      ```
    "%
    = null,

  let_is_not_recursive
    | doc m%"
      Pkl's self-referencing `const e_x = new { colourTemp { owner = e_x } }` has
      no direct translation. This is why home.ncl is one big recursive RECORD
      rather than a sequence of lets.

      ```nickel
      let e = { entity_id = "light.x", owner = e } in
      e.owner.entity_id
      # => error: unbound identifier `e`
      ```
    "%
    = null,

  merge_is_not_amend
    | doc m%"
      `&` is SYMMETRIC and requires the two sides to agree; it does not
      override. So every field a helper may override has to be declared
      `| default` in the base, or use `std.record.update`. Pkl's amend just
      overrides.

      ```nickel
      let node = { cell = { classes = [] } } in
      (node & { cell.classes = ["fh-cols-full"] }).cell.classes
      # => error
      ```
    "%
    = null,

  default_makes_the_override_work
    | doc m%"
      ```nickel
      let node = { cell | default = { classes | Array String | default = [] } } in
      (node & { cell.classes = ["fh-cols-full"] }).cell.classes
      # => ["fh-cols-full"]
      ```
    "%
    = null,

  a_back_pointer_does_not_terminate_on_export
    | doc m%"
      The `owner` back-pointer in home.ncl is infinite when serialized (measured
      as a SIGKILL, not an error) until it is marked `not_exported` — Pkl's
      `hidden`. Anything cyclic that codegen emits needs the same treatment.

      ```nickel
      let home = import "home.ncl" in
      std.record.has_field "owner" home.entities.light_hue_bibliotek.colourTemp
      # => true
      ```
    "%
    = null,

  not_exported_is_honoured_by_export_but_not_by_serialize
    | doc m%"
      `nickel export home.ncl` terminates and omits `owner`. `std.serialize
      'Json home` on the same value DOES NOT TERMINATE (measured: timeout, then
      SIGKILL) — so `not_exported` is honoured by the export pipeline but not by
      the stdlib serializer.

      That makes the divergence untestable from inside a doctest, which is why
      this one only asserts the finite half. Check the other half by hand:

          nickel export home.ncl | grep -c owner   # 0
          echo 'std.serialize '"'"'Json (import "home.ncl")' | nickel export -I . --stdin   # hangs

      The lesson for codegen: a cyclic field needs `not_exported` AND nothing
      downstream may call `std.serialize` on the dump.

      ```nickel
      let home = import "home.ncl" in
      std.record.fields home.entities.light_hue_bibliotek.colourTemp
      # => ["max_kelvin", "min_kelvin", "owner"]
      ```
    "%
    = null,
}
