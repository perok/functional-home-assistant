# The component library — slider, subsliders, button, toggle, layout.
# Analogue of src/test/pkl/components.test.pkl.
#
#   nickel test modules/fh-datastar-view/src/test/nickel/components.test.ncl
#
# Every component returns a PLACED node, `{ cell, body }`. That split is not
# decoration: `&` is untyped, so a statically typed layout helper cannot amend a
# node — it has to rebuild the concrete half and leave the body behind a type
# variable. See lib/core.ncl.
{
  slider_on_an_entity
    | doc m%"
      The slider reads its action/key/range from the domain spec, so the author
      names only the entity.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      (c.slider ('Entity home.entities.light_plug)).body.slots
      # => { action = "light/turn_on", entity_id = "light.plug", key = "brightness", max = "255", min = "1", value = "$attr.brightness" }
      ```
    "%
    = null,

  slider_on_a_narrowed_axis
    | doc m%"
      Handing the slider a colourTemp AXIS instead of the entity swaps the whole
      wire contract — different key, different range, range taken from the
      device. The union is a tagged ADT and the dispatch is a `match`, which the
      typechecker checks exhaustively.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      let s = (c.slider ('Axis home.entities.light_hue_bibliotek.colourTemp)).body in
      [s.slots.key, s.slots.min, s.slots.max]
      # => ["color_temp_kelvin", "2000", "6535"]
      ```
    "%
    = null,

  subsliders_are_children
    | doc m%"
      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      let g = c.sliderGroup ('Entity home.entities.light_plug) ['Entity home.entities.light_hue_bibliotek] in
      std.array.length g.body.children
      # => 1
      ```
    "%
    = null,

  button_and_toggle
    | doc m%"
      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      [(c.button "Close" "closePopup()").body.slots.label,
       (c.toggle home.entities.light_plug).body.slots.label]
      # => ["Close", "Plug"]
      ```
    "%
    = null,

  layout_helpers_rebuild_the_placement
    | doc m%"
      `fullWidth`/`columns` return a new placement and leave the body alone.

      ```nickel
      let c = import "lib/components.ncl" in
      let core = import "lib/core.ncl" in
      let home = import "home.ncl" in
      [(core.fullWidth (c.toggle home.entities.light_plug)).cell.classes,
       (core.columns 6 (c.toggle home.entities.light_plug)).cell.classes]
      # => [["fh-cols-full"], ["fh-cols-6"]]
      ```
    "%
    = null,

  one_layout_helper_covers_cards_and_query_sets
    | doc m%"
      The payoff of separating placement from body: `fullWidth` is polymorphic
      in the body, so the SAME function takes a component card and a query set
      node. The merge-based version needed a weaker open-record contract to do
      this, and could not be statically typed at all.

      ```nickel
      let core = import "lib/core.ncl" in
      let q = import "lib/query.ncl" in
      let home = import "home.ncl" in
      let set = q.pipe.from home.lights |> q.pipe.build in
      (core.fullWidth (core.place set)).cell.classes
      # => ["fh-cols-full"]
      ```
    "%
    = null,

  a_non_slidable_domain_is_an_error
    | doc m%"
      The runtime validation the type system cannot state: the domain has to be
      one the slider knows how to drive. Here it is the `sliderSpec` lookup that
      fails; `lib/hass.ncl` has the same check as an explicit contract, for use
      at a boundary. Pkl states it as a constrained type.

      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      c.slider ('Entity home.entities.sensor_kitchen_temp)
      # => error
      ```
    "%
    = null,

  an_untagged_argument_is_rejected_at_the_call
    | doc m%"
      ```nickel
      let c = import "lib/components.ncl" in
      let home = import "home.ncl" in
      c.slider home.entities.light_plug
      # => error
      ```
    "%
    = null,
}
