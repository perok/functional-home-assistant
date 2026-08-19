# The typing question this whole spike exists to answer: does Nickel give the
# per-entity capability typing Pkl gives, where the type is optional but the
# value is PROVEN, so an author can just use it?
#
#   nickel test modules/fh-datastar-view/src/test/nickel/hass.test.ncl
#
# The answer is yes, but only via the shape in home.ncl: codegen emits one
# contract PER ENTITY next to the entity, declaring exactly the fields that
# entity has. A single shared `LightEntity` contract does not do it.
{
  capability_needs_no_guard
    | doc m%"
      A light that HAS colour temperature: read it straight, no `!!`, no
      if-defined dance.

      ```nickel
      let home = import "home.ncl" in
      home.entities.light_hue_bibliotek.colourTemp.min_kelvin
      # => 2000
      ```
    "%
    = null,

  absent_capability_is_an_error
    | doc m%"
      A light that does NOT: reading it fails, and the message points at the
      record that lacks the field rather than at some nullable downstream.

      ```nickel
      let home = import "home.ncl" in
      home.entities.light_plug.colourTemp
      # => error: missing field
      ```
    "%
    = null,

  upcast_is_free
    | doc m%"
      Both lights still satisfy the shared, wide contract, so generic code over
      the whole list keeps working. This is the half a nominal class system
      would normally charge for.

      ```nickel
      let hass = import "lib/hass.ncl" in
      let home = import "home.ncl" in
      let wide | Array hass.LightEntity = home.lights in
      std.array.length wide
      # => 2
      ```
    "%
    = null,

  generic_code_over_the_wide_list
    | doc m%"
      ```nickel
      let home = import "home.ncl" in
      std.array.map (fun e => e.friendly_name) home.lights
      # => ["Bibliotek", "Plug"]
      ```
    "%
    = null,

  contract_rejects_a_missing_required_field
    | doc m%"
      Caught only once the record is FORCED (here by serializing it).

      ```nickel
      let hass = import "lib/hass.ncl" in
      let e | hass.Entity = { entity_id = "light.x", domain = "light" } in
      std.serialize 'Json e
      # => error: missing definition for `friendly_name`
      ```
    "%
    = null,

  a_missing_required_field_is_invisible_until_forced
    | doc m%"
      The same broken record, read field-wise, is FINE — laziness means a
      contract violation on one field does not fail a read of another. So "it
      evaluated" is not "it is valid"; only a full force (export, serialize) is.

      ```nickel
      let hass = import "lib/hass.ncl" in
      let e | hass.Entity = { entity_id = "light.x", domain = "light" } in
      e.entity_id
      # => "light.x"
      ```
    "%
    = null,

  entity_contracts_are_open
    | doc m%"
      Record contracts are CLOSED by default in Nickel, so every entity contract
      needs a trailing `..` or a generated entity carrying one extra attribute
      is rejected. This is a real codegen constraint, not a style note.

      ```nickel
      let hass = import "lib/hass.ncl" in
      let e | hass.Entity = {
        entity_id = "light.x", domain = "light", friendly_name = "X",
        some_attribute_codegen_added_later = 1,
      } in
      e.some_attribute_codegen_added_later
      # => 1
      ```
    "%
    = null,

  typecheck_does_not_catch_any_of_this
    | doc m%"
      Worth knowing before reading too much into a green `nickel typecheck`:
      contracts are checked at EVAL, not by the typechecker. `nickel typecheck`
      returned 0 on every error in this directory. Run `nickel test` / `nickel
      export`, never typecheck alone, if you want these caught.

      ```nickel
      let hass = import "lib/hass.ncl" in
      let unused | hass.Entity = { entity_id = 5 } in
      "the broken binding above is never forced"
      # => "the broken binding above is never forced"
      ```
    "%
    = null,
}
