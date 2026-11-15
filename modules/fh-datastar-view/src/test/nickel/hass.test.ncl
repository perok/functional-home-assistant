# The typing question this whole spike exists to answer: does Nickel give the
# per-entity capability typing Pkl gives, where the capability is optional but
# the value is PROVEN, so an author can just use it?
#
#   nickel test modules/fh-datastar-view/src/test/nickel/hass.test.ncl
#
# Yes — and better than the contract version, because a static per-entity TYPE
# is checked by `nickel typecheck` BEFORE evaluation. The compile-time half is
# in typecheck/dashboard-capability.ncl; this file pins the eval-time half.
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
      A light that does NOT. `nickel typecheck` rejects this before evaluation
      (`typecheck/dashboard-capability.ncl`), and eval rejects it too.

      ```nickel
      let home = import "home.ncl" in
      home.entities.light_plug.colourTemp
      # => error
      ```
    "%
    = null,

  the_house_wide_list_carries_the_common_projection
    | doc m%"
      An `Array` needs ONE element type, so `lights` holds what every light has.
      That is the right answer rather than a limitation: a query over every
      light should only see the common fields, and per-entity capabilities stay
      reachable through `entities.<name>`. It is the upcast Pkl gets from
      subtyping, written out because codegen writes it.

      ```nickel
      let home = import "home.ncl" in
      std.array.map (fun e => e.friendly_name) home.lights
      # => ["Bibliotek", "Plug"]
      ```
    "%
    = null,

  generic_code_over_the_wide_list
    | doc m%"
      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      std.array.map (fun e => (c.toggle e).body.slots.entity_id) home.lights
      # => ["light.hue_bibliotek", "light.plug"]
      ```
    "%
    = null,

  row_polymorphism_is_what_makes_this_compose
    | doc m%"
      Components take `forall a. { entity_id : String, …; a }`, so one signature
      accepts every entity type the dump generates, extra fields and all. Without
      it a component would have to name each entity's shape, which is impossible
      — Nickel has no type alias.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      [(c.toggle home.entities.light_hue_bibliotek).body.slots.label,
       (c.toggle home.entities.light_plug).body.slots.label]
      # => ["Bibliotek", "Plug"]
      ```
    "%
    = null,

  the_runtime_validation_that_is_left
    | doc m%"
      `lib/hass.ncl` is the only contract left in the library, and it earns its
      place: "this domain is one the slider can drive" is a fact about a value,
      not a shape, so no type can state it.

      ```nickel
      let hass = import "lib/hass.ncl" in
      let ok | hass.Slidable = { entity_id = "light.x", domain = "light" } in
      ok.domain
      # => "light"
      ```
    "%
    = null,

  the_runtime_validation_rejects
    | doc m%"
      ```nickel
      let hass = import "lib/hass.ncl" in
      let bad | hass.Slidable = { entity_id = "sensor.x", domain = "sensor" } in
      bad.domain
      # => error
      ```
    "%
    = null,
}
